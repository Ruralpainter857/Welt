package org.pepsoft.worldpainter.selection;

import java.awt.Rectangle;
import java.util.Random;
import java.lang.management.ManagementFactory;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.*;
import org.pepsoft.worldpainter.nativeapi.Native;

public final class SelectionBrushBenchmark {
    static Dimension fixture() {
        Dimension d = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0 || x == 1 && y == 1) continue;
            d.addTile(new Tile(x, y, d.getMinHeight(), d.getMaxHeight()));
        }
        for (Tile t : d.getTiles()) {
            t.setBitLayerValue(SelectionChunk.INSTANCE, 16, 32, true);
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                if ((x * 7 + y) % 19 == 0) t.setBitLayerValue(SelectionBlock.INSTANCE, x, y, true);
        }
        return d;
    }
    public static void main(String[] args) throws Exception {
        boolean rust = args.length > 0 && args[0].equals("rust"); System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
        System.setProperty("wp.native.gen.selectionCompact", "true");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Brush brush = SymmetricBrush.LINEAR_SQUARE.clone(); brush.setRadius(127); brush.setLevel(0.7f);
        brush = RotatedBrush.rotate(brush, 17);
        double[] times = new double[7]; long allocated = 0;
        for (int trial = -5; trial < 7; trial++) {
            Dimension d = fixture(); Random random = new Random(781); SelectionHelper helper = new SelectionHelper(d, random::nextDouble);
            d.setEventsInhibited(true);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 16; stroke++) {
                if ((stroke & 1) == 0) helper.addToSelection(64, 64, brush, null, 0.65f, null);
                else helper.removeFromSelection(64, 64, brush, null, 0.65f, null);
            }
            double elapsed = (System.nanoTime() - start) / 1e6;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            d.setEventsInhibited(false); if (trial >= 0) times[trial] = elapsed;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s selection_16_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
