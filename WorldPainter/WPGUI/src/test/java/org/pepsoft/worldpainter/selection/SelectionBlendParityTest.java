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
    }    @Test public void nativeBlendingUsesOneCallPerTileAndKeepsUndoAndRandomState() throws Exception {
        org.junit.Assume.assumeTrue(org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = fixture(), expected = fixture(), actual = fixture();
            SelectionOptions o = new SelectionOptions(); o.setCopyAnnotations(true); o.setDoBlending(true);
            random().setSeed(42); SelectionCopyBenchmark.copy(expected, o, 17, -19, false); long next = random().nextLong();
            var undo = new org.pepsoft.util.undo.UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
            random().setSeed(42); System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            var plan = org.pepsoft.worldpainter.SelectionCopyAccess.prepareBlended(actual, 17, -19, true, true, true, true, true, true, true,
                    (org.pepsoft.worldpainter.nativeapi.SnapshotRandom) random());
            assertNotNull(plan);
            for (int x = 0; x >= -1; x--) for (int y = -1; y <= 0; y++) assertTrue("Native blend ABI required", plan.copyTile(actual.getTile(x, y), 17, -19));
            actual.setEventsInhibited(false); assertEquals(4, plan.getNativeCalls());
            assertEquals(next, random().nextLong()); SelectionCopyParityTest.same(expected, actual);
            assertTrue(undo.undo()); SelectionCopyParityTest.same(before, actual);
            assertTrue(undo.redo()); SelectionCopyParityTest.same(expected, actual);
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    @Test public void everyOptionAndMissingDestinationKeepsTheExactDrawCount() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int option = 0; option < 7; option++) {
                Dimension a = fixture(), b = fixture(); a.removeTile(0, 0); b.removeTile(0, 0);
                SelectionOptions o = new SelectionOptions(); o.setDoBlending(true);
                o.setCopyHeights(option == 0); o.setCopyTerrain(option == 1); o.setCopyFluids(option == 2);
                o.setCopyLayers(option == 3 || option == 6); o.setCopyAnnotations(option == 4); o.setCopyBiomes(option == 5);
                o.setRemoveExistingLayers(option != 6);
                random().setSeed(781); SelectionCopyBenchmark.copy(a, o, 129, 19, false); long next = random().nextLong();
                random().setSeed(781); SelectionCopyBenchmark.copy(b, o, 129, 19, true);
                assertEquals("option=" + option, next, random().nextLong()); SelectionCopyParityTest.same(a, b);
            }
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    @Test public void sparseLargeLayerMapsKeepTheOrderAfterUndoCopyOnWrite() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = fixture(), b = fixture();
            for (int i = 0; i < 64; i++) {
                Layer l = new Layer("welt.test.blend.removed." + i, "Removed", "", Layer.DataSize.BYTE, false, 50) {};
                for (Dimension d : new Dimension[] {a, b}) for (var tile : d.getTiles()) { tile.setLayerValue(l, 0, 0, 1); tile.clearLayerData(l); }
            }
            for (int i = 0; i < 16; i++) {
                Layer l = new Layer("welt.test.blend.sparse." + i, "Sparse", "", Layer.DataSize.BYTE, false, 50+i) {};
                for (Dimension d : new Dimension[] {a, b}) for (var tile : d.getTiles()) {
                    tile.inhibitEvents();
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) if ((x+y+i)%11==0) tile.setLayerValue(l, x, y, i+1);
                    tile.releaseEvents();
                }
            }
            var javaUndo = new org.pepsoft.util.undo.UndoManager(); a.registerUndoManager(javaUndo); javaUndo.armSavePoint();
            var undo = new org.pepsoft.util.undo.UndoManager(); b.registerUndoManager(undo); undo.armSavePoint();
            SelectionOptions o = new SelectionOptions(); o.setDoBlending(true); o.setCopyAnnotations(true);
            random().setSeed(42); SelectionCopyBenchmark.copy(a, o, 17, -19, false); long next = random().nextLong();
            random().setSeed(42); System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
            var plan = org.pepsoft.worldpainter.SelectionCopyAccess.prepareBlended(b, 17, -19, true, true, true, true, true, true, true,
                    (org.pepsoft.worldpainter.nativeapi.SnapshotRandom) random());
            assertNotNull(plan);
            for (int x = 0; x >= -1; x--) for (int y = -1; y <= 0; y++) assertTrue(plan.copyTile(b.getTile(x, y), 17, -19));
            b.setEventsInhibited(false); assertEquals(4, plan.getNativeCalls());
            assertEquals(next, random().nextLong()); SelectionCopyParityTest.same(a, b);
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    @Test public void treeBinLayerMapsKeepTheJavaFallbackAndRandomState() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = fixture(), b = fixture();
            for (int i = 0; i < 9; i++) {
                StringBuilder id = new StringBuilder(); for (int bit = 0; bit < 4; bit++) id.append((i & (1 << bit)) == 0 ? "Aa" : "BB");
                Layer l = new Layer(id.toString(), "Collision", "", Layer.DataSize.BIT, false, 50+i) {};
                for (Dimension d : new Dimension[] {a,b}) for (var tile : d.getTiles()) tile.setBitLayerValue(l);
            }
            System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
            assertNull(org.pepsoft.worldpainter.SelectionCopyAccess.prepareBlended(b, 17, -19, true, true, true, true, true, true, true,
                    (org.pepsoft.worldpainter.nativeapi.SnapshotRandom) random())); b.setEventsInhibited(false);
            SelectionOptions o = new SelectionOptions(); o.setDoBlending(true); o.setCopyAnnotations(true);
            random().setSeed(42); SelectionCopyBenchmark.copy(a, o, 17, -19, false); long next = random().nextLong();
            random().setSeed(42); SelectionCopyBenchmark.copy(b, o, 17, -19, true);
            assertEquals(next, random().nextLong()); SelectionCopyParityTest.same(a, b);
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }

}
