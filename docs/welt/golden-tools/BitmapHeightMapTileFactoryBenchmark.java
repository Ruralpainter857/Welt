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
        final boolean bicubic = args.length > 2 && "bicubic".equalsIgnoreCase(args[2]);
        final BufferedImage image = createImage();
        final HeightMapTileFactory legacy = factory(image, true, bicubic);
        final HeightMapTileFactory batch = factory(image, false, bicubic);
        final double[] legacySamples = new double[rounds];
        final double[] batchSamples = new double[rounds];
        for (int warmup = 0; warmup < 10; warmup++) {
            sample(legacy, tiles, warmup);
            sample(batch, tiles, warmup);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                legacySamples[round] = sample(legacy, tiles, round);
                batchSamples[round] = sample(batch, tiles, round);
            } else {
                batchSamples[round] = sample(batch, tiles, round);
                legacySamples[round] = sample(legacy, tiles, round);
            }
        }
        Arrays.sort(legacySamples);
        Arrays.sort(batchSamples);
        System.out.printf("scenario=%s tiles=%d rounds=%d legacy_ms_per_tile=%.4f batch_ms_per_tile=%.4f speedup=%.3f sink=%d%n",
                bicubic ? "bitmap-bicubic" : "bitmap", tiles, rounds,
                legacySamples[rounds / 2], batchSamples[rounds / 2],
                legacySamples[rounds / 2] / batchSamples[rounds / 2], sink);
    }

    private static BufferedImage createImage() {
        final BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_USHORT_GRAY);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRaster().setSample(x, y, 0, (x * 397 + y * 101 + (x * y)) & 0xffff);
            }
        }
        return image;
    }

    private static HeightMapTileFactory factory(BufferedImage image, boolean legacy, boolean bicubic) {
        final BitmapHeightMap bitmap = BitmapHeightMap.build().withImage(image).withChannel(0).now();
        final org.pepsoft.worldpainter.HeightMap heightMap = bicubic ? new BicubicHeightMap(bitmap) : bitmap;
        final SimpleTheme theme = SimpleTheme.createDefault(Terrain.GRASS, 0, 256, 62, false, true);
        final SimpleTheme selectedTheme = legacy
                ? new SimpleTheme(theme.getSeed(), theme.getWaterHeight(), theme.getTerrainRanges(),
                        theme.getLayerMap(), 0, 256, false, true) { }
                : theme;
        return new HeightMapTileFactory(0x3141_5926L, heightMap, 0, 256, false, selectedTheme);
    }

    private static double sample(HeightMapTileFactory factory, int tiles, int round) {
        final long start = System.nanoTime();
        int check = 0;
        for (int i = 0; i < tiles; i++) {
            final int tileIndex = (round * tiles + i) & 15;
            final Tile tile = factory.createTile(tileIndex & 3, tileIndex >>> 2);
            check ^= Float.floatToRawIntBits(tile.getHeight(i & 127, (i * 29) & 127));
        }
        sink ^= check;
        return (System.nanoTime() - start) / 1_000_000.0 / tiles;
    }
}
