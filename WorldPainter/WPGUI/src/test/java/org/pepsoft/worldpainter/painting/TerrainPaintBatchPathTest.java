package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.TileFactory;
import org.pepsoft.worldpainter.TileFactoryFactory;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public final class TerrainPaintBatchPathTest {
    @Test
    public void groupedStrokeMatchesPerPixelPathAcrossCompleteTiles() {
        assertStrokeParity(true, true);
    }

    @Test
    public void groupedStrokeMatchesPerPixelPathWithMissingTiles() {
        assertStrokeParity(false, true);
    }

    @Test
    public void nonInhibitedStrokeKeepsPerPixelPath() {
        assertStrokeParity(true, false);
    }

    private static void assertStrokeParity(boolean fourTiles, boolean inhibitEvents) {
        final Dimension reference = createDimension(fourTiles);
        final Dimension actual = createDimension(fourTiles);
        final DimensionPainter referencePainter = createPainter(new ReferenceTerrainPaint(Terrain.SAND));
        final DimensionPainter actualPainter = createPainter(new TerrainPaint(Terrain.SAND));

        if (inhibitEvents) {
            reference.setEventsInhibited(true);
            actual.setEventsInhibited(true);
        }
        try {
            referencePainter.drawLine(reference, -64, 0, 64, 0, false);
            actualPainter.drawLine(actual, -64, 0, 64, 0, false);
        } finally {
            if (inhibitEvents) {
                reference.setEventsInhibited(false);
                actual.setEventsInhibited(false);
            }
        }

        final int extent = fourTiles ? 256 : 128;
        for (int y = -128; y < -128 + extent; y++) {
            for (int x = -128; x < -128 + extent; x++) {
                assertEquals("terrain mismatch at " + x + "," + y,
                        reference.getTerrainAt(x, y), actual.getTerrainAt(x, y));
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

    private static DimensionPainter createPainter(Paint paint) {
        paint.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        paint.getBrush().setRadius(48);
        final DimensionPainter painter = new DimensionPainter();
        painter.setPaint(paint);
        return painter;
    }

    static final class ReferenceTerrainPaint extends AbstractPaint {
        private final Terrain terrain;

        ReferenceTerrainPaint(Terrain terrain) {
            this.terrain = terrain;
        }

        @Override
        public String getId() {
            return "ReferenceTerrain/" + terrain.ordinal();
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
                    if (dynamicLevel * getFullStrength(centreX, centreY, x, y) > 0.75f) {
                        dimension.setTerrainAt(x, y, terrain);
                    }
                }
            }
        }

        @Override
        public void remove(Dimension dimension, int x, int y, float dynamicLevel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyPixel(Dimension dimension, int x, int y) {
            dimension.setTerrainAt(x, y, terrain);
        }

        @Override
        public void removePixel(Dimension dimension, int x, int y) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BufferedImage getIcon(org.pepsoft.worldpainter.ColourScheme colourScheme) {
            return null;
        }
    }
}
