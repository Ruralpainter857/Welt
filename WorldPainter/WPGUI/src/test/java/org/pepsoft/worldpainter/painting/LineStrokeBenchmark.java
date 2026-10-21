package org.pepsoft.worldpainter.painting;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.LineStrokeAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Measures complete lines, including brush preparation, tile access and event release. */
public final class LineStrokeBenchmark {
    private static volatile long checksum;
    private record Result(long nanos, long allocated, long hash, long calls) { }
    private static Result run(boolean nativeMode, boolean wholeStroke) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        System.setProperty("welt.native.lineStroke", Boolean.toString(wholeStroke));
        Dimension dimension=NibbleLayerPaintParityTest.fixture();
        NibbleLayerPaint paint=new NibbleLayerPaint(Resources.INSTANCE);
        SymmetricBrush brush=SymmetricBrush.LINEAR_CIRCLE.clone();
        brush.setRadius(Integer.getInteger("welt.benchmark.strokeRadius",16));brush.setLevel(.63f);paint.setBrush(brush);
        DimensionPainter painter=new DimensionPainter();painter.setPaint(paint);
        var memory=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long callsBefore=LineStrokeAccess.completedCalls();
        long allocated=memory.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        dimension.setEventsInhibited(true);
        try {
            for(int i=0;i<4;i++) painter.drawLine(dimension,-240,-192+i*96,240,-128+i*96,.73f,false);
            painter.setUndo(true);
            for(int i=0;i<4;i++) painter.drawLine(dimension,240,-128+i*96,-240,-192+i*96,.73f,false);
        } finally {dimension.setEventsInhibited(false);}
        long nanos=System.nanoTime()-start,bytes=memory.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
        long calls=LineStrokeAccess.completedCalls()-callsBefore;
        if(wholeStroke && (calls<1 || calls>8*15))throw new AssertionError("Expected at most one JNI per tile per line");
        long hash=1;
        for(int ty=-2;ty<2;ty++)for(int tx=-2;tx<2;tx++){
            Tile tile=dimension.getTile(tx,ty);if(tile==null)continue;
            hash=hash*31+(tile.hasLayer(Resources.INSTANCE)?1:0);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)hash=hash*31+tile.getLayerValue(Resources.INSTANCE,x,y);
        }
        checksum=hash;return new Result(nanos,bytes,hash,calls);
    }
    public static void main(String[] args) {
        int warmups=Integer.getInteger("welt.benchmark.strokeWarmups",20);
        if(warmups<5 || warmups>100)throw new IllegalArgumentException("Warmups must be 5..100");
        int radius=Integer.getInteger("welt.benchmark.strokeRadius",16);if(radius<0 || radius>127)throw new IllegalArgumentException("Radius must be 0..127");
        boolean fixedPrevious=args.length>0&&args[0].equals("previous");
        boolean previous=args.length>0&&args[0].equals("compare-existing");
        boolean paired=previous||args.length>0&&args[0].equals("compare"),nativeMode=args.length>0&&args[0].equals("rust");
        long tileCalls=0;
        double[] jt=new double[9],rt=new double[9],ratios=new double[9];long[] ja=new long[9],ra=new long[9];
        for(int trial=-warmups;trial<9;trial++){
            if(paired){
                Result j=null,r=null;
                for(int pass=0;pass<2;pass++){if(((trial+pass)&1)==0)j=run(previous,false);else r=run(true,true);}
                tileCalls=r.calls;
                if(j.hash!=r.hash)throw new AssertionError("Whole stroke output differs");
                if(trial>=0){jt[trial]=j.nanos/1e6;rt[trial]=r.nanos/1e6;ratios[trial]=(double)j.nanos/r.nanos;ja[trial]=j.allocated;ra[trial]=r.allocated;}
            }else{Result r=run(nativeMode||fixedPrevious,nativeMode);tileCalls=r.calls;if(trial>=0){jt[trial]=r.nanos/1e6;ja[trial]=r.allocated;}}
        }
        Arrays.sort(jt);Arrays.sort(rt);Arrays.sort(ratios);Arrays.sort(ja);Arrays.sort(ra);
        if(paired)System.out.printf(Locale.ROOT,"lineStroke reference=%s lines=8 referenceMs=%.3f rustMs=%.3f pairedRatio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d checksum=%d tileCalls=%d%n",previous?"previous-native":"java",jt[4],rt[4],ratios[4],ratios[0],ratios[8],ja[4],ra[4],checksum,tileCalls);
        else System.out.printf(Locale.ROOT,"lineStroke mode=%s lines=8 medianMs=%.3f allocated=%d checksum=%d%n",nativeMode?"rust":fixedPrevious?"previous-native":"java",jt[4],ja[4],checksum);
    }
}
