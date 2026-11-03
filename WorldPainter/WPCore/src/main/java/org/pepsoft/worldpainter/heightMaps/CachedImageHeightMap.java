package org.pepsoft.worldpainter.heightMaps;

import org.pepsoft.util.IconUtils;
import org.pepsoft.util.MathUtils;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import java.awt.*;
import java.awt.image.ComponentSampleModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.awt.image.DataBuffer.*;

/**
 * A height map of a single band greyscale TIFF image with bounded memory use, with the same heights as a
 * {@link BitmapHeightMap} of the image as {@link ImageIO#read(File)} would load it.
 *
 * <p>{@link #capture(File, String)} decodes the image once, in full width bands of bounded size, with the same reader
 * that {@code ImageIO.read} would use, and stores the exact samples in a private cache file in the temporary directory,
 * in blocks of 128x128 samples. The range of the heights is computed during the capture. The original file is not read
 * again afterwards, so the height map is a stable snapshot, like an image loaded in memory. Heights are then read from
 * the cache file through a bounded cache of decoded blocks.
 *
 * <p>All reads return <em>heights</em>, as {@link BitmapHeightMap#getHeight(int, int)} does: 32 bit integer samples
 * are unsigned (0 to 2<sup>32</sup> - 1). Note that {@link BitmapHeightMap#fillSamples(int, int, int, int, double[],
 * double[])} instead returns the raw raster samples, which are signed for 32 bit integers; {@link #fillHeights(int,
 * int, int, int, double[], double[])} is named differently for that reason.
 *
 * <p>The captured height map owns its cache file and deletes it when it is {@link #close() closed}; consumers only
 * borrow it. Clones (see {@link AbstractHeightMap#clone()}) share the cache and its state but do not own it: closing a
 * clone does nothing, and once the owner is closed, all of them read the lowest value of the type. It cannot be
 * serialised.
 */
public final class CachedImageHeightMap extends AbstractHeightMap implements Closeable {
    private CachedImageHeightMap(String name, File imageFile, int width, int height, int bitDepth, int transferType, Path cacheFile) {
        super(name);
        this.imageFile = imageFile;
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.transferType = transferType;
        extent = new Rectangle(0, 0, width, height);
        bytesPerSample = bytesPerSample(transferType);
        blocksX = (width + BLOCK - 1) / BLOCK;
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
        storage = new Storage(cacheFile);
        owner = true;
    }

