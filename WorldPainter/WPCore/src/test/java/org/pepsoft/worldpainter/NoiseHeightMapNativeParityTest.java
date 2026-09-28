package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.DifferenceHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.MinimisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MaximisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MandelbrotHeightMap;
import org.pepsoft.worldpainter.heightMaps.ProductHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Compares the production JNI bulk path with NoiseHeightMap's Java output. */
public final class NoiseHeightMapNativeParityTest {
    @Test
    public void nativeGenerationDefaultsOnAndCanBeDisabled() {
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        final String previousNinePatchFlag = System.getProperty(Native.NINE_PATCH_GEN_KEY);
        try {
            System.clearProperty(Native.GEN_KEY);
            System.clearProperty(Native.NINE_PATCH_GEN_KEY);
            assertTrue("native generation should be enabled by default", Native.isGenEnabled());
            assertFalse("NinePatch should keep its Java path by default", Native.isNinePatchGenEnabled());
            Native.setNinePatchGenEnabled(true);
            assertTrue("NinePatch native path should be explicitly selectable", Native.isNinePatchGenEnabled());
            Native.setGenEnabled(false);
            assertFalse("the Java fallback must remain explicitly selectable", Native.isGenEnabled());
            assertFalse("the NinePatch override must still require native generation", Native.isNinePatchGenEnabled());
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
            if (previousNinePatchFlag == null) {
                System.clearProperty(Native.NINE_PATCH_GEN_KEY);
            } else {
                System.setProperty(Native.NINE_PATCH_GEN_KEY, previousNinePatchFlag);
            }
        }
    }

