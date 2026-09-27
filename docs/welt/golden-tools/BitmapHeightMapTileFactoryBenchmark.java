import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.BicubicHeightMap;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Compares per-cell bitmap sampling with the in-bounds raster batch path. */
public final class BitmapHeightMapTileFactoryBenchmark {
    private static volatile int sink;

    private BitmapHeightMapTileFactoryBenchmark() {
    }

    public static void main(String[] args) {
        final int tiles = args.length > 0 ? Integer.parseInt(args[0]) : 48;
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 9;
        final String scenario = args.length > 2 ? args[2].toLowerCase() : "bitmap";
        final boolean bicubic = "bicubic".equals(scenario);
        final boolean edge = "edge".equals(scenario);
        final boolean repeat = "repeat".equals(scenario);
        final int imageWidth = edge ? 515 : repeat ? 96 : 512;
        final int imageHeight = edge ? 513 : repeat ? 73 : 512;
        final BufferedImage image = createImage(imageWidth, imageHeight);
        final HeightMapTileFactory legacy = factory(image, true, bicubic, repeat);
        final HeightMapTileFactory batch = factory(image, false, bicubic, repeat);
        final double[] legacySamples = new double[rounds];
        final double[] batchSamples = new double[rounds];
        for (int warmup = 0; warmup < 10; warmup++) {
            sample(legacy, tiles, warmup, edge);
            sample(batch, tiles, warmup, edge);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                legacySamples[round] = sample(legacy, tiles, round, edge);
                batchSamples[round] = sample(batch, tiles, round, edge);
            } else {
                batchSamples[round] = sample(batch, tiles, round, edge);
                legacySamples[round] = sample(legacy, tiles, round, edge);
            }
        }
        Arrays.sort(legacySamples);
        Arrays.sort(batchSamples);
        System.out.printf("scenario=%s tiles=%d rounds=%d legacy_ms_per_tile=%.4f batch_ms_per_tile=%.4f speedup=%.3f sink=%d%n",
                "bitmap-" + scenario, tiles, rounds,
                legacySamples[rounds / 2], batchSamples[rounds / 2],
                legacySamples[rounds / 2] / batchSamples[rounds / 2], sink);
    }

    private static BufferedImage createImage(int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRaster().setSample(x, y, 0, (x * 397 + y * 101 + (x * y)) & 0xffff);
            }
        }
        return image;
    }

    private static HeightMapTileFactory factory(BufferedImage image, boolean legacy, boolean bicubic, boolean repeat) {
        final BitmapHeightMap bitmap = BitmapHeightMap.build()
                .withImage(image).withChannel(0).withRepeat(repeat).now();
        final org.pepsoft.worldpainter.HeightMap heightMap = bicubic ? new BicubicHeightMap(bitmap) : bitmap;
        final SimpleTheme theme = SimpleTheme.createDefault(Terrain.GRASS, 0, 256, 62, false, true);
        final SimpleTheme selectedTheme = legacy
                ? new SimpleTheme(theme.getSeed(), theme.getWaterHeight(), theme.getTerrainRanges(),
                        theme.getLayerMap(), 0, 256, false, true) { }
                : theme;
        return new HeightMapTileFactory(0x3141_5926L, heightMap, 0, 256, false, selectedTheme);
    }

    private static double sample(HeightMapTileFactory factory, int tiles, int round, boolean edge) {
        final long start = System.nanoTime();
        int check = 0;
        for (int i = 0; i < tiles; i++) {
            final int side = edge ? 5 : 4;
            final int tileIndex = (round * tiles + i) % (side * side);
            final Tile tile = factory.createTile(tileIndex % side, tileIndex / side);
            check ^= Float.floatToRawIntBits(tile.getHeight(i & 127, (i * 29) & 127));
        }
        sink ^= check;
        return (System.nanoTime() - start) / 1_000_000.0 / tiles;
    }
}
