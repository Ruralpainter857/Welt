package org.pepsoft.worldpainter;

import java.util.Random;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class ErosionAccessParityTest {
    @Test public void sequentialMutationsMatchAcrossTilesHolesAndRepeatedStrokes() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (int radius : new int[] {0, 3, 63, 127}) {
                Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                Random random = new Random(154);
                for (int stroke = 0; stroke < 3; stroke++) {
                    byte[] controls = ErosionRegionBenchmark.controls(radius, random);
                    int cx = stroke == 0 ? -1 : 127, cy = stroke == 0 ? -1 : 128;
                    ErosionRegionBenchmark.scalar(expected, cx, cy, radius, controls);
                    assertTrue(ErosionAccess.apply(actual, cx, cy, radius, controls));
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false);
                for (Tile t : expected.getTiles()) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                    assertEquals(t.getRawHeight(x, y), actual.getTile(t.getX(), t.getY()).getRawHeight(x, y));
            }
        } finally { restore(old); }
    }

    @Test public void shortStorageWrapAndUndoArePreserved() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Tile expected = new Tile(0, 0, 0, 256), actual = new Tile(0, 0, 0, 256);
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                int value = x % 2 == 0 ? 65535 : 0;
                expected.setRawHeight(x, y, value); actual.setRawHeight(x, y, value);
            }
            UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint(); actual.inhibitEvents();
            int radius = 3, width = 9, area = 81, types = 32 + area * 4, mask = types + area, controls = mask + area;
            java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(controls + 147).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            data.putInt(0, 0x52454c57).putInt(4, 1).putInt(8, radius).putInt(12, width)
                    .putInt(16, 32).putInt(20, types).putInt(24, mask).putInt(28, controls);
            actual.copyErosionRegion(1, 1, width, width, data, 0, width, types);
            byte[] decisions = ErosionRegionBenchmark.controls(radius, new Random(451));
            data.position(controls); data.put(decisions); data.position(0);
            java.nio.ByteBuffer fallback = java.nio.ByteBuffer.allocate(data.limit()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            fallback.put(data.duplicate());
            var method = ErosionAccess.class.getDeclaredMethod("erodeJava", java.nio.ByteBuffer.class,
                    int.class, int.class, int.class, int.class, int.class);
            method.setAccessible(true); method.invoke(null, fallback, 7, width, types, mask, controls);
            assertTrue("The compact JNI entry point must execute", org.pepsoft.worldpainter.nativeapi.NativeSlices.erodeCompactRegion(data));
            for (int i = 0; i < data.limit(); i++) assertEquals("Java fallback byte " + i, fallback.get(i), data.get(i));
            actual.applyErosionRegion(1, 1, width, width, data, 0, width, mask); actual.releaseEvents();
            // La référence utilise de vrais setters u16 après chaque mutation.
            for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) {
                int c = (x * 7 + y) * 3; if (decisions[c] == 0) continue;
                int lx = 0, ly = 0, low = Integer.MAX_VALUE;
                for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
                    int dx = decisions[c + 1] != 0 ? 2 - a : a, dy = decisions[c + 2] != 0 ? 2 - b : b;
                    int value = expected.getRawHeight(x + dx + 1, y + dy + 1);
                    if (value < low) { low = value; lx = dx; ly = dy; }
                }
                if (lx == 1 && ly == 1) continue;
                int current = expected.getRawHeight(x + 2, y + 2);
                int amount = Math.min((int) ((current - low) / 2 / ((lx != 1 && ly != 1) ? (float) Math.sqrt(2) : 1)), 64);
                amount = (int) ((amount / 64f) * (amount / 64f) * 64);
                if (amount > 0) { expected.setRawHeight(x + 2, y + 2, current - amount); expected.setRawHeight(x + lx + 1, y + ly + 1, low + amount); }
            }
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y));
            assertTrue(undo.undo()); assertEquals(65535, actual.getRawHeight(2, 2));
            assertTrue(undo.redo()); assertEquals(expected.getRawHeight(2, 2), actual.getRawHeight(2, 2));
        } finally { restore(old); }
    }

    @Test public void unsupportedPathsRejectBeforeEditing() {
        Dimension d = ErosionRegionBenchmark.fixture();
        assertFalse(ErosionAccess.apply(d, 0, 0, 3, new byte[147]));
        d.setEventsInhibited(true);
        assertFalse(ErosionAccess.apply(d, 0, 0, -1, new byte[0]));
        assertFalse(ErosionAccess.apply(d, 0, 0, 256, new byte[0]));
        d.setEventsInhibited(false);
    }

    @Test public void workersKeepIndependentRegionBuffers() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                final int seed = worker;
                tasks.add(workers.submit(() -> {
                    Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                    byte[] controls = ErosionRegionBenchmark.controls(3, new Random(seed));
                    expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                    ErosionRegionBenchmark.scalar(expected, -1, -1, 3, controls);
                    assertTrue(ErosionAccess.apply(actual, -1, -1, 3, controls));
                    for (int x = -5; x < 5; x++) for (int y = -5; y < 5; y++)
                        assertEquals(expected.getRawHeightAt(x, y), actual.getRawHeightAt(x, y));
                    expected.setEventsInhibited(false); actual.setEventsInhibited(false);
                }));
            }
            for (var task : tasks) task.get();
        } finally { workers.shutdownNow(); restore(old); }
    }
    private static void restore(String previous) { if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous); }
}
