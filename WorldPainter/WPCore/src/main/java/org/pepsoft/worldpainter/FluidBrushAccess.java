package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.BitSet;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

public final class FluidBrushAccess {
    private FluidBrushAccess() { }
    private static final ThreadLocal<Scratch> BUFFER = ThreadLocal.withInitial(Scratch::new);

    public static void apply(Dimension dimension, int ox, int oy, int width, int height,
                             float[] strengths, boolean reset, int waterHeight) {
        if (width <= 0 || height <= 0 || (long) width * height != strengths.length)
            throw new IllegalArgumentException("Expected one strength per region cell");
        if (reset && waterHeight == -1) return;
        boolean batch = dimension.getClass() == Dimension.class && dimension.isEventsInhibited()
                && Native.isGenEnabled() && NativeLoader.areSlicesAvailable();
        // Les sous-classes peuvent observer l'ordre global des setters : conserver ce chemin intégralement.
        if (batch) for (int x = 0; x < width && batch;) {
            int wx = ox + x, rw = Math.min(width - x, TILE_SIZE - (wx & TILE_SIZE_MASK));
            for (int y = 0; y < height;) {
                int wy = oy + y, rh = Math.min(height - y, TILE_SIZE - (wy & TILE_SIZE_MASK));
                Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null && tile.getClass() != Tile.class) { batch = false; break; }
                y += rh;
            }
            x += rw;
        }
        int level = reset ? waterHeight : dimension.getMinHeight();
        if (!batch) {
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) if (strengths[x * height + y] != 0f) {
                dimension.setWaterLevelAt(ox + x, oy + y, level);
                if (reset) dimension.setBitLayerValueAt(FloodWithLava.INSTANCE, ox + x, oy + y, false);
            }
            return;
        }
        for (int x = 0; x < width;) {
            int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
            for (int y = 0; y < height;) {
                int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(height - y, TILE_SIZE - ly);
                boolean touched = false;
                for (int dx = 0; dx < rw && !touched; dx++) for (int dy = 0; dy < rh; dy++)
                    if (strengths[(x + dx) * height + y + dy] != 0f) { touched = true; break; }
                if (touched) {
                    Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.editFluidRegion(lx, ly, rw, rh, strengths, x * height + y, height, reset, level);
                }
                y += rh;
            }
            x += rw;
        }
    }

    // ABI WLFB v1 : en-tête 64 octets, forces X-major, eau row-major, lave de la tuile entière.
    static Scratch edit(int x, int y, int width, int height, float[] strengths, int offset, int stride,
                        int rawLevel, boolean reset, byte[] water, short[] tallWater, BitSet lava) {
        Scratch scratch = BUFFER.get(); ByteBuffer buffer = scratch.buffer;
        int bits = tallWater != null ? 16 : 8, waterOffset = 64 + width * height * 4;
        int lavaOffset = waterOffset + width * height * bits / 8;
        buffer.clear().limit(lavaOffset + 2048);
        buffer.putInt(0, 0x42464c57).putInt(4, 1).putInt(8, width).putInt(12, height)
                .putInt(16, x).putInt(20, y).putInt(24, bits).putInt(28, reset ? 1 : 0)
                .putInt(32, rawLevel).putInt(36, 0).putInt(40, 0).putInt(44, waterOffset)
                .putInt(48, lavaOffset).putInt(52, 0).putInt(56, 0).putInt(60, 0);
        scratch.strengths.clear().limit(width * height);
        for (int dx = 0; dx < width; dx++) scratch.strengths.put(strengths, offset + dx * stride, height);
        if (tallWater != null) {
            scratch.shorts.clear().position(waterOffset / 2);
            for (int dy = 0; dy < height; dy++) scratch.shorts.put(tallWater, x + ((y + dy) << TILE_SIZE_BITS), width);
        } else {
            buffer.position(waterOffset);
            for (int dy = 0; dy < height; dy++) buffer.put(water, x + ((y + dy) << TILE_SIZE_BITS), width);
        }
        if (reset) {
            for (int i = 0; i < 2048; i++) buffer.put(lavaOffset + i, (byte) 0);
            if (lava != null) for (int bit = lava.nextSetBit(0); bit >= 0; bit = lava.nextSetBit(bit + 1)) {
                if (bit >= 16384) return null;
                int b = lavaOffset + bit / 8;
                buffer.put(b, (byte) (buffer.get(b) | (1 << (bit & 7))));
            }
        }
        buffer.position(0);
        return NativeSlices.editFluidBrush(buffer) ? scratch : null;
    }

    static final class Scratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(100416).order(ByteOrder.LITTLE_ENDIAN);
        final FloatBuffer strengths = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(64).limit(64 + 65536).asFloatBuffer();
        final ShortBuffer shorts = buffer.asShortBuffer();

        void copyWater(int x, int y, int width, int height, byte[] water, short[] tallWater) {
            int offset = buffer.getInt(44);
            if (tallWater != null) {
                shorts.clear().position(offset / 2);
                for (int dy = 0; dy < height; dy++) shorts.get(tallWater, x + ((y + dy) << TILE_SIZE_BITS), width);
            } else {
                buffer.position(offset);
                for (int dy = 0; dy < height; dy++) buffer.get(water, x + ((y + dy) << TILE_SIZE_BITS), width);
            }
        }
    }
}
