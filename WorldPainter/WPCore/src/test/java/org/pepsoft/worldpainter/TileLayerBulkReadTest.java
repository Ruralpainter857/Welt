package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Populate;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.layers.Void;

import static org.junit.Assert.assertEquals;

public final class TileLayerBulkReadTest {
    @Test
    public void copiesBitAndNumericLayersInXMajorOrder() {
        final Tile tile = new Tile(0, 0, 0, 64);
        tile.setBitLayerValue(Void.INSTANCE, 5, 6, true);
        tile.setBitLayerValue(Populate.INSTANCE, 4, 5, true);
        tile.setLayerValue(Resources.INSTANCE, 4, 5, 7);
        tile.setLayerValue(Resources.INSTANCE, 5, 7, 12);

        final byte[] voidValues = new byte[10];
        java.util.Arrays.fill(voidValues, (byte) 9);
        tile.copyBitLayerValues(Void.INSTANCE, 4, 5, 2, 3, voidValues, 2);
        assertEquals(0, voidValues[2]);
        assertEquals(0, voidValues[3]);
        assertEquals(0, voidValues[4]);
        assertEquals(0, voidValues[5]);
        assertEquals(1, voidValues[6]);
        assertEquals(0, voidValues[7]);
        assertEquals(9, voidValues[1]);
        assertEquals(9, voidValues[8]);

        final byte[] chunkValues = new byte[6];
        tile.copyBitLayerValues(Populate.INSTANCE, 4, 5, 2, 3, chunkValues, 0);
        for (byte value : chunkValues) {
            assertEquals(1, value);
        }

        final int[] resourceValues = new int[8];
        java.util.Arrays.fill(resourceValues, -1);
        tile.copyLayerValues(Resources.INSTANCE, 4, 5, 2, 3, resourceValues, 1);
        assertEquals(7, resourceValues[1]);
        assertEquals(0, resourceValues[2]);
        assertEquals(0, resourceValues[3]);
        assertEquals(0, resourceValues[4]);
        assertEquals(0, resourceValues[5]);
        assertEquals(12, resourceValues[6]);
        assertEquals(-1, resourceValues[0]);
        assertEquals(-1, resourceValues[7]);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsRectanglesOutsideTile() {
        final Tile tile = new Tile(0, 0, 0, 64);
        tile.copyBitLayerValues(Void.INSTANCE, 127, 0, 2, 1, new byte[2], 0);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsInsufficientDestination() {
        final Tile tile = new Tile(0, 0, 0, 64);
        tile.copyLayerValues(Resources.INSTANCE, 0, 0, 2, 2, new int[3], 0);
    }
}