    @Test
    public void nativeBulkMatchesJavaBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final double[][] cases = {
                    {512.0, 1.25, 1},
                    {83.5, 0.375, 2},
                    {240.0, 2.75, 6},
                    {700.0, 0.03125, 10}
            };
            final long[] offsets = {0x1234_5678L, -0x0123_4567L, 0x3141_5926L, 0x5eed_5eedL};
            final int[][] origins = {{-1024, -256}, {-128, 896}, {0, 0}, {12_288, -8192}};
            for (int caseIndex = 0; caseIndex < cases.length; caseIndex++) {
                final double[] spec = cases[caseIndex];
                final NoiseHeightMap map = new NoiseHeightMap(spec[0], spec[1], (int) spec[2], offsets[caseIndex]);
                map.setSeed(0x7fff_ffffL - caseIndex);
                final int originX = origins[caseIndex][0];
                final int originY = origins[caseIndex][1];
                final double[] nativeValues = map.getNativeHeights(originX, originY, 128, 128);
                assertNotNull("native bridge must be active for case " + caseIndex, nativeValues);
                final double[] reusedValues = new double[128 * 128];
                Arrays.fill(reusedValues, Double.NaN);
                assertTrue("caller-owned native buffer should be filled", map.fillNativeHeights(
                        originX, originY, 128, 128, reusedValues));
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        final double javaValue = map.getHeight(originX + x, originY + y);
                        assertEquals("case=" + caseIndex + " x=" + (originX + x) + " y=" + (originY + y),
                                Double.doubleToRawLongBits(javaValue),
                                Double.doubleToRawLongBits(nativeValues[y * 128 + x]));
                        assertEquals("reused buffer case=" + caseIndex + " x=" + (originX + x)
                                        + " y=" + (originY + y),
                                Double.doubleToRawLongBits(javaValue),
                                Double.doubleToRawLongBits(reusedValues[y * 128 + x]));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeRectanglesMatchJavaAtIntegerCoordinateBoundaries() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final int[][] rectangles = {
                    {0, 0, 7, 3},
                    {Integer.MAX_VALUE - 2, Integer.MIN_VALUE + 2, 5, 9},
                    {Integer.MIN_VALUE + 2, Integer.MAX_VALUE - 3, 1, 17}
            };
            final double[][] specs = {
                    {128.0, 0.75, 1},
                    {375.0, 1.125, 4},
                    {91.0, 0.0625, 10}
            };
            for (int caseIndex = 0; caseIndex < rectangles.length; caseIndex++) {
                final int[] rectangle = rectangles[caseIndex];
                final double[] spec = specs[caseIndex];
                final NoiseHeightMap map = new NoiseHeightMap(spec[0], spec[1], (int) spec[2],
                        0x6a09_e667L + caseIndex);
                map.setSeed(0xbb67_ae85L - caseIndex);
                final double[] nativeValues = map.getNativeHeights(rectangle[0], rectangle[1], rectangle[2], rectangle[3]);
                assertNotNull("native bridge must be active for rectangle " + caseIndex, nativeValues);
                assertEquals(rectangle[2] * rectangle[3], nativeValues.length);
                for (int y = 0; y < rectangle[3]; y++) {
                    for (int x = 0; x < rectangle[2]; x++) {
                        // Java int addition intentionally wraps, matching the JNI kernel's wrapping_add.
                        final int worldX = rectangle[0] + x;
                        final int worldY = rectangle[1] + y;
                        assertEquals("rectangle=" + caseIndex + " x=" + worldX + " y=" + worldY,
                                Double.doubleToRawLongBits(map.getHeight(worldX, worldY)),
                                Double.doubleToRawLongBits(nativeValues[y * rectangle[2] + x]));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeNoisePreservesNonPositiveOctaveEdgeCases() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            for (final int octaves : new int[] {0, -3}) {
                final NoiseHeightMap map = new NoiseHeightMap(128.0, 0.75, octaves, 0x243f_6a88L);
                map.setSeed(0x85a3_08d3L);
                final double[] nativeValues = map.getNativeHeights(-19, 23, 5, 7);
                assertNotNull("native bridge must be active for octaves=" + octaves, nativeValues);
                for (int y = 0; y < 7; y++) {
                    for (int x = 0; x < 5; x++) {
                        final double javaValue = map.getHeight(-19 + x, 23 + y);
                        assertEquals("octaves=" + octaves + " x=" + (-19 + x) + " y=" + (23 + y),
                                Double.doubleToLongBits(javaValue),
                                Double.doubleToLongBits(nativeValues[y * 5 + x]));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeNestedSumProgramMatchesJavaBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final NoiseHeightMap firstNoise = new NoiseHeightMap(83.5, 0.375, 2, -0x0123_4567L);
            final NoiseHeightMap secondNoise = new NoiseHeightMap(240.0, 2.75, 6, 0x3141_5926L);
            final SumHeightMap tree = new SumHeightMap(
                    new SumHeightMap(new ConstantHeightMap(0.1), firstNoise), secondNoise);
            tree.setSeed(0x7fff_ffffL);

            // Post-order: (constant + firstNoise) + secondNoise.
            final int[] opcodes = {0, 1, 2, 1, 2};
            final double[] values = {0.1, firstNoise.getHeight(), 0.0,
                    secondNoise.getHeight(), 0.0};
            final double[] scales = {0.0, firstNoise.getScale(), 0.0,
                    secondNoise.getScale(), 0.0};
            final int[] octaves = {0, firstNoise.getOctaves(), 0,
                    secondNoise.getOctaves(), 0};
            final long[] seeds = {0L, firstNoise.getSeed() + firstNoise.getSeedOffset(), 0L,
                    secondNoise.getSeed() + secondNoise.getSeedOffset(), 0L};
            final int originX = -257, originY = Integer.MAX_VALUE - 12;
            final int width = 17, height = 11;
            final double[] output = new double[width * height];
            assertTrue(NativeSlices.fillHeightMapTree(originX, originY, width, height,
                    opcodes.length, opcodes, values, scales, octaves, seeds, output));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    assertEquals("nested sum at " + x + ',' + y,
                            Double.doubleToRawLongBits(tree.getHeight(originX + x, originY + y)),
                            Double.doubleToRawLongBits(output[y * width + x]));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeProductAndDifferenceProgramMatchesJavaBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final NoiseHeightMap firstNoise = new NoiseHeightMap(83.5, 0.375, 2, -0x0123_4567L);
            final NoiseHeightMap secondNoise = new NoiseHeightMap(24.0, 2.75, 6, 0x3141_5926L);
            final DifferenceHeightMap tree = new DifferenceHeightMap(
                    new ProductHeightMap(
                            new SumHeightMap(new ConstantHeightMap(20.1), firstNoise),
                            secondNoise),
                    new ConstantHeightMap(12.5));
            tree.setSeed(0x7fff_ffffL);

            // Post-order: ((constant + firstNoise) * secondNoise) - constant.
            final int[] opcodes = {0, 1, 2, 1, 4, 0, 3};
            final double[] values = {20.1, firstNoise.getHeight(), 0.0,
                    secondNoise.getHeight(), 0.0, 12.5, 0.0};
            final double[] scales = {0.0, firstNoise.getScale(), 0.0,
                    secondNoise.getScale(), 0.0, 0.0, 0.0};
            final int[] octaves = {0, firstNoise.getOctaves(), 0,
                    secondNoise.getOctaves(), 0, 0, 0};
            final long[] seeds = {0L, firstNoise.getSeed() + firstNoise.getSeedOffset(), 0L,
                    secondNoise.getSeed() + secondNoise.getSeedOffset(), 0L, 0L, 0L};
            final int originX = -257, originY = Integer.MAX_VALUE - 12;
            final int width = 17, height = 11;
            final double[] output = new double[width * height];
            assertTrue(NativeSlices.fillHeightMapTree(originX, originY, width, height,
                    opcodes.length, opcodes, values, scales, octaves, seeds, output));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    assertEquals("composite at " + x + ',' + y,
                            Double.doubleToRawLongBits(tree.getHeight(originX + x, originY + y)),
                            Double.doubleToRawLongBits(output[y * width + x]));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeMinimumAndMaximumMatchJavaNaNPayloadAndSignedZero() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final double nanA = Double.longBitsToDouble(0x7ff8_0000_0000_0001L);
            final double nanB = Double.longBitsToDouble(0x7ff8_0000_0000_0002L);
            final double[][] cases = {{-0.0, 0.0}, {0.0, -0.0}, {nanA, nanB}, {3.0, nanB}, {nanA, 3.0}};
            for (double[] operands : cases) {
                final double[] values = {operands[0], operands[1], 0.0};
                final int[] opcodes = {0, 0, 5};
                final double[] output = new double[1];
                assertTrue(NativeSlices.fillHeightMapTree(0, 0, 1, 1, 3, opcodes,
                        values, new double[3], new int[3], new long[3], output));
                assertEquals(Double.doubleToRawLongBits(Math.min(operands[0], operands[1])),
                        Double.doubleToRawLongBits(output[0]));
                opcodes[2] = 6;
                assertTrue(NativeSlices.fillHeightMapTree(0, 0, 1, 1, 3, opcodes,
                        values, new double[3], new int[3], new long[3], output));
                assertEquals(Double.doubleToRawLongBits(Math.max(operands[0], operands[1])),
                        Double.doubleToRawLongBits(output[0]));
            }

            final NoiseHeightMap noise = new NoiseHeightMap(30.0, 1.0, 2, 0x1357L);
            final int[] opcodes = {0, 1, 5};
            final double[] values = {45.0, noise.getHeight(), 0.0};
            final double[] scales = {0.0, noise.getScale(), 0.0};
            final int[] octaves = {0, noise.getOctaves(), 0};
            final long[] seeds = {0L, noise.getSeed() + noise.getSeedOffset(), 0L};
            final double[] output = new double[7 * 5];
            assertTrue(NativeSlices.fillHeightMapTree(-9, 17, 7, 5, 3, opcodes,
                    values, scales, octaves, seeds, output));
            final MinimisingHeightMap min = new MinimisingHeightMap(new ConstantHeightMap(45.0), noise);
            final MaximisingHeightMap max = new MaximisingHeightMap(new ConstantHeightMap(45.0), noise);
            for (int i = 0; i < output.length; i++) {
                assertEquals(Double.doubleToRawLongBits(min.getHeight(-9 + i % 7, 17 + i / 7)),
                        Double.doubleToRawLongBits(output[i]));
            }
            opcodes[2] = 6;
            assertTrue(NativeSlices.fillHeightMapTree(-9, 17, 7, 5, 3, opcodes,
                    values, scales, octaves, seeds, output));
            for (int i = 0; i < output.length; i++) {
                assertEquals(Double.doubleToRawLongBits(max.getHeight(-9 + i % 7, 17 + i / 7)),
                        Double.doubleToRawLongBits(output[i]));
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeMandelbrotProgramMatchesJavaBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final MandelbrotHeightMap map = new MandelbrotHeightMap();
            final int[] opcodes = {8};
            final double[] output = new double[17 * 13];
            assertTrue(NativeSlices.fillHeightMapTree(-12, -8, 17, 13, 1,
                    opcodes, new double[1], new double[1], new int[1], new long[1], output));
            for (int y = 0; y < 13; y++) {
                for (int x = 0; x < 17; x++) {
                    assertEquals("Mandelbrot at " + x + ',' + y,
                            Double.doubleToRawLongBits(map.getHeight(-12 + x, -8 + y)),
                            Double.doubleToRawLongBits(output[y * 17 + x]));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }
}
