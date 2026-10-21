import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.TileFactoryFactory;

import java.util.Arrays;

/** Measures the actual default constant-plus-noise tile creation path. */
public final class NoiseTileFactoryBenchmark {
    private static volatile float sink;

    private NoiseTileFactoryBenchmark() {
    }

    public static void main(String[] args) {
        final int tilesPerRound = args.length > 0 ? Integer.parseInt(args[0]) : 48;
        final HeightMapTileFactory factory = TileFactoryFactory.createNoiseTileFactory(
                0x3141_5926L, Terrain.GRASS, 0, 256, 58, 62,
                false, true, 20.0f, 1.0);
        for (int i = 0; i < 24; i++) {
            sink = sample(factory, i, false);
            sink = sample(factory, i, true);
        }
        final double[] javaTimes = new double[5];
        final double[] nativeTimes = new double[5];
        for (int round = 0; round < javaTimes.length; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(factory, tilesPerRound, round, false);
                nativeTimes[round] = measure(factory, tilesPerRound, round, true);
            } else {
                nativeTimes[round] = measure(factory, tilesPerRound, round, true);
                javaTimes[round] = measure(factory, tilesPerRound, round, false);
            }
        }
        Arrays.sort(javaTimes);
        Arrays.sort(nativeTimes);
        System.out.printf("tiles_per_round=%d java_ms_per_tile=%.4f native_ms_per_tile=%.4f speedup=%.2fx%n",
                tilesPerRound, javaTimes[2], nativeTimes[2], javaTimes[2] / nativeTimes[2]);
        if (Float.isNaN(sink)) {
            System.out.println("sink=NaN");
        }
    }

    private static double measure(HeightMapTileFactory factory, int tiles, int round, boolean nativePath) {
        final long start = System.nanoTime();
        float sum = 0.0f;
        for (int i = 0; i < tiles; i++) {
            sum += sample(factory, round * tiles + i + 100, nativePath);
        }
        sink = sum;
        return (System.nanoTime() - start) / 1_000_000.0 / tiles;
    }

    private static float sample(HeightMapTileFactory factory, int index, boolean nativePath) {
        System.setProperty("wp.native.gen", Boolean.toString(nativePath));
        final Tile tile = factory.createTile(index - 256, 8);
        return tile.getHeight(32, 64);
    }
}
