package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;

public final class RiverRegionBenchmark {
    static float[] slopes(float[] forces) {
        float[] values = new float[forces.length];
        for (int i = 0; i < forces.length; i++) if (forces[i] > 0 && forces[i] <= .25f)
            values[i] = (float) (Math.tan(-forces[i] * (2 * Math.PI) + Math.PI / 2) / (2 * Math.PI));
        return values;
    }
    static int scalar(Dimension d, int ox, int oy, int side, float[] forces, float[] slopes, int previous, float depth, boolean lava) {
        int level = Integer.MAX_VALUE;
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            int i = x * side + y; if (forces[i] > .25f) continue;
            int height = d.getIntHeightAt(ox + x, oy + y);
            if (d.getWaterLevelAt(ox + x, oy + y) < height && height < level
                    && (x > 0 && forces[i - side] > .25f || x + 1 < side && forces[i + side] > .25f
                    || y > 0 && forces[i - 1] > .25f || y + 1 < side && forces[i + 1] > .25f)) level = height;
        }
        level = Math.min(level, previous);
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            int i = x * side + y; float strength = forces[i];
            if (strength > .25f) {
                float height = level - strength / .75f * depth;
                if (d.getHeightAt(ox + x, oy + y) > height) d.setHeightAt(ox + x, oy + y, height);
                d.setWaterLevelAt(ox + x, oy + y, level); d.setBitLayerValueAt(FloodWithLava.INSTANCE, ox + x, oy + y, lava);
                if (!lava) d.setTerrainAt(ox + x, oy + y, Terrain.BEACHES);
            } else if (strength > 0) {
                float height = level + slopes[i];
                if (d.getHeightAt(ox + x, oy + y) > height) d.setHeightAt(ox + x, oy + y, height);
                if (!lava && height - level < 2) d.setTerrainAt(ox + x, oy + y, Terrain.BEACHES);
            }
        }
        return level;
    }
    static final class Legacy {
        final float[] heights; final int[] terrains, waters; final byte[] modified, flooded, beaches; final int[] level = new int[1];
        Legacy(int area) { heights = new float[area]; terrains = new int[area]; waters = new int[area]; modified = new byte[area]; flooded = new byte[area]; beaches = new byte[area]; }
        int apply(Dimension d, float[] forces, float[] slopes, int previous, boolean lava) {
            for (int x = 0; x < 255; x++) for (int y = 0; y < 255; y++) {
                int i = x * 255 + y; heights[i] = d.getHeightAt(x - 127, y - 127);
                terrains[i] = d.getIntHeightAt(x - 127, y - 127); waters[i] = d.getWaterLevelAt(x - 127, y - 127);
            }
            if (!org.pepsoft.worldpainter.nativeapi.NativeSlices.applyRiverPaint(127, previous, 5f, lava, heights, terrains, waters,
                    forces, slopes, modified, flooded, beaches, level)) throw new AssertionError("Legacy JNI unavailable");
            for (int x = 0; x < 255; x++) for (int y = 0; y < 255; y++) {
                int i = x * 255 + y;
                if (modified[i] != 0) d.setHeightAt(x - 127, y - 127, heights[i]);
                if (flooded[i] != 0) { d.setWaterLevelAt(x - 127, y - 127, level[0]); d.setBitLayerValueAt(FloodWithLava.INSTANCE, x - 127, y - 127, lava); }
                if (beaches[i] != 0) d.setTerrainAt(x - 127, y - 127, Terrain.BEACHES);
            }
            return level[0];
        }
    }
    public static void main(String[] args) throws Exception {
        System.setProperty(Native.GEN_KEY, "true");
        boolean rust = args.length > 0 && args[0].equals("rust");
        boolean legacy = args.length > 0 && args[0].equals("legacy");
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean(); long allocated = 0;
        float[] forces = MountainBrushBenchmark.forces(127), slopes = slopes(forces); double[] times = new double[7];
        for (int trial = -5; trial < 7; trial++) {
            Dimension d = ErosionRegionBenchmark.fixture(); d.setEventsInhibited(true); int previous = 52;
            Legacy baseline = legacy ? new Legacy(forces.length) : null;
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 16; stroke++) {
                if (rust) { Integer result = RiverAccess.tryApply(d, -127, -127, 255, forces, slopes, previous, 5f, (stroke & 1) != 0); if (result == null) throw new AssertionError("JNI unavailable"); previous = result; }
                else if (legacy) previous = baseline.apply(d, forces, slopes, previous, (stroke & 1) != 0);
                else previous = scalar(d, -127, -127, 255, forces, slopes, previous, 5f, (stroke & 1) != 0);
            }
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - start) / 1e6;
            d.setEventsInhibited(false);
        }
        java.util.Arrays.sort(times); System.out.printf("%s river_16_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : legacy ? "Legacy Rust" : "Java", times[3], allocated);
    }
}
