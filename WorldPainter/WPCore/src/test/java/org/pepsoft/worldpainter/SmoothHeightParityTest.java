package org.pepsoft.worldpainter;

import org.junit.Test;
import java.nio.*;
import java.util.Random;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

public class SmoothHeightParityTest {
    @Test public void compactPathRequiresExplicitOptIn() {
        String old = System.getProperty(SmoothHeightAccess.ENABLE_KEY);
        try {
            System.clearProperty(SmoothHeightAccess.ENABLE_KEY); assertFalse(SmoothHeightAccess.isEnabled());
            System.setProperty(SmoothHeightAccess.ENABLE_KEY, "true"); assertTrue(SmoothHeightAccess.isEnabled());
        } finally { if (old == null) System.clearProperty(SmoothHeightAccess.ENABLE_KEY); else System.setProperty(SmoothHeightAccess.ENABLE_KEY, old); }
    }
    @Test public void matchesStreamingJavaAcrossTilesHolesAndRepeatedPasses() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (int side : new int[] {1, 7, 127, 245}) {
                Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                UndoManager undo = new UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
                int ox = 124, oy = 124; float before = actual.getHeightAt(ox, oy);
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                float[] forces = new float[side * side]; Random random = new Random(518);
                SmoothRegionBenchmark.Scratch scratch = new SmoothRegionBenchmark.Scratch(side + 10);
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < forces.length; i++) forces[i] = i % 41 == 0 ? Float.NaN : i % 43 == 0 ? Float.POSITIVE_INFINITY : random.nextFloat() * 1.2f;
                    SmoothRegionBenchmark.legacy(expected, ox, oy, side, forces, scratch);
                    assertTrue(SmoothHeightAccess.tryApply(actual, ox, oy, side, side, forces));
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false);
                for (Tile t : expected.getTiles()) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                    assertEquals(t.getRawHeight(x, y), actual.getTile(t.getX(), t.getY()).getRawHeight(x, y));
                if (side > 1) {
                    assertTrue(undo.undo()); assertEquals(before, actual.getHeightAt(ox, oy), 0f);
                    assertTrue(undo.redo()); assertEquals(expected.getHeightAt(ox, oy), actual.getHeightAt(ox, oy), 0f);
                }
            }
        } finally { restore(old); }
    }
    @Test public void jniAndFallbackMatchForRectangularWindowsAndRejectInvalidOffsets() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            int iw = 11, ih = 12, forces = 48 + iw * ih * 4, output = forces + 8, mask = output + 8;
            ByteBuffer data = ByteBuffer.allocateDirect(mask + 2).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(0, 0x4d534c57).putInt(4, 1).putInt(8, iw).putInt(12, ih).putInt(16, -64)
                    .putInt(20, 48).putInt(24, forces).putInt(28, output).putInt(32, mask);
            for (int i = 0; i < iw * ih; i++) data.putFloat(48 + i * 4, i % 11 == 0 ? -Float.MAX_VALUE : i / 3.125f);
            data.putFloat(48 + (5 * ih + 6) * 4, -Float.MAX_VALUE);
            data.putFloat(forces, 0.73f).putFloat(forces + 4, Float.POSITIVE_INFINITY);
            ByteBuffer fallback = ByteBuffer.allocate(data.limit()).order(ByteOrder.LITTLE_ENDIAN); fallback.put(data.duplicate());
            var method = SmoothHeightAccess.class.getDeclaredMethod("smoothJava", ByteBuffer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            method.setAccessible(true); method.invoke(null, fallback, 1, 2, ih, forces, output, mask);
            assertTrue("WLSM v1 must execute through JNI", NativeSlices.smoothCompactRegion(data));
            for (int i = 0; i < data.limit(); i++) assertEquals(fallback.get(i), data.get(i));
            assertEquals(1, data.getInt(40)); assertEquals(0, data.get(mask + 1));
            data.putInt(28, output + 4); byte[] before = new byte[data.limit()]; data.duplicate().get(before);
            assertFalse(NativeSlices.smoothCompactRegion(data));
            byte[] after = new byte[data.limit()]; data.duplicate().get(after); assertArrayEquals(before, after);
        } finally { restore(old); }
    }
    @Test public void unsignedHeightApplicationPreservesUndo() {
        Tile actual = new Tile(0, 0, 0, 256); UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint();
        ByteBuffer data = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, -1).putInt(4, 65536).putInt(8, 98304).putInt(12, Integer.MAX_VALUE);
        data.put(16, (byte) 1).put(17, (byte) 1).put(18, (byte) 1).put(19, (byte) 0);
        actual.inhibitEvents(); actual.applyRawHeightRegion(126, 126, 2, 2, data, 0, 2, 0, 16, 4); actual.releaseEvents();
        assertEquals(65535, actual.getRawHeight(126, 126)); assertEquals(0, actual.getRawHeight(126, 127));
        assertEquals(32768, actual.getRawHeight(127, 126)); assertEquals(0, actual.getRawHeight(127, 127));
        assertTrue(undo.undo()); assertEquals(0, actual.getRawHeight(126, 126));
        assertTrue(undo.redo()); assertEquals(65535, actual.getRawHeight(126, 126));
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
