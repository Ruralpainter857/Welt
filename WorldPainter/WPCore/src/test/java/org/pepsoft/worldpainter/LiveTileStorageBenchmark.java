package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Locale;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Complete serial dimension creation and a water edit/undo, with a live-plane inventory outside the clock. */
public final class LiveTileStorageBenchmark {
    private static volatile Object retained;

    private record Result(double millis, long allocated, long hash, int tiles, long heap,
                          long payload, int waterArrays, long waterBytes, int uniformWaterTiles) {}

    private static Result run(int side, boolean nativeMode, boolean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        var platform = DefaultPlugin.JAVA_ANVIL_1_19;
        var factory = new HeightMapTileFactory(197,
                new SumHeightMap(new ConstantHeightMap(64), new NoiseHeightMap(35f, 1.0, 2, 0)),
                -64, 320, false, SimpleTheme.createDefault(Terrain.GRASS, -64, 320, 62, true, true));
        var world = new World2(platform, -64, 320);
        var dimension = new Dimension(world, "Live storage", 197, factory, Dimension.Anchor.NORMAL_DETAIL, true);
        world.addDimension(dimension);
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId(), calls = HeightMapTileFactory.completedNativeGeneratedTiles();
        long allocated = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        dimension.setEventsInhibited(true);
        try {
            for (int x = -side/2; x < side-side/2; x++) for (int y = -side/2; y < side-side/2; y++) {
                dimension.addTile(factory.createTile(x,y));
            }
        } finally { dimension.setEventsInhibited(false); }
        UndoManager undo = new UndoManager(4);
        dimension.registerUndoManager(undo);
        dimension.armSavePoint();
        int oldWater = dimension.getWaterLevelAt(0,0);
        dimension.setWaterLevelAt(0,0,oldWater+3);
        if (dimension.getWaterLevelAt(0,0) != oldWater+3 || !undo.undo()
                || dimension.getWaterLevelAt(0,0) != oldWater) throw new AssertionError("Water edit/undo failed");
        double millis = (System.nanoTime()-start)/1e6;
        allocated = meter.getThreadAllocatedBytes(thread)-allocated;
        if (HeightMapTileFactory.completedNativeGeneratedTiles()-calls != (nativeMode ? side*side : 0)) {
            throw new AssertionError("Complete native generation coverage missing");
        }
        Field[] planes = new Field[7];
        String[] names = {"heightMap","tallHeightMap","waterLevel","tallWaterLevel","terrain","layerData","bitLayerData"};
        for (int i=0;i<planes.length;i++) { planes[i]=Tile.class.getDeclaredField(names[i]); planes[i].setAccessible(true); }
        var seen = new IdentityHashMap<Object,Boolean>();
        var waters = new IdentityHashMap<short[],Boolean>();
        long payload=0, waterBytes=0, hash=1;
        int uniform=0;
        var tiles = dimension.getTiles().stream().sorted(Comparator.comparingInt(Tile::getX).thenComparingInt(Tile::getY)).toList();
        for (Tile tile:tiles) {
            tile.getWaterLevel(0,0); // Refresh any lazy history view before inspecting backing arrays.
            for (Field plane:planes) payload += TileStorageInfo.retainedBytes(plane.get(tile),seen);
            short[] water=(short[])planes[3].get(tile);
            if (waters.put(water,Boolean.TRUE)==null) waterBytes+=16L+2L*water.length;
            boolean same=true;for(int i=1;i<water.length;i++) if(water[i]!=water[0]) {same=false;break;}
            if(same) uniform++;
            hash=hash*31+tile.getX();hash=hash*31+tile.getY();
            var layers=tile.getLayers().stream().sorted(Comparator.comparing(Layer::getId)).toList();
            for(var layer:layers) hash=hash*31+layer.getId().hashCode();
            for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                hash=hash*31+tile.getRawHeight(x,y);hash=hash*31+tile.getWaterLevel(x,y);hash=hash*31+tile.getTerrain(x,y).ordinal();
                for(var layer:layers)hash=hash*31+switch(layer.getDataSize()){
                    case BIT,BIT_PER_CHUNK -> tile.getBitLayerValue(layer,x,y)?1:0;
                    default -> tile.getLayerValue(layer,x,y);
                };
            }
        }
        long heap=0;
        retained=dimension;
        if(memory){System.gc();heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
        java.lang.ref.Reference.reachabilityFence(world);
        return new Result(millis,allocated,hash,dimension.getTileCount(),heap,payload,waters.size(),waterBytes,uniform);
    }

    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"rust":args[0];boolean paired=mode.equals("compare"),nativeMode=!mode.equals("java");
        if(!paired&&!mode.equals("java")&&!mode.equals("rust"))throw new IllegalArgumentException("Invalid benchmark mode");
        int side=Integer.getInteger("welt.benchmark.liveTileSide",16), trials=Integer.getInteger("welt.benchmark.liveTileTrials",paired?7:3);
        if(side<2||side>64||trials<3)throw new IllegalArgumentException("Invalid live storage benchmark dimensions");
        boolean memory=Boolean.getBoolean("welt.benchmark.liveTileMemory");
        if(memory&&paired)throw new IllegalArgumentException("Memory instrumentation requires separate processes");
        System.setProperty("welt.native.proceduralGeneration","true");
        System.setProperty(Native.RENDER_KEY,"false");
        if(!NativeLoader.areSlicesAvailable())throw new AssertionError("Release library required for comparable environments");
        double[] times=new double[trials],reference=new double[trials],ratios=new double[trials];long expected=0;
        for(int trial=-5;trial<trials;trial++){
            for(int pass=0;pass<(paired?2:1);pass++){
                if(paired)System.setProperty("welt.tile.shareTallWater",Boolean.toString(((trial+pass)&1)!=0));
                boolean sharing=Boolean.parseBoolean(System.getProperty("welt.tile.shareTallWater","true"));
                retained=null;if(memory)System.gc();
                Result result=run(side,nativeMode,memory&&trial>=0);
                if(trial==-5&&pass==0)expected=result.hash;else if(expected!=result.hash)throw new AssertionError("Unstable live world output");
                if(trial>=0){if(paired&&!sharing)reference[trial]=result.millis;else times[trial]=result.millis;
                    System.out.printf(Locale.ROOT,
                    "live_tiles mode=%s sharing=%s trial=%d ms=%.3f allocated=%d tiles=%d hash=%d retained_heap=%d plane_payload=%d water_arrays=%d water_bytes=%d uniform_water_tiles=%d%n",
                    nativeMode?"rust":"java",sharing,trial,result.millis,result.allocated,result.tiles,result.hash,result.heap,result.payload,result.waterArrays,result.waterBytes,result.uniformWaterTiles);}
            }
            if(paired&&trial>=0)ratios[trial]=reference[trial]/times[trial];
        }
        Arrays.sort(times);Arrays.sort(reference);Arrays.sort(ratios);
        System.out.printf(Locale.ROOT,"live_tiles_summary median_ms=%.3f reference_ms=%.3f paired_ratio=%.3f memory_instrumented=%s workers=1%n",times[trials/2],reference[trials/2],ratios[trials/2],memory);
    }
}
