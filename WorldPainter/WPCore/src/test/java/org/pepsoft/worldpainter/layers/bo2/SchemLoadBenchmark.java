package org.pepsoft.worldpainter.layers.bo2;
import org.jnbt.*;
import org.pepsoft.worldpainter.objects.WPObject;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Complete schematic library loading; block validation is outside timing. */
public final class SchemLoadBenchmark {
    private static volatile long checksum;
    private static volatile Schem[] heldLibrary;
    private static final boolean NOISE=Boolean.getBoolean("welt.benchmark.schemNoise");
    private static int index(int x,int y,int z){
        if(!NOISE)return (x*17+y*7+z*3)&3;
        int value=x*0x9e3779b9^y*0x85ebca6b^z*0xc2b2ae35;
        value^=value>>>16;value*=0x7feb352d;value^=value>>>15;return value&3;
    }
    public static byte[] fixture(int side,int version) throws IOException {
        Map<String,Tag> palette=new LinkedHashMap<>();
        String[] names={"minecraft:air","minecraft:stone","minecraft:oak_leaves[persistent=true]","welt:custom[facing=north]"};
        for(int i=0;i<names.length;i++)palette.put(names[i],new IntTag(names[i],i));
        byte[] indices=new byte[side*side*side];
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++)indices[x+y*side+z*side*side]=(byte)index(x,y,z);
        Map<String,Tag> tags=new LinkedHashMap<>();
        tags.put("Version",new IntTag("Version",version));
        tags.put("Width",new ShortTag("Width",(short)side));
        tags.put("Height",new ShortTag("Height",(short)side));
        tags.put("Length",new ShortTag("Length",(short)side));
        if(version==3) {
            Map<String,Tag> blocks=new LinkedHashMap<>();
            blocks.put("Palette",new CompoundTag("Palette",palette));
            blocks.put("Data",new ByteArrayTag("Data",indices));
            tags.put("Blocks",new CompoundTag("Blocks",blocks));
        }else{
            tags.put("Palette",new CompoundTag("Palette",palette));
            tags.put("BlockData",new ByteArrayTag("BlockData",indices));
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(NBTOutputStream out=new NBTOutputStream(new GZIPOutputStream(bytes))){out.writeTag(new CompoundTag("Schematic",tags));}
        return bytes.toByteArray();
    }
    private static long validate(Schem object,int side) {
        if(!object.getDimensions().equals(new javax.vecmath.Point3i(side,side,side)))throw new AssertionError("Dimensions differ");
        String[] names={"minecraft:air","minecraft:stone","minecraft:oak_leaves","welt:custom"};
        long hash=1;
        for(int z=0;z<side;z++)for(int x=0;x<side;x++)for(int y=0;y<side;y++){
            int expected=index(x,y,z);
            var material=object.getMaterial(x,y,z);
            if(!material.name.equals(names[expected])||object.getMask(x,y,z)!=(expected!=0))throw new AssertionError("Block or mask differs");
            hash=hash*31+material.hashCode();
        }
        if(!object.getOffset().equals(object.guestimateOffset()))throw new AssertionError("Offset differs");
        if(!object.getAttribute(WPObject.ATTRIBUTE_MANAGE_WATERLOGGED))throw new AssertionError("Waterlogged inference differs");
        return hash;
    }
    public static void main(String[] args) throws Exception {
        int side=Integer.getInteger("welt.benchmark.schemSide",64),objects=Integer.getInteger("welt.benchmark.schemObjects",8);
        int warmups=Integer.getInteger("welt.benchmark.schemWarmups",20);
        if(side<2||side>128||objects<1||objects>32||warmups<5||warmups>100)throw new IllegalArgumentException("Invalid fixture dimensions");
        byte[] bytes=fixture(side,3);
        boolean saving=System.getProperty("welt.benchmark.schemOperation","load").equals("save");
        System.out.println("schemFixture operation="+(saving?"save":"load")+" distribution="+(NOISE?"noise":"periodic"));
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();
        double[] times=new double[9];long[] allocated=new long[9];
        boolean compare=args.length>0&&args[0].equals("compare"),rust=args.length>0&&args[0].equals("rust");
        double[] jt=new double[9],rt=new double[9],ratios=new double[9];long[] ja=new long[9],ra=new long[9];
        long callsAtStart=SchemPackedAccess.completedObjects();
        long storage=0;
        for(int trial=-warmups;trial<9;trial++){
            for(int pass=0;pass<(compare?2:1);pass++){
                boolean nativeMode=compare?((trial+pass)&1)!=0:rust;
                System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.schem",Boolean.toString(nativeMode));
                long callsBefore=SchemPackedAccess.completedObjects();
                Schem[] loaded=new Schem[objects];
                if(saving)for(int i=0;i<objects;i++)loaded[i]=Schem.load(new ByteArrayInputStream(bytes),"Benchmark");
                long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                byte[] saved=null;
                if(!saving)for(int i=0;i<objects;i++)loaded[i]=Schem.load(new ByteArrayInputStream(bytes),"Benchmark");
                else{
                    var sink=new ByteArrayOutputStream();
                    try(var out=new ObjectOutputStream(new GZIPOutputStream(sink))){out.writeObject(loaded);}
                    saved=sink.toByteArray();
                }
                long elapsed=System.nanoTime()-start,allocation=bean.getThreadAllocatedBytes(thread)-before;
                long hash=1;storage=0;
                for(Schem object:loaded){hash=hash*31+validate(object,side);storage+=object.getBlockIndexStorageBytes();}
                checksum=hash;
                heldLibrary=loaded;
                long calls=SchemPackedAccess.completedObjects()-callsBefore;
                if(nativeMode&&calls!=objects||!nativeMode&&calls!=0)throw new AssertionError("Unexpected native coverage");
                if(saving){
                    System.setProperty("wp.native.gen","false");System.setProperty("welt.native.schem","false");
                    try(var in=new ObjectInputStream(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(saved)))){
                        Schem[] restored=(Schem[])in.readObject();if(restored.length!=objects)throw new AssertionError("Saved library size differs");
                        long restoredHash=1;for(Schem object:restored)restoredHash=restoredHash*31+validate(object,side);
                        if(restoredHash!=hash)throw new AssertionError("Saved library differs");
                    }
                }
                if(trial>=0){times[trial]=elapsed/1e6;allocated[trial]=allocation;}
                if(trial>=0){if(nativeMode){rt[trial]=elapsed/1e6;ra[trial]=allocation;}else{jt[trial]=elapsed/1e6;ja[trial]=allocation;}}
            }
            if(compare&&trial>=0)ratios[trial]=jt[trial]/rt[trial];
        }
        System.gc();Thread.sleep(150);
        long liveHeap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.printf(Locale.ROOT,"schemHeldLibrary objects=%d postGcHeapBytes=%d%n",heldLibrary.length,liveHeap);
        if(compare){
            Arrays.sort(jt);Arrays.sort(rt);Arrays.sort(ratios);Arrays.sort(ja);Arrays.sort(ra);
            System.out.printf(Locale.ROOT,"schemLibrary paired side=%d objects=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d javaIndexBytes=%d rustIndexBytes=%d nativeCalls=%d%n",
                    side,objects,jt[4],rt[4],ratios[4],ratios[0],ratios[8],ja[4],ra[4],(long)side*side*side*objects*4,(long)side*side*side*objects,SchemPackedAccess.completedObjects()-callsAtStart);
        }
        if(Boolean.getBoolean("welt.native.schemProfile")){
            long[] profile=SchemPackedAccess.profile();System.out.printf(Locale.ROOT,"schemProfile prepareMs=%.3f nativeMs=%.3f%n",profile[0]/1e6,profile[1]/1e6);
        }
        if(!compare){
        Arrays.sort(times);Arrays.sort(allocated);
        System.out.printf(Locale.ROOT,"schemLibrary side=%d objects=%d medianMs=%.3f callerAllocatedBytes=%d retainedIndexBytes=%d checksum=%d compressedSourceBytes=%d%n",side,objects,times[4],allocated[4],storage,checksum,bytes.length);
        }
    }
}
