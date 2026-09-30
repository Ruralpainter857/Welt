package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.BitSet;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

final class LayerEditAccess {
    // ABI v1 : en-tête de 16 octets, descripteurs de 24 octets, puis plans compactés contigus.
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(40 + 16384).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<FluidScratch> FLUID_BUFFER = ThreadLocal.withInitial(FluidScratch::new);

    static ByteBuffer resetFluids(boolean tall, int rawLevel, boolean lava) {
        int waterBytes = 16384 * (tall ? 2 : 1);
        ByteBuffer buffer = FLUID_BUFFER.get().buffer;
        buffer.clear().limit(64 + waterBytes + 2048);
        buffer.putInt(0, 0x44454c57).putInt(4, 1).putInt(8, 2).putInt(12, 0);
        buffer.putInt(16, 128).putInt(20, tall ? 16 : 8).putInt(24, 2)
                .putInt(28, rawLevel & (tall ? 0xffff : 0xff)).putInt(32, 64).putInt(36, 0);
        buffer.putInt(40, 128).putInt(44, 1).putInt(48, 2).putInt(52, lava ? 1 : 0)
                .putInt(56, 64 + waterBytes).putInt(60, 0);
        return NativeSlices.editLayerPlanes(buffer) ? buffer : null;
    }

    static void copyFluidWater(ByteBuffer buffer, short[] destination) {
        FluidScratch scratch = FLUID_BUFFER.get();
        if (buffer != scratch.buffer) throw new IllegalArgumentException("Unexpected fluid buffer");
        scratch.water.clear().limit(destination.length);
        scratch.water.get(destination);
    }

    private static final class FluidScratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(64 + 32768 + 2048).order(ByteOrder.LITTLE_ENDIAN);
        final ShortBuffer water = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(64).asShortBuffer();
    }

    static ByteBuffer edit(Layer layer, boolean invert, int minimum, byte[] values, BitSet bits) {
        int width = layer.dataSize == Layer.DataSize.BIT || layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 1
                : layer.dataSize == Layer.DataSize.NIBBLE ? 4 : layer.dataSize == Layer.DataSize.BYTE ? 8 : 0;
        int side = layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 8 : 128;
        int bytes = side * side * width / 8;
        if (width == 0 || minimum < 0 || minimum >= (1 << width)
                || (bits != null && bits.length() > side * side)
                || (values != null && values.length != bytes)) return null;
        ByteBuffer buffer = BUFFER.get();
        buffer.clear().limit(40 + bytes);
        buffer.putInt(0, 0x44454c57).putInt(4, 1).putInt(8, 1).putInt(12, 0);
        buffer.putInt(16, side).putInt(20, width).putInt(24, invert ? 0 : 1)
                .putInt(28, minimum).putInt(32, 40).putInt(36, 0);
        if (values != null) { buffer.position(40); buffer.put(values); }
        else {
            int defaultByte = width == 1 ? 0 : layer.getDefaultValue();
            if (width == 4) defaultByte |= defaultByte << 4;
            for (int i = 0; i < bytes; i++) buffer.put(40 + i, (byte) defaultByte);
            if (bits != null) for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) {
                int offset = 40 + bit / 8;
                buffer.put(offset, (byte) (buffer.get(offset) | (1 << (bit & 7))));
            }
        }
        buffer.position(0);
        return NativeSlices.editLayerPlanes(buffer) ? buffer : null;
    }
}
