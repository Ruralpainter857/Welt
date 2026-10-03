package org.pepsoft.worldpainter.importing;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import org.pepsoft.minecraft.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.plugins.PlatformManager;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Whole disk-backed Minecraft import; fixture creation and output checking are outside timing. */
public final class MapImportBenchmark extends AbstractTool {
    static final Platform PLATFORM=DefaultPlugin.JAVA_ANVIL_1_18;
    static final Layer[] LAYERS={Frost.INSTANCE,FloodWithLava.INSTANCE,org.pepsoft.worldpainter.layers.Void.INSTANCE,Biome.INSTANCE,ReadOnly.INSTANCE,Populate.INSTANCE};
    static File fixture(Path root) throws Exception {
        Path world=root.resolve("fixture");Files.createDirectories(world.resolve("region"));
        JavaLevel level=JavaLevel.create(PLATFORM,-64,320);level.setName("Welt import fixture");level.setSeed(7331);
        level.setGenerator(0,new SuperflatGenerator(SuperflatPreset.defaultPreset(PLATFORM)));level.save(world.toFile());
        int side=Integer.getInteger("welt.benchmark.importSide",4);
        try(ChunkStore store=PlatformManager.getInstance().getChunkStore(PLATFORM,world.toFile(),0)){
            for(int cz=-side/2;cz<side-side/2;cz++)for(int cx=-side/2;cx<side-side/2;cx++){
                MC118AnvilChunk chunk=new MC118AnvilChunk(cx,cz,-64,320);
                for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    int h=48+((x*3+z*7+cx*13+cz*11)&31);
                    if((x+z)%53==0)continue;
                    for(int y=-64;y<h;y++)chunk.setMaterial(x,y,z,y==-64?Material.BEDROCK:Material.STONE);
                    chunk.setMaterial(x,h,z,Material.GRASS_BLOCK);
                    if(h<62)for(int y=h+1;y<=62;y++)chunk.setMaterial(x,y,z,Material.WATER);
                    if((x+z)%7==0)chunk.setMaterial(x,h+1,z,Material.SNOW.withProperty(Material.LAYERS,4));
                    if((x+z)%11==0)chunk.setMaterial(x,h+2,z,Material.get("minecraft:oak_leaves"));
                    if((x+z)%13==0)chunk.setMaterial(x,h+3,z,Material.ICE);
                    if((x+z)%17==0)chunk.setMaterial(x,h+4,z,Material.BRICKS);
                    if((x+z)%19==0)chunk.setMaterial(x,h-20,z,Material.GLASS);
                }
                store.saveChunk(chunk);
            }
            store.flush();
        }
        return world.resolve("level.dat").toFile();
    }
    static World2 importWorld(File levelDat) throws Exception {
        JavaMapImporter.resetImportProfile();org.pepsoft.worldpainter.exporting.JavaChunkStore.resetDecodeProfile();
        var factory=new HeightMapTileFactory(7331,new ConstantHeightMap(62),-64,320,false,SimpleTheme.createSingleTerrain(Terrain.GRASS,-64,320,62));
        var importer=new JavaMapImporter(PLATFORM,factory,levelDat,null,MapImporter.ReadOnlyOption.valueOf(System.getProperty("welt.benchmark.importReadOnly","MAN_MADE")),Set.of(0));
        World2 world=importer.doImport(null);if(importer.getWarnings()!=null)throw new AssertionError("Fixture must import without warnings: "+importer.getWarnings());
        return world;
    }
    static List<Tile> tiles(World2 world){
        List<Tile> result=new ArrayList<>(world.getDimension(Dimension.Anchor.NORMAL_DETAIL).getTiles());
        result.sort(Comparator.comparingInt(Tile::getX).thenComparingInt(Tile::getY));return result;
    }
    static List<Layer> layers(Tile tile){List<Layer> result=new ArrayList<>(tile.getLayers());result.sort(Comparator.comparing(Layer::getId));return result;}
    static int value(Tile tile,Layer layer,int x,int y){
        return layer.dataSize==Layer.DataSize.BIT || layer.dataSize==Layer.DataSize.BIT_PER_CHUNK?(tile.getBitLayerValue(layer,x,y)?1:0):tile.getLayerValue(layer,x,y);
    }
    static long hash(World2 world){
        long result=1;
        for(Tile t:tiles(world)){result=result*31+t.getX();result=result*31+t.getY();List<Layer> layers=layers(t);
            for(Layer l:layers)result=result*31+l.getId().hashCode();
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){
                result=result*31+Float.floatToIntBits(t.getHeight(x,y));result=result*31+t.getWaterLevel(x,y);result=result*31+t.getTerrain(x,y).ordinal();
                for(Layer l:layers)result=result*31+value(t,l,x,y);
            }
        }
        return result;
    }
    private record Result(long nanos,long allocated,long hash,long calls) { }
    private static Result run(File levelDat,String mode) throws Exception {
        System.setProperty("welt.native.regionHeader",Boolean.toString(!mode.equals("java")));
        System.setProperty("welt.native.regionHeaderKernel",Boolean.toString(!mode.equals("grouped-java")));
        long calls=RegionHeaderAccess.completedCalls();
        var memory=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long before=memory.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();World2 world=importWorld(levelDat);
        long elapsed=System.nanoTime()-start,bytes=memory.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        long delta=RegionHeaderAccess.completedCalls()-calls;
        if(mode.equals("rust")&&delta==0)throw new AssertionError("Native region decoder did not execute");
        if(!mode.equals("rust")&&delta!=0)throw new AssertionError("Reference unexpectedly executed native decoder");
        return new Result(elapsed,bytes,hash(world),delta);
    }
    private static void quietLogging() throws Exception {
        Object root=org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        try {
            Class<?> level=Class.forName("ch.qos.logback.classic.Level");
            root.getClass().getMethod("setLevel",level).invoke(root,level.getField("WARN").get(null));
        } catch(ClassNotFoundException | NoSuchMethodException e) {
            java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
        }
    }

    public static void main(String[] args) throws Exception {
        String selected=args.length==0?"java":args[0];boolean paired=selected.startsWith("compare");
        Path root=Files.createTempDirectory("welt-map-import-");Files.createDirectories(root.resolve("home"));System.setProperty("user.home",root.resolve("home").toString());
        System.setProperty("org.pepsoft.worldpainter.threads",System.getProperty("welt.benchmark.importWorkers","1"));initialisePlatform();
        quietLogging();
        File levelDat=fixture(root);int warm=Integer.getInteger("welt.benchmark.importWarmups",6),trials=Integer.getInteger("welt.benchmark.importTrials",9);
        double[] millis=new double[trials],rust=new double[trials],ratios=new double[trials];long[] allocated=new long[trials],rustAllocated=new long[trials];long expected=0;Result last=null;
        String reference=selected.equals("compare-grouped")?"grouped-java":"java";
        for(int i=-warm;i<trials;i++){
            if(paired){Result j=null,r=null;for(int pass=0;pass<2;pass++){if(((i+pass)&1)==0)j=run(levelDat,reference);else r=run(levelDat,"rust");}
                if(j.hash!=r.hash)throw new AssertionError("Whole imported worlds differ");last=r;
                if(i==-warm)expected=r.hash;else if(r.hash!=expected)throw new AssertionError("Imported world changed across trials");
                if(i>=0){millis[i]=j.nanos/1e6;rust[i]=r.nanos/1e6;ratios[i]=(double)j.nanos/r.nanos;allocated[i]=j.allocated;rustAllocated[i]=r.allocated;}
            }else{last=run(levelDat,selected);if(i==-warm)expected=last.hash;else if(last.hash!=expected)throw new AssertionError("Import output changed");
                if(i>=0){millis[i]=last.nanos/1e6;allocated[i]=last.allocated;}}
        }
        Arrays.sort(millis);Arrays.sort(rust);Arrays.sort(ratios);Arrays.sort(allocated);Arrays.sort(rustAllocated);int middle=trials/2;
        var decode=org.pepsoft.worldpainter.exporting.JavaChunkStore.decodeProfile();
        System.out.printf(Locale.ROOT,"mapDecodeProfile chunks=%d workerNbtMs=%.3f constructionMs=%.3f allocatedBytes=%d%n",decode.chunks(),decode.nbtNanos()/1e6,decode.constructionNanos()/1e6,decode.allocatedBytes());
        var profile=JavaMapImporter.importProfile();
        System.out.printf(Locale.ROOT,"mapImportProfile chunks=%d workerVisitorMs=%.3f surfaceMs=%.3f writesMs=%.3f biomesMs=%.3f%n",profile.chunks(),profile.visitorNanos()/1e6,profile.surfaceNanos()/1e6,profile.writesNanos()/1e6,profile.biomesNanos()/1e6);
        if(paired)System.out.printf(Locale.ROOT,"mapImport side=%d workers=%s readOnly=%s reference=%s referenceMs=%.3f rustMs=%.3f pairedRatio=%.3f range=%.3f..%.3f callerAllocated=%d rustCallerAllocated=%d hash=%d nativeRegionCalls=%d%n",Integer.getInteger("welt.benchmark.importSide",4),System.getProperty("org.pepsoft.worldpainter.threads"),System.getProperty("welt.benchmark.importReadOnly","MAN_MADE"),reference,millis[middle],rust[middle],ratios[middle],ratios[0],ratios[trials-1],allocated[middle],rustAllocated[middle],expected,last.calls);
        else System.out.printf(Locale.ROOT,"mapImport side=%d workers=%s readOnly=%s mode=%s medianMs=%.3f callerAllocated=%d hash=%d nativeRegionCalls=%d%n",Integer.getInteger("welt.benchmark.importSide",4),System.getProperty("org.pepsoft.worldpainter.threads"),System.getProperty("welt.benchmark.importReadOnly","MAN_MADE"),selected,millis[middle],allocated[middle],expected,last.calls);
    }
}