/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.heightMaps;

import org.pepsoft.util.IconUtils;
import org.pepsoft.util.MathUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.util.Arrays;

import static java.awt.image.DataBuffer.*;

/**
 *
 * @author SchmitzP
 */
public final class BitmapHeightMap extends AbstractHeightMap {
    private BitmapHeightMap(String name, BufferedImage image, int channel, File imageFile, boolean repeat) {
        super(name);
        this.image = image;
        this.channel = channel;
        this.imageFile = imageFile;
        this.repeat = repeat;
        raster = image.getRaster();
        width = image.getWidth();
        height = image.getHeight();
        extent = repeat ? null : new Rectangle(0, 0, width, height);
        bitDepth = raster.getSampleModel().getSampleSize(0);
        final int transferType = raster.getTransferType();
        switch (transferType) {
            case TYPE_FLOAT:
                minHeight = -Float.MAX_VALUE;
                maxHeight = Float.MAX_VALUE;
                floatingPoint = true;
                signed = true;
                break;
            case TYPE_DOUBLE:
                minHeight = -Double.MAX_VALUE;
                maxHeight = Double.MAX_VALUE;
                floatingPoint = true;
                signed = true;
                break;
            case TYPE_SHORT:
                minHeight = -(1L << (bitDepth - 1));
                maxHeight = (1L << (bitDepth - 1)) - 1L;
                floatingPoint = false;
                signed = true;
                break;
            default:
                minHeight = 0;
                maxHeight = (1L << bitDepth) - 1L;
                floatingPoint = false;
                signed = false;
                break;
        }
        hasAlpha = image.getColorModel().hasAlpha();
    }

    public BufferedImage getImage() {
        return image;
    }

