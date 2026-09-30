package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

public final class NibbleBrushAccess {
    private NibbleBrushAccess() { }
    private static final ThreadLocal<Scratch> BUFFER = ThreadLocal.withInitial(Scratch::new);

    public static void apply(Dimension dimension, Layer layer, int ox, int oy, int width, int height,
                             float[] strengths, int mode) {
        if (width <= 0 || height <= 0 || (long) width * height != strengths.length
                || layer.dataSize != Layer.DataSize.NIBBLE || mode < 0 || mode > 2)
            throw new IllegalArgumentException("Invalid nibble brush region");
        boolean batch = layer.getDefaultValue() >= 0 && layer.getDefaultValue() <= 15
                && TileRegionAccess.canBatch(dimension, ox, oy, width, height);
        if (batch) for (float strength : strengths) if (strength < 0f || strength > 1f) { batch = false; break; }
        if (!batch) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int current = dimension.getLayerValueAt(layer, ox + x, oy + y);
                float strength = strengths[y * width + x];
                if (strength == 0f) continue;
                int target = target(mode, strength);
                if (mode == 0 ? target > current : target < current) dimension.setLayerValueAt(layer, ox + x, oy + y, target);
            }
            return;
        }
        for (int y = 0; y < height;) {
            int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(height - y, TILE_SIZE - ly);
            for (int x = 0; x < width;) {
                int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
                Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.editNibbleRegion(layer, lx, ly, rw, rh, strengths, y * width + x, width, mode);
                x += rw;
            }
            y += rh;
        }
    }

    static int target(int mode, float strength) {
        return mode == 0 ? 1 + Math.round(strength * 14) : mode == 1
                ? 14 - Math.round(strength * 14) : 14 - (int) (strength * 14 + 0f);
    }

    // ABI WLBP v1 : en-tête 48 octets, plan compacté de 8192 octets, forces row-major.
    static ByteBuffer edit(int x, int y, int width, int height, float[] strengths, int offset, int stride,
                           int mode, byte[] values, int defaultValue) {
        Scratch scratch = BUFFER.get(); ByteBuffer buffer = scratch.buffer;
        buffer.clear().limit(8240 + width * height * 4);
        buffer.putInt(0, 0x50424c57).putInt(4, 1).putInt(8, width).putInt(12, height)
                .putInt(16, x).putInt(20, y).putInt(24, mode).putInt(28, 0)
                .putInt(32, 0).putInt(36, 0).putInt(40, 0).putInt(44, 0);
        buffer.position(48);
        if (values != null) buffer.put(values);
        else for (int i = 0; i < 8192; i++) buffer.put((byte) (defaultValue | defaultValue << 4));
        scratch.strengths.clear().limit(width * height);
        for (int dy = 0; dy < height; dy++) scratch.strengths.put(strengths, offset + dy * stride, width);
        buffer.position(0);
        return NativeSlices.editNibbleBrush(buffer) ? buffer : null;
    }

    private static final class Scratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(73776).order(ByteOrder.LITTLE_ENDIAN);
        final FloatBuffer strengths = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(8240).asFloatBuffer();
    }
}
