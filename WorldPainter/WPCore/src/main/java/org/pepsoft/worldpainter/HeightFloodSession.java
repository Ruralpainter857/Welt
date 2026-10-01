package org.pepsoft.worldpainter;

import java.awt.Point;
import java.nio.*;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** WLFH v1/v2 : relief, peinture et frontière dans un tampon de tuile réutilisé par worker. */
public final class HeightFloodSession {
    private static final int AREA = 16384;
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(64 + AREA * 6 + AREA / 8).order(ByteOrder.LITTLE_ENDIAN));
    private final Dimension dimension;
    private final TileFloodFrontier frontier;
    private final int level, target, mode, bits;
    private final boolean painted;
    private final Layer layer;

    private HeightFloodSession(Dimension d, int sx, int sy, int level, boolean painted, Layer layer, int target, int mode, int bits) {
        dimension = d; this.level = level; this.painted = painted; this.layer = layer; this.target = target; this.mode = mode; this.bits = bits;
        frontier = new TileFloodFrontier(d, sx, sy);
    }
    /** Les cas non couverts restent en Java, avant toute écriture. */
    public static HeightFloodSession tryStart(Dimension d, int sx, int sy) {
        return start(d, sx, sy, false, null, 0, 0, 0);
    }

    /** La peinture est déterministe et indépendante de l'ordre des mutations du relief. */
    public static HeightFloodSession tryStart(Dimension d, int sx, int sy, Layer layer, Terrain terrain, int target, int mode) {
        int bits = layer == null || layer.dataSize == Layer.DataSize.BYTE ? 8 : layer.dataSize == Layer.DataSize.NIBBLE ? 4
                : layer.dataSize == Layer.DataSize.BIT ? 1 : 0;
        if (layer == null) { if (terrain == null) return null; target = terrain.ordinal(); }
        if (bits == 0 || mode < 0 || mode > 1 || target < 0 || target >= 1 << bits
                || layer != null && (layer.getDefaultValue() < 0 || layer.getDefaultValue() >= 1 << bits)) return null;
        return start(d, sx, sy, true, layer, target, mode, bits);
    }

    private static HeightFloodSession start(Dimension d, int sx, int sy, boolean painted, Layer layer, int target, int mode, int bits) {
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
        HeightFloodSession result = new HeightFloodSession(d, sx, sy, level, painted, layer, target, mode, bits);
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
        int seeds = 64 + AREA * (painted ? 6 : 5);
        ByteBuffer data = BUFFER.get(); data.clear().limit(seeds + AREA / 8);
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x48464c57).putInt(4, painted ? 2 : 1).putInt(8, 128).putInt(12, 128).putInt(16, level);
        if (painted) data.putInt(20, target).putInt(24, mode).putInt(28, bits);
        if (tile == null) {
            for (int i = 0; i < AREA; i++) {
                data.putInt(64 + i * 4, Integer.MIN_VALUE);
                if (painted) data.put(64 + AREA * 4 + i, (byte) (layer == null || bits == 1 ? 0 : layer.getDefaultValue()));
            }
        } else if (painted) tile.copyHeightPaintFlood(data, layer); else tile.copyHeightFlood(data);
        frontier.copySeeds(data, seeds);
        if (!NativeSlices.floodHeightRegion(data)) return false;
        if (data.getInt(36) > 0 && tile != null) {
            Tile editing = dimension.getTileForEditing(point.x, point.y);
            if (painted) editing.applyHeightPaintFlood(data, level, layer); else editing.applyHeightFlood(data, level);
        }
        frontier.finish(data, 64 + AREA * (painted ? 5 : 4), 1, tile == null || data.getInt(40) == 0, tile != null && data.getInt(36) > 0);
        return true;
    }
}
