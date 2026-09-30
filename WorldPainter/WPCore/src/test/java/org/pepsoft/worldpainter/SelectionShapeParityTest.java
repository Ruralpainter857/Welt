package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import org.pepsoft.worldpainter.selection.SelectionHelper;

import java.awt.*;
import java.awt.geom.*;

import static org.junit.Assert.*;

public class SelectionShapeParityTest {
    @Test
    public void completeShapeEditsMatchJavaIncludingChunkPromotionAndDemotion() {
        assertTrue(NativeLoader.areSlicesAvailable());
        final String previous = System.getProperty(Native.GEN_KEY);
        try {
            final Dimension java = fixture(), rust = fixture();
            final Shape[] shapes = {new Rectangle(-23, -17, 179, 93),
                    new Ellipse2D.Double(-47.5, -52.75, 211.2, 219.7),
                    new RoundRectangle2D.Double(7.1, 8.2, 93.3, 91.4, 17.5, 25.6),
                    new Polygon(new int[] {-64, 171, -3}, new int[] {-39, 57, 163}, 3),
                    new Rectangle(16, 32, 16, 16), new Rectangle(-128, -128, 128, 128),
                    new Rectangle(0, 0, 0, 0), new Rectangle2D.Double(16, 32, 15.5, 15.5),
                    new Area(new Ellipse2D.Double(-11.2, 8.5, 167.3, 170.7))};
            for (int repeat = 0; repeat < 3; repeat++) {
                for (Shape shape : shapes) {
                    final boolean add = repeat == 0 || repeat == 2;
                    edit(java, shape, add, false);
                    edit(rust, shape, add, true);
                    assertPlanesEqual(java, rust);
                }
            }
        } finally {
            restore(previous);
        }
    }

    @Test
    public void groupedEditPreservesUndoAndOtherBitLayers() {
        final String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            final Tile tile = new Tile(0, 0, 0, 256);
            tile.setBitLayerValue(SelectionChunk.INSTANCE, 16, 32, true);
            tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 1, 2, true);
            final UndoManager undo = new UndoManager();
            tile.register(undo);
            undo.armSavePoint();
            tile.inhibitEvents();
            try {
                assertTrue("must execute native path", tile.editSelectionShape(new Rectangle(17, 33, 7, 6), false));
            } finally {
                tile.releaseEvents();
            }
            assertFalse(tile.getBitLayerValue(SelectionChunk.INSTANCE, 16, 32));
            assertFalse(tile.getBitLayerValue(SelectionBlock.INSTANCE, 18, 34));
            assertTrue(tile.getBitLayerValue(SelectionBlock.INSTANCE, 16, 32));
            assertTrue(tile.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 1, 2));
            assertTrue(undo.undo());
            assertTrue(tile.getBitLayerValue(SelectionChunk.INSTANCE, 16, 32));
            assertFalse(tile.hasLayer(SelectionBlock.INSTANCE));
            assertTrue(undo.redo());
            assertFalse(tile.getBitLayerValue(SelectionChunk.INSTANCE, 16, 32));
        } finally {
            restore(previous);
        }
    }

    static Dimension fixture() {
        final Dimension dimension = TestData.createDimension(new Rectangle(-128, -128, 384, 384), 62);
        for (Tile tile : dimension.getTiles()) {
            tile.setBitLayerValue(SelectionChunk.INSTANCE, 16, 32, true);
            tile.setBitLayerValue(SelectionChunk.INSTANCE, 112, 112, true);
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    if ((x * 7 + y) % 17 == 0) tile.setBitLayerValue(SelectionBlock.INSTANCE, x, y, true);
                }
            }
        }
        return dimension;
    }

    static void edit(Dimension dimension, Shape shape, boolean add, boolean nativeMode) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        dimension.setEventsInhibited(true);
        try {
            final SelectionHelper helper = new SelectionHelper(dimension);
            if (add) helper.addToSelection(shape); else helper.removeFromSelection(shape);
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private static void assertPlanesEqual(Dimension expected, Dimension actual) {
        for (Tile tile : expected.getTiles()) {
            final Tile other = actual.getTile(tile.getX(), tile.getY());
            assertEquals(tile.bitLayerData, other.bitLayerData);
            assertEquals(tile.getLayers(), other.getLayers());
        }
    }

    private static void restore(String value) {
        if (value == null) System.clearProperty(Native.GEN_KEY);
        else System.setProperty(Native.GEN_KEY, value);
    }
}
