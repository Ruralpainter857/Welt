/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.operations;

import org.pepsoft.util.DesktopUtils;
import org.pepsoft.util.IconUtils;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.WorldPainter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import javax.swing.*;
import java.awt.*;

import static javax.swing.BoxLayout.X_AXIS;

/**
 *
 * @author pepijn
 */
public class Flatten extends AbstractBrushOperation {
    public Flatten(WorldPainter view) {
        super("Flatten", "Flatten an area", view, 100, "operation.flatten");
    }

    @Override
    public JPanel getOptionsPanel() {
        return optionsPanel;
    }

    @Override
    protected void tick(final int centreX, final int centreY, final boolean inverse, final boolean first, final float dynamicLevel) {
        final Dimension dimension = getDimension();
        if (dimension == null) {
            // Probably some kind of race condition
            return;
        }
        if (first) {
            targetHeight = dimension.getHeightAt(centreX, centreY);
            if (targetHeight == -Float.MAX_VALUE) {
                DesktopUtils.beep();
            }
        }
        if (targetHeight == -Float.MAX_VALUE) {
            return;
        }
        dimension.setEventsInhibited(true);
        try {
            int radius = getEffectiveRadius();
            boolean applyTheme = options.isApplyTheme();
            if (flattenNative(dimension, centreX, centreY, radius,
                    dynamicLevel, applyTheme)) {
                return;
            }
            switch (mode) {
                case FLATTEN -> {
                    for (int x = centreX - radius; x <= centreX + radius; x++) {
                        for (int y = centreY - radius; y <= centreY + radius; y++) {
                            float currentHeight = dimension.getHeightAt(x, y);
                            float strength = dynamicLevel * getStrength(centreX, centreY, x, y);
                            if (strength > 0.0f) {
                                float newHeight = strength * targetHeight  + (1f - strength) * currentHeight;
                                dimension.setHeightAt(x, y, newHeight);
                                if (applyTheme) {
                                    dimension.applyTheme(x, y);
                                }
                            }
                        }
                    }
                }
                case RAISE -> {
                    for (int x = centreX - radius; x <= centreX + radius; x++) {
                        for (int y = centreY - radius; y <= centreY + radius; y++) {
                            float currentHeight = dimension.getHeightAt(x, y);
                            float strength = dynamicLevel * getStrength(centreX, centreY, x, y);
                            if (strength > 0.0f) {
                                float newHeight = strength * targetHeight  + (1f - strength) * currentHeight;
                                if (newHeight > currentHeight) {
                                    dimension.setHeightAt(x, y, newHeight);
                                    if (applyTheme) {
                                        dimension.applyTheme(x, y);
                                    }
                                }
                            }
                        }
                    }
                }
                case LOWER -> {
                    for (int x = centreX - radius; x <= centreX + radius; x++) {
                        for (int y = centreY - radius; y <= centreY + radius; y++) {
                            float currentHeight = dimension.getHeightAt(x, y);
                            float strength = dynamicLevel * getStrength(centreX, centreY, x, y);
                            if (strength > 0.0f) {
                                float newHeight = strength * targetHeight  + (1f - strength) * currentHeight;
                                if (newHeight < currentHeight) {
                                    dimension.setHeightAt(x, y, newHeight);
                                    if (applyTheme) {
                                        dimension.applyTheme(x, y);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private boolean flattenNative(Dimension dimension, int centreX, int centreY, int radius,
                                  float dynamicLevel, boolean applyTheme) {
        if (getFilter() != null || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        long diameterLong = 2L * radius + 1L;
        long areaLong = diameterLong * diameterLong;
        if (radius < 0 || diameterLong > 256L || areaLong > MAX_NATIVE_CELLS) {
            return false;
        }
        int area = (int) areaLong;
        ensureNativeBuffers(radius, area);

        int index = 0;
        for (int x = centreX - radius; x <= centreX + radius; x++) {
            for (int y = centreY - radius; y <= centreY + radius; y++) {
                nativeHeights[index] = dimension.getHeightAt(x, y);
                nativeStrengths[index] = dynamicLevel * getStrength(centreX, centreY, x, y);
                index++;
            }
        }

        int nativeMode = mode == Mode.FLATTEN ? 0 : mode == Mode.RAISE ? 1 : 2;
        if (!NativeSlices.applyFlattenBrush(nativeMode, targetHeight,
                nativeHeights, nativeStrengths, nativeModified)) {
            flattenHeightBufferInJava(nativeMode, targetHeight,
                    nativeHeights, nativeStrengths, nativeModified);
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

    private void ensureNativeBuffers(int radius, int area) {
        if (nativeRadius != radius) {
            nativeRadius = radius;
            nativeHeights = new float[area];
            nativeStrengths = new float[area];
            nativeModified = new byte[area];
        }
    }

    private static void flattenHeightBufferInJava(int mode, float targetHeight,
                                                  float[] heights, float[] strengths,
                                                  byte[] modified) {
        for (int index = 0; index < heights.length; index++) {
            float currentHeight = heights[index];
            float strength = strengths[index];
            if (strength > 0.0f) {
                float newHeight = strength * targetHeight + (1f - strength) * currentHeight;
                boolean shouldWrite = (mode == 0)
                        || ((mode == 1) && (newHeight > currentHeight))
                        || ((mode == 2) && (newHeight < currentHeight));
                if (shouldWrite) {
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
    
    private final TerrainShapingOptions<Flatten> options = new TerrainShapingOptions<>();
    private final TerrainShapingOptionsPanel optionsPanel = new TerrainShapingOptionsPanel("Flatten", "<p>Click to flatten the surrounding terrain to the level at that location", options) {
        @Override
        protected void addAdditionalComponents(GridBagConstraints constraints) {
            final ButtonGroup buttonGroup = new ButtonGroup();

            JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, X_AXIS));
            buttonPanel.add(new JLabel(FLATTEN_ICON));
            final JRadioButton flattenButton = new JRadioButton("Flatten in both directions", true);
            flattenButton.addActionListener(e -> mode = Mode.FLATTEN);
            buttonGroup.add(flattenButton);
            buttonPanel.add(flattenButton);
            add(buttonPanel, constraints);

            buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, X_AXIS));
            buttonPanel.add(new JLabel(RAISE_ICON));
            final JRadioButton raiseButton = new JRadioButton("Only raise terrain", true);
            raiseButton.addActionListener(e -> mode = Mode.RAISE);
            buttonGroup.add(raiseButton);
            buttonPanel.add(raiseButton);
            add(buttonPanel, constraints);

            buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, X_AXIS));
            buttonPanel.add(new JLabel(LOWER_ICON));
            final JRadioButton lowerButton = new JRadioButton("Only lower terrain", true);
            lowerButton.addActionListener(e -> mode = Mode.LOWER);
            buttonGroup.add(lowerButton);
            buttonPanel.add(lowerButton);
            add(buttonPanel, constraints);

            super.addAdditionalComponents(constraints);
        }
    };
    private float targetHeight = -Float.MAX_VALUE;
    private Mode mode = Mode.FLATTEN;
    private int nativeRadius = -1;
    private float[] nativeHeights, nativeStrengths;
    private byte[] nativeModified;

    private static final Icon FLATTEN_ICON = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/flatten.png");
    private static final Icon RAISE_ICON = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/raise.png");
    private static final Icon LOWER_ICON = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/lower.png");

    enum Mode { FLATTEN, RAISE, LOWER}
    private static final int MAX_NATIVE_CELLS = 65_536;
}
