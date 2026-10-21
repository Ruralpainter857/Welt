package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.util.Random;
import static org.junit.Assert.*;

public class HeightStatisticsParityTest {
    private static void sameQueries(Tile t) {
        System.setProperty(Native.GEN_KEY,"false");int low=t.getLowestRawHeight(),high=t.getHighestRawHeight();int[] range=t.getRawHeightRange();
        System.setProperty(Native.GEN_KEY,"true");assertEquals(low,t.getLowestRawHeight());assertEquals(high,t.getHighestRawHeight());assertArrayEquals(range,t.getRawHeightRange());
    }
    @Test public void shortTallAndOutOfRangeDataMatchOriginalJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());String old=System.getProperty(Native.GEN_KEY);
        try {Random random=new Random(19);
            for(boolean tall:new boolean[]{false,true})for(int kind=0;kind<7;kind++) {
                Tile t=new Tile(-100,37,-64,tall?320:192);t.inhibitEvents();
                for(int y=0;y<128;y++)for(int x=0;x<128;x++)t.setRawHeight(x,y,kind==0?0:kind==1?12000:kind==2?Integer.MIN_VALUE:
                        kind==3?Integer.MAX_VALUE:kind==4?random.nextInt():kind==5?random.nextInt(65280):65280);
                t.releaseEvents();sameQueries(t);
                t.setRawHeight(127,127,Integer.MAX_VALUE);sameQueries(t);t.setRawHeight(0,0,Integer.MIN_VALUE);sameQueries(t);
            }
            System.setProperty(Native.GEN_KEY,"true");assertEquals(Long.MIN_VALUE,NativeSlices.heightStatistics(null,null,1,0));
            assertEquals(Long.MIN_VALUE,NativeSlices.heightStatistics(new short[10],null,1,0));
            assertEquals(Long.MIN_VALUE,NativeSlices.heightStatistics(new short[16384],new int[16384],1,0));
            assertEquals(Long.MIN_VALUE,NativeSlices.heightStatistics(new short[16384],null,1,3));
        }finally{restore(old);}
    }
    @Test public void queriesRemainReadOnlyAcrossUndoAndUniformCopyOnWrite() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());String old=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(Native.GEN_KEY,"true");Tile t=new Tile(0,0,-64,320);Tile untouched=new Tile(1,0,-64,320);
            UndoManager undo=new UndoManager();t.register(undo);undo.armSavePoint();
            for(int i=0;i<20;i++)sameQueries(t);assertFalse("Read-only queries must not create an undo frame",undo.undo());
            t.setRawHeight(5,6,12345);sameQueries(t);assertEquals(0,untouched.getRawHeight(5,6));
            assertTrue(undo.undo());sameQueries(t);assertEquals(0,t.getHighestRawHeight());
            assertTrue(undo.redo());sameQueries(t);assertEquals(12345,t.getHighestRawHeight());
        }finally{restore(old);}
    }
    @Test public void completeWorldRangeAndFloatConversionsMatchAcrossStorageFormats() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());String old=System.getProperty(Native.GEN_KEY);
        try {for(boolean tall:new boolean[]{false,true}) {
            Dimension d=HeightStatisticsBenchmark.fixture(tall);
            System.setProperty(Native.GEN_KEY,"false");int[] raw=d.getRawHeightRange(),integer=d.getIntHeightRange();float[] heights=d.getHeightRange();
            System.setProperty(Native.GEN_KEY,"true");assertArrayEquals(raw,d.getRawHeightRange());assertArrayEquals(integer,d.getIntHeightRange());assertArrayEquals(heights,d.getHeightRange(),0f);
        }}finally{restore(old);}
    }
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
}
