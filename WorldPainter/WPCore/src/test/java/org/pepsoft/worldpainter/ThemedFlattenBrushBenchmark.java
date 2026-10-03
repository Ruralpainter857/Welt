package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Complete flatten strokes include force preparation, height/theme edits and notifications. */
public final class ThemedFlattenBrushBenchmark {
    static void scalar(Dimension d,int ox,int oy,int side,float[] forces,int mode,float target) {
        for(int x=0;x<side;x++)for(int y=0;y<side;y++) {
            int wx=ox+x,wy=oy+y;float current=d.getHeightAt(wx,wy),strength=forces[x*side+y];
            if(!(strength>0f))continue;
            float edited=strength*target+(1f-strength)*current;
            if(mode==HeightBrushAccess.FLATTEN||(mode==HeightBrushAccess.FLATTEN_RAISE?edited>current:edited<current)) {
                d.setHeightAt(wx,wy,edited);d.applyTheme(wx,wy);
            }
        }
    }
    static void legacy(Dimension d,int ox,int oy,int side,float[] forces,int mode,float target,float[] heights,byte[] modified) {
        TerrainHeightAccess.copy(d,ox,oy,side,side,heights);
        if(!org.pepsoft.worldpainter.nativeapi.NativeSlices.applyFlattenBrush(mode-2,target,heights,forces,modified))throw new AssertionError("Existing height kernel unavailable");
        for(int x=0;x<side;x++)for(int y=0;y<side;y++)if(modified[x*side+y]!=0){int wx=ox+x,wy=oy+y;d.setHeightAt(wx,wy,heights[x*side+y]);d.applyTheme(wx,wy);}
    }
    private record Result(double millis,long bytes,Dimension dimension,long nextRandom,int calls) { }
    private static Result run(String mode,int radius) throws Exception {
        Dimension d=ThemedHeightBrushBenchmark.fixture();int side=radius*2+1,origin=-radius/2;
        float[] forces=new float[side*side],heights=new float[side*side];byte[] modified=new byte[side*side];
        ThemeResetParityTest.random().setSeed(99);
        var meter=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long beforeCalls=HeightBrushAccess.completedThemedCalls();
        long allocated=meter.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();int calls=0;
        for(int stroke=0;stroke<8;stroke++) {
            HeightBrushBenchmark.strengths(forces,radius,stroke);int operation=2+stroke%3;float target=stroke%2==0?74.5f:115.125f;
            d.setEventsInhibited(true);
            try {
                if(mode.equals("rust")) {
                    if(!HeightBrushAccess.tryApplyThemed(d,origin,origin,side,side,forces,operation,target,d.getMinHeight(),d.getMaxHeight()-1))throw new AssertionError("Native flatten transaction unavailable");
                    calls++;
                }else if(mode.equals("existing"))legacy(d,origin,origin,side,forces,operation,target,heights,modified);
                else scalar(d,origin,origin,side,forces,operation,target);
            }finally{d.setEventsInhibited(false);}
        }
        double elapsed=(System.nanoTime()-start)/1e6;long bytes=meter.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
        if(HeightBrushAccess.completedThemedCalls()-beforeCalls!=calls)throw new AssertionError("Unexpected native transaction count");
        return new Result(elapsed,bytes,d,ThemeResetParityTest.random().nextLong(),calls);
    }
    static void same(Dimension a,Dimension b){for(Tile t:a.getTiles())ThemeResetParityTest.same(t,b.getTile(t.getX(),t.getY()));}
    public static void main(String[] args) throws Exception {
        String selected=args.length==0?"java":args[0];int radius=Integer.getInteger("welt.benchmark.flattenRadius",127);
        int warm=Integer.getInteger("welt.benchmark.flattenWarmups",20),trials=Integer.getInteger("welt.benchmark.flattenTrials",9);
        boolean paired=selected.startsWith("compare");String reference=selected.equals("compare-existing")?"existing":"java";
        double[] times=new double[trials],nativeTimes=new double[trials],ratios=new double[trials];long[] bytes=new long[trials],nativeBytes=new long[trials];Result last=null;
        for(int i=-warm;i<trials;i++) {
            if(paired){Result j=null,r=null;for(int pass=0;pass<2;pass++){if(((i+pass)&1)==0)j=run(reference,radius);else r=run("rust",radius);}same(j.dimension,r.dimension);if(j.nextRandom!=r.nextRandom)throw new AssertionError("Theme random state differs");last=r;
                if(i>=0){times[i]=j.millis;nativeTimes[i]=r.millis;ratios[i]=j.millis/r.millis;bytes[i]=j.bytes;nativeBytes[i]=r.bytes;}}
            else{last=run(selected,radius);if(i>=0){times[i]=last.millis;bytes[i]=last.bytes;}}
        }
        Arrays.sort(times);Arrays.sort(nativeTimes);Arrays.sort(ratios);Arrays.sort(bytes);Arrays.sort(nativeBytes);int middle=trials/2;
        if(paired)System.out.printf(Locale.ROOT,"themedFlatten radius=%d reference=%s referenceMs=%.3f rustMs=%.3f pairedRatio=%.3f range=%.3f..%.3f allocated=%d rustAllocated=%d nativeCalls=%d%n",radius,reference,times[middle],nativeTimes[middle],ratios[middle],ratios[0],ratios[trials-1],bytes[middle],nativeBytes[middle],last.calls);
        else System.out.printf(Locale.ROOT,"themedFlatten radius=%d mode=%s medianMs=%.3f allocated=%d nativeCalls=%d%n",radius,selected,times[middle],bytes[middle],last.calls);
    }
}