    /**
     * Capture a greyscale TIFF image into a new cached height map.
     *
     * @return The height map, or {@code null} if the file is not a supported image, in which case the caller should
     * load it the usual way. Nothing is left behind in that case.
     * @throws IOException If the image could not be read or the cache could not be written. The cache file is deleted.
     */
    public static CachedImageHeightMap capture(File file, String name) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            if (in == null) {
                return null;
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (! readers.hasNext()) {
                return null;
            }
            // The reader ImageIO.read() would use
            final ImageReader reader = readers.next();
            try {
                final String format = reader.getFormatName();
                if ((format == null) || (! format.toLowerCase().startsWith("tif"))) {
                    return null;
                }
                // Not forward only, since the first image is read again for each band. The number of images is not
                // queried: that may scan the whole file, and is illegal for forward only input
                reader.setInput(in, false, true);
                final ImageTypeSpecifier type;
                final int width, height;
                try {
                    final Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
                    if ((types == null) || (! types.hasNext())) {
                        return null;
                    }
                    type = types.next();
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                } catch (IndexOutOfBoundsException e) {
                    // No first image
                    return null;
                }
                final SampleModel sampleModel = type.getSampleModel();
                final int transferType = sampleModel.getTransferType();
                if ((sampleModel.getNumBands() != 1) || (! (sampleModel instanceof ComponentSampleModel))
                        || (type.getColorModel() == null) || (type.getColorModel() instanceof IndexColorModel)
                        || type.getColorModel().isAlphaPremultiplied() || (bytesPerSample(transferType) == 0)) {
                    return null;
                }
                if ((width <= 0) || (height <= 0) || (width > MAX_SIDE) || (height > MAX_SIDE)) {
                    return null;
                }
                // Only the reader whose region reads have been verified, and only layouts it decodes with bounded work
                if ((! reader.getClass().getName().equals(VERIFIED_READER))
                        || (! isBandable(reader, height, bandRows(width, height, bytesPerSample(transferType))))) {
                    return null;
                }
                final Path cacheFile = Files.createTempFile("welt-heightmap-", ".cache");
                cacheFile.toFile().deleteOnExit();
                boolean success = false;
                CachedImageHeightMap heightMap = null;
                try {
                    heightMap = new CachedImageHeightMap(name, file, width, height, sampleModel.getSampleSize(0), transferType, cacheFile);
                    heightMap.write(reader);
                    success = true;
                    return heightMap;
                } finally {
                    if (! success) {
                        if (heightMap != null) {
                            heightMap.close();
                        } else {
                            Files.deleteIfExists(cacheFile);
                        }
                    }
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * The number of rows of a band: at most {@link #MAX_BAND_BYTES} of samples (but at least one row), as many whole
     * rows of blocks as fit, since each band read has a significant fixed cost, or for very wide images a power of two
     * rows dividing a block.
     */
    private static int bandRows(int width, int height, int bytesPerSample) {
        final long blockRowBytes = (long) width * BLOCK * bytesPerSample;
        if (blockRowBytes <= MAX_BAND_BYTES) {
            return (int) Math.min((long) BLOCK * (MAX_BAND_BYTES / blockRowBytes), (height + BLOCK - 1) / BLOCK * (long) BLOCK);
        } else {
            int bandRows = BLOCK;
            while ((bandRows > 1) && ((long) width * bandRows * bytesPerSample > MAX_BAND_BYTES)) {
                bandRows /= 2;
            }
            return bandRows;
        }
    }

    /**
     * Whether the reader decodes a band without decoding more than the strips or tiles which intersect it, judging by
     * the layout of the TIFF file: uncompressed data is read by seeking, but each compressed strip or tile must be at
     * most as high as a band. Otherwise, as for a large image stored as one compressed strip, every band decodes the
     * strip from its start again, which is bounded in memory but quadratic in time. If the layout cannot be
     * determined, the image is not bandable either.
     */
    private static boolean isBandable(ImageReader reader, int height, int bandRows) {
        final IIOMetadata metadata;
        try {
            metadata = reader.getImageMetadata(0);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if ((metadata == null) || (! TIFF_METADATA_FORMAT.equals(metadata.getNativeMetadataFormatName()))) {
            return false;
        }
        final Node tree = metadata.getAsTree(TIFF_METADATA_FORMAT);
        final long compression = tiffField(tree, 259);
        if ((compression == -1) || (compression == 1)) {
            return true;
        }
        final long tileLength = tiffField(tree, 323), rowsPerStrip = tiffField(tree, 278);
        final long unitRows = (tileLength > 0) ? tileLength : ((rowsPerStrip > 0) ? Math.min(rowsPerStrip, height) : height);
        return unitRows <= bandRows;
    }

    /** The first value of a numeric field of a TIFF image metadata tree, or -1 if it is absent. */
    private static long tiffField(Node node, int number) {
        if ("TIFFField".equals(node.getNodeName())) {
            final Node attribute = (node.getAttributes() != null) ? node.getAttributes().getNamedItem("number") : null;
            if ((attribute != null) && String.valueOf(number).equals(attribute.getNodeValue())) {
                // TIFFField > TIFFShorts/TIFFLongs > TIFFShort/TIFFLong value="..."
                final Node values = node.getFirstChild(), value = (values != null) ? values.getFirstChild() : null;
                final Node valueAttribute = ((value != null) && (value.getAttributes() != null)) ? value.getAttributes().getNamedItem("value") : null;
                if (valueAttribute != null) {
                    try {
                        return Long.parseLong(valueAttribute.getNodeValue().trim());
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
                return -1;
            }
            return -1;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            final long value = tiffField(child, number);
            if (value != -1) {
                return value;
            }
        }
        return -1;
    }

    /** Whether bounded TIFF import is enabled ({@code -Dwelt.import.boundedTiff=true}). */
    public static boolean isEnabled() {
        return Boolean.getBoolean("welt.import.boundedTiff");
    }

    public File getImageFile() {
        return imageFile;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public double getMinHeight() {
        return minHeight;
    }

    public double getMaxHeight() {
        return maxHeight;
    }

    public boolean isFloatingPoint() {
        return floatingPoint;
    }

    public int getBitDepth() {
        return bitDepth;
    }

    public boolean isSigned() {
        return signed;
    }

    /** The size of the private cache file, in bytes. */
    public long getCacheFileSize() {
        return (long) blocksX * ((height + BLOCK - 1) / BLOCK) * BLOCK * BLOCK * bytesPerSample;
    }

    /**
     * Fill {@code heights} (row major) with the heights of a region, outside the image the lowest value of the type.
     * The layout is that of {@link BitmapHeightMap#fillSamples(int, int, int, int, double[], double[])} of a non
     * repeating bitmap, but the values are heights (unsigned for 32 bit integers), not raw samples.
     */
    public boolean fillHeights(int x, int y, int sampleWidth, int sampleHeight, double[] heights, double[] rowSamples) {
        return fillHeights(x, y, sampleWidth, sampleHeight, heights, rowSamples, false);
    }

    /** Like {@link #fillHeights(int, int, int, int, double[], double[])}, with the image repeated in both directions. */
    public boolean fillRepeatedHeights(int x, int y, int sampleWidth, int sampleHeight, double[] heights, double[] rowSamples) {
        return fillHeights(x, y, sampleWidth, sampleHeight, heights, rowSamples, true);
    }

    /** Read the heights of an in-bounds region into {@code heights} at {@code offset}, with the given row stride. */
    public void readRegion(int x, int y, int regionWidth, int regionHeight, double[] heights, int offset, int stride) {
        if ((x < 0) || (y < 0) || (regionWidth < 0) || (regionHeight < 0) || ((long) x + regionWidth > width) || ((long) y + regionHeight > height)) {
            throw new IndexOutOfBoundsException("Region " + x + "," + y + " " + regionWidth + "x" + regionHeight + " outside " + width + "x" + height);
        }
        if ((regionWidth == 0) || (regionHeight == 0)) {
            return;
        }
        for (int blockY = y / BLOCK; blockY <= (y + regionHeight - 1) / BLOCK; blockY++) {
            final int y1 = Math.max(y, blockY * BLOCK), y2 = Math.min(y + regionHeight, (blockY + 1) * BLOCK);
            for (int blockX = x / BLOCK; blockX <= (x + regionWidth - 1) / BLOCK; blockX++) {
                final int x1 = Math.max(x, blockX * BLOCK), x2 = Math.min(x + regionWidth, (blockX + 1) * BLOCK);
                synchronized (storage) {
                    final double[] block = block(blockX, blockY);
                    for (int row = y1; row < y2; row++) {
                        System.arraycopy(block, (row - blockY * BLOCK) * BLOCK + (x1 - blockX * BLOCK), heights, offset + (row - y) * stride + (x1 - x), x2 - x1);
                    }
                }
            }
        }
    }

    /** Close the cache and delete its file, if this is the captured height map; does nothing for a clone. */
    @Override
    public void close() {
        if (owner) {
            storage.close();
        }
    }

    /** Whether the cache has been closed by its owner. */
    public boolean isClosed() {
        return storage.isClosed();
    }

    // HeightMap

    @Override
    public double getHeight(int x, int y) {
        if ((x < 0) || (y < 0) || (x >= width) || (y >= height)) {
            return minHeight;
        }
        synchronized (storage) {
            return block(x / BLOCK, y / BLOCK)[(y % BLOCK) * BLOCK + (x % BLOCK)];
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
    public Icon getIcon() {
        return ICON;
    }

    @Override
    public double[] getRange() {
        return range.clone();
    }

    @Override
    public CachedImageHeightMap clone() {
        final CachedImageHeightMap clone = (CachedImageHeightMap) super.clone();
        clone.owner = false;
        return clone;
    }

    private boolean fillHeights(int x, int y, int sampleWidth, int sampleHeight, double[] heights, double[] rowSamples, boolean repeatCoordinates) {
        if ((sampleWidth <= 0) || (sampleHeight <= 0) || (heights == null) || ((long) sampleWidth * sampleHeight != heights.length)) {
            return false;
        }
        if ((x >= 0) && (y >= 0) && ((long) x + sampleWidth <= width) && ((long) y + sampleHeight <= height)) {
            readRegion(x, y, sampleWidth, sampleHeight, heights, 0, sampleWidth);
            return true;
        }
        if ((rowSamples == null) || (rowSamples.length < sampleWidth)) {
            return false;
        }
        Arrays.fill(heights, minHeight);
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
            int column = 0;
            while (column < sampleWidth) {
                final int worldX = x + column;
                final int sourceX, runLength;
                if (repeatCoordinates) {
                    sourceX = MathUtils.mod(worldX, width);
                    final int beforeOverflow = (int) Math.min(Integer.MAX_VALUE - (long) worldX + 1L, Integer.MAX_VALUE);
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
                readRegion(sourceX, sourceY, runLength, 1, heights, row * sampleWidth + column, runLength);
                column += runLength;
            }
        }
        return true;
    }

    /**
     * Decode the image band by band (see {@link #bandRows(int, int, int)}) into the cache file and compute the range.
     * Each band writes its rows of every block it covers. Samples are transferred in bulk in their own type.
     */
    private void write(ImageReader reader) throws IOException {
        final int bandRows = bandRows(width, height, bytesPerSample);
        final int sliceRows = Math.min(bandRows, BLOCK);
        final Range range = new Range(floatingPoint, minHeight, maxHeight);
        try (FileChannel out = FileChannel.open(storage.cacheFile, StandardOpenOption.WRITE)) {
            final ByteBuffer chunk = ByteBuffer.allocate(sliceRows * BLOCK * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
            final byte[] zeroes = new byte[chunk.capacity()];
            final ImageReadParam param = reader.getDefaultReadParam();
            Object samples = null;
            for (int bandY = 0; bandY < height; bandY += bandRows) {
                final int bandHeight = Math.min(bandRows, height - bandY);
                param.setSourceRegion(new Rectangle(0, bandY, width, bandHeight));
                final Raster band = reader.read(0, param).getRaster();
                if ((band.getWidth() != width) || (band.getHeight() != bandHeight) || (band.getTransferType() != transferType)
                        || (band.getNumDataElements() != 1) || (band.getSampleModel().getSampleSize(0) != bitDepth)) {
                    throw new IOException("Image reader returned an unexpected band");
                }
                // The part of the band in each row of blocks, then in each block
                for (int sliceY = bandY; sliceY < bandY + bandHeight; ) {
                    final int sliceHeight = Math.min(bandY + bandHeight, (sliceY / BLOCK + 1) * BLOCK) - sliceY;
                    final int blockY = sliceY / BLOCK, rowInBlock = sliceY % BLOCK;
                    for (int blockX = 0; blockX < blocksX; blockX++) {
                        final int x1 = blockX * BLOCK, blockWidth = Math.min(BLOCK, width - x1);
                        // One sample per data element: the samples of the part of the slice in this block, row major
                        samples = band.getDataElements(band.getMinX() + x1, band.getMinY() + sliceY - bandY, blockWidth, sliceHeight, samples);
                        if (blockWidth < BLOCK) {
                            // The columns right of the image in the last column of blocks
                            chunk.put(0, zeroes);
                        }
                        for (int row = 0; row < sliceHeight; row++) {
                            putRow(chunk, row * BLOCK, samples, row * blockWidth, blockWidth, range, x1, sliceY + row);
                        }
                        chunk.position(0).limit(sliceHeight * BLOCK * bytesPerSample);
                        // The rows of a block are contiguous in the file
                        long position = blockOffset(blockX, blockY) + (long) rowInBlock * BLOCK * bytesPerSample;
                        while (chunk.hasRemaining()) {
                            position += out.write(chunk, position);
                        }
                        chunk.clear();
                    }
                    sliceY += sliceHeight;
                }
            }
            // The rows below the image in the last row of blocks
            final long size = getCacheFileSize();
            if (out.size() < size) {
                out.write(ByteBuffer.allocate(1), size - 1);
            }
        }
        this.range = range.result();
        storage.open();
    }

    /**
     * Store a row of samples at sample index {@code to} of the chunk, and include their heights, the first of which is
     * at {@code x}, {@code y} in the image, in the range.
     */
    private void putRow(ByteBuffer chunk, int to, Object samples, int from, int count, Range range, int x, int y) {
        switch (transferType) {
            case TYPE_BYTE -> {
                final byte[] values = (byte[]) samples;
                chunk.put(to, values, from, count);
                for (int i = from; i < from + count; i++) {
                    range.include(values[i] & 0xff);
                }
            }
            case TYPE_USHORT, TYPE_SHORT -> {
                final short[] values = (short[]) samples;
                chunk.asShortBuffer().put(to, values, from, count);
                final boolean unsigned = transferType == TYPE_USHORT;
                for (int i = from; i < from + count; i++) {
                    range.include(unsigned ? (values[i] & 0xffff) : values[i]);
                }
            }
            case TYPE_INT -> {
                final int[] values = (int[]) samples;
                chunk.asIntBuffer().put(to, values, from, count);
                // As BitmapHeightMap.getHeight() returns them: unsigned
                for (int i = from; i < from + count; i++) {
                    range.include(values[i] & 0xffffffffL);
                }
            }
            case TYPE_FLOAT -> {
                final float[] values = (float[]) samples;
                chunk.asFloatBuffer().put(to, values, from, count);
                for (int i = from; i < from + count; i++) {
                    range.include(values[i], x + i - from, y);
                }
            }
            default -> {
                final double[] values = (double[]) samples;
                chunk.asDoubleBuffer().put(to, values, from, count);
                for (int i = from; i < from + count; i++) {
                    range.include(values[i], x + i - from, y);
                }
            }
        }
    }

    /** Convert a block of samples read from the cache file to heights. */
    private void getBlock(ByteBuffer buffer, double[] block, Storage storage) {
        switch (transferType) {
            case TYPE_BYTE -> {
                final byte[] values = storage.bytes(block.length);
                buffer.get(0, values);
                for (int i = 0; i < block.length; i++) {
                    block[i] = values[i] & 0xff;
                }
            }
            case TYPE_USHORT, TYPE_SHORT -> {
                final short[] values = storage.shorts(block.length);
                buffer.asShortBuffer().get(0, values);
                if (transferType == TYPE_USHORT) {
                    for (int i = 0; i < block.length; i++) {
                        block[i] = values[i] & 0xffff;
                    }
                } else {
                    for (int i = 0; i < block.length; i++) {
                        block[i] = values[i];
                    }
                }
            }
            case TYPE_INT -> {
                final int[] values = storage.ints(block.length);
                buffer.asIntBuffer().get(0, values);
                for (int i = 0; i < block.length; i++) {
                    block[i] = values[i] & 0xffffffffL;
                }
            }
            case TYPE_FLOAT -> {
                final float[] values = storage.floats(block.length);
                buffer.asFloatBuffer().get(0, values);
                for (int i = 0; i < block.length; i++) {
                    block[i] = values[i];
                }
            }
            default -> buffer.asDoubleBuffer().get(0, block);
        }
    }

    private long blockOffset(int blockX, int blockY) {
        return ((long) blockY * blocksX + blockX) * BLOCK * BLOCK * bytesPerSample;
    }

    /** The heights of a block. Must be called with the lock on {@link #storage} held, and the result used under it. */
    private double[] block(int blockX, int blockY) {
        if (storage.closed) {
            // A consumer still drawing (such as a preview) after the owner closed it sees the lowest value
            if (storage.closedBlock == null) {
                storage.closedBlock = new double[BLOCK * BLOCK];
                Arrays.fill(storage.closedBlock, minHeight);
            }
            return storage.closedBlock;
        }
        final int key = blockY * blocksX + blockX;
        if (key == storage.lastKey) {
            return storage.lastBlock;
        }
        double[] block = storage.blocks.get(key);
        if (block == null) {
            if (storage.buffer == null) {
                storage.buffer = ByteBuffer.allocate(BLOCK * BLOCK * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
            }
            final ByteBuffer buffer = storage.buffer;
            buffer.clear();
            try {
                long position = blockOffset(blockX, blockY);
                while (buffer.hasRemaining()) {
                    final int count = storage.channel.read(buffer, position);
                    if (count < 0) {
                        throw new EOFException("Height map cache truncated");
                    }
                    position += count;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read height map cache of " + getName(), e);
            }
            // Typed views start at the position
            buffer.flip();
            // Reuse the block evicted last, if any: it is only ever used under the lock
            block = (storage.spare != null) ? storage.spare : new double[BLOCK * BLOCK];
            storage.spare = null;
            getBlock(buffer, block, storage);
            storage.blocks.put(key, block);
        }
        storage.lastKey = key;
        storage.lastBlock = block;
        return block;
    }

    /**
     * The range a capture computes for the samples of a single band raster, which it receives in this order: by rows of
     * blocks, then blocks, then rows. For tests of the range against {@link BitmapHeightMap#getRange()} with samples a
     * TIFF reader may not return.
     */
    static double[] captureRange(Raster raster) {
        final int transferType = raster.getTransferType(), bitDepth = raster.getSampleModel().getSampleSize(0);
        final boolean floatingPoint = (transferType == TYPE_FLOAT) || (transferType == TYPE_DOUBLE);
        final double minHeight, maxHeight;
        switch (transferType) {
            case TYPE_FLOAT -> {
                minHeight = -Float.MAX_VALUE;
                maxHeight = Float.MAX_VALUE;
            }
            case TYPE_DOUBLE -> {
                minHeight = -Double.MAX_VALUE;
                maxHeight = Double.MAX_VALUE;
            }
            case TYPE_SHORT -> {
                minHeight = -(1L << (bitDepth - 1));
                maxHeight = (1L << (bitDepth - 1)) - 1L;
            }
            default -> {
                minHeight = 0;
                maxHeight = (1L << bitDepth) - 1L;
            }
        }
        final Range range = new Range(floatingPoint, minHeight, maxHeight);
        final int width = raster.getWidth(), height = raster.getHeight();
        for (int blockY = 0; blockY * BLOCK < height; blockY++) {
            for (int blockX = 0; blockX * BLOCK < width; blockX++) {
                for (int y = blockY * BLOCK; y < Math.min(height, (blockY + 1) * BLOCK); y++) {
                    for (int x = blockX * BLOCK; x < Math.min(width, (blockX + 1) * BLOCK); x++) {
                        final double value = raster.getSampleDouble(raster.getMinX() + x, raster.getMinY() + y, 0);
                        range.include(((transferType == TYPE_INT) && (value < 0)) ? value + 4294967296.0 : value, x, y);
                    }
                }
            }
        }
        return range.result();
    }

    private static int bytesPerSample(int transferType) {
        return switch (transferType) {
            case TYPE_BYTE -> 1;
            case TYPE_USHORT, TYPE_SHORT -> 2;
            case TYPE_INT, TYPE_FLOAT -> 4;
            case TYPE_DOUBLE -> 8;
            default -> 0;
        };
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new NotSerializableException(CachedImageHeightMap.class.getName());
    }

    private final File imageFile;
    private final int width, height, bitDepth, transferType, bytesPerSample, blocksX;
    private final Rectangle extent;
    private final boolean floatingPoint, signed;
    private final double minHeight, maxHeight;
    /** The cache file and its state, shared with clones. All access to its state, and to the blocks, is synchronised on it. */
    private final transient Storage storage;
    /** Whether this is the captured height map, which owns the storage, rather than a clone of it. */
    private transient boolean owner;
    private double[] range;

    /**
     * The side of a block, and the number of decoded blocks cached (16 MiB). A decoded block of 128x128 doubles (128 KiB)
     * stays well below half a G1 region of 1 MiB, the smallest region size: a larger one would be a humongous object
     * taking a whole region, doubling the real cost of the cache on heaps up to 2 GiB.
     */
    private static final int BLOCK = 128, MAX_BLOCKS = 128;
    private static final int MAX_SIDE = 1 << 20, MAX_BAND_BYTES = 16 << 20;
    /** The TIFF reader whose region reads were verified to return the samples of a complete read. */
    private static final String VERIFIED_READER = "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReader";
    private static final String TIFF_METADATA_FORMAT = "com_sun_media_imageio_plugins_tiff_image_1.0";
    private static final Icon ICON = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/height_map.png");
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The range of the heights of an image, exactly as {@link BitmapHeightMap#getRange()} computes it although the
     * samples arrive row by row: it scans column by column (x major), keeps the first of equal values (which matters
     * for -0 and +0) and stops as soon as both bounds of the type have been reached (which matters for infinities, beyond
     * the bounds of floating point types). For integer types neither changes the result. For floating point types the
     * first positions, in x major order, of the values at or beyond each bound, of the infinities and of the zeroes are
     * tracked, and {@link #result()} derives the result of the x major scan from them.
     */
    private static final class Range {
        Range(boolean floatingPoint, double minHeight, double maxHeight) {
            this.floatingPoint = floatingPoint;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
        }

        void include(double value) {
            if (value < low) {
                low = value;
            }
            if (value > high) {
                high = value;
            }
        }

        void include(double value, int x, int y) {
            include(value);
            if (! floatingPoint) {
                return;
            }
            if ((value <= minHeight) || (value >= maxHeight) || (value == 0.0)) {
                // Image coordinates are below 2^20, so this orders positions x major
                final long position = ((long) x << 21) | y;
                if (value <= minHeight) {
                    lowBound = Math.min(lowBound, position);
                    if (value == Double.NEGATIVE_INFINITY) {
                        negativeInfinity = Math.min(negativeInfinity, position);
                    }
                }
                if (value >= maxHeight) {
                    highBound = Math.min(highBound, position);
                    if (value == Double.POSITIVE_INFINITY) {
                        positiveInfinity = Math.min(positiveInfinity, position);
                    }
                }
                if ((value == 0.0) && (position < zero)) {
                    zero = position;
                    negativeZero = Double.doubleToRawLongBits(value) != 0L;
                }
            }
        }

        double[] result() {
            if (! floatingPoint) {
                return new double[] {low, high};
            }
            if ((lowBound != NONE) && (highBound != NONE)) {
                // The x major scan stops at the later of the first values at or beyond each bound; beyond the bound there
                // is only the infinity
                final long stop = Math.max(lowBound, highBound);
                return new double[] {(negativeInfinity <= stop) ? Double.NEGATIVE_INFINITY : minHeight,
                        (positiveInfinity <= stop) ? Double.POSITIVE_INFINITY : maxHeight};
            }
            // A zero extreme is the first zero of the x major scan
            final double zeroValue = negativeZero ? -0.0 : 0.0;
            return new double[] {(low == 0.0) ? zeroValue : low, (high == 0.0) ? zeroValue : high};
        }

        private final boolean floatingPoint;
        private final double minHeight, maxHeight;
        private double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        private long lowBound = NONE, highBound = NONE, negativeInfinity = NONE, positiveInfinity = NONE, zero = NONE;
        private boolean negativeZero;

        private static final long NONE = Long.MAX_VALUE;
    }

    /** The cache file of a captured height map and its clones. */
    private static final class Storage {
        Storage(Path cacheFile) {
            this.cacheFile = cacheFile;
        }

        synchronized void open() throws IOException {
            channel = FileChannel.open(cacheFile, StandardOpenOption.READ);
        }

        synchronized boolean isClosed() {
            return closed;
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            blocks.clear();
            lastKey = -1;
            lastBlock = null;
            spare = null;
            buffer = null;
            scratch = null;
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException e) {
                // Nothing to do; the file is deleted below
            } finally {
                channel = null;
                try {
                    Files.deleteIfExists(cacheFile);
                } catch (IOException e) {
                    cacheFile.toFile().deleteOnExit();
                }
            }
        }

        byte[] bytes(int length) {
            if (! (scratch instanceof byte[])) {
                scratch = new byte[length];
            }
            return (byte[]) scratch;
        }

        short[] shorts(int length) {
            if (! (scratch instanceof short[])) {
                scratch = new short[length];
            }
            return (short[]) scratch;
        }

        int[] ints(int length) {
            if (! (scratch instanceof int[])) {
                scratch = new int[length];
            }
            return (int[]) scratch;
        }

        float[] floats(int length) {
            if (! (scratch instanceof float[])) {
                scratch = new float[length];
            }
            return (float[]) scratch;
        }

        final Path cacheFile;
        FileChannel channel;
        boolean closed;
        double[] closedBlock;
        /** The block most recently returned, and its key, to skip the map for consecutive reads of one block. */
        int lastKey = -1;
        double[] lastBlock;
        /** The block most recently evicted, to be reused, and the buffers for reading a block. */
        double[] spare;
        ByteBuffer buffer;
        Object scratch;
        /** Decoded blocks, least recently used first; at most {@link #MAX_BLOCKS}. */
        final Map<Integer, double[]> blocks = new LinkedHashMap<>(MAX_BLOCKS * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, double[]> eldest) {
                if (size() > MAX_BLOCKS) {
                    if (eldest.getValue() != lastBlock) {
                        spare = eldest.getValue();
                    }
                    return true;
                }
                return false;
            }
        };
    }
}
