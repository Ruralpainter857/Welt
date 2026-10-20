package org.pepsoft.worldpainter.objects;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.layers.bo2.Schem;
import org.pepsoft.worldpainter.layers.bo2.Schematic;
import org.jnbt.*;
import org.pepsoft.worldpainter.layers.bo2.SchemLoadBenchmark;
import javax.vecmath.Point3i;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.*;

/** Complete selection snapshots, editor access and project saves; validation is outside timing. */
public final class GenericObjectBenchmark {
    private static volatile GenericObject[] heldLibrary;
    private static volatile WPObject heldSource;
    private static volatile long checksum;
    private static WPObject fixture(String kind, int width, int length, int height) throws Exception {
        WPObject object;
        if (kind.equals("legacy")) {
            byte[] blocks = new byte[width * length * height], data = new byte[blocks.length];
            int[] ids = {0, 1, 53, 54, 17, 85};
            for (int i = 0; i < blocks.length; i++) { blocks[i] = (byte) ids[(i * 17 + i / width) % ids.length]; data[i] = (byte) ((i / 7) & 3); }
            Map<String, Tag> values = new HashMap<>();
            values.put("Materials", new StringTag("Materials", "Alpha"));
            values.put("Width", new ShortTag("Width", (short) width));
            values.put("Length", new ShortTag("Length", (short) length));
            values.put("Height", new ShortTag("Height", (short) height));
            values.put("Blocks", new ByteArrayTag("Blocks", blocks));
            values.put("Data", new ByteArrayTag("Data", data));
            object = new Schematic("Source", new CompoundTag("Schematic", values), null);
        } else if (kind.equals("schem")) {
            int side = Integer.getInteger("welt.benchmark.genericSchemSide",64);
            object=Schem.load(new ByteArrayInputStream(SchemLoadBenchmark.fixture(side,3)),"Source");
        } else {
            Material[] values = new Material[width*length*height];
            Material[] palette = {null,Material.AIR,Material.STONE,Material.get("welt:custom","facing","north")};
            for (int z=0;z<height;z++) for (int y=0;y<length;y++) for (int x=0;x<width;x++) values[x+y*width+z*width*length]=palette[(x*17+y*7+z*3)&3];
            object=new GenericObject("Source",width,length,height,values,null,null,Map.of(WPObject.ATTRIBUTE_OFFSET.key,new Point3i(-1,-2,-3)));
        }
        var platform=org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL_1_15;
        return switch(System.getProperty("welt.benchmark.genericTransform","none")) {
            case "none" -> object;
            case "rotate1" -> new RotatedObject(object,1,platform);
            case "mirrorX" -> new MirroredObject(object,false,platform);
            case "combined" -> new RotatedObject(new MirroredObject(new RotatedObject(object,3,platform),true,platform),1,platform);
            default -> throw new IllegalArgumentException("Unknown transform");
        };
    }
    private static void validate(WPObject source, GenericObject copy) {
        if (!source.getName().equals(copy.getName()) || !source.getDimensions().equals(copy.getDimensions()) || !Objects.equals(source.getAttributes(),copy.getAttributes())) throw new AssertionError("Metadata differs");
        Point3i d=source.getDimensions();
        for (int z=0;z<d.z;z++) for (int y=0;y<d.y;y++) for (int x=0;x<d.x;x++) {
            boolean mask=source.getMask(x,y,z);
            Material expected=mask?source.getMaterial(x,y,z):null;
            if (copy.getMaterial(x,y,z)!=expected || copy.getMask(x,y,z)!=(expected!=null)) throw new AssertionError("Snapshot block differs");
        }
    }
    private static long visit(GenericObject object) {
        Point3i d=object.getDimensions();long hash=1;
        for (int z=0;z<d.z;z++) for (int x=0;x<d.x;x++) for (int y=0;y<d.y;y++) hash=hash*31+(object.getMask(x,y,z)?object.getMaterial(x,y,z).hashCode():0);
        return hash;
    }
    private static long materials(WPObject source, boolean nativeMode) {
        SortedSet<Material> result = new TreeSet<>(Comparator.comparing(Material::toString));
        java.util.function.Consumer<Material> collect = material -> { if(material != Material.AIR) result.add(material); };
        if(nativeMode) CompactObjectMaterials.forEach(source, collect);
        else source.visitBlocks((object,x,y,z,material)->{collect.accept(material);return true;});
        long hash=1; for(Material material:result) hash=31*hash+material.hashCode(); return hash;
    }
    private static void mode(boolean nativeMode) {
        System.setProperty("wp.native.gen",Boolean.toString(nativeMode));
        System.setProperty("welt.native.objectSnapshots",Boolean.toString(nativeMode));
        System.setProperty("welt.native.objectTransforms",Boolean.toString(nativeMode));
    }
    private static void compare() throws Exception {
        String kind=System.getProperty("welt.benchmark.genericSource","generic");
        String operation=System.getProperty("welt.benchmark.genericOperation","copy");
        int objects=Integer.getInteger("welt.benchmark.genericObjects",8),warmups=Integer.getInteger("welt.benchmark.genericWarmups",20);
        WPObject[] source=new WPObject[2];
        for(int m=0;m<2;m++){mode(m==1);source[m]=fixture(kind,Integer.getInteger("welt.benchmark.genericWidth",96),Integer.getInteger("welt.benchmark.genericLength",64),Integer.getInteger("welt.benchmark.genericHeight",48));}
        long legacyBefore=operation.equals("materials")?CompactMaterialVolume.completedLegacyClassifications():CompactMaterialVolume.completedLegacySnapshots();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();double[][] times=new double[2][9];long[][] allocation=new long[2][9];
        for(int trial=-warmups;trial<9;trial++)for(int turn=0;turn<2;turn++) {
            int m=(trial&1)^turn;mode(m==1);GenericObject[] library=new GenericObject[objects];
            if(!operation.equals("copy") && !operation.equals("materials"))for(int i=0;i<objects;i++)library[i]=new GenericObject(source[m]);
            ByteArrayOutputStream saved=null;long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime(),hash=1;
            if(operation.equals("copy"))for(int i=0;i<objects;i++)library[i]=new GenericObject(source[m]);
            else if(operation.equals("materials"))for(int i=0;i<objects;i++)hash=31*hash+materials(source[m],m==1);
            else if(operation.equals("access"))for(GenericObject object:library)hash=31*hash+visit(object);
            else if(operation.equals("save")){saved=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(new GZIPOutputStream(saved))){out.writeObject(library);}hash=saved.size();}
            else throw new IllegalArgumentException("Unknown operation");
            long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(thread)-before;
            if(operation.equals("materials")){long expected=1;for(int i=0;i<objects;i++)expected=31*expected+materials(source[m],false);if(hash!=expected)throw new AssertionError("Material classification differs");}
            else for(GenericObject object:library)validate(source[m],object);
            if(saved!=null)try(ObjectInputStream in=new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(saved.toByteArray())))){for(GenericObject object:(GenericObject[])in.readObject())validate(source[m],object);}
            if(trial>=0){times[m][trial]=elapsed/1e6;allocation[m][trial]=bytes;}
            checksum=hash;heldLibrary=library;
        }
        double[] ratios=new double[9];for(int i=0;i<9;i++)ratios[i]=times[0][i]/times[1][i];
        Arrays.sort(ratios);for(int m=0;m<2;m++){Arrays.sort(times[m]);Arrays.sort(allocation[m]);}
        if (kind.equals("legacy")) {
            long calls=(operation.equals("materials")?CompactMaterialVolume.completedLegacyClassifications():CompactMaterialVolume.completedLegacySnapshots())-legacyBefore;
            if (calls != (long) objects*(warmups+9)) throw new AssertionError("Native snapshot transaction count differs: " + calls);
            System.out.println("legacyNativeTransactions="+calls);
        }
        System.out.printf(Locale.ROOT,"genericComparison operation=%s source=%s javaMs=%.6f compactMs=%.6f pairedRatio=%.3f range=%.3f..%.3f callerAllocatedBytes=%d/%d checksum=%d%n",operation,kind,times[0][4],times[1][4],ratios[4],ratios[0],ratios[8],allocation[0][4],allocation[1][4],checksum);
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("compare")){compare();return;}
        String operation=System.getProperty("welt.benchmark.genericOperation","copy"),kind=System.getProperty("welt.benchmark.genericSource","generic");
        int width=Integer.getInteger("welt.benchmark.genericWidth",96),length=Integer.getInteger("welt.benchmark.genericLength",64),height=Integer.getInteger("welt.benchmark.genericHeight",48);
        int objects=Integer.getInteger("welt.benchmark.genericObjects",8),warmups=Integer.getInteger("welt.benchmark.genericWarmups",20);
        if (!Set.of("generic","schem","legacy").contains(kind)||!Set.of("copy","access","save").contains(operation)||width<1||length<1||height<1||(long)width*length*height>4*1024*1024||objects<1||objects>16||warmups<5||warmups>100) throw new IllegalArgumentException("Invalid snapshot parameters");
        WPObject source=fixture(kind,width,length,height);heldSource=source;
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();if(!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();double[] times=new double[9];long[] allocations=new long[9];
        for (int trial=-warmups;trial<9;trial++) {
            GenericObject[] loaded=new GenericObject[objects];
            if (!operation.equals("copy")) for(int i=0;i<objects;i++)loaded[i]=new GenericObject(source);
            ByteArrayOutputStream saved=null;long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime(),hash=1;
            if(operation.equals("copy"))for(int i=0;i<objects;i++)loaded[i]=new GenericObject(source);
            else if(operation.equals("access"))for(GenericObject object:loaded)hash=hash*31+visit(object);
            else {saved=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(new GZIPOutputStream(saved))){out.writeObject(loaded);}hash=saved.size();}
            long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(thread)-before;
            for(GenericObject object:loaded)validate(source,object);
            if(saved!=null)try(ObjectInputStream in=new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(saved.toByteArray())))){GenericObject[] restored=(GenericObject[])in.readObject();if(restored.length!=objects)throw new AssertionError("Project size differs");for(GenericObject object:restored)validate(source,object);}
            heldLibrary=loaded;checksum=hash;if(trial>=0){times[trial]=elapsed/1e6;allocations[trial]=allocated;}
        }
        for(int i=0;i<3;i++){System.gc();Thread.sleep(100);} Arrays.sort(times);Arrays.sort(allocations);
        long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(pool->pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.println("postGcDirectBytes="+direct);
        boolean compressedOops=Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("UseCompressedOops").getValue());
        Point3i d=source.getDimensions();long slots=(long)d.x*d.y*d.z*objects*(compressedOops?4:8);
        System.out.printf(Locale.ROOT,"genericLibrary operation=%s source=%s dimensions=%dx%dx%d objects=%d medianMs=%.3f callerAllocatedBytes=%d postGcHeapBytes=%d javaReferenceSlotBytes=%d compressedOops=%s checksum=%d%n",operation,kind,d.x,d.y,d.z,objects,times[4],allocations[4],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),slots,compressedOops,checksum);
    }
}
