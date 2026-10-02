/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.heightMaps.gui;

import org.pepsoft.util.MathUtils;
import org.pepsoft.util.swing.TileListener;
import org.pepsoft.util.swing.TileProvider;
import org.pepsoft.worldpainter.HeightMap;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.awt.image.DataBufferByte;

/**
 *
 * @author pepijn
 */
public class HeightMapTileProvider implements TileProvider {
    public HeightMapTileProvider(HeightMap heightMap) {
        if (heightMap == null) {
            throw new NullPointerException();
        }
        this.heightMap = heightMap;
    }

    public HeightMap getHeightMap() {
        return heightMap;
    }
    
    // TileProvider
    
    @Override
    public int getTileSize() {
        return 128;
    }

    @Override
    public boolean isTilePresent(int x, int y) {
        return true;
    }

    @Override
    public boolean paintTile(Image tileImage, int x, int y, int imageX, int imageY) {
        final BufferedImage image = renderBufferRef.get();
        final WritableRaster raster = image.getRaster();
        final double vertScale = 255 / heightMap.getRange()[1];
        if (renderNative(image, x, y, vertScale)) {
            // The raster remains TYPE_BYTE_GRAY: Graphics2D retains the same
            // colour-space conversion and clipping as the original preview.
        } else if (zoom < 0) {
            final int scale = -zoom;
            final int xOffset = x << 7 << scale, yOffset = y << 7 << scale;
            for (int dx = 0; dx < 128; dx++) {
                for (int dy = 0; dy < 128; dy++) {
                    raster.setSample(dx, dy, 0, MathUtils.clamp(0, Math.round(heightMap.getHeight(xOffset + (dx << scale), yOffset + (dy << scale)) * vertScale), 255));
                }
            }
        } else {
            final int xOffset = x << 7, yOffset = y << 7;
            for (int dx = 0; dx < 128; dx++) {
                for (int dy = 0; dy < 128; dy++) {
                    raster.setSample(dx, dy, 0, MathUtils.clamp(0, Math.round(heightMap.getHeight(xOffset + dx, yOffset + dy) * vertScale), 255));
                }
            }
        }
        Graphics2D g2 = (Graphics2D) tileImage.getGraphics();
        try {
            g2.drawImage(image, imageX, imageY, null);
        } finally {
            g2.dispose();
        }
        return true;
    }

    private boolean renderNative(BufferedImage image, int tileX, int tileY, double vertScale) {
        if (!Native.isGenEnabled() || !Boolean.getBoolean("welt.native.heightMapPreview")) return false;
        PreviewBuffers buffers = previewBuffers.get();
        int shift = zoom < 0 ? -zoom & 31 : 0;
        if (shift != 0 && buffers.x == null) { buffers.x = new float[16384]; buffers.y = new float[16384]; }
        if (!HeightMapTileFactory.tryFillPreviewHeights(heightMap, tileX << 7 << shift, tileY << 7 << shift,
                shift, buffers.x, buffers.y, buffers.heights)) return false;
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = (byte) MathUtils.clamp(0, Math.round(buffers.heights[i] * vertScale), 255);
        buffers.completed++; return true;
    }

    /** Successful complete tile evaluations on the calling rendering worker. */
    public long completedNativePreviewTiles() { return previewBuffers.get().completed; }

    private static final class PreviewBuffers {
        private final double[] heights = new double[16384];
        private float[] x, y;
        private long completed;
    }
    private final ThreadLocal<PreviewBuffers> previewBuffers = ThreadLocal.withInitial(PreviewBuffers::new);

    @Override
    public int getTilePriority(int x, int y) {
        return 0;
    }

    @Override
    public Rectangle getExtent() {
        return null;
    }

    @Override
    public void addTileListener(TileListener tileListener) {
        // Do nothing
    }

    @Override
    public void removeTileListener(TileListener tileListener) {
        // Do nothing
    }

    @Override
    public boolean isZoomSupported() {
        return true;
    }

    @Override
    public int getZoom() {
        return zoom;
    }

    @Override
    public void setZoom(int zoom) {
        if (zoom != this.zoom) {
            if (zoom > 0) {
                throw new UnsupportedOperationException("Zooming in not supported");
            }
            this.zoom = zoom;
        }
    }
    
    private final HeightMap heightMap;
    private final ThreadLocal<BufferedImage> renderBufferRef = new ThreadLocal<BufferedImage>() {
        @Override
        protected BufferedImage initialValue() {
            return new BufferedImage(128, 128, BufferedImage.TYPE_BYTE_GRAY);
        }
    };
    private int zoom = 0;
}
