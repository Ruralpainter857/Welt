package org.pepsoft.worldpainter.painting;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Original scalar glyph renderer is independent of the production drawText implementation. */
public class TextPaintParityTest {
    @Test public void glyphsMatchForAllPaintsRotationsAndEraseModes() {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "bit", "chunk", "nibble", "combined"})
                for (int angle = 0; angle < 4; angle++) for (boolean undo : new boolean[] {false, true}) {
                    System.setProperty(Native.GEN_KEY, "false"); Dimension expected = NibbleLayerPaintParityTest.fixture();
                    Dimension actual = NibbleLayerPaintParityTest.fixture(); Paint paint = TextPaintBenchmark.paint(type);
                    Font font = new Font("Dialog", Font.BOLD, 19); String text = "Welt — 世界\nRust\n\nSnow";
                    expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                    scalar(expected, paint, font, angle, undo, -9, 4, text);
                    System.setProperty(Native.GEN_KEY, "true");
                    DimensionPainter painter = painter(paint, font, angle, undo); painter.drawText(actual, -9, 4, text);
                    expected.setEventsInhibited(false); actual.setEventsInhibited(false); same(expected, actual);
                }
        } finally {restore(old);}
    }
    @Test public void largeFontsSpacesAndExtendedGlyphImagesKeepExactShapes() {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (String family : new String[] {"Dialog", "Serif", "Monospaced"})
                for (int size : new int[] {7, 120}) {
                    System.setProperty(Native.GEN_KEY, "false"); Dimension expected = NibbleLayerPaintParityTest.fixture(), actual = NibbleLayerPaintParityTest.fixture();
                    Paint paint = TextPaintBenchmark.paint("biome"); Font font = new Font(family, Font.ITALIC, size);
                    expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                    String text = "    \nWelt large glyph image 0123456789\n\n";
                    scalar(expected, paint, font, 1, false, -130, 129, text);
                    System.setProperty(Native.GEN_KEY, "true"); painter(paint,font,1,false).drawText(actual,-130,129,text);
                    expected.setEventsInhibited(false);actual.setEventsInhibited(false);same(expected,actual);
                }
        } finally {restore(old);}
    }
    @Test public void completeTextEditsPreserveUndoAndRedo() {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension before = NibbleLayerPaintParityTest.fixture();
            Dimension expected = NibbleLayerPaintParityTest.fixture(), actual = NibbleLayerPaintParityTest.fixture();
            Paint paint = TextPaintBenchmark.paint("combined"); Font font = new Font("Dialog",Font.BOLD,32);
            UndoManager undo = new UndoManager(); actual.registerUndoManager(undo);undo.armSavePoint();
            expected.setEventsInhibited(true);scalar(expected,paint,font,3,false,-4,-3,"Welt\nRust");expected.setEventsInhibited(false);
            System.setProperty(Native.GEN_KEY,"true");actual.setEventsInhibited(true);
            painter(paint,font,3,false).drawText(actual,-4,-3,"Welt\nRust");actual.setEventsInhibited(false);
            same(expected,actual);assertTrue(undo.undo());same(before,actual);assertTrue(undo.redo());same(expected,actual);
        } finally {restore(old);}
    }
    static DimensionPainter painter(Paint paint, Font font, int angle, boolean undo) {
        DimensionPainter painter = new DimensionPainter();painter.setPaint(paint);painter.setFont(font);
        painter.setTextAngle(angle);painter.setUndo(undo);return painter;
    }
    static void scalar(Dimension dimension, Paint paint, Font font, int angle, boolean undo, int x, int y, String text) {
        for (String line : text.split("\n")) {
            BufferedImage image = new BufferedImage(1000,100,BufferedImage.TYPE_BYTE_BINARY);
            Graphics2D g = image.createGraphics(); Rectangle2D bounds; int width, height;
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);g.setFont(font);
                bounds = font.getStringBounds(line,g.getFontRenderContext());width = (int)Math.ceil(bounds.getWidth());height = (int)Math.ceil(bounds.getHeight());
                if(width>1000 || height>100) {
                    g.dispose();image = new BufferedImage(width,height,BufferedImage.TYPE_BYTE_BINARY);g = image.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);g.setFont(font);
                }
                if(width>0 && height>0) g.drawString(line,(int)-bounds.getX(),(int)-bounds.getY());
            } finally {g.dispose();}
            for(int xx=0;xx<width;xx++) for(int yy=0;yy<height;yy++) if((image.getRGB(xx,yy)&1)!=0) {
                int wx = x + (angle==0?xx:angle==1?yy:angle==2?-xx:-yy);
                int wy = y + (angle==0?yy:angle==1?-xx:angle==2?-yy:xx);
                if(undo) paint.removePixel(dimension,wx,wy);else paint.applyPixel(dimension,wx,wy);
            }
            int lineHeight = (int)bounds.getHeight();
            switch(angle) {case 0:y+=lineHeight;break;case 1:x+=lineHeight;break;case 2:y-=lineHeight;break;case 3:x-=lineHeight;break;default:break;}
        }
    }
    static void same(Dimension a, Dimension b) {
        assertEquals(a.getTileCoords(),b.getTileCoords());
        for(Tile tile:a.getTiles()) {
            Tile other=b.getTile(tile.getX(),tile.getY());assertEquals(tile.getLayers(),other.getLayers());
            for(int x=0;x<128;x++) for(int y=0;y<128;y++) {
                assertEquals(tile.getRawHeight(x,y),other.getRawHeight(x,y));assertEquals(tile.getWaterLevel(x,y),other.getWaterLevel(x,y));
                assertEquals(tile.getTerrain(x,y),other.getTerrain(x,y));
                for(Layer layer:tile.getLayers()) {
                    if(layer.dataSize==Layer.DataSize.BIT || layer.dataSize==Layer.DataSize.BIT_PER_CHUNK)
                        assertEquals(tile.getBitLayerValue(layer,x,y),other.getBitLayerValue(layer,x,y));
                    else assertEquals(tile.getLayerValue(layer,x,y),other.getLayerValue(layer,x,y));
                }
            }
        }
    }
    private static void restore(String old) {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
}
