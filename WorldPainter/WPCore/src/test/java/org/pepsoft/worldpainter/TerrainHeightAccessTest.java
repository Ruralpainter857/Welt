package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;

import java.awt.Rectangle;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TerrainHeightAccessTest {
    @Test
    public void regionsCrossNegativeCoordinatesMissingTilesAndIntegerWrap() {
        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        dimension.addTile(new Tile(-1, -1, TestData.MIN_HEIGHT, TestData.MAX_HEIGHT));
        dimension.addTile(new Tile(Integer.MAX_VALUE >> 7, 0, TestData.MIN_HEIGHT, TestData.MAX_HEIGHT));
        dimension.addTile(new Tile(Integer.MIN_VALUE >> 7, 0, TestData.MIN_HEIGHT, TestData.MAX_HEIGHT));
        dimension.setHeightAt(-1, -1, 13.125f);
        dimension.setHeightAt(Integer.MAX_VALUE, 1, 47.75f);
        dimension.setHeightAt(Integer.MIN_VALUE, 1, 89.5f);
        checkRead(dimension, -3, -4, 140, 138);
        checkRead(dimension, Integer.MAX_VALUE - 2, 0, 7, 3);
        final float[] values = new float[20];
        final byte[] modified = new byte[20];
        Arrays.fill(values, 73.123f);
        Arrays.fill(modified, (byte) 1);
        dimension.setEventsInhibited(true);
        try {
            TerrainHeightAccess.apply(dimension, Integer.MAX_VALUE - 2, 0, 5, 4, values, modified);
        } finally {
            dimension.setEventsInhibited(false);
        }
        checkRead(dimension, Integer.MAX_VALUE - 2, 0, 5, 4);
        for (int x = 0; x < 5; x++) {
            assertEquals(73.12109375f, dimension.getHeightAt(Integer.MAX_VALUE - 2 + x, 0), 0f);
        }
    }

    @Test
    public void groupedWritesMatchScalarQuantisationAndPreserveUndoAndSharedBuffers() {
        for (int maxHeight : new int[] {256, 512}) {
            final Tile tile = new Tile(0, 0, 0, maxHeight);
            final Tile untouched = new Tile(1, 0, 0, maxHeight);
            final Tile reference = new Tile(2, 0, 0, maxHeight);
            final UndoManager undo = new UndoManager();
            tile.register(undo);
            undo.armSavePoint();
            final float[] values = {73.123f, Float.NaN, -1f, Float.POSITIVE_INFINITY, 99.5f, 1f};
            final byte[] mask = {1, 1, 1, 1, 0, 1};
            tile.inhibitEvents();
            try {
                tile.applyHeightRegion(3, 5, 2, 3, values, mask, 0, 3);
            } finally {
                tile.releaseEvents();
            }
            for (int x = 0; x < 2; x++) {
                for (int y = 0; y < 3; y++) {
                    if (mask[x * 3 + y] != 0) {
                        reference.setHeight(3 + x, 5 + y, values[x * 3 + y]);
                    }
                    assertEquals(reference.getRawHeight(3 + x, 5 + y), tile.getRawHeight(3 + x, 5 + y));
                    assertEquals(0, untouched.getRawHeight(3 + x, 5 + y));
                }
            }
            assertTrue(undo.undo());
            assertEquals(0, tile.getRawHeight(3, 5));
            assertTrue(undo.redo());
            assertEquals(reference.getRawHeight(3, 5), tile.getRawHeight(3, 5));
        }
    }

    @Test
    public void maskedRegionWritesPreserveMissingTilesAndUntouchedCells() {
        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        final float[] values = new float[24];
        final byte[] mask = new byte[24];
        Arrays.fill(values, 91.75f);
        mask[0] = mask[7] = mask[23] = 1;
        dimension.setEventsInhibited(true);
        try {
            TerrainHeightAccess.apply(dimension, 126, 126, 6, 4, values, mask);
        } finally {
            dimension.setEventsInhibited(false);
        }
        assertEquals(91.75f, dimension.getHeightAt(126, 126), 0f);
        assertEquals(62f, dimension.getHeightAt(126, 127), 0f);
        assertEquals(-Float.MAX_VALUE, dimension.getHeightAt(131, 129), 0f);
        assertEquals(1, dimension.getTiles().size());
    }

    private static void checkRead(Dimension dimension, int originX, int originY, int width, int height) {
        final float[] values = new float[width * height];
        TerrainHeightAccess.copy(dimension, originX, originY, width, height, values);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                assertEquals(Float.floatToRawIntBits(dimension.getHeightAt(originX + x, originY + y)),
                        Float.floatToRawIntBits(values[x * height + y]));
            }
        }
    }
}
