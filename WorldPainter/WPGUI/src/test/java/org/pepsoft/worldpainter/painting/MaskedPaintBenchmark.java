package org.pepsoft.worldpainter.painting;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.brushes.BrushShape;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;

public final class MaskedPaintBenchmark {
    static Paint paint(String type) {
        return switch (type) {
            case "terrain" -> new TerrainPaint(Terrain.CUSTOM_1);
            case "biome" -> new DiscreteLayerPaint(Biome.INSTANCE, 42);
            case "bit" -> new BitLayerPaint(Frost.INSTANCE);
            case "chunk" -> new BitLayerPaint(Populate.INSTANCE);
            default -> throw new IllegalArgumentException(type);
        };
    }
    static final class SolidBrush extends NibbleLayerPaintParityTest.Brush {
        @Override public float getFullStrength(int x, int y) { return Math.abs(x) <= getRadius() && Math.abs(y) <= getRadius() ? 1f : 0f; }
        @Override public BrushShape getBrushShape() { return BrushShape.SQUARE; }
    }

    public static void main(String[] args) {
        String type = args.length == 0 ? "biome" : args[0];
        int fixed = args.length < 2 ? -1 : args[1].equals("rust") ? 1 : 0;
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean(); memory.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 5; i++) { if (fixed < 0) { run(type, false, memory); run(type, true, memory); } else run(type, fixed == 1, memory); }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (fixed < 0 ? 2 : 1); order++) {
            int mode = fixed < 0 ? (round + order) & 1 : fixed;
            double[] sample = run(type, mode == 1, memory); times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = 0; mode < 2; mode++) {
            if (fixed >= 0 && mode != fixed) continue;
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s %s complete_32_strokes_ms=%.3f heap_allocated_bytes=%.0f tiles=15%n",
                    mode == 0 ? "Java" : "Rust", type, times[mode][3], bytes[mode][3]);
        }
    }

    private static double[] run(String type, boolean nativeMode, ThreadMXBean memory) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode)); Dimension dimension = NibbleLayerPaintParityTest.fixture();
        Paint painter = paint(type); SolidBrush brush = new SolidBrush(); brush.setRadius(127); painter.setBrush(brush);
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        dimension.setEventsInhibited(true);
        try {
            for (int pass = 0; pass < 2; pass++) for (int y = -192; y <= 192; y += 128) for (int x = -192; x <= 192; x += 128) {
                if (pass == 1 && !type.equals("terrain")) painter.remove(dimension, x, y, 1f);
                else painter.apply(dimension, x, y, 1f);
            }
        } finally { dimension.setEventsInhibited(false); }
        return new double[] {(System.nanoTime() - start) / 1_000_000.0, memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
