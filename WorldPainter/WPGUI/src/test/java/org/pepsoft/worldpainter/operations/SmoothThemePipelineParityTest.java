package org.pepsoft.worldpainter.operations;

import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.lang.reflect.Method;
import java.util.Random;
import static org.junit.Assert.*;

/** Check the actual GUI smoothing methods against one another without opening a window. */
public class SmoothThemePipelineParityTest {
    private static Object call(String type,String name,Class<?>[] signature,Object... args) throws Exception {
        Method method=Class.forName(type).getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(null,args);
    }
    private static Dimension fixture() throws Exception {return (Dimension)call("org.pepsoft.worldpainter.ThemedHeightBrushBenchmark","fixture",new Class<?>[0]);}
    private static Random random() throws Exception {return (Random)call("org.pepsoft.worldpainter.ThemeResetParityTest","random",new Class<?>[0]);}
    @Test public void actualSmoothPipelineKeepsPlanesAndThemeDrawsWithOneCall() throws Exception {
        String old=System.getProperty(Native.GEN_KEY);
        try{System.setProperty(Native.GEN_KEY,"true");
            for(int radius:new int[]{64,122}){
                Dimension expected=fixture(),actual=fixture();Smooth brush=new Smooth(new BrushPipelineTestView());
                brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(radius);brush.setLevel(.63f);
                Class<?>[] signature={Dimension.class,int.class,int.class,int.class,float.class,boolean.class};
                Method java=Smooth.class.getDeclaredMethod("smoothJava",signature),nativeMethod=Smooth.class.getDeclaredMethod("smoothNative",signature);
                java.setAccessible(true);nativeMethod.setAccessible(true);
                expected.setEventsInhibited(true);actual.setEventsInhibited(true);random().setSeed(57);
                java.invoke(brush,expected,-3,-3,radius,.91f,true);
                long next=random().nextLong();random().setSeed(57);long before=HeightBrushAccess.completedThemedCalls();
                assertTrue((Boolean)nativeMethod.invoke(brush,actual,-3,-3,radius,.91f,true));
                assertEquals(1,HeightBrushAccess.completedThemedCalls()-before);assertEquals(next,random().nextLong());
                expected.setEventsInhibited(false);actual.setEventsInhibited(false);
                call("org.pepsoft.worldpainter.ThemedFlattenBrushBenchmark","same",new Class<?>[]{Dimension.class,Dimension.class},expected,actual);
            }
        }finally{if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
}
