package org.pepsoft.worldpainter.heightMaps;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.HeightMap;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Worker-owned live bitmap patch; coordinates and interpolation stay together in Rust. */
public final class BitmapPreviewAccess {
    private static final int HEADER = 128, MAX_PATCH = 262144;
    private double[] samples = new double[0], row = new double[0];
    private final double[] matrix = new double[6];
    private final Point2D.Float corner = new Point2D.Float();
    private ByteBuffer frame;

    public boolean fill(HeightMap map, int originX, int originY, int shift, double[] output) {
        if (map == null || output == null || map.getClass() != TransformingHeightMap.class || shift < 0 || shift > 7 || output.length != 16384) return false;
        TransformingHeightMap transforming = (TransformingHeightMap) map;
        if (transforming.getScaleX() == 1 && transforming.getScaleY() == 1 && transforming.getRotation() == 0) return false;
        HeightMap base = transforming.getBaseHeightMap();
        if (base.getClass() != BicubicHeightMap.class) return false;
        BicubicHeightMap bicubic = (BicubicHeightMap) base;
        if (bicubic.getHeightMap(0).getClass() != BitmapHeightMap.class) return false;
        BitmapHeightMap bitmap = (BitmapHeightMap) bicubic.getHeightMap(0);
        if (bitmap.getWidth() > (1 << 24) || bitmap.getHeight() > (1 << 24)) return false;
        var raster = bitmap.getImage().getRaster();
        // Custom raster callbacks retain their original per-sample access order.
        if (!raster.getClass().getName().startsWith("sun.awt.image.")
                || !raster.getSampleModel().getClass().getName().startsWith("java.awt.image.")) return false;
        java.awt.Rectangle extent = bicubic.getExtent();
        if (extent != null && (extent.width != bitmap.getWidth() || extent.height != bitmap.getHeight())) return false;
        AffineTransform transform = new AffineTransform();
        transform.scale(1 / transforming.getScaleX(), 1 / transforming.getScaleY());
        transform.translate(-transforming.getOffsetX(), -transforming.getOffsetY());
        transform.rotate(-transforming.getRotation());
        transform.getMatrix(matrix);
        for (double value : matrix) if (!Double.isFinite(value)) return false;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (int cy : new int[] {0, 127}) for (int cx : new int[] {0, 127}) {
            corner.setLocation(originX + (cx << shift), originY + (cy << shift));
            transform.transform(corner, corner);
            if (!Float.isFinite(corner.x) || !Float.isFinite(corner.y)
                    || Math.abs(corner.x) >= (1 << 24) - 4 || Math.abs(corner.y) >= (1 << 24) - 4) return false;
            minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y);
            maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y);
        }
        int patchX = (int) Math.floor(minX) - 3, patchY = (int) Math.floor(minY) - 3;
        int width = (int) Math.floor(maxX) - patchX + 4, height = (int) Math.floor(maxY) - patchY + 4;
        long area = (long) width * height;
        if (width <= 0 || height <= 0 || area > MAX_PATCH) return false;
        int cells = (int) area, outputOffset = HEADER + cells * 8, length = outputOffset + output.length * 8;
        if (samples.length < cells) samples = new double[cells];
        if (row.length < width) row = new double[width];
        if (!bitmap.fillBicubicPatch(patchX, patchY, width, height, bicubic.isRepeat() || bitmap.isRepeat(), samples, row)) return false;
        for (int i = 0; i < cells; i++) if (!Double.isFinite(samples[i])) return false;
        if (frame == null || frame.capacity() < length) frame = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        frame.clear().limit(length);
        for (int i = 0; i < HEADER; i += 8) frame.putLong(i, 0L);
        frame.putInt(0, 0x31504257).putInt(4, 1).putInt(8, originX).putInt(12, originY).putInt(16, shift)
                .putInt(20, 128).putInt(24, 128).putInt(28, patchX).putInt(32, patchY)
                .putInt(36, width).putInt(40, height).putInt(48, HEADER).putInt(52, outputOffset);
        for (int i = 0; i < 6; i++) frame.putDouble(64 + i * 8, matrix[i]);
        frame.position(HEADER); frame.slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().put(samples, 0, cells);
        frame.position(0);
        if (!NativeSlices.fillBitmapPreview(frame)) return false;
        frame.position(outputOffset); frame.slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().get(output);
        return true;
    }
}
