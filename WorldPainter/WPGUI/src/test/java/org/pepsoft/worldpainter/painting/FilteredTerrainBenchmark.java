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
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] times = new long[9], allocations = new long[9];
        for (int sample = -5; sample < times.length; sample++) {
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
            if (sample >= 0) { times[sample] = elapsed; allocations[sample] = allocated; }
            checksum ^= dimension.getTerrainAt(0, 0).ordinal();
        }
        Arrays.sort(times); Arrays.sort(allocations);
        System.out.printf("filteredTerrain fourCompleteLines medianMs=%.3f allocatedBytes=%d checksum=%d%n",
                times[4] / 1_000_000.0, allocations[4], checksum);
    }
}
