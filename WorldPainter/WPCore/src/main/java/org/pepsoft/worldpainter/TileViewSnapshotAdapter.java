package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.nativeapi.TileView;

import java.awt.Point;

/**
 * Builds a native read view from an immutable undo snapshot of a tile.
 *
 * <p>This adapter lives in the tile package so it can read Tile's protected
 * buffers after the snapshot has resolved them. {@link TileView#ofArrays}
 * immediately makes defensive copies; no copy-on-write buffer is retained or
 * exposed to native code.</p>
 */
public final class TileViewSnapshotAdapter {
    private TileViewSnapshotAdapter() {
    }

    /**
     * Capture a tile by its tile coordinates from a dimension snapshot.
     *
     * @param dimension an immutable dimension snapshot
     * @param tileCoordinates tile coordinates, not pixel coordinates
     * @return a standalone, read-only native view
     * @throws IllegalArgumentException if the requested tile does not exist
     */
    public static TileView of(DimensionSnapshot dimension, Point tileCoordinates) {
        if (dimension == null) {
            throw new NullPointerException("dimension");
        }
        if (tileCoordinates == null) {
            throw new NullPointerException("tileCoordinates");
        }
        final Tile tile = dimension.getTile(tileCoordinates);
        if (tile == null) {
            throw new IllegalArgumentException("No tile at " + tileCoordinates);
        }
        return of((TileSnapshot) tile);
    }

    /**
     * Capture a non-tall tile snapshot using its backing buffers instead of
     * synchronised per-cell getters.
     *
     * @param tile an immutable tile obtained from {@link DimensionSnapshot}
     * @return a standalone, read-only native view
     * @throws IllegalArgumentException if the tile uses the unsupported tall
     *         height representation
     */
    public static TileView of(TileSnapshot tile) {
        if (tile == null) {
            throw new NullPointerException("tile");
        }
        final int minHeight = tile.getMinHeight();
        final int maxHeight = tile.getMaxHeight();
        if (maxHeight - minHeight > 256) {
            throw new IllegalArgumentException("Tall tiles are not representable by TileView v1 (height range "
                + (maxHeight - minHeight) + " > 256, tile " + tile.getX() + "," + tile.getY() + ")");
        }

        synchronized (tile) {
            tile.ensureReadable(Tile.TileBuffer.HEIGHTMAP);
            tile.ensureReadable(Tile.TileBuffer.TERRAIN);
            tile.ensureReadable(Tile.TileBuffer.WATERLEVEL);
            tile.ensureReadable(Tile.TileBuffer.LAYER_DATA);
            tile.ensureReadable(Tile.TileBuffer.BIT_LAYER_DATA);
            return TileView.ofArrays(tile.getX(), tile.getY(), minHeight, maxHeight,
                tile.heightMap, tile.terrain, tile.waterLevel, tile.bitLayerData, tile.layerData);
        }
    }
}
