package org.pepsoft.worldpainter.selection;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Mesure le copier-coller complet, notifications comprises, sans le rafraîchissement graphique. */
public final class SelectionCopyBenchmark {
    static final Layer CHUNK = new Layer("welt.test.copy.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) {};
    static final Layer DEFAULT = new Layer("welt.test.copy.default", "Default", "", Layer.DataSize.NIBBLE, false, 31) {
        @Override public int getDefaultValue() { return 7; }
    };
    static Dimension fixture(int side) {
        Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        World2 world = new World2(platform, platform.minZ, platform.standardMaxHeight);
        int min = world.getMinHeight(), max = world.getMaxHeight();
        var factory = new HeightMapTileFactory(0, new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(62), min, max, false,
                org.pepsoft.worldpainter.themes.SimpleTheme.createSingleTerrain(Terrain.GRASS, min, max, 62));
        Dimension d = new Dimension(world, "Copy", 0, factory, Dimension.Anchor.NORMAL_DETAIL, false);
        for (int tx = -1; tx < side - 1; tx++) for (int ty = -1; ty < side - 1; ty++) {
            Tile t = new Tile(tx, ty, min, max); t.inhibitEvents();
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                int n = Math.floorMod(tx * 29 + ty * 47 + x * 5 + y * 11, 256);
                t.setRawHeight(x, y, 20000 + n * 17); t.setWaterLevel(x, y, 50 + n / 4);
                t.setTerrain(x, y, n % 3 == 0 ? Terrain.CUSTOM_1 : Terrain.GRASS);
                t.setLayerValue(Resources.INSTANCE, x, y, 1 + n % 15);
                t.setLayerValue(DEFAULT, x, y, n % 16);
                t.setLayerValue(Biome.INSTANCE, x, y, n);
                t.setLayerValue(Annotations.INSTANCE, x, y, n % 16);
                t.setBitLayerValue(Frost.INSTANCE, x, y, n % 2 == 0);
                t.setBitLayerValue(FloodWithLava.INSTANCE, x, y, n % 5 == 0);
                if (x % 16 == 0 && y % 16 == 0) {
                    t.setBitLayerValue(CHUNK, x, y, n % 2 == 0);
                    t.setBitLayerValue(SelectionChunk.INSTANCE, x, y, x >= 16 && x < 112 && y >= 16 && y < 112);
                }
                if (x == 1 || y == 1) t.setBitLayerValue(SelectionBlock.INSTANCE, x, y, n % 3 == 0);
            }
            t.releaseEvents(); d.addTile(t);
        }
        return d;
    }
    static void copy(Dimension d, SelectionOptions options, int dx, int dy, boolean rust) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
        SelectionHelper helper = new SelectionHelper(d); helper.setOptions(options);
        var bounds = helper.getSelectionBounds();
        d.setEventsInhibited(true);
        try { helper.copySelection(bounds.x + dx, bounds.y + dy, null); }
        finally { d.setEventsInhibited(false); }
    }
    public static void main(String[] args) throws Exception {
        boolean rust = args.length > 0 && args[0].equals("rust");
        int side = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        boolean blending = args.length > 2 && args[2].equals("blend");
        NativeLoader.areSlicesAvailable();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[] times = new double[7]; long[] bytes = new long[7]; long peak = 0;
        SelectionOptions options = new SelectionOptions(); options.setCopyAnnotations(true); options.setDoBlending(blending);
        for (int trial = -5; trial < 7; trial++) {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(side);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int i = 0; i < 8; i++) copy(d, options, (i & 1) == 0 ? 17 : -17, (i & 2) == 0 ? -19 : 19, rust);
            double elapsed = (System.nanoTime() - start) / 1e6;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            peak = Math.max(peak, NativeSlices.currentProcessResidentBytes());
            if (trial >= 0) { times[trial] = elapsed; bytes[trial] = allocated; }
        }
        Arrays.sort(times); Arrays.sort(bytes);
        System.out.printf(Locale.ROOT, "%s copy_8_actions_ms=%.3f heap_allocated_bytes=%d sampled_peak_rss_bytes=%d tiles=%d blending=%b%n",
                rust ? "Rust" : "Java", times[3], bytes[3], peak, side * side, blending);
    }
}
