package org.pepsoft.worldpainter.operations;
import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.FilteredPaintAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.panels.DefaultFilter;
import java.lang.reflect.*;
import static org.junit.Assert.*;
/** Actual GUI height methods and filtered flattening share the grouped backend. */
public class FilteredHeightPipelineParityTest {
    private static Object call(String type,String name,Class<?>[] signature,Object... args) throws Exception {
        Method m=Class.forName(type).getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(null,args);
    }
    private static Dimension fixture() throws Exception{return (Dimension)call("org.pepsoft.worldpainter.FilteredHeightBrushBenchmark","fixture",new Class<?>[0]);}
    private static DefaultFilter filter(Dimension d) throws Exception{return (DefaultFilter)call("org.pepsoft.worldpainter.FilteredHeightBrushBenchmark","filter",new Class<?>[]{Dimension.class},d);}
    private static void same(Dimension a,Dimension b) throws Exception{call("org.pepsoft.worldpainter.ThemedFlattenBrushBenchmark","same",new Class<?>[]{Dimension.class,Dimension.class},a,b);}
    @Test public void actualFilteredHeightPipelineKeepsJavaResultsInBothDirections() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);try{System.setProperty(Native.GEN_KEY,"true");
            for(boolean inverse:new boolean[]{false,true}){
                Dimension a=fixture(),b=fixture();Height brush=new Height(null);brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(64);brush.setLevel(.63f);
                Class<?>[] signature={Dimension.class,int.class,int.class,int.class,boolean.class,float.class,float.class,float.class,boolean.class};
                Method java=Height.class.getDeclaredMethod("applyJavaHeightBrush",signature),rust=Height.class.getDeclaredMethod("applyNativeHeightBrush",signature);java.setAccessible(true);rust.setAccessible(true);
                a.setEventsInhibited(true);b.setEventsInhibited(true);brush.setFilter(filter(a));java.invoke(brush,a,-3,-3,64,inverse,8f,68f,110f,false);
                brush.setFilter(filter(b));long before=FilteredPaintAccess.completedTransactions();assertTrue((Boolean)rust.invoke(brush,b,-3,-3,64,inverse,8f,68f,110f,false));assertEquals(before+1,FilteredPaintAccess.completedTransactions());
                a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);
            }
        }finally{if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
    @Test public void actualFilteredFlattenPipelinePreservesStrengthOrderForEveryMode() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);try{System.setProperty(Native.GEN_KEY,"true");
            for(Flatten.Mode mode:Flatten.Mode.values()){
                Dimension a=fixture(),b=fixture();Flatten brush=new Flatten(null);brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(64);brush.setLevel(.63f);
                Field setting=Flatten.class.getDeclaredField("mode"),target=Flatten.class.getDeclaredField("targetHeight");setting.setAccessible(true);target.setAccessible(true);setting.set(brush,mode);target.setFloat(brush,85.125f);
                a.setEventsInhibited(true);b.setEventsInhibited(true);DefaultFilter f=filter(a);
                for(int x=-67;x<=61;x++)for(int y=-67;y<=61;y++){
                    float current=a.getHeightAt(x,y),strength=.91f*f.modifyStrength(x,y,brush.getBrush().getStrength(x+3,y+3));
                    if(strength>0){float edited=strength*85.125f+(1-strength)*current;if(mode==Flatten.Mode.FLATTEN||(mode==Flatten.Mode.RAISE?edited>current:edited<current))a.setHeightAt(x,y,edited);}
                }
                brush.setFilter(filter(b));long before=FilteredPaintAccess.completedTransactions();Method rust=Flatten.class.getDeclaredMethod("flattenNative",Dimension.class,int.class,int.class,int.class,float.class,boolean.class);rust.setAccessible(true);
                assertTrue((Boolean)rust.invoke(brush,b,-3,-3,64,.91f,false));assertEquals(before+1,FilteredPaintAccess.completedTransactions());a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);
            }
        }finally{if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
}
