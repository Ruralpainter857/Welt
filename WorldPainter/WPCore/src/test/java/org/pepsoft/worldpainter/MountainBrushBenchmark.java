package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.pepsoft.worldpainter.Constants.MEDIUM_BLOBS;

public final class MountainBrushBenchmark {
    static final PerlinNoise NOISE = new PerlinNoise(67);
    static float target(int x, int y, float strength, int min, int range, float peak, float factor, boolean inverse) {
        float variation = (0.5f - Math.abs(strength - 0.5f)) / 5;
        strength += NOISE.getPerlinNoise(x / MEDIUM_BLOBS, y / MEDIUM_BLOBS) * variation * strength;
        if (strength < 0) strength = 0; else if (strength > 1) strength = 1;
        return (inverse ? Math.max(range - (range - peak) * factor * strength, 0) : Math.min(peak * factor * strength, range)) + min;
    }
    static void scalar(Dimension d, int ox, int oy, int side, float[] forces, float peak, float factor, boolean inverse) {
        int min = d.getMinHeight(), range = d.getMaxHeight() - 1 - min;
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            float current = d.getHeightAt(ox + x, oy + y);
            float target = target(ox + x, oy + y, forces[x * side + y], min, range, peak, factor, inverse);
            if (inverse ? target < current : target > current) d.setHeightAt(ox + x, oy + y, target);
        }
    }
    static float[] forces(int radius) {
        int side = radius * 2 + 1; float[] forces = new float[side * side];
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) forces[x * side + y] = Math.max(0, 1f - Math.max(Math.abs(x - radius), Math.abs(y - radius)) / (float) radius);
        return forces;
    }
    public static void main(String[] args) throws Exception {
        boolean rust = args.length > 0 && args[0].equals("rust");
        boolean legacy = args.length > 0 && args[0].equals("legacy"); System.setProperty(Native.GEN_KEY, "true");
        float[] forces = forces(127); double[] times = new double[7]; long allocated = 0;
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int trial = -5; trial < 7; trial++) {
            Dimension d = ErosionRegionBenchmark.fixture(); d.setEventsInhibited(true);
            float[] heights = legacy ? new float[forces.length] : null; byte[] modified = legacy ? new byte[forces.length] : null;
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 16; stroke++) {
                boolean inverse = (stroke & 1) != 0; float peak = inverse ? 64 : 220;
                if (rust) { if (!MountainAccess.tryApply(d, -127, -127, 255, forces, peak, 1.25f, inverse)) throw new AssertionError("Native path unavailable"); }
                else if (legacy) {
                    for (int x = 0; x < 255; x++) for (int y = 0; y < 255; y++) heights[x * 255 + y] = d.getHeightAt(x - 127, y - 127);
                    if (!org.pepsoft.worldpainter.nativeapi.NativeSlices.applyRaiseMountain(-127, -127, 255, 255,
                            d.getMinHeight(), d.getMaxHeight() - 1 - d.getMinHeight(), peak, 1.25f, inverse, MEDIUM_BLOBS, 67L, heights, forces, modified)) throw new AssertionError("Legacy JNI unavailable");
                    for (int x = 0; x < 255; x++) for (int y = 0; y < 255; y++) if (modified[x * 255 + y] != 0) d.setHeightAt(x - 127, y - 127, heights[x * 255 + y]);
                }
                else scalar(d, -127, -127, 255, forces, peak, 1.25f, inverse);
            }
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - start) / 1e6;
            d.setEventsInhibited(false);
        }
        java.util.Arrays.sort(times); System.out.printf("%s mountain_16_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : legacy ? "Legacy Rust" : "Java", times[3], allocated);
    }
}
