package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.util.concurrent.atomic.AtomicLong;
import static org.pepsoft.minecraft.Material.AIR;
import static org.pepsoft.minecraft.Material.WATERLOGGED;

/** Construction-time palette conversion; live block reads remain ordinary Java array accesses. */
final class SchemPackedAccess {
    record Result(byte[] blocks, int stride, int[] summary) { }
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final ThreadLocal<long[]> PROFILE = ThreadLocal.withInitial(() -> new long[2]);
    static long completedObjects() { return COMPLETED.get(); }
    static long[] profile() { return PROFILE.get().clone(); }

    static Result decode(byte[] source, int[] indices, Material[] palette, int width, int length, int height) {
        long plane = (long) width * length;
        if (plane < 1 || plane > 4 * 1024 * 1024 || (source == null) == (indices == null)) return null;
        long count = plane * height;
        if (!NativeSlices.isSchematicLoadingAvailable() || palette == null || palette.length < 1 || palette.length > 65536
                || width <= 0 || length <= 0 || height <= 0 || count < 1 || count > 4 * 1024 * 1024
                || source != null && source.length > 20 * 1024 * 1024
                || indices != null && indices.length != count) return null;
        boolean profiling = Boolean.getBoolean("welt.native.schemProfile");
        long start = profiling ? System.nanoTime() : 0;
        int stride = palette.length <= 256 ? 1 : 2;
        byte[] flags = new byte[palette.length];
        for (int i = 0; i < flags.length; i++) {
            Material material = palette[i];
            if (material != null && material != AIR) flags[i] = (byte) (1 | (material.is(WATERLOGGED) ? 2 : 0));
        }
        byte[] output = new byte[(int) count * stride];
        int[] summary = new int[8];
        long prepared = profiling ? System.nanoTime() : 0;
        if (!NativeSlices.decodeSchematic(source, indices, flags, output, summary, width, length, height)) return null;
        COMPLETED.incrementAndGet();
        if (profiling) {
            long[] p = PROFILE.get(); p[0] += prepared - start; p[1] += System.nanoTime() - prepared;
        }
        return new Result(output, stride, summary);
    }
}
