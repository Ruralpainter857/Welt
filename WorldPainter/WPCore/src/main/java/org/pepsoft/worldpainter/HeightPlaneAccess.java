package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

final class HeightPlaneAccess {
    // ABI v1 : en-tête de 40 octets, puis hauteurs brutes 16 ou 32 bits, en little endian.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    static Scratch edit(int minHeight, int minClamp, int maxClamp, TerrainHeightOperation operation,
                        float value, short[] shortHeights, int[] tallHeights) {
        Scratch scratch = SCRATCH.get();
        boolean tall = tallHeights != null;
        ByteBuffer buffer = scratch.buffer;
        buffer.clear().limit(40 + 16384 * (tall ? 4 : 2));
        buffer.putInt(0, 0x44454857).putInt(4, 1).putInt(8, minHeight).putInt(12, minClamp)
                .putInt(16, maxClamp).putInt(20, tall ? 32 : 16).putInt(24, operation.wireCode)
                .putFloat(28, value).putInt(32, 0).putInt(36, 0);
        if (tall) { scratch.ints.clear(); scratch.ints.put(tallHeights); }
        else { scratch.shorts.clear().limit(16384); scratch.shorts.put(shortHeights); }
        return NativeSlices.editHeightPlane(buffer) ? scratch : null;
    }

    static final class Scratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(40 + 65536).order(ByteOrder.LITTLE_ENDIAN);
        final ShortBuffer shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(40).asShortBuffer();
        final IntBuffer ints = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(40).asIntBuffer();
        void copy(short[] destination) { shorts.clear().limit(16384); shorts.get(destination); }
        void copy(int[] destination) { ints.clear(); ints.get(destination); }
    }
}
