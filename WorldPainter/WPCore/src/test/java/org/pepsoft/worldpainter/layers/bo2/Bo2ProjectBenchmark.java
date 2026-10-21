package org.pepsoft.worldpainter.layers.bo2;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.*;

/** Complete compressed library reopening; fixture preparation and validation are outside timing. */
public final class Bo2ProjectBenchmark {
    private static volatile Bo2Object[] retained;
    private static volatile long checksum;
    private record Result(long nanos,long allocated,Bo2Object[] library) { }
    private static Result run(byte[] project,boolean compact,int side)throws Exception {
        System.setProperty("wp.native.gen",Boolean.toString(compact));
        System.setProperty("welt.native.bo2Projects",Boolean.toString(compact));
        long calls=Bo2CompactBlocks.completedObjects();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        Bo2Object[] objects;
        try(var in=new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(project)))){objects=(Bo2Object[])in.readObject();}
        if ("access".equals(System.getProperty("welt.benchmark.bo2ProjectOperation"))) {
            allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId());start=System.nanoTime();
            checksum=hash(objects,side);
        }
        long nanos=System.nanoTime()-start;
        long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
        if(Bo2CompactBlocks.completedObjects()-calls!=(compact?objects.length:0))throw new AssertionError("Every supported project object must use one snapshot transaction");
        return new Result(nanos,bytes,objects);
    }
    private static long hash(Bo2Object[] objects,int side) {
        long[] value={1,0,0};
        for(Bo2Object object:objects) {
            for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
                boolean present=object.getMask(x,y,z);value[0]=31*value[0]+(present?object.getMaterial(x,y,z).hashCode():0);
            }
            value[1]=0;value[2]=0;
            // HashMap deserialization does not promise the pre-save visitor order.
            object.visitBlocks((source,x,y,z,material)->{value[1]+=((long)x*193+(long)y*79+(long)z*31)*material.hashCode();value[2]++;return true;});
            value[0]=31*value[0]+value[1];value[0]=31*value[0]+value[2];
        }
        return value[0];
    }
    public static void main(String[] args)throws Exception {
        int side=Integer.getInteger("welt.benchmark.bo2ProjectSide",24),count=Integer.getInteger("welt.benchmark.bo2ProjectObjects",4);
        int warmups=Integer.getInteger("welt.benchmark.bo2ProjectWarmups",20);
        if(side<2||side>64||count<1||count>16||warmups<5||warmups>100)throw new IllegalArgumentException("Invalid library dimensions");
        boolean sparse=Boolean.getBoolean("welt.benchmark.bo2ProjectSparse"),compare=args.length>0&&args[0].equals("compare"),compact=args.length>0&&args[0].equals("rust");
        if(Boolean.getBoolean("welt.benchmark.bo2ProjectPreload")) {
            System.setProperty("wp.native.gen","true");System.setProperty("welt.native.bo2Projects","true");
            if(!org.pepsoft.worldpainter.nativeapi.NativeSlices.isBo2ProjectSnapshotAvailable())throw new AssertionError("Release preload failed");
        }
        System.setProperty("wp.native.gen","false");byte[] source=Bo2LoadBenchmark.fixture(side,sparse);
        Bo2Object[] original=new Bo2Object[count];for(int i=0;i<count;i++)original[i]=Bo2Object.load("Project",new ByteArrayInputStream(source));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(new GZIPOutputStream(bytes))){out.writeObject(original);}
        byte[] project=bytes.toByteArray();long expected=hash(original,side);original=null;
        double[] javaTimes=new double[9],rustTimes=new double[9],ratios=new double[9];long[] javaAlloc=new long[9],rustAlloc=new long[9];
        for(int trial=-warmups;trial<9;trial++) {
            Result j=null,r=null;
            for(int pass=0;pass<(compare?2:1);pass++) {
                boolean mode=compare?((trial+pass)&1)!=0:compact;Result result=run(project,mode,side);
                if(hash(result.library,side)!=expected)throw new AssertionError("Materials/masks/visited coordinates differ");retained=result.library;checksum=expected;
                if(mode)r=result;else j=result;
                if(trial>=0){if(mode){rustTimes[trial]=result.nanos/1e6;rustAlloc[trial]=result.allocated;}else{javaTimes[trial]=result.nanos/1e6;javaAlloc[trial]=result.allocated;}}
            }
            if(compare&&trial>=0)ratios[trial]=(double)j.nanos/r.nanos;
        }
        System.out.println("bo2ProjectOperation="+System.getProperty("welt.benchmark.bo2ProjectOperation","reload"));
        Arrays.sort(javaTimes);Arrays.sort(rustTimes);Arrays.sort(ratios);Arrays.sort(javaAlloc);Arrays.sort(rustAlloc);
        if(compare)System.out.printf(Locale.ROOT,"bo2Project paired side=%d objects=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d%n",side,count,javaTimes[4],rustTimes[4],ratios[4],ratios[0],ratios[8],javaAlloc[4],rustAlloc[4]);
        else System.out.printf(Locale.ROOT,"bo2Project mode=%s side=%d objects=%d medianMs=%.3f allocated=%d checksum=%d%n",compact?"rust":"java",side,count,compact?rustTimes[4]:javaTimes[4],compact?rustAlloc[4]:javaAlloc[4],checksum);
        System.out.println("bo2ProjectNativeCalls="+Bo2CompactBlocks.completedObjects());
        for(int i=0;i<3;i++){System.gc();Thread.sleep(100);}
        System.out.printf(Locale.ROOT,"bo2ProjectMemory heapBytes=%d heldObjects=%d compressedBytes=%d%n",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),retained.length,project.length);
    }
}
