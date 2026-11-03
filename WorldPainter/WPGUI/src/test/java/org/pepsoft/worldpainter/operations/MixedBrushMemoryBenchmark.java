package org.pepsoft.worldpainter.operations;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.RiverAccess;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete alternating mountain/river edits, including undo preparation; display redraw is excluded. */
public final class MixedBrushMemoryBenchmark {
    public static void main(String[] args) throws Exception {
        System.setProperty(Native.GEN_KEY, "true");
        System.setProperty(Native.RENDER_KEY, "false");
        System.setProperty("welt.native.largeHeightBrush", "true");
        System.setProperty("welt.native.largeRiver", "true");
        Configuration.setInstance(new Configuration());
        var view = new BrushPipelineTestView();
        var mountain = LargeMountainStrokeBenchmark.brush(view, 383, true);
        var river = LargeRiverStrokeBenchmark.brush(view, 383);
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId(), expected = 0, expectedRandom = 0;
        boolean memory = Boolean.getBoolean("welt.benchmark.mixedMemory");
        for (int trial = -3; trial < 5; trial++) {
            var dimension = LargeMountainStrokeBenchmark.fixture(383);
            dimension.setWaterLevelAt(-3, 5, 250);
            LargeMountainStrokeBenchmark.bind(view, dimension);
            LargeHeightStrokeParityTest.random().setSeed(197);
            long heights = HeightBrushAccess.completedThemedCalls(), rivers = RiverAccess.completedLargeCalls();
            long allocated = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
            for (int tick = 0; tick < 4; tick++) {
                dimension.armSavePoint();
                mountain.tick(-3, 5, (tick & 1) != 0, tick == 0, .85f);
                dimension.armSavePoint();
                river.tick(-3, 5, false, tick == 0, 1f);
            }
            double millis = (System.nanoTime() - start) / 1e6;
            allocated = meter.getThreadAllocatedBytes(thread) - allocated;
            if (HeightBrushAccess.completedThemedCalls() - heights != 4 || RiverAccess.completedLargeCalls() - rivers != 4) {
                throw new AssertionError("Expected eight complete native edits; mountain="
                        + (HeightBrushAccess.completedThemedCalls() - heights) + ", river="
                        + (RiverAccess.completedLargeCalls() - rivers));
            }
            long hash = LargeMountainStrokeBenchmark.digest(dimension);
            long random = LargeHeightStrokeParityTest.random().nextLong();
            if (trial == -3) { expected = hash; expectedRandom = random; }
            else if (expected != hash || expectedRandom != random) throw new AssertionError("Mixed edit output changed");
            long heap = -1, direct = -1;
            if (memory && trial >= 0) {
                // Retained bytes after collection, not a claim about instantaneous peak or RSS.
                System.gc();
                Thread.sleep(100);
                heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                        .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
            }
            if (trial >= 0) System.out.printf(Locale.ROOT,
                    "mixed trial=%d ms=%.3f allocated=%d hash=%d random=%d heap=%d direct=%d%n",
                    trial, millis, allocated, hash, random, heap, direct);
            LargeMountainStrokeBenchmark.bind(view, null);
            java.lang.ref.Reference.reachabilityFence(dimension);
        }
    }
}
