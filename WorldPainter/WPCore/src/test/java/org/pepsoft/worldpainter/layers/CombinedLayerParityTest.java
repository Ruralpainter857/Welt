package org.pepsoft.worldpainter.layers;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleSupplier;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.CombinedLayerAccess;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class CombinedLayerParityTest {
    private static final Layer BYTES = new Layer("combined-test-bytes", "Bytes", "Byte plane", Layer.DataSize.BYTE, false, 50) {
        @Override public int getDefaultValue() { return 37; }
    };

    private static final class Draws implements DoubleSupplier {
        final Random random = new Random(317);
        int count;
        @Override public double getAsDouble() { count++; return random.nextDouble(); }
    }

    @Test public void nativeTransactionMatchesOriginalJavaForAllPlanesAndRandomOrder() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (float factor : new float[] {0f, 0.5f, 0.8f, 1.25f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
                for (boolean present : new boolean[] {false, true}) for (boolean terrain : new boolean[] {false, true}) {
                    CombinedLayer source = source(factor, terrain);
                    Tile java = fixture(source, present), rust = fixture(source, present);
                    Map<String, Integer> javaEvents = listen(java), rustEvents = listen(rust);
                    Draws expectedDraws = new Draws(), actualDraws = new Draws();
                    System.setProperty(Native.GEN_KEY, "false");
                    Set<Layer> expected = source.apply(java, expectedDraws);
                    System.setProperty(Native.GEN_KEY, "true");
                    rust.inhibitEvents();
                    CombinedLayerAccess.Result result;
                    try { result = CombinedLayerAccess.apply(rust, source, actualDraws); }
                    finally { rust.releaseEvents(); }
                    assertNotNull("Transaction must be supported", result);
                    assertNotNull("JNI must execute, rather than fall back", result.layers());
                    assertEquals(expected, result.layers());
                    assertEquals(expectedDraws.count, actualDraws.count);
                    assertEquals(expectedDraws.random.nextLong(), actualDraws.random.nextLong());
                    same(java, rust, source);
                    assertEquals(javaEvents, rustEvents);
                }
            }
        } finally { restore(old); }
    }

    @Test public void bulkApplicationPreservesUndoAndRedo() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            CombinedLayer source = source(0.8f, true);
            Tile tile = fixture(source, false), before = fixture(source, false), after = fixture(source, false);
            UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
            source.apply(tile, new Draws()); source.apply(after, new Draws());
            assertTrue(undo.undo()); same(before, tile, source);
            assertTrue(undo.redo()); same(after, tile, source);
            Tile untouched = new Tile(99, 99, -64, 320);
            assertFalse(untouched.hasLayer(Frost.INSTANCE));
            assertEquals(0, untouched.getRawHeight(5, 6));
        } finally { restore(old); }
    }

    @Test public void repeatedTargetsAndSelfReferencesFallBackBeforeConsumingRandom() {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            CombinedLayer source = source(1f, false);
            Draws draws = new Draws();
            Tile tile = fixture(source, false);
            source.setLayers(List.of(Frost.INSTANCE, Frost.INSTANCE));
            assertNull(CombinedLayerAccess.apply(tile, source, draws));
            assertEquals(0, draws.count);
            source.setLayers(List.of(source)); source.setFactors(Map.of(source, 1f));
            assertNull(CombinedLayerAccess.apply(tile, source, draws));
            assertEquals(0, draws.count);
        } finally { restore(old); }
    }

    @Test public void fallbackReplaysDrawsWithoutAdvancingTheRandomStreamTwice() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            CombinedLayer source = source(0.8f, true);
            Tile expected = fixture(source, false), actual = fixture(source, false);
            Draws reference = new Draws(), recorded = new Draws();
            System.setProperty(Native.GEN_KEY, "false");
            Set<Layer> expectedLayers = source.apply(expected, reference);
            System.setProperty(Native.GEN_KEY, "true");
            Set<Layer> actualLayers = source.apply(actual, () -> {
                // Force bridge fallback after preparation has begun recording the stream.
                System.setProperty(Native.GEN_KEY, "false");
                return recorded.getAsDouble();
            });
            assertEquals(expectedLayers, actualLayers);
            assertEquals(reference.count, recorded.count);
            assertEquals(reference.random.nextLong(), recorded.random.nextLong());
            same(expected, actual, source);
        } finally { restore(old); }
    }

    @Test public void assigningAnAbsentNumericDefaultDoesNotCreateAPlane() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            CombinedLayer source = source(1f, false);
            source.setLayers(List.of(BYTES)); source.setFactors(Map.of(BYTES, 37f / 7f));
            Tile expected = new Tile(-1, -1, -64, 320), actual = new Tile(-1, -1, -64, 320);
            expected.inhibitEvents(); actual.inhibitEvents();
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                expected.setLayerValue(source, x, y, 7); actual.setLayerValue(source, x, y, 7);
            }
            expected.releaseEvents(); actual.releaseEvents();
            System.setProperty(Native.GEN_KEY, "false"); Set<Layer> layers = source.apply(expected, new Draws());
            System.setProperty(Native.GEN_KEY, "true"); assertEquals(layers, source.apply(actual, new Draws()));
            assertTrue(layers.contains(BYTES)); assertFalse(actual.hasLayer(BYTES));
            same(expected, actual, source);
        } finally { restore(old); }
    }

    @Test public void workerBuffersAreIndependentAndReusable() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            CombinedLayer source = source(0.8f, true);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) futures.add(workers.submit(() -> {
                for (int iteration = 0; iteration < 3; iteration++) {
                    // Tile subclasses deliberately keep the original Java route, even with the flag enabled.
                    Tile expected = fixture(source, iteration != 0, true);
                    Tile actual = fixture(source, iteration != 0, false);
                    Draws javaDraws = new Draws(), rustDraws = new Draws();
                    assertEquals(source.apply(expected, javaDraws), source.apply(actual, rustDraws));
                    assertEquals(javaDraws.count, rustDraws.count);
                    assertEquals(javaDraws.random.nextLong(), rustDraws.random.nextLong());
                    same(expected, actual, source);
                }
            }));
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); restore(old); }
    }

    private static CombinedLayer source(float factor, boolean terrain) {
        CombinedLayer source = new CombinedLayer("Parity", "Combined transaction", Color.GREEN);
        source.setLayers(List.of(Resources.INSTANCE, BYTES, Frost.INSTANCE, Populate.INSTANCE));
        source.setFactors(Map.of(Resources.INSTANCE, factor, BYTES, factor, Frost.INSTANCE, factor, Populate.INSTANCE, factor));
        if (terrain) {
            source.setTerrain(Terrain.CUSTOM_1); source.setBiome(13); source.setApplyTerrainAndBiomeOnExport(true);
        }
        return source;
    }

    private static Tile fixture(CombinedLayer source, boolean present) {
        return fixture(source, present, false);
    }

    private static Tile fixture(CombinedLayer source, boolean present, boolean originalJava) {
        Tile tile = originalJava ? new Tile(-7, 19, -64, 320) { } : new Tile(-7, 19, -64, 320);
        tile.inhibitEvents();
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            tile.setLayerValue(source, x, y, (x * 3 + y * 7) & 15);
            if (present) {
                tile.setLayerValue(Resources.INSTANCE, x, y, (x + y) & 15);
                tile.setLayerValue(BYTES, x, y, (x * 11 + y * 3) & 255);
                tile.setBitLayerValue(Frost.INSTANCE, x, y, ((x + y) & 3) == 0);
                tile.setBitLayerValue(Populate.INSTANCE, x, y, ((x / 16 + y / 16) & 1) != 0);
                tile.setLayerValue(Biome.INSTANCE, x, y, (x + y) & 31);
            }
        }
        tile.releaseEvents(); return tile;
    }

    private static void same(Tile expected, Tile actual, CombinedLayer source) {
        assertEquals(expected.getLayers(), actual.getLayers());
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            assertEquals(expected.getTerrain(x, y), actual.getTerrain(x, y));
            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y));
            for (Layer layer : List.of(source, Resources.INSTANCE, BYTES, Frost.INSTANCE, Populate.INSTANCE, Biome.INSTANCE)) {
                if (layer.dataSize.maxValue == 1) assertEquals(expected.getBitLayerValue(layer, x, y), actual.getBitLayerValue(layer, x, y));
                else assertEquals(expected.getLayerValue(layer, x, y), actual.getLayerValue(layer, x, y));
            }
        }
    }

    private static Map<String, Integer> listen(Tile tile) {
        Map<String, Integer> events = new HashMap<>();
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                new Class<?>[] {Tile.Listener.class}, (p, m, a) -> { events.merge(m.getName(), 1, Integer::sum); return null; }));
        return events;
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
