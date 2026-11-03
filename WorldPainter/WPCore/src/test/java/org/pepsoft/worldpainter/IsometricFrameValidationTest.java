package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

/** Exercises the JNI frame separately from Java2D's own native drawing calls. */
public class IsometricFrameValidationTest {
    private String previous;
    @Before public void prepare() {
        previous = System.getProperty(Native.RENDER_KEY);
        if (Boolean.getBoolean("welt.test.requireIsometric")) assertTrue(NativeLoader.areSlicesAvailable());
        else Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        System.setProperty(Native.RENDER_KEY, "true");
    }
    @After public void restore() { if (previous == null) System.clearProperty(Native.RENDER_KEY); else System.setProperty(Native.RENDER_KEY, previous); }
    private static ByteBuffer frame() {
        int output = 64 + 16384 * 32;
        ByteBuffer frame = ByteBuffer.allocateDirect(output + 256 * 128 * 4).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(0, 0x44334957).putInt(4, 1).putInt(8, 16384).putInt(12, 256).putInt(16, 128).putInt(48, 64).putInt(52, output);
        for (int at = output; at < frame.limit(); at++) frame.put(at, (byte) 0xa5);
        return frame;
    }
    @Test public void completeEmptyTileIsTransparentAndDoesNotMutateInputs() {
        ByteBuffer frame = frame(); int output = frame.getInt(52); byte[] inputs = new byte[output]; frame.get(inputs).position(0);
        assertTrue(NativeSlices.renderIsometricTile(frame));
        for (int at = 0; at < output; at++) assertEquals(inputs[at], frame.get(at));
        for (int at = output; at < frame.limit(); at++) assertEquals(0, frame.get(at));
    }
    @Test public void refusedFramesKeepEveryByteAndRejectReadOnlyOrHeapStorage() {
        for (int[] invalid : new int[][]{{0,0},{4,2},{16,-1},{20,4},{52,-1},{56,Integer.MAX_VALUE},{60,1},{76,16}}) {
            ByteBuffer frame = frame(); frame.putInt(invalid[0], invalid[1]); byte[] before = new byte[frame.limit()]; frame.get(before).position(0);
            assertFalse(NativeSlices.renderIsometricTile(frame)); byte[] after = new byte[frame.limit()]; frame.get(after).position(0); assertArrayEquals(before, after);
        }
        assertFalse(NativeSlices.renderIsometricTile(frame().asReadOnlyBuffer()));
        assertFalse(NativeSlices.renderIsometricTile(ByteBuffer.allocate(655424)));
    }
}
