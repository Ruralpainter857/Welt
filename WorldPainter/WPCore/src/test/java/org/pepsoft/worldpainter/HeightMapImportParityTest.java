package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.MathUtils;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.importing.HeightMapImporter;
import org.pepsoft.worldpainter.layers.Void;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Constants.MEDIUM_BLOBS;

/** Independent scalar oracle for the complete import, including theme random state. */
public class HeightMapImportParityTest {
    @Test public void completeImportsMatchAcrossModesAndSamplingPaths() throws Exception {
        withConfiguration(() -> {
            HeightMap bitmap = HeightMapImportBenchmark.image(1);
            HeightMap[] maps = {bitmap, new BicubicHeightMap(bitmap),
                    translated(bitmap, -61, -73), translated(new BicubicHeightMap(bitmap, true), -61, -73)};
            for (HeightMap map : maps) for (String mode : new String[] {"fresh", "existing", "raise"}) {
                compareImport(map, mode, false, true, 0);
                compareImport(map, mode, true, true, 0);
            }
        });
    }

    @Test public void heightConversionClampingAndThemesMatchAtTheirBoundaries() throws Exception {
        withConfiguration(() -> {
            BufferedImage image = new BufferedImage(19, 23, BufferedImage.TYPE_USHORT_GRAY);
            int[] levels = {0, 1, 49, 50, 61, 62, 63, 79, 80, 89, 90, 99, 100, 119, 120, 254, 255, 256, 319, 320, 65535};
            for (int x = 0; x < 19; x++) for (int y = 0; y < 23; y++)
                image.getRaster().setSample(x, y, 0, levels[(x * 7 + y) % levels.length]);
            HeightMap bitmap = BitmapHeightMap.build().withImage(image).now();
            for (HeightMap map : new HeightMap[] {bitmap, new BicubicHeightMap(bitmap), translated(bitmap, -9, -11)})
                for (String mode : new String[] {"fresh", "existing", "raise"})
                    for (int conversion = 1; conversion <= 3; conversion++)
                        compareImport(map, mode, true, true, conversion);
        });
    }

    @Test public void missingTilesAndAbsentThemeKeepTheirOriginalState() throws Exception {
        withConfiguration(() -> {
            HeightMap map = translated(HeightMapImportBenchmark.image(1), -15, -17);
            for (boolean voidBelow : new boolean[] {false, true}) {
                compareImport(map, "fresh", voidBelow, false, 0);
                compareImport(map, "existing", voidBelow, false, 0);
                compareImport(map, "raise", voidBelow, false, 0);
            }
            HeightMapImportBenchmark.State state = HeightMapImportBenchmark.fixture(map, "fresh", true);
            state.importer().importToDimension(state.dimension(), false, null);
            assertEquals(0, state.dimension().getTileCount());
        });
    }

    @Test public void existingImportsPreserveUndoRedoAndLayerPresence() throws Exception {
        withConfiguration(() -> {
            HeightMap map = HeightMapImportBenchmark.image(1);
            for (String mode : new String[] {"existing", "raise"}) {
                System.setProperty(Native.GEN_KEY, "false");
                HeightMapImportBenchmark.State before = HeightMapImportBenchmark.fixture(map, mode, true);
                HeightMapImportBenchmark.State actual = HeightMapImportBenchmark.fixture(map, mode, true);
                HeightMapImportBenchmark.State expected = HeightMapImportBenchmark.fixture(map, mode, true);
                UndoManager undo = new UndoManager();
                actual.dimension().registerUndoManager(undo); undo.armSavePoint();
                HeightMapImportBenchmark.random().setSeed(314);
                scalar(expected);
                HeightMapImportBenchmark.random().setSeed(314);
                System.setProperty(Native.GEN_KEY, "true"); actual.run();
                same(expected.dimension(), actual.dimension());
                assertTrue(undo.undo()); same(before.dimension(), actual.dimension());
                assertTrue(undo.redo()); same(expected.dimension(), actual.dimension());
            }
        });
    }