    public int getChannel() {
        return channel;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Bulk-reads a rectangle using the same clipping and repeat rules as {@link #getHeight(int, int)}. */
    public boolean fillSamples(int x, int y, int sampleWidth, int sampleHeight,
                               double[] samples, double[] rowSamples) {
        return fillSamples(x, y, sampleWidth, sampleHeight, samples, rowSamples, repeat);
    }

    /** Bulk-reads a rectangle with wrapping even when this bitmap itself is non-repeating. */
    public boolean fillRepeatedSamples(int x, int y, int sampleWidth, int sampleHeight,
                                       double[] samples, double[] rowSamples) {
        return fillSamples(x, y, sampleWidth, sampleHeight, samples, rowSamples, true);
    }

    private boolean fillSamples(int x, int y, int sampleWidth, int sampleHeight,
                                double[] samples, double[] rowSamples, boolean repeatCoordinates) {
        if ((sampleWidth <= 0) || (sampleHeight <= 0) || (samples == null)
                || ((long) sampleWidth * sampleHeight != samples.length)) {
            return false;
        }
        if ((x >= 0) && (y >= 0)
                && ((long) x + sampleWidth <= width) && ((long) y + sampleHeight <= height)) {
            raster.getSamples(x, y, sampleWidth, sampleHeight, channel, samples);
            return true;
        }
        if ((rowSamples == null) || (rowSamples.length < sampleWidth)) {
            return false;
        }
        Arrays.fill(samples, minHeight);
        for (int row = 0; row < sampleHeight; row++) {
            final int worldY = y + row;
            final int sourceY;
            if (repeatCoordinates) {
                sourceY = MathUtils.mod(worldY, height);
            } else {
                if ((worldY < 0) || (worldY >= height)) {
                    continue;
                }
                sourceY = worldY;
            }
            final int outputRow = row * sampleWidth;
            if (repeatCoordinates && (width <= sampleWidth)) {
                raster.getSamples(0, sourceY, width, 1, channel, rowSamples);
                for (int column = 0; column < sampleWidth; column++) {
                    samples[outputRow + column] = rowSamples[MathUtils.mod(x + column, width)];
                }
                continue;
            }
            int column = 0;
            while (column < sampleWidth) {
                final int worldX = x + column;
                final int sourceX;
                final int runLength;
                if (repeatCoordinates) {
                    sourceX = MathUtils.mod(worldX, width);
                    final int beforeOverflow = (int) Math.min(Integer.MAX_VALUE - (long) worldX + 1L,
                            Integer.MAX_VALUE);
                    runLength = Math.min(Math.min(sampleWidth - column, width - sourceX), beforeOverflow);
                } else if (worldX < 0) {
                    column += (int) Math.min(sampleWidth - column, -(long) worldX);
                    continue;
                } else if (worldX >= width) {
                    break;
                } else {
                    sourceX = worldX;
                    runLength = Math.min(sampleWidth - column, width - sourceX);
                }
                raster.getSamples(sourceX, sourceY, runLength, 1, channel, rowSamples);
                System.arraycopy(rowSamples, 0, samples, outputRow + column, runLength);
                column += runLength;
            }
        }
        return true;
    }

    /**
     * Get the theoretical minimum value as determined by the image format, regardless of the actual minimum value.
     */
    public double getMinHeight() {
        return minHeight;
    }

    /**
     * Get the theoretical maximum value as determined by the image format, regardless of the actual maximum value.
     */
    public double getMaxHeight() {
        return maxHeight;
    }

    public boolean isFloatingPoint() {
        return floatingPoint;
    }

    public int getBitDepth() {
        return bitDepth;
    }

    public boolean hasAlpha() {
        return hasAlpha;
    }

    public boolean isSigned() {
        return signed;
    }

    // HeightMap
    
    @Override
    public double getHeight(int x, int y) {
        if (repeat) {
            return getSample(MathUtils.mod(x, width), MathUtils.mod(y, height));
        } else if (extent.contains(x, y)) {
            return getSample(x, y);
        } else {
            return minHeight;
        }
    }

    @Override
    public double getHeight(float x, float y) {
        return getHeight((int) x, (int) y);
    }

    @Override
    public Rectangle getExtent() {
        return extent;
    }

    @Override
    public int getColour(int x, int y) {
        if (repeat) {
            return image.getRGB(MathUtils.mod(x, width), MathUtils.mod(y, height));
        } else if (extent.contains(x, y)) {
            return image.getRGB(x, y);
        } else {
            return 0;
        }
    }

    @Override
    public Icon getIcon() {
        return ICON_BITMAP_HEIGHTMAP;
    }

    /**
     * Get the <em>actual</em> minimum and maximum values contained in the image data.
     */
    @Override
    public double[] getRange() {
        if (range == null) {
            double imageLowValue = Double.MAX_VALUE, imageHighValue = -Double.MAX_VALUE;
            outer:
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    final double value = getSample(x, y);
                    if (value < imageLowValue) {
                        imageLowValue = value;
                    }
                    if (value > imageHighValue) {
                        imageHighValue = value;
                    }
                    if ((imageLowValue <= minHeight) && (imageHighValue >= maxHeight)) {
                        // No point in looking any further!
                        break outer;
                    }
                }
            }
            range = new double[] { imageLowValue, imageHighValue };
        }
        return range;
    }

    public File getImageFile() {
        return imageFile;
    }

    public static BitmapHeightMapBuilder build() {
        return new BitmapHeightMapBuilder();
    }

    private double getSample(int x, int y) {
        if (floatingPoint) {
            return raster.getSampleDouble(x, y, channel);
        } else {
            return signed ? raster.getSample(x, y, channel) : (raster.getSample(x, y, channel) & 0xffffffffL);
        }
    }

    private final BufferedImage image;
    private final int channel, width, height, bitDepth;
    private final Raster raster;
    private final Rectangle extent;
    private final File imageFile;
    private final boolean repeat, floatingPoint, hasAlpha, signed;
    private final double minHeight, maxHeight;
    private double[] range;

    private static final long serialVersionUID = 1L;
    private static final Icon ICON_BITMAP_HEIGHTMAP = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/height_map.png");

    public static class BitmapHeightMapBuilder {
        public BitmapHeightMapBuilder withName(String name) {
            this.name = name;
            return this;
        }

        public BitmapHeightMapBuilder withChannel(int channel) {
            this.channel = channel;
            return this;
        }

        public BitmapHeightMapBuilder withImage(BufferedImage image) {
            this.image = image;
            return this;
        }

        public BitmapHeightMapBuilder withFile(File file) {
            this.file = file;
            return this;
        }

        public BitmapHeightMapBuilder withRepeat(boolean repeat) {
            this.repeat = repeat;
            return this;
        }

        public BitmapHeightMap now() {
            return new BitmapHeightMap(name, image, channel, file, repeat);
        }

        private String name;
        private BufferedImage image;
        private int channel;
        private File file;
        private boolean repeat;
    }
}
