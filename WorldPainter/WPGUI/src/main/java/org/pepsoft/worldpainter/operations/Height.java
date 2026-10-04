/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.TerrainHeightAccess;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.FilteredPaintAccess;
import org.pepsoft.worldpainter.panels.EditorFilterPlan;
import org.pepsoft.worldpainter.WorldPainter;
import org.pepsoft.worldpainter.panels.DefaultFilter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import javax.swing.*;

/**
 *
 * @author pepijn
 */
public class Height extends AbstractBrushOperation {
    public Height(WorldPainter view) {
        super("Height", "Raise or lower the terrain", view, 100, "operation.height");
    }

    @Override
    public JPanel getOptionsPanel() {
        return optionsPanel;
    }

    @Override
    protected void tick(int centreX, int centreY, boolean inverse, boolean first, float dynamicLevel) {
        final float adjustment = (float) Math.pow(dynamicLevel * getLevel() * 2, 2.0);
        final Dimension dimension = getDimension();
        if (dimension == null) {
            // Probably some kind of race condition
            return;
        }
        final float minZ, maxZ;
        if (getFilter() instanceof DefaultFilter) {
            final DefaultFilter filter = (DefaultFilter) getFilter();
            if (filter.getAboveLevel() != Integer.MIN_VALUE) {
                minZ = Math.max(filter.getAboveLevel(), dimension.getMinHeight());
            } else {
                minZ = dimension.getMinHeight();
            }
            if (filter.getBelowLevel() != Integer.MIN_VALUE) {
                maxZ = Math.min(filter.getBelowLevel(), dimension.getMaxHeight());
            } else {
                maxZ = dimension.getMaxHeight() - 1;
            }
        } else {
            minZ = dimension.getMinHeight();
            maxZ = dimension.getMaxHeight() - 1;
        }
        boolean applyTheme = options.isApplyTheme();
        dimension.setEventsInhibited(true);
        try {
            final int radius = getEffectiveRadius();
            if (!applyNativeHeightBrush(dimension, centreX, centreY, radius,
                    inverse, adjustment, minZ, maxZ, applyTheme)) {
                applyJavaHeightBrush(dimension, centreX, centreY, radius,
                        inverse, adjustment, minZ, maxZ, applyTheme);
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private boolean applyNativeHeightBrush(Dimension dimension, int centreX, int centreY,
                                           int radius, boolean inverse, float adjustment,
                                           float minZ, float maxZ, boolean applyTheme) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        long diameterLong = 2L * radius + 1L;
        if (radius < 0 || diameterLong > 256L) {
            return false;
        }
        int diameter = (int) diameterLong;
        int area = diameter * diameter;
        if (area > MAX_NATIVE_CELLS) {
            return false;
        }
        if (nativeStrengths == null || nativeStrengths.length != area) nativeStrengths = new float[area];

        if(getFilter()!=null){
            if(applyTheme||area<16384)return false;
            EditorFilterPlan plan=EditorFilterPlan.compile(getFilter(),dimension);if(plan==null)return false;
            for(int y=0;y<diameter;y++)for(int x=0;x<diameter;x++)
                nativeStrengths[y*diameter+x]=getBrush().getFullStrength(x-radius,y-radius);
            return FilteredPaintAccess.applyHeight(dimension,plan,centreX-radius,centreY-radius,diameter,diameter,
                    nativeStrengths,inverse?HeightBrushAccess.LOWER:HeightBrushAccess.RAISE,adjustment,minZ,maxZ);
        }
        int index = 0;
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int y = centreY - radius; y <= centreY + radius; y++) {
                nativeStrengths[index] = getFullStrength(centreX, centreY, x, y);
                index++;
            }
        }

        if (applyTheme && HeightBrushAccess.tryApplyThemed(dimension,centreX-radius,centreY-radius,
                diameter,diameter,nativeStrengths,inverse?HeightBrushAccess.LOWER:HeightBrushAccess.RAISE,
                adjustment,minZ,maxZ)) return true;
        if (!applyTheme && HeightBrushAccess.tryApply(dimension, centreX - radius, centreY - radius,
                diameter, diameter, nativeStrengths, inverse ? HeightBrushAccess.LOWER : HeightBrushAccess.RAISE,
                adjustment, minZ, maxZ)) return true;
        ensureNativeBuffers(area);
        TerrainHeightAccess.copy(dimension, centreX - radius, centreY - radius, diameter, diameter, nativeHeights);

        boolean nativeApplied = NativeSlices.applyHeightBrush(inverse, minZ, maxZ,
                adjustment, nativeHeights, nativeStrengths, nativeModified);
        if (!nativeApplied) {
            applyHeightBrushInJava(inverse, minZ, maxZ, adjustment,
                    nativeHeights, nativeStrengths, nativeModified);
        }

        if (!applyTheme) {
            TerrainHeightAccess.apply(dimension, centreX - radius, centreY - radius,
                    diameter, diameter, nativeHeights, nativeModified);
            return true;
        }
        index = 0;
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int y = centreY - radius; y <= centreY + radius; y++) {
                if (nativeModified[index] != 0) {
                    dimension.setHeightAt(x, y, nativeHeights[index]);
                    if (applyTheme) {
                        dimension.applyTheme(x, y);
                    }
                }
                index++;
            }
        }
        return true;
    }

    private void ensureNativeBuffers(int area) {
        if ((nativeHeights == null) || (nativeHeights.length != area)) {
            nativeHeights = new float[area];
            nativeModified = new byte[area];
        }
    }

    private static void applyHeightBrushInJava(boolean inverse, float minZ, float maxZ,
                                               float adjustment, float[] heights,
                                               float[] strengths, byte[] modified) {
        for (int index = 0; index < heights.length; index++) {
            float currentHeight = heights[index];
            float targetHeight = inverse
                    ? Math.max(currentHeight - adjustment, minZ)
                    : Math.min(currentHeight + adjustment, maxZ);
            float strength = strengths[index];
            if (strength > 0.0f) {
                float newHeight = strength * targetHeight + (1 - strength) * currentHeight;
                if (inverse ? (newHeight < currentHeight) : (newHeight > currentHeight)) {
                    heights[index] = newHeight;
                    modified[index] = 1;
                } else {
                    modified[index] = 0;
                }
            } else {
                modified[index] = 0;
            }
        }
    }

    private void applyJavaHeightBrush(Dimension dimension, int centreX, int centreY,
                                     int radius, boolean inverse, float adjustment,
                                     float minZ, float maxZ, boolean applyTheme) {
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int y = centreY - radius; y <= centreY + radius; y++) {
                final float currentHeight = dimension.getHeightAt(x, y);
                final float targetHeight = inverse ? Math.max(currentHeight - adjustment, minZ) : Math.min(currentHeight + adjustment, maxZ);
                final float strength = getFullStrength(centreX, centreY, x, y);
                if (strength > 0.0f) {
                    final float newHeight = strength * targetHeight + (1 - strength) * currentHeight;
                    if (inverse ? (newHeight < currentHeight) : (newHeight > currentHeight)) {
                        dimension.setHeightAt(x, y, newHeight);
                        if (applyTheme) {
                            dimension.applyTheme(x, y);
                        }
                    }
                }
            }
        }
    }

    private final TerrainShapingOptions<Height> options = new TerrainShapingOptions<>();
    private final TerrainShapingOptionsPanel optionsPanel = new TerrainShapingOptionsPanel("Height", "<ul><li>Left-click to raise the terrain<li>Right-click to lower the terrain</ul>", options);
    private float[] nativeHeights;
    private float[] nativeStrengths;
    private byte[] nativeModified;

    private static final int MAX_NATIVE_CELLS = 65_536;
}
