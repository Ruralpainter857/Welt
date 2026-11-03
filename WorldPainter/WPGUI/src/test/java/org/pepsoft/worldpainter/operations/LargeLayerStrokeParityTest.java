package org.pepsoft.worldpainter.operations;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.painting.*;
import static org.junit.Assert.*;

/** Large paint strokes preserve the complete model, history and coalesced events. */
public class LargeLayerStrokeParityTest {
    private final Map<String,String> properties = new HashMap<>();
    private Configuration configuration;
    @Before public void prepare() {
        configuration=Configuration.getInstance();
        if(Boolean.getBoolean("welt.test.requireLargeLayer")) assertTrue(NativeLoader.areSlicesAvailable());
        else org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        Configuration.setInstance(new Configuration());
        for(String key:new String[]{Native.GEN_KEY,Native.RENDER_KEY,"welt.native.largeLayerBrush"}) properties.put(key,System.getProperty(key));
        System.setProperty(Native.RENDER_KEY,"false");System.setProperty("welt.native.largeLayerBrush","true");
    }
    @After public void restore() {
        properties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
        Configuration.setInstance(configuration);
    }

    private static Map<String,Integer> events(Dimension dimension) {
        Map<String,Integer> result=new TreeMap<>();
        for(Tile tile:dimension.getTiles())tile.addListener((Tile.Listener)Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                new Class<?>[]{Tile.Listener.class},(proxy,method,args)->{result.merge(tile.getX()+","+tile.getY()+":"+method.getName(),1,Integer::sum);return null;}));
        return result;
    }
    private static void stroke(Paint paint,Dimension dimension,boolean nativeMode,boolean remove,int tick) throws Exception {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));dimension.armSavePoint();dimension.setEventsInhibited(true);
        LargeHeightStrokeParityTest.random().setSeed(197+tick);
        try{if(remove)paint.remove(dimension,-3+tick*11,5-tick*7,.93f);else paint.apply(dimension,-3+tick*11,5-tick*7,.93f);}
        finally{dimension.setEventsInhibited(false);}
    }
    private static void compare(CombinedLayer layer,boolean combined,Brush brush,boolean remove,boolean nativeExpected) throws Exception {
        int radius=brush.getEffectiveRadius();
        Dimension java=LargeLayerStrokeBenchmark.fixture(radius,layer),rust=LargeLayerStrokeBenchmark.fixture(radius,layer);
        var a=new org.pepsoft.util.undo.UndoManager(8);var b=new org.pepsoft.util.undo.UndoManager(8);
        java.unregisterUndoManager();rust.unregisterUndoManager();java.registerUndoManager(a);rust.registerUndoManager(b);
        Paint paint=combined?new CombinedLayerPaint(layer):new NibbleLayerPaint(layer);paint.setBrush(brush);paint.setDither(false);
        Map<String,Integer> left=events(java),right=events(rust);
        for(int tick=0;tick<3;tick++) {
            long calls=NibbleBrushAccess.completedNativeCalls();stroke(paint,java,false,remove,tick);
            long random=LargeHeightStrokeParityTest.random().nextLong();
            assertEquals(calls,NibbleBrushAccess.completedNativeCalls());stroke(paint,rust,true,remove,tick);
            assertEquals(random,LargeHeightStrokeParityTest.random().nextLong());
            assertEquals(nativeExpected?LargeLayerStrokeBenchmark.affectedTiles(rust,brush,-3+tick*11,5-tick*7,remove):0,NibbleBrushAccess.completedNativeCalls()-calls);
            LargeHeightStrokeParityTest.same(java,rust);
        }
        assertEquals(left,right);
        for(int i=0;i<3;i++){assertTrue(a.undo());assertTrue(b.undo());LargeHeightStrokeParityTest.same(java,rust);}
        for(int i=0;i<3;i++){assertTrue(a.redo());assertTrue(b.redo());LargeHeightStrokeParityTest.same(java,rust);}
    }
    private static Brush brush(int radius){Brush b=SymmetricBrush.LINEAR_CIRCLE.clone();b.setRadius(radius);b.setLevel(.85f);return b;}

    @Test public void combinedPaintingKeepsTerrainBiomeIntensityAndHistoryExact() throws Exception {
        compare(LargeLayerStrokeBenchmark.layer(),true,brush(383),false,true);
    }
    @Test public void biomeRemovalKeepsGlobalTruncationAndSparseDefaultsExact() throws Exception {
        CombinedLayer layer=LargeLayerStrokeBenchmark.layer();layer.setTerrain(null);
        compare(layer,true,brush(383),true,true);
    }
    @Test public void standaloneIntensityAndExportDeferredLayerKeepExistingSemantics() throws Exception {
        CombinedLayer layer=LargeLayerStrokeBenchmark.layer();
        compare(layer,false,brush(383),false,true);compare(layer,false,brush(383),true,true);
        layer.setApplyTerrainAndBiomeOnExport(true);compare(layer,true,brush(383),false,true);
    }
    @Test public void maximumBitmapAndRotatedSquareKeepEveryPlaneExact() throws Exception {
        java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(11,7,java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<7;y++)for(int x=0;x<11;x++)image.getRaster().setSample(x,y,0,(x*31+y*17)&255);
        var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",png);
        Brush bitmap=new BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()),"Layer bitmap");bitmap.setRadius(511);bitmap.setLevel(.85f);
        compare(LargeLayerStrokeBenchmark.layer(),true,bitmap,false,true);
        Brush square=SymmetricBrush.LINEAR_SQUARE.clone();square.setRadius(255);square.setLevel(.85f);
        compare(LargeLayerStrokeBenchmark.layer(),true,RotatedBrush.rotate(square,31),false,true);
    }
    @Test public void terrainRemovalAndDisabledOptionRetainWholeJavaPath() throws Exception {
        compare(LargeLayerStrokeBenchmark.layer(),true,brush(383),true,false);
        System.setProperty("welt.native.largeLayerBrush","false");compare(LargeLayerStrokeBenchmark.layer(),true,brush(383),false,false);
    }
    @Test public void unsupportedMetadataAndTilesRefuseBeforeSamplingOrEditing() throws Exception {
        CombinedLayer layer=LargeLayerStrokeBenchmark.layer();Dimension dimension=LargeLayerStrokeBenchmark.fixture(383,layer);
        System.setProperty(Native.GEN_KEY,"true");dimension.setEventsInhibited(true);
        int[] samples={0};NibbleBrushAccess.StrengthSampler sampler=(x,y,full)->{samples[0]++;return 1;};
        try {
            long hash=LargeMountainStrokeBenchmark.digest(dimension);
            assertFalse(NibbleBrushAccess.tryApplySampled(dimension,layer,Terrain.GRASS,200,Integer.MAX_VALUE,0,767,767,1,sampler,0));
            assertFalse(NibbleBrushAccess.tryApplySampled(dimension,layer,Terrain.GRASS,200,-386,-378,767,767,Float.NaN,sampler,0));
            assertEquals(hash,LargeMountainStrokeBenchmark.digest(dimension));
            dimension.removeTile(new java.awt.Point(-1,-1));dimension.addTile(new Tile(-1,-1,-64,320){});
            hash=LargeMountainStrokeBenchmark.digest(dimension);
            assertFalse(NibbleBrushAccess.tryApplySampled(dimension,layer,Terrain.GRASS,200,-386,-378,767,767,1,sampler,0));
            assertEquals(0,samples[0]);assertEquals(hash,LargeMountainStrokeBenchmark.digest(dimension));
        } finally{dimension.setEventsInhibited(false);}
    }

    @Test public void customBrushesAndFiltersRefuseBeforeTheirStrengthCallbacks() throws Exception {
        CombinedLayer layer=LargeLayerStrokeBenchmark.layer();Dimension dimension=LargeLayerStrokeBenchmark.fixture(383,layer);
        CombinedLayerPaint paint=new CombinedLayerPaint(layer);paint.setDither(false);paint.setBrush(brush(383));
        var method=CombinedLayerPaint.class.getDeclaredMethod("applyNativeCombinedBrush",Dimension.class,int.class,int.class,float.class,boolean.class);
        method.setAccessible(true);System.setProperty(Native.GEN_KEY,"true");dimension.setEventsInhibited(true);
        try {
            long hash=LargeMountainStrokeBenchmark.digest(dimension),calls=NibbleBrushAccess.completedNativeCalls();int[] callbacks={0};
            paint.setFilter((x,y,strength)->{callbacks[0]++;return strength;});
            assertFalse((boolean)method.invoke(paint,dimension,-3,5,.93f,false));paint.setFilter(null);
            Brush delegate=brush(383);
            paint.setBrush((Brush)Proxy.newProxyInstance(Brush.class.getClassLoader(),new Class<?>[]{Brush.class},(proxy,call,args)->{
                if(call.getName().equals("getStrength")||call.getName().equals("getFullStrength"))callbacks[0]++;
                return call.invoke(delegate,args);
            }));
            assertFalse((boolean)method.invoke(paint,dimension,-3,5,.93f,false));
            assertEquals(0,callbacks[0]);assertEquals(calls,NibbleBrushAccess.completedNativeCalls());
            assertEquals(hash,LargeMountainStrokeBenchmark.digest(dimension));
        }finally{dimension.setEventsInhibited(false);}
    }
}
