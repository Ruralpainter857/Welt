/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.WorldPainter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import javax.swing.*;

/**
 *
 * @author pepijn
 */
public class RaisePyramid extends MouseOrTabletOperation {
    public RaisePyramid(WorldPainter worldPainter) {
        super("Raise Pyramid", "Raises a square pyramid out of the ground", worldPainter, 100, "operation.raisePyramid", "pyramid");
    }

    @Override
    public JPanel getOptionsPanel() {
        return OPTIONS_PANEL;
    }

    @Override
    protected void tick(int centreX, int centreY, boolean inverse, boolean first, float dynamicLevel) {
        final Dimension dimension = getDimension();
        if (dimension == null) {
            // Probably some kind of race condition
            return;
        }
        float height = dimension.getHeightAt(centreX, centreY);
        dimension.setEventsInhibited(true);
        try {
            int maxR = dimension.getMaxHeight() - dimension.getMinHeight();
            if (raisePyramidNative(dimension, centreX, centreY, height, maxR)) {
                return;
            }
            if (height < (dimension.getMaxHeight() - 1.5f)) {
                dimension.setHeightAt(centreX, centreY, height + 1);
            }
            dimension.setTerrainAt(centreX, centreY, Terrain.SANDSTONE);
            for (int r = 1; r < maxR; r++) {
                if (! raiseRing(dimension, centreX, centreY, r, height--)) {
                    break;
                }
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private boolean raisePyramidNative(Dimension dimension, int centreX, int centreY,
                                       float centerHeight, int maxR) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || maxR > 128) {
            return false;
        }
        int radius = maxR > 1 ? maxR - 1 : 0;
        int side = radius * 2 + 1;
        int area = side * side;
        ensureNativeBuffers(radius, area);
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                nativeHeights[x * side + y] = dimension.getHeightAt(
                        centreX + x - radius, centreY + y - radius);
            }
        }

        float maxHeight = dimension.getMaxHeight() - 1.5f;
        if (!NativeSlices.raiseSquarePyramid(maxR, centerHeight,
                maxHeight, nativeHeights, nativeModified)) {
            raisePyramidHeightBufferInJava(maxR, centerHeight,
                    maxHeight, radius, side, nativeHeights, nativeModified);
        }
        applyPyramidHeightBuffer(dimension, centreX, centreY, maxR,
                radius, side, nativeHeights, nativeModified);
        return true;
    }

    private void ensureNativeBuffers(int radius, int area) {
        if (nativeRadius != radius) {
            nativeRadius = radius;
            nativeHeights = new float[area];
            nativeModified = new byte[area];
        }
    }

    private static void raisePyramidHeightBufferInJava(int maxR, float centerHeight,
                                                      float maxHeight, int radius, int side,
                                                      float[] heights, byte[] modified) {
        java.util.Arrays.fill(modified, (byte) 0);
        int centerIndex = radius * side + radius;
        if (centerHeight < maxHeight) {
            heights[centerIndex] = centerHeight + 1.0f;
            modified[centerIndex] = 2;
        }
        float desiredHeight = centerHeight;
        for (int ring = 1; ring < maxR; ring++) {
            boolean raised = false;
            for (int offset = -ring; offset <= ring; offset++) {
                raised |= raisePyramidCell(offset + radius, -ring + radius,
                        side, desiredHeight, heights, modified);
                raised |= raisePyramidCell(offset + radius, ring + radius,
                        side, desiredHeight, heights, modified);
            }
            for (int offset = -ring + 1; offset < ring; offset++) {
                raised |= raisePyramidCell(-ring + radius, offset + radius,
                        side, desiredHeight, heights, modified);
                raised |= raisePyramidCell(ring + radius, offset + radius,
                        side, desiredHeight, heights, modified);
            }
            if (!raised) {
                break;
            }
            desiredHeight -= 1.0f;
        }
    }

    private static boolean raisePyramidCell(int x, int y, int side, float desiredHeight,
                                            float[] heights, byte[] modified) {
        int index = x * side + y;
        if (heights[index] < desiredHeight) {
            heights[index] = desiredHeight;
            modified[index] = 1;
            return true;
        }
        return false;
    }

    private static void applyPyramidHeightBuffer(Dimension dimension, int centreX, int centreY,
                                                 int maxR, int radius, int side,
                                                 float[] heights, byte[] modified) {
        int centerIndex = radius * side + radius;
        if (modified[centerIndex] == 2) {
            dimension.setHeightAt(centreX, centreY, heights[centerIndex]);
        }
        dimension.setTerrainAt(centreX, centreY, Terrain.SANDSTONE);
        for (int ring = 1; ring < maxR; ring++) {
            boolean raised = false;
            for (int offset = -ring; offset <= ring; offset++) {
                raised |= applyPyramidCell(dimension, centreX + offset, centreY - ring,
                        offset + radius, -ring + radius, side, heights, modified);
                raised |= applyPyramidCell(dimension, centreX + offset, centreY + ring,
                        offset + radius, ring + radius, side, heights, modified);
            }
            for (int offset = -ring + 1; offset < ring; offset++) {
                raised |= applyPyramidCell(dimension, centreX - ring, centreY + offset,
                        -ring + radius, offset + radius, side, heights, modified);
                raised |= applyPyramidCell(dimension, centreX + ring, centreY + offset,
                        ring + radius, offset + radius, side, heights, modified);
            }
            if (!raised) {
                break;
            }
        }
    }

    private static boolean applyPyramidCell(Dimension dimension, int x, int y,
                                            int gridX, int gridY, int side,
                                            float[] heights, byte[] modified) {
        int index = gridX * side + gridY;
        if (modified[index] == 1) {
            dimension.setHeightAt(x, y, heights[index]);
            dimension.setTerrainAt(x, y, Terrain.SANDSTONE);
            return true;
        }
        return false;
    }

    private boolean raiseRing(Dimension dimension, int x, int y, int r, float desiredHeight) {
        boolean raised = false;
        for (int i = -r; i <= r; i++) {
            float actualHeight = dimension.getHeightAt(x + i, y - r);
            if (actualHeight < desiredHeight) {
                raised = true;
                dimension.setHeightAt(x + i, y - r, desiredHeight);
                dimension.setTerrainAt(x + i, y - r, Terrain.SANDSTONE);
            }
            actualHeight = dimension.getHeightAt(x + i, y + r);
            if (actualHeight < desiredHeight) {
                raised = true;
                dimension.setHeightAt(x + i, y + r, desiredHeight);
                dimension.setTerrainAt(x + i, y + r, Terrain.SANDSTONE);
            }
        }
        for (int i = -r + 1; i < r; i++) {
            float actualHeight = dimension.getHeightAt(x - r, y + i);
            if (actualHeight < desiredHeight) {
                raised = true;
                dimension.setHeightAt(x - r, y + i, desiredHeight);
                dimension.setTerrainAt(x - r, y + i, Terrain.SANDSTONE);
            }
            actualHeight = dimension.getHeightAt(x + r, y + i);
            if (actualHeight < desiredHeight) {
                raised = true;
                dimension.setHeightAt(x + r, y + i, desiredHeight);
                dimension.setTerrainAt(x + r, y + i, Terrain.SANDSTONE);
            }
        }
        return raised;
    }

    private static final StandardOptionsPanel OPTIONS_PANEL = new StandardOptionsPanel("Raise Pyramid", "<p>Click to raise a four-sided sandstone pyramid from the ground");
    private int nativeRadius = -1;
    private float[] nativeHeights;
    private byte[] nativeModified;
}
