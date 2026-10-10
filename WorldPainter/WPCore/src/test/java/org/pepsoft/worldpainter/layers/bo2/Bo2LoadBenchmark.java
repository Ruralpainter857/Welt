package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.minecraft.Material;
import javax.vecmath.Point3i;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Complete BO2 library loading and editor access; fixture validation is outside timing. */
public final class Bo2LoadBenchmark {
    private static volatile Bo2Object[] heldLibrary;
    private static volatile long checksum;
    private static final int[] IDS = {1,17,20,35}, DATA = {0,0,0,4};
    private static boolean present(int x,int y,int z,int side,boolean sparse) {
        return !sparse || ((x*13+y*11+z*5)&15)==0 || x==0&&y==0&&z==0 || x==side-1&&y==side-1&&z==side-1;
    }
    static byte[] fixture(int side,boolean sparse) {
        StringBuilder source=new StringBuilder("Ignored header\n[META]\nrandomRotation=false\nneedsFoundation=false\nspawnWater=true\ncustom = preserved\n[DATA]\n");
        String[] specs={"1","17.0","20.0#3@7","35.4"};
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
            if(present(x,y,z,side,sparse))source.append(x-side/2).append(',').append(y-side/2).append(',').append(z).append(':').append(specs[(x+y+z)&3]).append('\n');
        }
        return source.toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static void validate(Bo2Object object,int side,boolean sparse) {
        if(!object.getDimensions().equals(new Point3i(side,side,side)))throw new AssertionError("Dimensions differ");
        if(!object.getOffset().equals(new Point3i(-side/2,-side/2,0)))throw new AssertionError("Offset differs");
        if(object.getAttribute(Bo2Object.ATTRIBUTE_RANDOM_ROTATION)||object.getAttribute(Bo2Object.ATTRIBUTE_NEEDS_FOUNDATION)||!object.getAttribute(Bo2Object.ATTRIBUTE_SPAWN_IN_WATER))throw new AssertionError("Metadata differs");
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
            boolean exists=present(x,y,z,side,sparse);
            if(object.getMask(x,y,z)!=exists)throw new AssertionError("Mask differs");
            if(exists){int state=(x+y+z)&3;if(object.getMaterial(x,y,z)!=Material.get(IDS[state],DATA[state]))throw new AssertionError("Material differs");}
        }
    }
    private static long visit(Bo2Object object,int side) {
        long hash=1;
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
            boolean present=object.getMask(x,y,z);
            hash=hash*31+(present?object.getMaterial(x,y,z).hashCode():0);
        }
        return hash;
    }
    public static void main(String[] args)throws Exception {
        int side=Integer.getInteger("welt.benchmark.bo2Side",24),objects=Integer.getInteger("welt.benchmark.bo2Objects",4),warmups=Integer.getInteger("welt.benchmark.bo2Warmups",20);
        boolean sparse=Boolean.getBoolean("welt.benchmark.bo2Sparse"),access="access".equals(System.getProperty("welt.benchmark.bo2Operation","load"));
        if(side<2||side>64||objects<1||objects>16||warmups<5||warmups>100)throw new IllegalArgumentException("Invalid fixture dimensions");
        boolean compare=args.length>0&&args[0].equals("compare"),rust=args.length>0&&args[0].equals("rust");
        byte[] bytes=fixture(side,sparse);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();double[] times=new double[9];long[] allocations=new long[9];
        double[] javaTimes=new double[9],rustTimes=new double[9],ratios=new double[9];long[] javaAlloc=new long[9],rustAlloc=new long[9];
        long callsAtStart=Bo2NativeParser.completedObjects();
        for(int trial=-warmups;trial<9;trial++) {
            for(int pass=0;pass<(compare?2:1);pass++) {
                boolean nativeMode=compare?((trial+pass)&1)!=0:rust;
                System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.bo2",Boolean.toString(nativeMode));
                long callsBefore=Bo2NativeParser.completedObjects();Bo2Object[] loaded=new Bo2Object[objects];
                if(access)for(int i=0;i<objects;i++)loaded[i]=Bo2Object.load("Benchmark",new ByteArrayInputStream(bytes));
                long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime(),hash=1;
                if(access)for(Bo2Object object:loaded)hash=hash*31+visit(object,side);
                else for(int i=0;i<objects;i++)loaded[i]=Bo2Object.load("Benchmark",new ByteArrayInputStream(bytes));
                long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(thread)-before;
                if(Bo2NativeParser.completedObjects()-callsBefore!=(nativeMode?objects:0))throw new AssertionError("Unexpected native coverage");
                for(Bo2Object object:loaded)validate(object,side,sparse);
                heldLibrary=loaded;checksum=hash;
                if(trial>=0){times[trial]=elapsed/1e6;allocations[trial]=allocated;if(nativeMode){rustTimes[trial]=elapsed/1e6;rustAlloc[trial]=allocated;}else{javaTimes[trial]=elapsed/1e6;javaAlloc[trial]=allocated;}}
            }
            if(compare&&trial>=0)ratios[trial]=javaTimes[trial]/rustTimes[trial];
        }
        System.gc();Thread.sleep(150);Arrays.sort(times);Arrays.sort(allocations);
        if(compare){
            Arrays.sort(javaTimes);Arrays.sort(rustTimes);Arrays.sort(ratios);Arrays.sort(javaAlloc);Arrays.sort(rustAlloc);
            System.out.printf(Locale.ROOT,"bo2Paired operation=%s sparse=%s javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d nativeCalls=%d postGcHeapBytes=%d%n",
                    access?"access":"load",sparse,javaTimes[4],rustTimes[4],ratios[4],ratios[0],ratios[8],javaAlloc[4],rustAlloc[4],Bo2NativeParser.completedObjects()-callsAtStart,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            return;
        }
        System.out.printf(Locale.ROOT,"bo2Library operation=%s sparse=%s side=%d objects=%d medianMs=%.3f callerAllocatedBytes=%d postGcHeapBytes=%d sourceBytes=%d checksum=%d heldObjects=%d%n",
                access?"access":"load",sparse,side,objects,times[4],allocations[4],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),bytes.length,checksum,heldLibrary.length);
    }
}