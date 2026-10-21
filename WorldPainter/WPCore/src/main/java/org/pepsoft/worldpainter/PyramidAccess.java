package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/**
 * Transaction de modelage avec hauteurs et terrain cohérents, tampon réutilisé par worker.
 * Un appel JNI par groupe ; un petit groupe d'abord, puis un groupe complet seulement si nécessaire.
 * Le demandeur sérialise les éditions de la dimension pendant cette transaction.
 */
public final class PyramidAccess {
    private PyramidAccess() { }
    private static final ThreadLocal<ByteBuffer> BUFFERS = new ThreadLocal<>();

    public static boolean tryApply(Dimension dimension, int cx, int cy, boolean rotated) {
        int rings = dimension.getMaxHeight() - dimension.getMinHeight();
        if (rings < 1 || rings > 512) return false;
        int radius = Math.min(rings - 1, 16);
        while (true) {
            int side = radius * 2 + 1, area = side * side;
            int ox = cx - radius, oy = cy - radius, mask = 32 + area * 4, length = mask + area;
            if (!TileRegionAccess.canBatch(dimension, ox, oy, side, side)) return false;
            ByteBuffer data = BUFFERS.get();
            if (data == null || data.capacity() < length) {
                data = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN); BUFFERS.set(data);
            }
            data.clear().limit(length);
            data.putInt(0, 0x59504c57).putInt(4, 1).putInt(8, rings).putFloat(12, dimension.getMaxHeight())
                    .putFloat(16, dimension.getHeightAt(cx, cy)).putInt(20, rotated ? 1 : 0).putInt(24, side).putInt(28, 0);
            FloatBuffer heights = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(32).asFloatBuffer();
            for (int x = 0; x < side;) {
                int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
                for (int y = 0; y < side;) {
                    int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                    Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.copyHeightRegionDirect(lx, ly, rw, rh, heights, x * side + y, side);
                    else for (int dx = 0; dx < rw; dx++) for (int dy = 0; dy < rh; dy++) heights.put((x + dx) * side + y + dy, -Float.MAX_VALUE);
                    y += rh;
                }
                x += rw;
            }
            // Si le JNI manque, aucune édition n'a eu lieu : le demandeur peut conserver son parcours Java.
            if (!NativeSlices.shapePyramidRegion(data)) return false;
            // Une zone atteinte est élargie avant toute édition Java ; le second groupe reprend la source intacte.
            if (data.getInt(28) != 0) { radius = rings - 1; continue; }
            for (int x = 0; x < side;) {
                int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(side - x, TILE_SIZE - lx);
                for (int y = 0; y < side;) {
                    int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(side - y, TILE_SIZE - ly);
                    Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                    if (tile != null) tile.applyShapingRegion(lx, ly, rw, rh, data, x * side + y, side, mask, area, Terrain.SANDSTONE);
                    y += rh;
                }
                x += rw;
            }
            return true;
        }
    }
}
