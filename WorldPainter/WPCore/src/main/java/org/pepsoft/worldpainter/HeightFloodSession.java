package org.pepsoft.worldpainter;

import java.awt.Point;
import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** WLFH v1 : hauteurs et frontière dans un tampon de tuile réutilisé par worker. */
public final class HeightFloodSession {
    private static final int AREA = 16384, SEEDS = 64 + AREA * 5;
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(SEEDS + AREA / 8).order(ByteOrder.LITTLE_ENDIAN));
    private final Dimension dimension;
    private final TileFloodFrontier frontier;
    private final int level;

    private HeightFloodSession(Dimension d, int sx, int sy, int level) {
        dimension = d; this.level = level;
        frontier = new TileFloodFrontier(d, sx, sy);
    }
    /** Les cas non couverts restent en Java, avant toute écriture. */
    public static HeightFloodSession tryStart(Dimension d, int sx, int sy) {
        if (d.getClass() != Dimension.class || !d.isEventsInhibited() || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return null;
        long ox = (long) d.getLowestX() * 128, oy = (long) d.getLowestY() * 128;
        long width = (long) d.getWidth() * 128, height = (long) d.getHeight() * 128;
        // Le recadrage historique et son avertissement restent traités par Java.
        if (width <= 0 || height <= 0 || width > 46340 || height > 46340 || ox < Integer.MIN_VALUE || oy < Integer.MIN_VALUE
                || ox + width - 1 > Integer.MAX_VALUE || oy + height - 1 > Integer.MAX_VALUE
                || (long) sx - 23170 < Integer.MIN_VALUE || (long) sy - 23170 < Integer.MIN_VALUE
                || (long) sx + 23170 > Integer.MAX_VALUE || (long) sy + 23170 > Integer.MAX_VALUE
                || ox < (long) sx - 23170 || oy < (long) sy - 23170
                || ox + width > (long) sx + 23170 || oy + height > (long) sy + 23170) return null;
        for (Tile tile : d.getTiles()) if (tile.getClass() != Tile.class) return null;
        int current = d.getIntHeightAt(sx, sy);
        if (current == Integer.MIN_VALUE || current >= d.getMaxHeight() - 1) return null;
        int level = current + 1;
        // Le stockage doit rendre le bloc modifié frontalier, même après sa conversion en flottant.
        for (Tile tile : d.getTiles()) {
            int raw = (int) (((float) level - tile.getMinHeight()) * 256f);
            boolean tall = tile.getMaxHeight() - tile.getMinHeight() > 256;
            if (raw < 0 || !tall && raw > 65535
                    || Math.round(raw / 256f + tile.getMinHeight()) < level) return null;
        }
        HeightFloodSession result = new HeightFloodSession(d, sx, sy, level);
        return result.process() ? result : null;
    }
    public synchronized boolean isComplete() { return frontier.isComplete(); }
    public synchronized int getNativeCalls() { return frontier.getCalls(); }
    public synchronized int getTouchedTiles() { return frontier.getTouched(); }
    /** Le tampon n'est pas conservé entre étapes : reprise possible sur un autre worker. */
    public synchronized void advance() {
        if (!frontier.isComplete() && !dimension.isEventsInhibited()) throw new IllegalStateException("Inhibited dimension required");
        if (!frontier.isComplete() && !process()) throw new IndexOutOfBoundsException("Native height fill failed after startup");
    }
    private boolean process() {
        Point point = frontier.first(); Tile tile = dimension.getTile(point.x, point.y);
        ByteBuffer data = BUFFER.get(); data.clear();
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x48464c57).putInt(4, 1).putInt(8, 128).putInt(12, 128).putInt(16, level);
        if (tile == null) {
            for (int i = 0; i < AREA; i++) data.putInt(64 + i * 4, Integer.MIN_VALUE);
        } else tile.copyHeightFlood(data);
        frontier.copySeeds(data, SEEDS);
        if (!NativeSlices.floodHeightRegion(data)) return false;
        if (data.getInt(36) > 0 && tile != null)
            dimension.getTileForEditing(point.x, point.y).applyHeightFlood(data, level);
        frontier.finish(data, 64 + AREA * 4, 1, tile == null || data.getInt(40) == 0, tile != null && data.getInt(36) > 0);
        return true;
    }
}
