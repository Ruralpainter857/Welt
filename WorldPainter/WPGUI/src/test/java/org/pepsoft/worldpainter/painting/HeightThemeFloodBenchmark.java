package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

/** Mesure le remplissage et son thème complet sur des tuiles existantes. */
public final class HeightThemeFloodBenchmark {
    static Dimension fixture() {
        Dimension d = FluidFloodBenchmark.fixture(4);
        ((HeightMapTileFactory) d.getTileFactory()).setTheme(SimpleTheme.createDefault(Terrain.CUSTOM_1, -64, 320, 62, true, false));
        return d;
    }
    static void fill(Dimension d, boolean rust) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(rust)); d.setEventsInhibited(true);
        try { if (!new DimensionPainter().fill(d, -64, -64, DimensionPainter.AdditionalFillAction.APPLY_THEME, null)) throw new AssertionError("Bounds reached"); }
        finally { d.setEventsInhibited(false); }
    }
    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust"); NativeLoader.areSlicesAvailable();
        double[] times = new double[7]; long allocated = 0;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int trial = -5; trial < 7; trial++) {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture();
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), started = System.nanoTime();
            for (int i = 0; i < 12; i++) fill(d, rust);
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - started) / 1e6;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s height_theme_12_clicks_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