    @Test public void variedFactoryAndInitialRandomLayersMatch() throws Exception {
        withConfiguration(() -> {
            HeightMap map = translated(HeightMapImportBenchmark.image(1), -21, -31);
            for (boolean constant : new boolean[] {true, false}) {
                System.setProperty(Native.GEN_KEY, "false");
                HeightMapImportBenchmark.State expected = HeightMapImportBenchmark.fixture(map, "fresh", true);
                HeightMapImportBenchmark.State actual = HeightMapImportBenchmark.fixture(map, "fresh", true);
                for (HeightMapImportBenchmark.State state : new HeightMapImportBenchmark.State[] {expected, actual}) {
                    HeightMap initial = constant ? new ConstantHeightMap(105)
                            : new SumHeightMap(new ConstantHeightMap(80), new NoiseHeightMap(70, .4, 3, 0));
                    initial.setSeed(197);
                    ((HeightMapTileFactory) state.importer().getTileFactory()).setHeightMap(initial);
                }
                HeightMapImportBenchmark.random().setSeed(77); scalar(expected);
                long next = HeightMapImportBenchmark.random().nextLong();
                HeightMapImportBenchmark.random().setSeed(77); System.setProperty(Native.GEN_KEY, "true"); actual.run();
                assertEquals(next, HeightMapImportBenchmark.random().nextLong()); same(expected.dimension(), actual.dimension());
                if (NativeLoader.areSlicesAvailable()) assertEquals(4, actual.importer().getLastNativeImportCalls());
            }
        });
    }

    @Test public void shortAndTallTilesHaveExactValuesAndCoalescedEvents() throws Exception {
        withConfiguration(() -> {
            for (int[] bounds : new int[][] {{0, 128}, {0, 256}, {-64, 320}, {-128, 1024}}) {
                HeightMap map = HeightMapImportBenchmark.image(1);
                for (String mode : new String[] {"fresh", "existing", "raise"}) {
                    System.setProperty(Native.GEN_KEY, "false");
                    HeightMapImportBenchmark.random().setSeed(23);
                    HeightMapImportBenchmark.State expected = boundedFixture(map, mode, bounds[0], bounds[1]);
                    HeightMapImportBenchmark.random().setSeed(23);
                    HeightMapImportBenchmark.State actual = boundedFixture(map, mode, bounds[0], bounds[1]);
                    java.util.Map<String, Integer> expectedEvents = events(expected.dimension());
                    java.util.Map<String, Integer> actualEvents = events(actual.dimension());
                    HeightMapImportBenchmark.random().setSeed(81); scalar(expected);
                    long next = HeightMapImportBenchmark.random().nextLong();
                    HeightMapImportBenchmark.random().setSeed(81); System.setProperty(Native.GEN_KEY, "true"); actual.run();
                    assertEquals(next, HeightMapImportBenchmark.random().nextLong()); same(expected.dimension(), actual.dimension());
                    assertEquals(expectedEvents, actualEvents);
                    if (NativeLoader.areSlicesAvailable()) assertEquals(1, actual.importer().getLastNativeImportCalls());
                }
            }
        });
    }

    @Test public void customThemeAndDisabledNativeKeepTheJavaPath() throws Exception {
        withConfiguration(() -> {
            HeightMap map = HeightMapImportBenchmark.image(1);
            for (boolean enabled : new boolean[] {false, true}) {
                System.setProperty(Native.GEN_KEY, "false");
                HeightMapImportBenchmark.State expected = HeightMapImportBenchmark.fixture(map, "existing", true);
                HeightMapImportBenchmark.State actual = HeightMapImportBenchmark.fixture(map, "existing", true);
                for (HeightMapImportBenchmark.State state : new HeightMapImportBenchmark.State[] {expected, actual}) {
                    state.importer().setTheme(new org.pepsoft.worldpainter.themes.SimpleTheme(197, 62,
                            new java.util.TreeMap<>(java.util.Map.of(-65, Terrain.STONE)), null, -64, 320, false, false) {
                        @Override public Terrain getTerrain(int x, int y, int height) {return (x+y)%2==0?Terrain.GRASS:Terrain.CUSTOM_3;}
                    });
                }
                scalar(expected); System.setProperty(Native.GEN_KEY, Boolean.toString(enabled)); actual.run();
                assertEquals(0, actual.importer().getLastNativeImportCalls()); same(expected.dimension(), actual.dimension());
            }
        });
    }

