/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.threedeeview;

import org.pepsoft.util.jobqueue.UniqueJobQueue;
import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.threedeeview.Tile3DRenderer.LayerVisibilityMode;

import java.util.Set;

/**
 *
 * @author pepijn
 */
public class Background3DTileRenderer extends Thread {
    public Background3DTileRenderer(Dimension dimension, ColourScheme colourScheme, CustomBiomeManager customBiomeManager, int rotation, UniqueJobQueue<Tile3DRenderJob> jobQueue, ThreeDeeRenderManager threeDeeRenderManager,
                                    LayerVisibilityMode layerVisibility, Set<Layer> hiddenLayers) {
        super("Background 3D renderer");
        this.jobQueue = jobQueue;
        this.threeDeeRenderManager = threeDeeRenderManager;
        renderer = new Tile3DRenderer(dimension, colourScheme, customBiomeManager, rotation, layerVisibility, hiddenLayers);
        setDaemon(true);
    }

    @Override
    public void run() {
        if (logger.isDebugEnabled()) {
            logger.debug("Background 3D rendering thread started");
        }
        try {
            while (running) {
                // Do not hold the worker monitor while blocking on an empty queue.
                Tile3DRenderJob job = jobQueue.takeJob();
                synchronized (renderLock) {
                    Tile tile = job.getTile();
                    RenderResult result = null;
                    try {
                        if (running) result = renderTile(tile);
                    } finally {
                        threeDeeRenderManager.tileFinished(jobQueue, running ? result : null);
                    }
                }
            }
        } catch (InterruptedException e) {
            if (running) {
                throw new RuntimeException("Thread interrupted while waiting for render job", e);
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Background 3D rendering thread halted");
        }
    }
    
    public void halt() {
        running = false;
        interrupt();
    }
    
    /**
     * Blocks until the thread is idle (not rendering a tile)
     */
    public void waitToIdle() {
        synchronized (renderLock) {
            // Acquiring the render lock waits for a current draw, without blocking Thread.join().
        }
    }
    
    private RenderResult renderTile(Tile tile) {
        if (logger.isTraceEnabled()) {
            logger.trace("Rendering 3D view of tile " + tile);
        }
        try {
            return new RenderResult(tile, renderer.render(tile));
        } catch (RuntimeException e) {
            logger.error("Could not render 3D tile {},{}", tile.getX(), tile.getY(), e);
            return new RenderResult(tile, ThreeDeeView.TILE_NOT_RENDERABLE);
        }
    }
    
    private final UniqueJobQueue<Tile3DRenderJob> jobQueue;
    private final ThreeDeeRenderManager threeDeeRenderManager;
    private final Tile3DRenderer renderer;
    private final Object renderLock = new Object();
    private volatile boolean running = true;
    
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Background3DTileRenderer.class);
}
