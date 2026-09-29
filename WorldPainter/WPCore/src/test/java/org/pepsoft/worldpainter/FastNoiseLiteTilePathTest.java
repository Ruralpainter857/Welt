package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.FastNoiseLiteHeightMap;
import org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

public final class FastNoiseLiteTilePathTest {
    private static final long WORLD_SEED = 0x5745_4c54L;
    private static final long NOISE_OFFSET = 0x1234_5678_9abc_def0L;
    private static volatile int sink;

    @Test
    public void completeTileMatchesJavaForEverySupportedNoiseAndFractalType() {
        assumeTrue("welt_slices release library is required", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final FastNoiseLite.FractalType[] fractalTypes = {
                    FastNoiseLite.FractalType.None,
                    FastNoiseLite.FractalType.FBm,
                    FastNoiseLite.FractalType.Ridged,
                    FastNoiseLite.FractalType.PingPong
            };
            final int[][] tiles = {{-3, 7}, {-12, -8}};
            for (FastNoiseLite.NoiseType noiseType : FastNoiseLite.NoiseType.values()) {
                for (FastNoiseLite.FractalType fractalType : fractalTypes) {
                    for (int[] tileCoords : tiles) {
                        final FastNoiseLiteHeightMap javaMap = createMap(noiseType, fractalType);
                        final FastNoiseLiteHeightMap nativeMap = createMap(noiseType, fractalType);
                        final HeightMapTileFactory javaFactory = createFactory(javaMap);
                        final HeightMapTileFactory nativeFactory = createFactory(nativeMap);

                        Native.setGenEnabled(false);
                        final Tile javaTile = javaFactory.createTile(tileCoords[0], tileCoords[1]);
                        Native.setGenEnabled(true);
                        final Tile nativeTile = nativeFactory.createTile(tileCoords[0], tileCoords[1]);
                        assertTilesEqual(noiseType + "/" + fractalType
                                + " tile=" + Arrays.toString(tileCoords), javaTile, nativeTile);
                    }
                }
            }
        } finally {
            restore(Native.GEN_KEY, previousFlag);
        }
    }

    @Test
    public void benchmarkCompleteTileCreationAndMemory() throws Exception {
        assumeTrue("Set -Dwelt.fastNoise.benchmark=true to run the opt-in full-tile benchmark",
                Boolean.getBoolean("welt.fastNoise.benchmark"));
        assumeTrue("welt_slices release library is required", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMapTileFactory factory = createFactory(
                    createMap(FastNoiseLite.NoiseType.OpenSimplex2, FastNoiseLite.FractalType.FBm));
            final int tiles = 128;
            final int rounds = 9;
            final double[] javaSamples = new double[rounds];
            final double[] nativeSamples = new double[rounds];

            for (int warmup = 0; warmup < 6; warmup++) {
                sample(factory, tiles, warmup, false);
                sample(factory, tiles, warmup, true);
            }
            for (int round = 0; round < rounds; round++) {
                if ((round & 1) == 0) {
                    javaSamples[round] = sample(factory, tiles, round, false);
                    nativeSamples[round] = sample(factory, tiles, round, true);
                } else {
                    nativeSamples[round] = sample(factory, tiles, round, true);
                    javaSamples[round] = sample(factory, tiles, round, false);
                }
            }
            Arrays.sort(javaSamples);
            Arrays.sort(nativeSamples);
            final int median = rounds / 2;
            final BenchmarkMemorySupport.Snapshot javaMemory =
                    BenchmarkMemorySupport.measure(() -> sample(factory, tiles, rounds, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory =
                    BenchmarkMemorySupport.measure(() -> sample(factory, tiles, rounds, true));
            final String result = String.format(
                    "tiles=%d rounds=%d java_ms_per_tile=%.4f native_ms_per_tile=%.4f speedup=%.3f "
                            + "java_memory=[%s] native_memory=[%s] sink=%d%n",
                    tiles, rounds, javaSamples[median], nativeSamples[median],
                    javaSamples[median] / nativeSamples[median], javaMemory, nativeMemory, sink);
            final Path output = Path.of(System.getProperty("welt.fastNoise.benchmark.output",
                    "target/fastnoise-tile-benchmark.txt"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, result, StandardCharsets.UTF_8);
            System.out.print("FastNoiseLite tile benchmark: " + result);
        } finally {
            restore(Native.GEN_KEY, previousFlag);
        }
    }
    private static FastNoiseLiteHeightMap createMap(FastNoiseLite.NoiseType noiseType,
                                                     FastNoiseLite.FractalType fractalType) {
        final FastNoiseLiteHeightMap map =
                new FastNoiseLiteHeightMap("FastNoiseLite parity", 192.0, 1.75, 5, NOISE_OFFSET);
        map.setNoiseType(noiseType);
        map.setFractalType(fractalType);
        return map;
    }

    private static HeightMapTileFactory createFactory(FastNoiseLiteHeightMap map) {
        return new HeightMapTileFactory(WORLD_SEED, map, 0, 256, false,
                SimpleTheme.createSingleTerrain(Terrain.GRASS, 0, 256, 62));
    }

    private static void assertTilesEqual(String label, Tile expected, Tile actual) {
        assertEquals(label + " layers", new HashSet<>(expected.getLayers()), new HashSet<>(actual.getLayers()));
        final Set<Layer> layers = new HashSet<>(expected.getLayers());
        layers.addAll(actual.getLayers());
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                assertEquals(label + " raw height at " + x + ',' + y,
                        expected.getRawHeight(x, y), actual.getRawHeight(x, y));
                assertEquals(label + " water at " + x + ',' + y,
                        expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
                assertEquals(label + " terrain at " + x + ',' + y,
                        expected.getTerrain(x, y), actual.getTerrain(x, y));
                for (Layer layer : layers) {
                    if ((layer.getDataSize() == Layer.DataSize.BIT)
                            || (layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK)) {
                        assertEquals(label + " layer " + layer + " at " + x + ',' + y,
                                expected.getBitLayerValue(layer, x, y),
                                actual.getBitLayerValue(layer, x, y));
                    } else {
                        assertEquals(label + " layer " + layer + " at " + x + ',' + y,
                                expected.getLayerValue(layer, x, y),
                                actual.getLayerValue(layer, x, y));
                    }
                }
            }
        }
    }

    private static double sample(HeightMapTileFactory factory, int tileCount, int round, boolean nativePath) {
        Native.setGenEnabled(nativePath);
        final long started = System.nanoTime();
        int checksum = 0;
        for (int i = 0; i < tileCount; i++) {
            final int tileX = Math.floorMod(i * 7 + round, 9) - 4;
            final int tileY = Math.floorMod(i * 13 + round * 3, 9) - 4;
            final Tile tile = factory.createTile(tileX, tileY);
            checksum ^= tile.getRawHeight(i & 127, (i * 17) & 127);
            checksum ^= tile.getTerrain(i & 127, (i * 17) & 127).ordinal();
        }
        sink ^= checksum;
        return (System.nanoTime() - started) / 1_000_000.0 / tileCount;
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
