package org.pepsoft.worldpainter;

import java.awt.Point;
import java.nio.*;
import java.util.*;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Remplissage progressif WLPF v2 : seul le front de tuiles reste en mémoire. */
public final class PaintFloodSession {
    private static final int AREA = 16384, SEEDS = 64 + AREA * 2;
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(SEEDS + AREA / 8).order(ByteOrder.LITTLE_ENDIAN));
    private final Dimension dimension;
    private final Layer layer;
    private final int bits, matching, target, mode, minX, minY, maxX, maxY;
    private final Map<Point, BitSet> pending = new HashMap<>();
    private final ArrayDeque<Point> queue = new ArrayDeque<>();
    private final Set<Point> closed = new HashSet<>();
    private final Set<Point> touched = new HashSet<>();
    private int nativeCalls;

    private PaintFloodSession(Dimension d, Layer layer, int bits, int matching, int target, int mode, int sx, int sy) {
        dimension = d; this.layer = layer; this.bits = bits; this.matching = matching; this.target = target; this.mode = mode;
        minX = d.getLowestX(); minY = d.getLowestY(); maxX = minX + d.getWidth() - 1; maxY = minY + d.getHeight() - 1;
        Point first = new Point(sx >> 7, sy >> 7); BitSet seeds = new BitSet(AREA);
        seeds.set((sx & 127) + (sy & 127) * 128); pending.put(first, seeds); queue.add(first);
    }

    /** Retourne null avant toute mutation si la dimension ou l'ABI ne sont pas prises en charge. */
    public static PaintFloodSession tryStart(Dimension d, int sx, int sy, Layer layer, Terrain terrain, int target, int mode) {
        if (d.getClass() != Dimension.class || !d.isEventsInhibited() || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || mode < 0 || mode > 2 || layer == null && terrain == null) return null;
        int bits = layer == null || layer.dataSize == Layer.DataSize.BYTE ? 8 : layer.dataSize == Layer.DataSize.NIBBLE ? 4
                : layer.dataSize == Layer.DataSize.BIT ? 1 : 0;
        if (layer == null) target = terrain.ordinal();
        if (bits == 0 || target < 0 || target >= 1 << bits || mode == 2 && target != 0
                || layer != null && (layer.getDefaultValue() < 0 || layer.getDefaultValue() >= 1 << bits)) return null;
        long ox = (long) d.getLowestX() * 128, oy = (long) d.getLowestY() * 128;
        long width = (long) d.getWidth() * 128, height = (long) d.getHeight() * 128;
        // Le découpage historique des mondes trop grands conserve son avertissement et son chemin Java.
        if (width <= 0 || height <= 0 || width > 46340 || height > 46340 || ox < Integer.MIN_VALUE || oy < Integer.MIN_VALUE
                || ox + width - 1 > Integer.MAX_VALUE || oy + height - 1 > Integer.MAX_VALUE
                || (long) sx - 23170 < Integer.MIN_VALUE || (long) sy - 23170 < Integer.MIN_VALUE
                || (long) sx + 23170 > Integer.MAX_VALUE || (long) sy + 23170 > Integer.MAX_VALUE
                || ox < (long) sx - 23170 || oy < (long) sy - 23170
                || ox + width > (long) sx + 23170 || oy + height > (long) sy + 23170) return null;
        for (Tile tile : d.getTiles()) if (tile.getClass() != Tile.class) return null;
        Tile first = d.getTile(sx >> 7, sy >> 7);
        if (first == null) return null;
        int matching = layer == null ? first.getTerrain(sx & 127, sy & 127).ordinal()
                : bits == 1 ? first.getBitLayerValue(layer, sx & 127, sy & 127) ? 1 : 0 : first.getLayerValue(layer, sx & 127, sy & 127);
        PaintFloodSession result = new PaintFloodSession(d, layer, bits, matching, target, mode, sx, sy);
        if (!result.process()) return null;
        return result;
    }

    public synchronized boolean isComplete() { return queue.isEmpty(); }
    public synchronized int getNativeCalls() { return nativeCalls; }
    public synchronized int getTouchedTiles() { return touched.size(); }

    /** Une étape est un seul appel JNI par visite de tuile, permettant une annulation entre les étapes. */
    public synchronized void advance() {
        if (!queue.isEmpty() && !dimension.isEventsInhibited()) throw new IllegalStateException("Inhibited dimension required");
        if (!queue.isEmpty() && !process()) throw new IndexOutOfBoundsException("Native paint fill failed after startup");
    }

    private boolean process() {
        Point point = queue.peek(); BitSet seeds = pending.get(point);
        Tile tile = dimension.getTile(point.x, point.y);
        ByteBuffer data = BUFFER.get(); data.clear();
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x46504c57).putInt(4, 2).putInt(8, 128).putInt(12, 128).putInt(16, matching)
                .putInt(24, mode).putInt(28, target).putInt(32, bits);
        if (tile == null) {
            int defaults = layer == null || bits == 1 ? 0 : layer.getDefaultValue();
            for (int i = 0; i < AREA; i++) data.put(64 + i, (byte) defaults).put(64 + AREA + i, (byte) (layer == null ? 0 : 1));
        } else tile.copyFloodPaint(data, 0, 128, AREA, layer);
        for (int i = 0; i < AREA / 8; i += 8) data.putLong(SEEDS + i, 0);
        for (int i = seeds.nextSetBit(0); i >= 0; i = seeds.nextSetBit(i + 1)) {
            int offset = SEEDS + i / 8; data.put(offset, (byte) (data.get(offset) | 1 << (i & 7)));
        }
        if (!NativeSlices.floodPaintRegion(data)) return false;
        nativeCalls++; queue.remove(); pending.remove(point);
        if (tile == null || data.getInt(40) == 0) closed.add(point);
        if (data.getInt(36) > 0) {
            if (tile != null) {
                dimension.getTileForEditing(point.x, point.y).applyFloodPaint(data, 0, 128, AREA, layer); touched.add(point);
            }
            spread(point.x - 1, point.y, data, 0); spread(point.x + 1, point.y, data, 1);
            spread(point.x, point.y - 1, data, 2); spread(point.x, point.y + 1, data, 3);
        }
        return true;
    }

    private void spread(int tx, int ty, ByteBuffer data, int edge) {
        if (tx < minX || ty < minY || tx > maxX || ty > maxY) return;
        Point point = new Point(tx, ty); if (closed.contains(point)) return;
        BitSet seeds = pending.get(point);
        for (int i = 0; i < 128; i++) {
            int cell = edge == 0 ? i * 128 : edge == 1 ? 127 + i * 128 : edge == 2 ? i : i + 127 * 128;
            if ((data.get(64 + AREA + cell) & 2) == 0) continue;
            if (seeds == null) { seeds = new BitSet(AREA); pending.put(point, seeds); queue.add(point); }
            int targetCell = edge == 0 ? 127 + i * 128 : edge == 1 ? i * 128 : edge == 2 ? i + 127 * 128 : i;
            seeds.set(targetCell);
        }
    }
}
