package org.pepsoft.worldpainter.brushes;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Measures complete rotated-brush preparation and subsequent strength reads. */
public final class RotatedBrushBenchmark {
    private static volatile Object held;
    private static volatile long checksum;
    public static void main(String[] args) throws Exception {
        int radius = Integer.getInteger("welt.benchmark.rotatedRadius", 255);
        int warmups = Integer.getInteger("welt.benchmark.rotatedWarmups", 20);
        if (radius < 1 || radius > 1023 || warmups < 5 || warmups > 100) throw new IllegalArgumentException();
        String scenario = System.getProperty("welt.benchmark.rotatedMode", "compare");
        if (!java.util.Set.of("compare", "java", "rust").contains(scenario)) throw new IllegalArgumentException();
        if (Boolean.getBoolean("welt.benchmark.preloadSlices")
                || (!scenario.equals("java") && Boolean.getBoolean("welt.test.requireBrushRaster"))) {
            System.setProperty("welt.native.brushRaster", "true");
            if (!org.pepsoft.worldpainter.nativeapi.NativeSlices.expandBrushRaster(new float[]{0.5f}, 1, 0, new byte[1])) throw new AssertionError("Release JNI required");
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        SymmetricBrush source = SymmetricBrush.COSINE_SQUARE.clone(); source.setRadius(radius); source.setLevel(0.75f);
        double[][] times = new double[2][9]; long[][] bytes = new long[2][9]; long[] hashes = new long[2];
        for (int trial = -warmups; trial < 9; trial++) for (int turn = 0; turn < 2; turn++) {
            int mode = (trial & 1) ^ turn;
            if (!scenario.equals("compare") && mode != (scenario.equals("java") ? 0 : 1)) continue;
            System.setProperty("welt.native.brushRaster", Boolean.toString(mode == 1));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime(), sum = 1;
            Brush[] rotated = new Brush[4]; int index = 0;
            for (int angle : new int[]{17, 73, 133, 271}) {
                Brush brush = RotatedBrush.rotate(source.clone(), angle); rotated[index++] = brush;
                int r = brush.getEffectiveRadius();
                for (int x = -r; x <= r; x += 16) for (int y = -r; y <= r; y += 16)
                    sum = 31 * sum + Float.floatToIntBits(brush.getStrength(x, y));
            }
            long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            held = rotated; checksum = sum; hashes[mode] = sum;
            if (trial >= 0) { times[mode][trial] = elapsed / 1e6; bytes[mode][trial] = allocated; }
        }
        if (!scenario.equals("compare")) {
            int mode = scenario.equals("java") ? 0 : 1;
            System.gc(); Thread.sleep(150); Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "rotatedMemory mode=%s radius=%d medianMs=%.4f allocatedBytes=%d heapBytes=%d checksum=%d%n",
                    scenario, radius, times[mode][4], bytes[mode][4], ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), checksum);
            return;
        }
        if (hashes[0] != hashes[1]) throw new AssertionError("Rotation outputs differ");
        double[] ratios = new double[9]; for (int i = 0; i < 9; i++) ratios[i] = times[0][i] / times[1][i];
        Arrays.sort(ratios); for (int mode = 0; mode < 2; mode++) { Arrays.sort(times[mode]); Arrays.sort(bytes[mode]); }
        System.out.printf(Locale.ROOT,"rotatedBrush radius=%d javaMs=%.4f rustMs=%.4f pairedRatio=%.3f range=%.3f..%.3f allocatedBytes=%d/%d checksum=%d%n",
                radius, times[0][4], times[1][4], ratios[4], ratios[0], ratios[8], bytes[0][4], bytes[1][4], checksum);
    }
}