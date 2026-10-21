package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.TileFactory;
import org.pepsoft.worldpainter.TileFactoryFactory;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Layer;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public final class BitLayerPaintBatchPathTest {
    @Test
    public void groupedStrokeMatchesPerPixelPathAcrossCompleteTiles() {
        assertStrokeParity(true, true, false);
    }

    @Test
    public void groupedStrokeMatchesPerPixelPathWithMissingTiles() {
        assertStrokeParity(false, true, false);
    }

    @Test
    public void nonInhibitedStrokeKeepsPerPixelPath() {
        assertStrokeParity(true, false, false);
    }

    @Test
    public void deterministicDitherStrokeMatchesPerPixelPath() {
        assertStrokeParity(true, true, true);
    }

    private static void assertStrokeParity(boolean fourTiles, boolean inhibitEvents, boolean dither) {
        final Dimension reference = createDimension(fourTiles);
        final Dimension actual = createDimension(fourTiles);
        final DimensionPainter referencePainter = createPainter(new ReferenceBitLayerPaint(Frost.INSTANCE), dither);
        final DimensionPainter actualPainter = createPainter(new BitLayerPaint(Frost.INSTANCE), dither);

        if (inhibitEvents) {
            reference.setEventsInhibited(true);
            actual.setEventsInhibited(true);
        }
        try {
            referencePainter.drawLine(reference, -64, 0, 64, 0, false);
            actualPainter.drawLine(actual, -64, 0, 64, 0, false);
            assertLayerParity(reference, actual, fourTiles);

            referencePainter.setUndo(true);
            actualPainter.setUndo(true);
            referencePainter.drawLine(reference, -64, 0, 64, 0, false);
            actualPainter.drawLine(actual, -64, 0, 64, 0, false);
        } finally {
            if (inhibitEvents) {
                reference.setEventsInhibited(false);
                actual.setEventsInhibited(false);
            }
        }
        assertLayerParity(reference, actual, fourTiles);
    }

    private static void assertLayerParity(Dimension expected, Dimension actual, boolean fourTiles) {
        final int extent = fourTiles ? 256 : 128;
        for (int y = -128; y < -128 + extent; y++) {
            for (int x = -128; x < -128 + extent; x++) {
                final Tile expectedTile = expected.getTile(x >> 7, y >> 7);
                final Tile actualTile = actual.getTile(x >> 7, y >> 7);
                final boolean expectedValue = (expectedTile != null)
                        && expectedTile.getBitLayerValue(Frost.INSTANCE, x & 127, y & 127);
                final boolean actualValue = (actualTile != null)
                        && actualTile.getBitLayerValue(Frost.INSTANCE, x & 127, y & 127);
                assertEquals("layer mismatch at " + x + "," + y, expectedValue, actualValue);
            }
        }
    }

    private static Dimension createDimension(boolean fourTiles) {
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        final World2 world = new World2(platform, platform.minZ, platform.standardMaxHeight);
        final TileFactory tileFactory = TileFactoryFactory.createFlatTileFactory(
                17L, Terrain.GRASS, platform.minZ, platform.standardMaxHeight,
                62, 62, false, false);
        final Dimension dimension = new Dimension(world, "Surface", 17L, tileFactory,
                Dimension.Anchor.NORMAL_DETAIL);
        final int maxTile = fourTiles ? 0 : -1;
        for (int tileX = -1; tileX <= maxTile; tileX++) {
            for (int tileY = -1; tileY <= maxTile; tileY++) {
                dimension.addTile(tileFactory.createTile(tileX, tileY));
            }
        }
        return dimension;
    }

    private static DimensionPainter createPainter(Paint paint, boolean dither) {
        final Brush brush = (dither ? SymmetricBrush.CONSTANT_CIRCLE : SymmetricBrush.LINEAR_CIRCLE).clone();
        brush.setRadius(48);
        paint.setBrush(brush);
        paint.setDither(dither);
        final DimensionPainter painter = new DimensionPainter();
        painter.setPaint(paint);
        return painter;
    }

    static final class ReferenceBitLayerPaint extends AbstractPaint {
        private final Layer layer;

        ReferenceBitLayerPaint(Layer layer) {
            this.layer = layer;
        }

        @Override
        public String getId() {
            return "ReferenceBitLayer/" + layer.getId();
        }

        @Override
        public void apply(Dimension dimension, int centreX, int centreY, float dynamicLevel) {
            if (brush.getRadius() == 0) {
                applyPixel(dimension, centreX, centreY);
                return;
            }
            final Rectangle bounds = brush.getBoundingBox();
            final int x1 = centreX + bounds.x, y1 = centreY + bounds.y;
            final int x2 = x1 + bounds.width - 1, y2 = y1 + bounds.height - 1;
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    final float strength = dynamicLevel * (dither
                            ? getStrength(centreX, centreY, x, y)
                            : getFullStrength(centreX, centreY, x, y));
                    if (dither ? ((strength > 0.95f) || (Math.random() < strength)) : (strength > 0.75f)) {
                        dimension.setBitLayerValueAt(layer, x, y, true);
                    }
                }
            }
        }

        @Override
        public void remove(Dimension dimension, int centreX, int centreY, float dynamicLevel) {
            if (brush.getRadius() == 0) {
                removePixel(dimension, centreX, centreY);
                return;
            }
            final Rectangle bounds = brush.getBoundingBox();
            final int x1 = centreX + bounds.x, y1 = centreY + bounds.y;
            final int x2 = x1 + bounds.width - 1, y2 = y1 + bounds.height - 1;
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    final float strength = dynamicLevel * getFullStrength(centreX, centreY, x, y);
                    if (dither ? ((strength > 0.95f) || (Math.random() < strength)) : (strength > 0.75f)) {
                        dimension.setBitLayerValueAt(layer, x, y, false);
                    }
                }
            }
        }

        @Override
        public void applyPixel(Dimension dimension, int x, int y) {
            dimension.setBitLayerValueAt(layer, x, y, true);
        }

        @Override
        public void removePixel(Dimension dimension, int x, int y) {
            dimension.setBitLayerValueAt(layer, x, y, false);
        }

        @Override
        public BufferedImage getIcon(org.pepsoft.worldpainter.ColourScheme colourScheme) {
            return layer.getIcon();
        }
    }
}
