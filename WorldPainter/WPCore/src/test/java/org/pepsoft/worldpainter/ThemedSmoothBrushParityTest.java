package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.util.*;
import static org.junit.Assert.*;

/** Compare the original streaming scatter sums with immutable grouped smoothing and theme edits. */
public class ThemedSmoothBrushParityTest {
    private static Map<String,Integer> events(Dimension d){
        Map<String,Integer> result=new TreeMap<>();
        for(Tile tile:d.getTiles())tile.addListener((Tile.Listener)java.lang.reflect.Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),new Class<?>[]{Tile.Listener.class},(p,m,a)->{result.merge(tile.getX()+","+tile.getY()+":"+m.getName(),1,Integer::sum);return null;}));
        return result;
    }
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    @Test public void crossTileStrokesKeepAllPlanesEventsAndRandomDraws() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            Map<String,Integer> ea=events(a),eb=events(b);float[] forces=new float[245*245];
            var scratch=new ThemedSmoothBrushBenchmark.Scratch();
            for(int stroke=0;stroke<4;stroke++){
                HeightBrushBenchmark.strengths(forces,122,stroke);
                a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(91+stroke);
                ThemedSmoothBrushBenchmark.scalar(a,-61,-61,245,forces,scratch);long next=ThemeResetParityTest.random().nextLong();
                ThemeResetParityTest.random().setSeed(91+stroke);long calls=HeightBrushAccess.completedThemedCalls();
                assertTrue(HeightBrushAccess.tryApplyThemed(b,-61,-61,245,245,forces,HeightBrushAccess.SMOOTH,0,-64,319));
                assertEquals(calls+1,HeightBrushAccess.completedThemedCalls());
                assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);
                ThemedFlattenBrushBenchmark.same(a,b);assertEquals(ea,eb);
            }
        }finally{restore(old);}
    }
    @Test public void holesShortStorageSpecialForcesAndUndoRemainExact() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            for(boolean shortStorage:new boolean[]{false,true}){
                Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture(),before=ThemedHeightBrushBenchmark.fixture();
                for(Dimension d:new Dimension[]{a,b,before}){
                    d.removeTile(0,0);
                    if(shortStorage){d.setMaxHeight(192);for(Tile t:d.getTiles())t.setMinMaxHeight(-64,192,HeightTransform.IDENTITY);((HeightMapTileFactory)d.getTileFactory()).getTheme().setMinMaxHeight(-64,192,HeightTransform.IDENTITY);}
                }
                UndoManager undo=new UndoManager();b.registerUndoManager(undo);undo.armSavePoint();
                float[] forces={0,-1,Float.NaN,.25f,1,2,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,.5f};
                a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(121);
                ThemedSmoothBrushBenchmark.scalar(a,-1,-1,3,forces,new ThemedSmoothBrushBenchmark.Scratch());long next=ThemeResetParityTest.random().nextLong();
                ThemeResetParityTest.random().setSeed(121);assertTrue(ThemedHeightBrushAccess.apply(b,-1,-1,3,3,forces,5,0,-64,b.getMaxHeight()-1));
                assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);
                ThemedFlattenBrushBenchmark.same(a,b);assertTrue(undo.undo());ThemedFlattenBrushBenchmark.same(before,b);assertTrue(undo.redo());ThemedFlattenBrushBenchmark.same(a,b);
            }
        }finally{restore(old);}
    }
    @Test public void rectangularBordersMatchTheSameSquareStrokeWithAnInactiveLastRow() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            int width=129,height=128;float[] square=new float[width*width],rectangle=new float[width*height];
            HeightBrushBenchmark.strengths(square,64,0);
            for(int x=0;x<width;x++){square[x*width+128]=0;System.arraycopy(square,x*width,rectangle,x*height,height);}
            a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(151);
            ThemedSmoothBrushBenchmark.scalar(a,-63,-63,width,square,new ThemedSmoothBrushBenchmark.Scratch());long next=ThemeResetParityTest.random().nextLong();
            ThemeResetParityTest.random().setSeed(151);assertTrue(HeightBrushAccess.tryApplyThemed(b,-63,-63,width,height,rectangle,5,0,-64,319));
            assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
        }finally{restore(old);}
    }
    @Test public void unsupportedHaloAndDimensionsRejectWithoutRandomOrTileChanges() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");Dimension a=ThemedHeightBrushBenchmark.fixture(),b=ThemedHeightBrushBenchmark.fixture();
            b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(161);long unchanged=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(161);
            assertFalse(ThemedHeightBrushAccess.apply(b,0,0,247,1,new float[247],5,0,-64,319));
            assertFalse(ThemedHeightBrushAccess.apply(b,Integer.MIN_VALUE,0,1,1,new float[]{1},5,0,-64,319));
            assertFalse(ThemedHeightBrushAccess.apply(b,Integer.MAX_VALUE-4,0,1,1,new float[]{1},5,0,-64,319));
            assertEquals(unchanged,ThemeResetParityTest.random().nextLong());b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
        }finally{restore(old);}
    }
}
