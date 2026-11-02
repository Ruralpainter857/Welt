package org.pepsoft.worldpainter.exporting;

import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.*;
import java.util.Arrays;
import java.util.Vector;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightMapImageAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.exporting.HeightMapExporter.Format;

/** A single reusable tile-row cache shared by row, strip and tile image encoders. */
final class HeightMapRenderedImage implements RenderedImage, AutoCloseable {
    private static final int TILE = 128;
    private static final long MAX_BAND_BYTES = 16L * 1024 * 1024;
    private Dimension dimension;
    private final ImageTypeSpecifier type;
    private final Format format;
    private final int width, height, lowestX, lowestY, minimum;
    private final float offset, scale;
    private WritableRaster storage, band;
    private int bandY = -TILE;

    static HeightMapRenderedImage create(Dimension dimension, ImageTypeSpecifier type, ImageWriter writer,
                                         Format format, int minimum, float offset, float scale) {
        if (!Boolean.parseBoolean(System.getProperty("welt.heightmap.streaming", "false"))
                || dimension.getClass() != Dimension.class || type.getNumBands() != 1) return null;
        String provider = writer.getClass().getName();
        if (!provider.equals("com.sun.imageio.plugins.png.PNGImageWriter")
                && !provider.equals("com.sun.imageio.plugins.tiff.TIFFImageWriter")
                && !provider.equals("com.twelvemonkeys.imageio.plugins.tiff.TIFFImageWriter")) return null;
        int dataType = type.getSampleModel().getDataType();
        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT && dataType != DataBuffer.TYPE_FLOAT) return null;
        long width = (long) dimension.getWidth() * TILE, height = (long) dimension.getHeight() * TILE;
        if (width < 1 || width > Integer.MAX_VALUE || height < 1 || height > Integer.MAX_VALUE
                || width * TILE * (DataBuffer.getDataTypeSize(dataType) / 8) > MAX_BAND_BYTES) return null;
        // Changing traversal order is safe only for stock tiles with pure sample accessors.
        for (Tile tile : dimension.getTiles()) if (tile.getClass() != Tile.class) return null;
        return new HeightMapRenderedImage(dimension, type, format, minimum, offset, scale, (int) width, (int) height);
    }

    private HeightMapRenderedImage(Dimension dimension, ImageTypeSpecifier type, Format format,
                                   int minimum, float offset, float scale, int width, int height) {
        this.dimension = dimension; this.type = type; this.format = format; this.minimum = minimum;
        this.offset = offset; this.scale = scale; this.width = width; this.height = height;
        lowestX = dimension.getLowestX(); lowestY = dimension.getLowestY();
    }
    private void requireOpen() {
        if (dimension == null) throw new IllegalStateException("Heightmap image is closed");
    }
    private void prepareBand(int y) {
        requireOpen();
        int first = y / TILE * TILE;
        if (first == bandY) return;
        bandY = -TILE;
        if (storage == null) storage = type.createBufferedImage(width, TILE).getRaster();
        DataBuffer pixels = storage.getDataBuffer();
        // Clear missing tiles, including holes that held data in the previous row.
        if (pixels instanceof DataBufferByte data) Arrays.fill(data.getData(), (byte) 0);
        else if (pixels instanceof DataBufferUShort data) Arrays.fill(data.getData(), (short) 0);
        else if (pixels instanceof DataBufferInt data) Arrays.fill(data.getData(), 0);
        else if (pixels instanceof DataBufferFloat data) Arrays.fill(data.getData(), 0f);
        else throw new IllegalStateException("Unsupported heightmap raster storage");
        band = storage.createWritableTranslatedChild(0, first);
        int worldY = lowestY + first / TILE;
        for (int column = 0; column < width / TILE; column++) {
            Tile tile = dimension.getTile(lowestX + column, worldY);
            if (tile != null) HeightMapImageAccess.writeTileSamples(tile, band, column * TILE, first,
                    format, minimum, offset, scale);
        }
        // A failed read must not make a partially prepared band look valid on retry.
        bandY = first;
    }
    private Rectangle bounds() { return new Rectangle(0, 0, width, height); }
    private void validate(Rectangle rectangle) {
        requireOpen();
        if (rectangle.width < 1 || rectangle.height < 1 || !bounds().contains(rectangle))
            throw new RasterFormatException("Requested heightmap area is outside the image");
    }
    private WritableRaster allocate(Rectangle rectangle) {
        return Raster.createWritableRaster(type.getSampleModel().createCompatibleSampleModel(
                rectangle.width, rectangle.height), new Point(rectangle.x, rectangle.y));
    }
    @Override public synchronized Raster getTile(int tileX, int tileY) {
        if (tileX < 0 || tileX >= getNumXTiles() || tileY < 0 || tileY >= getNumYTiles())
            throw new IndexOutOfBoundsException("Heightmap tile is outside the image");
        return getData(new Rectangle(0, tileY * TILE, width, TILE));
    }
    @Override public synchronized Raster getData() { return getData(bounds()); }
    @Override public synchronized Raster getData(Rectangle rectangle) {
        validate(rectangle);
        WritableRaster copy = allocate(rectangle);
        copyData(copy);
        return copy;
    }
    @Override public synchronized WritableRaster copyData(WritableRaster destination) {
        requireOpen();
        if (destination == null) destination = allocate(bounds());
        Rectangle rectangle = bounds().intersection(destination.getBounds());
        if (rectangle.isEmpty()) return destination;
        int end = rectangle.y + rectangle.height;
        for (int y = rectangle.y; y < end;) {
            prepareBand(y);
            int rows = Math.min(end - y, TILE - (y - bandY));
            destination.setRect(band.createChild(rectangle.x, y, rectangle.width, rows, rectangle.x, y, null));
            y += rows;
        }
        return destination;
    }
    /** Returned rasters own their pixels and remain stable after the cache advances or closes. */
    @Override public synchronized void close() {
        dimension = null; storage = null; band = null; bandY = -TILE;
    }
    synchronized long cachedBytes() {
        return storage == null ? 0 : (long) storage.getDataBuffer().getSize()
                * (DataBuffer.getDataTypeSize(storage.getDataBuffer().getDataType()) / 8);
    }
    @Override public ColorModel getColorModel() { return type.getColorModel(); }
    @Override public SampleModel getSampleModel() { return type.getSampleModel().createCompatibleSampleModel(width, TILE); }
    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public int getMinX() { return 0; }
    @Override public int getMinY() { return 0; }
    @Override public int getNumXTiles() { return 1; }
    @Override public int getNumYTiles() { return height / TILE; }
    @Override public int getMinTileX() { return 0; }
    @Override public int getMinTileY() { return 0; }
    @Override public int getTileWidth() { return width; }
    @Override public int getTileHeight() { return TILE; }
    @Override public int getTileGridXOffset() { return 0; }
    @Override public int getTileGridYOffset() { return 0; }
    @Override public Vector<RenderedImage> getSources() { return null; }
    @Override public Object getProperty(String name) { return Image.UndefinedProperty; }
    @Override public String[] getPropertyNames() { return null; }
}
