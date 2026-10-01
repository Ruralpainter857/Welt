package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Complete strokes include force preparation, height edits, themes and notifications. */
public final class ThemedHeightBrushBenchmark {
    static Dimension fixture() {
        Dimension d = ErosionRegionBenchmark.fixture();
        ((HeightMapTileFactory)d.getTileFactory()).setTheme(ThemeResetParityTest.theme(true, true));
        return d;
    }
    static void scalar(Dimension d, int ox, int oy, int side, float[] forces, boolean inverse, float value) {
        for (int x=0;x<side;x++) for(int y=0;y<side;y++) {
            float strength=forces[x*side+y];
            if (!(strength>0f)) continue;
            int wx=ox+x,wy=oy+y;float current=d.getHeightAt(wx,wy);
            float target=inverse?Math.max(current-value,d.getMinHeight()):Math.min(current+value,d.getMaxHeight()-1);
            float edited=strength*target+(1f-strength)*current;
            if(inverse?edited<current:edited>current){d.setHeightAt(wx,wy,edited);d.applyTheme(wx,wy);}
        }
    }
    public static void main(String[] args) throws Exception {
        boolean rust=args.length>0&&args[0].equals("rust");
        int radius=args.length>1?Integer.parseInt(args[1]):127,side=2*radius+1,origin=-radius/2;
        var nativeStroke=rust?HeightBrushAccess.class.getMethod("tryApplyThemed",Dimension.class,int.class,int.class,int.class,int.class,
                float[].class,int.class,float.class,float.class,float.class):null;
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        double[] times=new double[7];long[] allocated=new long[7];
        for(int trial=-5;trial<7;trial++) {
            Dimension d=fixture();float[] forces=new float[side*side];ThemeResetParityTest.random().setSeed(99);
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
            for(int stroke=0;stroke<8;stroke++) {
                HeightBrushBenchmark.strengths(forces,radius,stroke);d.setEventsInhibited(true);
                try {
                    if(rust){if(!(Boolean)nativeStroke.invoke(null,d,origin,origin,side,side,forces,stroke%2,7.5f,
                            (float)d.getMinHeight(),(float)(d.getMaxHeight()-1))) {
                        if(side>=128)throw new AssertionError("Native themed brush unavailable");
                        scalar(d,origin,origin,side,forces,stroke%2!=0,7.5f);
                    }}
                    else scalar(d,origin,origin,side,forces,stroke%2!=0,7.5f);
                }finally{d.setEventsInhibited(false);}
            }
            if(trial>=0){times[trial]=(System.nanoTime()-start)/1e6;allocated[trial]=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;}
        }
        Arrays.sort(times);Arrays.sort(allocated);
        System.out.printf("%s radius=%d themed_height_8_strokes_ms=%.3f heap_allocated_bytes=%d%n",rust?"Rust":"Java",radius,times[3],allocated[3]);
    }
}
