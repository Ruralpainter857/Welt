package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.LongAdder;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** A reusable worker frame for an entire deterministic material preview. */
final class MaterialPreviewAccess implements AutoCloseable {
    private static final ThreadLocal<MaterialPreviewAccess> WORKER = ThreadLocal.withInitial(MaterialPreviewAccess::new);
    private static final LongAdder TRANSACTIONS = new LongAdder();
    private ByteBuffer frame;
    private boolean active;
    private static final int HEADER = 128;

    static MaterialPreviewAccess fill(int width, int height, MixedMaterial.Row[] rows, float scale,
                           int paletteSize, boolean repeat, double slope, NoiseHeightMap variation, int noiseOffset) {
        if (!Native.isGenEnabled() || !Boolean.getBoolean("welt.native.materialPreview")
                || !NativeLoader.areSlicesAvailable() || width < 1 || width > 1024 || height < 1 || height > 1024
                || (long) width * height < 4096 || paletteSize < 1 || paletteSize > 1_000_000
                || rows != null && rows.length > 256
                || variation != null && (variation.getOctaves() < 1 || variation.getOctaves() > 10)) return null;
        MaterialPreviewAccess worker = WORKER.get();
        if (worker.active) return null;
        int count = rows == null ? 0 : rows.length;
        int output = HEADER + count * 8;
        int length = output + width * height * 4;
        if (worker.frame == null || worker.frame.capacity() < length) {
            worker.frame = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer frame = worker.frame;
        frame.clear().limit(length);
        for (int i = 0; i < HEADER; i += 8) frame.putLong(i, 0L);
        frame.putInt(0, 0x31504d57).putInt(4, 1).putInt(8, width).putInt(12, height)
                .putInt(16, rows == null ? 3 : 2).putInt(20, count).putInt(24, paletteSize)
                .putInt(28, repeat ? 1 : 0).putInt(32, output).putFloat(40, scale)
                .putInt(44, variation == null ? 0 : variation.getOctaves())
                .putDouble(48, variation == null ? 1.0 : variation.getScale())
                .putDouble(56, variation == null ? 0.0 : variation.getHeight())
                .putDouble(64, slope).putInt(72, noiseOffset);
        for (int i = 0; i < count; i++) {
            frame.putFloat(HEADER + i * 8, rows[i].scale).putFloat(HEADER + i * 8 + 4, rows[i].chance);
        }
        if (!NativeSlices.fillMaterialPreview(frame)) return null;
        TRANSACTIONS.increment();
        worker.active = true;
        return worker;
    }
    ByteBuffer buffer() { return frame; }
    @Override public void close() { active = false; }
    static long nativeTransactions() { return TRANSACTIONS.sum(); }
    private MaterialPreviewAccess() { }
}
