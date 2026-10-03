package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Factory sampling, height quantisation and theme share the existing WHIM transaction. */
final class BitmapTileGenerationAccess {
    private static final int AREA = 16384, MAX_BYTES = 4194304;
    private static final ThreadLocal<Worker> WORKERS = ThreadLocal.withInitial(Worker::new);
    private static final class Worker { ByteBuffer data; long completed; }
    static long completed() { return WORKERS.get().completed; }

    static boolean fill(HeightMapTileFactory factory, Tile tile, int tx, int ty) {
        if (!Native.isGenEnabled() || !Boolean.getBoolean("welt.native.bitmapGeneration") || !NativeLoader.areSlicesAvailable()
                || factory.getClass() != HeightMapTileFactory.class || factory.isFloodWithLava()
                || factory.getTheme().getClass() != SimpleTheme.class) return false;
        long x = (long) tx * 128, y = (long) ty * 128;
        if (x < Integer.MIN_VALUE || y < Integer.MIN_VALUE || x + 127 > Integer.MAX_VALUE || y + 127 > Integer.MAX_VALUE) return false;
        BitmapImportSource source = BitmapImportSource.prepare(factory.getHeightMap());
        if (source == null) return false;
        BitmapImportSource.Window window = source.window((int) x, (int) y);
        if (window == null) return false;
        SimpleTheme.ImportPlan plan = ((SimpleTheme) factory.getTheme()).prepareImport();
        if (plan == null || !plan.supportsFreshImport()) return false;
        Layer[] themeLayers = plan.getLayers(), layers = new Layer[themeLayers.length + 3];
        System.arraycopy(themeLayers, 0, layers, 3, themeLayers.length);
        int n = layers.length, samples = 256 + n * 16, meta = samples + AREA * 8;
        int[] kinds = new int[n], roles = new int[n], offsets = new int[n];
        int bytes = 0;
        for (int p = 0; p < n; p++) {
            roles[p] = Math.min(p, 3);
            kinds[p] = p < 2 ? 0 : p == 2 ? 1 : layers[p].dataSize == Layer.DataSize.BYTE ? 1
                    : layers[p].dataSize == Layer.DataSize.NIBBLE ? 2 : layers[p].dataSize == Layer.DataSize.BIT ? 3 : 4;
            offsets[p] = bytes; bytes += SelectionCopyAccess.length(kinds[p]);
        }
        int theme = meta + 32 + bytes, patch = theme + plan.bytes(), size = patch + window.bytes();
        if (size > MAX_BYTES) return false;
        Worker worker = WORKERS.get();
        if (worker.data == null || worker.data.capacity() < size) worker.data = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer data = worker.data; data.clear().limit(size);
        for (int i = 0; i < 256; i += 8) data.putLong(i, 0L);
        data.putInt(0, 0x4d494857).putInt(4, 3).putInt(8, size).putInt(12, n)
                .putInt(16, factory.getMinHeight()).putInt(20, factory.getMaxHeight()).putInt(24, 65)
                .putInt(36, factory.getWaterHeight()).putInt(40, (int) x).putInt(44, (int) y)
                .putInt(48, (int) x).putInt(52, (int) y).putInt(56, 128).putInt(60, 128)
                .putInt(112, samples).putInt(116, meta).putInt(120, meta).putInt(124, 256)
                .putInt(128, theme).putInt(136, Terrain.BEACHES.ordinal()).putInt(208, patch);
        for (int p = 0; p < n; p++) data.putInt(256 + p * 16, kinds[p]).putInt(260 + p * 16, roles[p])
                .putInt(264 + p * 16, layers[p] == null ? 0 : layers[p].getDefaultValue()).putInt(268 + p * 16, offsets[p]);
        data.putInt(meta, tx).putInt(meta + 4, ty).putLong(meta + 8, 7L).putLong(meta + 16, 0L).putLong(meta + 24, 0L);
        plan.write(data, theme, layers);
        source.write(data, patch, window);
        if (!SimpleTheme.processHeightMapImport(data)) return false;
        tile.applyOrderedPlanes(data, meta, layers, roles, kinds, offsets);
        worker.completed++;
        return true;
    }
}
