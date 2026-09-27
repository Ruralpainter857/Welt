package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.SlopeHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assume.assumeTrue;

/** End-to-end Java/Rust timing fixture for slope generation over a noisy base map. */
public final class SlopeHeightMapBenchmarkTest {
    private static volatile int sink;

    @Test
    public void compareJavaAndNativeTileCreation() throws Exception {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMapTileFactory factory = new HeightMapTileFactory(0x57454c54L,
                    new SlopeHeightMap(new SumHeightMap(new ConstantHeightMap(42.25),
                            new NoiseHeightMap(38.0, 0.8, 4, -0x1020_3040L)), 1.75f),
                    0, 256, false,
                    SimpleTheme.createSingleTerrain(Terrain.GRASS, 0, 256, 62));
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
            final String result = String.format(
                    "tiles=%d rounds=%d java_ms_per_tile=%.4f native_ms_per_tile=%.4f speedup=%.3f sink=%d%n",
                    tiles, rounds, javaSamples[median], nativeSamples[median],
                    javaSamples[median] / nativeSamples[median], sink);
            final Path output = Path.of(System.getProperty("welt.slope.benchmark.output",
                    "target/slope-heightmap-benchmark.txt"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, result, StandardCharsets.UTF_8);
            System.out.print("Slope benchmark: " + result);
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
