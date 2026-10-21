package org.pepsoft.worldpainter.painting;

import java.util.Random;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.LineStrokeAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class LineSetStrokeParityTest {
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static long draw(Dimension d, boolean nativeMode, boolean reverse, int[] line, int radius, boolean filtered) {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        System.setProperty("welt.native.lineSetStroke","true");
        Paint paint=LineSetStrokeBenchmark.paint(reverse);
        SymmetricBrush brush=SymmetricBrush.LINEAR_CIRCLE.clone();brush.setRadius(radius);brush.setLevel(.63f);paint.setBrush(brush);
        if(filtered)paint.setFilter((x,y,s)->s*.8f);
        DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);
        painter.setUndo(reverse&&!LineSetStrokeBenchmark.mode().equals("terrain"));
        long before=LineStrokeAccess.completedCalls();d.setEventsInhibited(true);
        try{painter.drawLine(d,line[0],line[1],line[2],line[3],.91f,false);}finally{d.setEventsInhibited(false);}
        return LineStrokeAccess.completedCalls()-before;
    }
    private static void same(Dimension left,Dimension right){
        for(Tile a:left.getTiles()){
            Tile b=right.getTile(a.getX(),a.getY());assertEquals(a.getLayers(),b.getLayers());
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){
                assertEquals(a.getTerrain(x,y),b.getTerrain(x,y));
                if(LineSetStrokeBenchmark.numericLayer()!=null)assertEquals(a.getLayerValue(LineSetStrokeBenchmark.numericLayer(),x,y),b.getLayerValue(LineSetStrokeBenchmark.numericLayer(),x,y));
                assertEquals(a.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE,x,y),b.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE,x,y));
                assertEquals(a.getBitLayerValue(LineSetStrokeBenchmark.CHUNK,x,y),b.getBitLayerValue(LineSetStrokeBenchmark.CHUNK,x,y));
            }
        }
    }
    @Test public void completeStrokesPreserveValuesStorageAndRandomStream() throws Exception {
        String[] keys={Native.GEN_KEY,"welt.native.lineSetStroke","welt.benchmark.strokePaint","welt.benchmark.strokeDither"};
        String[] saved=new String[keys.length];for(int i=0;i<keys.length;i++)saved[i]=System.getProperty(keys[i]);
        Random random=LineSetStrokeBenchmark.mathRandom();
        try{
            int[][] lines={{-190,-130,190,130},{190,130,-190,-130},{-64,-190,64,190},{0,0,0,0},{120,64,130,64},{-70,-70,-65,-65}};
            for(String mode:new String[]{"terrain","bit","chunk-bit","biome","annotations"})for(boolean dither:new boolean[]{false,true}){
                System.setProperty("welt.benchmark.strokePaint",mode);System.setProperty("welt.benchmark.strokeDither",Boolean.toString(dither));
                for(int radius:new int[]{0,3,17})for(boolean reverse:new boolean[]{false,true})for(int[] line:lines){
                    Dimension a=LineSetStrokeBenchmark.fixture(),b=LineSetStrokeBenchmark.fixture();
                    random.setSeed(7331);draw(a,false,reverse,line,radius,false);long expected=random.nextLong();
                    random.setSeed(7331);long calls=draw(b,true,reverse,line,radius,false);assertEquals("Random stream",expected,random.nextLong());
                    // A stroke wholly inside the missing tile legitimately makes no native call.
                    assertTrue(calls<=15);same(a,b);
                }
            }
        }finally{for(int i=0;i<keys.length;i++)restore(keys[i],saved[i]);}
    }
    @Test public void filtersKeepFallbackAndUndoRestoresGroupedChanges() throws Exception {
        String[] keys={Native.GEN_KEY,"welt.native.lineSetStroke","welt.benchmark.strokePaint","welt.benchmark.strokeDither"};
        String[] saved=new String[keys.length];for(int i=0;i<keys.length;i++)saved[i]=System.getProperty(keys[i]);
        Random random=LineSetStrokeBenchmark.mathRandom();
        try{
            for(String mode:new String[]{"terrain","bit","chunk-bit","biome","annotations"}){
                System.setProperty("welt.benchmark.strokePaint",mode);System.setProperty("welt.benchmark.strokeDither","true");
                int[] line={120,64,130,64};Dimension expected=LineSetStrokeBenchmark.fixture(),actual=LineSetStrokeBenchmark.fixture();
                random.setSeed(3);draw(expected,false,false,line,17,true);long next=random.nextLong();
                random.setSeed(3);assertEquals(0,draw(actual,true,false,line,17,true));assertEquals(next,random.nextLong());same(expected,actual);
                Dimension before=LineSetStrokeBenchmark.fixture(),after=LineSetStrokeBenchmark.fixture();
                UndoManager undo=new UndoManager();for(Tile tile:after.getTiles())tile.register(undo);undo.armSavePoint();
                assertTrue(draw(after,true,false,line,17,false)>0);assertTrue(undo.undo());same(before,after);assertTrue(undo.redo());
            }
        }finally{for(int i=0;i<keys.length;i++)restore(keys[i],saved[i]);}
    }
    @Test public void automaticSelectionKeepsSmallStrokesAndHonoursOverrides(){
        String flag=System.getProperty("welt.native.lineSetStroke");
        try{
            System.clearProperty("welt.native.lineSetStroke");
            assertTrue(LineStrokeAccess.isSetEnabled());
            assertTrue(LineStrokeAccess.useSetStroke(33,33,-240,0,240,0));
            assertFalse(LineStrokeAccess.useSetStroke(7,7,-240,0,240,0));
            assertFalse(LineStrokeAccess.useSetStroke(33,33,120,0,130,0));
            System.setProperty("welt.native.lineSetStroke","false");
            assertFalse(LineStrokeAccess.isSetEnabled());
            assertFalse(LineStrokeAccess.useSetStroke(33,33,-240,0,240,0));
            System.setProperty("welt.native.lineSetStroke","true");
            assertTrue(LineStrokeAccess.useSetStroke(7,7,120,0,130,0));
        }finally{restore("welt.native.lineSetStroke",flag);}
    }
}