    private static HeightMapImportBenchmark.State boundedFixture(HeightMap map, String mode, int min, int max) {
        World2 world = new World2(max == 128 ? DefaultPlugin.JAVA_MCREGION : DefaultPlugin.JAVA_ANVIL_1_19, min, max);
        HeightMapTileFactory factory = new HeightMapTileFactory(197, new ConstantHeightMap(105), min, max, false,
                HeightMapImportBenchmark.theme(min, max));
        Dimension dimension = new Dimension(world, "Bounded import", 197, factory, Dimension.Anchor.NORMAL_DETAIL, false);
        if (!mode.equals("fresh")) dimension.addTile(factory.createTile(0, 0));
        HeightMapImporter importer = new HeightMapImporter(); importer.setHeightMap(map); importer.setTileFactory(factory);
        importer.setMinHeight(min); importer.setMaxHeight(max); importer.setWorldLowLevel(min); importer.setWorldHighLevel(max-1);
        importer.setWorldWaterLevel(62); importer.setImageLowLevel(0); importer.setImageHighLevel(65535);
        importer.setOnlyRaise(mode.equals("raise")); importer.setVoidBelow(true); importer.setVoidBelowLevel(4096);
        return new HeightMapImportBenchmark.State(dimension, importer, mode.equals("fresh"));
    }
    private static java.util.Map<String, Integer> events(Dimension dimension) {
        java.util.Map<String, Integer> result = new java.util.TreeMap<>();
        for (Tile tile : dimension.getTiles()) tile.addListener((Tile.Listener) java.lang.reflect.Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> {result.merge(m.getName(), 1, Integer::sum);return null;}));
        return result;
    }

    private static void compareImport(HeightMap map, String mode, boolean voidBelow, boolean themed,
                                      int conversion) throws Exception {
        System.setProperty(Native.GEN_KEY, "false");
        HeightMapImportBenchmark.State expected = HeightMapImportBenchmark.fixture(map, mode, voidBelow);
        HeightMapImportBenchmark.State actual = HeightMapImportBenchmark.fixture(map, mode, voidBelow);
        for (HeightMapImportBenchmark.State state : new HeightMapImportBenchmark.State[] {expected, actual}) {
            if (!themed) state.importer().setTheme(null);
            if (conversion == 1) {
                state.importer().setWorldLowLevel(0); state.importer().setImageLowLevel(0);
                state.importer().setWorldHighLevel(255); state.importer().setImageHighLevel(255);
            } else if (conversion == 2) {
                state.importer().setWorldLowLevel(-64); state.importer().setImageLowLevel(0);
                state.importer().setWorldHighLevel(319); state.importer().setImageHighLevel(65535);
            } else if (conversion == 3) {
                state.importer().setWorldLowLevel(-64); state.importer().setImageLowLevel(17);
                state.importer().setWorldHighLevel(319); state.importer().setImageHighLevel(201);
            }
            // Existing Void values are never cleared by an import.
            for (Tile tile : state.dimension().getTiles()) tile.setBitLayerValue(Void.INSTANCE, 4, 5, true);
        }
        HeightMapImportBenchmark.random().setSeed(197);
        scalar(expected); long next = HeightMapImportBenchmark.random().nextLong();
        HeightMapImportBenchmark.random().setSeed(197);
        System.setProperty(Native.GEN_KEY, "true"); actual.run();
        assertEquals("The theme must consume exactly the same random stream", next, HeightMapImportBenchmark.random().nextLong());
        if (NativeLoader.areSlicesAvailable()) assertEquals("One JNI transaction per imported tile",
                actual.dimension().getTileCount(), actual.importer().getLastNativeImportCalls());
        same(expected.dimension(), actual.dimension());
    }

    /** Intentionally samples and mutates one cell at a time, without any batch APIs. */
    private static void scalar(HeightMapImportBenchmark.State state) {
        HeightMapImporter importer = state.importer(); HeightMap map = importer.getHeightMap();
        Rectangle extent = map.getExtent();
        int min = importer.getMinHeight(), max = importer.getMaxHeight() - 1;
        boolean oneOnOne = importer.getWorldLowLevel() == importer.getImageLowLevel()
                && importer.getWorldHighLevel() == importer.getImageHighLevel();
        boolean highRes = importer.getImageHighLevel() >= importer.getMaxHeight()
                && importer.getWorldHighLevel() < importer.getMaxHeight();
        boolean unscaled = map instanceof BitmapHeightMap
                || map instanceof TransformingHeightMap && ((TransformingHeightMap) map).getScaleX() == 1f
                && ((TransformingHeightMap) map).getScaleY() == 1f
                && ((TransformingHeightMap) map).getBaseHeightMap() instanceof BitmapHeightMap;
        double scale = (importer.getWorldHighLevel() - importer.getWorldLowLevel())
                / (importer.getImageHighLevel() - importer.getImageLowLevel());
        int floor = Math.max(importer.getWorldWaterLevel() - 20, min);
        int variation = Math.min(15, (importer.getWorldWaterLevel() - floor) / 2);
        PerlinNoise noise = new PerlinNoise(state.dimension().getSeed());
        for (int tx = extent.x >> 7; tx <= (extent.x + extent.width - 1) >> 7; tx++)
            for (int ty = extent.y >> 7; ty <= (extent.y + extent.height - 1) >> 7; ty++) {
                Tile tile = state.dimension().getTileForEditing(tx, ty); boolean fresh = tile == null;
                if (fresh) {
                    if (!state.createTiles()) continue;
                    tile = importer.getTileFactory().createTile(tx, ty);
                } else tile.inhibitEvents();
                try {
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                        int imageX = (tx << 7) + x, imageY = (ty << 7) + y;
                        if (extent.contains(imageX, imageY)) {
                            double value = map.getHeight(imageX, imageY);
                            float height = MathUtils.clamp(min, (float) ((!highRes && oneOnOne)
                                    ? (unscaled ? value - .4375 : value)
                                    : (value - importer.getImageLowLevel()) * scale + importer.getWorldLowLevel()), max);
                            if (importer.isOnlyRaise() && !fresh) {
                                if (!(height > tile.getHeight(x, y))) continue;
                                tile.setHeight(x, y, height);
                            } else {
                                tile.setHeight(x, y, height);
                                tile.setWaterLevel(x, y, importer.getWorldWaterLevel());
                                if (importer.isVoidBelow() && value <= importer.getVoidBelowLevel())
                                    tile.setBitLayerValue(Void.INSTANCE, x, y, true);
                            }
                            if (importer.getTheme() != null) importer.getTheme().apply(tile, x, y);
                        } else if (fresh) {
                            tile.setHeight(x, y, floor + (noise.getPerlinNoise(imageX / MEDIUM_BLOBS,
                                    imageY / MEDIUM_BLOBS) + .5f) * variation);
                            tile.setTerrain(x, y, Terrain.BEACHES);
                            tile.setWaterLevel(x, y, importer.getWorldWaterLevel());
                            if (importer.isVoidBelow()) tile.setBitLayerValue(Void.INSTANCE, x, y, true);
                        }
                    }
                } finally { if (!fresh) tile.releaseEvents(); }
                if (fresh) state.dimension().addTile(tile);
            }
    }

    private static HeightMap translated(HeightMap map, int x, int y) {
        return new TransformingHeightMap("Import parity", map, 1f, 1f, x, y, 0f);
    }
    private static void same(Dimension expected, Dimension actual) {
        assertEquals(expected.getTileCoords(), actual.getTileCoords());
        for (Tile tile : expected.getTiles()) {
            Tile other = actual.getTile(tile.getX(), tile.getY());
            assertFalse(other.isEventsInhibited()); ThemeResetParityTest.same(tile, other);
        }
    }
    private static void withConfiguration(CheckedAction action) throws Exception {
        Configuration old = Configuration.getInstance(); String flag = System.getProperty(Native.GEN_KEY);
        Configuration configuration = new Configuration(); configuration.setDefaultPlatform(DefaultPlugin.JAVA_ANVIL_1_19);
        Configuration.setInstance(configuration);
        try { action.run(); }
        finally {
            Configuration.setInstance(old);
            if (flag == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, flag);
        }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
