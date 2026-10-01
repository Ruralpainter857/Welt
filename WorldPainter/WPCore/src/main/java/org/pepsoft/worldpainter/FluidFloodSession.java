package org.pepsoft.worldpainter;

import java.awt.Point;
import java.nio.*;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** WLFD v2 : eau, lave et hauteurs dans un tampon de tuile réutilisé par worker. */
public final class FluidFloodSession {
    private static final int AREA = 16384, SEEDS = 64 + AREA * 10;
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(SEEDS + AREA / 8).order(ByteOrder.LITTLE_ENDIAN));
    private final Dimension dimension;
    private final TileFloodFrontier frontier;
    private final int mode, level;
    private final boolean lava;

    private FluidFloodSession(Dimension d, int sx, int sy, int mode, int level, boolean lava) {
        dimension = d; this.mode = mode; this.level = level; this.lava = lava;
        frontier = new TileFloodFrontier(d, sx, sy);
    }
    /** Les cas non couverts restent en Java, avant toute écriture. */
    public static FluidFloodSession tryStart(Dimension d, int sx, int sy, boolean inverse, boolean lava) {
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
        int terrain = d.getIntHeightAt(sx, sy), water = d.getWaterLevelAt(sx, sy);
        if (terrain == Integer.MIN_VALUE || inverse && water <= terrain) return null;
        boolean convert = water > terrain && lava != d.getBitLayerValueAt(FloodWithLava.INSTANCE, sx, sy);
        int base = Math.max(terrain, water);
        if (!convert && (inverse ? base <= d.getMinHeight() : base >= d.getMaxHeight() - 1)) return null;
        int level = inverse ? base - 1 : base + 1;
        // Un niveau qui serait tronqué par le stockage Java ne ferme pas la frontière : repli sûr.
        if (!convert) for (Tile tile : d.getTiles()) {
            long raw = (long) level - tile.getMinHeight();
            if (raw < 0 || raw > (tile.getMaxHeight() - tile.getMinHeight() > 256 ? 65535 : 255)) return null;
        }
        FluidFloodSession result = new FluidFloodSession(d, sx, sy, convert ? 2 : inverse ? 1 : 0, level, lava);
        return result.process() ? result : null;
    }
    public synchronized boolean isComplete() { return frontier.isComplete(); }
    public synchronized int getNativeCalls() { return frontier.getCalls(); }
    public synchronized int getTouchedTiles() { return frontier.getTouched(); }
    /** Le tampon n'est pas conservé entre étapes : reprise possible sur un autre worker. */
    public synchronized void advance() {
        if (!frontier.isComplete() && !dimension.isEventsInhibited()) throw new IllegalStateException("Inhibited dimension required");
        if (!frontier.isComplete() && !process()) throw new IndexOutOfBoundsException("Native fluid fill failed after startup");
    }
    private boolean process() {
        Point point = frontier.first(); Tile tile = dimension.getTile(point.x, point.y);
        ByteBuffer data = BUFFER.get(); data.clear();
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x44464c57).putInt(4, 2).putInt(8, 128).putInt(12, 128)
                .putInt(24, mode).putInt(28, level).putInt(32, lava ? 1 : 0);
        if (tile == null) {
            for (int i = 0; i < AREA; i++) data.putInt(64 + i * 4, Integer.MIN_VALUE)
                    .putInt(64 + AREA * 4 + i * 4, Integer.MIN_VALUE).put(64 + AREA * 8 + i, (byte) 0);
        } else tile.copyFluidFlood(data, 0, 128, AREA);
        frontier.copySeeds(data, SEEDS);
        if (!NativeSlices.floodFluidRegion(data)) return false;
        if (data.getInt(36) > 0 && tile != null)
            dimension.getTileForEditing(point.x, point.y).applyFluidFlood(data, 0, 128, AREA, mode);
        frontier.finish(data, 64 + AREA * 9, 1, tile == null || data.getInt(40) == 0, tile != null && data.getInt(36) > 0);
        return true;
    }
}
