package org.pepsoft.worldpainter;

import java.io.*;
import java.util.Random;
import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.SnapshotRandom;
import static org.junit.Assert.*;

public class SnapshotRandomParityTest {
    private static void compare(Random expected, Random actual) {
        for (int i = 0; i < 1000; i++) {
            assertEquals(expected.nextInt(), actual.nextInt());
            assertEquals(expected.nextInt(17), actual.nextInt(17));
            assertEquals(expected.nextInt(1073741825), actual.nextInt(1073741825));
            assertEquals(expected.nextLong(), actual.nextLong());
            assertEquals(Float.floatToRawIntBits(expected.nextFloat()), Float.floatToRawIntBits(actual.nextFloat()));
            assertEquals(Double.doubleToRawLongBits(expected.nextDouble()), Double.doubleToRawLongBits(actual.nextDouble()));
            assertEquals(Double.doubleToRawLongBits(expected.nextGaussian()), Double.doubleToRawLongBits(actual.nextGaussian()));
            assertEquals(expected.nextBoolean(), actual.nextBoolean());
            byte[] a = new byte[7], b = new byte[7]; expected.nextBytes(a); actual.nextBytes(b); assertArrayEquals(a, b);
        }
    }
    @Test public void everyInheritedGeneratorMatchesTheJdkBitForBit() {
        for (long seed : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 123456789}) compare(new Random(seed), new SnapshotRandom(seed));
    }
    @Test public void seedResetAlsoResetsTheGaussianCache() {
        Random a = new Random(91); SnapshotRandom b = new SnapshotRandom(91);
        assertEquals(a.nextGaussian(), b.nextGaussian(), 0); a.setSeed(42); b.setSeed(42); compare(a, b);
    }
    @Test public void restoringNativeFloatDrawsPreservesTheNextJavaDraw() {
        SnapshotRandom actual = new SnapshotRandom(42); Random expected = new Random(42);
        synchronized (actual) {
            long state = actual.snapshotState();
            for (int i = 0; i < 100003; i++) {
                state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48)-1);
                assertEquals(Float.floatToRawIntBits(expected.nextFloat()), Float.floatToRawIntBits((state >>> 24) / 16777216f));
            }
            actual.restoreState(state);
        }
        compare(expected, actual);
    }
    @Test public void failedTransactionCanLeaveTheOriginalStateUntouched() {
        SnapshotRandom actual = new SnapshotRandom(77);
        synchronized (actual) { actual.snapshotState(); }
        compare(new Random(77), actual);
    }
    @Test public void cachedGaussianSurvivesFloatOnlyNativeTransactions() {
        SnapshotRandom actual = new SnapshotRandom(42); Random expected = new Random(42);
        assertEquals(expected.nextGaussian(), actual.nextGaussian(), 0);
        synchronized (actual) {
            long state = actual.snapshotState();
            for (int i = 0; i < 37; i++) { expected.nextFloat(); state = (state*0x5DEECE66DL+0xBL)&((1L<<48)-1); }
            actual.restoreState(state);
        }
        assertEquals(expected.nextGaussian(), actual.nextGaussian(), 0); compare(expected, actual);
    }
    @Test public void stateTransfersRequireTheMonitorAndRejectInvalidStates() {
        SnapshotRandom r = new SnapshotRandom(42);
        try { r.snapshotState(); fail(); } catch (IllegalStateException expected) {}
        try { r.restoreState(1); fail(); } catch (IllegalStateException expected) {}
        synchronized (r) {
            long state = r.snapshotState();
            try { r.restoreState(1L << 48); fail(); } catch (IllegalArgumentException expected) {}
            assertEquals(state, r.snapshotState());
        }
    }
    @Test public void serializationPreservesTheStateAndGaussianCache() throws Exception {
        SnapshotRandom r = new SnapshotRandom(42); r.nextGaussian();
        var bytes = new ByteArrayOutputStream(); try (var out = new ObjectOutputStream(bytes)) { out.writeObject(r); }
        SnapshotRandom copy; try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { copy = (SnapshotRandom) in.readObject(); }
        compare(r, copy);
    }
}
