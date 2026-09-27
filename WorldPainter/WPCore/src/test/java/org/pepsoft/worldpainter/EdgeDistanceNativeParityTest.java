package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.NotPresent;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Verifies the Rust edge-distance slice against the original Java algorithm. */
public final class EdgeDistanceNativeParityTest {
    @Test
    public void jniKernelMatchesTheJavaEdgeWalkBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        Native.setExportEnabled(true);
        try {
            final int width = 17;
            final int height = 13;
            final float maxDistance = 5.5f;
            final byte[] mask = new byte[width * height];
            for (int y = 3; y < 10; y++) {
                for (int x = 4; x < 13; x++) {
                    if (!((x >= 7 && x <= 9) && (y >= 5 && y <= 7))) {
                        mask[y * width + x] = 1;
                    }
                }
            }
            final float[] nativeValues = NativeSlices.edgeDistances(width, height, maxDistance, mask);
            assertNotNull(nativeValues);
            final float[] javaValues = javaEdgeDistances(width, height, maxDistance, mask);
            assertEquals(javaValues.length, nativeValues.length);
            for (int i = 0; i < javaValues.length; i++) {
                assertEquals("index=" + i, Float.floatToRawIntBits(javaValues[i]),
                        Float.floatToRawIntBits(nativeValues[i]));
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void dimensionBakeMatchesJavaAcrossTileBoundary() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 256, 128), 62);
            final Tile left = dimension.getTile(0, 0);
            final Tile right = dimension.getTile(1, 0);
            for (int y = 20; y < 81; y++) {
                for (int x = 90; x < 128; x++) {
                    left.setBitLayerValue(Frost.INSTANCE, x, y, true);
                }
                for (int x = 0; x < 36; x++) {
                    right.setBitLayerValue(Frost.INSTANCE, x, y, true);
                }
            }

            Native.setExportEnabled(true);
            final HeightMap nativeMap = dimension.getDistancesToEdge(Frost.INSTANCE, 12.5f);
            Native.setExportEnabled(false);
            final HeightMap javaMap = dimension.getDistancesToEdge(Frost.INSTANCE, 12.5f);
            for (int y = 20; y < 81; y++) {
                for (int x = 90; x < 164; x++) {
                    final int worldX = x;
                    final int worldY = y;
                    assertEquals("x=" + worldX + " y=" + worldY,
                            Double.doubleToRawLongBits(javaMap.getHeight(worldX, worldY)),
                            Double.doubleToRawLongBits(nativeMap.getHeight(worldX, worldY)));
                }
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void bitPerChunkLayerBakeMatchesJava() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
            final Tile tile = dimension.getTile(0, 0);
            for (int chunkY = 2; chunkY < 6; chunkY++) {
                for (int chunkX = 2; chunkX < 6; chunkX++) {
                    if (!((chunkX == 3) && (chunkY == 3))) {
                        tile.setBitLayerValue(NotPresent.INSTANCE, chunkX << 4, chunkY << 4, true);
                    }
                }
            }

            Native.setExportEnabled(true);
            final HeightMap nativeMap = dimension.getDistancesToEdge(NotPresent.INSTANCE, 9.5f);
            Native.setExportEnabled(false);
            final HeightMap javaMap = dimension.getDistancesToEdge(NotPresent.INSTANCE, 9.5f);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    if (tile.getBitLayerValue(NotPresent.INSTANCE, x, y)) {
                        assertEquals("x=" + x + " y=" + y,
                                Double.doubleToRawLongBits(javaMap.getHeight(x, y)),
                                Double.doubleToRawLongBits(nativeMap.getHeight(x, y)));
                    }
                }
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    private static float[] javaEdgeDistances(int width, int height, float maxDistance, byte[] mask) {
        final int radius = (int) Math.ceil(maxDistance);
        final float[] distances = new float[width * height];
        java.util.Arrays.fill(distances, maxDistance);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                if ((mask[index] != 0) || !hasPaintedNeighbour(mask, width, height, x, y)) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    final int targetY = y + dy;
                    if ((targetY < 0) || (targetY >= height)) {
                        continue;
                    }
                    for (int dx = -radius; dx <= radius; dx++) {
                        final int targetX = x + dx;
                        if ((targetX < 0) || (targetX >= width)) {
                            continue;
                        }
                        final int target = targetY * width + targetX;
                        if (mask[target] != 0) {
                            final float distance = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
                            if (distance < distances[target]) {
                                distances[target] = distance;
                            }
                        }
                    }
                }
            }
        }
        return distances;
    }

    private static boolean hasPaintedNeighbour(byte[] mask, int width, int height, int x, int y) {
        return ((x > 0) && (mask[y * width + x - 1] != 0))
                || ((x + 1 < width) && (mask[y * width + x + 1] != 0))
                || ((y > 0) && (mask[(y - 1) * width + x] != 0))
                || ((y + 1 < height) && (mask[(y + 1) * width + x] != 0));
    }

    private static void restoreFlag(String previousFlag) {
        if (previousFlag == null) {
            System.clearProperty(Native.EXPORT_KEY);
        } else {
            System.setProperty(Native.EXPORT_KEY, previousFlag);
        }
    }
}
