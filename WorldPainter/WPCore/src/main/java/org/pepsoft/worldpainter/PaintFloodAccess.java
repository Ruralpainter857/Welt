package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** WLPF v1 : valeurs et présence réutilisées pour l'exploration et l'application groupées. */
public final class PaintFloodAccess {
    private PaintFloodAccess() { }
    public static final int EQUAL = 0, RAISE = 1, ERASE = 2;
    private static final ThreadLocal<ByteBuffer> BUFFERS = new ThreadLocal<>();
    public static boolean tryFill(Dimension dimension, int sx, int sy, Layer layer, Terrain terrain, int target, int mode) {
        if (mode < 0 || mode > 2 || layer == null && terrain == null) return false;
        int bits = layer == null || layer.dataSize == Layer.DataSize.BYTE ? 8
                : layer.dataSize == Layer.DataSize.NIBBLE ? 4 : layer.dataSize == Layer.DataSize.BIT ? 1 : 0;
        if (layer == null) target = terrain.ordinal();
        if (bits == 0 || target < 0 || target >= 1 << bits || mode == ERASE && target != 0
                || layer != null && (layer.getDefaultValue() < 0 || layer.getDefaultValue() >= 1 << bits)) return false;
        long w = (long) dimension.getWidth() * 128, h = (long) dimension.getHeight() * 128;
        long ox = (long) dimension.getLowestX() * 128, oy = (long) dimension.getLowestY() * 128;
        if (w <= 0 || h <= 0 || w > 65536 || h > 65536 || w * h > 65536 || ox < Integer.MIN_VALUE || oy < Integer.MIN_VALUE
                || ox + w - 1 > Integer.MAX_VALUE || oy + h - 1 > Integer.MAX_VALUE
                || sx < ox || sy < oy || sx >= ox + w || sy >= oy + h
                || !TileRegionAccess.canBatch(dimension, (int) ox, (int) oy, (int) w, (int) h)) return false;
        int width = (int) w, area = width * (int) h, length = 64 + area * 2;
        ByteBuffer data = BUFFERS.get();
        if (data == null || data.capacity() < length) { data = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN); BUFFERS.set(data); }
        data.clear().limit(length);
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x46504c57).putInt(4, 1).putInt(8, width).putInt(12, (int) h).putInt(16, sx - (int) ox)
                .putInt(20, sy - (int) oy).putInt(24, mode).putInt(28, target).putInt(32, bits);
        // Les couches traversent aussi les trous à leur valeur par défaut, comme Dimension.getLayerValueAt.
        int defaults = layer == null || bits == 1 ? 0 : layer.getDefaultValue();
        for (int i = 0; i < area; i++) data.put(64 + i, (byte) defaults).put(64 + area + i, (byte) (layer == null ? 0 : 1));
        for (Tile tile : dimension.getTiles()) tile.copyFloodPaint(data, (tile.getX() * 128 - (int) ox) + (tile.getY() * 128 - (int) oy) * width, width, area, layer);
        if (!NativeSlices.floodPaintRegion(data)) return false;
        for (Tile tile : dimension.getTiles()) {
            int offset = (tile.getX() * 128 - (int) ox) + (tile.getY() * 128 - (int) oy) * width;
            boolean touched = false;
            for (int y = 0; y < 128 && !touched; y++) for (int x = 0; x < 128; x++) {
                if ((data.get(64 + area + offset + x + y * width) & 2) != 0) { touched = true; break; }
            }
            if (touched) dimension.getTileForEditing(tile.getX(), tile.getY()).applyFloodPaint(data, offset, width, area, layer);
        }
        return true;
    }
}
