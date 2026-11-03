package org.pepsoft.worldpainter.operations;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.HeightTransform;
import org.pepsoft.worldpainter.MountainAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.BitmapBrush;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.RotatedBrush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Complete mountain ticks retain peak sampling, noise, layer draws and grouped editing semantics. */
public class LargeMountainStrokeParityTest {
    private final Map<String,String> properties = new HashMap<>();
    private Configuration configuration;

    @Before public void prepare() {
        configuration = Configuration.getInstance();
        if (Boolean.getBoolean("welt.test.requireLargeMountain")) assertTrue(NativeLoader.areSlicesAvailable());
        else org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        Configuration.setInstance(new Configuration());
        for (String key : new String[]{Native.GEN_KEY, Native.RENDER_KEY, "welt.native.largeHeightBrush"}) properties.put(key,System.getProperty(key));
        System.setProperty(Native.RENDER_KEY,"false");
        System.setProperty("welt.native.largeHeightBrush","true");
    }

    @After public void restore() {
        properties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
        Configuration.setInstance(configuration);
    }

    private static void stroke(BrushPipelineTestView view, RaiseMountain brush, Dimension dimension,
                               boolean nativeMode, boolean inverse) throws Exception {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        LargeMountainStrokeBenchmark.bind(view,dimension);
        dimension.armSavePoint();
        try { brush.tick(-3,5,inverse,true,.85f); }
        finally { LargeMountainStrokeBenchmark.bind(view,null); }
    }

    private static long affectedTiles(Dimension dimension,int radius) {
        return dimension.getTiles().stream().filter(tile->tile.getX()>=((-3-radius)>>7)
                &&tile.getX()<=((-3+radius)>>7)&&tile.getY()>=((5-radius)>>7)&&tile.getY()<=((5+radius)>>7)).count();
    }

