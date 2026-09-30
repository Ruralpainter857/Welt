package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.WorldPainterView;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/**
 * A tool for creating rivers. It floods an area defined by where the brush
 * intensity is at least 25% to a depth defined by where you first clicked.
 * The level to which it is flooded can only decrease while the mouse is held
 * down. The terrain is only ever lowered, not raised (which means the level of
 * the water is the highest at which it will not spill over).
 *
 * Created by Pepijn Schmitz on 30-09-15.
 */
public class RiverPaint extends AbstractBrushOperation {
    public RiverPaint(WorldPainterView view) {
        super("RiverPaint", "Paint a river of water or lava", view, 100, "operation.riverPaint", "river");
    }

    @Override
    protected void tick(int centreX, int centreY, boolean inverse, boolean first, float dynamicLevel) {
        final Dimension dim = getDimension();
        if (dim == null) {
            // Probably some kind of race condition
            return;
        }
        if (first) {
            // Measure depth and fluid type at first click
            previousWaterLevel = dim.getWaterLevelAt(centreX, centreY);
            depth = (previousWaterLevel - dim.getHeightAt(centreX, centreY)) / getFullStrength(centreX, centreY, centreX, centreY);
            lava = dim.getBitLayerValueAt(FloodWithLava.INSTANCE, centreX, centreY);
//            System.out.println("previousWaterLevel: " + previousWaterLevel + ", height: " + dim.getHeightAt(centreX, centreY) + ", depth: " + depth + ", brush strength at centre: " + getFullStrength(centreX, centreY, centreX, centreY) + ", lava: " + lava);
        }
        if (depth < 0) {
            return;
        }
        int r = getEffectiveRadius();
        if (applyNativeRiverPaint(dim, centreX, centreY, r)) {
            return;
        }

        // Step 1: determine the water level by finding the lowest block along the edge of the part which should be
        // flooded (the part where the brush is at 25% intensity or higher)
        int waterLevel = Integer.MAX_VALUE;
        for (int x = centreX - r; x <= centreX + r; x++) {
            for (int y = centreY - r; y <= centreY + r; y++) {
                int height;
                if ((! shouldFlood(centreX, centreY, x, y, r))
                        && (dim.getWaterLevelAt(x, y) < (height = dim.getIntHeightAt(x, y)))
                        && (height < waterLevel)
                        && (shouldFlood(centreX, centreY, x - 1, y, r) || shouldFlood(centreX, centreY, x, y - 1, r) || shouldFlood(centreX, centreY, x + 1, y, r) || shouldFlood(centreX, centreY, x, y + 1, r))) {
                    // Edge block; the water level must not be higher than the
                    // lowest edge block so it doesn't spill over
                    waterLevel = height;
                }
            }
        }
        // Only lower the water level during each drag, never raise
        if (waterLevel > previousWaterLevel) {
            waterLevel = previousWaterLevel;
        } else {
            previousWaterLevel = waterLevel;
        }

        // Step 2: lower the terrain and flood with water or lava
        dim.setEventsInhibited(true);
        try {
            for (int x = centreX - r; x <= centreX + r; x++) {
                for (int y = centreY - r; y <= centreY + r; y++) {
                    float strength = getFullStrength(centreX, centreY, x, y);
                    if (shouldFlood(centreX, centreY, x, y, r)) {
                        // Should be flooded; lower terrain and add water or lava
                        float requiredHeight = waterLevel - strength / 0.75f * depth;
                        if (dim.getHeightAt(x, y) > requiredHeight) {
                            dim.setHeightAt(x, y, requiredHeight);
                        }
                        dim.setWaterLevelAt(x, y, waterLevel);
                        dim.setBitLayerValueAt(FloodWithLava.INSTANCE, x, y, lava);
                        if (! lava) {
                            dim.setTerrainAt(x, y, Terrain.BEACHES);
                        }
                    } else if (strength > 0.0f) {
                        // Should not be flooded; lower terrain proportionally and
                        // leave existing fluids alone
                        float maximumHeight = waterLevel + (float) (Math.tan(-strength * DOUBLE_PI + HALF_PI) / DOUBLE_PI);
                        if (dim.getHeightAt(x, y) > maximumHeight) {
                            dim.setHeightAt(x, y, maximumHeight);
                        }
                        if ((! lava) && ((maximumHeight - waterLevel) < 2)) {
                            dim.setTerrainAt(x, y, Terrain.BEACHES);
                        }
                    }
                }
            }
        } finally {
            dim.setEventsInhibited(false);
        }
    }

