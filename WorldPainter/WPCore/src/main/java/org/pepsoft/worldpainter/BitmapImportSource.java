package org.pepsoft.worldpainter;

import java.awt.image.DataBuffer;
import java.nio.ByteBuffer;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.BicubicHeightMap;
import org.pepsoft.worldpainter.heightMaps.TransformingHeightMap;

/** Bounded raster windows for sampling inside the existing import JNI transaction. */
final class BitmapImportSource {
    private static final int MAX_CELLS = 262144;
    private static final ThreadLocal<double[]> SAMPLES = new ThreadLocal<>();
    private final BitmapHeightMap bitmap;
    private final boolean integer, cubic, repeat;
    private final int offsetX, offsetY;
    private final double factorX, factorY, translateX, translateY, crossX, crossY;

    private BitmapImportSource(BitmapHeightMap bitmap, boolean integer, boolean cubic, boolean repeat,
                               int offsetX, int offsetY, float scaleX, float scaleY, float rotation) {
        this.bitmap = bitmap; this.integer = integer; this.cubic = cubic; this.repeat = repeat;
        this.offsetX = offsetX; this.offsetY = offsetY;
        // AffineTransform receives the result of the original float division.
        java.awt.geom.AffineTransform transform = new java.awt.geom.AffineTransform();
        if (scaleX != 1 || scaleY != 1) transform.scale(1 / scaleX, 1 / scaleY);
        if (offsetX != 0 || offsetY != 0) transform.translate(-offsetX, -offsetY);
        if (rotation != 0) transform.rotate(-rotation);
        factorX = transform.getScaleX(); factorY = transform.getScaleY();
        crossX = transform.getShearX(); crossY = transform.getShearY();
        translateX = transform.getTranslateX(); translateY = transform.getTranslateY();
    }

    static BitmapImportSource prepare(HeightMap map) {
        int ox = 0, oy = 0; float sx = 1, sy = 1, rotation = 0;
        if (map.getClass() == TransformingHeightMap.class) {
            TransformingHeightMap t = (TransformingHeightMap) map;
            if (!Float.isFinite(t.getRotation()) || !Float.isFinite(t.getScaleX()) || !Float.isFinite(t.getScaleY())
                    || t.getScaleX() == 0 || t.getScaleY() == 0) return null;
            ox = t.getOffsetX(); oy = t.getOffsetY(); sx = t.getScaleX(); sy = t.getScaleY(); rotation = t.getRotation(); map = t.getBaseHeightMap();
        }
        boolean cubic = false, repeat = false;
        if (map.getClass() == BicubicHeightMap.class) {
            BicubicHeightMap b = (BicubicHeightMap) map;
            cubic = true; repeat = b.isRepeat(); map = b.getHeightMap(0);
            java.awt.Rectangle extent = b.getExtent();
            if (extent != null && map.getClass() == BitmapHeightMap.class
                    && (extent.width != ((BitmapHeightMap) map).getWidth() || extent.height != ((BitmapHeightMap) map).getHeight())) return null;
        }
        if (map.getClass() != BitmapHeightMap.class) return null;
        BitmapHeightMap bitmap = (BitmapHeightMap) map;
        var raster = bitmap.getImage().getRaster();
        // Custom raster callbacks must retain their original sampling order.
        if (!raster.getClass().getName().startsWith("sun.awt.image.")
                || !raster.getSampleModel().getClass().getName().startsWith("java.awt.image.")
                || bitmap.getWidth() > 4194304 || bitmap.getHeight() > 4194304) return null;
        return new BitmapImportSource(bitmap, sx == 1 && sy == 1 && rotation == 0, cubic, repeat || bitmap.isRepeat(), ox, oy, sx, sy, rotation);
    }

    record Window(int x, int y, int width, int height) {
        int bytes() { return 112 + width * height * 8; }
    }

    Window window(int x, int y) {
        int[] xb = bounds(x, y, true), yb = bounds(x, y, false);
        if (xb == null || yb == null) return null;
        if (!repeat) {
            xb[0] = clamp(xb[0], bitmap.getWidth()); xb[1] = clamp(xb[1], bitmap.getWidth());
            yb[0] = clamp(yb[0], bitmap.getHeight()); yb[1] = clamp(yb[1], bitmap.getHeight());
        }
        int width = xb[1] - xb[0] + 1, height = yb[1] - yb[0] + 1;
        if (width <= 0 || height <= 0 || (long) width * height > MAX_CELLS) return null;
        return new Window(xb[0], yb[0], width, height);
    }

