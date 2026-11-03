package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Reentrant brush callbacks must not corrupt the outer transaction's frame. */
public class EditorBufferTransactionParityTest {
    @Test public void riverCallbacksCanRunEitherFrameVersionWithoutOverwritingTheOuterEdit() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean nestedLarge : new boolean[] {false, true}) {
                Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                Dimension innerExpected = ErosionRegionBenchmark.fixture(), innerActual = ErosionRegionBenchmark.fixture();
                float[] forces = MountainBrushBenchmark.forces(3), slopes = RiverRegionBenchmark.slopes(forces);
                int expectedLevel = RiverRegionBenchmark.scalar(expected, -3, -3, 7, forces, slopes, 52, 5f, false);
                int innerLevel = RiverRegionBenchmark.scalar(innerExpected, 4, 4, 7, forces, slopes, 48, 3f, true);
                actual.setEventsInhibited(true);
                innerActual.setEventsInhibited(true);
                try {
                    Integer level = RiverAccess.tryApplyLarge(actual, -3, -3, 7, (x, y) -> {
                        if (x == 2 && y == 3) {
                            Integer nested = nestedLarge
                                    ? RiverAccess.tryApplyLarge(innerActual, 4, 4, 7, (dx, dy) -> forces[dx * 7 + dy], 48, 3f, true)
                                    : RiverAccess.tryApply(innerActual, 4, 4, 7, forces, slopes, 48, 3f, true);
                            assertEquals(Integer.valueOf(innerLevel), nested);
                        }
                        return forces[x * 7 + y];
                    }, 52, 5f, false);
                    assertEquals(Integer.valueOf(expectedLevel), level);
                } finally {
                    actual.setEventsInhibited(false);
                    innerActual.setEventsInhibited(false);
                }
                same(expected, actual);
                same(innerExpected, innerActual);
            }
        } finally {
            if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old);
        }
    }

    @Test public void aThrowingStrengthCallbackLeavesTheWorldUntouchedAndTheNextEditWorks() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
            actual.setEventsInhibited(true);
            float[] forces = MountainBrushBenchmark.forces(3), slopes = RiverRegionBenchmark.slopes(forces);
            try {
                assertThrows(IllegalStateException.class, () -> RiverAccess.tryApplyLarge(actual, -3, -3, 7, (x, y) -> {
                    if (x == 2 && y == 3) throw new IllegalStateException("Brush callback failed");
                    return forces[x * 7 + y];
                }, 52, 5f, false));
                same(expected, actual);
                int level = RiverRegionBenchmark.scalar(expected, -3, -3, 7, forces, slopes, 52, 5f, false);
                assertEquals(Integer.valueOf(level), RiverAccess.tryApplyLarge(actual, -3, -3, 7,
                        (x, y) -> forces[x * 7 + y], 52, 5f, false));
            } finally { actual.setEventsInhibited(false); }
            same(expected, actual);
        } finally {
            if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old);
        }
    }

    private static void same(Dimension expected, Dimension actual) {
        assertEquals(expected.getTiles().size(), actual.getTiles().size());
        for (Tile tile : expected.getTiles()) {
            Tile other = actual.getTile(tile.getX(), tile.getY());
            assertNotNull(other);
            assertEquals(tile.getLayers(), other.getLayers());
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                assertEquals(tile.getRawHeight(x, y), other.getRawHeight(x, y));
                assertEquals(tile.getWaterLevel(x, y), other.getWaterLevel(x, y));
                assertEquals(tile.getTerrain(x, y), other.getTerrain(x, y));
                assertEquals(tile.getBitLayerValue(FloodWithLava.INSTANCE, x, y), other.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
            }
        }
    }
}
