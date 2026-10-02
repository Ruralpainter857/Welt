package org.pepsoft.worldpainter.heightMaps.gui;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.pepsoft.worldpainter.HeightMap;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.heightMaps.SlopeHeightMap;
import org.pepsoft.worldpainter.heightMaps.TransformingHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete height map preview redraws, including grey raster conversion and image writes. */
public final class HeightMapPreviewBenchmark {
    private static volatile int checksum;
    private record Setup(HeightMapTileProvider provider, BufferedImage image) { }
    private record Sample(long nanos, long allocated) { }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("compare")) { compare(); return; }
        boolean nativeMode = args.length > 0 && args[0].equals("rust");
        Setup setup = fixture();
        select(nativeMode);
        long[] times = new long[9], allocations = new long[9];
        for (int sample = -5; sample < 9; sample++) {
            Sample result = redraw(setup);
            if (sample >= 0) { times[sample] = result.nanos; allocations[sample] = result.allocated; }
        }
        Arrays.sort(times); Arrays.sort(allocations);
        long nativeTiles = nativeMode ? setup.provider.completedNativePreviewTiles() : 0;
        System.out.printf("heightMapPreview mode=%s twoFullRedraws medianMs=%.3f allocatedBytes=%d checksum=%d nativeTiles=%d%n",
                nativeMode ? "rust" : "java", times[4] / 1e6, allocations[4], checksum, nativeTiles);
        if (nativeMode && nativeTiles != 448) throw new AssertionError("Every preview tile must use Rust");
    }
    private static void select(boolean nativeMode) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        System.setProperty("welt.native.heightMapPreview", Boolean.toString(nativeMode));
        System.setProperty("welt.native.slopePreviewZoom", Boolean.toString(nativeMode));
    }
    private static Setup fixture() {
        HeightMap map = new SumHeightMap(new ConstantHeightMap(32),
                new SumHeightMap(new NoiseHeightMap(100, 1.7, 4, 17), new NoiseHeightMap(60, .7, 3, -123)));
        if (Boolean.getBoolean("welt.benchmark.previewSlope")) map = new SlopeHeightMap(map, 3.7f);
        if (Boolean.getBoolean("welt.benchmark.previewTransform"))
            map = new TransformingHeightMap("Preview", map, 1.7f, .65f, 31, -47, .37f);
        map.setSeed(123456789L);
        HeightMapTileProvider provider = new HeightMapTileProvider(map);
        provider.setZoom(Integer.getInteger("welt.benchmark.previewZoom", 0));
        return new Setup(provider, new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB));
    }
    private static Sample redraw(Setup setup) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        for (int pass = 0; pass < 2; pass++) for (int y = -2; y < 2; y++) for (int x = -2; x < 2; x++)
            setup.provider.paintTile(setup.image, x, y, (x + 2) << 7, (y + 2) << 7);
        long elapsed = System.nanoTime() - start;
        allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
        checksum = Arrays.hashCode(((DataBufferInt) setup.image.getRaster().getDataBuffer()).getData());
        return new Sample(elapsed, allocated);
    }
    private static void compare() {
        Setup java = fixture(), rust = fixture();
        double[] jt = new double[9], rt = new double[9], ratios = new double[9];
        long[] ja = new long[9], ra = new long[9];
        for (int sample = -5; sample < 9; sample++) {
            Sample j = null, r = null;
            for (int pass = 0; pass < 2; pass++) {
                boolean nativePass = ((sample + pass) & 1) != 0; select(nativePass);
                if (nativePass) r = redraw(rust); else j = redraw(java);
            }
            if (!Arrays.equals(((DataBufferInt) java.image.getRaster().getDataBuffer()).getData(),
                    ((DataBufferInt) rust.image.getRaster().getDataBuffer()).getData())) throw new AssertionError("Full preview pixel mismatch");
            if (sample >= 0) {
                jt[sample] = j.nanos / 1e6; rt[sample] = r.nanos / 1e6; ratios[sample] = (double) j.nanos / r.nanos;
                ja[sample] = j.allocated; ra[sample] = r.allocated;
            }
        }
        Arrays.sort(jt); Arrays.sort(rt); Arrays.sort(ratios); Arrays.sort(ja); Arrays.sort(ra);
        long calls = rust.provider.completedNativePreviewTiles();
        if (calls != 448) throw new AssertionError("Every preview tile must use Rust");
        System.out.printf("heightMapPreview paired javaMs=%.3f rustMs=%.3f ratio=%.3f minRatio=%.3f maxRatio=%.3f javaAllocated=%d rustAllocated=%d pixelParity=true nativeTiles=%d%n",
                jt[4], rt[4], ratios[4], ratios[0], ratios[8], ja[4], ra[4], calls);
    }
}