    private int[] bounds(int x, int y, boolean axisX) {
        if (integer) {
            long a = (long) (axisX ? x : y) - (axisX ? offsetX : offsetY), b = a + 127;
            if (a < -8388608 || b > 8388608) return null;
            return new int[] {(int) a, (int) b};
        }
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int dx : new int[] {0, 127}) for (int dy : new int[] {0, 127}) {
            float value = coordinate(x + dx, y + dy, axisX);
            if (!Float.isFinite(value) || value < -8388600 || value > 8388600) return null;
            min = Math.min(min, value); max = Math.max(max, value);
        }
        if (!cubic) return new int[] {(int) min, (int) max};
        float a = min - Math.signum(min) / 2, b = max - Math.signum(max) / 2;
        float lo = Math.min(a, b), hi = Math.max(a, b);
        // The half-pixel shift changes direction at zero; include both one-sided limits.
        if (min <= 0 && max >= 0) { lo = Math.min(lo, -.5f); hi = Math.max(hi, .5f); }
        return new int[] {(int) Math.floor(lo) - 1, (int) Math.floor(hi) + 2};
    }

    private float coordinate(int x, int y, boolean axisX) {
        double a = (float) (axisX ? x : y), b = (float) (axisX ? y : x);
        double scale = axisX ? factorX : factorY, cross = axisX ? crossX : crossY;
        boolean shear = crossX != 0 || crossY != 0, diagonal = factorX != 0 || factorY != 0;
        double value = shear ? (diagonal ? a * scale + b * cross : b * cross) : a * scale;
        if (translateX != 0 || translateY != 0) value += axisX ? translateX : translateY;
        return (float) value;
    }

    private static int clamp(int n, int extent) { return Math.max(0, Math.min(extent - 1, n)); }

    void write(ByteBuffer d, int base, Window w) {
        int area = w.width * w.height;
        double[] samples = SAMPLES.get();
        if (samples == null || samples.length < area) { samples = new double[area]; SAMPLES.set(samples); }
        var raster = bitmap.getImage().getRaster();
        if (!repeat) raster.getSamples(w.x, w.y, w.width, w.height, bitmap.getChannel(), samples);
        else {
            for (int row = 0; row < w.height; row++) {
                int sy = Math.floorMod(w.y + row, bitmap.getHeight()), column = 0;
                while (column < w.width) {
                    int sx = Math.floorMod(w.x + column, bitmap.getWidth());
                    int count = Math.min(w.width - column, bitmap.getWidth() - sx);
                    // Raster.getSamples has no destination offset; reuse one bounded row scratch.
                    double[] values = ROW.get();
                    if (values == null || values.length < count) { values = new double[count]; ROW.set(values); }
                    raster.getSamples(sx, sy, count, 1, bitmap.getChannel(), values);
                    System.arraycopy(values, 0, samples, row * w.width + column, count); column += count;
                }
            }
        }
        if (raster.getTransferType() == DataBuffer.TYPE_INT && !bitmap.isSigned())
            for (int i = 0; i < area; i++) if (samples[i] < 0) samples[i] += 4294967296.0;
        for (int i = 0; i < 112; i += 8) d.putLong(base + i, 0);
        d.putInt(base, 0x4d534257).putInt(base + 4, 1).putInt(base + 8, w.bytes())
                .putInt(base + 12, (integer ? 1 : 0) | (cubic ? 2 : 0) | (repeat ? 4 : 0))
                .putInt(base + 16, w.x).putInt(base + 20, w.y).putInt(base + 24, w.width).putInt(base + 28, w.height)
                .putInt(base + 32, bitmap.getWidth()).putInt(base + 36, bitmap.getHeight())
                .putInt(base + 40, offsetX).putInt(base + 44, offsetY).putDouble(base + 48, factorX).putDouble(base + 56, factorY)
                .putDouble(base + 64, translateX).putDouble(base + 72, translateY).putDouble(base + 80, bitmap.getMinHeight()).putDouble(base + 88, crossX).putDouble(base + 96, crossY);
        d.position(base + 112); d.slice().order(d.order()).asDoubleBuffer().put(samples, 0, area); d.position(0);
    }
    private static final ThreadLocal<double[]> ROW = new ThreadLocal<>();
}
