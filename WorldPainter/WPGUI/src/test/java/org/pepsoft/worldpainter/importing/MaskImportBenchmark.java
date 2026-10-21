package org.pepsoft.worldpainter.importing;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Complete image-mask imports, including range detection, scaling and notifications. */
public final class MaskImportBenchmark {
    static BufferedImage image(String type,int size) {
        BufferedImage image=new BufferedImage(size,size,type.equals("colour")?BufferedImage.TYPE_INT_ARGB:
                type.equals("terrain-index")||type.equals("discrete")||type.equals("direct")?BufferedImage.TYPE_BYTE_GRAY:BufferedImage.TYPE_USHORT_GRAY);
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
            int value=(x*971+y*313+x*y*17)&65535;
            if(type.equals("colour")) image.setRGB(x,y,((value%256)<<24)|((x*37&255)<<16)|((y*17&255)<<8)|(value&255));
            else image.getRaster().setSample(x,y,0,type.equals("terrain-index")?value%Terrain.VALUES.length:
                    type.equals("discrete")||type.equals("direct")?value%16:value);
        }
        return image;
    }
    static Dimension fixture(int width,int height,int ox,int oy) {
        Platform platform=DefaultPlugin.JAVA_ANVIL_1_19;
        World2 world=new World2(platform,platform.minZ,platform.standardMaxHeight);
        var ranges=new TreeMap<Integer,Terrain>();ranges.put(platform.minZ-1,Terrain.GRASS);
        var theme=new SimpleTheme(197,62,ranges,null,platform.minZ,platform.standardMaxHeight,false,false);
        var factory=new HeightMapTileFactory(197,new ConstantHeightMap(62),platform.minZ,platform.standardMaxHeight,false,theme);
        Dimension d=new Dimension(world,"Mask",197,factory,Dimension.Anchor.NORMAL_DETAIL,false);
        for(int tx=ox>>7;tx<=(ox+width-1)>>7;tx++) for(int ty=oy>>7;ty<=(oy+height-1)>>7;ty++) {
            if(tx==0&&ty==0) continue;
            Tile tile=factory.createTile(tx,ty);tile.inhibitEvents();
            for(int x=0;x<128;x++) for(int y=0;y<128;y++) {
                tile.setLayerValue(Resources.INSTANCE,x,y,(x+y)%16);
                tile.setLayerValue(Biome.INSTANCE,x,y,(x*7+y)%256);
                tile.setBitLayerValue(Frost.INSTANCE,x,y,(x+y)%3==0);
                tile.setBitLayerValue(Populate.INSTANCE,x,y,(x+y)%3==0);
                tile.setLayerValue(Annotations.INSTANCE,x,y,(x+y)%16);
            }
            tile.releaseEvents();d.addTile(tile);
        }
        return d;
    }
    static Layer layer(String type) {
        return switch(type) {case "terrain","terrain-index"->null;case "discrete","fixed"->Biome.INSTANCE;
            case "bit"->Frost.INSTANCE;case "chunk"->Populate.INSTANCE;case "colour"->Annotations.INSTANCE;default->Resources.INSTANCE;};
    }
    static Mapping mapping(String type) {
        return switch(type) {case "terrain"->Mapping.setTerrainValue(Terrain.CUSTOM_1).threshold();
            case "terrain-index"->Mapping.mapToTerrain();case "discrete"->Mapping.mapToLayer(Biome.INSTANCE);
            case "fixed"->Mapping.setLayerValue(Biome.INSTANCE,42).ditheredActualRange();
            case "bit"->Mapping.mapToLayer(Frost.INSTANCE).ditheredFullRange();
            case "chunk"->Mapping.mapToLayer(Populate.INSTANCE).threshold();case "colour"->Mapping.colourToAnnotations();
            case "direct"->Mapping.mapToLayer(Resources.INSTANCE);default->Mapping.mapActualRangeToLayer(Resources.INSTANCE);};
    }
    static void configure(MaskImporter importer,String type,float scale,boolean remove) {
        if(layer(type)==null) {importer.setApplyToTerrain(true);if(type.equals("terrain")) importer.setApplyToTerrainType(Terrain.CUSTOM_1);}
        else {importer.setApplyToLayer(layer(type));if(type.equals("fixed")) importer.setApplyToLayerValue(42);}
        importer.setMapping(mapping(type));importer.setScale(scale);importer.setxOffset(-31);importer.setyOffset(-47);
        importer.setThreshold(32767);importer.setRemoveExistingLayer(remove);
    }
    static void configure(ScalarMaskImporter importer,String type,float scale,boolean remove) {
        if(layer(type)==null) {importer.setApplyToTerrain(true);if(type.equals("terrain")) importer.setApplyToTerrainType(Terrain.CUSTOM_1);}
        else {importer.setApplyToLayer(layer(type));if(type.equals("fixed")) importer.setApplyToLayerValue(42);}
        importer.setMapping(mapping(type));importer.setScale(scale);importer.setxOffset(-31);importer.setyOffset(-47);
        importer.setThreshold(32767);importer.setRemoveExistingLayer(remove);
    }
    public static void main(String[] args)throws Exception {
        boolean rust=args.length>0&&args[0].equals("rust");String type=args.length>1?args[1]:"continuous";
        float scale=args.length>2?Float.parseFloat(args[2]):1;int side=args.length>3?Integer.parseInt(args[3]):512;
        boolean remove=args.length>4&&args[4].equals("remove");String old=System.getProperty(Native.GEN_KEY);
        NativeLoader.areSlicesAvailable();var image=image(type,side);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        double[] times=new double[7];long[] bytes=new long[7];long rss=0;int calls=0;
        try {
            for(int trial=-5;trial<7;trial++) {
                System.setProperty(Native.GEN_KEY,"false");Dimension d=fixture(Math.round(side*scale),Math.round(side*scale),-31,-47);
                System.setProperty(Native.GEN_KEY,Boolean.toString(rust));
                long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
                MaskImporter importer=new MaskImporter(d,new File("mask.png"),image);configure(importer,type,scale,remove);importer.doImport(null);if(rust)calls=importer.getLastNativeImportCalls();
                double elapsed=(System.nanoTime()-start)/1e6;long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
                rss=Math.max(rss,NativeSlices.currentProcessResidentBytes());if(trial>=0){times[trial]=elapsed;bytes[trial]=allocated;}
            }
            Arrays.sort(times);Arrays.sort(bytes);
            System.out.printf(Locale.ROOT,"%s mask_import=%s scale=%.2f side=%d remove=%b median_ms=%.3f heap_allocated_bytes=%d sampled_peak_rss_bytes=%d jni_calls=%d%n",
                    rust?"Rust":"Java",type,scale,side,remove,times[3],bytes[3],rss,calls);
        } finally {if(old==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,old);}
    }
}
