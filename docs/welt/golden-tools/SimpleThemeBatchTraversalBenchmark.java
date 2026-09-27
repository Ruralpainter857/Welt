import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.themes.Filter;
import org.pepsoft.worldpainter.themes.HeightFilter;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Compares fresh-tile traversal against the per-cell SimpleTheme fallback. */
public final class SimpleThemeBatchTraversalBenchmark {
    private static volatile int sink;

    private SimpleThemeBatchTraversalBenchmark() {
    }

    public static void main(String[] args) {
        final int tiles = args.length > 0 ? Integer.parseInt(args[0]) : 48;
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 9;
        final HeightMapTileFactory legacy = factory(true);
        final HeightMapTileFactory batch = factory(false);
        final double[] legacySamples = new double[rounds];
        final double[] batchSamples = new double[rounds];
        for (int warmup = 0; warmup < 10; warmup++) {
            sample(legacy, tiles, warmup, false);
            sample(batch, tiles, warmup, true);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                legacySamples[round] = sample(legacy, tiles, round, false);
                batchSamples[round] = sample(batch, tiles, round, true);
            } else {
                batchSamples[round] = sample(batch, tiles, round, true);
                legacySamples[round] = sample(legacy, tiles, round, false);
            }
        }
        Arrays.sort(legacySamples);
        Arrays.sort(batchSamples);
        System.out.printf("tiles=%d rounds=%d legacy_ms_per_tile=%.4f batch_ms_per_tile=%.4f speedup=%.3f sink=%d%n",
                tiles, rounds, legacySamples[rounds / 2], batchSamples[rounds / 2],
                legacySamples[rounds / 2] / batchSamples[rounds / 2], sink);
    }

    private static HeightMapTileFactory factory(boolean legacy) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(90, Terrain.STONE_MIX);
        ranges.put(150, Terrain.DEEP_SNOW);
        final Map<Filter, Layer> layers = new HashMap<>();
        layers.put(new HeightFilter(0, 256, 60, 160, true), FloodWithLava.INSTANCE);
        layers.put(new HeightFilter(0, 256, 130, 210, true), Frost.INSTANCE);
        final SimpleTheme selectedTheme = legacy
                ? new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { }
                : new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
        return new HeightMapTileFactory(0x3141_5926L,
                new SumHeightMap(new ConstantHeightMap(78),
                        new NoiseHeightMap(46, 0.9, 4, 0x1234_5678L)),
                0, 256, false, selectedTheme);
    }

    private static double sample(HeightMapTileFactory factory, int tiles, int round, boolean batch) {
        System.setProperty("wp.native.gen", "false");
        final long start = System.nanoTime();
        int check = 0;
        for (int tile = 0; tile < tiles; tile++) {
            final Tile result = factory.createTile(-80 + tile, 6 + round);
            check ^= result.getIntHeight(tile & 127, (tile * 29) & 127);
        }
        sink ^= check ^ (batch ? 1 : 0);
        return (System.nanoTime() - start) / 1_000_000.0 / tiles;
    }
}
