package org.pepsoft.worldpainter;

import java.util.Arrays;

import static org.pepsoft.worldpainter.Constants.*;

/**
 * Shared boundary for region-based terrain kernels. Buffers belong to the caller
 * and use X-major order: {@code x * height + y}. No tile backing array escapes.
 * Coordinate addition has the same wrapping semantics as the scalar API.
 * Reads are coherent per tile; callers must serialize edits to the dimension.
 */
public final class TerrainHeightAccess {
    private TerrainHeightAccess() { }

    public static void copy(Dimension dimension, int originX, int originY,
                            int width, int height, float[] output) {
        checkRegion(width, height, output.length);
        if (dimension.getClass() != Dimension.class) {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    output[x * height + y] = dimension.getHeightAt(originX + x, originY + y);
                }
            }
            return;
        }
        for (int x = 0; x < width;) {
            final int worldX = originX + x, localX = worldX & TILE_SIZE_MASK;
            final int runWidth = Math.min(width - x, TILE_SIZE - localX);
            for (int y = 0; y < height;) {
                final int worldY = originY + y, localY = worldY & TILE_SIZE_MASK;
                final int runHeight = Math.min(height - y, TILE_SIZE - localY);
                final Tile tile = dimension.getTile(worldX >> TILE_SIZE_BITS, worldY >> TILE_SIZE_BITS);
                final int offset = x * height + y;
                if (tile == null) {
                    for (int dx = 0; dx < runWidth; dx++) {
                        Arrays.fill(output, offset + dx * height,
                                offset + dx * height + runHeight, -Float.MAX_VALUE);
                    }
                } else if (tile.getClass() == Tile.class) {
                    tile.copyHeightRegion(localX, localY, runWidth, runHeight, output, offset, height);
                } else {
                    for (int dx = 0; dx < runWidth; dx++) {
                        for (int dy = 0; dy < runHeight; dy++) {
                            output[offset + dx * height + dy] = tile.getHeight(localX + dx, localY + dy);
                        }
                    }
                }
                y += runHeight;
            }
            x += runWidth;
        }
    }

    /**
     * Apply independent edits with no intervening theme/filter evaluation.
     * With inhibited events, each touched tile uses a single COW transaction.
     * Custom dimensions/tiles and immediate notifications retain scalar setters.
     */
    public static void apply(Dimension dimension, int originX, int originY,
                             int width, int height, float[] values, byte[] modified) {
        checkRegion(width, height, values.length);
        checkRegion(width, height, modified.length);
        if (dimension.getClass() != Dimension.class || !dimension.isEventsInhibited()) {
            applyScalar(dimension, originX, originY, width, height, values, modified);
            return;
        }
        for (int x = 0; x < width;) {
            final int worldX = originX + x, localX = worldX & TILE_SIZE_MASK;
            final int runWidth = Math.min(width - x, TILE_SIZE - localX);
            for (int y = 0; y < height;) {
                final int worldY = originY + y, localY = worldY & TILE_SIZE_MASK;
                final int runHeight = Math.min(height - y, TILE_SIZE - localY);
                final int offset = x * height + y;
                boolean touched = false;
                for (int dx = 0; dx < runWidth && !touched; dx++) {
                    for (int dy = 0; dy < runHeight; dy++) {
                        if (modified[offset + dx * height + dy] != 0) {
                            touched = true;
                            break;
                        }
                    }
                }
                if (touched) {
                    final Tile tile = dimension.getTileForEditing(
                            worldX >> TILE_SIZE_BITS, worldY >> TILE_SIZE_BITS);
                    if (tile != null && tile.getClass() == Tile.class) {
                        tile.applyHeightRegion(localX, localY, runWidth, runHeight,
                                values, modified, offset, height);
                    } else if (tile != null) {
                        for (int dx = 0; dx < runWidth; dx++) {
                            for (int dy = 0; dy < runHeight; dy++) {
                                final int index = offset + dx * height + dy;
                                if (modified[index] != 0) {
                                    tile.setHeight(localX + dx, localY + dy, values[index]);
                                }
                            }
                        }
                    }
                }
                y += runHeight;
            }
            x += runWidth;
        }
    }

    private static void applyScalar(Dimension dimension, int originX, int originY,
                                    int width, int height, float[] values, byte[] modified) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                final int index = x * height + y;
                if (modified[index] != 0) {
                    dimension.setHeightAt(originX + x, originY + y, values[index]);
                }
            }
        }
    }

    private static void checkRegion(int width, int height, int length) {
        if (width <= 0 || height <= 0 || (long) width * height != length) {
            throw new IllegalArgumentException("Expected one value per region cell");
        }
    }
}
