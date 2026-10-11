package org.pepsoft.worldpainter.brushes;

import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

/** Compare complete lifecycles against classes compiled from the pre-change revision. */
public class BrushCacheParityTest {
    @Test public void allBuiltInsMatchOriginalAcrossLevelsAndRadii() throws Exception {
        if (Boolean.getBoolean("welt.test.requireBrushCache")) {
            float[] source = new float[4096], output = new float[4096];
            source[0] = 0.625f;
            assertTrue("Release brush JNI must execute", NativeSlices.scaleBrushCache(source, 0.375f, output));
            assertEquals(Float.floatToIntBits(0.625f * 0.375f), Float.floatToIntBits(output[0]));
        }
        Brush[] expected = System.getProperty("welt.benchmark.originalBrushes") == null ? null : BrushCacheBenchmark.brushes(BrushCacheBenchmark.original(), 0);
        Brush[] actual = BrushCacheBenchmark.brushes(SymmetricBrush.class, 0);
        for (int radius : new int[]{0, 1, 3, 31, 127, 7, 63, 0}) {
            for (int i = 0; i < actual.length; i++) {
                if (expected != null) expected[i].setRadius(radius); actual[i].setRadius(radius);
                for (float level : new float[]{1.0f, 0.375f, 0.875f, 0.0f, -0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY, 1.0f}) {
                    if (expected != null) expected[i].setLevel(level); actual[i].setLevel(level);
                    if (expected != null) compare(expected[i], actual[i], radius);
                    for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) {
                        float full = scalar(i, radius, Math.abs(x), Math.abs(y));
                        assertEquals(Float.floatToIntBits(full), Float.floatToIntBits(actual[i].getFullStrength(x, y)));
                        assertEquals(Float.floatToIntBits(full * actual[i].getLevel()), Float.floatToIntBits(actual[i].getStrength(x, y)));
                    }
                }
            }
        }
    }

    private static float scalar(int kind, int radius, int x, int y) {
        float distance = org.pepsoft.util.MathUtils.getDistance(x, y);
        float dr = distance / radius;
        if ((kind & 1) == 0) {
            if (!(distance <= radius)) return 0.0f;
        } else if (x != 0 && y != 0) {
            int u, v;
            if (x < y) { u = radius * x / y; v = radius; }
            else { u = radius; v = radius * y / x; }
            float factor = org.pepsoft.util.MathUtils.getDistance(u, v) / radius;
            dr /= factor;
        }
        switch (kind / 2) {
            case 0: return 1.0f;
            case 1: return 1.0f - dr;
            case 2: return (float) Math.cos(dr * Math.PI) / 2 + 0.5f;
            case 3: return dr <= 0.5f ? 1.0f : (float) Math.cos((dr - 0.5f) * (float) (Math.PI * 2)) / 2 + 0.5f;
            case 4: return (1.0f - dr) * (1.0f - dr);
            case 5: return (float) Math.sqrt(1 - dr * dr);
            default: throw new AssertionError();
        }
    }
    private static void compare(Brush expected, Brush actual, int radius) {
        assertEquals(expected.getBoundingBox(), actual.getBoundingBox());
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) {
            assertEquals(Float.floatToIntBits(expected.getStrength(x, y)), Float.floatToIntBits(actual.getStrength(x, y)));
            assertEquals(Float.floatToIntBits(expected.getFullStrength(x, y)), Float.floatToIntBits(actual.getFullStrength(x, y)));
        }
    }

    @Test public void clonedPlanesRemainIndependent() {
        SymmetricBrush source = SymmetricBrush.COSINE_SQUARE.clone(); source.setRadius(127); source.setLevel(0.75f);
        SymmetricBrush clone = source.clone();
        float before = clone.getStrength(22, 37), fullBefore = clone.getFullStrength(22, 37);
        source.setLevel(0.25f); source.setRadius(31);
        assertEquals(Float.floatToIntBits(before), Float.floatToIntBits(clone.getStrength(22, 37)));
        assertEquals(Float.floatToIntBits(fullBefore), Float.floatToIntBits(clone.getFullStrength(22, 37)));
        clone.setLevel(1.0f); SymmetricBrush shared = clone.clone(); clone.setLevel(0.375f); clone.setRadius(3);
        assertEquals(Float.floatToIntBits(fullBefore), Float.floatToIntBits(shared.getStrength(22, 37)));
    }

    @Test public void customBrushStillRecalculatesWhenLevelChanges() {
        SymmetricBrush brush = new SymmetricBrush("Level-dependent", BrushShape.SQUARE, false) {
            @Override protected float calcStrength(int x, int y) { return getLevel() + x * 0.01f + y * 0.02f; }
        };
        brush.setRadius(7);
        float full = brush.getFullStrength(3, 2);
        brush.setLevel(0.25f);
        assertEquals(Float.floatToIntBits((0.25f + 3 * 0.01f + 2 * 0.02f) * 0.25f), Float.floatToIntBits(brush.getStrength(3, 2)));
        assertEquals(Float.floatToIntBits(full), Float.floatToIntBits(brush.getFullStrength(3, 2)));
        brush.setLevel(1.0f);
        assertEquals(Float.floatToIntBits(1.0f + 3 * 0.01f + 2 * 0.02f), Float.floatToIntBits(brush.getStrength(3, 2)));
    }

    @Test public void concurrentBulkTransactionsKeepPlanesPrivate() throws Exception {
        if (!Boolean.getBoolean("welt.test.requireBrushCache")) return;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<?>> work = new java.util.ArrayList<>();
            for (int worker = 0; worker < 4; worker++) work.add(pool.submit(() -> {
                float[] source = new float[16384], result = new float[source.length];
                for (int i = 0; i < source.length; i++) source[i] = i / 16384.0f;
                for (int turn = 0; turn < 40; turn++) {
                    float level = turn / 40.0f;
                    assertTrue(NativeSlices.scaleBrushCache(source, level, result));
                    for (int i = 0; i < source.length; i++) {
                        assertEquals(Float.floatToIntBits((i / 16384.0f) * level), Float.floatToIntBits(result[i]));
                        assertEquals(Float.floatToIntBits(i / 16384.0f), Float.floatToIntBits(source[i]));
                    }
                }
            }));
            for (var future : work) future.get();
        } finally { pool.shutdownNow(); }
    }
    @Test public void invalidBulkInputsLeaveOutputUntouched() {
        float[] output = new float[4096]; java.util.Arrays.fill(output, 42.0f);
        assertFalse(NativeSlices.scaleBrushCache(output, 0.5f, output));
        assertFalse(NativeSlices.scaleBrushCache(new float[4095], 0.5f, output));
        for (float value : output) assertEquals(42.0f, value, 0.0f);
    }
}