package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.tunnel.TunnelLayer;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.util.Arrays;

/** End-to-end comparison of Dimension.getEdgeHeights with Java and Rust enabled. */
public final class EdgeHeightBenchmark {
    private static volatile long sink;

    private EdgeHeightBenchmark() {
    }

    public static void main(String[] args) {
        final int warmupRounds = (args.length > 0) ? Integer.parseInt(args[0]) : 4;
        final int measuredRounds = (args.length > 1) ? Integer.parseInt(args[1]) : 11;
        final int iterationsPerRound = (args.length > 2) ? Integer.parseInt(args[2]) : 3;
        final int tileColumns = (args.length > 3) ? Integer.parseInt(args[3]) : 2;
        final int tileRows = (args.length > 4) ? Integer.parseInt(args[4]) : 2;
        final float maxDistance = (args.length > 5) ? Float.parseFloat(args[5]) : 12.0f;
        final Fixture fixture = createFixture(tileColumns, tileRows);

        for (int i = 0; i < warmupRounds; i++) {
            run(fixture, false, tileColumns, tileRows, maxDistance);
            run(fixture, true, tileColumns, tileRows, maxDistance);
        }
        final long[] javaTimes = new long[measuredRounds];
        final long[] rustTimes = new long[measuredRounds];
        for (int round = 0; round < measuredRounds; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(fixture, false, iterationsPerRound, tileColumns, tileRows, maxDistance);
                rustTimes[round] = measure(fixture, true, iterationsPerRound, tileColumns, tileRows, maxDistance);
            } else {
                rustTimes[round] = measure(fixture, true, iterationsPerRound, tileColumns, tileRows, maxDistance);
                javaTimes[round] = measure(fixture, false, iterationsPerRound, tileColumns, tileRows, maxDistance);
            }
        }
        final double javaMedian = median(javaTimes) / (double) iterationsPerRound;
        final double rustMedian = median(rustTimes) / (double) iterationsPerRound;
        System.out.printf("Java path: %.3f ms/bake%n", javaMedian / 1_000_000.0);
        System.out.printf("Rust path: %.3f ms/bake%n", rustMedian / 1_000_000.0);
        System.out.printf("speedup:   %.2fx%n", javaMedian / rustMedian);
        System.out.printf("fixture:   %dx%d tiles, radius %.1f%n", tileColumns, tileRows, maxDistance);
        System.out.println("sink:      " + sink);
    }

    private static Fixture createFixture(int tileColumns, int tileRows) {
        final TileFactory terrainFactory = TestData.createTileFactory(62);
        final World2 world = new World2(TestData.PLATFORM, TestData.SEED, terrainFactory);
        final Dimension dimension = world.getDimension(Dimension.Anchor.NORMAL_DETAIL);
        final int floorId = 71;
        final TileFactory floorFactory = TestData.createTileFactory(41);
        final Dimension floor = new Dimension(world, "Benchmark floating floor", TestData.SEED,
                floorFactory, new Dimension.Anchor(Constants.DIM_NORMAL,
                Dimension.Role.FLOATING_FLOOR, false, floorId));
        final TunnelLayer layer = new TunnelLayer("Benchmark floating tunnel",
                TunnelLayer.LayerMode.FLOATING, null, TestData.PLATFORM);
        layer.setFloorDimensionId(floorId);
        for (int tileX = 0; tileX < tileColumns; tileX++) {
            for (int tileY = 0; tileY < tileRows; tileY++) {
                final Tile terrainTile = terrainFactory.createTile(tileX, tileY);
                final Tile floorTile = floorFactory.createTile(tileX, tileY);
                dimension.addTile(terrainTile);
                floor.addTile(floorTile);
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        final int worldX = tileX * Constants.TILE_SIZE + x;
                        final int worldY = tileY * Constants.TILE_SIZE + y;
                        if ((worldX >= 12) && (worldY >= 12)
                                && (worldX < tileColumns * Constants.TILE_SIZE - 12)
                                && (worldY < tileRows * Constants.TILE_SIZE - 12)
                                && !((worldX >= 90) && (worldX < 130)
                                && (worldY >= 90) && (worldY < 130))) {
                            terrainTile.setBitLayerValue(layer, x, y, true);
                        }
                    }
                }
            }
        }
        world.addDimension(floor);
        return new Fixture(dimension, layer);
    }

    private static long measure(Fixture fixture, boolean nativeEnabled, int iterations,
                                int tileColumns, int tileRows, float maxDistance) {
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            run(fixture, nativeEnabled, tileColumns, tileRows, maxDistance);
        }
        return System.nanoTime() - start;
    }

    private static void run(Fixture fixture, boolean nativeEnabled, int tileColumns, int tileRows,
                            float maxDistance) {
        Native.setExportEnabled(nativeEnabled);
        final HeightMap heights = fixture.dimension.getEdgeHeights(fixture.layer, maxDistance);
        long sample = 0;
        for (int y = 0; y < tileRows * Constants.TILE_SIZE; y += 19) {
            for (int x = 0; x < tileColumns * Constants.TILE_SIZE; x += 23) {
                sample += Double.doubleToRawLongBits(heights.getHeight(x, y));
            }
        }
        sink = sample;
    }

    private static long median(long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static final class Fixture {
        private final Dimension dimension;
        private final TunnelLayer layer;

        private Fixture(Dimension dimension, TunnelLayer layer) {
            this.dimension = dimension;
            this.layer = layer;
        }
    }
}
