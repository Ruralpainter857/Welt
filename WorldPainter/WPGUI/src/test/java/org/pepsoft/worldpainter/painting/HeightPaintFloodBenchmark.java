package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

/** Mesure le relief et la peinture dans la même action utilisateur, notifications incluses. */
public final class HeightPaintFloodBenchmark {
    static void fill(Dimension d, int x, int y, boolean rust, Paint paint) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(rust)); d.setEventsInhibited(true);
        try {
            DimensionPainter p = new DimensionPainter(); p.setPaint(paint);
            if (!p.fill(d, x, y, DimensionPainter.AdditionalFillAction.APPLY_PAINT, null)) throw new AssertionError("Bounds reached");
        } finally { d.setEventsInhibited(false); }
    }
    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust");
        String type = args.length > 1 ? args[1] : "terrain";
        NativeLoader.areSlicesAvailable(); double[] times = new double[7]; long allocated = 0;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        Paint[] paints = {PaintFloodBenchmark.paint(type, 0), PaintFloodBenchmark.paint(type, 1)};
        for (int trial = -5; trial < 7; trial++) {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = FluidFloodBenchmark.fixture(4);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), started = System.nanoTime();
            for (int i = 0; i < 12; i++) fill(d, -64, -64, rust, paints[i & 1]);
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - started) / 1e6;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s %s height_paint_12_clicks_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", type, times[3], allocated);
    }
}
