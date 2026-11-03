package org.pepsoft.worldpainter.operations;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.layers.CombinedLayer;
import org.pepsoft.worldpainter.painting.CombinedLayerPaint;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete combined-layer strokes, including history and notifications, excluding asynchronous display redraw. */
public final class LargeLayerStrokeBenchmark {
    static CombinedLayer layer() throws ReflectiveOperationException {
        CombinedLayer layer = new CombinedLayer("Large layer", "Large layer", java.awt.Color.GREEN);
        // Set the fixture identifier before any map insertion so independent JVMs have comparable complete digests.
        var id = org.pepsoft.worldpainter.layers.Layer.class.getDeclaredField("id");
        id.setAccessible(true); id.set(layer, "welt-large-layer-benchmark-v1");
        layer.setTerrain(Terrain.CUSTOM_96); layer.setBiome(200);
        layer.setApplyTerrainAndBiomeOnExport(false);
        return layer;
    }

    static Dimension fixture(int radius, CombinedLayer layer) throws Exception {
        Dimension dimension = LargeMountainStrokeBenchmark.fixture(radius, false);
        dimension.unregisterUndoManager();
        for (Tile tile : dimension.getTiles()) {
            tile.inhibitEvents();
            try {
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) tile.setLayerValue(layer, x, y, (x + y * 3) & 15);
            } finally { tile.releaseEvents(); }
        }
        dimension.registerUndoManager(new org.pepsoft.util.undo.UndoManager(8));
        return dimension;
    }

    private record Result(double millis, long allocation, long hash, long heap, long direct) {}

    static long affectedTiles(Dimension dimension, Brush brush, int cx, int cy, boolean remove) {
        var bounds = brush.getBoundingBox(); long count = 0;
        for (Tile tile : dimension.getTiles()) {
            int x1 = Math.max(tile.getX()*128, cx+bounds.x), x2 = Math.min(tile.getX()*128+127, cx+bounds.x+bounds.width-1);
            int y1 = Math.max(tile.getY()*128, cy+bounds.y), y2 = Math.min(tile.getY()*128+127, cy+bounds.y+bounds.height-1);
            boolean touched = false;
            for (int y=y1; y<=y2 && !touched; y++) for(int x=x1; x<=x2; x++) {
                if ((remove ? brush.getFullStrength(x-cx,y-cy) : brush.getStrength(x-cx,y-cy)) != 0f) {touched=true;break;}
            }
            if(touched) count++;
        }
        return count;
    }

    private static Result run(int radius, CombinedLayer layer, boolean nativeMode, boolean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = fixture(radius, layer);
        CombinedLayerPaint paint = new CombinedLayerPaint(layer);
        var brush = SymmetricBrush.LINEAR_CIRCLE.clone(); brush.setRadius(radius); brush.setLevel(.85f);
        paint.setBrush(brush); paint.setDither(false);
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        if (memory) System.gc();
        long expectedCalls = 0;
        if (nativeMode) for (int tick=0; tick<4; tick++) expectedCalls += affectedTiles(dimension, brush, -3+tick*11, 5-tick*7, false);
        long calls = NibbleBrushAccess.completedNativeCalls();
        long allocated = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int tick = 0; tick < 4; tick++) {
            dimension.armSavePoint(); dimension.setEventsInhibited(true);
            try { paint.apply(dimension, -3 + tick * 11, 5 - tick * 7, .93f); }
            finally { dimension.setEventsInhibited(false); }
        }
        double millis = (System.nanoTime() - start) / 1e6;
        allocated = meter.getThreadAllocatedBytes(thread) - allocated;
        if (NibbleBrushAccess.completedNativeCalls()-calls != expectedCalls) throw new AssertionError("Unexpected native tile coverage");
        long heap = 0, direct = 0;
        if (memory) {
            System.gc(); heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                    .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        }
        long hash = LargeMountainStrokeBenchmark.digest(dimension);
        java.lang.ref.Reference.reachabilityFence(dimension);
        return new Result(millis, allocated, hash, heap, direct);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "java" : args[0];
        boolean paired = mode.equals("compare"), memory = Boolean.getBoolean("welt.benchmark.largeLayerMemory");
        if ((!paired && !mode.equals("java") && !mode.equals("rust")) || (paired && memory)) throw new IllegalArgumentException("Invalid mode");
        int trials = Integer.getInteger("welt.benchmark.largeLayerTrials", 7), radius = 383;
        if (trials < 3) throw new IllegalArgumentException("At least three trials required");
        System.setProperty("welt.native.largeLayerBrush", "true");
        System.setProperty(Native.RENDER_KEY, "false");
        Configuration.setInstance(new Configuration());
        CombinedLayer layer = layer();
        double[] left = new double[trials], right = new double[trials], ratios = new double[trials];
        long expected = 0;
        for (int trial = -5; trial < trials; trial++) {
            Result java = null, rust = null;
            for (int pass = 0; pass < (paired ? 2 : 1); pass++) {
                boolean nativeMode = paired ? ((trial & 1) == pass) : mode.equals("rust");
                Result result = run(radius, layer, nativeMode, memory);
                if (expected != 0 && expected != result.hash) throw new AssertionError("Complete combined-layer result differs");
                expected = result.hash;
                if (nativeMode) rust = result; else java = result;
                if (trial >= 0) System.out.printf(Locale.ROOT, "layer mode=%s trial=%d ms=%.3f allocated=%d hash=%d heap=%d direct=%d%n",
                        nativeMode ? "rust" : "java", trial, result.millis, result.allocation, result.hash, result.heap, result.direct);
            }
            if (trial >= 0) {
                left[trial] = java == null ? 0 : java.millis; right[trial] = rust == null ? 0 : rust.millis;
                ratios[trial] = paired ? java.millis / rust.millis : 0;
            }
        }
        Arrays.sort(left); Arrays.sort(right); Arrays.sort(ratios);
        System.out.printf(Locale.ROOT, "layer_summary java_ms=%.3f rust_ms=%.3f paired_ratio=%.3f memory_instrumented=%s%n",
                left[trials/2], right[trials/2], ratios[trials/2], memory);
    }
}
