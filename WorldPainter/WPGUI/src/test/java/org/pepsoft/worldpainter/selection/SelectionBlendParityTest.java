package org.pepsoft.worldpainter.selection;

import java.util.Random;
import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Compare complete blended copies and the next RNG draw, including colliding layer IDs. */
public class SelectionBlendParityTest {
    private static final Layer A = new Layer("Aa", "First", "", Layer.DataSize.BIT, false, 40) {};
    private static final Layer B = new Layer("BB", "Second", "", Layer.DataSize.BIT, false, 41) {};
    private static Random random() throws Exception {
        var field = SelectionHelper.class.getDeclaredField("RANDOM"); field.setAccessible(true); return (Random) field.get(null);
    }
    private static Dimension fixture() {
        Dimension d = SelectionCopyBenchmark.fixture(2);
        for (var tile : d.getTiles()) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            tile.setBitLayerValue(A, x, y, x % 3 == 0); tile.setBitLayerValue(B, x, y, y % 5 == 0);
        }
        return d;
    }
    @Test public void blendingKeepsAllPlanesAndTheRandomDrawOrder() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (long seed : new long[] {0, 42, Long.MIN_VALUE}) for (int dx : new int[] {-17, 17}) {
                Dimension java = fixture(), nativeMode = fixture();
                SelectionOptions o = new SelectionOptions(); o.setCopyAnnotations(true); o.setDoBlending(true);
                random().setSeed(seed); SelectionCopyBenchmark.copy(java, o, dx, -19, false); long next = random().nextLong();
                random().setSeed(seed); SelectionCopyBenchmark.copy(nativeMode, o, dx, -19, true);
                assertEquals(next, random().nextLong()); SelectionCopyParityTest.same(java, nativeMode);
            }
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
}
