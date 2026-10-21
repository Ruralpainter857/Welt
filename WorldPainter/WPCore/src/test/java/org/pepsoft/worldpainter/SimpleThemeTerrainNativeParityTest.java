package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Compares the bulk Rust terrain selector with SimpleTheme.getTerrain. */
public final class SimpleThemeTerrainNativeParityTest {
    @Test
    public void nativeTerrainMatchesJavaAcrossThemeModesAndCoordinateEdges() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            assertMatches(0, 256, 62, true, true, 0x243f_6a88L,
                    Integer.MAX_VALUE - 3, Integer.MIN_VALUE + 2);
            assertMatches(-64, 384, 40, true, false, -0x1234_5678L, -4096, 2048);
            assertMatches(0, 256, 62, false, true, 0x85a3_08d3L, 128, -128);
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    private static void assertMatches(final int minHeight, final int maxHeight,
                                      final int waterHeight, final boolean randomise,
                                      final boolean beaches, final long seed,
                                      final int originX, final int originY) {
        final int width = 19, height = 13;
        final SimpleTheme theme = SimpleTheme.createDefault(
                Terrain.GRASS, minHeight, maxHeight, waterHeight, randomise, beaches);
        theme.setSeed(seed);
        final int[] heights = new int[width * height];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = switch (i % 7) {
                case 0 -> minHeight - 100;
                case 1 -> waterHeight - 2;
                case 2 -> waterHeight - 1;
                case 3 -> waterHeight;
                case 4 -> waterHeight + 1;
                case 5 -> maxHeight - 1;
                default -> maxHeight + 100;
            };
        }
        final int[] actual = NativeSlices.simpleThemeTerrains(
                originX, originY, width, height, minHeight, maxHeight,
                waterHeight, randomise, beaches, Terrain.BEACHES.ordinal(), seed,
                heights, theme.getTerrainRangeOrdinals());
        assertNotNull("native terrain bridge", actual);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                final int clampedHeight = Math.max(minHeight, Math.min(maxHeight - 1, heights[index]));
                assertEquals("randomise=" + randomise + " beaches=" + beaches
                                + " x=" + (originX + x) + " y=" + (originY + y)
                                + " height=" + clampedHeight,
                        theme.getTerrain(originX + x, originY + y, clampedHeight).ordinal(),
                        actual[index]);
            }
        }
    }
}
