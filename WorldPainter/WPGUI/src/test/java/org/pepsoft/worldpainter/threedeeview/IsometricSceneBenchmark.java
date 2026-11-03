package org.pepsoft.worldpainter.threedeeview;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Measures complete isometric scene redraws, including terrain colour and image creation. */
public final class IsometricSceneBenchmark {
    private static volatile long checksum;

    static Dimension scene() {
        var platform = DefaultPlugin.JAVA_ANVIL_1_19;
        var factory = new HeightMapTileFactory(197, new ConstantHeightMap(64), -64, 320, false,
                SimpleTheme.createSingleTerrain(Terrain.GRASS, -64, 320, 62));
        var world = new World2(platform, -64, 320);
        var dimension = new Dimension(world, "Isometric fixture", 197, factory,
                Dimension.Anchor.NORMAL_DETAIL, true);
        for (int ty = -1; ty <= 0; ty++) for (int tx = -1; tx <= 0; tx++) {
            Tile tile = new Tile(tx, ty, -64, 320);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                float height = 48 + (float) (Math.sin((x + tx * 128) * .017)
                        * Math.cos((y + ty * 128) * .021) * 46);
                tile.setHeight(x, y, height);
                tile.setWaterLevel(x, y, 62);
                tile.setTerrain(x, y, x < 64 ? Terrain.GRASS : Terrain.SAND);
                tile.setBitLayerValue(Frost.INSTANCE, x, y, x < 28);
                tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, x > 110);
                tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, x, y, x < 3 && y < 18);
            }
            dimension.addTile(tile);
        }
        return dimension;
    }

    static long hash(BufferedImage image) {
        long hash = 1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            hash = hash * 31 + image.getRGB(x, y);
        return hash;
    }

    public static void main(String[] args) {
        System.setProperty(Native.GEN_KEY, "false");
        System.setProperty(Native.RENDER_KEY, "false");
        Dimension dimension = scene();
        boolean compare = args.length > 0 && args[0].equals("compare");
        boolean nativeOnly = args.length > 0 && args[0].equals("rust");
        boolean memory = Boolean.getBoolean("welt.benchmark.isometricMemory");
        if (memory && compare) throw new IllegalArgumentException("Measure memory in separate processes");
        var renderer = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(),
                0, Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
        var tiles = new ArrayList<>(dimension.getTiles());
        tiles.sort(Comparator.comparingInt(Tile::getX).thenComparingInt(Tile::getY));
        var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId(), expectedHash = 0;
        double[] times = new double[9], javaTimes = new double[9], rustTimes = new double[9], ratios = new double[9];
        long[] allocated = new long[9], javaAllocated = new long[9], rustAllocated = new long[9];
        for (int round = -5; round < 9; round++) {
          for (int pass = 0; pass < (compare ? 2 : 1); pass++) {
            boolean nativeMode = compare ? ((round + pass) & 1) != 0 : nativeOnly;
            System.setProperty(Native.RENDER_KEY, Boolean.toString(nativeMode));
            System.setProperty("welt.native.isometric", Boolean.toString(nativeMode));
            if (memory && round >= 0) {
                System.gc();
                for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
            }
            long callsBefore = IsometricRenderAccess.completedTiles();
            BufferedImage[] images = new BufferedImage[tiles.size()];
            long before = allocations.getThreadAllocatedBytes(thread), start = System.nanoTime();
            for (int tile = 0; tile < tiles.size(); tile++) images[tile] = renderer.render(tiles.get(tile));
            long elapsed = System.nanoTime() - start;
            long bytes = allocations.getThreadAllocatedBytes(thread) - before;
            if (IsometricRenderAccess.completedTiles() - callsBefore != (nativeMode ? tiles.size() : 0))
                throw new AssertionError("Unexpected native isometric tile coverage");
            long hash = 1;
            for (BufferedImage image : images) {
                if (image == ThreeDeeView.TILE_NOT_RENDERABLE || image.getWidth() != 256)
                    throw new AssertionError("Scene tile did not render");
                hash = hash * 31 + hash(image);
            }
            if (round == -5) expectedHash = hash;
            else if (expectedHash != hash) throw new AssertionError("Unstable isometric scene pixels");
            checksum = hash;
            images = null;
            if (round >= 0) {
                times[round] = elapsed / 1e6; allocated[round] = bytes;
                if (nativeMode) { rustTimes[round] = elapsed / 1e6; rustAllocated[round] = bytes; }
                else { javaTimes[round] = elapsed / 1e6; javaAllocated[round] = bytes; }
                if (memory) {
                    long peak = ManagementFactory.getMemoryPoolMXBeans().stream()
                            .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                            .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
                    System.gc();
                    long retained = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                    long direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                            .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
                    System.out.printf(Locale.ROOT, "isometricMemory mode=%s trial=%d heapPoolPeakSumBytes=%d retainedHeapBytes=%d directBytes=%d%n",
                            nativeMode ? "rust" : "java", round, peak, retained, direct);
                }
            }
          }
          if (compare && round >= 0) ratios[round] = javaTimes[round] / rustTimes[round];
        }
        if (compare) {
            Arrays.sort(javaTimes); Arrays.sort(rustTimes); Arrays.sort(ratios); Arrays.sort(javaAllocated); Arrays.sort(rustAllocated);
            System.out.printf(Locale.ROOT, "isometricScene paired tiles=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocatedBytes=%d rustAllocatedBytes=%d nativeCalls=%d checksum=%d%n",
                    tiles.size(), javaTimes[4], rustTimes[4], ratios[4], ratios[0], ratios[8], javaAllocated[4], rustAllocated[4], IsometricRenderAccess.completedTiles(), checksum);
            return;
        }
        Arrays.sort(times); Arrays.sort(allocated);
        System.out.printf(Locale.ROOT, "isometricScene mode=%s tiles=%d medianMs=%.3f callerAllocatedBytes=%d nativeCalls=%d checksum=%d%n",
                nativeOnly ? "rust" : "java", tiles.size(), times[4], allocated[4], IsometricRenderAccess.completedTiles(), checksum);
    }
}
