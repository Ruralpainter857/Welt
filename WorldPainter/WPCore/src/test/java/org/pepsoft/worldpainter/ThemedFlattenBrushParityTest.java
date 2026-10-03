package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.util.*;
import static org.junit.Assert.*;

/** Whole-plane and RNG parity for all three themed flatten modes. */
public class ThemedFlattenBrushParityTest {
    private static Map<String,Integer> events(Dimension d) {
        Map<String,Integer> result=new TreeMap<>();
        for(Tile tile:d.getTiles())tile.addListener((Tile.Listener)java.lang.reflect.Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(p,m,a)->{result.merge(tile.getX()+","+tile.getY()+":"+m.getName(),1,Integer::sum);return null;}));
        return result;
    }
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    @Test public void crossTileFlattenRaiseAndLowerKeepPlanesEventsAndGlobalRandomOrder() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            Map<String,Integer> ea=events(a),eb=events(b);float[] forces=new float[255*255];
            for(int stroke=0;stroke<6;stroke++) {
                HeightBrushBenchmark.strengths(forces,127,stroke);int mode=2+stroke%3;float target=stroke%2==0?74.5f:115.125f;
                a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(91+stroke);
                ThemedFlattenBrushBenchmark.scalar(a,-63,-63,255,forces,mode,target);long next=ThemeResetParityTest.random().nextLong();
                ThemeResetParityTest.random().setSeed(91+stroke);assertTrue(HeightBrushAccess.tryApplyThemed(b,-63,-63,255,255,forces,mode,target,-64,319));
                assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);
                ThemedFlattenBrushBenchmark.same(a,b);assertEquals(ea,eb);
            }
        }finally{restore(old);}
    }
    @Test public void shortAndTallStorageSpecialValuesAndUndoRemainExact() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            for(boolean shortStorage:new boolean[]{false,true}){
                Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture(),before=ThemedHeightBrushBenchmark.fixture();
                if(shortStorage)for(Dimension d:new Dimension[]{a,b,before}){d.setMaxHeight(192);for(Tile t:d.getTiles())t.setMinMaxHeight(-64,192,HeightTransform.IDENTITY);((HeightMapTileFactory)d.getTileFactory()).getTheme().setMinMaxHeight(-64,192,HeightTransform.IDENTITY);}
                UndoManager undo=new UndoManager();b.registerUndoManager(undo);undo.armSavePoint();
                float[] forces={0,-1,Float.NaN,.25f,1,2,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,.5f};
                for(int mode=2;mode<=4;mode++)for(float target:new float[]{74.5f,115.125f,0f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
                    a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(121);
                    ThemedFlattenBrushBenchmark.scalar(a,-1,-1,3,forces,mode,target);long next=ThemeResetParityTest.random().nextLong();
                    ThemeResetParityTest.random().setSeed(121);assertTrue(ThemedHeightBrushAccess.apply(b,-1,-1,3,3,forces,mode,target,-64,b.getMaxHeight()-1));
                    assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
                }
                assertTrue(undo.undo());ThemedFlattenBrushBenchmark.same(before,b);assertTrue(undo.redo());ThemedFlattenBrushBenchmark.same(a,b);
            }
        }finally{restore(old);}
    }
    @Test public void unchangedFlattenStillAppliesThemeAndRejectedModesStayAtomic() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            int side=129;float[] forces=new float[side*side];forces[0]=1f;float target=a.getHeightAt(0,0);
            a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(141);
            ThemedFlattenBrushBenchmark.scalar(a,0,0,side,forces,2,target);long next=ThemeResetParityTest.random().nextLong();
            ThemeResetParityTest.random().setSeed(141);assertTrue(HeightBrushAccess.tryApplyThemed(b,0,0,side,side,forces,2,target,-64,319));assertEquals(next,ThemeResetParityTest.random().nextLong());
            a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
            b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(161);long unchanged=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(161);
            assertFalse(ThemedHeightBrushAccess.apply(b,0,0,side,side,forces,5,target,-64,319));assertFalse(HeightBrushAccess.tryApplyThemed(b,0,0,3,3,new float[9],2,target,-64,319));
            assertEquals(unchanged,ThemeResetParityTest.random().nextLong());b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
        }finally{restore(old);}
    }
}
