package org.pepsoft.worldpainter.brushes;

import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/** Complete brush interactions; the original implementation is loaded separately. */
public final class BrushCacheBenchmark {
    static final String[] NAMES = {"CONSTANT_CIRCLE", "CONSTANT_SQUARE", "LINEAR_CIRCLE", "LINEAR_SQUARE",
            "COSINE_CIRCLE", "COSINE_SQUARE", "PLATEAU_CIRCLE", "PLATEAU_SQUARE",
            "SPIKE_CIRCLE", "SPIKE_SQUARE", "DOME_CIRCLE", "DOME_SQUARE"};
    private static volatile Object held;
    private static volatile long checksum;

    static Class<?> original() throws Exception {
        URLClassLoader loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("welt.benchmark.originalBrushes")).toUri().toURL()}, BrushCacheBenchmark.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.pepsoft.worldpainter.brushes.SymmetricBrush")
                        || name.startsWith("org.pepsoft.worldpainter.brushes.RadialBrush")) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        };
        return loader.loadClass(SymmetricBrush.class.getName());
    }

    static Brush[] brushes(Class<?> type, int radius) throws Exception {
        Brush[] result = new Brush[NAMES.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = ((Brush) type.getField(NAMES[i]).get(null)).clone();
            result[i].setRadius(radius);
        }
        return result;
    }

    private static void interaction(Brush[] brushes, String operation, int radius) {
        if (operation.equals("clone")) {
            Brush[] copies = new Brush[96];
            for (int i = 0; i < copies.length; i++) copies[i] = brushes[i % brushes.length].clone();
            long sum = 0;
            for (Brush copy : copies) {
                int r = copy.getRadius();
                sum = 31 * sum + Float.floatToIntBits(copy.getStrength(r / 2, r / 3));
                sum = 31 * sum + Float.floatToIntBits(copy.getFullStrength(r / 2, r / 3));
            }
            checksum = sum;
            held = copies;
        } else {
            long sum = 0;
            for (int turn = 0; turn < 8; turn++) for (Brush brush : brushes) {
                if (operation.equals("level")) brush.setLevel((turn & 1) == 0 ? 0.375f : 0.875f);
                else if (operation.equals("radius")) brush.setRadius(radius + (turn & 1));
                else throw new IllegalArgumentException("Unknown operation");
                int r = brush.getRadius();
                for (int x = -r; x <= r; x += 32) for (int y = -r; y <= r; y += 32)
                    sum = 31 * sum + Float.floatToIntBits(brush.getStrength(x, y));
            }
            checksum = sum;
            held = brushes;
        }
    }

    public static void main(String[] args) throws Exception {
        int radius = Integer.getInteger("welt.benchmark.brushRadius", 255);
        if (radius < 0 || radius > 1023) throw new IllegalArgumentException("Invalid radius");
        String operation = System.getProperty("welt.benchmark.brushOperation", "level");
        String mode = System.getProperty("welt.benchmark.brushMode", "compare");
        if (!java.util.Set.of("level", "radius", "clone").contains(operation)
                || !java.util.Set.of("compare", "scaling", "original", "current").contains(mode)) throw new IllegalArgumentException("Invalid brush scenario");
        boolean nativeScale = Boolean.getBoolean("welt.native.brushCache");
        Class<?> original = original();
        Class<?>[] types = mode.equals("scaling") ? new Class<?>[]{SymmetricBrush.class, SymmetricBrush.class} : new Class<?>[]{original, SymmetricBrush.class};
        boolean paired = mode.equals("compare") || mode.equals("scaling");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        int warmups = Integer.getInteger("welt.benchmark.brushWarmups", 12);
        if (warmups < 5 || warmups > 100) throw new IllegalArgumentException("Invalid warmups");
        long[] hashes = new long[2];
        double[][] times = new double[2][9];
        long[][] allocation = new long[2][9];
        Brush[][] input = new Brush[2][];
        for (int i = 0; i < 2; i++) if (paired || i == (mode.equals("original") ? 0 : 1)) input[i] = brushes(types[i], radius);
        if (operation.equals("clone")) for (Brush[] group : input) if (group != null) for (Brush b : group) b.setLevel(0.875f);
        for (int trial = -warmups; trial < 9; trial++) for (int turn = 0; turn < 2; turn++) {
            int modeIndex = (trial & 1) ^ turn;
            if (!paired && modeIndex != (mode.equals("original") ? 0 : 1)) continue;
            if (mode.equals("scaling")) System.setProperty("welt.native.brushCache", Boolean.toString(modeIndex == 1));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            interaction(input[modeIndex], operation, radius);
            long elapsed = System.nanoTime() - start;
            long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            hashes[modeIndex] = checksum;
            if (trial >= 0) { times[modeIndex][trial] = elapsed / 1e6; allocation[modeIndex][trial] = bytes; }
        }
        if (paired) {
            if (hashes[0] != hashes[1]) throw new AssertionError("Brush interaction differs");
            double[] ratios = new double[9];
            for (int i = 0; i < 9; i++) ratios[i] = times[0][i] / times[1][i];
            Arrays.sort(ratios);
            for (int i = 0; i < 2; i++) { Arrays.sort(times[i]); Arrays.sort(allocation[i]); }
            System.out.printf(Locale.ROOT, "brush operation=%s radius=%d nativeScale=%s referenceMs=%.4f currentMs=%.4f pairedRatio=%.3f range=%.3f..%.3f allocatedBytes=%d/%d checksum=%d%n",
                    operation, radius, mode.equals("scaling") ? "pairedJavaRust" : nativeScale, times[0][4], times[1][4], ratios[4], ratios[0], ratios[8], allocation[0][4], allocation[1][4], checksum);
        } else {
            int i = mode.equals("original") ? 0 : 1;
            // Drop the unused fixture so both standalone modes retain the same logical brushes.
            input[1 - i] = null;
            System.gc(); Thread.sleep(150);
            Arrays.sort(times[i]); Arrays.sort(allocation[i]);
            System.out.printf(Locale.ROOT, "brushMemory mode=%s operation=%s radius=%d medianMs=%.4f allocatedBytes=%d heapBytes=%d checksum=%d%n",
                    mode, operation, radius, times[i][4], allocation[i][4], ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), checksum);
        }
    }
}