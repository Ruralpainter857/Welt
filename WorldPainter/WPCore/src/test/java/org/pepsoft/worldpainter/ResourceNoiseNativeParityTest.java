package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Verifies batched Rust ore-noise decisions against the production Java noise. */
public final class ResourceNoiseNativeParityTest {
    @Test
    public void nativeDecisionsMatchJavaAcrossFrequenciesSeedsAndColumnBounds() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        Native.setExportEnabled(true);
        try {
            final int minZ = -64, maxZ = 127, height = maxZ - minZ + 1;
            final double[] tinyX = new double[9], tinyY = new double[9];
            final double[] dirtX = new double[9], dirtY = new double[9];
            final int[] columnMinZ = new int[9], columnMaxZ = new int[9];
            final int[] resourceValues = new int[9];
            for (int column = 0; column < 9; column++) {
                final int worldX = -384 + column * 79;
                final int worldY = 256 - column * 113;
                tinyX[column] = worldX / 4.099f;
                tinyY[column] = worldY / 4.099f;
                dirtX[column] = worldX / 16.411f;
                dirtY[column] = worldY / 16.411f;
                columnMinZ[column] = minZ - (column % 3) * 11;
                columnMaxZ[column] = 96 + (column % 4) * 13;
                resourceValues[column] = column == 8 ? 0 : 1 + column % 15;
            }
            final long[] seeds = {0, 1, -1, 0x1234_5678_9abcL, Long.MIN_VALUE + 7};
            final int[] materialMinZ = {-64, -20, 0, 32, 100};
            final int[] materialMaxZ = {127, 80, 127, 127, 127};
            final byte[] dirtMaterials = {0, 1, 0, 1, 0};
            final float[] chances = new float[seeds.length * 16];
            final PerlinNoise[] noises = new PerlinNoise[seeds.length];
            for (int material = 0; material < seeds.length; material++) {
                noises[material] = new PerlinNoise(seeds[material]);
                for (int value = 0; value < 16; value++) {
                    chances[material * 16 + value] = (material + value) % 5 == 0
                            ? 0.50001f : -0.5f + ((material * 17 + value * 11) % 97) / 194.0f;
                }
            }
            final byte[] actual = NativeSlices.resourceMaterials(minZ, maxZ,
                    tinyX, tinyY, dirtX, dirtY, columnMinZ, columnMaxZ,
                    resourceValues, seeds, materialMinZ, materialMaxZ, dirtMaterials, chances);
            assertNotNull("native resource-noise bridge", actual);
            assertEquals(9 * height, actual.length);
            for (int column = 0; column < 9; column++) {
                for (int y = minZ; y <= maxZ; y++) {
                    int expected = -1;
                    if (resourceValues[column] > 0 && y >= columnMinZ[column]
                            && y <= columnMaxZ[column]) {
                        for (int material = 0; material < seeds.length; material++) {
                            final float chance = chances[material * 16 + resourceValues[column]];
                            if (chance > 0.5f || y < materialMinZ[material] || y > materialMaxZ[material]) {
                                continue;
                            }
                            final float noise = dirtMaterials[material] != 0
                                    ? noises[material].getPerlinNoise(dirtX[column], dirtY[column], y / 16.411f)
                                    : noises[material].getPerlinNoise(tinyX[column], tinyY[column], y / 4.099f);
                            if (noise >= chance) {
                                expected = material;
                                break;
                            }
                        }
                    }
                    assertEquals("column=" + column + " y=" + y,
                            expected, (actual[column * height + y - minZ] & 0xff) - 1);
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.EXPORT_KEY);
            } else {
                System.setProperty(Native.EXPORT_KEY, previousFlag);
            }
        }
    }
}
