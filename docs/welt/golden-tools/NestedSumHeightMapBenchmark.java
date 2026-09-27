import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.util.Arrays;

/** Compares Java and Rust generation for a nested sum of two noise maps. */
public final class NestedSumHeightMapBenchmark {
    private static volatile int sink;

    private NestedSumHeightMapBenchmark() {
    }

    public static void main(String[] args) {
        final int tiles = args.length > 0 ? Integer.parseInt(args[0]) : 48;
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 9;
        if (!NativeLoader.areSlicesAvailable()) {
            throw new IllegalStateException("welt_slices is unavailable; build with Maven -Pnative");
        }
        final HeightMapTileFactory factory = new HeightMapTileFactory(0x57454c54L,
                new SumHeightMap(
                        new SumHeightMap(new ConstantHeightMap(48.25),
                                new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                        new NoiseHeightMap(12.0, 2.1, 5, -0x2468_1357L)),
                0, 256, false,
                SimpleTheme.createSingleTerrain(org.pepsoft.worldpainter.Terrain.GRASS, 0, 256, 62));
        final double[] javaSamples = new double[rounds];
        final double[] rustSamples = new double[rounds];

        for (int i = 0; i < 10; i++) {
            sample(factory, tiles, false);
            sample(factory, tiles, true);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                javaSamples[round] = sample(factory, tiles, false);
                rustSamples[round] = sample(factory, tiles, true);
            } else {
                rustSamples[round] = sample(factory, tiles, true);
                javaSamples[round] = sample(factory, tiles, false);
            }
        }
        Arrays.sort(javaSamples);
        Arrays.sort(rustSamples);
        System.out.printf("tiles=%d rounds=%d java_median_ms_per_tile=%.4f rust_median_ms_per_tile=%.4f rust_speedup=%.3f sink=%d%n",
                tiles, rounds, javaSamples[rounds / 2], rustSamples[rounds / 2],
                javaSamples[rounds / 2] / rustSamples[rounds / 2], sink);
    }

    private static double sample(HeightMapTileFactory factory, int tiles, boolean rust) {
        Native.setGenEnabled(rust);
        final long start = System.nanoTime();
        int check = 0;
        for (int i = 0; i < tiles; i++) {
            final Tile tile = factory.createTile(-5 + i, 7 - i);
            check ^= Float.floatToRawIntBits(tile.getHeight(i & 127, (i * 17) & 127));
        }
        final long elapsed = System.nanoTime() - start;
        sink ^= check;
        return elapsed / 1_000_000.0 / tiles;
    }
}
