package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.IdentityHashMap;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.layers.LayerPreviewCreator;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.objects.MinecraftWorldObject;
import org.pepsoft.worldpainter.plugins.WPPluginManager;

/** Measures the actual layer-preview construction, including chunks and post-processing. */
public final class CompactLayerPreviewBenchmark {
    private static volatile MinecraftWorldObject retained;
    private static long checksum(MinecraftWorldObject world) {
        IdentityHashMap<Material,Integer> codes=new IdentityHashMap<>();long hash=1;
        var dimensions=world.getDimensions();
        for(int x=0;x<dimensions.x;x++)for(int y=0;y<dimensions.y;y++)for(int z=0;z<dimensions.z;z++) {
            Material material=world.getMaterial(x,y,z);Integer code=codes.get(material);
            if(code==null){code=material==null?0:material.toString().hashCode();codes.put(material,code);}
            hash=31*hash+code;
        }
        return hash;
    }
    public static void main(String[] args)throws Exception {
        String configRoot=System.getProperty("welt.benchmark.configRoot");
        if(configRoot==null||!Configuration.getConfigDir().toPath().toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(configRoot).toAbsolutePath().normalize()))throw new IllegalStateException("Isolated configuration required");
        Configuration config=new Configuration();config.setAutosaveEnabled(false);Configuration.setInstance(config);
        WPPluginManager.initialise(config.getUuid(),WPContext.INSTANCE);
        System.setProperty(Native.GEN_KEY,"false");
        String layer=System.getProperty("welt.benchmark.previewLayer","resources");
        org.pepsoft.worldpainter.layers.Layer selectedLayer=switch(layer) {
            case "resources" -> Resources.INSTANCE;
            case "frost" -> org.pepsoft.worldpainter.layers.Frost.INSTANCE;
            case "caverns" -> org.pepsoft.worldpainter.layers.Caverns.INSTANCE;
            case "chasms" -> org.pepsoft.worldpainter.layers.Chasms.INSTANCE;
            case "groundcover" -> new org.pepsoft.worldpainter.layers.groundcover.GroundCoverLayer("Custom cover",MixedMaterial.create("Custom cover",Material.get("welt:preview_custom_cover")),java.awt.Color.GREEN);
            case "pockets" -> new org.pepsoft.worldpainter.layers.pockets.UndergroundPocketsLayer("Custom pockets",MixedMaterial.create("Custom pockets",Material.get("welt:preview_custom_pocket")),null,500,0,127,100,java.awt.Color.BLUE);
            default -> throw new IllegalArgumentException("Unknown preview layer");
        };
        LayerPreviewCreator creator=new LayerPreviewCreator();creator.setLayer(selectedLayer);
        creator.setSubterranean(!layer.equals("frost")&&!layer.equals("groundcover"));
        if(layer.equals("resources")) {
        var settings=org.pepsoft.worldpainter.layers.exporters.ResourcesExporter.ResourcesExporterSettings.defaultSettings(DefaultPlugin.JAVA_ANVIL_1_17,Dimension.Anchor.NORMAL_DETAIL,0,128);
        // Defaults deliberately generate fresh random offsets; fix only this benchmark fixture.
        var settingsField=settings.getClass().getDeclaredField("settings");settingsField.setAccessible(true);
        var resources=(java.util.Map<?,?>)settingsField.get(settings);
        for(var entry:resources.entrySet()) {
            var offset=entry.getValue().getClass().getDeclaredField("seedOffset");offset.setAccessible(true);
            offset.setLong(entry.getValue(),0x57454c5400000000L^entry.getKey().toString().hashCode());
        }
        creator.setSettings(settings);
        }
        boolean solo=Boolean.getBoolean("welt.benchmark.previewSolo"),selected=Boolean.getBoolean("welt.benchmark.previewCompact");
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        var plantRandomField=org.pepsoft.worldpainter.layers.plants.Plants.class.getDeclaredField("RANDOM");plantRandomField.setAccessible(true);
        var plantRandom=(java.util.Random)plantRandomField.get(null);
        long expected=0;
        int warmups=Integer.getInteger("welt.benchmark.previewWarmups",6),samples=Integer.getInteger("welt.benchmark.previewSamples",7);
        if(warmups<0||samples<1)throw new IllegalArgumentException("Invalid preview sample counts");
        for(int sample=-warmups;sample<samples;sample++)for(int pass=0;pass<2;pass++) {
            boolean compact=((sample+pass)&1)!=0;if(solo&&compact!=selected)continue;
            System.setProperty("welt.compact.previewWorld",Boolean.toString(compact));
            plantRandom.setSeed(0x57454c54L);
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
            MinecraftWorldObject world=creator.renderPreview();long nanos=System.nanoTime()-start;
            allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
            var storage=MinecraftWorldObject.class.getDeclaredField("compactBlocks");storage.setAccessible(true);
            if((storage.get(world)!=null)!=compact)throw new AssertionError("Storage mode was not applied");
            long hash=checksum(world);
            if(retained!=null) {
                var dimensions=world.getDimensions();
                if(!dimensions.equals(retained.getDimensions())||!world.getOffset().equals(retained.getOffset()))throw new AssertionError("Preview geometry mismatch");
                for(int x=0;x<dimensions.x;x++)for(int y=0;y<dimensions.y;y++)for(int z=0;z<dimensions.z;z++)
                    if(world.getMaterial(x,y,z)!=retained.getMaterial(x,y,z))throw new AssertionError("Preview material mismatch at "+x+","+y+","+z+" previous="+retained.getMaterial(x,y,z)+" actual="+world.getMaterial(x,y,z));
            }
            if(expected==0)expected=hash;else if(expected!=hash)throw new AssertionError("Preview checksum mismatch");
            retained=world;if(sample>=0)System.out.println("sample="+sample+" compact="+compact+" nanos="+nanos+" allocated="+allocated);
        }
        for(int i=0;i<3;i++){System.gc();Thread.sleep(100);}
        long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p->p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.println("checksum="+expected+" heap="+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()+" direct="+direct);
    }
}
