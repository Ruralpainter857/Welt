package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable WTGP v1/v2 snapshot; intermediate source planes remain in Rust.
 * V2 mode 4 uses header word 60: maximum=0, minimum=1, sum=2, difference=3, product=4. */
final class ProceduralTileSource {
    private final byte[] packet;
    ProceduralTileSource(int mode, int x, int y, int width, int height, float scaling, double[] matrix, int first, int second, int third, int order, int composition,
                         int count, int[] opcodes, double[] values, double[] scales, int[] octaves, long[] seeds) {
        packet = new byte[128 + count * 32];
        ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x50475457).putInt(4, composition == 0 ? 1 : 2).putInt(8, packet.length).putInt(12, count)
                .putInt(16, mode).putInt(20, x).putInt(24, y).putInt(28, width).putInt(32, height)
                .putInt(40, first).putInt(44, second).putFloat(48, scaling).putInt(52, third).putInt(56, order).putInt(60, composition);
        if (matrix != null) for (int i = 0; i < 6; i++) b.putDouble(64 + i * 8, matrix[i]);
        for (int i = 0; i < count; i++) {
            int p = 128 + i * 32;
            b.putInt(p, opcodes[i]).putInt(p + 4, octaves[i]).putDouble(p + 8, values[i])
                    .putDouble(p + 16, scales[i]).putLong(p + 24, seeds[i]);
        }
    }
    int bytes() { return packet.length; }
    void write(ByteBuffer target, int offset) { target.position(offset); target.put(packet); target.position(0); }
}