    private static Map<String,Integer> events(Dimension dimension) {
        Map<String,Integer> result=new TreeMap<>();
        for(Tile tile:dimension.getTiles())tile.addListener((Tile.Listener)Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(proxy,method,args)->{
                    result.merge(tile.getX()+","+tile.getY()+":"+method.getName(),1,Integer::sum);return null;
                }));
        return result;
    }

    @Test public void bothDirectionsPreserveRichThemeDrawsPlanesUndoAndNotifications() throws Exception {
        for(boolean theme:new boolean[]{false,true})for(boolean inverse:new boolean[]{false,true}) {
            Dimension java=LargeMountainStrokeBenchmark.fixture(383),rust=LargeMountainStrokeBenchmark.fixture(383);
            java.unregisterUndoManager();rust.unregisterUndoManager();
            if(inverse){java.setHeightAt(-3,5,-5);rust.setHeightAt(-3,5,-5);}
            UndoManager a=new UndoManager(8),b=new UndoManager(8);java.registerUndoManager(a);rust.registerUndoManager(b);
            Map<String,Integer> expectedEvents=events(java),actualEvents=events(rust);
            BrushPipelineTestView view=new BrushPipelineTestView();RaiseMountain brush=LargeMountainStrokeBenchmark.brush(view,383,theme);
            long calls=HeightBrushAccess.completedThemedCalls(),compact=MountainAccess.completedCompactCalls();boolean drewRandom=false;
            for(int tick=0;tick<2;tick++) {
                LargeHeightStrokeParityTest.random().setSeed(197+tick);stroke(view,brush,java,false,inverse);
                long random=LargeHeightStrokeParityTest.random().nextLong();drewRandom|=random!=new Random(197+tick).nextLong();
                LargeHeightStrokeParityTest.random().setSeed(197+tick);stroke(view,brush,rust,true,inverse);
                assertEquals(random,LargeHeightStrokeParityTest.random().nextLong());LargeHeightStrokeParityTest.same(java,rust);
            }
            assertEquals(theme?2:0,HeightBrushAccess.completedThemedCalls()-calls);
            assertEquals(theme?0:2*affectedTiles(rust,383),MountainAccess.completedCompactCalls()-compact);
            assertEquals(theme,drewRandom);assertEquals(expectedEvents,actualEvents);
            for(int tick=0;tick<2;tick++){assertTrue(a.undo());assertTrue(b.undo());LargeHeightStrokeParityTest.same(java,rust);}
        }
    }

    @Test public void maximumBitmapFootprintAndShortHeightPlanesRemainExact() throws Exception {
        var image=new java.awt.image.BufferedImage(17,13,java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<13;y++)for(int x=0;x<17;x++)image.getRaster().setSample(x,y,0,32+Math.floorMod(x*13+y*17,200));
        var png=new java.io.ByteArrayOutputStream();assertTrue(javax.imageio.ImageIO.write(image,"png",png));
        for(boolean theme:new boolean[]{false,true}) {
            Dimension java=LargeMountainStrokeBenchmark.fixture(511,false),rust=LargeMountainStrokeBenchmark.fixture(511,false);
            for(Dimension d:new Dimension[]{java,rust}){
                d.unregisterUndoManager();
                d.setMaxHeight(192);for(Tile tile:d.getTiles())tile.setMinMaxHeight(-64,192,HeightTransform.IDENTITY);
                ((HeightMapTileFactory)d.getTileFactory()).getTheme().setMinMaxHeight(-64,192,HeightTransform.IDENTITY);
                d.registerUndoManager(new UndoManager(8));
            }
            BrushPipelineTestView view=new BrushPipelineTestView();RaiseMountain brush=LargeMountainStrokeBenchmark.brush(view,511,theme);
            brush.setBrush(new BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()),"Large mountain bitmap"));
            long before=LargeMountainStrokeBenchmark.digest(java),calls=HeightBrushAccess.completedThemedCalls(),compact=MountainAccess.completedCompactCalls();
            LargeHeightStrokeParityTest.random().setSeed(197);stroke(view,brush,java,false,false);long random=LargeHeightStrokeParityTest.random().nextLong();
            LargeHeightStrokeParityTest.random().setSeed(197);stroke(view,brush,rust,true,false);
            assertEquals(theme?1:0,HeightBrushAccess.completedThemedCalls()-calls);
            assertEquals(theme?0:affectedTiles(rust,511),MountainAccess.completedCompactCalls()-compact);
            assertNotEquals(before,LargeMountainStrokeBenchmark.digest(java));
            assertEquals(random,LargeHeightStrokeParityTest.random().nextLong());LargeHeightStrokeParityTest.same(java,rust);
        }
    }

    @Test public void rotatedStockBrushKeepsItsCachedPeakAndEffectiveFootprint() throws Exception {
        BrushPipelineTestView view=new BrushPipelineTestView();RaiseMountain brush=LargeMountainStrokeBenchmark.brush(view,255,true);
        brush.setBrush(RotatedBrush.rotate(SymmetricBrush.LINEAR_SQUARE.clone(),33));
        int radius=brush.getEffectiveRadius();assertTrue(radius>255&&radius<=511);
        Dimension java=LargeMountainStrokeBenchmark.fixture(radius),rust=LargeMountainStrokeBenchmark.fixture(radius);
        long calls=HeightBrushAccess.completedThemedCalls();LargeHeightStrokeParityTest.random().setSeed(197);
        stroke(view,brush,java,false,true);long random=LargeHeightStrokeParityTest.random().nextLong();
        LargeHeightStrokeParityTest.random().setSeed(197);stroke(view,brush,rust,true,true);
        assertEquals(1,HeightBrushAccess.completedThemedCalls()-calls);assertEquals(random,LargeHeightStrokeParityTest.random().nextLong());
        LargeHeightStrokeParityTest.same(java,rust);
    }

    @Test public void unsupportedLargeStrokesRefuseWithoutEditsOrCustomCallbacks() throws Exception {
        Dimension dimension=LargeMountainStrokeBenchmark.fixture(383);BrushPipelineTestView view=new BrushPipelineTestView();
        RaiseMountain brush=LargeMountainStrokeBenchmark.brush(view,383,true);
        Method method=RaiseMountain.class.getDeclaredMethod("applyNativeMountain",Dimension.class,int.class,int.class,int.class,
                boolean.class,int.class,int.class,float.class,boolean.class);method.setAccessible(true);
        System.setProperty(Native.GEN_KEY,"true");dimension.setEventsInhibited(true);
        try {
            long hash=LargeMountainStrokeBenchmark.digest(dimension);
            System.setProperty("welt.native.largeHeightBrush","false");
            assertFalse((boolean)method.invoke(brush,dimension,-3,5,383,false,-64,383,250f,true));
            assertEquals(hash,LargeMountainStrokeBenchmark.digest(dimension));System.setProperty("welt.native.largeHeightBrush","true");
            dimension.removeTile(new java.awt.Point(-1,-1));dimension.addTile(new Tile(-1,-1,-64,320){});
            hash=LargeMountainStrokeBenchmark.digest(dimension);
            for(boolean theme:new boolean[]{false,true}){
                LargeHeightStrokeParityTest.random().setSeed(197);
                assertFalse((boolean)method.invoke(brush,dimension,-3,5,383,false,-64,383,250f,theme));
                long random=LargeHeightStrokeParityTest.random().nextLong();LargeHeightStrokeParityTest.random().setSeed(197);
                assertEquals(LargeHeightStrokeParityTest.random().nextLong(),random);assertEquals(hash,LargeMountainStrokeBenchmark.digest(dimension));
            }
            int[] callbacks={0};Brush delegate=SymmetricBrush.LINEAR_CIRCLE.clone();
            brush.setBrush((Brush)Proxy.newProxyInstance(Brush.class.getClassLoader(),new Class<?>[]{Brush.class},
                    (proxy,call,args)->{if(call.getName().equals("getFullStrength"))callbacks[0]++;return call.invoke(delegate,args);}));
            callbacks[0]=0;assertFalse((boolean)method.invoke(brush,dimension,-3,5,383,false,-64,383,250f,true));assertEquals(0,callbacks[0]);
        } finally {dimension.setEventsInhibited(false);}
    }
}
