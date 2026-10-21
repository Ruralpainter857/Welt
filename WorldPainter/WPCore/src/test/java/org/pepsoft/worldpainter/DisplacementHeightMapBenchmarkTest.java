package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.DisplacementHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/** End-to-end Java/Rust timing fixture for displacement over noisy height maps. */
public final class DisplacementHeightMapBenchmarkTest {
    private static volatile int sink;

    @Test
    public void pairedDisplacementProductionPathMatchesJava() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(true);
            assumePairBridgeAvailable();
            assertNativePathMatchesJava(createFactory());
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkJavaAndNativeTileCreationWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.displacement.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(true);
            assumePairBridgeAvailable();
            final HeightMapTileFactory factory = createFactory();
            final int tiles = 32;
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
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> sample(factory, tiles, rounds, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> sample(factory, tiles, rounds, true));
            final String result = String.format(
                    "tiles=%d rounds=%d java_ms_per_tile=%.4f native_ms_per_tile=%.4f speedup=%.3f "
                            + "java_memory=[%s] native_memory=[%s] sink=%d%n",
                    tiles, rounds, javaSamples[median], nativeSamples[median],
                    javaSamples[median] / nativeSamples[median], javaMemory, nativeMemory, sink);
            final Path output = Path.of(System.getProperty("welt.displacement.benchmark.output",
                    "target/displacement-heightmap-benchmark.txt"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, result, StandardCharsets.UTF_8);
            System.out.print("Displacement benchmark: " + result);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    private static void assumePairBridgeAvailable() {
        final double[] firstOutput = new double[1];
        final double[] secondOutput = new double[1];
        assumeTrue("paired height-map JNI entry point is not in the loaded native library",
                NativeSlices.fillHeightMapTreePair(0, 0, 1, 1, 1, 1,
                        new int[] {0, 0}, new double[] {2.0, 7.0},
                        new double[] {0.0, 0.0}, new int[] {0, 0},
                        new long[] {0L, 0L}, firstOutput, secondOutput));
        assertEquals(2.0, firstOutput[0], 0.0);
        assertEquals(7.0, secondOutput[0], 0.0);
    }

    private static void restoreGenerationFlag(String previousFlag) {
        if (previousFlag == null) {
            System.clearProperty(Native.GEN_KEY);
        } else {
            System.setProperty(Native.GEN_KEY, previousFlag);
        }
    }

    private static HeightMapTileFactory createFactory() {
        return new HeightMapTileFactory(0x57454c54L,
                new DisplacementHeightMap(
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 4, -0x1020_3040L)),
                        new NoiseHeightMap(6.0, 0.75, 2, 0x1234_5678L),
                        new NoiseHeightMap(8.0, 1.25, 2, -0x2468_1357L)),
                0, 256, false,
                SimpleTheme.createSingleTerrain(Terrain.GRASS, 0, 256, 62));
    }

    private static void assertNativePathMatchesJava(HeightMapTileFactory factory) {
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final int[][] coordinates = {{0, 0}, {-1, 0}, {3, -4}, {-7, 8}};
            for (final int[] coordinate : coordinates) {
                Native.setGenEnabled(false);
                final Tile javaTile = factory.createTile(coordinate[0], coordinate[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = factory.createTile(coordinate[0], coordinate[1]);
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        assertEquals("height mismatch at tile " + coordinate[0] + "," + coordinate[1]
                                        + " cell " + x + "," + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain mismatch at tile " + coordinate[0] + "," + coordinate[1]
                                        + " cell " + x + "," + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                        assertEquals("water level mismatch at tile " + coordinate[0] + "," + coordinate[1]
                                        + " cell " + x + "," + y,
                                javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    private static double sample(HeightMapTileFactory factory, int tileCount, int round, boolean nativePath) {
        Native.setGenEnabled(nativePath);
        final long start = System.nanoTime();
        int check = 0;
        for (int i = 0; i < tileCount; i++) {
            final int tileX = Math.floorMod(i * 7 + round, 9) - 4;
            final int tileY = Math.floorMod(i * 13 + round * 3, 9) - 4;
            final Tile tile = factory.createTile(tileX, tileY);
            check ^= Float.floatToRawIntBits(tile.getHeight(i & 127, (i * 17) & 127));
        }
        sink ^= check;
        return (System.nanoTime() - start) / 1_000_000.0 / tileCount;
    }
}
