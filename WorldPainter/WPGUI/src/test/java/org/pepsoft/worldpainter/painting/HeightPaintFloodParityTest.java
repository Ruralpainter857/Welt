package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class HeightPaintFloodParityTest {
    @Test public void allStandardPaintsMatchJavaWithHolesAndRepeatedActions() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "bit", "nibble"}) for (boolean hole : new boolean[] {false, true}) {
                Dimension a = PaintFloodBenchmark.fixture(4), b = PaintFloodBenchmark.fixture(4);
                if (hole) { a.removeTile(0, 0); b.removeTile(0, 0); }
                var ea = FluidFloodParityTest.events(a); var eb = FluidFloodParityTest.events(b);
                for (int pass = 0; pass < 4; pass++) {
                    HeightPaintFloodBenchmark.fill(a, -64, -64, false, PaintFloodBenchmark.paint(type, pass & 1));
                    HeightPaintFloodBenchmark.fill(b, -64, -64, true, PaintFloodBenchmark.paint(type, pass & 1));
                    PaintFloodParityTest.same(a, b); assertEquals(ea, eb);
                }
            }
        } finally { restore(old); }
    }
    @Test public void absentDefaultBiomeIsNotAllocatedAndIdempotentPaintKeepsEvents() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            var ea = FluidFloodParityTest.events(a); var eb = FluidFloodParityTest.events(b);
            for (Paint paint : new Paint[] {new DiscreteLayerPaint(Biome.INSTANCE, 255), new TerrainPaint(Terrain.GRASS),
                    new BitLayerPaint(Frost.INSTANCE), new BitLayerPaint(Frost.INSTANCE)}) {
                HeightPaintFloodBenchmark.fill(a, -64, -64, false, paint);
                HeightPaintFloodBenchmark.fill(b, -64, -64, true, paint);
                PaintFloodParityTest.same(a, b); assertEquals(ea, eb);
                for (Tile t : b.getTiles()) assertFalse(t.getLayers().contains(Biome.INSTANCE));
            }
        } finally { restore(old); }
    }
    @Test public void combinedSessionSharesTheVisitAndSupportsUndoRedo() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = FluidFloodBenchmark.fixture(4), expected = FluidFloodBenchmark.fixture(4), actual = FluidFloodBenchmark.fixture(4);
            var undo = new org.pepsoft.util.undo.UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
            HeightPaintFloodBenchmark.fill(expected, -64, -64, false, new TerrainPaint(Terrain.CUSTOM_1));
            System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            HeightFloodSession s = HeightFloodSession.tryStart(actual, -64, -64, null, Terrain.CUSTOM_1, 0, 0);
            assertNotNull("WLFH v2 must execute", s); while (!s.isComplete()) s.advance(); actual.setEventsInhibited(false);
            assertEquals(16, s.getNativeCalls()); PaintFloodParityTest.same(expected, actual);
            assertTrue(undo.undo()); PaintFloodParityTest.same(before, actual);
            assertTrue(undo.redo()); PaintFloodParityTest.same(expected, actual);
        } finally { restore(old); }
    }
    @Test public void customPaintAndPerChunkLayerUseJavaFallback() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (Paint paint : new Paint[] {new NibbleLayerPaint(Resources.INSTANCE) {
                @Override public void applyPixel(Dimension d, int x, int y) { d.setWaterLevelAt(x, y, 70); super.applyPixel(d, x, y); }
            }, new BitLayerPaint(org.pepsoft.worldpainter.selection.SelectionChunk.INSTANCE)}) {
                paint.setBrush(org.pepsoft.worldpainter.brushes.SymmetricBrush.CONSTANT_SQUARE.clone());
                Dimension a = FluidFloodBenchmark.fixture(2), b = FluidFloodBenchmark.fixture(2);
                HeightPaintFloodBenchmark.fill(a, -64, -64, false, paint); HeightPaintFloodBenchmark.fill(b, -64, -64, true, paint);
                PaintFloodParityTest.same(a, b);
            }
        } finally { restore(old); }
    }
    @Test public void undoPaintFlagDoesNotTurnTheHeightActionIntoRemoval() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            for (int i = 0; i < 2; i++) {
                Dimension d = i == 0 ? a : b; System.setProperty(Native.GEN_KEY, Boolean.toString(i == 1)); d.setEventsInhibited(true);
                DimensionPainter p = new DimensionPainter(); p.setPaint(new TerrainPaint(Terrain.DIRT)); p.setUndo(true);
                assertTrue(p.fill(d, -64, -64, DimensionPainter.AdditionalFillAction.APPLY_PAINT, null)); d.setEventsInhibited(false);
            }
            PaintFloodParityTest.same(a, b); assertEquals(Terrain.DIRT, b.getTerrainAt(-64, -64));
        } finally { restore(old); }
    }
    @Test public void allDescriptorsExecuteThroughTheCombinedAbi() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int kind = 0; kind < 4; kind++) {
                Dimension expected = FluidFloodBenchmark.fixture(4), actual = FluidFloodBenchmark.fixture(4);
                String type = new String[] {"terrain", "biome", "bit", "nibble"}[kind];
                Paint paint = PaintFloodBenchmark.paint(type, 0); HeightPaintFloodBenchmark.fill(expected, -64, -64, false, paint);
                System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
                Layer layer = kind == 0 ? null : kind == 1 ? Biome.INSTANCE : kind == 2 ? Frost.INSTANCE : Resources.INSTANCE;
                HeightFloodSession s = HeightFloodSession.tryStart(actual, -64, -64, layer, kind == 0 ? Terrain.DIRT : null,
                        kind == 1 ? 77 : kind == 2 ? 1 : 9, kind == 3 ? 1 : 0);
                assertNotNull("Combined ABI required for " + type, s); while (!s.isComplete()) s.advance(); actual.setEventsInhibited(false);
                assertEquals(16, s.getNativeCalls()); PaintFloodParityTest.same(expected, actual);
            }
        } finally { restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
