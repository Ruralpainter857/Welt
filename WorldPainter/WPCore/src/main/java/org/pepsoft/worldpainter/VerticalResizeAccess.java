package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VerticalResizeAccess {
    static final int BYTES = 48 + 16384 * 8;
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN));

    static ByteBuffer prepare(int oldMin, int newMin, int newMax, boolean oldTall,
                              boolean newTall, HeightTransform transform) {
        ByteBuffer buffer = BUFFER.get();
        buffer.clear();
        buffer.putInt(0, 0x56525357).putInt(4, 1).putInt(8, oldMin).putInt(12, newMin)
                .putInt(16, newMax).putInt(20, oldTall ? 1 : 0).putInt(24, newTall ? 1 : 0)
                .putFloat(28, transform.getScalingFactor()).putInt(32, transform.getTranslateAmount())
                .putInt(36, transform.isIdentity() ? 1 : 0).putInt(40, 0).putInt(44, 0);
        return buffer;
    }

    static boolean resize(ByteBuffer buffer) { return NativeSlices.resizeVerticalTile(buffer); }
}
