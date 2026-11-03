package org.pepsoft.worldpainter.heightMaps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.util.Random;

import static java.awt.image.DataBuffer.*;
import static org.junit.Assert.assertEquals;

/**
 * The range a {@link CachedImageHeightMap} capture computes from samples arriving row by row, against
 * {@link BitmapHeightMap#getRange()} of the same samples in memory (x major scan, first of equal values, early stop at
 * the type bounds) in every configuration: Java or native generation, with and without native floating point ranges.
 * Rasters, not TIFF files, since the TIFF reader clamps floating point samples to 0..1.
 */
public class CachedImageHeightMapTest {
    @Before
    public void setUp() {
        previousGeneration = System.getProperty(Native.GEN_KEY);
        previousFloatRanges = System.getProperty(FLOAT_RANGES);
    }

    @After
    public void tearDown() {
        restore(Native.GEN_KEY, previousGeneration);
        restore(FLOAT_RANGES, previousFloatRanges);
    }

    @Test
    public void floatRangesFollowTheDenseScanOrder() {
        final float max = Float.MAX_VALUE, inf = Float.POSITIVE_INFINITY;
        // {background, {x, y, value}...}
        final Object[][] cases = {
                // Minimum zero: the first zero of the x major scan, +0 at (0,1) before -0 at (1,0), and the reverse
                {5f, new float[][] {{0, 0, 1}, {0, 1, 0.0f}, {1, 0, -0.0f}}},
                {5f, new float[][] {{0, 0, 1}, {0, 1, -0.0f}, {1, 0, 0.0f}}},
                // Maximum zero
                {-5f, new float[][] {{0, 1, -0.0f}, {1, 0, 0.0f}}},
                // Both bounds before -Inf: the scan stops at (1,0) and never sees the infinities
                {5f, new float[][] {{0, 3, -max}, {1, 0, max}, {2, 0, -inf}, {3, 1, inf}}},
                // -Inf before the stop, +Inf after it
                {5f, new float[][] {{0, 2, -inf}, {1, 1, max}, {2, 0, inf}}},
                // Only the low bound: no stop
                {5f, new float[][] {{0, 2, -max}, {4, 4, -inf}, {3, 1, 7}}},
                // Both bounds in one column, the infinity below them in the same column
                {5f, new float[][] {{2, 0, max}, {2, 1, -max}, {2, 2, -inf}}},
                // NaN never counts
                {Float.NaN, new float[][] {{2, 2, 3}, {1, 3, -2}}}};
        for (int i = 0; i < cases.length; i++) {
            final WritableRaster raster = raster(TYPE_FLOAT, 6, 5);
            fill(raster, (float) cases[i][0]);
            for (float[] point: (float[][]) cases[i][1]) {
                raster.setSample((int) point[0], (int) point[1], 0, point[2]);
            }
            check("case " + i, raster);
        }
    }

    @Test
    public void rangesOfRandomArrangementsOfSpecialValuesMatch() {
        // Many arrangements, not one: special values at random positions, float and double, several sizes (one crossing
        // a block boundary), and integer types with their bounds
        final float[] floats = {Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -1, -0.0f, 0.0f, Float.MIN_VALUE, 1, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN, 3.5f};
        final double[] doubles = {Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -Float.MAX_VALUE, -0.0, 0.0, 2, Float.MAX_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN};
        final Random random = new Random(20261103);
        for (int round = 0; round < 400; round++) {
            final int width = 1 + random.nextInt(9), height = 1 + random.nextInt(8);
            final WritableRaster floatRaster = raster(TYPE_FLOAT, width, height);
            final WritableRaster doubleRaster = raster(TYPE_DOUBLE, width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    floatRaster.setSample(x, y, 0, floats[random.nextInt(floats.length)]);
                    doubleRaster.setSample(x, y, 0, doubles[random.nextInt(doubles.length)]);
                }
            }
            check("float round " + round, floatRaster);
            check("double round " + round, doubleRaster);
        }
        // Across a block boundary: 300 columns, zeroes and bounds in both blocks
        final WritableRaster wide = raster(TYPE_FLOAT, 300, 3);
        fill(wide, 2);
        wide.setSample(290, 0, 0, -0.0f);
        wide.setSample(10, 2, 0, 0.0f);
        check("zeroes across blocks", wide);
        wide.setSample(280, 1, 0, Float.MAX_VALUE);
        wide.setSample(20, 0, 0, -Float.MAX_VALUE);
        wide.setSample(270, 0, 0, Float.NEGATIVE_INFINITY);
        wide.setSample(5, 1, 0, Float.POSITIVE_INFINITY);
        check("bounds across blocks", wide);
        // Integer types: bounds in any order give the bounds
        for (int type: new int[] {TYPE_BYTE, TYPE_USHORT, TYPE_SHORT, TYPE_INT}) {
            for (int round = 0; round < 50; round++) {
                final WritableRaster raster = raster(type, 5, 4);
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 5; x++) {
                        raster.setSample(x, y, 0, random.nextInt());
                    }
                }
                check("type " + type + " round " + round, raster);
            }
        }
    }

    private void check(String label, WritableRaster raster) {
        final double[] actual = CachedImageHeightMap.captureRange(raster);
        final int type = raster.getTransferType();
        final ColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, type);
        for (String generation: new String[] {"false", "true"}) {
            for (String floatRanges: new String[] {"false", "true"}) {
                System.setProperty(Native.GEN_KEY, generation);
                System.setProperty(FLOAT_RANGES, floatRanges);
                final double[] dense = BitmapHeightMap.build().withImage(new BufferedImage(model, raster, false, null)).now().getRange();
                final String configuration = label + " (gen " + generation + ", native float ranges " + floatRanges + ")";
                assertEquals(configuration + " low", Double.doubleToRawLongBits(dense[0]), Double.doubleToRawLongBits(actual[0]));
                assertEquals(configuration + " high", Double.doubleToRawLongBits(dense[1]), Double.doubleToRawLongBits(actual[1]));
            }
        }
    }

    private static WritableRaster raster(int type, int width, int height) {
        return new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, type)
                .createCompatibleWritableRaster(width, height);
    }

    private static void fill(WritableRaster raster, float value) {
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                raster.setSample(x, y, 0, value);
            }
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private String previousGeneration, previousFloatRanges;

    private static final String FLOAT_RANGES = "welt.native.floatingImageRanges";
}
