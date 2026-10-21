package org.pepsoft.worldpainter.painting;

import java.awt.Font;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Complete text operations, including font rasterization, pixel reads and notifications. */
public final class TextPaintBenchmark {
    static final String TEXT = "Welt Rust 2026\nTerrain + Biomes\nWater and snow";
    static Paint paint(String type) {
        Paint paint = type.equals("nibble") ? new NibbleLayerPaint(Resources.INSTANCE) : MaskedPaintBenchmark.paint(type);
        var brush = org.pepsoft.worldpainter.brushes.SymmetricBrush.CONSTANT_SQUARE.clone(); brush.setLevel(.63f); paint.setBrush(brush); return paint;
    }
    static int draw(Dimension dimension, Paint paint, int count) {
        int calls = 0;
        DimensionPainter painter = new DimensionPainter(); painter.setPaint(paint); painter.setFont(new Font("Dialog", Font.BOLD, 48));
        for (int action = 0; action < count; action++) {
            int angle = action & 3;
            painter.setTextAngle(angle); painter.setUndo(action >= 4 && action < 8 && !(paint instanceof TerrainPaint)
                    && !(paint instanceof CombinedLayerPaint));
            int x = angle < 2 ? -220 : 220, y = angle == 0 || angle == 3 ? -220 : 220;
            dimension.setEventsInhibited(true);
            try {painter.drawText(dimension, x, y, TEXT);calls += painter.getLastNativeTextCalls();} finally {dimension.setEventsInhibited(false);}
        }
        return calls;
    }
    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust"); String type = args.length > 1 ? args[1] : "combined";
        String old = System.getProperty(Native.GEN_KEY); NativeLoader.areSlicesAvailable();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true); double[] times = new double[7]; long[] bytes = new long[7]; long rss = 0; int calls = 0;
        try {
            for (int trial = -5; trial < 7; trial++) {
                System.setProperty(Native.GEN_KEY, "false"); Dimension dimension = NibbleLayerPaintParityTest.fixture(); Paint paint = paint(type);
                System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
                calls = draw(dimension, paint, 12);
                double ms = (System.nanoTime()-start)/1e6; long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
                rss = Math.max(rss, NativeSlices.currentProcessResidentBytes());
                if (trial >= 0) {times[trial] = ms; bytes[trial] = allocated;}
            }
            Arrays.sort(times); Arrays.sort(bytes);
            System.out.printf(Locale.ROOT, "%s text_paint=%s complete_12_draws_ms=%.3f heap_allocated_bytes=%d sampled_peak_rss_bytes=%d jni_calls=%d%n",
                    rust ? "Rust" : "Java", type, times[3], bytes[3], rss, calls);
        } finally {if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old);}
    }
}
