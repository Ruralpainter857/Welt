package org.pepsoft.worldpainter.operations;

import java.lang.reflect.*;
import java.util.Random;
import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Actual GUI dispatch preserves live filters, theme draws and one-call transactions. */
public class FilteredThemedPipelineParityTest {
    private static Object call(String type,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=Class.forName(type).getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private static Dimension fixture() throws Exception {
        return (Dimension)call("org.pepsoft.worldpainter.FilteredHeightBrushBenchmark","fixture",new Class<?>[0]);
    }
    private static Filter filter(Dimension d,int selected) throws Exception {
        return (Filter)call("org.pepsoft.worldpainter.FilteredHeightBrushParityTest","filter",new Class<?>[]{Dimension.class,int.class},d,selected);
    }
    private static Random random() throws Exception {
        return (Random)call("org.pepsoft.worldpainter.ThemeResetParityTest","random",new Class<?>[0]);
    }
    private static void same(Dimension a,Dimension b) throws Exception {
        call("org.pepsoft.worldpainter.ThemedFlattenBrushBenchmark","same",new Class<?>[]{Dimension.class,Dimension.class},a,b);
    }
    private static void restore(String key,String old){if(old==null)System.clearProperty(key);else System.setProperty(key,old);}
    @Test public void heightAndSmoothUseLiveFilteredThemeTransactions() throws Exception {
        String key="welt.native.filteredThemedHeight",old=System.getProperty(key),gen=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(key,"true");System.setProperty(Native.GEN_KEY,"true");
            for(boolean smooth:new boolean[]{false,true})for(int f:new int[]{0,3,5}){
                Dimension a=fixture(),b=fixture();AbstractBrushOperation brush=smooth?new Smooth(new BrushPipelineTestView()):new Height(new BrushPipelineTestView());
                brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(64);brush.setLevel(.63f);
                Class<?>[] types=smooth?new Class<?>[]{Dimension.class,int.class,int.class,int.class,float.class,boolean.class}
                        :new Class<?>[]{Dimension.class,int.class,int.class,int.class,boolean.class,float.class,float.class,float.class,boolean.class};
                Method java=brush.getClass().getDeclaredMethod(smooth?"smoothJava":"applyJavaHeightBrush",types);
                Method rust=brush.getClass().getDeclaredMethod(smooth?"smoothNative":"applyNativeHeightBrush",types);
                java.setAccessible(true);rust.setAccessible(true);a.setEventsInhibited(true);b.setEventsInhibited(true);
                brush.setFilter(filter(a,f));random().setSeed(57);
                if(smooth)java.invoke(brush,a,-3,-3,64,.91f,true);else java.invoke(brush,a,-3,-3,64,false,8f,-64f,319f,true);
                long next=random().nextLong();random().setSeed(57);brush.setFilter(filter(b,f));long before=HeightBrushAccess.completedThemedCalls();
                boolean applied=(Boolean)(smooth?rust.invoke(brush,b,-3,-3,64,.91f,true):rust.invoke(brush,b,-3,-3,64,false,8f,-64f,319f,true));
                assertTrue("smooth="+smooth+" filter="+f,applied);assertEquals(before+1,HeightBrushAccess.completedThemedCalls());assertEquals(next,random().nextLong());
                a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);
            }
        } finally {restore(key,old);restore(Native.GEN_KEY,gen);}
    }
    @Test public void flattenModesPreserveFilteredThemeOrdering() throws Exception {
        String key="welt.native.filteredThemedHeight",old=System.getProperty(key),gen=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(key,"true");System.setProperty(Native.GEN_KEY,"true");
            for(Flatten.Mode mode:Flatten.Mode.values()){
                Dimension a=fixture(),b=fixture();Flatten brush=new Flatten(new BrushPipelineTestView());
                brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(64);brush.setLevel(.63f);
                Field setting=Flatten.class.getDeclaredField("mode"),target=Flatten.class.getDeclaredField("targetHeight");setting.setAccessible(true);target.setAccessible(true);setting.set(brush,mode);target.setFloat(brush,85.125f);
                a.setEventsInhibited(true);b.setEventsInhibited(true);Filter java=filter(a,0);random().setSeed(57);
                for(int x=-67;x<=61;x++)for(int y=-67;y<=61;y++){
                    float current=a.getHeightAt(x,y),strength=.91f*java.modifyStrength(x,y,brush.getBrush().getStrength(x+3,y+3));
                    if(strength>0){float edited=strength*85.125f+(1-strength)*current;
                        if(mode==Flatten.Mode.FLATTEN||(mode==Flatten.Mode.RAISE?edited>current:edited<current)){a.setHeightAt(x,y,edited);a.applyTheme(x,y);}}
                }
                long next=random().nextLong();random().setSeed(57);brush.setFilter(filter(b,0));long before=HeightBrushAccess.completedThemedCalls();
                Method rust=Flatten.class.getDeclaredMethod("flattenNative",Dimension.class,int.class,int.class,int.class,float.class,boolean.class);rust.setAccessible(true);
                assertTrue((Boolean)rust.invoke(brush,b,-3,-3,64,.91f,true));assertEquals(before+1,HeightBrushAccess.completedThemedCalls());assertEquals(next,random().nextLong());
                a.setEventsInhibited(false);b.setEventsInhibited(false);same(a,b);
            }
        } finally {restore(key,old);restore(Native.GEN_KEY,gen);}
    }
}