    private boolean shouldFlood(int centreX, int centreY, int x, int y, int r) {
        int dx = Math.abs(x - centreX), dy = Math.abs(y - centreY);
        return (dx <= r) && (dy <= r) && (getFullStrength(centreX, centreY, x, y) > 0.25f);
    }

    private boolean applyNativeRiverPaint(Dimension dimension, int centreX, int centreY, int radius) {
        final long diameterLong = 2L * radius + 1L;
        if (radius < 0 || diameterLong > 255L || getFilter() != null
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        final int diameter = (int) diameterLong;
        final int area = diameter * diameter;
        ensureNativeBuffers(area);
        ensureNativeWaterLevelOutput();

        int index = 0;
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int y = centreY - radius; y <= centreY + radius; y++) {
                final float strength = getFullStrength(centreX, centreY, x, y);
                nativeHeights[index] = dimension.getHeightAt(x, y);
                nativeTerrainHeights[index] = dimension.getIntHeightAt(x, y);
                nativeWaterLevels[index] = dimension.getWaterLevelAt(x, y);
                nativeStrengths[index] = strength;
                if (strength > 0.0f && strength <= 0.25f) {
                    nativeSlopeOffsets[index] = (float) (Math.tan(-strength * DOUBLE_PI + HALF_PI) / DOUBLE_PI);
                } else {
                    nativeSlopeOffsets[index] = 0.0f;
                }
                index++;
            }
        }

        if (!NativeSlices.applyRiverPaint(radius, previousWaterLevel, depth, lava,
                nativeHeights, nativeTerrainHeights, nativeWaterLevels, nativeStrengths,
                nativeSlopeOffsets, nativeHeightModified, nativeFlooded, nativeBeaches,
                nativeWaterLevelOutput)) {
            return false;
        }

        final int waterLevel = nativeWaterLevelOutput[0];
        previousWaterLevel = waterLevel;
        dimension.setEventsInhibited(true);
        try {
            index = 0;
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                for (int y = centreY - radius; y <= centreY + radius; y++) {
                    if (nativeFlooded[index] != 0) {
                        if (nativeHeightModified[index] != 0) {
                            dimension.setHeightAt(x, y, nativeHeights[index]);
                        }
                        dimension.setWaterLevelAt(x, y, waterLevel);
                        dimension.setBitLayerValueAt(FloodWithLava.INSTANCE, x, y, lava);
                        if (!lava) {
                            dimension.setTerrainAt(x, y, Terrain.BEACHES);
                        }
                    } else {
                        if (nativeHeightModified[index] != 0) {
                            dimension.setHeightAt(x, y, nativeHeights[index]);
                        }
                        if (nativeBeaches[index] != 0) {
                            dimension.setTerrainAt(x, y, Terrain.BEACHES);
                        }
                    }
                    index++;
                }
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
        return true;
    }

    private void ensureNativeBuffers(int area) {
        if (nativeHeights == null || nativeHeights.length != area) {
            nativeHeights = new float[area];
            nativeTerrainHeights = new int[area];
            nativeWaterLevels = new int[area];
            nativeStrengths = new float[area];
            nativeSlopeOffsets = new float[area];
            nativeHeightModified = new byte[area];
            nativeFlooded = new byte[area];
            nativeBeaches = new byte[area];
        }
    }

    private float depth;
    private int previousWaterLevel;
    private boolean lava;
    private float[] nativeHeights;
    private int[] nativeTerrainHeights;
    private int[] nativeWaterLevels;
    private float[] nativeStrengths;
    private float[] nativeSlopeOffsets;
    private byte[] nativeHeightModified;
    private byte[] nativeFlooded;
    private byte[] nativeBeaches;
    private int[] nativeWaterLevelOutput;

    private void ensureNativeWaterLevelOutput() {
        if (nativeWaterLevelOutput == null) {
            nativeWaterLevelOutput = new int[1];
        }
    }

    private static final double DOUBLE_PI = Math.PI * 2;
    private static final double HALF_PI = Math.PI / 2;
}
