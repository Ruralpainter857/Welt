package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Objects;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

public final class MaskedPlaneAccess {
    private MaskedPlaneAccess() { }
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(32816).order(ByteOrder.LITTLE_ENDIAN));

    public static void applyLayer(Dimension dimension, Layer layer, int value, int ox, int oy,
                                  int width, int height, byte[] mask) {
        Objects.requireNonNull(layer);
        if (layer.dataSize != Layer.DataSize.BIT && layer.dataSize != Layer.DataSize.BIT_PER_CHUNK
                && layer.dataSize != Layer.DataSize.NIBBLE && layer.dataSize != Layer.DataSize.BYTE)
            throw new IllegalArgumentException("Unsupported masked layer");
        apply(dimension, layer, null, value, ox, oy, width, height, mask);
    }
    public static void applyTerrain(Dimension dimension, Terrain terrain, int ox, int oy,
                                    int width, int height, byte[] mask) {
        apply(dimension, null, Objects.requireNonNull(terrain), 0, ox, oy, width, height, mask);
    }
    private static void apply(Dimension dimension, Layer layer, Terrain terrain, int value, int ox, int oy,
                               int width, int height, byte[] mask) {
        if (width <= 0 || height <= 0 || (long) width * height != mask.length)
            throw new IllegalArgumentException("Expected one mask value per region cell");
        boolean bit = layer != null && (layer.dataSize == Layer.DataSize.BIT || layer.dataSize == Layer.DataSize.BIT_PER_CHUNK);
        boolean batch = TileRegionAccess.canBatch(dimension, ox, oy, width, height)
                && (layer == null || value >= 0 && value <= layer.dataSize.maxValue);
        if (!batch) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) if (mask[y * width + x] != 0) {
                if (layer == null) dimension.setTerrainAt(ox + x, oy + y, terrain);
                else if (bit) dimension.setBitLayerValueAt(layer, ox + x, oy + y, value != 0);
                else dimension.setLayerValueAt(layer, ox + x, oy + y, value);
            }
            return;
        }
        for (int y = 0; y < height;) {
            int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(height - y, TILE_SIZE - ly);
            for (int x = 0; x < width;) {
                int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
                boolean selected = false;
                for (int dy = 0; dy < rh && !selected; dy++) for (int dx = 0; dx < rw; dx++)
                    if (mask[(y + dy) * width + x + dx] != 0) { selected = true; break; }
                if (selected) {
                    Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.editMaskedRegion(layer, terrain, value, lx, ly, rw, rh, mask, y * width + x, width);
                }
                x += rw;
            }
            y += rh;
        }
    }

    // ABI WLBM v1 : en-tête 48 octets, plan compacté, masque row-major de la région.
    static ByteBuffer edit(int x, int y, int width, int height, int bits, int side, int value,
                            byte[] values, BitSet bitValues, int defaultValue, byte[] mask, int offset, int stride) {
        ByteBuffer buffer = BUFFER.get(); int bytes = side * side * bits / 8;
        buffer.clear().limit(48 + bytes + width * height);
        buffer.putInt(0, 0x4d424c57).putInt(4, 1).putInt(8, x).putInt(12, y).putInt(16, width).putInt(20, height)
                .putInt(24, bits).putInt(28, side).putInt(32, value).putInt(36, 0).putInt(40, 0).putInt(44, 0);
        buffer.position(48);
        if (values != null) buffer.put(values);
        else {
            int packed = bits == 4 ? defaultValue | defaultValue << 4 : defaultValue;
            for (int i = 0; i < bytes; i++) buffer.put((byte) packed);
            if (bitValues != null) for (int bit = bitValues.nextSetBit(0); bit >= 0; bit = bitValues.nextSetBit(bit + 1)) {
                if (bit >= side * side) return null;
                int b = 48 + bit / 8; buffer.put(b, (byte) (buffer.get(b) | (1 << (bit & 7))));
            }
        }
        buffer.position(48 + bytes);
        for (int dy = 0; dy < height; dy++) buffer.put(mask, offset + dy * stride, width);
        buffer.position(0);
        return NativeSlices.editMaskedPlane(buffer) ? buffer : null;
    }
}
