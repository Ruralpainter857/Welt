package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.awt.image.*;

/** Bounded live raster analysis without a second image-sized sample plane. */
public final class ImageRangeAccess {
    private ImageRangeAccess() { }
    private static final java.util.Set<String> RASTERS = java.util.Set.of(
            "sun.awt.image.ByteInterleavedRaster", "sun.awt.image.ShortInterleavedRaster",
            "sun.awt.image.ByteBandedRaster", "sun.awt.image.ShortBandedRaster",
            "sun.awt.image.IntegerInterleavedRaster", "sun.awt.image.IntegerBandedRaster",
            "sun.awt.image.SunWritableRaster", "java.awt.image.WritableRaster", "java.awt.image.Raster");

    /** Image callers sample at zero origin and may have custom dimension overrides. */
    public static double[] range(BufferedImage image, int band, boolean unsigned, double initialLow, double initialHigh,
                                 double stopLow, double stopHigh) {
        return range(image, band, unsigned, initialLow, initialHigh, stopLow, stopHigh, false);
    }
    public static double[] range(BufferedImage image, int band, boolean unsigned, double initialLow, double initialHigh,
                                 double stopLow, double stopHigh, boolean exactStop) {
        if (image == null || image.getClass() != BufferedImage.class) return null;
        Raster raster = image.getRaster();
        if (raster.getMinX() != 0 || raster.getMinY() != 0 || raster.getWidth() != image.getWidth()
                || raster.getHeight() != image.getHeight()) return null;
        return range(raster, band, unsigned, initialLow, initialHigh, stopLow, stopHigh, exactStop);
    }
    public static double[] range(Raster raster, int band, boolean unsigned, double initialLow, double initialHigh,
                                 double stopLow, double stopHigh) {
        return range(raster, band, unsigned, initialLow, initialHigh, stopLow, stopHigh, false);
    }
    public static double[] range(Raster raster, int band, boolean unsigned, double initialLow, double initialHigh,
                                 double stopLow, double stopHigh, boolean exactStop) {
        if (!NativeSlices.isImageRangesAvailable() || raster == null) return null;
        SampleModel sample = raster.getSampleModel();
        if (sample.getClass() != ComponentSampleModel.class && sample.getClass() != PixelInterleavedSampleModel.class
                && sample.getClass() != BandedSampleModel.class) return null;
        // Custom rasters can override sampling independently of their SampleModel.
        String rasterClass = raster.getClass().getName();
        if (!RASTERS.contains(rasterClass)) return null;
        ComponentSampleModel model = (ComponentSampleModel) sample;
        if (band < 0 || band >= model.getNumBands()) return null;
        int width = raster.getWidth(), height = raster.getHeight();
        if (width < 1 || height < 1 || width > 65536 || height > 65536) return null;
        DataBuffer buffer = raster.getDataBuffer();
        if ((buffer.getDataType() == DataBuffer.TYPE_FLOAT || buffer.getDataType() == DataBuffer.TYPE_DOUBLE)
                && !Boolean.getBoolean("welt.native.floatingImageRanges")) return null;
        int bank = model.getBankIndices()[band];
        if (bank < 0 || bank >= buffer.getNumBanks()) return null;
        long offset = ((long) raster.getMinX() - raster.getSampleModelTranslateX()) * model.getPixelStride()
                + ((long) raster.getMinY() - raster.getSampleModelTranslateY()) * model.getScanlineStride()
                + model.getBandOffsets()[band] + buffer.getOffsets()[bank];
        int pixelStride = model.getPixelStride(), rowStride = model.getScanlineStride();
        long end = offset + (long) (width - 1) * pixelStride + (long) (height - 1) * rowStride;
        if (offset < 0 || pixelStride < 1 || rowStride < 1 || end > Integer.MAX_VALUE) return null;
        Object data; int kind, length;
        if (buffer.getClass() == DataBufferByte.class) { data=((DataBufferByte)buffer).getData(bank); kind=0; length=((byte[])data).length; }
        else if (buffer.getClass() == DataBufferUShort.class) { data=((DataBufferUShort)buffer).getData(bank); kind=1; length=((short[])data).length; }
        else if (buffer.getClass() == DataBufferShort.class) { data=((DataBufferShort)buffer).getData(bank); kind=2; length=((short[])data).length; }
        else if (buffer.getClass() == DataBufferInt.class) { data=((DataBufferInt)buffer).getData(bank); kind=3; length=((int[])data).length; }
        else if (buffer.getClass() == DataBufferFloat.class) { data=((DataBufferFloat)buffer).getData(bank); kind=4; length=((float[])data).length; }
        else if (buffer.getClass() == DataBufferDouble.class) { data=((DataBufferDouble)buffer).getData(bank); kind=5; length=((double[])data).length; }
        else return null;
        if (end >= length) return null;
        // Integer extrema can use contiguous rows only when stopping at the full type bounds.
        double theoreticalLow = kind == 2 && !unsigned ? Short.MIN_VALUE : kind == 3 && !unsigned ? Integer.MIN_VALUE : 0;
        double theoreticalHigh = kind == 0 ? 255 : kind == 1 ? 65535 : kind == 2 && !unsigned ? Short.MAX_VALUE
                : kind == 3 && !unsigned ? Integer.MAX_VALUE : 4294967295.0;
        boolean rows = kind < 4 && stopLow <= theoreticalLow && stopHigh >= theoreticalHigh;
        int[] layout = {kind, unsigned ? 1 : 0, (int) offset, pixelStride, rowStride, 0, 0, width, height, rows ? 1 : 0, exactStop ? 1 : 0};
        double[] result = {initialLow, initialHigh};
        int across = rows ? height : width, step = Math.max(1, 65536 / (rows ? width : height));
        for (int start = 0; start < across; start += step) {
            int count = Math.min(step, across - start);
            layout[5] = rows ? 0 : start; layout[6] = rows ? start : 0;
            layout[7] = rows ? width : count; layout[8] = rows ? count : height;
            int status = NativeSlices.scanImageRange(data, layout, stopLow, stopHigh, result);
            if (status < 0) return null;
            if (status == 1) break;
        }
        return result;
    }
}