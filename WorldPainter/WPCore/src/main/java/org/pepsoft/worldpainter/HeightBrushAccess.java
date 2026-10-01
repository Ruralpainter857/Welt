package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/**
 * Frontière commune des pinceaux de hauteur et d'aplanissement sans thème ni filtre dépendant des mutations.
 * Un appel JNI par tuile ; les hauteurs restent compactes et le tampon est réutilisé par worker.
 * Le demandeur sérialise les éditions de la dimension. Les forces suivent le parcours X puis Y.
 */
public final class HeightBrushAccess {
    public static final int RAISE = 0, LOWER = 1, FLATTEN = 2, FLATTEN_RAISE = 3, FLATTEN_LOWER = 4;
    private HeightBrushAccess() { }
    private static final ThreadLocal<Scratch> BUFFER = ThreadLocal.withInitial(Scratch::new);

    public static boolean tryApply(Dimension dimension, int ox, int oy, int width, int height,
                                   float[] strengths, int mode, float value, float minClamp, float maxClamp) {
        if (width <= 0 || height <= 0 || (long) width * height != strengths.length || mode < 0 || mode > 4
                || !TileRegionAccess.canBatch(dimension, ox, oy, width, height)) return false;
        for (int x = 0; x < width;) {
            int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
            for (int y = 0; y < height;) {
                int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(height - y, TILE_SIZE - ly);
                Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.editHeightBrushRegion(lx, ly, rw, rh, strengths, x * height + y, height,
                        mode, value, minClamp, maxClamp);
                y += rh;
            }
            x += rw;
        }
        return true;
    }

    /** Apply heights and an exact SimpleTheme together without per-cell Java setters. */
    public static boolean tryApplyThemed(Dimension dimension,int ox,int oy,int width,int height,
                                         float[] strengths,int mode,float value,float minClamp,float maxClamp) {
        // Complete small-stroke measurements still favour the original path over full packed-tile copies.
        if ((long)width * height < 16384) return false;
        return ThemedHeightBrushAccess.apply(dimension,ox,oy,width,height,strengths,mode,value,minClamp,maxClamp);
    }

    static Scratch edit(int minZ, int x, int y, int width, int height, float[] strengths, int offset, int stride,
                        int mode, float value, float minClamp, float maxClamp, short[] shorts, int[] ints) {
        Scratch scratch = BUFFER.get(); ByteBuffer buffer = scratch.buffer;
        int forces = 64 + (ints == null ? 32768 : 65536);
        buffer.clear().limit(forces + width * height * 4);
        buffer.putInt(0, 0x44454857).putInt(4, 2).putInt(8, minZ).putFloat(12, minClamp).putFloat(16, maxClamp)
                .putInt(20, ints == null ? 16 : 32).putInt(24, mode).putFloat(28, value).putInt(32, 0).putInt(36, 0)
                .putInt(40, x).putInt(44, y).putInt(48, width).putInt(52, height).putInt(56, forces).putInt(60, 0);
        if (ints != null) { scratch.ints.clear().limit(16384); scratch.ints.put(ints); }
        else { scratch.shorts.clear().limit(16384); scratch.shorts.put(shorts); }
        scratch.forces.clear().position(forces / 4).limit(forces / 4 + width * height);
        for (int dx = 0; dx < width; dx++) scratch.forces.put(strengths, offset + dx * stride, height);
        return NativeSlices.editHeightPlane(buffer) ? scratch : null;
    }

    static final class Scratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(64 + 131072).order(ByteOrder.LITTLE_ENDIAN);
        final ShortBuffer shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(64).asShortBuffer();
        final IntBuffer ints = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(64).asIntBuffer();
        final FloatBuffer forces = buffer.asFloatBuffer();
        void copy(short[] output) { shorts.clear().limit(16384); shorts.get(output); }
        void copy(int[] output) { ints.clear().limit(16384); ints.get(output); }
    }
}
