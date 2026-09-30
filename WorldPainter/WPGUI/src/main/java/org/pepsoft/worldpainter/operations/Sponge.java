/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.TileFactory;
import org.pepsoft.worldpainter.WorldPainterView;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import javax.swing.*;

/**
 *
 * @author pepijn
 */
public class Sponge extends AbstractBrushOperation {
    public Sponge(WorldPainterView view) {
        super("Sponge", "Dry up or reset water and lava", view, 100, "operation.sponge");
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
        final int waterHeight, minHeight = dimension.getMinHeight();
        final TileFactory tileFactory = dimension.getTileFactory();
        if (tileFactory instanceof HeightMapTileFactory) {
            waterHeight = ((HeightMapTileFactory) tileFactory).getWaterHeight();
        } else {
            // If we can't determine the water height disable the inverse
            // functionality, which resets to the default water height
            waterHeight = -1;
        }
        dimension.setEventsInhibited(true);
        try {
            final int radius = getEffectiveRadius();
            if (!applyNativeSponge(dimension, centreX, centreY, radius,
                    inverse, waterHeight, minHeight)) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        if (getStrength(centreX, centreY, centreX + dx, centreY + dy) != 0f) {
                            if (inverse) {
                                if (waterHeight != -1) {
                                    dimension.setWaterLevelAt(centreX + dx, centreY + dy, waterHeight);
                                    dimension.setBitLayerValueAt(FloodWithLava.INSTANCE, centreX + dx, centreY + dy, false);
                                }
                            } else {
                                dimension.setWaterLevelAt(centreX + dx, centreY + dy, minHeight);
                            }
                        }
                    }
                }
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private boolean applyNativeSponge(Dimension dimension, int centreX, int centreY,
                                      int radius, boolean inverse, int waterHeight,
                                      int minHeight) {
        final long diameterLong = 2L * radius + 1L;
        if (radius < 0 || diameterLong > 255L || !Native.isGenEnabled()
                || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        final int diameter = (int) diameterLong;
        final int area = diameter * diameter;
        ensureNativeBuffers(area);

        int index = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                nativeStrengths[index++] = getStrength(
                        centreX, centreY, centreX + dx, centreY + dy);
            }
        }
        if (!NativeSlices.applySpongeBrush(inverse, waterHeight,
                nativeStrengths, nativeActions)) {
            return false;
        }

        index = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                final int x = centreX + dx;
                final int y = centreY + dy;
                if (nativeActions[index] == DRY_CELL) {
                    dimension.setWaterLevelAt(x, y, minHeight);
                } else if (nativeActions[index] == RESET_FLUID) {
                    dimension.setWaterLevelAt(x, y, waterHeight);
                    dimension.setBitLayerValueAt(FloodWithLava.INSTANCE, x, y, false);
                }
                index++;
            }
        }
        return true;
    }

    private void ensureNativeBuffers(int area) {
        if (nativeStrengths == null || nativeStrengths.length != area) {
            nativeStrengths = new float[area];
            nativeActions = new byte[area];
        }
    }

    private static final JPanel OPTIONS_PANEL = new StandardOptionsPanel("Sponge", "<ul><li>Left-click to remove water and lava<li>Right-click to reset to the default fluid type and height</ul>");
    private static final byte DRY_CELL = 1;
    private static final byte RESET_FLUID = 2;
    private float[] nativeStrengths;
    private byte[] nativeActions;
}
