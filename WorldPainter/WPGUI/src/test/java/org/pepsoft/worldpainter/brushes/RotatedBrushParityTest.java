package org.pepsoft.worldpainter.brushes;

import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

public class RotatedBrushParityTest {
    private static void mode(boolean enabled) { System.setProperty("welt.native.brushRaster", Boolean.toString(enabled)); }

    @Test public void completeRotationsMatchAtEveryPixel() {
        boolean nativeRequired = Boolean.getBoolean("welt.test.requireBrushRaster");
        org.junit.Assume.assumeTrue("Optional release JNI test", nativeRequired);
        mode(true);
        assertTrue("Release raster symbol must execute", NativeSlices.expandBrushRaster(new float[]{0.5f}, 1, 0, new byte[1]));
        for (SymmetricBrush prototype : new SymmetricBrush[]{SymmetricBrush.CONSTANT_SQUARE, SymmetricBrush.LINEAR_SQUARE,
                SymmetricBrush.COSINE_SQUARE, SymmetricBrush.PLATEAU_SQUARE, SymmetricBrush.SPIKE_SQUARE, SymmetricBrush.DOME_SQUARE}) {
            for (int radius : new int[]{0, 1, 7, 31, 127}) {
                SymmetricBrush source = prototype.clone(); source.setRadius(radius);
                for (int angle : new int[]{-720, -91, 1, 17, 45, 89, 90, 180, 271, 359, 361}) {
                    mode(false); Brush expected = RotatedBrush.rotate(source.clone(), angle);
                    mode(true); Brush actual = RotatedBrush.rotate(source.clone(), angle);
                    for (float level : new float[]{1.0f, 0.375f, 0.0f}) {
                        expected.setLevel(level); actual.setLevel(level); compare(expected, actual);
                    }
                }
            }
        }
    }

    private static void compare(Brush expected, Brush actual) {
        assertEquals(expected.getBoundingBox(), actual.getBoundingBox());
        assertEquals(expected.getRadius(), actual.getRadius());
        int r = expected.getEffectiveRadius();
        int margin = expected instanceof RotatedBrush ? 1 : 0;
        for (int y = -r - margin; y <= r + margin; y++) for (int x = -r - margin; x <= r + margin; x++) {
            assertEquals(Float.floatToIntBits(expected.getFullStrength(x, y)), Float.floatToIntBits(actual.getFullStrength(x, y)));
            assertEquals(Float.floatToIntBits(expected.getStrength(x, y)), Float.floatToIntBits(actual.getStrength(x, y)));
        }
    }

    @Test public void radiusChangesAndClonesKeepTheirMasks() {
        org.junit.Assume.assumeTrue("Optional release JNI test", Boolean.getBoolean("welt.test.requireBrushRaster"));
        mode(false); RotatedBrush expected = (RotatedBrush) RotatedBrush.rotate(SymmetricBrush.COSINE_SQUARE.clone(), 17);
        mode(true); RotatedBrush actual = (RotatedBrush) RotatedBrush.rotate(SymmetricBrush.COSINE_SQUARE.clone(), 17);
        for (int radius : new int[]{127, 7, 126, 63, 0, 31}) {
            mode(false); expected.setRadius(radius); expected.setDegrees(radius + 19);
            mode(true); actual.setRadius(radius); actual.setDegrees(radius + 19);
            compare(expected, actual);
        }
        RotatedBrush snapshot = actual.clone(); mode(false); Brush reference = expected.clone();
        actual.setRadius(63); actual.setDegrees(91); actual.setLevel(0.125f);
        compare(reference, snapshot);
    }

    @Test public void exactQuantizationWithPaddedStrideAndSpecialFloats() {
        org.junit.Assume.assumeTrue("Optional release JNI test", Boolean.getBoolean("welt.test.requireBrushRaster"));
        mode(true);
        float[] values = {0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 1f, 0.5f, 256f, Float.MIN_VALUE};
        float[] source = new float[49]; for (int i = 0; i < source.length; i++) source[i] = values[i % values.length];
        float[] saved = source.clone(); byte[] output = new byte[25];
        assertTrue(NativeSlices.expandBrushRaster(source, 7, 2, output));
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) {
            assertEquals((byte) (source[Math.abs(x - 2) * 7 + Math.abs(y - 2)] * 255), output[y * 5 + x]);
        }
        for (int i = 0; i < source.length; i++) assertEquals(Float.floatToRawIntBits(saved[i]), Float.floatToRawIntBits(source[i]));
    }

    @Test public void asymmetricCustomQuadrantsMatchAfterRotation() {
        org.junit.Assume.assumeTrue("Optional release JNI test", Boolean.getBoolean("welt.test.requireBrushRaster"));
        SymmetricBrush source = new SymmetricBrush("Custom asymmetric quadrant", BrushShape.SQUARE, false) {
            @Override protected float calcStrength(int x, int y) { return (x * 31 + y * 13) / (float) (getRadius() * 55 + 1) - 0.2f; }
        };
        source.setRadius(127);
        for (int angle : new int[]{17, 73, 180, 271}) {
            mode(false); Brush expected = RotatedBrush.rotate(source.clone(), angle);
            mode(true); Brush actual = RotatedBrush.rotate(source.clone(), angle);
            compare(expected, actual);
        }
    }
    @Test public void invalidInputsKeepRasterUntouched() {
        byte[] result = new byte[9]; java.util.Arrays.fill(result, (byte) 42);
        assertFalse(NativeSlices.expandBrushRaster(new float[4], 1, 1, result));
        assertFalse(NativeSlices.expandBrushRaster(new float[4], 2, 2, result));
        assertFalse(NativeSlices.expandBrushRaster(new float[4], 2, 1, new byte[8]));
        for (byte value : result) assertEquals(42, value);
    }
}