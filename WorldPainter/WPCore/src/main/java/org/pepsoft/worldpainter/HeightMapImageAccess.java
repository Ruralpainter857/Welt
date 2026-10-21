package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.exporting.HeightMapExporter.Format;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicLong;

/** One worker-owned WHIE frame, with bulk reads and row-wise raster application. */
public final class HeightMapImageAccess {
    private HeightMapImageAccess() { }
    private static final class Scratch {
        final ByteBuffer frame = ByteBuffer.allocateDirect(65568).order(ByteOrder.LITTLE_ENDIAN);
        final IntBuffer integers;
        final FloatBuffer floats;
        final int[] integerRow = new int[128];
        final float[] floatRow = new float[128];
        long preparationNanos, nativeNanos, applicationNanos;
        Scratch() {
            frame.position(32);
            ByteBuffer body = frame.slice().order(ByteOrder.LITTLE_ENDIAN);
            integers = body.asIntBuffer(); floats = body.asFloatBuffer(); frame.position(0);
        }
    }
    private static final ThreadLocal<Scratch> BUFFER = ThreadLocal.withInitial(Scratch::new);
    private static final AtomicLong COMPLETED = new AtomicLong();

    public static long completedTiles() { return COMPLETED.get(); }
    public static long[] profile() {
        Scratch s = BUFFER.get();
        return new long[] {s.preparationNanos, s.nativeNanos, s.applicationNanos};
    }

    public static boolean writeTile(Tile tile, WritableRaster raster, int x, int y,
                                    Format format, int dimensionMin, float offset, float scale) {
        if (!Boolean.getBoolean("welt.native.heightmapImage") || !Native.isRenderEnabled()
                || tile.getClass() != Tile.class || raster.getNumBands() != 1
                || x < raster.getMinX() || y < raster.getMinY()
                || (long) x + 128 > (long) raster.getMinX() + raster.getWidth()
                || (long) y + 128 > (long) raster.getMinY() + raster.getHeight()) return false;
        boolean floating = format == Format.FLOAT_NORMALISED || format == Format.FLOAT_ONE_TO_ONE;
        int type = raster.getDataBuffer().getDataType();
        if (floating ? type != DataBuffer.TYPE_FLOAT
                : type != DataBuffer.TYPE_BYTE && type != DataBuffer.TYPE_USHORT && type != DataBuffer.TYPE_INT) return false;
        boolean profiling = Boolean.getBoolean("welt.native.heightmapImageProfile");
        long start = profiling ? System.nanoTime() : 0;
        Scratch s = BUFFER.get(); ByteBuffer b = s.frame;
        int mode = switch (format) {
            case INTEGER_HIGH_RESOLUTION -> 0;
            case INTEGER_LOW_RESOLUTION -> 1;
            case FLOAT_ONE_TO_ONE -> 2;
            case FLOAT_NORMALISED -> 3;
        };
        b.clear().putInt(0, 0x45494857).putInt(4, 1).putInt(8, 16384).putInt(12, mode)
                .putInt(20, dimensionMin).putFloat(24, offset).putFloat(28, scale);
        s.integers.clear();
        synchronized (tile) {
            b.putInt(16, tile.getMinHeight());
            tile.copyRawImageHeights(s.integers);
        }
        long ready = profiling ? System.nanoTime() : 0;
        if (!NativeSlices.convertHeightmapImage(b)) return false;
        long computed = profiling ? System.nanoTime() : 0;
        if (floating) {
            s.floats.clear();
            for (int row = 0; row < 128; row++) {
                s.floats.get(s.floatRow);
                raster.setSamples(x, y + row, 128, 1, 0, s.floatRow);
            }
        } else {
            s.integers.clear();
            for (int row = 0; row < 128; row++) {
                s.integers.get(s.integerRow);
                raster.setSamples(x, y + row, 128, 1, 0, s.integerRow);
            }
        }
        COMPLETED.incrementAndGet();
        if (profiling) {
            s.preparationNanos += ready - start;
            s.nativeNanos += computed - ready;
            s.applicationNanos += System.nanoTime() - computed;
        }
        return true;
    }
}
