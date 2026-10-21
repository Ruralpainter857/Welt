package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.heightMaps.AbstractHeightMap;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;

import static java.util.Objects.requireNonNull;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE_BITS;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE_MASK;

class TileCacheHeightMap extends AbstractHeightMap {
    TileCacheHeightMap(float[][][][] cache, int tileXOffset, int tileYOffset, float rangeMin, float rangeMax) {
        requireNonNull(cache);
        this.cache = cache;
        flatCache = null;
        flatTilePresence = null;
        flatWidth = 0;
        tileColumns = cache.length;
        tileRows = cache[0].length;
        this.tileXOffset = tileXOffset;
        this.tileYOffset = tileYOffset;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        extent = new Rectangle(tileXOffset << TILE_SIZE_BITS, tileYOffset << TILE_SIZE_BITS,
                tileColumns << TILE_SIZE_BITS, tileRows << TILE_SIZE_BITS);
    }

    TileCacheHeightMap(float[] flatCache, int flatWidth, int tileColumns, int tileRows,
                       boolean[] flatTilePresence, int tileXOffset, int tileYOffset,
                       float rangeMin, float rangeMax) {
        requireNonNull(flatCache);
        requireNonNull(flatTilePresence);
        this.cache = null;
        this.flatCache = flatCache;
        this.flatWidth = flatWidth;
        this.tileColumns = tileColumns;
        this.tileRows = tileRows;
        this.flatTilePresence = flatTilePresence;
        this.tileXOffset = tileXOffset;
        this.tileYOffset = tileYOffset;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        extent = new Rectangle(tileXOffset << TILE_SIZE_BITS, tileYOffset << TILE_SIZE_BITS,
                tileColumns << TILE_SIZE_BITS, tileRows << TILE_SIZE_BITS);
    }

    @Override
    public Icon getIcon() {
        return null;
    }

    @Override
    public double[] getRange() {
        return new double[] {rangeMin, rangeMax};
    }

    @Override
    public Rectangle getExtent() {
        return extent;
    }

    @Override
    public double getHeight(int x, int y) {
        final int tileX = (x >> TILE_SIZE_BITS) - tileXOffset, tileY = (y >> TILE_SIZE_BITS) - tileYOffset;
        if ((tileX < 0) || (tileX >= tileColumns) || (tileY < 0) || (tileY >= tileRows)) {
            return rangeMin;
        }
        if (flatCache != null) {
            if (!flatTilePresence[tileY * tileColumns + tileX]) {
                return rangeMin;
            }
            final int localX = tileX * TILE_SIZE + (x & TILE_SIZE_MASK) + 1;
            final int localY = tileY * TILE_SIZE + (y & TILE_SIZE_MASK) + 1;
            return flatCache[localY * flatWidth + localX];
        }
        if (cache[tileX][tileY] == null) {
            return rangeMin;
        }
        return cache[tileX][tileY][x & TILE_SIZE_MASK][y & TILE_SIZE_MASK];
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new NotSerializableException();
    }

    private final float[][][][] cache;
    private final float[] flatCache;
    private final boolean[] flatTilePresence;
    private final int flatWidth, tileColumns, tileRows;
    private final int tileXOffset, tileYOffset;
    private final float rangeMin, rangeMax;
    private final Rectangle extent;
}
