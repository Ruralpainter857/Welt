package org.pepsoft.worldpainter.operations;

import java.lang.reflect.*;
import java.util.Random;
import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** The actual mountain GUI target and grouped dispatch preserve complete theme planes. */
public class MountainThemePipelineParityTest {
    private static Object call(String type,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=Class.forName(type).getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private static Dimension fixture() throws Exception {
        return (Dimension)call("org.pepsoft.worldpainter.ThemedMountainBrushBenchmark","fixture",new Class<?>[0]);
    }
    private static Random random() throws Exception {
        return (Random)call("org.pepsoft.worldpainter.ThemeResetParityTest","random",new Class<?>[0]);
    }
    private static void restore(String key,String old){if(old==null)System.clearProperty(key);else System.setProperty(key,old);}
    @Test public void actualMountainTargetsAndDispatchKeepBothDirectionsExact() throws Exception {
        String key="welt.native.themedMountain",old=System.getProperty(key),gen=System.getProperty(Native.GEN_KEY);
        try {System.setProperty(key,"true");System.setProperty(Native.GEN_KEY,"true");
            for(boolean inverse:new boolean[]{false,true}) {
                Dimension a=fixture(),b=fixture();RaiseMountain brush=new RaiseMountain(new BrushPipelineTestView());
                brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(64);brush.setLevel(.63f);
                Field factor=RaiseMountain.class.getDeclaredField("peakFactor");factor.setAccessible(true);factor.setFloat(brush,1.25f);
                Method target=RaiseMountain.class.getDeclaredMethod("getTargetHeight",int.class,int.class,int.class,int.class,int.class,int.class,float.class,boolean.class);
                Method rust=RaiseMountain.class.getDeclaredMethod("applyNativeMountain",Dimension.class,int.class,int.class,int.class,boolean.class,int.class,int.class,float.class,boolean.class);
                target.setAccessible(true);rust.setAccessible(true);a.setEventsInhibited(true);b.setEventsInhibited(true);random().setSeed(37);
                float peak=inverse?64:220;int min=a.getMinHeight(),range=a.getMaxHeight()-1-min;
                for(int x=-67;x<=61;x++)for(int y=-67;y<=61;y++){
                    float current=a.getHeightAt(x,y),edited=(Float)target.invoke(brush,min,range,-3,-3,x,y,peak,inverse);
                    if(inverse?edited<current:edited>current){a.setHeightAt(x,y,edited);a.applyTheme(x,y);}
                }
                long next=random().nextLong();random().setSeed(37);long before=HeightBrushAccess.completedThemedCalls();
                assertTrue((Boolean)rust.invoke(brush,b,-3,-3,64,inverse,min,range,peak,true));
                assertEquals(before+1,HeightBrushAccess.completedThemedCalls());assertEquals(next,random().nextLong());
                a.setEventsInhibited(false);b.setEventsInhibited(false);
                call("org.pepsoft.worldpainter.ThemedFlattenBrushBenchmark","same",new Class<?>[]{Dimension.class,Dimension.class},a,b);
            }
        }finally{restore(key,old);restore(Native.GEN_KEY,gen);}
    }
}
