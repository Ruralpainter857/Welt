package org.pepsoft.worldpainter.operations;

import java.lang.reflect.Method;
import java.util.*;
import org.junit.*;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Real GUI computation over many tiles, with exact theme draws and the existing undo mechanism. */
public class LargeHeightStrokeParityTest {
    private final Map<String,String> previous = new HashMap<>();
    @Before public void prepare() {
        if (Boolean.getBoolean("welt.test.requireLargeHeight")) assertTrue(NativeLoader.areSlicesAvailable()); else Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        for (String key : new String[]{Native.GEN_KEY,"welt.native.largeHeightBrush"}) previous.put(key,System.getProperty(key));
        System.setProperty("welt.native.largeHeightBrush","true");
    }
    @After public void restore() { previous.forEach((key,value) -> {if(value==null)System.clearProperty(key);else System.setProperty(key,value);}); }
    static Random random() throws Exception {
        Method method = Class.forName("org.pepsoft.worldpainter.ThemeResetParityTest").getDeclaredMethod("random"); method.setAccessible(true); return (Random)method.invoke(null);
    }
    static Height brush(int radius) { Height brush=new Height(new BrushPipelineTestView());brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(radius);brush.setLevel(.63f);return brush; }
    static boolean apply(Height brush, Dimension dimension, int radius, boolean inverse, boolean nativeMode) throws Exception {
        Method method=Height.class.getDeclaredMethod(nativeMode?"applyNativeHeightBrush":"applyJavaHeightBrush",Dimension.class,int.class,int.class,int.class,boolean.class,float.class,float.class,float.class,boolean.class);
        method.setAccessible(true);System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        dimension.armSavePoint();dimension.setEventsInhibited(true);
        try {Object result=method.invoke(brush,dimension,-3,5,radius,inverse,8f,-64f,319f,true);return !nativeMode || Boolean.TRUE.equals(result);}
        finally {dimension.setEventsInhibited(false);}
    }
    @Test public void largeThemedEditsKeepAllPlanesRandomDrawsAndUndoExact() throws Exception {
        Dimension java=LargeHeightStrokeBenchmark.fixture(255),rust=LargeHeightStrokeBenchmark.fixture(255);
        java.unregisterUndoManager();rust.unregisterUndoManager();UndoManager a=new UndoManager(8),b=new UndoManager(8);java.registerUndoManager(a);rust.registerUndoManager(b);
        Height brush=brush(255);long calls=HeightBrushAccess.completedThemedCalls();
        Map<String,Integer> javaEvents=events(java),rustEvents=events(rust);
        for(int stroke=0;stroke<4;stroke++) {
            random().setSeed(99+stroke);assertTrue(apply(brush,java,255,(stroke&1)!=0,false));long expected=random().nextLong();
            random().setSeed(99+stroke);assertTrue("Large native transaction required",apply(brush,rust,255,(stroke&1)!=0,true));assertEquals("Theme random sequence",expected,random().nextLong());same(java,rust);
        }
        assertEquals(4,HeightBrushAccess.completedThemedCalls()-calls);
        assertEquals("Coalesced edit notifications",javaEvents,rustEvents);
        for(int stroke=0;stroke<4;stroke++){assertTrue(a.undo());assertTrue(b.undo());same(java,rust);}
    }
    private static Map<String,Integer> events(Dimension dimension) {
        Map<String,Integer> events=new TreeMap<>();
        for(Tile tile:dimension.getTiles())tile.addListener((Tile.Listener)java.lang.reflect.Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},
                (proxy,method,args)->{events.merge(tile.getX()+","+tile.getY()+":"+method.getName(),1,Integer::sum);return null;}));
        return events;
    }
    @Test public void disabledLargeFramesAndCustomTilesRefuseBeforeAnyEditsOrRandomDraws() throws Exception {
        Dimension dimension=LargeHeightStrokeBenchmark.fixture(255);Height brush=brush(255);
        long calls=HeightBrushAccess.completedThemedCalls();System.setProperty("welt.native.largeHeightBrush","false");
        random().setSeed(197);assertFalse(apply(brush,dimension,255,false,true));long actual=random().nextLong();random().setSeed(197);assertEquals(random().nextLong(),actual);
        System.setProperty("welt.native.largeHeightBrush","true");dimension.removeTile(new java.awt.Point(-1,-1));dimension.addTile(new Tile(-1,-1,-64,320){});
        random().setSeed(197);assertFalse(apply(brush,dimension,255,false,true));actual=random().nextLong();random().setSeed(197);assertEquals(random().nextLong(),actual);
        int[] callbacks={0};var delegate=SymmetricBrush.LINEAR_CIRCLE.clone();
        var custom=(org.pepsoft.worldpainter.brushes.Brush)java.lang.reflect.Proxy.newProxyInstance(
                org.pepsoft.worldpainter.brushes.Brush.class.getClassLoader(),new Class<?>[]{org.pepsoft.worldpainter.brushes.Brush.class},
                (proxy,method,args)->{if(method.getName().equals("getFullStrength"))callbacks[0]++;return method.invoke(delegate,args);});
        brush.setBrush(custom);assertFalse(apply(brush,dimension,255,false,true));assertEquals("Custom strength callbacks must stay on the Java path",0,callbacks[0]);
        assertEquals(calls,HeightBrushAccess.completedThemedCalls());
    }
    @Test public void largestSupportedFootprintKeepsOneTransactionAndExactPlanes() throws Exception {
        Dimension java=LargeHeightStrokeBenchmark.fixture(511),rust=LargeHeightStrokeBenchmark.fixture(511);Height brush=brush(511);
        random().setSeed(197);assertTrue(apply(brush,java,511,false,false));long expected=random().nextLong();
        long calls=HeightBrushAccess.completedThemedCalls();random().setSeed(197);assertTrue(apply(brush,rust,511,false,true));
        assertEquals(1,HeightBrushAccess.completedThemedCalls()-calls);assertEquals(expected,random().nextLong());same(java,rust);
    }
    @Test public void bitmapStrengthsKeepTheExactThemedResult() throws Exception {
        var image=new java.awt.image.BufferedImage(17,13,java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<13;y++)for(int x=0;x<17;x++)image.getRaster().setSample(x,y,0,32+Math.floorMod(x*13+y*17,224));
        var png=new java.io.ByteArrayOutputStream();assertTrue(javax.imageio.ImageIO.write(image,"png",png));
        System.setProperty(Native.GEN_KEY,"false");Height brush=brush(255);
        brush.setBrush(new org.pepsoft.worldpainter.brushes.BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()),"Large bitmap fixture"));
        Dimension java=LargeHeightStrokeBenchmark.fixture(255),rust=LargeHeightStrokeBenchmark.fixture(255);int before=rust.getRawHeightAt(-3,5);
        random().setSeed(197);assertTrue(apply(brush,java,255,false,false));long expected=random().nextLong();
        long calls=HeightBrushAccess.completedThemedCalls();random().setSeed(197);assertTrue(apply(brush,rust,255,false,true));
        assertEquals(1,HeightBrushAccess.completedThemedCalls()-calls);assertEquals(expected,random().nextLong());assertTrue(rust.getRawHeightAt(-3,5)>before);same(java,rust);
    }
    static void same(Dimension expected,Dimension actual) {
        assertEquals(expected.getTileCoords(),actual.getTileCoords());
        for(Tile a:expected.getTiles()) {
            Tile b=actual.getTile(a.getX(),a.getY());assertEquals(a.getLayers(),b.getLayers());
            for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                assertEquals(a.getRawHeight(x,y),b.getRawHeight(x,y));assertEquals(a.getWaterLevel(x,y),b.getWaterLevel(x,y));assertSame(a.getTerrain(x,y),b.getTerrain(x,y));
                for(Layer layer:a.getLayers()) {
                    if(layer.getDataSize()==Layer.DataSize.BIT || layer.getDataSize()==Layer.DataSize.BIT_PER_CHUNK)assertEquals(a.getBitLayerValue(layer,x,y),b.getBitLayerValue(layer,x,y));
                    else assertEquals(a.getLayerValue(layer,x,y),b.getLayerValue(layer,x,y));
                }
            }
        }
    }
}
