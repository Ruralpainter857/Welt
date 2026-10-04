package org.pepsoft.worldpainter;

import java.util.Arrays;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.operations.Filter;
import org.pepsoft.worldpainter.panels.EditorFilterPlan;
import static org.junit.Assert.*;

/** Full live-filter, height and theme parity, including stream state and grouped undo. */
public class FilteredThemedHeightBrushParityTest {
    private static void scalar(Dimension d,int side,float[] forces,Filter filter,int mode,float value,float dynamic) {
        for(int x=0;x<side;x++)for(int y=0;y<side;y++){
            int wx=x-63,wy=y-63;float current=d.getHeightAt(wx,wy);
            float target=mode==0?Math.min(current+value,d.getMaxHeight()-1)
                    :mode==1?Math.max(current-value,d.getMinHeight()):value;
            float force=dynamic*filter.modifyStrength(wx,wy,forces[x*side+y]);
            if(force>0){float edited=force*target+(1-force)*current;
                if(mode==2||(mode==0||mode==3?edited>current:edited<current)){
                    d.setHeightAt(wx,wy,edited);d.applyTheme(wx,wy);
                }
            }
        }
    }
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    @Test public void allFiveModesKeepEvolvingSlopesBiomesLayersSelectionAndThemeDraws() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");int side=129;float[] forces=new float[side*side];Arrays.fill(forces,.7f);
            for(int mode=0;mode<5;mode++)for(int f=0;f<6;f++){
                Dimension a=FilteredHeightBrushBenchmark.fixture(),b=FilteredHeightBrushBenchmark.fixture();
                Filter java=FilteredHeightBrushParityTest.filter(a,f),rust=FilteredHeightBrushParityTest.filter(b,f);
                a.setEventsInhibited(true);b.setEventsInhibited(true);
                ThemeResetParityTest.random().setSeed(99);
                scalar(a,side,forces,java,mode,mode<2?8:85.125f,.83f);
                long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(99);
                long calls=HeightBrushAccess.completedThemedCalls();
                assertTrue("mode="+mode+" filter="+f,HeightBrushAccess.tryApplyFilteredThemed(b,-63,-63,side,side,
                        forces,mode,mode<2?8:85.125f,-64,319,EditorFilterPlan.compile(rust,b),.83f));
                assertEquals(calls+1,HeightBrushAccess.completedThemedCalls());
                assertEquals(next,ThemeResetParityTest.random().nextLong());
                a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
            }
        }finally{restore(old);}
    }
    @Test public void groupedFilteredThemeKeepsUndoRedoAndMissingTiles() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            Dimension a=FilteredHeightBrushBenchmark.fixture(),b=FilteredHeightBrushBenchmark.fixture(),before=FilteredHeightBrushBenchmark.fixture();
            for(Dimension d:new Dimension[]{a,b,before}){Tile t=d.getTile(0,0);if(t!=null)d.removeTile(t);}
            UndoManager undo=new UndoManager();b.registerUndoManager(undo);undo.armSavePoint();
            int side=129;float[] forces=new float[side*side];Arrays.fill(forces,.63f);
            Filter java=FilteredHeightBrushParityTest.filter(a,0),rust=FilteredHeightBrushParityTest.filter(b,0);
            a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(44);
            scalar(a,side,forces,java,0,8,1f);long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(44);
            assertTrue(HeightBrushAccess.tryApplyFilteredThemed(b,-63,-63,side,side,forces,0,8,-64,319,EditorFilterPlan.compile(rust,b),1f));
            assertEquals(next,ThemeResetParityTest.random().nextLong());
            a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
            assertTrue(undo.undo());ThemedFlattenBrushBenchmark.same(before,b);
            assertTrue(undo.redo());ThemedFlattenBrushBenchmark.same(a,b);
        }finally{restore(old);}
    }
    @Test public void rejectedFramesAndDisabledBackendPreservePlanesAndRandomState() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            Dimension a=FilteredHeightBrushBenchmark.fixture(),b=FilteredHeightBrushBenchmark.fixture();
            b.setEventsInhibited(true);var plan=EditorFilterPlan.compile(FilteredHeightBrushParityTest.filter(b,0),b);
            float[] forces=new float[129*129];Arrays.fill(forces,.7f);
            ThemeResetParityTest.random().setSeed(47);long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(47);
            long calls=HeightBrushAccess.completedThemedCalls();
            assertFalse(HeightBrushAccess.tryApplyFilteredThemed(b,-63,-63,129,129,forces,0,8,-64,319,null,1));
            assertFalse(HeightBrushAccess.tryApplyFilteredThemed(b,Integer.MIN_VALUE,0,129,129,forces,0,8,-64,319,plan,1));
            assertFalse(HeightBrushAccess.tryApplyFilteredThemed(b,-63,-63,129,129,forces,0,8,-64,319,plan,Float.NaN));
            assertFalse(HeightBrushAccess.tryApplyFilteredThemed(b,0,0,3,3,new float[9],0,8,-64,319,plan,1));
            System.setProperty(Native.GEN_KEY,"false");
            assertFalse(HeightBrushAccess.tryApplyFilteredThemed(b,-63,-63,129,129,forces,0,8,-64,319,plan,1));
            assertEquals(calls,HeightBrushAccess.completedThemedCalls());assertEquals(next,ThemeResetParityTest.random().nextLong());
            b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
        }finally{restore(old);}
    }
}
