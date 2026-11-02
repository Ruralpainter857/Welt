package org.pepsoft.worldpainter.exporting;

import com.google.common.collect.ImmutableList;
import com.twelvemonkeys.imageio.util.ImageTypeSpecifiers;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.HeightMapImageAccess;

import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.util.concurrent.atomic.AtomicLong;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import static java.awt.image.DataBuffer.TYPE_FLOAT;
import static java.awt.image.DataBuffer.TYPE_INT;
import static java.util.Collections.singletonList;
import static javax.imageio.ImageWriteParam.MODE_EXPLICIT;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.exporting.HeightMapExporter.Format.FLOAT_NORMALISED;
import static org.pepsoft.worldpainter.exporting.HeightMapExporter.Format.INTEGER_LOW_RESOLUTION;

public class HeightMapExporter {
    public HeightMapExporter(Dimension dimension, Format format) {
        this.dimension = dimension;
        this.format = format;
        minHeight = dimension.getMinHeight();
        switch (format) {
            case INTEGER_HIGH_RESOLUTION:
                intHighestHeight = dimension.getHighestIntHeight();
                bitsRequired = (int) Math.ceil(Math.log(((intHighestHeight - minHeight + 1) << 8) - 1) / Math.log(2));
                floatLowestHeight = floatHighestHeight = -Float.MAX_VALUE;
                break;
            case INTEGER_LOW_RESOLUTION:
                intHighestHeight = dimension.getHighestIntHeight();
                bitsRequired = (int) Math.ceil(Math.log(intHighestHeight - minHeight) / Math.log(2));
                floatLowestHeight = floatHighestHeight = -Float.MAX_VALUE;
                break;
            case FLOAT_NORMALISED:
            case FLOAT_ONE_TO_ONE:
                final float[] range = dimension.getHeightRange();
                floatLowestHeight = range[0];
                floatHighestHeight = range[1];
                bitsRequired = 32;
                intHighestHeight = Integer.MIN_VALUE;
                break;
            default:
                throw new InternalError();
        }
    }

    public List<String> getSupportedFileExtensions() {
        if (bitsRequired <= 16) {
            return ImmutableList.of("png", "tiff");
        } else {
            return singletonList("tiff");
        }
    }

    public String getDefaultFilename() {
        final String defaultExtension = getSupportedFileExtensions().get(0);
        final StringBuilder sb = new StringBuilder();
        sb.append(dimension.getWorld().getName().replaceAll("\\s", "").toLowerCase());
        if (dimension.getAnchor().dim != DIM_NORMAL) {
            sb.append('_');
            sb.append(dimension.getName().replaceAll("\\s", "").toLowerCase());
        }
        switch (format) {
            case FLOAT_NORMALISED:
                sb.append("_normalised-");
                break;
            case INTEGER_HIGH_RESOLUTION:
                sb.append("_high-res-");
                break;
            default:
                sb.append('_');
                break;
        }
        sb.append("heightmap.");
        sb.append(defaultExtension);
        return sb.toString();
    }

