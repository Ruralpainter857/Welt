/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.TerrainHeightAccess;
import org.pepsoft.worldpainter.SmoothHeightAccess;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.WorldPainter;
import org.pepsoft.worldpainter.panels.EditorFilterPlan;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import javax.swing.*;
import java.util.Arrays;

/**
 *
 * @author pepijn
 */
public class Smooth extends AbstractBrushOperation {
    public Smooth(WorldPainter view) {
        super("Smooth", "Smooth the terrain out", view, 100, "operation.smooth");
    }

    @Override
    public JPanel getOptionsPanel() {
        return optionsPanel;
    }

    @Override
    protected void tick(int centreX, int centreY, boolean inverse, boolean first, float dynamicLevel) {
        final Dimension dimension = getDimension();
        if (dimension == null) {
            // Probably some kind of race condition
            return;
        }

        int radius = getEffectiveRadius();
        boolean applyTheme = options.isApplyTheme();
        dimension.setEventsInhibited(true);
        try {
            if (!smoothNative(dimension, centreX, centreY, radius, dynamicLevel, applyTheme)) {
                smoothJava(dimension, centreX, centreY, radius, dynamicLevel, applyTheme);
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private boolean smoothNative(Dimension dimension, int centreX, int centreY, int radius,
                                 float dynamicLevel, boolean applyTheme) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        long diameterLong = 2L * radius + 1L;
        long inputSideLong = diameterLong + 10L;
        long inputAreaLong = inputSideLong * inputSideLong;
        long outputAreaLong = diameterLong * diameterLong;
        if (radius < 0 || inputSideLong > 256L
                || inputAreaLong > MAX_NATIVE_CELLS || outputAreaLong > MAX_NATIVE_CELLS) {
            return false;
        }
        int diameter = (int) diameterLong;
        int inputSide = (int) inputSideLong;
        int inputArea = (int) inputAreaLong;
        int outputArea = (int) outputAreaLong;
        if (nativeStrengths == null || nativeStrengths.length != outputArea) nativeStrengths = new float[outputArea];
        if(getFilter()!=null){
            if(!applyTheme || !HeightBrushAccess.isFilteredThemedEnabled() || outputArea<16384)return false;
            EditorFilterPlan plan=EditorFilterPlan.compile(getFilter(),dimension);if(plan==null)return false;
            for(int x=0;x<diameter;x++)for(int y=0;y<diameter;y++)
                nativeStrengths[x*diameter+y]=getBrush().getStrength(x-radius,y-radius);
            return HeightBrushAccess.tryApplyFilteredThemed(dimension,centreX-radius,centreY-radius,
                    diameter,diameter,nativeStrengths,HeightBrushAccess.SMOOTH,0,
                    dimension.getMinHeight(),dimension.getMaxHeight()-1,plan,dynamicLevel);
        }
        for (int x = 0; x < diameter; x++) {
            for (int y = 0; y < diameter; y++) {
                nativeStrengths[x * diameter + y] = dynamicLevel * getStrength(
                        centreX, centreY, centreX + x - radius, centreY + y - radius);
            }
        }

        if (applyTheme && HeightBrushAccess.tryApplyThemed(dimension, centreX - radius, centreY - radius,
                diameter, diameter, nativeStrengths, HeightBrushAccess.SMOOTH, 0,
                dimension.getMinHeight(), dimension.getMaxHeight() - 1)) return true;
        if (!applyTheme && SmoothHeightAccess.isEnabled() && SmoothHeightAccess.tryApply(dimension, centreX - radius, centreY - radius,
                diameter, diameter, nativeStrengths)) return true;
        ensureNativeBuffers(inputSide, inputArea, outputArea);
        TerrainHeightAccess.copy(dimension, centreX - radius - 5, centreY - radius - 5, inputSide, inputSide, nativeHeights);
        if (!NativeSlices.smoothHeightRegion(inputSide, inputSide, nativeHeights,
                nativeStrengths, nativeOutputHeights, nativeModified)) {
            smoothHeightBufferInJava(inputSide, diameter, nativeHeights,
                    nativeStrengths, nativeOutputHeights, nativeModified);
        }

        if (!applyTheme) {
            TerrainHeightAccess.apply(dimension, centreX - radius, centreY - radius,
                    diameter, diameter, nativeOutputHeights, nativeModified);
            return true;
        }
        int index = 0;
        for (int x = 0; x < diameter; x++) {
            for (int y = 0; y < diameter; y++) {
                if (nativeModified[index] != 0) {
                    int worldX = centreX + x - radius;
                    int worldY = centreY + y - radius;
                    dimension.setHeightAt(worldX, worldY, nativeOutputHeights[index]);
                    if (applyTheme) {
                        dimension.applyTheme(worldX, worldY);
                    }
                }
                index++;
            }
        }
        return true;
    }

    private void ensureNativeBuffers(int inputSide, int inputArea, int outputArea) {
        if (nativeSide != inputSide) {
            nativeSide = inputSide;
            nativeHeights = new float[inputArea];
            nativeOutputHeights = new float[outputArea];
            nativeModified = new byte[outputArea];
        }
    }

    private static void smoothHeightBufferInJava(int inputSide, int diameter,
                                                 float[] heights, float[] strengths,
                                                 float[] output, byte[] modified) {
        int index = 0;
        for (int x = 0; x < diameter; x++) {
            for (int y = 0; y < diameter; y++) {
                float strength = strengths[index];
                if (strength > 0.0f) {
                    float total = 0.0f;
                    int sampleCount = 0;
                    for (int sampleX = x; sampleX <= x + 10; sampleX++) {
                        for (int sampleY = y; sampleY <= y + 10; sampleY++) {
                            float currentHeight = heights[sampleX * inputSide + sampleY];
                            if (currentHeight != -Float.MAX_VALUE) {
                                total += currentHeight;
                                sampleCount++;
                            }
                        }
                    }
                    float currentHeight = heights[(x + 5) * inputSide + y + 5];
                    if (currentHeight == -Float.MAX_VALUE) {
                        currentHeight = 0.0f;
                    }
                    output[index] = strength * (total / sampleCount)
                            + (1 - strength) * currentHeight;
                    modified[index] = 1;
                } else {
                    modified[index] = 0;
                }
                index++;
            }
        }
    }

    private void smoothJava(Dimension dimension, int centreX, int centreY, int radius,
                            float dynamicLevel, boolean applyTheme) {
        int diameter = radius * 2 + 1;
        if ((totals == null) || (totals.length < (diameter + 10))) {
            totals = new float[diameter + 10][diameter + 10];
            currentHeights = new float[diameter + 10][diameter + 10];
            sampleCounts = new int[diameter + 10][diameter + 10];
        } else {
            for (int i = 0; i < diameter + 10; i++) {
                Arrays.fill(totals[i], 0.0f);
                Arrays.fill(currentHeights[i], 0.0f);
                Arrays.fill(sampleCounts[i], 0);
            }
        }
        for (int x = 0; x < diameter + 10; x++) {
            for (int y = 0; y < diameter + 10; y++) {
                float currentHeight = dimension.getHeightAt(centreX - radius + x - 5, centreY - radius + y - 5);
                if (currentHeight != -Float.MAX_VALUE) {
                    currentHeights[x][y] = currentHeight;
                    int dxFrom = Math.max(x - 5, 0);
                    int dxTo = Math.min(x + 5, diameter + 9);
                    int dyFrom = Math.max(y - 5, 0);
                    int dyTo = Math.min(y + 5, diameter + 9);
                    for (int dx = dxFrom; dx <= dxTo; dx++) {
                        for (int dy = dyFrom; dy <= dyTo; dy++) {
                            totals[dx][dy] += currentHeight;
                            sampleCounts[dx][dy]++;
                        }
                    }
                }
            }
            if (x >= 10) {
                for (int y = 5; y < diameter + 5; y++) {
                    float strength = dynamicLevel * getStrength(centreX, centreY,
                            centreX + x - radius - 10, centreY + y - radius - 5);
                    if (strength > 0.0f) {
                        float newHeight = strength * (totals[x - 5][y] / sampleCounts[x - 5][y])
                                + (1 - strength) * currentHeights[x - 5][y];
                        int worldX = x + centreX - radius - 10;
                        int worldY = y + centreY - radius - 5;
                        dimension.setHeightAt(worldX, worldY, newHeight);
                        if (applyTheme) {
                            dimension.applyTheme(worldX, worldY);
                        }
                    }
                }
            }
        }
    }
    
    private final TerrainShapingOptions<Smooth> options = new TerrainShapingOptions<>();
    private final TerrainShapingOptionsPanel optionsPanel = new TerrainShapingOptionsPanel("Smooth", "<p>Click to smooth the terrain out", options);
    private float[][] totals, currentHeights;
    private int[][] sampleCounts;
    private int nativeSide;
    private float[] nativeHeights, nativeStrengths, nativeOutputHeights;
    private byte[] nativeModified;

    private static final int MAX_NATIVE_CELLS = 65_536;
}
