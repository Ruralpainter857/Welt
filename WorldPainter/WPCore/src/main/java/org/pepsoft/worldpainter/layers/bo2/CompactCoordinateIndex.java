package org.pepsoft.worldpainter.layers.bo2;

import java.nio.*;
import java.lang.invoke.*;
import java.util.Arrays;

/** Shared immutable coordinate index, independent of material or specification payloads. */
class CompactCoordinateIndex {
    private static final int HEADER=64;
    final byte[] data;
    final int mode,count,width,length,height,slots,stride;
    private static final VarHandle WORD=MethodHandles.byteArrayViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
    CompactCoordinateIndex(byte[] frame,int paletteSize) {
        if (frame == null || frame.length < HEADER || paletteSize < 0 || paletteSize > 262144) throw new IllegalArgumentException("Invalid coordinate frame");
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        mode = header.getInt(8); count = header.getInt(16); slots = header.getInt(48); stride = header.getInt(52);
        width = header.getInt(20); length = header.getInt(24); height = header.getInt(28);
        int bytes = header.getInt(12), offset = header.getInt(36);
        if (header.getInt(0) != 0x57535452 || header.getInt(4) != 1 || header.getInt(44) != frame.length
                || (mode != 1 && mode != 2) || count < 0 || count > 262144 || header.getInt(32) != paletteSize
                || bytes < 0 || (long) HEADER + bytes != offset || offset > frame.length
                || slots < 0 || slots > 524288 || mode == 1 && slots != 0
                || mode == 2 && (bytes != count * 16 + slots * 4 || count == 0 && slots != 0
                || count != 0 && (slots < count * 2 || (slots & (slots - 1)) != 0))
                || mode == 1 && ((stride != 1 && stride != 2 && stride != 4) || width < 1 || length < 1 || height < 1 || (long) width * length > 262144
                || (long) width * length * height > 262144 || (long) width * length * height * stride != bytes))
            throw new IllegalArgumentException("Invalid compact structure storage");
        data = Arrays.copyOfRange(frame, HEADER, offset);
    }
    final long storageBytes() { return data.length; }
    final int word(int offset) {
        return (int) WORD.get(data, offset);
    }
    final int index(int x, int y, int z) {
        if (mode == 1) {
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= length || z >= height) return -1;
            int at = (x + y * width + z * width * length) * stride;
            if (stride == 4) return word(at) - 1;
            return (stride == 1 ? data[at] & 255 : (data[at] & 255) | (data[at + 1] & 255) << 8) - 1;
        }
        if (count == 0) return -1;
        int hash = x * 0x9e3779b9 ^ y * 0x85ebca6b ^ z * 0xc2b2ae35;
        hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15;
        int slot = hash & (slots - 1), start = count * 16;
        for (int probe = 0; probe < slots; probe++) {
            int entry = word(start + slot * 4);
            if (entry == 0) return -1;
            int at = (entry - 1) * 16;
            if (word(at) == x && word(at + 4) == y && word(at + 8) == z) return word(at + 12);
            slot = (slot + 1) & (slots - 1);
        }
        return -1;
    }
}
