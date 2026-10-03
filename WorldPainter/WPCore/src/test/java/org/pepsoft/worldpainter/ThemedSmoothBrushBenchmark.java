package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Whole smooth strokes include force preparation, the original ordered sums, theme and events. */
public final class ThemedSmoothBrushBenchmark {
    private static final ThreadLocal<Scratch> CACHE=ThreadLocal.withInitial(Scratch::new);
    static final class Scratch {
        int side;float[][] totals,heights;int[][] counts;float[] input,output;byte[] modified;
        void prepare(int side){if(this.side==side)return;this.side=side;int inputSide=side+10;totals=new float[inputSide][inputSide];heights=new float[inputSide][inputSide];counts=new int[inputSide][inputSide];input=new float[inputSide*inputSide];output=new float[side*side];modified=new byte[side*side];}
    }
    static void scalar(Dimension d,int ox,int oy,int side,float[] forces,Scratch s) {
        s.prepare(side);int inputSide=side+10;
        for(int x=0;x<inputSide;x++){Arrays.fill(s.totals[x],0f);Arrays.fill(s.heights[x],0f);Arrays.fill(s.counts[x],0);}
        for(int x=0;x<inputSide;x++){
            for(int y=0;y<inputSide;y++){
                float value=d.getHeightAt(ox+x-5,oy+y-5);
                if(value!=-Float.MAX_VALUE){s.heights[x][y]=value;
                    for(int dx=Math.max(x-5,0);dx<=Math.min(x+5,inputSide-1);dx++)for(int dy=Math.max(y-5,0);dy<=Math.min(y+5,inputSide-1);dy++){s.totals[dx][dy]+=value;s.counts[dx][dy]++;}}
            }
            if(x>=10)for(int y=5;y<side+5;y++){
                float strength=forces[(x-10)*side+y-5];
                if(strength>0f){float edited=strength*(s.totals[x-5][y]/s.counts[x-5][y])+(1-strength)*s.heights[x-5][y];int wx=ox+x-10,wy=oy+y-5;d.setHeightAt(wx,wy,edited);d.applyTheme(wx,wy);}
            }
        }
    }
    static void existing(Dimension d,int ox,int oy,int side,float[] forces,Scratch s) {
        s.prepare(side);int inputSide=side+10;TerrainHeightAccess.copy(d,ox-5,oy-5,inputSide,inputSide,s.input);
        if(!org.pepsoft.worldpainter.nativeapi.NativeSlices.smoothHeightRegion(inputSide,inputSide,s.input,forces,s.output,s.modified))throw new AssertionError("Existing smooth kernel unavailable");
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)if(s.modified[x*side+y]!=0){int wx=ox+x,wy=oy+y;d.setHeightAt(wx,wy,s.output[x*side+y]);d.applyTheme(wx,wy);}
    }
    private record Result(double millis,long allocated,Dimension dimension,long nextRandom,long calls){}
    private static Result run(String mode,int radius) throws Exception {
        Dimension d=ThemedHeightBrushBenchmark.fixture();int side=2*radius+1,origin=-radius/2;float[] forces=new float[side*side];Scratch scratch=CACHE.get();
        ThemeResetParityTest.random().setSeed(99);long beforeCalls=HeightBrushAccess.completedThemedCalls();var meter=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long allocated=meter.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        for(int stroke=0;stroke<8;stroke++){
            HeightBrushBenchmark.strengths(forces,radius,stroke);d.setEventsInhibited(true);
            try{if(mode.equals("rust")){if(!HeightBrushAccess.tryApplyThemed(d,origin,origin,side,side,forces,5,0,d.getMinHeight(),d.getMaxHeight()-1))throw new AssertionError("Grouped themed smooth unavailable");}
                else if(mode.equals("existing"))existing(d,origin,origin,side,forces,scratch);else scalar(d,origin,origin,side,forces,scratch);
            }finally{d.setEventsInhibited(false);}
        }
        double elapsed=(System.nanoTime()-start)/1e6;long bytes=meter.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated,calls=HeightBrushAccess.completedThemedCalls()-beforeCalls;
        if(calls!=(mode.equals("rust")?8:0))throw new AssertionError("Unexpected grouped call count");
        return new Result(elapsed,bytes,d,ThemeResetParityTest.random().nextLong(),calls);
    }
    public static void main(String[] args) throws Exception {
        String selected=args.length==0?"java":args[0];int radius=Integer.getInteger("welt.benchmark.smoothThemeRadius",122),warm=Integer.getInteger("welt.benchmark.smoothThemeWarmups",20),trials=Integer.getInteger("welt.benchmark.smoothThemeTrials",9);
        boolean paired=selected.startsWith("compare");String reference=selected.equals("compare-existing")?"existing":"java";
        double[] times=new double[trials],nativeTimes=new double[trials],ratios=new double[trials];long[] allocated=new long[trials],nativeAllocated=new long[trials];Result last=null;
        for(int i=-warm;i<trials;i++){
            if(paired){Result j=null,r=null;for(int pass=0;pass<2;pass++){if(((i+pass)&1)==0)j=run(reference,radius);else r=run("rust",radius);}ThemedFlattenBrushBenchmark.same(j.dimension,r.dimension);if(j.nextRandom!=r.nextRandom)throw new AssertionError("Theme RNG mismatch");last=r;
                if(i>=0){times[i]=j.millis;nativeTimes[i]=r.millis;ratios[i]=j.millis/r.millis;allocated[i]=j.allocated;nativeAllocated[i]=r.allocated;}}
            else{last=run(selected,radius);if(i>=0){times[i]=last.millis;allocated[i]=last.allocated;}}
        }
        Arrays.sort(times);Arrays.sort(nativeTimes);Arrays.sort(ratios);Arrays.sort(allocated);Arrays.sort(nativeAllocated);int mid=trials/2;
        if(paired)System.out.printf(Locale.ROOT,"themedSmooth radius=%d reference=%s referenceMs=%.3f rustMs=%.3f pairedRatio=%.3f range=%.3f..%.3f allocated=%d rustAllocated=%d nativeCalls=%d%n",radius,reference,times[mid],nativeTimes[mid],ratios[mid],ratios[0],ratios[trials-1],allocated[mid],nativeAllocated[mid],last.calls);
        else System.out.printf(Locale.ROOT,"themedSmooth radius=%d mode=%s medianMs=%.3f allocated=%d nativeCalls=%d%n",radius,selected,times[mid],allocated[mid],last.calls);
    }
}
