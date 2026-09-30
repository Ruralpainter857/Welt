package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.River;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class AutoBiomeBenchmark {
    static Dimension fixture() {
        Dimension dimension = VerticalResizeBenchmark.fixture();
        for (Tile tile : dimension.getTiles()) {
            tile.inhibitEvents();
            for (int y = 0; y < 128; y += 3) for (int x = 0; x < 128; x += 3) {
                tile.setBitLayerValue(Frost.INSTANCE, x, y, (x + y) % 2 == 0);
                tile.setBitLayerValue(River.INSTANCE, x, y, (x + y) % 5 == 0);
                if ((x + y) % 7 == 0) tile.setLayerValue(Biome.INSTANCE, x, y, 2);
            }
            tile.releaseEvents();
        }
        return dimension;
    }

    static void apply(Dimension dimension) throws Exception {
        dimension.visitTilesForEditing().andDo(tile -> {
            dimension.makeAutoBiomesPermanent(tile);
        }, null);
    }

    public static void main(String[] args) throws Exception {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        boolean baseline = args.length > 0;
        for (int i = 0; i < 5; i++) { run(false, memory); if (!baseline) run(true, memory); }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (baseline ? 1 : 2); order++) {
            int mode = baseline ? 0 : (round + order) & 1;
            double[] sample = run(mode == 1, memory); times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = 0; mode < (baseline ? 1 : 2); mode++) {
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s full_biome_bake_ms=%.3f heap_allocated_bytes=%.0f tiles=64%n",
                    mode == 0 ? "Java" : "Rust", times[mode][3], bytes[mode][3]);
        }
    }

    private static double[] run(boolean nativeMode, ThreadMXBean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = fixture();
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        apply(dimension);
        return new double[] {(System.nanoTime() - start) / 1_000_000.0,
                memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
