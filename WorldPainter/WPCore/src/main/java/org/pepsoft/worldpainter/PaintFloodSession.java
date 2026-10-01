package org.pepsoft.worldpainter;

import java.awt.Point;
import java.nio.*;
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
    private final int bits, matching, target, mode;
    private final TileFloodFrontier frontier;

    private PaintFloodSession(Dimension d, Layer layer, int bits, int matching, int target, int mode, int sx, int sy) {
        dimension = d; this.layer = layer; this.bits = bits; this.matching = matching; this.target = target; this.mode = mode;
        frontier = new TileFloodFrontier(d, sx, sy);
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

    public synchronized boolean isComplete() { return frontier.isComplete(); }
    public synchronized int getNativeCalls() { return frontier.getCalls(); }
    public synchronized int getTouchedTiles() { return frontier.getTouched(); }

    /** Une étape est un seul appel JNI par visite de tuile, permettant une annulation entre les étapes. */
    public synchronized void advance() {
        if (!frontier.isComplete() && !dimension.isEventsInhibited()) throw new IllegalStateException("Inhibited dimension required");
        if (!frontier.isComplete() && !process()) throw new IndexOutOfBoundsException("Native paint fill failed after startup");
    }

    private boolean process() {
        Point point = frontier.first();
        Tile tile = dimension.getTile(point.x, point.y);
        ByteBuffer data = BUFFER.get(); data.clear();
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x46504c57).putInt(4, 2).putInt(8, 128).putInt(12, 128).putInt(16, matching)
                .putInt(24, mode).putInt(28, target).putInt(32, bits);
        if (tile == null) {
            int defaults = layer == null || bits == 1 ? 0 : layer.getDefaultValue();
            for (int i = 0; i < AREA; i++) data.put(64 + i, (byte) defaults).put(64 + AREA + i, (byte) (layer == null ? 0 : 1));
        } else tile.copyFloodPaint(data, 0, 128, AREA, layer);
        frontier.copySeeds(data, SEEDS);
        if (!NativeSlices.floodPaintRegion(data)) return false;
        if (data.getInt(36) > 0 && tile != null)
            dimension.getTileForEditing(point.x, point.y).applyFloodPaint(data, 0, 128, AREA, layer);
        frontier.finish(data, 64 + AREA, 2, tile == null || data.getInt(40) == 0, tile != null && data.getInt(36) > 0);
        return true;
    }
}
