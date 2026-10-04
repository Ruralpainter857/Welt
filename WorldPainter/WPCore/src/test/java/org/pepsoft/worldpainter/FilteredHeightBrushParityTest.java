package org.pepsoft.worldpainter;
import org.junit.Test;
import java.util.*;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.panels.*;
import org.pepsoft.worldpainter.operations.Filter;
import static org.junit.Assert.*;
/** Exercise evolving filters, quantisation and ordered cross-tile writes for all height modes. */
public class FilteredHeightBrushParityTest {
    private static Filter filter(Dimension d,int i){return switch(i){
        case 0 -> new DefaultFilter(d,false,false,Integer.MIN_VALUE,Integer.MIN_VALUE,false,false,null,false,null,75,false);
        case 1 -> new DefaultFilter(d,false,false,70,110,true,true,Terrain.GRASS,false,null,30,false);
        case 2 -> new DefaultFilter(d,false,false,100,80,true,false,null,true,TerrainOrLayerFilter.WATER,20,true);
        case 3 -> new DefaultFilter(d,false,false,Integer.MIN_VALUE,Integer.MIN_VALUE,false,true,new DefaultFilter.LayerValue(Biome.INSTANCE,-4),false,null,-1,false);
        case 4 -> new DefaultFilter(d,true,false,Integer.MIN_VALUE,Integer.MIN_VALUE,false,false,null,false,null,-1,false);
        default -> new CombinedFilter(List.of(OnlyOnTerrainOrLayerFilter.create(d,Resources.INSTANCE),ExceptOnTerrainOrLayerFilter.create(d,TerrainOrLayerFilter.LAVA)));
    };}
    private static void scalar(Dimension d,int side,float[] forces,Filter f,int mode,float value){
        for(int x=0;x<side;x++)for(int y=0;y<side;y++){
            int wx=x-63,wy=y-63;float current=d.getHeightAt(wx,wy),target=mode==0?Math.min(current+value,319):mode==1?Math.max(current-value,-64):value;
            float force=.83f*f.modifyStrength(wx,wy,forces[y*side+x]);
            if(force>0){float edited=force*target+(1-force)*current;if(mode==2||(mode==0||mode==3?edited>current:edited<current))d.setHeightAt(wx,wy,edited);}
        }
    }
    private static void restore(String old){if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    @Test public void allModesKeepEvolvingSlopesWaterBiomesSelectionAndLayerPredicates() {
        String old=System.getProperty(Native.GEN_KEY);try{System.setProperty(Native.GEN_KEY,"true");int side=129;float[] forces=new float[side*side];Arrays.fill(forces,.7f);
            for(int mode=0;mode<5;mode++)for(int f=0;f<6;f++){
                Dimension a=FilteredHeightBrushBenchmark.fixture(),b=FilteredHeightBrushBenchmark.fixture();Filter ja=filter(a,f),rb=filter(b,f);
                a.setEventsInhibited(true);b.setEventsInhibited(true);scalar(a,side,forces,ja,mode,mode<2?8:85.125f);
                long before=FilteredPaintAccess.completedTransactions();assertTrue(FilteredPaintAccess.applyHeight(b,EditorFilterPlan.compile(rb,b),-63,-63,side,side,forces,mode,mode<2?8:85.125f,-64,319,.83f));assertEquals(before+1,FilteredPaintAccess.completedTransactions());
                a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
            }
        }finally{restore(old);}
    }
    @Test public void specialStrengthsShortStorageMissingTilesAndUndoKeepExactRawPlanes(){
        String old=System.getProperty(Native.GEN_KEY);try{System.setProperty(Native.GEN_KEY,"true");
            for(boolean tall:new boolean[]{false,true}){
                Dimension a=FilteredHeightBrushBenchmark.fixture(),b=FilteredHeightBrushBenchmark.fixture(),before=FilteredHeightBrushBenchmark.fixture();
                for(Dimension d:new Dimension[]{a,b,before}){d.removeTile(0,0);if(!tall){d.setMaxHeight(192);for(Tile t:d.getTiles())t.setMinMaxHeight(-64,192,HeightTransform.IDENTITY);}}
                UndoManager undo=new UndoManager();b.registerUndoManager(undo);undo.armSavePoint();
                int side=129;float[] forces=new float[side*side];float[] values={0,-1,Float.NaN,.25f,1,2,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,.5f};for(int i=0;i<forces.length;i++)forces[i]=values[i%values.length];
                for(int mode=0;mode<5;mode++){
                    a.setEventsInhibited(true);b.setEventsInhibited(true);Filter ja=filter(a,0),rb=filter(b,0);scalar(a,side,forces,ja,mode,85.125f);
                    assertTrue(FilteredPaintAccess.applyHeight(b,EditorFilterPlan.compile(rb,b),-63,-63,side,side,forces,mode,85.125f,-64,319,.83f));
                    a.setEventsInhibited(false);b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
                }
                assertTrue(undo.undo());ThemedFlattenBrushBenchmark.same(before,b);assertTrue(undo.redo());ThemedFlattenBrushBenchmark.same(a,b);
            }
        }finally{restore(old);}
    }
    @Test public void invalidAndDisabledPathsPreserveTiles(){
        String old=System.getProperty(Native.GEN_KEY);try{System.setProperty(Native.GEN_KEY,"true");Dimension a=FilteredHeightBrushBenchmark.fixture(),b=FilteredHeightBrushBenchmark.fixture();b.setEventsInhibited(true);
            var p=EditorFilterPlan.compile(filter(b,0),b);float[] forces=new float[129*129];Arrays.fill(forces,1);
            assertFalse(FilteredPaintAccess.applyHeight(b,p,-63,-63,129,129,forces,5,8,-64,319));
            assertFalse(FilteredPaintAccess.applyHeight(b,p,Integer.MIN_VALUE,0,129,129,forces,0,8,-64,319));
            assertFalse(FilteredPaintAccess.applyHeight(b,null,-63,-63,129,129,forces,0,8,-64,319));
            System.setProperty(Native.GEN_KEY,"false");assertFalse(FilteredPaintAccess.applyHeight(b,p,-63,-63,129,129,forces,0,8,-64,319));b.setEventsInhibited(false);ThemedFlattenBrushBenchmark.same(a,b);
        }finally{restore(old);}
    }
}
