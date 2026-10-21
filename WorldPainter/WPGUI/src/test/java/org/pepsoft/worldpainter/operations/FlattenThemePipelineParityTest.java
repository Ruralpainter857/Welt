package org.pepsoft.worldpainter.operations;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.lang.reflect.*;
import java.util.Random;
import static org.junit.Assert.*;

/** Exercise the actual GUI brush pipeline without opening a window. */
public class FlattenThemePipelineParityTest {
    private static Object call(String type,String name,Class<?>[] signature,Object... args) throws Exception {
        Method method=Class.forName(type).getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(null,args);
    }
    private static Dimension fixture() throws Exception {return (Dimension)call("org.pepsoft.worldpainter.ThemedHeightBrushBenchmark","fixture",new Class<?>[0]);}
    private static Random random() throws Exception {return (Random)call("org.pepsoft.worldpainter.ThemeResetParityTest","random",new Class<?>[0]);}
    @Test public void actualFlattenPipelineGroupsHeightAndThemeForAllModes() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            for(Flatten.Mode mode:Flatten.Mode.values())for(int radius:new int[]{64,127}){
                Dimension expected=fixture(),actual=fixture();Flatten brush=new Flatten(new BrushPipelineTestView());
                brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(radius);brush.setLevel(.63f);
                Field setting=Flatten.class.getDeclaredField("mode");setting.setAccessible(true);setting.set(brush,mode);
                Field target=Flatten.class.getDeclaredField("targetHeight");target.setAccessible(true);target.setFloat(brush,87.125f);
                int side=2*radius+1,centre=-3;float[] forces=new float[side*side];
                for(int x=0;x<side;x++)for(int y=0;y<side;y++)forces[x*side+y]=.91f*brush.getStrength(centre,centre,centre-radius+x,centre-radius+y);
                expected.setEventsInhibited(true);actual.setEventsInhibited(true);random().setSeed(57);
                call("org.pepsoft.worldpainter.ThemedFlattenBrushBenchmark","scalar",new Class<?>[]{Dimension.class,int.class,int.class,int.class,float[].class,int.class,float.class},expected,centre-radius,centre-radius,side,forces,mode.ordinal()+2,87.125f);
                long next=random().nextLong();random().setSeed(57);long before=HeightBrushAccess.completedThemedCalls();
                Method pipeline=Flatten.class.getDeclaredMethod("flattenNative",Dimension.class,int.class,int.class,int.class,float.class,boolean.class);pipeline.setAccessible(true);
                assertTrue((Boolean)pipeline.invoke(brush,actual,centre,centre,radius,.91f,true));assertEquals(1,HeightBrushAccess.completedThemedCalls()-before);assertEquals(next,random().nextLong());
                expected.setEventsInhibited(false);actual.setEventsInhibited(false);
                call("org.pepsoft.worldpainter.ThemedFlattenBrushBenchmark","same",new Class<?>[]{Dimension.class,Dimension.class},expected,actual);
            }
        }finally{if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
}
