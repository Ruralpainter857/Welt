package org.pepsoft.worldpainter;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.util.*;

/** Frontière de tuiles partagée : les entrées voisines sont regroupées avant chaque visite. */
final class TileFloodFrontier {
    private final int minX, minY, maxX, maxY;
    private final Map<Point, BitSet> pending = new HashMap<>();
    private final ArrayDeque<Point> queue = new ArrayDeque<>();
    private final Set<Point> closed = new HashSet<>(), touched = new HashSet<>();
    private int calls;

    TileFloodFrontier(Dimension d, int sx, int sy) {
        minX = d.getLowestX(); minY = d.getLowestY(); maxX = minX + d.getWidth() - 1; maxY = minY + d.getHeight() - 1;
        Point first = new Point(sx >> 7, sy >> 7); BitSet seeds = new BitSet(16384);
        seeds.set((sx & 127) + (sy & 127) * 128); pending.put(first, seeds); queue.add(first);
    }
    boolean isComplete() { return queue.isEmpty(); }
    int getCalls() { return calls; }
    int getTouched() { return touched.size(); }
    Point first() { return queue.peek(); }
    void copySeeds(ByteBuffer data, int offset) {
        for (int i = 0; i < 2048; i += 8) data.putLong(offset + i, 0);
        BitSet seeds = pending.get(first());
        for (int i = seeds.nextSetBit(0); i >= 0; i = seeds.nextSetBit(i + 1)) {
            int at = offset + i / 8; data.put(at, (byte) (data.get(at) | 1 << (i & 7)));
        }
    }
    void finish(ByteBuffer data, int flags, int visited, boolean complete, boolean modified) {
        Point point = queue.remove(); pending.remove(point); calls++;
        if (complete) closed.add(point);
        if (modified) touched.add(point);
        if (data.getInt(36) > 0) {
            spread(point.x - 1, point.y, data, flags, visited, 0);
            spread(point.x + 1, point.y, data, flags, visited, 1);
            spread(point.x, point.y - 1, data, flags, visited, 2);
            spread(point.x, point.y + 1, data, flags, visited, 3);
        }
    }
    private void spread(int tx, int ty, ByteBuffer data, int flags, int visited, int edge) {
        if (tx < minX || ty < minY || tx > maxX || ty > maxY) return;
        Point point = new Point(tx, ty); if (closed.contains(point)) return;
        BitSet seeds = pending.get(point);
        for (int i = 0; i < 128; i++) {
            int cell = edge == 0 ? i * 128 : edge == 1 ? 127 + i * 128 : edge == 2 ? i : i + 127 * 128;
            if ((data.get(flags + cell) & visited) == 0) continue;
            if (seeds == null) { seeds = new BitSet(16384); pending.put(point, seeds); queue.add(point); }
            int target = edge == 0 ? 127 + i * 128 : edge == 1 ? i * 128 : edge == 2 ? i + 127 * 128 : i;
            seeds.set(target);
        }
    }
}