    public boolean exportToFile(File file) {
        final String type = file.getName().substring(file.getName().lastIndexOf('.') + 1).toUpperCase();
        try {
            final ImageTypeSpecifier imageType;
            ImageWriter writer;
            ImageWriteParam params;
            final float scale, offset;
            final double formatMax, dimensionMax;
            // TODO fail gracefully if the world is too large because the data buffer would overflow
            switch (format) {
                case INTEGER_LOW_RESOLUTION:
                case INTEGER_HIGH_RESOLUTION:
                    if (bitsRequired > 16) {
                        imageType = ImageTypeSpecifiers.createGrayscale(32, TYPE_INT);
                        final Iterator<ImageWriter> writers = ImageIO.getImageWriters(imageType, type);
                        if (writers.hasNext()) {
                            writer = writers.next();
                            params = writer.getDefaultWriteParam();
                            params.setCompressionMode(MODE_EXPLICIT);
                            params.setCompressionType("LZW");
                            params.setCompressionQuality(0f);
                            formatDescription = "in 32-bit unsigned integer grayscale compressed " + type + " format.";
                            formatMax = Math.pow(2.0, 32.0);
                            dimensionMax = (format == INTEGER_LOW_RESOLUTION) ? (intHighestHeight - minHeight) : ((intHighestHeight - minHeight) << 8);
                        } else {
                            return false;
                        }
                    } else {
                        imageType = ImageTypeSpecifier.createFromBufferedImageType((bitsRequired <= 8) ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_USHORT_GRAY);
                        final Iterator<ImageWriter> writers = ImageIO.getImageWriters(imageType, type);
                        if (writers.hasNext()) {
                            writer = writers.next();
                            params = writer.getDefaultWriteParam();
                            formatDescription = ((bitsRequired <= 8) ? "in 8-bit" : "in 16-bit") + " unsigned integer grayscale " + type + " format.";
                            formatMax = Math.pow(2.0, (bitsRequired <= 8) ? 8.0 : 16.0);
                            dimensionMax = (format == INTEGER_LOW_RESOLUTION) ? (intHighestHeight - minHeight) : ((intHighestHeight - minHeight) << 8);
                        } else {
                            return false;
                        }
                    }
                    scale = offset = -Float.MAX_VALUE;
                    break;
                case FLOAT_NORMALISED:
                case FLOAT_ONE_TO_ONE:
                    imageType = ImageTypeSpecifiers.createGrayscale(32, TYPE_FLOAT);
                    final Iterator<ImageWriter> writers = ImageIO.getImageWriters(imageType, type);
                    final ImageWriter floatingPointWriter = selectFloatingPointWriter(writers);
                    if (floatingPointWriter != null) {
                        writer = floatingPointWriter;
                        params = writer.getDefaultWriteParam();
                        params.setCompressionMode(MODE_EXPLICIT);
                        params.setCompressionType("LZW");
                        params.setCompressionQuality(0f);
                        formatDescription = "in " + ((format == FLOAT_NORMALISED) ? "normalised " : "") + " floating point grayscale compressed " + type + " format.";
                    } else {
                        return false;
                    }
                    scale = floatHighestHeight - floatLowestHeight;
                    offset = floatLowestHeight;
                    formatMax = (format == FLOAT_NORMALISED) ? 1.0 : floatHighestHeight;
                    dimensionMax = (format == FLOAT_NORMALISED) ? 1.0 : floatHighestHeight;
                    break;
                default:
                    throw new InternalError();
            }
            if (dimensionMax / formatMax < 0.2) {
                formatDescription += "\n\n" +
                        "PLEASE NOTE: this height map will appear very dark when displayed as\n" +
                        "an image, because the exported values are very small compared to the\n" +
                        "theoretical range of the image format.";
            }
            HeightMapRenderedImage streamed = HeightMapRenderedImage.create(dimension, imageType, writer,
                    format, minHeight, offset, scale);
            // TwelveMonkeys LZW output is not valid when this source exposes several tile rows.
            // Use the standard TIFF encoder for streamed unsigned 32-bit samples, preserving the raw sample format.
            if (streamed != null && bitsRequired > 16 && format != FLOAT_NORMALISED && format != Format.FLOAT_ONE_TO_ONE
                    && writer.getClass().getName().equals("com.twelvemonkeys.imageio.plugins.tiff.TIFFImageWriter")) {
                ImageWriter replacement = standardTiffWriter(imageType);
                streamed.close();
                if (replacement == null) streamed = null;
                else {
                    writer.dispose(); writer = replacement;
                    params = writer.getDefaultWriteParam(); params.setCompressionMode(MODE_EXPLICIT);
                    params.setCompressionType("LZW"); params.setCompressionQuality(0f);
                    streamed = HeightMapRenderedImage.create(dimension, imageType, writer, format, minHeight, offset, scale);
                }
            }
            try {
                final RenderedImage image;
                if (streamed != null) image = streamed;
                else {
                    BufferedImage dense = imageType.createBufferedImage(dimension.getWidth() * TILE_SIZE, dimension.getHeight() * TILE_SIZE);
                    for (Tile tile : dimension.getTiles()) {
                        int x = (tile.getX() - dimension.getLowestX()) * TILE_SIZE;
                        int y = (tile.getY() - dimension.getLowestY()) * TILE_SIZE;
                        HeightMapImageAccess.writeTileSamples(tile, dense.getRaster(), x, y, format, minHeight, offset, scale);
                    }
                    image = dense;
                }
                try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
                    writer.setOutput(out);
                    IIOMetadata metadata = null;
                    if (streamed != null && imageType.getSampleModel().getDataType() == TYPE_INT
                            && writer.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageWriter")) {
                        TIFFDirectory directory = TIFFDirectory.createFromMetadata(writer.getDefaultImageMetadata(imageType, params));
                        directory.addTIFFField(new TIFFField(BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_SAMPLE_FORMAT),
                                TIFFTag.TIFF_SHORT, 1, new char[]{BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER}));
                        metadata = directory.getAsMetadata();
                    }
                    writer.write(null, new IIOImage(image, null, metadata), params);
                    if (streamed != null) STREAMED_EXPORTS.incrementAndGet();
                    return true;
                }
            } finally {
                try { writer.dispose(); }
                finally { if (streamed != null) streamed.close(); }
            }
        } catch (IOException e) {
            throw new RuntimeException("I/O error while exporting image", e);
        }
    }

    private static ImageWriter standardTiffWriter(ImageTypeSpecifier type) {
        Iterator<ImageWriter> writers = ImageIO.getImageWriters(type, "TIFF");
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            if (candidate.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageWriter")) return candidate;
            candidate.dispose();
        }
        return null;
    }
    private static ImageWriter selectFloatingPointWriter(Iterator<ImageWriter> writers) {
        while (writers.hasNext()) {
            ImageWriter candidate = writers.next();
            // The bundled TwelveMonkeys writer advertises float images but rejects TYPE_FLOAT
            // in writeImageData. Keep other providers eligible, including the JDK TIFF writer.
            if (candidate.getClass().getName().equals("com.twelvemonkeys.imageio.plugins.tiff.TIFFImageWriter")) {
                candidate.dispose();
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** Completed bounded-image writes, for parity and whole-operation benchmarks. */
    public static long completedStreamingExports() { return STREAMED_EXPORTS.get(); }
    private static final AtomicLong STREAMED_EXPORTS = new AtomicLong();

    public String getFormatDescription() {
        return formatDescription;
    }

    private final Dimension dimension;
    private final Format format;
    private final int minHeight, intHighestHeight, bitsRequired;
    private final float floatLowestHeight, floatHighestHeight;
    private String formatDescription;

    public enum Format { INTEGER_LOW_RESOLUTION, INTEGER_HIGH_RESOLUTION, FLOAT_NORMALISED, FLOAT_ONE_TO_ONE}
}