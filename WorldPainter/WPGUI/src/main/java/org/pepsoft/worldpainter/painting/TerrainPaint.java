/*
 * WorldPainter, a graphical and interactive map generator for Minecraft.
 * Copyright � 2011-2015  pepsoft.org, The Netherlands
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE_BITS;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE_MASK;

/**
 * <strong>Note:</strong> does <em>not</em> do any event inhibitation management.
 *
 * <p>Created by pepijn on 20-05-15.
 */
public final class TerrainPaint extends AbstractPaint {
    public TerrainPaint(Terrain terrain) {
        this.terrain = terrain;
    }

    public Terrain getTerrain() {
        return terrain;
    }

    @Override
    public String getId() {
        return "Terrain/" + terrain.ordinal();
    }

    @Override
    public void apply(Dimension dimension, int centreX, int centreY, float dynamicLevel) {
        if (brush.getRadius() == 0) {
            // Special case: if the radius is 0, assume that the user wants to paint complete pixels instead of trying
            // to apply the brush
            applyPixel(dimension, centreX, centreY);
            return;
        }
        final Rectangle boundingBox = brush.getBoundingBox();
        final int x1 = centreX + boundingBox.x, y1 = centreY + boundingBox.y, x2 = x1 + boundingBox.width - 1, y2 = y1 + boundingBox.height - 1;
        final int tileX1 = x1 >> TILE_SIZE_BITS, tileY1 = y1 >> TILE_SIZE_BITS, tileX2 = x2 >> TILE_SIZE_BITS, tileY2 = y2 >> TILE_SIZE_BITS;
        final boolean oneTile = (tileX1 == tileX2) && (tileY1 == tileY2);
        if (applyNativeTerrainBrush(dimension, centreX, centreY, dynamicLevel,
                x1, y1, x2, y2, oneTile, false)) {
            return;
        }
        if (oneTile) {
            // The bounding box of the brush is entirely on one tile; optimize by painting directly to the tile
            final Tile tile = dimension.getTileForEditing(tileX1, tileY1);
            if (tile == null) {
                return;
            }
            final int x1InTile = x1 & TILE_SIZE_MASK, y1InTile = y1 & TILE_SIZE_MASK, x2InTile = x2 & TILE_SIZE_MASK, y2InTile = y2 & TILE_SIZE_MASK;
            final int tileXInWorld = tileX1 << TILE_SIZE_BITS, tileYInWorld = tileY1 << TILE_SIZE_BITS;
            if (dither) {
                for (int y = y1InTile; y <= y2InTile; y++) {
                    for (int x = x1InTile; x <= x2InTile; x++) {
                        final float strength = dynamicLevel * getStrength(centreX, centreY, tileXInWorld + x, tileYInWorld + y);
                        if ((strength > 0.95f) || (Math.random() < strength)) {
                            tile.setTerrain(x, y, terrain);
                        }
                    }
                }
            } else {
                for (int y = y1InTile; y <= y2InTile; y++) {
                    for (int x = x1InTile; x <= x2InTile; x++) {
                        final float strength = dynamicLevel * getFullStrength(centreX, centreY, tileXInWorld + x, tileYInWorld + y);
                        if (strength > 0.75f) {
                            tile.setTerrain(x, y, terrain);
                        }
                    }
                }
            }
        } else {
            // The bounding box of the brush straddles more than one tile. While the dimension is
            // already inhibiting events for this edit, reuse the tile lookup across each row's
            // horizontal tile segment. Tile.setTerrain still performs the normal copy-on-write
            // and change notification handling.
            if (dimension.isEventsInhibited()) {
                if (dither) {
                    for (int y = y1; y <= y2; y++) {
                        final int tileY = y >> TILE_SIZE_BITS;
                        int cachedTileX = Integer.MIN_VALUE;
                        Tile tile = null;
                        for (int x = x1; x <= x2; x++) {
                            final float strength = dynamicLevel * getStrength(centreX, centreY, x, y);
                            if ((strength > 0.95f) || (Math.random() < strength)) {
                                final int tileX = x >> TILE_SIZE_BITS;
                                if (tileX != cachedTileX) {
                                    cachedTileX = tileX;
                                    tile = dimension.getTileForEditing(tileX, tileY);
                                }
                                if (tile != null) {
                                    tile.setTerrain(x & TILE_SIZE_MASK, y & TILE_SIZE_MASK, terrain);
                                }
                            }
                        }
                    }
                } else {
                    for (int y = y1; y <= y2; y++) {
                        final int tileY = y >> TILE_SIZE_BITS;
                        int cachedTileX = Integer.MIN_VALUE;
                        Tile tile = null;
                        for (int x = x1; x <= x2; x++) {
                            final float strength = dynamicLevel * getFullStrength(centreX, centreY, x, y);
                            if (strength > 0.75f) {
                                final int tileX = x >> TILE_SIZE_BITS;
                                if (tileX != cachedTileX) {
                                    cachedTileX = tileX;
                                    tile = dimension.getTileForEditing(tileX, tileY);
                                }
                                if (tile != null) {
                                    tile.setTerrain(x & TILE_SIZE_MASK, y & TILE_SIZE_MASK, terrain);
                                }
                            }
                        }
                    }
                }
            } else if (dither) {
                for (int y = y1; y <= y2; y++) {
                    for (int x = x1; x <= x2; x++) {
                        final float strength = dynamicLevel * getStrength(centreX, centreY, x, y);
                        if ((strength > 0.95f) || (Math.random() < strength)) {
                            dimension.setTerrainAt(x, y, terrain);
                        }
                    }
                }
            } else {
                for (int y = y1; y <= y2; y++) {
                    for (int x = x1; x <= x2; x++) {
                        final float strength = dynamicLevel * getFullStrength(centreX, centreY, x, y);
                        if (strength > 0.75f) {
                            dimension.setTerrainAt(x, y, terrain);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void remove(Dimension dimension, int centreX, int centreY, float dynamicLevel) {
        if (brush.getRadius() == 0) {
            // Special case: if the radius is 0, assume that the user wants to remove complete pixels instead of trying
            // to apply the brush
            removePixel(dimension, centreX, centreY);
            return;
        }
        final Rectangle boundingBox = brush.getBoundingBox();
        final int x1 = centreX + boundingBox.x, y1 = centreY + boundingBox.y, x2 = x1 + boundingBox.width - 1, y2 = y1 + boundingBox.height - 1;
        if (applyNativeTerrainBrush(dimension, centreX, centreY, dynamicLevel,
                x1, y1, x2, y2, false, true)) {
            return;
        }
        // Can't optimise by painting directly to tile, because Tile doesn't have the applyTheme() method
        if (dither) {
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    final float strength = dynamicLevel * getFullStrength(centreX, centreY, x, y);
                    if ((strength > 0.95f) || (Math.random() < strength)) {
                        dimension.applyTheme(x, y);
                    }
                }
            }
        } else {
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    final float strength = dynamicLevel * getFullStrength(centreX, centreY, x, y);
                    if (strength > 0.75f) {
                        dimension.applyTheme(x, y);
                    }
                }
            }
        }
    }

    @Override
    public void applyPixel(Dimension dimension, int x, int y) {
        dimension.setTerrainAt(x, y, terrain);
    }

    @Override
    public void removePixel(Dimension dimension, int x, int y) {
        dimension.applyTheme(x, y);
    }

    @Override
    public BufferedImage getIcon(ColourScheme colourScheme) {
        return terrain.getScaledIcon(16, colourScheme);
    }

    private boolean applyNativeTerrainBrush(Dimension dimension, int centreX, int centreY,
                                            float dynamicLevel, int x1, int y1, int x2, int y2,
                                            boolean oneTile, boolean remove) {
        final long width = (long) x2 - x1 + 1L;
        final long height = (long) y2 - y1 + 1L;
        if (dither || filter != null || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || width > 65_536L || height > 65_536L
                || width * height > 65_536L) {
            return false;
        }
        final int area = (int) (width * height);
        final Tile tile;
        if (oneTile && !remove) {
            tile = dimension.getTileForEditing(x1 >> TILE_SIZE_BITS, y1 >> TILE_SIZE_BITS);
            if (tile == null) {
                return true;
            }
        } else {
            tile = null;
        }
        ensureNativeBuffers(area);

        int index = 0;
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                nativeStrengths[index++] = dynamicLevel * getFullStrength(centreX, centreY, x, y);
            }
        }
        if (!NativeSlices.paintThresholdMask(nativeStrengths, nativeModified)) {
            return false;
        }

        index = 0;
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (nativeModified[index] != 0) {
                    if (remove) {
                        dimension.applyTheme(x, y);
                    } else if (oneTile) {
                        tile.setTerrain(x & TILE_SIZE_MASK, y & TILE_SIZE_MASK, terrain);
                    } else {
                        dimension.setTerrainAt(x, y, terrain);
                    }
                }
                index++;
            }
        }
        return true;
    }

    private void ensureNativeBuffers(int area) {
        if (nativeStrengths == null || nativeStrengths.length != area) {
            nativeStrengths = new float[area];
            nativeModified = new byte[area];
        }
    }

    private final Terrain terrain;
    private float[] nativeStrengths;
    private byte[] nativeModified;
}
