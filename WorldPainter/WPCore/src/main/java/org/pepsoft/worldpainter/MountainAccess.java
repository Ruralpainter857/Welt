package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/** WHED v3 : modelage sans thème, un tampon compact et un appel JNI par tuile. */
public final class MountainAccess {
    private MountainAccess() { }
    private static final ThreadLocal<Scratch> BUFFERS = ThreadLocal.withInitial(Scratch::new);

    public static boolean tryApply(Dimension dimension, int ox, int oy, int side, float[] forces,
                                   float peak, float factor, boolean inverse) {
        if (side < 1 || side > 511 || (long) side * side != forces.length
                || !TileRegionAccess.canBatch(dimension, ox, oy, side, side)) return false;
        for (int x = 0; x < side;) {
            int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
            for (int y = 0; y < side;) {
                int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.editMountainRegion(lx, ly, rw, rh, forces, x * side + y, side,
                        peak, factor, inverse, dimension.getMinHeight(), dimension.getMaxHeight() - 1 - dimension.getMinHeight());
                y += rh;
            }
            x += rw;
        }
        return true;
    }
    static Scratch edit(int tx, int ty, int minZ, int range, int x, int y, int width, int height,
                        float[] forces, int offset, int stride, float peak, float factor, boolean inverse,
                        short[] shorts, int[] ints) {
        Scratch scratch = BUFFERS.get(); ByteBuffer buffer = scratch.buffer;
        int strengthOffset = 80 + (ints == null ? 32768 : 65536);
        buffer.clear().limit(strengthOffset + width * height * 4);
        buffer.putInt(0, 0x44454857).putInt(4, 3).putInt(8, minZ).putFloat(12, range).putFloat(16, peak)
                .putInt(20, ints == null ? 16 : 32).putInt(24, inverse ? 1 : 0).putFloat(28, factor)
                .putInt(32, 0).putInt(36, 0).putInt(40, x).putInt(44, y).putInt(48, width).putInt(52, height)
                .putInt(56, strengthOffset).putInt(60, 0).putInt(64, tx << TILE_SIZE_BITS).putInt(68, ty << TILE_SIZE_BITS)
                .putFloat(72, MEDIUM_BLOBS).putInt(76, 0);
        if (ints != null) { scratch.ints.clear(); scratch.ints.put(ints); }
        else { scratch.shorts.clear(); scratch.shorts.put(shorts); }
        scratch.forces.clear().position(strengthOffset / 4);
        for (int dx = 0; dx < width; dx++) scratch.forces.put(forces, offset + dx * stride, height);
        return NativeSlices.editHeightPlane(buffer) ? scratch : null;
    }
    static float target(int x, int y, float strength, int min, int range, float peak, float factor, boolean inverse) {
        float variation = (0.5f - Math.abs(strength - 0.5f)) / 5;
        strength += BUFFERS.get().noise.getPerlinNoise(x / MEDIUM_BLOBS, y / MEDIUM_BLOBS) * variation * strength;
        if (strength < 0) strength = 0; else if (strength > 1) strength = 1;
        return (inverse ? Math.max(range - (range - peak) * factor * strength, 0) : Math.min(peak * factor * strength, range)) + min;
    }
    static final class Scratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(80 + 131072).order(ByteOrder.LITTLE_ENDIAN);
        final ShortBuffer shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(80).asShortBuffer();
        final IntBuffer ints = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(80).asIntBuffer();
        final FloatBuffer forces = buffer.asFloatBuffer();
        final PerlinNoise noise = new PerlinNoise(67);
        void copy(short[] values) { shorts.clear().limit(16384); shorts.get(values); }
        void copy(int[] values) { ints.clear().limit(16384); ints.get(values); }
    }
}
