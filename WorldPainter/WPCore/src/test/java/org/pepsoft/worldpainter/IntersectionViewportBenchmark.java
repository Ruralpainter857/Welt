package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.tunnel.TunnelLayer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Complete redraws of linked dimensions with intersections (see {@link IntersectionViewportFixtures}): the surface
 * with its ceiling intersection and the ceiling with its surface intersection ({@code ceiling} scene), or the tunnel
 * floor dimension with its roof intersection ({@code tunnel} scene). Modes: {@code java}, {@code fused} (native
 * viewport) or {@code compare} (alternating pairs with complete pixel parity after every pair).
 *
 * <p>Usage: {@code IntersectionViewportBenchmark scene mode [zoom] [side] [warmups] [samples]}.
 */
public final class IntersectionViewportBenchmark {
    private static volatile int checksum;

    private record Setup(List<Tile> tiles, TileRenderer renderer, BufferedImage image, int origin, int tilePixels) { }

    public static void main(String[] args) {
        final String scene = args[0], mode = args[1];
        final int zoom = (args.length > 2) ? Integer.parseInt(args[2]) : 0;
        final int side = (args.length > 3) ? Integer.parseInt(args[3]) : 4;
        final int warmups = (args.length > 4) ? Integer.parseInt(args[4]) : 5;
        final int samples = (args.length > 5) ? Integer.parseInt(args[5]) : 9;
        System.setProperty(Native.GEN_KEY, "false");
        if (mode.equals("compare")) {
            final Setup[] java = setups(scene, zoom, side), fused = setups(scene, zoom, side);
            final double[] javaMs = new double[samples], fusedMs = new double[samples], ratios = new double[samples];
            for (int sample = -warmups; sample < samples; sample++) {
                long javaNanos = 0, fusedNanos = 0;
                for (int pass = 0; pass < 2; pass++) {
                    final boolean nativePass = ((sample + pass) & 1) != 0;
                    select(nativePass);
                    if (nativePass) fusedNanos = redraw(fused); else javaNanos = redraw(java);
                }
                for (int i = 0; i < java.length; i++) {
                    if (! Arrays.equals(pixels(java[i]), pixels(fused[i]))) {
                        throw new AssertionError("Complete viewport pixel mismatch in " + scene + " renderer " + i);
                    }
                }
                if (sample >= 0) {
                    javaMs[sample] = javaNanos / 1e6;
                    fusedMs[sample] = fusedNanos / 1e6;
                    ratios[sample] = (double) javaNanos / fusedNanos;
                }
            }
            Arrays.sort(javaMs);
            Arrays.sort(fusedMs);
            Arrays.sort(ratios);
            long nativeTiles = 0, expectedTiles = 0;
            for (Setup setup: fused) {
                nativeTiles += setup.renderer.completedNativeViewportTiles();
                expectedTiles += (long) (warmups + samples) * 3 * setup.tiles.size();
            }
            System.out.printf(Locale.ROOT, "intersection paired scene=%s zoom=%d javaMs=%.3f nativeMs=%.3f ratio=%.3f minRatio=%.3f maxRatio=%.3f pixelParity=true nativeTiles=%d expectedTiles=%d%n",
                    scene, zoom, javaMs[samples / 2], fusedMs[samples / 2], ratios[samples / 2], ratios[0], ratios[samples - 1], nativeTiles, expectedTiles);
            if (nativeTiles != expectedTiles) {
                throw new AssertionError("Every tile redraw must use the native transaction");
            }
            return;
        }
        final boolean fused = mode.equals("fused");
        final Setup[] setups = setups(scene, zoom, side);
        select(fused);
        final var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long[] times = new long[samples], allocations = new long[samples];
        for (int sample = -warmups; sample < samples; sample++) {
            final long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            final long nanos = redraw(setups);
            if (sample >= 0) {
                times[sample] = nanos;
                allocations[sample] = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
            }
        }
        Arrays.sort(times);
        Arrays.sort(allocations);
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        final long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        long nativeTiles = 0;
        for (Setup setup: setups) {
            nativeTiles += setup.renderer.completedNativeViewportTiles();
        }
        System.out.printf(Locale.ROOT, "intersection scene=%s mode=%s zoom=%d threeFullRedraws medianMs=%.3f allocatedBytes=%d retainedHeapBytes=%d directBytes=%d checksum=%d nativeTiles=%d%n",
                scene, mode, zoom, times[samples / 2] / 1e6, allocations[samples / 2],
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), direct, checksum, nativeTiles);
    }

    static Setup[] setups(String scene, int zoom, int side) {
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        final TunnelLayer.Mode roofMode = TunnelLayer.Mode.valueOf(System.getProperty("welt.benchmark.roofMode", "CONSTANT_DEPTH"));
        final boolean roofNoise = Boolean.parseBoolean(System.getProperty("welt.benchmark.roofNoise", "true"));
        final IntersectionViewportFixtures.Worlds worlds = IntersectionViewportFixtures.create(side, roofMode, roofNoise, platform.minZ, platform.standardMaxHeight);
        final int origin = side / 2 + 1, tilePixels = 128 >> -zoom, pixels = (side + 2) * tilePixels;
        if (scene.equals("ceiling")) {
            return new Setup[] {
                    new Setup(worlds.surfaceTiles, IntersectionViewportFixtures.renderer(worlds.surface, zoom), new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB), origin, tilePixels),
                    new Setup(worlds.ceilingTiles, IntersectionViewportFixtures.renderer(worlds.ceiling, zoom), new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB), origin, tilePixels)};
        } else if (scene.equals("tunnel")) {
            return new Setup[] {
                    new Setup(worlds.floorTiles, IntersectionViewportFixtures.renderer(worlds.floor, zoom), new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB), origin, tilePixels)};
        } else {
            throw new IllegalArgumentException("Unknown scene " + scene);
        }
    }

    static void select(boolean nativeRendering) {
        System.setProperty(Native.RENDER_KEY, Boolean.toString(nativeRendering));
        System.setProperty("welt.native.viewport", Boolean.toString(nativeRendering));
        if (nativeRendering && ! NativeLoader.areSlicesAvailable()) {
            throw new AssertionError("Native rendering library unavailable");
        }
    }

    static long redraw(Setup[] setups) {
        final long start = System.nanoTime();
        for (int pass = 0; pass < 3; pass++) {
            for (Setup setup: setups) {
                for (Tile tile: setup.tiles) {
                    setup.renderer.renderTile(tile, setup.image, (tile.getX() + setup.origin) * setup.tilePixels, (tile.getY() + setup.origin) * setup.tilePixels);
                }
            }
        }
        final long elapsed = System.nanoTime() - start;
        int hash = 0;
        for (Setup setup: setups) {
            hash = hash * 31 + Arrays.hashCode(pixels(setup));
        }
        checksum = hash;
        return elapsed;
    }

    static int[] pixels(Setup setup) {
        return ((DataBufferInt) setup.image.getRaster().getDataBuffer()).getData();
    }
}
