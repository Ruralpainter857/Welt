import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.TileSnapshot;
import org.pepsoft.worldpainter.nativeapi.TileView;

import java.util.Arrays;

/** Standalone comparison of getter-based TileView capture and snapshot bulk capture. */
public final class TileViewSnapshotBenchmark {
    private static volatile int sink;

    private TileViewSnapshotBenchmark() {
    }

    public static void main(String[] args) {
        final int warmupIterations = (args.length > 0) ? Integer.parseInt(args[0]) : 12;
        final int measuredRounds = (args.length > 1) ? Integer.parseInt(args[1]) : 21;
        final int iterationsPerRound = (args.length > 2) ? Integer.parseInt(args[2]) : 8;
        final Tile tile = new Tile(0, 0, 0, 256);
        final UndoManager undoManager = new UndoManager(4);
        tile.register(undoManager);
        final TileSnapshot snapshot = new TileSnapshot(tile, undoManager.getSnapshot());

        for (int i = 0; i < warmupIterations; i++) {
            consume(TileView.of(tile));
            consume(TileView.of(snapshot));
        }

        final long[] getterTimes = new long[measuredRounds];
        final long[] snapshotTimes = new long[measuredRounds];
        for (int round = 0; round < measuredRounds; round++) {
            if ((round & 1) == 0) {
                getterTimes[round] = measure(tile, iterationsPerRound);
                snapshotTimes[round] = measure(snapshot, iterationsPerRound);
            } else {
                snapshotTimes[round] = measure(snapshot, iterationsPerRound);
                getterTimes[round] = measure(tile, iterationsPerRound);
            }
        }

        final double getterMedian = median(getterTimes) / (double) iterationsPerRound;
        final double snapshotMedian = median(snapshotTimes) / (double) iterationsPerRound;
        System.out.printf("getter path:   %.3f ms/capture%n", getterMedian / 1_000_000.0);
        System.out.printf("snapshot path: %.3f ms/capture%n", snapshotMedian / 1_000_000.0);
        System.out.printf("speedup:       %.2fx%n", getterMedian / snapshotMedian);
        System.out.println("sink:          " + sink);
    }

    private static long measure(Tile tile, int iterations) {
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            consume(TileView.of(tile));
        }
        return System.nanoTime() - start;
    }

    private static long measure(TileSnapshot tile, int iterations) {
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            consume(TileView.of(tile));
        }
        return System.nanoTime() - start;
    }

    private static void consume(TileView view) {
        int checksum = 0;
        for (int y = 0; y < TileView.TILE_SIZE; y += 13) {
            for (int x = 0; x < TileView.TILE_SIZE; x += 11) {
                checksum += view.getRawHeight(x, y);
                checksum += view.getTerrainOrdinal(x, y);
                checksum += view.getRawWaterLevel(x, y);
            }
        }
        sink = checksum;
    }

    private static long median(long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
