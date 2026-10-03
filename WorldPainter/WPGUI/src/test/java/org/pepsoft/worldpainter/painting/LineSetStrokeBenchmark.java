package org.pepsoft.worldpainter.painting;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete terrain/bit-layer line operation with exact Math.random stream checks. */
public final class LineSetStrokeBenchmark {
    static final Layer CHUNK=new Layer("welt.test.stroke.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) { };
    static String mode(){return System.getProperty("welt.benchmark.strokePaint","terrain");}
    static boolean dither(){return Boolean.getBoolean("welt.benchmark.strokeDither");}
    static Random mathRandom() throws Exception {
        // Test-only access to the existing stream; production keeps ordinary Math.random calls.
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)field.get(null);
        var holder=Class.forName("java.lang.Math$RandomNumberGeneratorHolder");
        var stream=holder.getDeclaredField("randomNumberGenerator");
        return (Random)unsafe.getObject(unsafe.staticFieldBase(stream),unsafe.staticFieldOffset(stream));
    }
    static Dimension fixture(){
        Dimension d=NibbleLayerPaintParityTest.fixture();byte[] values=new byte[16384];
        for(int i=0;i<values.length;i++)values[i]=(byte)((i%31==0)?1:0);
        for(Tile tile:d.getTiles()){
            tile.inhibitEvents();tile.initializeLayerValues(Frost.INSTANCE,values,0);tile.initializeLayerValues(CHUNK,values,0);tile.releaseEvents();
        }
        return d;
    }
    static Paint paint(boolean reverse){
        Paint paint=switch(mode()){
            case "terrain" -> new TerrainPaint(reverse?Terrain.CUSTOM_3:Terrain.SAND);
            case "bit" -> new BitLayerPaint(Frost.INSTANCE);
            case "chunk-bit" -> new BitLayerPaint(CHUNK);
            default -> throw new IllegalArgumentException("Unknown stroke paint");
        };
        SymmetricBrush brush=SymmetricBrush.LINEAR_CIRCLE.clone();brush.setRadius(Integer.getInteger("welt.benchmark.strokeRadius",16));brush.setLevel(.63f);
        paint.setBrush(brush);paint.setDither(dither());return paint;
    }
    private record Result(long nanos,long allocated,long hash,long nextRandom,long calls){ }
    private static Result run(boolean nativeMode,boolean whole) throws Exception {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));System.setProperty("welt.native.lineSetStroke",Boolean.toString(whole));
        Dimension d=fixture();DimensionPainter painter=new DimensionPainter();Paint first=paint(false),second=paint(true);
        Random random=mathRandom();random.setSeed(7331);
        long callsBefore=LineStrokeAccess.completedCalls();
        var memory=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long allocated=memory.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        d.setEventsInhibited(true);
        try{
            painter.setPaint(first);for(int i=0;i<4;i++)painter.drawLine(d,-240,-192+i*96,240,-128+i*96,.91f,false);
            painter.setPaint(second);painter.setUndo(!mode().equals("terrain"));
            for(int i=0;i<4;i++)painter.drawLine(d,240,-128+i*96,-240,-192+i*96,.91f,false);
        }finally{d.setEventsInhibited(false);}
        long nanos=System.nanoTime()-start,bytes=memory.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
        long calls=LineStrokeAccess.completedCalls()-callsBefore,next=random.nextLong(),hash=1;
        for(int ty=-2;ty<2;ty++)for(int tx=-2;tx<2;tx++){
            Tile tile=d.getTile(tx,ty);if(tile==null)continue;
            hash=hash*31+(tile.hasLayer(Frost.INSTANCE)?1:0);hash=hash*31+(tile.hasLayer(CHUNK)?1:0);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){
                hash=hash*31+tile.getTerrain(x,y).ordinal();hash=hash*31+(tile.getBitLayerValue(Frost.INSTANCE,x,y)?1:0);
                hash=hash*31+(tile.getBitLayerValue(CHUNK,x,y)?1:0);
            }
        }
        return new Result(nanos,bytes,hash,next,calls);
    }
    public static void main(String[] args) throws Exception {
        String selected=args.length==0?"java":args[0];boolean paired=selected.startsWith("compare"),previous=selected.equals("compare-existing");
        double[] reference=new double[9],nativeTimes=new double[9],ratios=new double[9];long[] ja=new long[9],ra=new long[9];Result last=null;
        for(int trial=-20;trial<9;trial++){
            if(paired){Result j=null,r=null;for(int pass=0;pass<2;pass++){if(((trial+pass)&1)==0)j=run(previous,false);else r=run(true,true);}
                if(j.hash!=r.hash||j.nextRandom!=r.nextRandom)throw new AssertionError("Complete stroke or RNG mismatch");
                if(r.calls<1||r.calls>120)throw new AssertionError("One JNI per destination tile required");last=r;
                if(trial>=0){reference[trial]=j.nanos/1e6;nativeTimes[trial]=r.nanos/1e6;ratios[trial]=(double)j.nanos/r.nanos;ja[trial]=j.allocated;ra[trial]=r.allocated;}
            }else{last=run(!selected.equals("java"),selected.equals("rust"));if(trial>=0){reference[trial]=last.nanos/1e6;ja[trial]=last.allocated;}}
        }
        Arrays.sort(reference);Arrays.sort(nativeTimes);Arrays.sort(ratios);Arrays.sort(ja);Arrays.sort(ra);
        if(paired)System.out.printf(Locale.ROOT,"lineSet paint=%s dither=%s reference=%s lines=8 referenceMs=%.3f rustMs=%.3f pairedRatio=%.3f range=%.3f..%.3f referenceAllocated=%d rustAllocated=%d hash=%d nextRandom=%d tileCalls=%d%n",mode(),dither(),previous?"previous-native":"java",reference[4],nativeTimes[4],ratios[4],ratios[0],ratios[8],ja[4],ra[4],last.hash,last.nextRandom,last.calls);
        else System.out.printf(Locale.ROOT,"lineSet paint=%s dither=%s mode=%s lines=8 medianMs=%.3f allocated=%d hash=%d nextRandom=%d tileCalls=%d%n",mode(),dither(),selected,reference[4],ja[4],last.hash,last.nextRandom,last.calls);
    }
}
