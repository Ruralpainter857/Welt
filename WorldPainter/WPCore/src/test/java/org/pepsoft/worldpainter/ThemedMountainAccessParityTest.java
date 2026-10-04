package org.pepsoft.worldpainter;

import java.util.Arrays;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Mountain shaping and theme preserve complete planes, random draws and grouped undo. */
public class ThemedMountainAccessParityTest {
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    @Test public void bothDirectionsMatchJavaIncludingSpecialForcesAndNegativeCoordinates() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(Native.GEN_KEY,"true");
            for(boolean tall:new boolean[]{false,true})for(boolean inverse:new boolean[]{false,true})for(float factor:new float[]{1,1.25f}){
                Dimension a=ThemedMountainBrushBenchmark.fixture(),b=ThemedMountainBrushBenchmark.fixture();
                if(!tall)for(Dimension d:new Dimension[]{a,b}){d.setMaxHeight(192);for(Tile t:d.getTiles())t.setMinMaxHeight(-64,192,HeightTransform.IDENTITY);((HeightMapTileFactory)d.getTileFactory()).getTheme().setMinMaxHeight(-64,192,HeightTransform.IDENTITY);}
                int side=129;float[] forces=new float[side*side];float[] values={0,-1,Float.NaN,.25f,1,2,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,.5f};
                for(int i=0;i<forces.length;i++)forces[i]=values[i%values.length];
                a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(83);
                for(int x=0;x<side;x++)for(int y=0;y<side;y++){
                    int wx=x-130,wy=y-65;float current=a.getHeightAt(wx,wy);
                    float target=MountainBrushBenchmark.target(wx,wy,forces[x*side+y],a.getMinHeight(),a.getMaxHeight()-1-a.getMinHeight(),inverse?64:220,factor,inverse);
                    if(inverse?target<current:target>current){a.setHeightAt(wx,wy,target);a.applyTheme(wx,wy);}
                }
                long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(83);
                long before=HeightBrushAccess.completedThemedCalls();
                assertTrue(MountainAccess.tryApplyThemed(b,-130,-65,side,forces,inverse?64:220,factor,inverse));
                assertEquals(before+1,HeightBrushAccess.completedThemedCalls());assertEquals(next,ThemeResetParityTest.random().nextLong());
                a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
            }
        } finally {restore(old);}
    }
    @Test public void missingTilesAndUndoRedoRetainPackedThemePlanes() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(Native.GEN_KEY,"true");
            Dimension a=ThemedMountainBrushBenchmark.fixture(),b=ThemedMountainBrushBenchmark.fixture(),before=ThemedMountainBrushBenchmark.fixture();
            for(Dimension d:new Dimension[]{a,b,before})d.removeTile(0,0);
            UndoManager undo=new UndoManager();b.registerUndoManager(undo);undo.armSavePoint();
            float[] forces=new float[129*129];Arrays.fill(forces,.73f);
            a.setEventsInhibited(true);b.setEventsInhibited(true);ThemeResetParityTest.random().setSeed(44);
            ThemedMountainBrushBenchmark.scalar(a,-63,-63,129,forces,false,220);
            long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(44);
            assertTrue(MountainAccess.tryApplyThemed(b,-63,-63,129,forces,220,1.25f,false));
            assertEquals(next,ThemeResetParityTest.random().nextLong());a.setEventsInhibited(false);b.setEventsInhibited(false);
            ThemedFlattenBrushBenchmark.same(a,b);assertTrue(undo.undo());ThemedFlattenBrushBenchmark.same(before,b);assertTrue(undo.redo());ThemedFlattenBrushBenchmark.same(a,b);
        } finally {restore(old);}
    }
    @Test public void rejectedAndDisabledTransactionsDoNotChangePlanesOrRandom() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(Native.GEN_KEY,"true");
            Dimension a=ThemedMountainBrushBenchmark.fixture(),b=ThemedMountainBrushBenchmark.fixture();
            float[] forces=new float[129*129];Arrays.fill(forces,.7f);b.setEventsInhibited(true);
            ThemeResetParityTest.random().setSeed(29);long next=ThemeResetParityTest.random().nextLong();ThemeResetParityTest.random().setSeed(29);
            long before=HeightBrushAccess.completedThemedCalls();
            assertFalse(MountainAccess.tryApplyThemed(b,-63,-63,129,forces,220,Float.NaN,false));
            assertFalse(MountainAccess.tryApplyThemed(b,-63,-63,129,forces,Float.POSITIVE_INFINITY,1,false));
            assertFalse(MountainAccess.tryApplyThemed(b,0,0,3,new float[9],220,1,false));
            assertFalse(MountainAccess.tryApplyThemed(b,-63,-63,129,null,220,1,false));
            assertFalse(MountainAccess.tryApplyThemed(b,Integer.MAX_VALUE,0,129,forces,220,1,false));
            System.setProperty(Native.GEN_KEY,"false");assertFalse(MountainAccess.tryApplyThemed(b,-63,-63,129,forces,220,1,false));
            assertEquals(before,HeightBrushAccess.completedThemedCalls());assertEquals(next,ThemeResetParityTest.random().nextLong());
            b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
        } finally {restore(old);}
    }
}
