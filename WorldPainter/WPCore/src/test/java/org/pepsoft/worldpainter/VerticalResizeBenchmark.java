package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.util.WorldUtils;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class VerticalResizeBenchmark {
    static Dimension fixture() {
        Dimension dimension = new Dimension(new World2(TestData.PLATFORM, -64, 320), "Surface", 0,
                new HeightMapTileFactory(0, new ConstantHeightMap(62), -64, 320, false,
                        SimpleTheme.createSingleTerrain(Terrain.GRASS, -64, 320, 62)), Dimension.Anchor.NORMAL_DETAIL);
        for (int n = 0; n < 64; n++) {
            Tile tile = new Tile(n % 8 - 4, n / 8 - 4, -64, 320);
            tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setHeight(x, y, -32.125f + (x * 3 + y * 7 + n) % 330);
                tile.setWaterLevel(x, y, 62 + (x + y) % 5);
            }
            tile.releaseEvents();
            dimension.addTile(tile);
        }
        return dimension;
    }

    public static void main(String[] args) throws Exception {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        boolean baseline = args.length > 0 && args[0].equals("java");
        for (int i = 0; i < 5; i++) {
            run(false, memory);
            if (!baseline) run(true, memory);
        }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (baseline ? 1 : 2); order++) {
            int mode = baseline ? 0 : (round + order) & 1;
            double[] sample = run(mode == 1, memory);
            times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = 0; mode < (baseline ? 1 : 2); mode++) {
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s full_dimension_resize_ms=%.3f heap_allocated_bytes=%.0f tiles=64%n",
                    mode == 0 ? "Java" : "Rust", times[mode][3], bytes[mode][3]);
        }
    }

    private static double[] run(boolean nativeMode, ThreadMXBean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = fixture();
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        WorldUtils.resizeDimension(dimension, -128, 512, HeightTransform.get(125, 10), true, null);
        return new double[] {(System.nanoTime() - start) / 1_000_000.0,
                memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
