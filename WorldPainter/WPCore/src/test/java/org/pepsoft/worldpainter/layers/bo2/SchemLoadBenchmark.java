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
    public static byte[] fixture(int side,int version) throws IOException {
        Map<String,Tag> palette=new LinkedHashMap<>();
        String[] names={"minecraft:air","minecraft:stone","minecraft:oak_leaves[persistent=true]","welt:custom[facing=north]"};
        for(int i=0;i<names.length;i++)palette.put(names[i],new IntTag(names[i],i));
        byte[] indices=new byte[side*side*side];
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++)indices[x+y*side+z*side*side]=(byte)((x*17+y*7+z*3)&3);
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
            int expected=(x*17+y*7+z*3)&3;
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
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();
        double[] times=new double[9];long[] allocated=new long[9];
        var field=Schem.class.getDeclaredField("blocks");field.setAccessible(true);
        long storage=0;
        for(int trial=-warmups;trial<9;trial++){
            Schem[] loaded=new Schem[objects];
            long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
            for(int i=0;i<objects;i++)loaded[i]=Schem.load(new ByteArrayInputStream(bytes),"Benchmark");
            long elapsed=System.nanoTime()-start,allocation=bean.getThreadAllocatedBytes(thread)-before;
            long hash=1;storage=0;
            for(Schem object:loaded){hash=hash*31+validate(object,side);storage+=((int[])field.get(object)).length*4L;}
            checksum=hash;
            if(trial>=0){times[trial]=elapsed/1e6;allocated[trial]=allocation;}
        }
        Arrays.sort(times);Arrays.sort(allocated);
        System.out.printf(Locale.ROOT,"schemLibrary side=%d objects=%d medianMs=%.3f callerAllocatedBytes=%d retainedIndexBytes=%d checksum=%d compressedSourceBytes=%d%n",side,objects,times[4],allocated[4],storage,checksum,bytes.length);
    }
}
