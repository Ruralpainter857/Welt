package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.pepsoft.worldpainter.Constants.*;

final class TileRegionAccess {
    private TileRegionAccess() { }
    static boolean canBatch(Dimension dimension, int ox, int oy, int width, int height) {
        if (dimension.getClass() != Dimension.class || !dimension.isEventsInhibited()
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        // Les sous-classes peuvent observer l'ordre global des setters ; les détecter avant toute écriture.
        for (int x = 0; x < width;) {
            int wx = ox + x, rw = Math.min(width - x, TILE_SIZE - (wx & TILE_SIZE_MASK));
            for (int y = 0; y < height;) {
                int wy = oy + y, rh = Math.min(height - y, TILE_SIZE - (wy & TILE_SIZE_MASK));
                Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null && tile.getClass() != Tile.class) return false;
                y += rh;
            }
            x += rw;
        }
        return true;
    }
}
