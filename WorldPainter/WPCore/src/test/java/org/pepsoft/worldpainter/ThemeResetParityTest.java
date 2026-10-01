package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Filter;
import java.util.*;
import static org.junit.Assert.*;

public class ThemeResetParityTest {
    private static final Layer CHUNK = new Layer("welt.test.theme.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) {};
    private static final Layer NIBBLE = new Layer("welt.test.theme.nibble", "Nibble", "", Layer.DataSize.NIBBLE, false, 31) {
        @Override public int getDefaultValue() { return 7; }
    };
    static Random random() throws Exception {
        var field = SimpleTheme.class.getDeclaredField("random"); field.setAccessible(true); return (Random) field.get(null);
    }
    static SimpleTheme theme(boolean randomise, boolean beaches) {
        var ranges = new TreeMap<Integer, Terrain>();
        ranges.put(-65, Terrain.CUSTOM_1); ranges.put(64, Terrain.GRASS); ranges.put(180, Terrain.STONE);
        Map<Filter, Layer> layers = new LinkedHashMap<>();
        layers.put((x, y, z, l) -> Math.floorMod(z, 16), NIBBLE);
        layers.put((x, y, z, l) -> z > 40 ? 15 : 0, Biome.INSTANCE);
        layers.put((x, y, z, l) -> Math.floorMod(z, 18) - 1, Frost.INSTANCE);
        layers.put((x, y, z, l) -> Math.floorMod(z + 3, 16), CHUNK);
        var theme = new SimpleTheme(987654321L, 62, ranges, layers, -64, 320, randomise, beaches);
        theme.setDiscreteValues(Map.of(Biome.INSTANCE, 200)); return theme;
    }
    static void same(Tile a, Tile b) {
        assertEquals(a.getLayers(), b.getLayers());
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            assertEquals(a.getRawHeight(x, y), b.getRawHeight(x, y));
            assertEquals(a.getWaterLevel(x, y), b.getWaterLevel(x, y));
            assertEquals(a.getTerrain(x, y), b.getTerrain(x, y));
            for (Layer layer : a.getLayers()) {
                if (layer.getDataSize() == Layer.DataSize.BIT || layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK)
                    assertEquals(a.getBitLayerValue(layer, x, y), b.getBitLayerValue(layer, x, y));
                else assertEquals(a.getLayerValue(layer, x, y), b.getLayerValue(layer, x, y));
            }
        }
    }
    private static Map<String, Integer> events(Tile tile) {
        Map<String, Integer> events = new TreeMap<>();
        tile.addListener((Tile.Listener) java.lang.reflect.Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                new Class<?>[] {Tile.Listener.class}, (p, m, a) -> { events.merge(m.getName(), 1, Integer::sum); return null; }));
        return events;
    }
    @Test public void shortTilesClampToThemeAndCoalesceIdenticalEvents() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Tile a = new Tile(-100, 100, 0, 256), b = new Tile(-100, 100, 0, 256);
            a.inhibitEvents(); b.inhibitEvents();
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                a.setHeight(x, y, (x + y) % 256 + .5f); b.setHeight(x, y, (x + y) % 256 + .5f);
                a.setLayerValue(NIBBLE, x, y, 7); b.setLayerValue(NIBBLE, x, y, 7);
            }
            a.releaseEvents(); b.releaseEvents();
            SimpleTheme theme = SimpleTheme.createDefault(Terrain.CUSTOM_1, 16, 128, 62, true, true);
            Map<String, Integer> expected = events(a), actual = events(b);
            a.inhibitEvents(); b.inhibitEvents();
            random().setSeed(99); ThemeResetBenchmark.scalar(theme, a);
            random().setSeed(99); assertTrue(theme.applyToExistingTile(b));
            a.releaseEvents(); b.releaseEvents(); same(a, b); assertEquals(expected, actual);
            var factory = new HeightMapTileFactory(0L, new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(62),
                    0, 256, false, theme);
            b.inhibitEvents(); assertTrue(factory.tryApplyTheme(b)); b.releaseEvents();
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    @Test public void uniformAndCustomThemesKeepTheirExistingBehavior() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            var ranges = new TreeMap<Integer, Terrain>(); ranges.put(-65, Terrain.STONE);
            SimpleTheme uniform = new SimpleTheme(1, 62, ranges, null, -64, 320, true, false);
            Tile a = ThemeResetBenchmark.fixture(3), b = ThemeResetBenchmark.fixture(3);
            a.inhibitEvents(); b.inhibitEvents(); ThemeResetBenchmark.scalar(uniform, a);
            assertTrue(uniform.applyToExistingTile(b)); a.releaseEvents(); b.releaseEvents(); same(a, b);
            SimpleTheme custom = new SimpleTheme(1, 62, ranges, null, -64, 320, false, false) {
                @Override public Terrain getTerrain(int x, int y, int z) { return Terrain.GRASS; }
            };
            b.inhibitEvents(); assertFalse(custom.applyToExistingTile(b)); b.releaseEvents(); same(a, b);
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    @Test public void rejectsInvalidPlanesBeforeChangingTerrain() {
        Tile a = ThemeResetBenchmark.fixture(2), b = ThemeResetBenchmark.fixture(2);
        b.inhibitEvents();
        int[] terrains = new int[16384]; Arrays.fill(terrains, Terrain.STONE.ordinal());
        byte[][] values = new byte[1][16384]; values[0][16383] = 16;
        try { b.applyPreparedTheme(terrains, new Layer[] {NIBBLE}, values); fail("Invalid plane accepted"); }
        catch (IllegalArgumentException expected) { same(a, b); }
        finally { b.releaseEvents(); }
    }
    @Test public void completeThemesAndRandomStreamMatchWithRepeatedEdits() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean noise : new boolean[] {false, true}) for (boolean beaches : new boolean[] {false, true}) {
                SimpleTheme theme = theme(noise, beaches);
                Tile a = ThemeResetBenchmark.fixture(0), b = ThemeResetBenchmark.fixture(0);
                a.setBitLayerValue(FloodWithLava.INSTANCE, 7, 8, true); b.setBitLayerValue(FloodWithLava.INSTANCE, 7, 8, true);
                for (int pass = 0; pass < 3; pass++) {
                    a.inhibitEvents(); b.inhibitEvents();
                    random().setSeed(1234 + pass); ThemeResetBenchmark.scalar(theme, a); long next = random().nextLong();
                    random().setSeed(1234 + pass); assertTrue(theme.applyToExistingTile(b)); assertEquals(next, random().nextLong());
                    a.releaseEvents(); b.releaseEvents(); same(a, b);
                }
            }
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    @Test public void undoRedoAndFallbackPreserveState() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            SimpleTheme theme = theme(true, true);
            Tile before = ThemeResetBenchmark.fixture(1), actual = ThemeResetBenchmark.fixture(1), expected = ThemeResetBenchmark.fixture(1);
            assertFalse(theme.applyToExistingTile(actual)); same(before, actual);
            for (Tile tile : new Tile[] {before, actual, expected}) {
                tile.inhibitEvents(); random().setSeed(55); ThemeResetBenchmark.scalar(theme, tile); tile.releaseEvents();
                tile.setBitLayerValue(FloodWithLava.INSTANCE, 8, 9, true);
            }
            theme.setSeed(-8237L);
            UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint();
            actual.inhibitEvents(); random().setSeed(77); assertTrue(theme.applyToExistingTile(actual)); actual.releaseEvents();
            random().setSeed(77); expected.inhibitEvents(); ThemeResetBenchmark.scalar(theme, expected); expected.releaseEvents(); same(expected, actual);
            assertTrue(undo.undo()); same(before, actual); assertTrue(undo.redo()); same(expected, actual);
            actual.inhibitEvents(); System.setProperty(Native.GEN_KEY, "false");
            long next; random().setSeed(19); next = random().nextLong(); random().setSeed(19);
            assertFalse(theme.applyToExistingTile(actual)); assertEquals(next, random().nextLong()); actual.releaseEvents(); same(expected, actual);
        } finally { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
}
