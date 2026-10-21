package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

/** Mesure le vrai remplissage de relief, préparation et notifications comprises. */
public final class HeightFloodBenchmark {
    static void fill(Dimension d, int x, int y, boolean rust) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(rust)); d.setEventsInhibited(true);
        try {
            DimensionPainter p = new DimensionPainter();
            if (!p.fill(d, x, y, DimensionPainter.AdditionalFillAction.NONE, null)) throw new AssertionError("Bounds reached");
        } finally { d.setEventsInhibited(false); }
    }
    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust");
        int side = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        NativeLoader.areSlicesAvailable();
        double[] times = new double[7]; long allocated = 0;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int trial = -5; trial < 7; trial++) {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = FluidFloodBenchmark.fixture(side);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), started = System.nanoTime();
            for (int i = 0; i < 12; i++) fill(d, -64, -64, rust);
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - started) / 1e6;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s height_fill_12_clicks_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
