package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/**
 * WLRV v1 edits heights, water, fluid and terrain in one region transaction. WLRV v2
 * ({@link #tryApplyLarge}) supports sides up to 1023, with compact planes filled directly from the brush.
 */
public final class RiverAccess {
    private RiverAccess() { }
    public static Integer tryApply(Dimension dimension, int ox, int oy, int side, float[] forces,
                                   float[] slopes, int previous, float depth, boolean lava) {
        if (side < 1 || side > 511 || side % 2 == 0 || (long) side * side != forces.length || slopes.length != forces.length
                || !TileRegionAccess.canBatch(dimension, ox, oy, side, side)) return null;
        int area = side * side, length = 64 + area * 21;
        try (var lease = EditorBufferPool.acquire(length)) {
            ByteBuffer data = lease.buffer();
            for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
            data.putInt(0, 0x56524c57).putInt(4, 1).putInt(8, side).putInt(12, previous).putFloat(16, depth).putInt(20, lava ? 1 : 0);
            for (int x = 0; x < side;) {
                int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
                for (int y = 0; y < side;) {
                    int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                    Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.copyRiverRegion(lx, ly, rw, rh, data, x * side + y, side, area);
                    else for (int dx = 0; dx < rw; dx++) for (int dy = 0; dy < rh; dy++) {
                        int i = (x + dx) * side + y + dy;
                        data.putFloat(64 + i * 4, -Float.MAX_VALUE).putInt(64 + area * 4 + i * 4, Integer.MIN_VALUE).putInt(64 + area * 8 + i * 4, Integer.MIN_VALUE);
                    }
                    y += rh;
                }
                x += rw;
            }
            FloatBuffer inputs = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(64 + area * 12).asFloatBuffer(); inputs.put(forces).put(slopes);
            if (!NativeSlices.editRiverRegion(data)) return null;
            int level = data.getInt(12);
            for (int x = 0; x < side;) {
                int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
                for (int y = 0; y < side;) {
                    int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                    Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.applyRiverRegion(lx, ly, rw, rh, data, x * side + y, side, area, level, lava);
                    y += rh;
                }
                x += rw;
            }
            return level;
            }
    }

    /** The full strength of the brush at an offset from the corner of the region, both from 0 to side - 1. */
    @FunctionalInterface
    public interface Strengths {
        float at(int dx, int dy);
    }

    /** The largest side of a WLRV v2 region: a brush with an effective radius of 511. */
    public static final int MAX_LARGE_SIDE = 1023;

    /** The number of completed WLRV v2 edits, for tests and diagnostics. */
    public static long completedLargeCalls() {
        return LARGE_CALLS.get();
    }

    /**
     * River Paint of a square region in one WLRV v2 transaction: one JNI call finds the water level along the edge of
     * the flooded part and edits the copied heights, then each tile applies the heights, water, fluid and beaches
     * with the same conversions as the setters. Nothing is asked of the brush, and nothing is changed, unless the
     * whole region can be edited this way.
     *
     * <p>Memory: a synchronous lease from the shared editor scratch pool, of 64 + 13 bytes per cell (at most about
     * 13.6 MB for a side of 1023); no other array. Events must be inhibited by the caller.
     *
     * @return The new water level, or {@code null} if the region was not edited (unsupported region, dimension or
     * tiles, coordinates out of range, or a native library which does not accept the frame), in which case the caller
     * must apply the edit itself.
     */
    public static Integer tryApplyLarge(Dimension dimension, long ox, long oy, int side, Strengths strengths, int previous,
                                        float depth, boolean lava) {
        // The side is validated first, so that the upper bounds are compared without any addition that could overflow
        if (side < 1 || side > MAX_LARGE_SIDE || side % 2 == 0
                || ox < Integer.MIN_VALUE || oy < Integer.MIN_VALUE
                || ox > Integer.MAX_VALUE - (long) (side - 1) || oy > Integer.MAX_VALUE - (long) (side - 1)
                || !TileRegionAccess.canBatch(dimension, (int) ox, (int) oy, side, side)) return null;
        final int x0 = (int) ox, y0 = (int) oy, area = side * side, length = 64 + area * 13;
        final int waters = 64 + area * 4, values = 64 + area * 8, classes = 64 + area * 12;
        try (var lease = EditorBufferPool.acquire(length)) {
            ByteBuffer data = lease.buffer();
            for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
            data.putInt(0, 0x56524c57).putInt(4, 2).putInt(8, side).putInt(12, previous).putFloat(16, depth).putInt(20, lava ? 1 : 0);
            // The class of each cell and its value: the strength where it floods, the slope where it is lowered
            for (int dx = 0; dx < side; dx++) for (int dy = 0; dy < side; dy++) {
                final int i = dx * side + dy;
                final float strength = strengths.at(dx, dy);
                if (strength > 0.25f) {
                    data.put(classes + i, (byte) 2).putFloat(values + i * 4, strength);
                } else if (strength > 0.0f) {
                    data.put(classes + i, (byte) 1).putFloat(values + i * 4, (float) (Math.tan(-strength * DOUBLE_PI + HALF_PI) / DOUBLE_PI));
                } else {
                    data.put(classes + i, (byte) 0).putFloat(values + i * 4, 0.0f);
                }
            }
            for (int x = 0; x < side;) {
                int wx = x0 + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
                for (int y = 0; y < side;) {
                    int wy = y0 + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                    Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.copyLargeRiverRegion(lx, ly, rw, rh, data, x * side + y, side, area);
                    else for (int dx = 0; dx < rw; dx++) for (int dy = 0; dy < rh; dy++) {
                        int i = (x + dx) * side + y + dy;
                        data.putFloat(64 + i * 4, -Float.MAX_VALUE).putInt(waters + i * 4, Integer.MIN_VALUE);
                    }
                    y += rh;
                }
                x += rw;
            }
            if (!NativeSlices.editRiverRegion(data)) return null;
            final int level = data.getInt(12);
            for (int x = 0; x < side;) {
                int wx = x0 + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
                for (int y = 0; y < side;) {
                    int wy = y0 + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                    Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.applyLargeRiverRegion(lx, ly, rw, rh, data, x * side + y, side, area, level, lava);
                    y += rh;
                }
                x += rw;
            }
            LARGE_CALLS.incrementAndGet();
            return level;
            }
    }

    private static final java.util.concurrent.atomic.AtomicLong LARGE_CALLS = new java.util.concurrent.atomic.AtomicLong();
    private static final double DOUBLE_PI = Math.PI * 2;
    private static final double HALF_PI = Math.PI / 2;
}
