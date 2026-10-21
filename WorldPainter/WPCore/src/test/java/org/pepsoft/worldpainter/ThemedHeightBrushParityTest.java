package org.pepsoft.worldpainter;

import java.util.*;
import java.nio.*;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import static org.junit.Assert.*;

public class ThemedHeightBrushParityTest {
    private static Map<String,Integer> events(Dimension d) {
        Map<String,Integer> events=new TreeMap<>();
        for(Tile tile:d.getTiles())tile.addListener((Tile.Listener)java.lang.reflect.Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(p,m,a)->{
                    events.merge(tile.getX()+","+tile.getY()+":"+m.getName(),1,Integer::sum);return null;}));
        return events;
    }
    private static void same(Dimension a,Dimension b){assertEquals(a.getTileCount(),b.getTileCount());
        for(Tile t:a.getTiles())ThemeResetParityTest.same(t,b.getTile(t.getX(),t.getY()));}
    @Test public void crossTileStrokesPreserveGlobalRandomOrderPlanesAndEvents() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            Map<String,Integer> ea=events(a),eb=events(b);float[] forces=new float[255*255];
            for(int stroke=0;stroke<4;stroke++) {
                HeightBrushBenchmark.strengths(forces,127,stroke);a.setEventsInhibited(true);b.setEventsInhibited(true);
                ThemeResetParityTest.random().setSeed(77+stroke);
                ThemedHeightBrushBenchmark.scalar(a,-63,-63,255,forces,stroke%2!=0,7.5f);
                long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(77+stroke);
                assertTrue(HeightBrushAccess.tryApplyThemed(b,-63,-63,255,255,forces,stroke%2,7.5f,-64,319));
                assertEquals(next,ThemeResetParityTest.random().nextLong());
                a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);assertEquals(ea,eb);
            }
        }finally{restore(old);}
    }
    @Test public void zeroAndRejectedStrokesDoNotEditOrConsumeRandom() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            Map<String,Integer> e=events(b);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(9);
            long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(9);
            assertTrue(HeightBrushAccess.tryApplyThemed(b,-63,-63,255,255,new float[255*255],0,8f,-64,319));
            assertFalse(HeightBrushAccess.tryApplyThemed(b,0,0,257,1,new float[257],0,8f,-64,319));
            assertFalse(HeightBrushAccess.tryApplyThemed(b,-1,-1,3,3,new float[9],0,8f,-64,319));
            ByteBuffer invalid=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
            invalid.putInt(0,0x42544857).putInt(4,1).putInt(8,128).putInt(12,64);
            assertFalse(NativeSlices.applyThemedHeightBrush(invalid));
            assertEquals(next,ThemeResetParityTest.random().nextLong());b.setEventsInhibited(false);same(a,b);assertTrue(e.isEmpty());
            System.setProperty(Native.GEN_KEY,"false");b.setEventsInhibited(true);
            assertFalse(HeightBrushAccess.tryApplyThemed(b,0,0,1,1,new float[]{1},0,8f,-64,319));b.setEventsInhibited(false);
        }finally{restore(old);}
    }
    @Test public void shortHeightStorageSpecialForcesAndUndoMatchScalar() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            for(Dimension d:new Dimension[]{a,b})shorten(d);
            UndoManager undo=new UndoManager();b.registerUndoManager(undo);undo.armSavePoint();
            Dimension before=ThemedHeightBrushBenchmark.fixture();shorten(before);
            float[] f={0f,-1f,Float.NaN,.25f,1f,2f,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,.5f};
            for(int mode=0;mode<2;mode++)for(float v:new float[]{0f,3.123f,-1f,Float.NaN,Float.POSITIVE_INFINITY}) {
                a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(19);
                ThemedHeightBrushBenchmark.scalar(a,-1,-1,3,f,mode!=0,v);long next=ThemeResetParityTest.random().nextLong();
                ThemeResetParityTest.random().setSeed(19);assertTrue(ThemedHeightBrushAccess.apply(b,-1,-1,3,3,f,mode,v,-64,191));
                assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);
            }
            assertTrue(undo.undo());same(before,b);assertTrue(undo.redo());same(a,b);
        }finally{restore(old);}
    }
    private static void shorten(Dimension d) {
        d.setMaxHeight(192);
        for(Tile t:d.getTiles())t.setMinMaxHeight(-64,192,HeightTransform.IDENTITY);
        ((HeightMapTileFactory)d.getTileFactory()).getTheme().setMinMaxHeight(-64,192,HeightTransform.IDENTITY);
    }
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
}
