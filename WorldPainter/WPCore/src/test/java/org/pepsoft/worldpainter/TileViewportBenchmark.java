package org.pepsoft.worldpainter;

import java.awt.Color;
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
    private record Setup(Dimension dimension, Tile[] tiles, TileRenderer renderer, BufferedImage image, int origin, int tilePixels) { }
    private record Sample(long nanos, long allocated) { }

    public static void main(String[] args) {
        boolean fused = args.length > 0 && (args[0].equals("fused") || args[0].equals("compare-fused"));
        System.setProperty("welt.benchmark.viewportFused", Boolean.toString(fused));
        if (args.length > 0 && args[0].equals("compare-fused")) { compare(); return; }
        if (args.length > 0 && args[0].equals("compare")) { compare(); return; }
        boolean nativeShade = fused || args.length > 0 && args[0].equals("shade");
        Setup setup = fixture(); select(nativeShade);
        long[] times = new long[9], allocations = new long[9];
        int warmups = Integer.getInteger("welt.benchmark.viewportWarmups", 5);
        if (warmups < 5 || warmups > 100) throw new IllegalArgumentException("Viewport warmups must be between 5 and 100");
        for (int sample = -warmups; sample < 9; sample++) {
            Sample result = redraw(setup);
            if (sample >= 0) { times[sample] = result.nanos; allocations[sample] = result.allocated; }
        }
        Arrays.sort(times); Arrays.sort(allocations);
        long direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.printf("viewport mode=%s zoom=%d tiles=%d threeFullRedraws medianMs=%.3f allocatedBytes=%d directBytes=%d checksum=%d nativeTiles=%d%n",
                fused ? "fused" : nativeShade ? "shade" : "java", setup.renderer.getZoom(), setup.tiles.length, times[4] / 1e6, allocations[4], direct, checksum, setup.renderer.completedNativeViewportTiles());
        if (fused && setup.renderer.completedNativeViewportTiles() != ((long) warmups + 9) * 3 * setup.tiles.length)
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
        boolean custom = Boolean.getBoolean("welt.benchmark.viewportCustomPaint");
        CustomLayer solid = custom ? new CustomLayer("Solid", "Custom paint fixture", Layer.DataSize.NIBBLE, 101, new Color(0x17395b)) { } : null;
        if (custom) solid.setOpacity(.37f);
        BufferedImage pattern = custom ? new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB_PRE) : null;
        if (custom) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            pattern.setRGB(x, y, ((x + y) % 4 * 85 << 24) | ((x * 17) << 16) | ((y * 17) << 8) | 0x39);
        CustomLayer textured = custom ? new CustomLayer("Texture", "Custom paint fixture", Layer.DataSize.NIBBLE, 102, pattern) { } : null;
        if (custom) textured.setOpacity(.65f);
        CustomLayer bitPaint = custom ? new CustomLayer("Bit paint", "Custom paint fixture", Layer.DataSize.BIT, 103, pattern) { } : null;
        if (custom) bitPaint.setOpacity(.73f);
        int[] biomes = {1, 4, 6, 21, 255};
        int zoom = Integer.getInteger("welt.benchmark.viewportZoom", 0);
        int side = Integer.getInteger("welt.benchmark.viewportSide", 4);
        if (zoom > 0 || zoom < -7 || side < 1 || side > 16)
            throw new IllegalArgumentException("Viewport zoom must be -7..0 and side 1..16");
        int origin = side / 2, tilePixels = 128 >> -zoom;
        Tile[] tiles = new Tile[side * side]; int next = 0;
        for (int ty = -origin; ty < side - origin; ty++) for (int tx = -origin; tx < side - origin; tx++) {
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
                if (custom) {
                    tile.setLayerValue(solid, x, y, (x + y * 3) & 15);
                    tile.setLayerValue(textured, x, y, (x * 7 + y) & 15);
                    tile.setBitLayerValue(bitPaint, x, y, ((x * 3 + y * 5) & 7) == 0);
                }
                if (indexed) {
                    tile.setLayerValue(Biome.INSTANCE, x, y, biomes[(x / 16 + y / 16) % biomes.length]);
                    tile.setLayerValue(Annotations.INSTANCE, x, y, (x + y * 3) & 15);
                }
            }
            tile.releaseEvents(); d.addTile(tile); tiles[next++] = tile;
        }
        TileRenderer renderer = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), zoom, true, null);
        renderer.setContourLines(true);
        return new Setup(d, tiles, renderer, new BufferedImage(side * tilePixels, side * tilePixels, BufferedImage.TYPE_INT_ARGB), origin, tilePixels);
    }
    private static Sample redraw(Setup setup) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        for (int pass = 0; pass < 3; pass++) for (Tile tile : setup.tiles)
            setup.renderer.renderTile(tile, setup.image, (tile.getX() + setup.origin) * setup.tilePixels, (tile.getY() + setup.origin) * setup.tilePixels);
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
        int warmups = Integer.getInteger("welt.benchmark.viewportWarmups", 5);
        if (warmups < 5 || warmups > 100) throw new IllegalArgumentException("Viewport warmups must be between 5 and 100");
        for (int sample = -warmups; sample < 9; sample++) {
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
        System.out.printf("viewport paired zoom=%d tiles=%d javaMs=%.3f nativeMs=%.3f ratio=%.3f minRatio=%.3f maxRatio=%.3f javaAllocated=%d nativeAllocated=%d pixelParity=true nativeTiles=%d%n",
                java.renderer.getZoom(), java.tiles.length, jt[4], rt[4], ratios[4], ratios[0], ratios[8], ja[4], ra[4], shade.renderer.completedNativeViewportTiles());
        if (Boolean.getBoolean("welt.benchmark.viewportFused") && shade.renderer.completedNativeViewportTiles() != ((long) warmups + 9) * 3 * shade.tiles.length)
            throw new AssertionError("Every tile redraw must use the fused path");
    }
}
