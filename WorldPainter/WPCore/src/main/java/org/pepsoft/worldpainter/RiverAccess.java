package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/** WLRV v1 : une zone, un appel JNI et une application cohérente hauteur/eau/lave/terrain. */
public final class RiverAccess {
    private RiverAccess() { }
    private static final ThreadLocal<ByteBuffer> BUFFERS = new ThreadLocal<>();
    public static Integer tryApply(Dimension dimension, int ox, int oy, int side, float[] forces,
                                   float[] slopes, int previous, float depth, boolean lava) {
        if (side < 1 || side > 511 || side % 2 == 0 || (long) side * side != forces.length || slopes.length != forces.length
                || !TileRegionAccess.canBatch(dimension, ox, oy, side, side)) return null;
        int area = side * side, length = 64 + area * 21;
        ByteBuffer data = BUFFERS.get();
        if (data == null || data.capacity() < length) { data = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN); BUFFERS.set(data); }
        data.clear().limit(length);
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
