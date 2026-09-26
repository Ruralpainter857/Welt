package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Compares the production JNI bulk path with NoiseHeightMap's Java output. */
public final class NoiseHeightMapNativeParityTest {
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
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        final double javaValue = map.getHeight(originX + x, originY + y);
                        assertEquals("case=" + caseIndex + " x=" + (originX + x) + " y=" + (originY + y),
                                Double.doubleToRawLongBits(javaValue),
                                Double.doubleToRawLongBits(nativeValues[y * 128 + x]));
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
}
