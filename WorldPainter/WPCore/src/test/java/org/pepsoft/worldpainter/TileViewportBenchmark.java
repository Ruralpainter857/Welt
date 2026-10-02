package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

/** Measures complete viewport redraws, including tile snapshots and image writes. */
public final class TileViewportBenchmark {
    private static volatile int checksum;
    private record Setup(Dimension dimension, Tile[] tiles, TileRenderer renderer, BufferedImage image) { }
    private record Sample(long nanos, long allocated) { }

    public static void main(String[] args) {
        boolean fused = args.length > 0 && (args[0].equals("fused") || args[0].equals("compare-fused"));
        System.setProperty("welt.benchmark.viewportFused", Boolean.toString(fused));
        if (args.length > 0 && args[0].equals("compare-fused")) { compare(); return; }
        if (args.length > 0 && args[0].equals("compare")) { compare(); return; }
        boolean nativeShade = fused || args.length > 0 && args[0].equals("shade");
        Setup setup = fixture(); select(nativeShade);
        long[] times = new long[9], allocations = new long[9];
        for (int sample = -5; sample < 9; sample++) {
            Sample result = redraw(setup);
            if (sample >= 0) { times[sample] = result.nanos; allocations[sample] = result.allocated; }
        }
        Arrays.sort(times); Arrays.sort(allocations);
        long direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.printf("viewport mode=%s threeFullRedraws medianMs=%.3f allocatedBytes=%d directBytes=%d checksum=%d nativeTiles=%d%n",
                fused ? "fused" : nativeShade ? "shade" : "java", times[4] / 1e6, allocations[4], direct, checksum, setup.renderer.completedNativeViewportTiles());
        if (fused && setup.renderer.completedNativeViewportTiles() != 14L * 3 * setup.tiles.length)
            throw new AssertionError("Every tile redraw must use the fused path");
    }
    private static void select(boolean nativeShade) {
        System.setProperty(Native.RENDER_KEY, Boolean.toString(nativeShade));
        System.setProperty("welt.native.viewport", Boolean.toString(nativeShade && Boolean.getBoolean("welt.benchmark.viewportFused")));
        if (nativeShade && !NativeLoader.areSlicesAvailable()) throw new AssertionError("Native rendering library unavailable");
    }
    private static Setup fixture() {
        System.setProperty(Native.GEN_KEY, "false");
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS,
                p.minZ, p.standardMaxHeight, 62, 62, false, false);
        Dimension d = new Dimension(new World2(p, p.minZ, p.standardMaxHeight), "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        Terrain[] terrains = {Terrain.GRASS, Terrain.SAND, Terrain.DIRT, Terrain.STONE};
        boolean indexed = Boolean.getBoolean("welt.benchmark.viewportIndexed");
        int[] biomes = {1, 4, 6, 21, 255};
        Tile[] tiles = new Tile[16]; int next = 0;
        for (int ty = -2; ty < 2; ty++) for (int tx = -2; tx < 2; tx++) {
            Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setHeight(x, y, 58 + ((x * 3 + y * 7) & 63) / 4f);
                tile.setWaterLevel(x, y, 64);
                tile.setTerrain(x, y, terrains[(x / 32 + y / 32) & 3]);
                tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3) & 15);
                tile.setLayerValue(DeciduousForest.INSTANCE, x, y, (x * 7 + y) & 15);
                tile.setLayerValue(PineForest.INSTANCE, x, y, (x + y * 5) & 15);
                tile.setBitLayerValue(River.INSTANCE, x, y, ((x * 3 + y * 5) & 15) == 0);
                tile.setBitLayerValue(Frost.INSTANCE, x, y, ((x + y) & 7) == 0);
                tile.setBitLayerValue(ReadOnly.INSTANCE, x, y, (x / 16 + y / 16) % 7 == 0);
                tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, x < 32 && y < 32);
                if (indexed) {
                    tile.setLayerValue(Biome.INSTANCE, x, y, biomes[(x / 16 + y / 16) % biomes.length]);
                    tile.setLayerValue(Annotations.INSTANCE, x, y, (x + y * 3) & 15);
                }
            }
            tile.releaseEvents(); d.addTile(tile); tiles[next++] = tile;
        }
        TileRenderer renderer = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
        renderer.setContourLines(true);
        return new Setup(d, tiles, renderer, new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
    }
    private static Sample redraw(Setup setup) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        for (int pass = 0; pass < 3; pass++) for (Tile tile : setup.tiles)
            setup.renderer.renderTile(tile, setup.image, (tile.getX() + 2) << 7, (tile.getY() + 2) << 7);
        long elapsed = System.nanoTime() - start;
        allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
        checksum = Arrays.hashCode(pixels(setup));
        return new Sample(elapsed, allocated);
    }
    private static int[] pixels(Setup setup) { return ((DataBufferInt) setup.image.getRaster().getDataBuffer()).getData(); }
    private static void compare() {
        Setup java = fixture(), shade = fixture();
        double[] jt = new double[9], rt = new double[9], ratios = new double[9];
        long[] ja = new long[9], ra = new long[9];
        for (int sample = -5; sample < 9; sample++) {
            Sample j = null, r = null;
            for (int pass = 0; pass < 2; pass++) {
                boolean nativePass = ((sample + pass) & 1) != 0; select(nativePass);
                if (nativePass) r = redraw(shade); else j = redraw(java);
            }
            if (!Arrays.equals(pixels(java), pixels(shade))) throw new AssertionError("Complete viewport pixel mismatch");
            if (sample >= 0) {
                jt[sample] = j.nanos / 1e6; rt[sample] = r.nanos / 1e6; ratios[sample] = (double) j.nanos / r.nanos;
                ja[sample] = j.allocated; ra[sample] = r.allocated;
            }
        }
        Arrays.sort(jt); Arrays.sort(rt); Arrays.sort(ratios); Arrays.sort(ja); Arrays.sort(ra);
        System.out.printf("viewport paired javaMs=%.3f nativeMs=%.3f ratio=%.3f minRatio=%.3f maxRatio=%.3f javaAllocated=%d nativeAllocated=%d pixelParity=true nativeTiles=%d%n",
                jt[4], rt[4], ratios[4], ratios[0], ratios[8], ja[4], ra[4], shade.renderer.completedNativeViewportTiles());
        if (Boolean.getBoolean("welt.benchmark.viewportFused") && shade.renderer.completedNativeViewportTiles() != 14L * 3 * shade.tiles.length)
            throw new AssertionError("Every tile redraw must use the fused path");
    }
}
