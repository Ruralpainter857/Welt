package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.util.undo.UndoManager;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class DitheredPaintParityTest {
    private static void ready(){org.junit.Assume.assumeTrue(Boolean.getBoolean("welt.test.requireDitheredPaint"));
        java.nio.ByteBuffer frame=java.nio.ByteBuffer.allocateDirect(48+16384+1).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        frame.putInt(0,0x4d424c57).putInt(4,1).putInt(16,1).putInt(20,1).putInt(24,8).putInt(28,128).put(48+16384,(byte)1);
        assertTrue(org.pepsoft.worldpainter.nativeapi.NativeSlices.editMaskedPlane(frame));}
    private static Paint painter(String kind,DitheredDiscreteBenchmark.Draws random,int radius){Paint paint=DitheredPaintBenchmark.paint(kind,random,false);
        var brush=SymmetricBrush.COSINE_CIRCLE.clone();brush.setRadius(radius);brush.setLevel(0.63f);paint.setBrush(brush);paint.setDither(true);return paint;}
    private static void mode(boolean nativeMode){System.setProperty("welt.native.ditheredPaint",Boolean.toString(nativeMode));}
    private static int[] events(Dimension world){int[] counts={0,0};for(Tile tile:world.getTiles())tile.addListener((Tile.Listener)Proxy.newProxyInstance(
        Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(object,method,args)->{if(method.getName().equals("terrainChanged"))counts[0]++;if(method.getName().equals("layerDataChanged"))counts[1]++;return null;}));return counts;}
    private static void same(Dimension a,Dimension b){for(Tile tile:a.getTiles()){Tile other=b.getTile(tile.getX(),tile.getY());
        assertEquals(tile.hasLayer(Frost.INSTANCE),other.hasLayer(Frost.INSTANCE));assertEquals(tile.hasLayer(Populate.INSTANCE),other.hasLayer(Populate.INSTANCE));
        for(int y=0;y<128;y++)for(int x=0;x<128;x++){assertEquals(tile.getTerrain(x,y),other.getTerrain(x,y));
            assertEquals(tile.getBitLayerValue(Frost.INSTANCE,x,y),other.getBitLayerValue(Frost.INSTANCE,x,y));assertEquals(tile.getBitLayerValue(Populate.INSTANCE,x,y),other.getBitLayerValue(Populate.INSTANCE,x,y));
            assertEquals(tile.getLayerValue(Resources.INSTANCE,x,y),other.getLayerValue(Resources.INSTANCE,x,y));assertEquals(tile.getRawHeight(x,y),other.getRawHeight(x,y));assertEquals(tile.getWaterLevel(x,y),other.getWaterLevel(x,y));}}
    }
    @Test public void completeStrokesKeepValuesEventsAndNextRandomValue(){ready();for(String kind:new String[]{"terrain","bit","chunk"})for(int radius:new int[]{0,3,63,127,511}){
        Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();
        Paint left=painter(kind,ra,radius),right=painter(kind,rb,radius);int[] ea=events(a),eb=events(b);
        for(int centre:new int[]{-1,64,127}){a.setEventsInhibited(true);b.setEventsInhibited(true);mode(false);left.apply(a,centre,centre,0.74f);if(!kind.equals("terrain"))left.remove(a,centre,centre,0.74f);
            mode(true);right.apply(b,centre,centre,0.74f);if(!kind.equals("terrain"))right.remove(b,centre,centre,0.74f);
            a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);assertArrayEquals(ea,eb);assertEquals(ra.count,rb.count);assertEquals(Double.doubleToLongBits(ra.getAsDouble()),Double.doubleToLongBits(rb.getAsDouble()));}
    }}
    @Test public void thresholdCornerCasesAndMissingSingleTileKeepDraws(){ready();for(String kind:new String[]{"terrain","bit","chunk"})for(float level:new float[]{0f,-1f,0.95f,Math.nextUp(0.95f),1f,Float.NaN,Float.POSITIVE_INFINITY}){
        Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();Paint left=painter(kind,ra,3),right=painter(kind,rb,3);
        a.setEventsInhibited(true);b.setEventsInhibited(true);mode(false);left.apply(a,64,64,level);mode(true);right.apply(b,64,64,level);a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);assertEquals(ra.count,rb.count);
        Dimension missing=NibbleLayerPaintParityTest.fixture();var random=new DitheredDiscreteBenchmark.Draws();Paint paint=painter(kind,random,3);missing.setEventsInhibited(true);paint.apply(missing,-64,-64,level);missing.setEventsInhibited(false);assertEquals(0,random.count);
    }}
    @Test public void undoRedoRestoreTerrainAndBothBitFormats(){ready();for(String kind:new String[]{"terrain","bit","chunk"}){
        Dimension world=NibbleLayerPaintParityTest.fixture();UndoManager undo=new UndoManager();for(Tile tile:world.getTiles())tile.register(undo);undo.armSavePoint();long before=DitheredPaintBenchmark.hash(world,kind);
        Paint paint=painter(kind,new DitheredDiscreteBenchmark.Draws(),127);mode(true);world.setEventsInhibited(true);paint.apply(world,0,0,0.74f);world.setEventsInhibited(false);long after=DitheredPaintBenchmark.hash(world,kind);
        assertNotEquals(before,after);assertTrue(undo.undo());assertEquals(before,DitheredPaintBenchmark.hash(world,kind));assertTrue(undo.redo());assertEquals(after,DitheredPaintBenchmark.hash(world,kind));
    }}
    @Test public void filtersImmediateEventsAndThemeRemovalStayOnOriginalPaths(){ready();for(String kind:new String[]{"terrain","bit","chunk"})for(boolean filtered:new boolean[]{false,true}){
        Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();var ra=new DitheredDiscreteBenchmark.Draws();var rb=new DitheredDiscreteBenchmark.Draws();Paint left=painter(kind,ra,3),right=painter(kind,rb,3);int[] ea=events(a),eb=events(b);
        if(filtered){left.setFilter((x,y,strength)->strength*0.5f);right.setFilter((x,y,strength)->strength*0.5f);a.setEventsInhibited(true);b.setEventsInhibited(true);}
        mode(false);left.apply(a,64,64,0.74f);left.remove(a,64,64,0.74f);mode(true);right.apply(b,64,64,0.74f);right.remove(b,64,64,0.74f);
        if(filtered){a.setEventsInhibited(false);b.setEventsInhibited(false);}same(a,b);assertArrayEquals(ea,eb);assertEquals(ra.count,rb.count);
    }}
    @Test public void terrainMaskPaddingPreservesCoordinates(){ready();Dimension a=NibbleLayerPaintParityTest.fixture(),b=NibbleLayerPaintParityTest.fixture();byte[] mask=new byte[512];
        for(int y=0;y<13;y++)for(int x=0;x<17;x++)mask[7+y*23+x]=(byte)((x+y)%3==0?0:1);a.setEventsInhibited(true);b.setEventsInhibited(true);
        for(int y=0;y<13;y++)for(int x=0;x<17;x++)if(mask[7+y*23+x]!=0)a.setTerrainAt(120+x,123+y,Terrain.CUSTOM_96);
        MaskedPlaneAccess.applyTerrain(b,Terrain.CUSTOM_96,120,123,17,13,mask,7,23);a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);
    }
}