package org.pepsoft.worldpainter;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.exporting.MinecraftWorld;
import org.pepsoft.worldpainter.layers.exporters.FrostExporter;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.awt.Rectangle;
import java.util.Arrays;

import static org.pepsoft.minecraft.Material.AIR;
import static org.pepsoft.minecraft.Material.GRASS_BLOCK;

/** Measures FrostExporter.addFeatures on a reusable in-memory surface. */
public final class FrostExporterBenchmark {
    private static volatile int sink;

    private FrostExporterBenchmark() {
    }

    public static void main(String[] args) {
        final int side = args.length > 0 ? Integer.parseInt(args[0]) : 128;
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 7;
        final Rectangle area = new Rectangle(0, 0, side, side);
        final Dimension dimension = TestData.createDimension(area, 80);
        final MinecraftWorld world = TestData.createMinecraftWorld(area, 80, GRASS_BLOCK);
        final FrostExporter.FrostSettings settings = new FrostExporter.FrostSettings();
        settings.setFrostEverywhere(true);
        settings.setMode(FrostExporter.FrostSettings.MODE_FLAT);
        final FrostExporter exporter = new FrostExporter(dimension, TestData.PLATFORM, settings);

        for (int i = 0; i < 3; i++) {
            measure(exporter, world, area, false);
            resetSnow(world, side);
            measure(exporter, world, area, true);
            resetSnow(world, side);
        }
        final double[] javaTimes = new double[rounds];
        final double[] nativeTimes = new double[rounds];
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(exporter, world, area, false);
                resetSnow(world, side);
                nativeTimes[round] = measure(exporter, world, area, true);
            } else {
                nativeTimes[round] = measure(exporter, world, area, true);
                resetSnow(world, side);
                javaTimes[round] = measure(exporter, world, area, false);
            }
            resetSnow(world, side);
        }
        Arrays.sort(javaTimes);
        Arrays.sort(nativeTimes);
        final double javaMedian = javaTimes[rounds / 2];
        final double nativeMedian = nativeTimes[rounds / 2];
        System.out.printf("columns=%d rounds=%d java_ms_per_column=%.6f native_ms_per_column=%.6f speedup=%.2fx%n",
                side * side, rounds, javaMedian, nativeMedian, javaMedian / nativeMedian);
        if (sink == Integer.MIN_VALUE) {
            System.out.println("sink=" + sink);
        }
    }

    private static double measure(FrostExporter exporter, MinecraftWorld world,
                                  Rectangle area, boolean nativePath) {
        Native.setExportEnabled(nativePath);
        final long start = System.nanoTime();
        exporter.addFeatures(area, area, world);
        final long elapsed = System.nanoTime() - start;
        sink += world.getMaterialAt(area.x, area.y, 81).hashCode();
        return elapsed / 1_000_000.0 / ((long) area.width * area.height);
    }

    private static void resetSnow(MinecraftWorld world, int side) {
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                world.setMaterialAt(x, y, 81, AIR);
            }
        }
    }
}
