package org.pepsoft.worldpainter;

import java.awt.Rectangle;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import static org.pepsoft.worldpainter.Constants.*;

/** Complete selection bounds; tile traversal, pruning and Java coordinate overflow remain unchanged. */
public final class SelectionBoundsAccess {
    private SelectionBoundsAccess() { }
    public static Rectangle getBounds(Dimension dimension) {
        int[] lowestX = {Integer.MAX_VALUE};
        int[] highestX = {Integer.MIN_VALUE};
        int[] lowestY = {Integer.MAX_VALUE};
        int[] highestY = {Integer.MIN_VALUE};
        dimension.visitTiles().forSelection().andDo(tile -> {
                    int tileX = tile.getX(), tileY = tile.getY();
                    if (((tileX << TILE_SIZE_BITS) >= lowestX[0])
                            && (((tileX + 1) << TILE_SIZE_BITS) < highestX[0])
                            && (((tileY) << TILE_SIZE_BITS) >= lowestY[0])
                            && (((tileY + 1) << TILE_SIZE_BITS) < highestY[0])) {
                        // Tiles which lie within the already established bounds can be safely skipped
                        return;
                    }
                    long local = tile.getNativeSelectionBounds();
                    if (local != Long.MIN_VALUE) {
                        if ((local & (1L << 32)) != 0) {
                            int originX = tileX << TILE_SIZE_BITS, originY = tileY << TILE_SIZE_BITS;
                            lowestX[0] = Math.min(lowestX[0], originX + (int) (local & 255));
                            highestX[0] = Math.max(highestX[0], originX + (int) ((local >>> 8) & 255));
                            lowestY[0] = Math.min(lowestY[0], originY + (int) ((local >>> 16) & 255));
                            highestY[0] = Math.max(highestY[0], originY + (int) ((local >>> 24) & 255));
                        }
                        return;
                    }
                    boolean tileHasChunkSelection = tile.hasLayer(SelectionChunk.INSTANCE);
                    boolean tileHasBlockSelection = tile.hasLayer(SelectionBlock.INSTANCE);
                    for (int chunkX = 0; chunkX < TILE_SIZE; chunkX += 16) {
                        for (int chunkY = 0; chunkY < TILE_SIZE; chunkY += 16) {
                            if (tileHasChunkSelection && tile.getBitLayerValue(SelectionChunk.INSTANCE, chunkX, chunkY)) {
                                int x1 = (tileX << TILE_SIZE_BITS) | chunkX;
                                int x2 = x1 + 15;
                                int y1 = (tileY << TILE_SIZE_BITS) | chunkY;
                                int y2 = y1 + 15;
                                if (x1 < lowestX[0]) {
                                    lowestX[0] = x1;
                                }
                                if (x2 > highestX[0]) {
                                    highestX[0] = x2;
                                }
                                if (y1 < lowestY[0]) {
                                    lowestY[0] = y1;
                                }
                                if (y2 > highestY[0]) {
                                    highestY[0] = y2;
                                }
                            } else if (tileHasBlockSelection) {
                                for (int dx = 0; dx < 16; dx++) {
                                    for (int dy = 0; dy < 16; dy++) {
                                        if (tile.getBitLayerValue(SelectionBlock.INSTANCE, chunkX + dx, chunkY + dy)) {
                                            final int x = ((tileX << TILE_SIZE_BITS) | chunkX) + dx;
                                            final int y = ((tileY << TILE_SIZE_BITS) | chunkY) + dy;
                                            if (x < lowestX[0]) {
                                                lowestX[0] = x;
                                            }
                                            if (x > highestX[0]) {
                                                highestX[0] = x;
                                            }
                                            if (y < lowestY[0]) {
                                                lowestY[0] = y;
                                            }
                                            if (y > highestY[0]) {
                                                highestY[0] = y;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
        if (lowestX[0] != Integer.MAX_VALUE) {
            return new Rectangle(lowestX[0], lowestY[0], highestX[0] - lowestX[0] + 1, highestY[0] - lowestY[0] + 1);
        } else {
            return null;
        }

    }
}
