package org.pepsoft.worldpainter.painting;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.panels.DefaultFilter;

/** Measures complete filtered terrain strokes, including filter reads and terrain writes. */
public final class FilteredTerrainBenchmark {
    private static volatile int checksum;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("compare")) { compare(); return; }
        boolean rust = args.length > 0 && args[0].equals("rust");
        Setup setup = fixture();
        System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
        System.setProperty("welt.native.filteredTerrain", "true");
        long[] times = new long[9], allocations = new long[9];
        for (int sample = -5; sample < times.length; sample++) {
            Sample result = stroke(setup);
            if (sample >= 0) { times[sample] = result.nanos; allocations[sample] = result.allocated; }
        }
        Arrays.sort(times); Arrays.sort(allocations);
        long direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.printf("filteredTerrain engine=%s fourCompleteLines medianMs=%.3f allocatedBytes=%d directBytes=%d nativeCalls=%d checksum=%d%n",
                rust ? "rust" : "java", times[4] / 1_000_000.0, allocations[4], direct,
                FilteredTerrainAccess.completedTransactions(), checksum);
        if (rust && FilteredTerrainAccess.completedTransactions() == 0) throw new AssertionError("Native path did not execute");
    }

    private record Setup(Dimension dimension, DimensionPainter painter) { }
    private record Sample(long nanos, long allocated) { }
    private static Setup fixture() {
        System.setProperty(Native.GEN_KEY, "false");
        Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        World2 world = new World2(platform, platform.minZ, platform.standardMaxHeight);
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS,
                platform.minZ, platform.standardMaxHeight, 62, 62, false, false);
        Dimension dimension = new Dimension(world, "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        for (int tx = -2; tx < 2; tx++) for (int ty = -2; ty < 2; ty++) {
            Tile tile = factory.createTile(tx, ty);
            tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                tile.setHeight(x, y, 58 + ((x * 17 + y * 3) & 15) / 2f);
            tile.releaseEvents();
            dimension.addTile(tile);
        }
        TerrainPaint paint = new TerrainPaint(Terrain.SAND);
        paint.setDither(false);
        paint.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        paint.getBrush().setRadius(96);
        paint.setFilter(new DefaultFilter(dimension, false, false, 60, 64, true,
                false, null, false, null, 20, false));
        DimensionPainter painter = new DimensionPainter();
        painter.setPaint(paint);
        return new Setup(dimension, painter);
    }
    private static Sample stroke(Setup setup) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Dimension dimension = setup.dimension;
        DimensionPainter painter = setup.painter;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            dimension.setEventsInhibited(true);
            try {
                for (int line = 0; line < 4; line++)
                    painter.drawLine(dimension, -128, -96 + line * 64, 128, -96 + line * 64, false);
            } finally {
                dimension.setEventsInhibited(false);
            }
            long elapsed = System.nanoTime() - start;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
            checksum ^= dimension.getTerrainAt(0, 0).ordinal();
        return new Sample(elapsed, allocated);
    }
    private static void compare() {
        Setup java = fixture(), rust = fixture();
        System.setProperty("welt.native.filteredTerrain", "true");
        double[] javaTimes = new double[9], rustTimes = new double[9], ratios = new double[9];
        long[] javaAlloc = new long[9], rustAlloc = new long[9];
        for (int sample = -5; sample < 9; sample++) {
            Sample j = null, r = null;
            for (int pass = 0; pass < 2; pass++) {
                boolean nativePass = ((sample + pass) & 1) != 0;
                System.setProperty(Native.GEN_KEY, Boolean.toString(nativePass));
                if (nativePass) r = stroke(rust); else j = stroke(java);
            }
            if (sample >= 0) {
                javaTimes[sample] = j.nanos / 1e6; rustTimes[sample] = r.nanos / 1e6;
                ratios[sample] = (double) j.nanos / r.nanos;
                javaAlloc[sample] = j.allocated; rustAlloc[sample] = r.allocated;
                System.out.printf("pair=%d javaMs=%.3f rustMs=%.3f ratio=%.3f%n", sample, javaTimes[sample], rustTimes[sample], ratios[sample]);
            }
        }
        Arrays.sort(javaTimes); Arrays.sort(rustTimes); Arrays.sort(ratios); Arrays.sort(javaAlloc); Arrays.sort(rustAlloc);
        System.out.printf("paired medianJavaMs=%.3f medianRustMs=%.3f medianRatio=%.3f minRatio=%.3f maxRatio=%.3f javaAllocated=%d rustAllocated=%d nativeCalls=%d%n",
                javaTimes[4], rustTimes[4], ratios[4], ratios[0], ratios[8], javaAlloc[4], rustAlloc[4], FilteredTerrainAccess.completedTransactions());
        if (FilteredTerrainAccess.completedTransactions() == 0) throw new AssertionError("Native path did not execute");
    }
}
