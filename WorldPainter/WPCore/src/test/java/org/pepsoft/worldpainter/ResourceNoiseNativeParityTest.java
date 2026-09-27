package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.minecraft.ChunkPaletteBuffer;
import org.pepsoft.minecraft.MC115AnvilChunk;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.pepsoft.minecraft.Constants.MC_DEEPSLATE;
import static org.pepsoft.minecraft.Material.*;

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
            final byte[] reusedOutput = new byte[actual.length];
            assertTrue(NativeSlices.resourceMaterialsInto(minZ, maxZ,
                    tinyX, tinyY, dirtX, dirtY, columnMinZ, columnMaxZ,
                    resourceValues, seeds, materialMinZ, materialMaxZ,
                    dirtMaterials, chances, reusedOutput));
            org.junit.Assert.assertArrayEquals(actual, reusedOutput);
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

    @Test
    public void nativeResourceResultsMutateLivePaletteIndexesWithJavaParity() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String oldExport = System.getProperty(Native.EXPORT_KEY);
        final String oldResources = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        final String oldCompact = System.getProperty("welt.packedArrayCube.compactPaletteStorage");
        Native.setExportEnabled(true);
        System.setProperty(Native.RESOURCES_EXPORT_KEY, "true");
        System.setProperty("welt.packedArrayCube.compactPaletteStorage", "true");
        try {
            final int minY = 0, maxY = 15, height = 16;
            final double[] tinyX = new double[256], tinyY = new double[256];
            final double[] dirtX = new double[256], dirtY = new double[256];
            final int[] columnMinY = new int[256], columnMaxY = new int[256];
            final int[] resourceValues = new int[256];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    final int column = x * 16 + z;
                    tinyX[column] = x / 4.099f;
                    tinyY[column] = z / 4.099f;
                    dirtX[column] = x / 16.411f;
                    dirtY[column] = z / 16.411f;
                    columnMinY[column] = minY;
                    columnMaxY[column] = maxY;
                    resourceValues[column] = 1;
                }
            }
            final long[] seeds = {0x54a31L};
            final int[] materialMinY = {minY}, materialMaxY = {maxY};
            final byte[] dirtMaterials = {0};
            final float[] chances = new float[16];
            final MC115AnvilChunk expected = new MC115AnvilChunk(0, 0, 16);
            final MC115AnvilChunk actual = new MC115AnvilChunk(0, 0, 16);
            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final Material initial = ((x + z + y) % 5 == 0)
                                ? Material.get(MC_DEEPSLATE) : STONE;
                        expected.setMaterial(x, y, z, initial);
                        actual.setMaterial(x, y, z, initial);
                    }
                }
            }

            final byte[] selected = NativeSlices.resourceMaterials(minY, maxY,
                    tinyX, tinyY, dirtX, dirtY, columnMinY, columnMaxY, resourceValues,
                    seeds, materialMinY, materialMaxY, dirtMaterials, chances);
            assertNotNull("reference decision buffer", selected);
            int selectedCount = 0, deepslateMatches = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    final int column = x * 16 + z;
                    for (int y = maxY; y >= minY; y--) {
                        if ((selected[column * height + y - minY] & 0xff) == 0) {
                            continue;
                        }
                        selectedCount++;
                        final Material existing = expected.getMaterial(x, y, z);
                        if (existing.isNamed(MC_DEEPSLATE)) {
                            deepslateMatches++;
                            expected.setMaterial(x, y, z, DEEPSLATE_DIAMOND_ORE);
                        } else {
                            expected.setMaterial(x, y, z, DIAMOND_ORE);
                        }
                    }
                }
            }
            assertTrue("fixture must exercise actual ore placement", selectedCount > 0);
            assertTrue("fixture must exercise deepslate replacement", deepslateMatches > 0);

            final Material[] targets = {DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE};
            final ChunkPaletteBuffer.LivePaletteView view =
                    ChunkPaletteBuffer.openLivePaletteView(actual, minY, maxY, targets);
            assertTrue("compact modern chunk palette view", view != null);
            final int[][] indexes = {view.indexes(0)};
            final byte[][] paletteFlags = {new byte[view.paletteSize(0)]};
            for (int paletteIndex = 0; paletteIndex < paletteFlags[0].length; paletteIndex++) {
                final Material material = view.paletteMaterial(0, paletteIndex);
                paletteFlags[0][paletteIndex] = (byte) ((material != null
                        && material.isNamed(MC_DEEPSLATE)) ? 1 : 0);
            }
            final int[][] outputPaletteIndexes = {{
                    view.paletteIndex(0, targets[0]), view.paletteIndex(0, targets[1])
            }};
            final long[] profileNanos = new long[2], applyNanos = new long[1];
            final byte[] resultBuffer = new byte[selected.length];
            assertTrue(NativeSlices.resourceMaterialsIntoPalette(minY, maxY,
                    tinyX, tinyY, dirtX, dirtY, columnMinY, columnMaxY, resourceValues,
                    seeds, materialMinY, materialMaxY, dirtMaterials, chances,
                    resultBuffer, view.minY(), indexes.length, indexes, paletteFlags, outputPaletteIndexes,
                    profileNanos, applyNanos));
            assertArrayEquals(selected, resultBuffer);

            for (int y = minY; y <= maxY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals("block " + x + "," + y + "," + z,
                                expected.getMaterial(x, y, z), actual.getMaterial(x, y, z));
                    }
                }
            }
        } finally {
            restore(Native.EXPORT_KEY, oldExport);
            restore(Native.RESOURCES_EXPORT_KEY, oldResources);
            restore("welt.packedArrayCube.compactPaletteStorage", oldCompact);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
