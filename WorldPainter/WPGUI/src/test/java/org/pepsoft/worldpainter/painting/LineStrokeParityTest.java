package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class LineStrokeParityTest {
    private static final Layer DEFAULT_ZERO=new Layer("welt.test.stroke.zero", "Default zero", "", Layer.DataSize.NIBBLE, false, 30) { };
    private static final Layer DEFAULT_THREE=new Layer("welt.test.stroke.default", "Default three", "", Layer.DataSize.NIBBLE, false, 30) {
        @Override public int getDefaultValue(){return 3;}
    };
    private static Brush brush(int radius){SymmetricBrush b=SymmetricBrush.LINEAR_CIRCLE.clone();b.setRadius(radius);b.setLevel(.63f);return b;}
    private static long draw(Dimension dimension, Layer layer, Brush brush, int[] line, boolean undo, boolean nativeMode, boolean inhibited) {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        System.setProperty("welt.native.lineStroke","true");
        NibbleLayerPaint paint=new NibbleLayerPaint(layer);paint.setBrush(brush);
        DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);painter.setUndo(undo);
        long before=LineStrokeAccess.completedCalls();
        if(inhibited)dimension.setEventsInhibited(true);
        try{painter.drawLine(dimension,line[0],line[1],line[2],line[3],.73f,false);}
        finally{if(inhibited)dimension.setEventsInhibited(false);}
        return LineStrokeAccess.completedCalls()-before;
    }
    private static void same(Dimension expected, Dimension actual, Layer layer){
        for(Tile left:expected.getTiles()){
            Tile right=actual.getTile(left.getX(),left.getY());assertEquals(left.getLayers(),right.getLayers());
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertEquals("At "+left.getX()+","+left.getY()+":"+x+","+y,left.getLayerValue(layer,x,y),right.getLayerValue(layer,x,y));
        }
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    @Test public void completeLinesMatchJavaAcrossTilesDirectionsAndMissingTiles(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            int[][] lines={{-190,-130,190,130},{190,130,-190,-130},{-64,-190,64,190},{0,0,0,0},{120,64,130,64},{64,64,70,65}};
            for(int radius:new int[]{0,3,17})for(boolean undo:new boolean[]{false,true})for(int[] line:lines){
                Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();
                draw(expected,Resources.INSTANCE,brush(radius),line,undo,false,true);
                long calls=draw(actual,Resources.INSTANCE,brush(radius),line,undo,true,true);
                assertTrue("Whole stroke JNI must execute",calls>0);assertTrue("At most one call per existing tile",calls<=15);
                same(expected,actual,Resources.INSTANCE);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void absentCustomLayersAndUndoRedoPreserveStorage(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            for(int radius:new int[]{0,17})for(boolean remove:new boolean[]{false,true}){
                Dimension before=NibbleLayerPaintParityTest.fixture(),expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();
                UndoManager undo=new UndoManager();for(Tile tile:actual.getTiles())tile.register(undo);undo.armSavePoint();
                int[] line={-190,-130,190,130};draw(expected,DEFAULT_THREE,brush(radius),line,remove,false,true);
                assertTrue(draw(actual,DEFAULT_THREE,brush(radius),line,remove,true,true)>0);same(expected,actual,DEFAULT_THREE);
                if(remove && radius==17){
                    // At this dynamic level all erase targets exceed the absent layer's default three.
                    same(before,actual,DEFAULT_THREE);assertFalse(undo.undo());
                }else{
                    assertTrue(undo.undo());same(before,actual,DEFAULT_THREE);assertTrue(undo.redo());same(expected,actual,DEFAULT_THREE);
                }
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void rotatedCachedBrushesMatchJava(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            for(boolean undo:new boolean[]{false,true}){
                Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();
                Brush b=RotatedBrush.rotate(brush(17),37);int[] line={-190,-130,190,130};
                draw(expected,Resources.INSTANCE,b,line,undo,false,true);assertTrue(draw(actual,Resources.INSTANCE,b,line,undo,true,true)>0);same(expected,actual,Resources.INSTANCE);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void bitmapBrushesPreserveIntensityAndShape() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            var image=new java.awt.image.BufferedImage(19,19,java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            for(int y=0;y<19;y++)for(int x=0;x<19;x++)image.getRaster().setSample(x,y,0,(x*19+y*11)&255);
            var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
            BitmapBrush brush=new BitmapBrush(new java.io.ByteArrayInputStream(bytes.toByteArray()),"Stroke bitmap");
            brush.setRadius(17);brush.setLevel(.63f);
            for(boolean remove:new boolean[]{false,true}){
                Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();int[] line={-190,-130,190,130};
                draw(expected,Resources.INSTANCE,brush,line,remove,false,true);assertTrue(draw(actual,Resources.INSTANCE,brush,line,remove,true,true)>0);same(expected,actual,Resources.INSTANCE);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void concurrentWorkersKeepStrokeBuffersIndependent() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            Dimension first=NibbleLayerPaintParityTest.fixture(),second=NibbleLayerPaintParityTest.fixture();
            int[] a={-190,-130,190,130},b={-64,-190,64,190};
            draw(first,Resources.INSTANCE,brush(17),a,false,false,true);draw(second,Resources.INSTANCE,brush(3),b,true,false,true);
            var left=pool.submit(()->{Dimension d=NibbleLayerPaintParityTest.fixture();assertTrue(draw(d,Resources.INSTANCE,brush(17),a,false,true,true)>0);return d;});
            var right=pool.submit(()->{Dimension d=NibbleLayerPaintParityTest.fixture();assertTrue(draw(d,Resources.INSTANCE,brush(3),b,true,true,true)>0);return d;});
            same(first,left.get(),Resources.INSTANCE);same(second,right.get(),Resources.INSTANCE);
        }finally{pool.shutdownNow();restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void intensitiesImmediatelyBelowRoundingTiesMatchJava(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            for(int radius:new int[]{0,3}){
                Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();
                SymmetricBrush brush=SymmetricBrush.CONSTANT_CIRCLE.clone();brush.setRadius(radius);
                brush.setLevel(Math.nextDown(.5f/14));
                for(boolean nativeMode:new boolean[]{false,true}){
                    System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));System.setProperty("welt.native.lineStroke","true");
                    NibbleLayerPaint paint=new NibbleLayerPaint(DEFAULT_ZERO);paint.setBrush(brush);
                    DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);Dimension d=nativeMode?actual:expected;
                    d.setEventsInhibited(true);try{painter.drawLine(d,64,64,70,65,1,false);}finally{d.setEventsInhibited(false);}
                }
                same(expected,actual,DEFAULT_ZERO);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void validatedNativeStrokesAreDefaultAndCanBeDisabled(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            System.setProperty(Native.GEN_KEY,"true");
            for(boolean enabled:new boolean[]{true,false}){
                if(enabled)System.clearProperty("welt.native.lineStroke");else System.setProperty("welt.native.lineStroke","false");
                assertEquals(enabled,LineStrokeAccess.isEnabled());
                Dimension d=NibbleLayerPaintParityTest.fixture();NibbleLayerPaint paint=new NibbleLayerPaint(Resources.INSTANCE);paint.setBrush(brush(3));
                DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);long before=LineStrokeAccess.completedCalls();
                d.setEventsInhibited(true);try{painter.drawLine(d,64,64,70,65,1,false);}finally{d.setEventsInhibited(false);}
                assertEquals(enabled?1:0,LineStrokeAccess.completedCalls()-before);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void filtersRetainTheirOriginalEvaluationOrder(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();
            java.util.List<String> reference=new java.util.ArrayList<>(),observed=new java.util.ArrayList<>();
            for(boolean nativeMode:new boolean[]{false,true}){
                System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));System.setProperty("welt.native.lineStroke","true");
                NibbleLayerPaint paint=new NibbleLayerPaint(Resources.INSTANCE);paint.setBrush(brush(3));
                java.util.List<String> log=nativeMode?observed:reference;
                paint.setFilter((x,y,strength)->{log.add(x+","+y);return strength*.5f;});
                Dimension d=nativeMode?actual:expected;DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);
                long before=LineStrokeAccess.completedCalls();d.setEventsInhibited(true);
                try{painter.drawLine(d,120,64,130,64,.73f,false);}finally{d.setEventsInhibited(false);}
                assertEquals(0,LineStrokeAccess.completedCalls()-before);
            }
            assertEquals(reference,observed);same(expected,actual,Resources.INSTANCE);
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
    @Test public void callbackBrushesAndImmediateEventsKeepExistingPath(){
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.lineStroke");
        try{
            for(boolean inhibited:new boolean[]{true,false}){
                Dimension expected=NibbleLayerPaintParityTest.fixture(),actual=NibbleLayerPaintParityTest.fixture();int[] line={120,64,130,64};
                Brush b=inhibited?new NibbleLayerPaintParityTest.Brush():brush(3);
                draw(expected,Resources.INSTANCE,b,line,false,false,inhibited);
                assertEquals(0,draw(actual,Resources.INSTANCE,b,line,false,true,inhibited));same(expected,actual,Resources.INSTANCE);
            }
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.lineStroke",flag);}
    }
}
