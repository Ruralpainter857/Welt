package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.pepsoft.minecraft.Material;
import javax.vecmath.Point3i;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Complete legacy schematic loading, block access and compatible project saves. */
public final class LegacySchematicBenchmark {
    private static volatile Schematic[] heldLibrary;
    private static volatile long checksum;
    private static final int[] IDS = Boolean.getBoolean("welt.benchmark.legacyVanilla") ? new int[]{0,1,17,35} : new int[]{0,1,17,35,300};
    private static int state(int x, int y, int z, boolean noise) {
        if (!noise) return (x * 17 + y * 7 + z * 3) % IDS.length;
        int value = x * 0x9e3779b9 ^ y * 0x85ebca6b ^ z * 0xc2b2ae35;
        value ^= value >>> 16; value *= 0x7feb352d; value ^= value >>> 15;
        return (value & 0x7fffffff) % IDS.length;
    }
    static byte[] fixture(int width, int length, int height, boolean compressed, boolean noise) throws IOException {
        int volume = width * length * height;
        byte[] blocks = new byte[volume], data = new byte[volume], add = new byte[(volume + 1) / 2];
        for (int z = 0; z < height; z++) for (int y = 0; y < length; y++) for (int x = 0; x < width; x++) {
            int at = x + width * y + width * length * z, id = IDS[state(x,y,z,noise)];
            blocks[at] = (byte) id; data[at] = (byte) (id == 35 ? 4 : 0);
            add[at / 2] |= (byte) ((id >>> 8) << ((at & 1) * 4));
        }
        Map<String, Tag> values = new HashMap<>(Map.of("Materials", new StringTag("Materials", "Alpha"),
                "Width", new ShortTag("Width", (short) width), "Length", new ShortTag("Length", (short) length),
                "Height", new ShortTag("Height", (short) height), "Blocks", new ByteArrayTag("Blocks", blocks),
                "Data", new ByteArrayTag("Data", data), "AddBlocks", new ByteArrayTag("AddBlocks", add),
                "custom", new StringTag("custom", "preserved")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream out = new NBTOutputStream(compressed ? new GZIPOutputStream(bytes) : bytes)) {
            out.writeTag(new CompoundTag("Schematic", values));
        }
        return bytes.toByteArray();
    }
    private static void validate(Schematic object, int width, int length, int height, boolean noise) {
        if (!object.getDimensions().equals(new Point3i(width,length,height))) throw new AssertionError("Dimensions differ");
        int minX = width, maxX = -1, minY = length, maxY = -1;
        for (int y = 0; y < length; y++) for (int x = 0; x < width; x++) {
            if ((IDS[state(x,y,0,noise)] & 255) != 0) { minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y); }
        }
        if (!object.getOffset().equals(new Point3i(-(minX+maxX)/2,-(minY+maxY)/2,0))) throw new AssertionError("Offset differs");
        for (int z = 0; z < height; z++) for (int y = 0; y < length; y++) for (int x = 0; x < width; x++) {
            int id = IDS[state(x,y,z,noise)], data = id == 35 ? 4 : 0;
            if (object.getMask(x,y,z) != ((id & 255) != 0)) throw new AssertionError("Historical low-byte mask differs");
            if (object.getMaterial(x,y,z) != Material.get(id,data)) throw new AssertionError("Material differs");
        }
        if (!((StringTag)object.toNBT().getTag("custom")).getValue().equals("preserved")) throw new AssertionError("Metadata differs");
    }
    private static long visit(Schematic object, int width, int length, int height) {
        long hash = 1;
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) for (int y = 0; y < length; y++) {
            hash = hash * 31 + (object.getMask(x,y,z) ? object.getMaterial(x,y,z).hashCode() : 0);
        }
        return hash;
    }
    public static void main(String[] args) throws Exception {
        int width=Integer.getInteger("welt.benchmark.legacyWidth",64), length=Integer.getInteger("welt.benchmark.legacyLength",48), height=Integer.getInteger("welt.benchmark.legacyHeight",32);
        int objects=Integer.getInteger("welt.benchmark.legacyObjects",8), warmups=Integer.getInteger("welt.benchmark.legacyWarmups",20);
        boolean compressed=!Boolean.getBoolean("welt.benchmark.legacyRaw"), noise=Boolean.getBoolean("welt.benchmark.legacyNoise");
        String operation=System.getProperty("welt.benchmark.legacyOperation","load");
        if (width<2 || width>128 || length<2 || length>128 || height<2 || height>128 || objects<1 || objects>16 || warmups<5 || warmups>100 || !Set.of("load","access","save").contains(operation)) throw new IllegalArgumentException("Invalid fixture parameters");
        boolean compare=args.length>0&&args[0].equals("compare"), rust=args.length>0&&args[0].equals("rust");
        byte[] source=fixture(width,length,height,compressed,noise);
        boolean extended = false;
        for(int z=0;z<height;z++)for(int y=0;y<length;y++)for(int x=0;x<width;x++)if(IDS[state(x,y,z,noise)]>255)extended=true;
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId(); double[] times=new double[9]; long[] allocations=new long[9];
        double[] javaTimes=new double[9],rustTimes=new double[9],ratios=new double[9];
        long[] javaAlloc=new long[9],rustAlloc=new long[9];
        long callsAtStart=LegacySchematicAccess.completedAnalyses();
        for (int trial=-warmups; trial<9; trial++) {
          for(int pass=0;pass<(compare?2:1);pass++) {
            boolean nativeMode=compare?((trial+pass)&1)!=0:rust;
            System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.legacySchematic",Boolean.toString(nativeMode));
            long callsBefore=LegacySchematicAccess.completedAnalyses();
            Schematic[] loaded=new Schematic[objects];
            if (!operation.equals("load")) for (int i=0;i<objects;i++) loaded[i]=Schematic.load("Benchmark",new ByteArrayInputStream(source));
            ByteArrayOutputStream saved = null;
            long before=bean.getThreadAllocatedBytes(thread), start=System.nanoTime(), hash=1;
            if (operation.equals("load")) for (int i=0;i<objects;i++) loaded[i]=Schematic.load("Benchmark",new ByteArrayInputStream(source));
            else if (operation.equals("access")) for (Schematic object:loaded) hash=hash*31+visit(object,width,length,height);
            else {
                saved = new ByteArrayOutputStream();
                try (ObjectOutputStream out=new ObjectOutputStream(new GZIPOutputStream(saved))) { out.writeObject(loaded); }
                hash=saved.size();
            }
            long elapsed=System.nanoTime()-start, allocated=bean.getThreadAllocatedBytes(thread)-before;
            if(LegacySchematicAccess.completedAnalyses()-callsBefore!=(nativeMode&&extended?objects:0))throw new AssertionError("Unexpected native coverage");
            if (saved != null) {
                try (ObjectInputStream in = new ObjectInputStream(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(saved.toByteArray())))) {
                    Schematic[] reloaded = (Schematic[]) in.readObject();
                    if (reloaded.length != objects) throw new AssertionError("Project library size differs");
                    for (Schematic object : reloaded) validate(object,width,length,height,noise);
                }
            }
            for (Schematic object:loaded) validate(object,width,length,height,noise);
            heldLibrary=loaded;checksum=hash;
            if (trial>=0) {
                times[trial]=elapsed/1e6;allocations[trial]=allocated;
                if(nativeMode){rustTimes[trial]=elapsed/1e6;rustAlloc[trial]=allocated;}else{javaTimes[trial]=elapsed/1e6;javaAlloc[trial]=allocated;}
            }
          }
          if(compare&&trial>=0)ratios[trial]=javaTimes[trial]/rustTimes[trial];
        }
        System.gc();Thread.sleep(150);Arrays.sort(times);Arrays.sort(allocations);
        if(compare) {
            Arrays.sort(javaTimes);Arrays.sort(rustTimes);Arrays.sort(ratios);Arrays.sort(javaAlloc);Arrays.sort(rustAlloc);
            System.out.printf(Locale.ROOT,"legacyPaired operation=%s compressed=%s noise=%s objects=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d nativeAnalyses=%d postGcHeapBytes=%d%n",
                    operation,compressed,noise,objects,javaTimes[4],rustTimes[4],ratios[4],ratios[0],ratios[8],javaAlloc[4],rustAlloc[4],LegacySchematicAccess.completedAnalyses()-callsAtStart,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            return;
        }
        System.out.printf(Locale.ROOT,"legacyLibrary operation=%s compressed=%s noise=%s objects=%d dimensions=%dx%dx%d medianMs=%.3f callerAllocatedBytes=%d postGcHeapBytes=%d sourceBytes=%d checksum=%d%n",
                operation,compressed,noise,objects,width,length,height,times[4],allocations[4],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),source.length,checksum);
    }
}
