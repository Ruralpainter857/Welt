/*
 * Copyright (C) 2014 pepijn
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.pepsoft.worldpainter.heightMaps;

import org.pepsoft.util.IconUtils;
import org.pepsoft.worldpainter.HeightMap;

import javax.swing.*;
import java.awt.*;

/**
 * A height map which calculates the slope of an underlying height map, in
 * degrees from 0 to 90.
 *
 * @author pepijn
 */
public class SlopeHeightMap extends DelegatingHeightMap {
    public SlopeHeightMap(HeightMap baseHeightMap) {
        this(baseHeightMap, 1.0f);
    }

    public SlopeHeightMap(HeightMap baseHeightMap, String name) {
        this(baseHeightMap, 1.0f, name);
    }
    
    public SlopeHeightMap(HeightMap baseHeightMap, float verticalScaling) {
        super("baseHeightMap");
        setName(baseHeightMap.getName() != null ? "Slope of " + baseHeightMap.getName() : null);
        this.verticalScaling = verticalScaling;
        setHeightMap(0, baseHeightMap);
    }

    public SlopeHeightMap(HeightMap baseHeightMap, float verticalScaling, String name) {
        super("baseHeightMap");
        setName(name);
        this.verticalScaling = verticalScaling;
        setHeightMap(0, baseHeightMap);
    }

    public HeightMap getBaseHeightMap() {
        return children[0];
    }

    public void setBaseHeightMap(HeightMap baseHeightMap) {
        replace(0, baseHeightMap);
    }

    public float getVerticalScaling() {
        return verticalScaling;
    }

    public void setVerticalScaling(float verticalScaling) {
        this.verticalScaling = verticalScaling;
    }

    // HeightMap

    @Override
    protected double doGetHeight(float x, float y) {
        HeightMap baseHeightMap = children[0];
        if (verticalScaling != 1.0f) {
            return Math.tan(Math.max(Math.max(Math.abs(baseHeightMap.getHeight(x + 1, y) / verticalScaling - baseHeightMap.getHeight(x - 1, y) / verticalScaling) / 2,
                Math.abs(baseHeightMap.getHeight(x + 1, y + 1) / verticalScaling - baseHeightMap.getHeight(x - 1, y - 1) / verticalScaling) / ROOT_EIGHT),
                Math.max(Math.abs(baseHeightMap.getHeight(x, y + 1) / verticalScaling - baseHeightMap.getHeight(x, y - 1) / verticalScaling) / 2,
                Math.abs(baseHeightMap.getHeight(x - 1, y + 1) / verticalScaling - baseHeightMap.getHeight(x + 1, y - 1) / verticalScaling) / ROOT_EIGHT))) * RADIANS_TO_DEGREES;
        } else {
            return Math.tan(Math.max(Math.max(Math.abs(baseHeightMap.getHeight(x + 1, y) / verticalScaling - baseHeightMap.getHeight(x - 1, y)) / 2,
                Math.abs(baseHeightMap.getHeight(x + 1, y + 1) - baseHeightMap.getHeight(x - 1, y - 1)) / ROOT_EIGHT),
                Math.max(Math.abs(baseHeightMap.getHeight(x, y + 1) - baseHeightMap.getHeight(x, y - 1)) / 2,
                Math.abs(baseHeightMap.getHeight(x - 1, y + 1) - baseHeightMap.getHeight(x + 1, y - 1)) / ROOT_EIGHT))) * RADIANS_TO_DEGREES;
        }
    }

    /**
     * Computes a slope tile from an already sampled base-map buffer with a one-cell halo.
     * The input is row-major and the output contains {@code inputWidth - 2} by
     * {@code inputHeight - 2} values, also row-major.
     */
    public boolean fillSamples(double[] baseSamples, int inputWidth, int inputHeight, double[] samples) {
        final int outputWidth = inputWidth - 2;
        final int outputHeight = inputHeight - 2;
        if ((baseSamples == null) || (samples == null) || (outputWidth <= 0) || (outputHeight <= 0)
                || ((long) inputWidth * inputHeight != baseSamples.length)
                || ((long) outputWidth * outputHeight != samples.length)) {
            return false;
        }
        final boolean scaled = verticalScaling != 1.0f;
        for (int y = 0; y < outputHeight; y++) {
            final int sourceRow = (y + 1) * inputWidth;
            final int northRow = sourceRow - inputWidth;
            final int southRow = sourceRow + inputWidth;
            final int outputRow = y * outputWidth;
            for (int x = 0; x < outputWidth; x++) {
                final int west = sourceRow + x;
                final int centre = west + 1;
                final int east = west + 2;
                final double horizontal;
                if (scaled) {
                    horizontal = Math.abs((baseSamples[east] / verticalScaling
                            - baseSamples[west] / verticalScaling) / 2);
                } else {
                    horizontal = Math.abs((baseSamples[east] / verticalScaling - baseSamples[west]) / 2);
                }
                final double diagonal1;
                final double vertical;
                final double diagonal2;
                if (scaled) {
                    diagonal1 = Math.abs((baseSamples[southRow + east - sourceRow] / verticalScaling
                            - baseSamples[northRow + west - sourceRow] / verticalScaling) / ROOT_EIGHT);
                    vertical = Math.abs((baseSamples[southRow + centre - sourceRow] / verticalScaling
                            - baseSamples[northRow + centre - sourceRow] / verticalScaling) / 2);
                    diagonal2 = Math.abs((baseSamples[southRow + west - sourceRow] / verticalScaling
                            - baseSamples[northRow + east - sourceRow] / verticalScaling) / ROOT_EIGHT);
                } else {
                    diagonal1 = Math.abs((baseSamples[southRow + east - sourceRow]
                            - baseSamples[northRow + west - sourceRow]) / ROOT_EIGHT);
                    vertical = Math.abs((baseSamples[southRow + centre - sourceRow]
                            - baseSamples[northRow + centre - sourceRow]) / 2);
                    diagonal2 = Math.abs((baseSamples[southRow + west - sourceRow]
                            - baseSamples[northRow + east - sourceRow]) / ROOT_EIGHT);
                }
                final double maximum = Math.max(Math.max(horizontal, diagonal1), Math.max(vertical, diagonal2));
                samples[outputRow + x] = Math.tan(maximum) * RADIANS_TO_DEGREES;
            }
        }
        return true;
    }

    @Override
    public Rectangle getExtent() {
        return children[0].getExtent();
    }

    @Override
    public SlopeHeightMap clone() {
        return new SlopeHeightMap(children[0].clone(), name);
    }

    @Override
    public Icon getIcon() {
        return ICON_SLOPE_HEIGHTMAP;
    }

    @Override
    public double[] getRange() {
        return RANGE;
    }

    private float verticalScaling;

    private static final long serialVersionUID = 1L;
    private static final double ROOT_EIGHT = Math.sqrt(8.0);
    private static final double RADIANS_TO_DEGREES = 180 / Math.PI;
    private static final Icon ICON_SLOPE_HEIGHTMAP = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/integral.png");
    private static final double[] RANGE = {0.0, 90.0};
}
