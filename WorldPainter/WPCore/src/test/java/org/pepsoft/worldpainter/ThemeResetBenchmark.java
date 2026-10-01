package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Mesure la réapplication complète, lectures et écritures comprises. */
public final class ThemeResetBenchmark {
    static Tile fixture(int index) {
        Tile tile = new Tile(index - 8, -3, -64, 320);
        tile.inhibitEvents();
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            tile.setHeight(x, y, -64 + (x * 13 + y * 7 + index * 11) % 383 + .25f);
            tile.setTerrain(x, y, Terrain.CUSTOM_1);
        }
        tile.releaseEvents();
        return tile;
    }
    static void scalar(SimpleTheme theme, Tile tile) {
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) theme.apply(tile, x, y);
    }
    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust");
        System.setProperty(org.pepsoft.worldpainter.nativeapi.Native.GEN_KEY, "true");
        SimpleTheme theme = SimpleTheme.createDefault(Terrain.GRASS, -64, 320, 62, true, true);
        double[] times = new double[7]; long allocated = 0;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int trial = -5; trial < 7; trial++) {
            Tile[] tiles = new Tile[16];
            for (int i = 0; i < tiles.length; i++) { tiles[i] = fixture(i); tiles[i].inhibitEvents(); }
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            for (Tile tile : tiles) {
                if (rust) { if (!theme.applyToExistingTile(tile)) throw new AssertionError("JNI unavailable"); }
                else scalar(theme, tile);
            }
            for (Tile tile : tiles) tile.releaseEvents();
            double elapsed = (System.nanoTime() - start) / 1e6;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = elapsed;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s theme_reset_16_tiles_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
