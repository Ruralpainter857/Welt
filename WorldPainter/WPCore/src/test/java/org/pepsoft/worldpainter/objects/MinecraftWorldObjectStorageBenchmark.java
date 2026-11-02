package org.pepsoft.worldpainter.objects;

import java.io.ObjectStreamClass;
import java.lang.management.ManagementFactory;
import java.util.IdentityHashMap;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.Box;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete temporary-world construction and population, with validation outside timing. */
public final class MinecraftWorldObjectStorageBenchmark {
    private static volatile MinecraftWorldObject retained;
    private static MinecraftWorldObject create() {
        MinecraftWorldObject world=new MinecraftWorldObject("Storage preview",new Box(-8,136,-8,136,0,128),128,63);
        for(int x=-8;x<136;x++)for(int y=-8;y<136;y++) {
            int top=36+((x*3+y*7)&63);
            for(int z=0;z<=top;z++)world.setMaterialAt(x,y,z,z==top?Material.GRASS_BLOCK:z>=top-3?Material.DIRT:Material.STONE);
            for(int z=top+1;z<=63;z++)world.setMaterialAt(x,y,z,Material.WATER);
        }
        return world;
    }
    private static long hash(MinecraftWorldObject world) {
        IdentityHashMap<Material,Integer> codes=new IdentityHashMap<>();long hash=1;
        for(int x=0;x<144;x++)for(int y=0;y<144;y++)for(int z=0;z<128;z++) {
            Material material=world.getMaterial(x,y,z);Integer code=codes.get(material);
            if(code==null){code=material.toString().hashCode();codes.put(material,code);}
            hash=31*hash+code;
        }
        return hash;
    }
    public static void main(String[] args)throws Exception {
        System.setProperty(Native.GEN_KEY,"false");
        System.out.println("uid="+ObjectStreamClass.lookup(MinecraftWorldObject.class).getSerialVersionUID());
        boolean solo=Boolean.getBoolean("welt.benchmark.worldStorageSolo");boolean selected=Boolean.getBoolean("welt.benchmark.worldStorageCompact");
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        long expected=0;
        for(int sample=-8;sample<9;sample++)for(int pass=0;pass<2;pass++){
            boolean compact=((sample+pass)&1)!=0;if(solo&&compact!=selected)continue;
            System.setProperty("welt.compact.previewWorld",Boolean.toString(compact));
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
            MinecraftWorldObject world=create();long nanos=System.nanoTime()-start;
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            long checksum=hash(world);if(expected==0)expected=checksum;else if(expected!=checksum)throw new AssertionError("World material mismatch");
            retained=world;if(sample>=0)System.out.println("sample="+sample+" compact="+compact+" nanos="+nanos+" allocated="+allocated);
        }
        for(int i=0;i<3;i++){System.gc();Thread.sleep(100);}
        long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p->p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.println("checksum="+expected+" heap="+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()+" direct="+direct);
    }
}
