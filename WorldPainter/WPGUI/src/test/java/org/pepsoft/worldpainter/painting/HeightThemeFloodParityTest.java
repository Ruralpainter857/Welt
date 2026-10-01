package org.pepsoft.worldpainter.painting;

import java.util.*;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Filter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class HeightThemeFloodParityTest {
    private static final Layer CHUNK = new Layer("welt.test.height.theme.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) {};
    private static SimpleTheme theme(boolean randomise, boolean beaches, boolean stochastic) {
        var ranges = new TreeMap<Integer, Terrain>(); ranges.put(-65, Terrain.CUSTOM_1); ranges.put(55, Terrain.GRASS); ranges.put(90, Terrain.STONE);
        Map<Filter, Layer> layers = new LinkedHashMap<>();
        layers.put((x, y, z, l) -> Math.floorMod(z, 16), Resources.INSTANCE);
        layers.put((x, y, z, l) -> 15, Biome.INSTANCE);
        layers.put((x, y, z, l) -> stochastic ? 7 : 15, Frost.INSTANCE);
        layers.put((x, y, z, l) -> 15, CHUNK);
        SimpleTheme t = new SimpleTheme(1234567L, 62, ranges, layers, -64, 320, randomise, beaches);
        t.setDiscreteValues(Map.of(Biome.INSTANCE, 200)); return t;
    }
    private static Dimension fixture(SimpleTheme theme, boolean hole) {
        Dimension d = FluidFloodBenchmark.fixture(4); ((HeightMapTileFactory) d.getTileFactory()).setTheme(theme);
        if (hole) d.removeTile(0, 0); return d;
    }
    private static Random random() throws Exception {
        var field = SimpleTheme.class.getDeclaredField("random"); field.setAccessible(true); return (Random) field.get(null);
    }
    @Test public void randomisedTerrainBeachesAndAllLayerTypesMatchWithHoles() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean noise : new boolean[] {false, true}) for (boolean beaches : new boolean[] {false, true}) {
                Dimension a = fixture(theme(noise, beaches, false), true), b = fixture(theme(noise, beaches, false), true);
                var ea = FluidFloodParityTest.events(a); var eb = FluidFloodParityTest.events(b);
                for (int i = 0; i < 12; i++) {
                    HeightThemeFloodBenchmark.fill(a, false); HeightThemeFloodBenchmark.fill(b, true);
                    PaintFloodParityTest.same(a, b); assertEquals(ea, eb);
                }
            }
        } finally { restore(old); }
    }
    @Test public void stochasticLayersKeepTheOriginalRandomDrawOrder() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = fixture(theme(true, true, true), false), b = fixture(theme(true, true, true), false);
            System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
            assertNull(HeightFloodSession.tryStartWithTheme(b, -64, -64)); b.setEventsInhibited(false);
            random().setSeed(42); HeightThemeFloodBenchmark.fill(a, false); int next = random().nextInt();
            random().setSeed(42); HeightThemeFloodBenchmark.fill(b, true); assertEquals(next, random().nextInt());
            PaintFloodParityTest.same(a, b);
        } finally { restore(old); }
    }
    @Test public void nativeSessionAndUndoRedoKeepTheWholeWorld() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = fixture(theme(true, true, false), false), expected = fixture(theme(true, true, false), false), actual = fixture(theme(true, true, false), false);
            var undo = new org.pepsoft.util.undo.UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
            HeightThemeFloodBenchmark.fill(expected, false);
            System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            HeightFloodSession session = HeightFloodSession.tryStartWithTheme(actual, -64, -64); assertNotNull("Prepared theme ABI required", session);
            while (!session.isComplete()) session.advance(); actual.setEventsInhibited(false);
            assertEquals(17, session.getNativeCalls()); PaintFloodParityTest.same(expected, actual);
            assertTrue(undo.undo()); PaintFloodParityTest.same(before, actual); assertTrue(undo.redo()); PaintFloodParityTest.same(expected, actual);
        } finally { restore(old); }
    }
    @Test public void customThemeFallsBackAndUniformThemeStillUsesNativeHeight() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            var ranges = new TreeMap<Integer, Terrain>(); ranges.put(-65, Terrain.GRASS);
            SimpleTheme custom = new SimpleTheme(0, 62, ranges, null, -64, 320, false, false) {
                @Override public Terrain getTerrain(int x, int y, int z) { return Terrain.DIRT; }
            };
            Dimension a = fixture(custom, false), b = fixture(custom, false);
            System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
            assertNull(HeightFloodSession.tryStartWithTheme(b, -64, -64)); b.setEventsInhibited(false);
            HeightThemeFloodBenchmark.fill(a, false); HeightThemeFloodBenchmark.fill(b, true); PaintFloodParityTest.same(a, b);
            Dimension uniform = fixture(SimpleTheme.createSingleTerrain(Terrain.CUSTOM_1, -64, 320, 62), false);
            uniform.setEventsInhibited(true); HeightFloodSession s = HeightFloodSession.tryStartWithTheme(uniform, -64, -64);
            assertNotNull(s); while (!s.isComplete()) s.advance(); uniform.setEventsInhibited(false);
            assertEquals(Terrain.CUSTOM_1, uniform.getTerrainAt(-64, -64));
        } finally { restore(old); }
    }
    @Test public void immutablePlanSurvivesWorkerHandoff() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Dimension expected = fixture(theme(true, true, false), false), actual = fixture(theme(true, true, false), false);
            HeightThemeFloodBenchmark.fill(expected, false); System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            HeightFloodSession s = HeightFloodSession.tryStartWithTheme(actual, -64, -64); assertNotNull(s);
            worker.submit(() -> { while (!s.isComplete()) s.advance(); }).get(); actual.setEventsInhibited(false);
            PaintFloodParityTest.same(expected, actual);
        } finally { worker.shutdownNow(); restore(old); }
    }
    @Test public void clampedThemeAndClearedLayersKeepDefaultsAndPresence() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            var ranges = new TreeMap<Integer, Terrain>(); ranges.put(-65, Terrain.CUSTOM_1); ranges.put(54, Terrain.GRASS);
            Map<Filter, Layer> layers = new LinkedHashMap<>();
            layers.put((x, y, z, l) -> z < 54 ? 15 : 0, Frost.INSTANCE);
            layers.put((x, y, z, l) -> z < 54 ? 15 : 0, CHUNK);
            layers.put((x, y, z, l) -> 0, Biome.INSTANCE);
            Dimension a = fixture(new SimpleTheme(1, 62, ranges, layers, -64, 320, true, false), false);
            Dimension b = fixture(new SimpleTheme(1, 62, ranges, layers, -64, 320, true, false), false);
            for (Dimension d : new Dimension[] {a, b})
                ((SimpleTheme) ((HeightMapTileFactory) d.getTileFactory()).getTheme()).setDiscreteValues(Map.of(Biome.INSTANCE, 200));
            for (int pass = 0; pass < 5; pass++) {
                HeightThemeFloodBenchmark.fill(a, false); HeightThemeFloodBenchmark.fill(b, true); PaintFloodParityTest.same(a, b);
            }
            for (Tile t : b.getTiles()) { assertFalse(t.getBitLayerValue(Frost.INSTANCE, 64, 64)); assertFalse(t.getLayers().contains(Biome.INSTANCE)); }
            for (Dimension d : new Dimension[] {a, b})
                ((SimpleTheme) ((HeightMapTileFactory) d.getTileFactory()).getTheme()).setMinMaxHeight(16, 48, HeightTransform.IDENTITY);
            HeightThemeFloodBenchmark.fill(a, false); HeightThemeFloodBenchmark.fill(b, true); PaintFloodParityTest.same(a, b);
        } finally { restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
