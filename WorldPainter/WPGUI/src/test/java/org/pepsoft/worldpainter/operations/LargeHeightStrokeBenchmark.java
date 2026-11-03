package org.pepsoft.worldpainter.operations;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** Full height-stroke computation: brush sampling, themed edits, undo and notifications, excluding display redraw. */
public final class LargeHeightStrokeBenchmark {
    private static volatile long checksum;

    static Dimension fixture(int radius) {
        var platform = DefaultPlugin.JAVA_ANVIL_1_19;
        var factory = new HeightMapTileFactory(197, new ConstantHeightMap(64), -64, 320, false,
                SimpleTheme.createDefault(Terrain.GRASS,-64,320,62,true,true));
        var world = new World2(platform,-64,320);
        var dimension = new Dimension(world,"Large brush",197,factory,Dimension.Anchor.NORMAL_DETAIL,true);
        for (int ty = (-radius-5)>>7; ty <= (radius+5)>>7; ty++) for (int tx = (-radius-5)>>7; tx <= (radius+5)>>7; tx++) {
            if (tx == 1 && ty == 1) continue;
            Tile tile = new Tile(tx,ty,-64,320);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setRawHeight(x,y,28000+Math.floorMod(x*193+y*79+tx*53+ty*71,20000));
                tile.setWaterLevel(x,y,62); tile.setTerrain(x,y,Terrain.GRASS);
            }
            dimension.addTile(tile);
        }
        dimension.registerUndoManager(new UndoManager(4)); return dimension;
    }

    private record Result(Dimension dimension, double millis, long allocated, long hash, long random,
                          long heapBaseline, long peakPools, long retained, long direct) { }

    private static Random random() throws Exception {
        Method source=Class.forName("org.pepsoft.worldpainter.ThemeResetParityTest").getDeclaredMethod("random");
        source.setAccessible(true);return (Random)source.invoke(null);
    }

    private static long digest(Dimension dimension) {
        List<Tile> tiles=new ArrayList<>(dimension.getTiles());
        tiles.sort(Comparator.comparingInt(Tile::getX).thenComparingInt(Tile::getY));
        long hash=1;
        for(Tile tile:tiles) {
            hash=hash*31+tile.getX();hash=hash*31+tile.getY();
            List<org.pepsoft.worldpainter.layers.Layer> layers=new ArrayList<>(tile.getLayers());
            layers.sort(Comparator.comparing(org.pepsoft.worldpainter.layers.Layer::getId));
            for(var layer:layers)hash=hash*31+layer.getId().hashCode();
            for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                hash=hash*31+tile.getRawHeight(x,y);hash=hash*31+tile.getWaterLevel(x,y);hash=hash*31+tile.getTerrain(x,y).ordinal();
                for(var layer:layers)hash=hash*31+switch(layer.getDataSize()) {
                    case BIT,BIT_PER_CHUNK -> tile.getBitLayerValue(layer,x,y)?1:0;
                    default -> tile.getLayerValue(layer,x,y);
                };
            }
        }
        return hash;
    }

    private static Result run(Height brush,int radius,boolean nativeMode,boolean memory) throws Exception {
        Dimension dimension=fixture(radius);random().setSeed(99);
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        System.setProperty("welt.native.largeHeightBrush","true");
        Class<?>[] signature={Dimension.class,int.class,int.class,int.class,boolean.class,float.class,float.class,float.class,boolean.class};
        Method operation=Height.class.getDeclaredMethod(nativeMode?"applyNativeHeightBrush":"applyJavaHeightBrush",signature);operation.setAccessible(true);
        var meter=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();meter.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId(),baseline=0;
        if(memory){System.gc();baseline=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();}
        long calls=HeightBrushAccess.completedThemedCalls(),allocatedBefore=meter.getThreadAllocatedBytes(thread),start=System.nanoTime();
        for(int stroke=0;stroke<4;stroke++) {
            dimension.armSavePoint();dimension.setEventsInhibited(true);
            try {
                Object result=operation.invoke(brush,dimension,-3,5,radius,(stroke&1)!=0,8f,-64f,319f,true);
                if(nativeMode&&!Boolean.TRUE.equals(result))throw new AssertionError("Large native height stroke unavailable");
            } finally {dimension.setEventsInhibited(false);}
        }
        long elapsed=System.nanoTime()-start,allocated=meter.getThreadAllocatedBytes(thread)-allocatedBefore;
        if(HeightBrushAccess.completedThemedCalls()-calls!=(nativeMode?4:0))throw new AssertionError("Unexpected whole-stroke native coverage");
        long peak=0,retained=0,direct=0;
        if(memory) {
            peak=ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool->pool.getType()==java.lang.management.MemoryType.HEAP).mapToLong(pool->pool.getPeakUsage().getUsed()).sum();
            System.gc();retained=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(pool->pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
            java.lang.ref.Reference.reachabilityFence(dimension);
        }
        long hash=digest(dimension),nextRandom=random().nextLong();checksum=hash;
        return new Result(dimension,elapsed/1e6,allocated,hash,nextRandom,baseline,peak,retained,direct);
    }

    public static void main(String[] args) throws Exception {
        int radius=args.length==0?255:Integer.parseInt(args[0]);String mode=args.length<2?"java":args[1];
        boolean paired=mode.equals("compare"),nativeOnly=mode.equals("rust"),memory=Boolean.getBoolean("welt.benchmark.largeHeightMemory");
        int warmups=Integer.getInteger("welt.benchmark.largeHeightWarmups",5),trials=Integer.getInteger("welt.benchmark.largeHeightTrials",9);
        if(radius<128||radius>1023||warmups<5||trials<3||!(paired||nativeOnly||mode.equals("java"))||memory&&paired)throw new IllegalArgumentException("Invalid large brush benchmark mode");
        System.setProperty(Native.GEN_KEY,"false");System.setProperty(Native.RENDER_KEY,"false");
        Height brush=new Height(new BrushPipelineTestView());brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());brush.setRadius(radius);brush.setLevel(.63f);
        double[] javaTimes=new double[trials],rustTimes=new double[trials],ratios=new double[trials];long[] javaBytes=new long[trials],rustBytes=new long[trials];
        long expectedHash=0,expectedRandom=0;
        for(int trial=-warmups;trial<trials;trial++) {
            Result java=null,rust=null;
            for(int pass=0;pass<(paired?2:1);pass++) {
                boolean nativeMode=paired?((trial+pass)&1)!=0:nativeOnly;
                Result result=run(brush,radius,nativeMode,memory&&trial>=0);
                if(trial==-warmups&&pass==0){expectedHash=result.hash;expectedRandom=result.random;}
                else if(expectedHash!=result.hash||expectedRandom!=result.random)throw new AssertionError("Unstable stroke output or theme RNG");
                if(nativeMode)rust=result;else java=result;
                if(trial>=0) {
                    if(nativeMode){rustTimes[trial]=result.millis;rustBytes[trial]=result.allocated;}else{javaTimes[trial]=result.millis;javaBytes[trial]=result.allocated;}
                    if(memory)System.out.printf(Locale.ROOT,"largeHeightMemory mode=%s trial=%d heapBaselineBytes=%d heapPoolPeakSumBytes=%d retainedHeapBytes=%d directBytes=%d%n",mode,trial,result.heapBaseline,result.peakPools,result.retained,result.direct);
                }
            }
            if(paired&&trial>=0)ratios[trial]=java.millis/rust.millis;
        }
        Arrays.sort(javaTimes);Arrays.sort(rustTimes);Arrays.sort(ratios);Arrays.sort(javaBytes);Arrays.sort(rustBytes);int median=trials/2;
        if(paired)System.out.printf(Locale.ROOT,"largeHeightStroke paired radius=%d strokes=4 javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocatedBytes=%d rustAllocatedBytes=%d checksum=%d%n",radius,javaTimes[median],rustTimes[median],ratios[median],ratios[0],ratios[trials-1],javaBytes[median],rustBytes[median],checksum);
        else System.out.printf(Locale.ROOT,"largeHeightStroke mode=%s radius=%d strokes=4 medianMs=%.3f callerAllocatedBytes=%d checksum=%d memoryInstrumented=%s%n",mode,radius,(nativeOnly?rustTimes:javaTimes)[median],(nativeOnly?rustBytes:javaBytes)[median],checksum,memory);
    }
}