package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.impl.fancy.FancyTheme;

/** Whole creation operation: source sampling, tile storage, theme and dimension insertion. */
public final class FancyWorldCreationBenchmark {
    private static volatile long checksum;
    private static long nativeCalls() { return HeightMapTileFactory.completedNativeFancyTiles(); }
    private record State(HeightMapTileFactory factory, Dimension dimension) { }
    private record Result(long nanos, long allocated, State state, long calls) { }
    private static final int SIDE = Integer.getInteger("welt.benchmark.creationSide", 4);
    private static FancyTheme template;
    private static State fixture(HeightMap ignored) {
        FancyTheme theme=template.clone();
        Platform p=DefaultPlugin.JAVA_ANVIL_1_19;
        HeightMapTileFactory factory=new HeightMapTileFactory(197,theme.getHeightMap(),p.minZ,p.standardMaxHeight,false,theme);
        Dimension dimension=new Dimension(new World2(p,p.minZ,p.standardMaxHeight),"Creation",197,factory,Dimension.Anchor.NORMAL_DETAIL,false);
        return new State(factory,dimension);
    }
    private static Result run(HeightMap map, boolean nativeMode) {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        System.setProperty("welt.native.fancyGeneration", Boolean.toString(nativeMode));
        Native.setNinePatchGenEnabled(nativeMode);
        State state=fixture(map);
        long callsBefore = nativeCalls();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        for(int y=-SIDE/2;y<SIDE/2;y++) for(int x=-SIDE/2;x<SIDE/2;x++) state.dimension.addTile(state.factory.createTile(x,y));
        long nanos=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        long calls = nativeCalls() - callsBefore;
        if (nativeMode && calls != SIDE * SIDE) throw new AssertionError("Every generated tile must use the native transaction");
        return new Result(nanos,allocated,state,calls);
    }
    private static long hash(State state) {
        long hash=1;
        for(int ty=-SIDE/2;ty<SIDE/2;ty++) for(int tx=-SIDE/2;tx<SIDE/2;tx++) {
            Tile tile=state.dimension.getTile(tx,ty);
            List<Layer> layers=new ArrayList<>(tile.getLayers());
            layers.sort(Comparator.comparing(Layer::getName));
            for(int y=0;y<128;y++) for(int x=0;x<128;x++) {
                hash=hash*31+Float.floatToIntBits(tile.getHeight(x,y)); hash=hash*31+tile.getWaterLevel(x,y);
                hash=hash*31+tile.getTerrain(x,y).ordinal();
                for(Layer layer:layers) {
                    hash=hash*31+layer.getName().hashCode();
                    hash=hash*31+(layer.getDataSize()==Layer.DataSize.BIT||layer.getDataSize()==Layer.DataSize.BIT_PER_CHUNK
                            ? (tile.getBitLayerValue(layer,x,y)?1:0) : tile.getLayerValue(layer,x,y));
                }
            }
        }
        return hash;
    }
    public static void main(String[] args) {
        if (SIDE < 2 || SIDE > 16 || (SIDE & 1) != 0) throw new IllegalArgumentException("Creation side must be even and 2..16");
        String source=System.getProperty("welt.benchmark.fancySource","noise");
        if (!source.equals("noise") && !source.equals("stock")) throw new IllegalArgumentException("Unknown Fancy creation source");
        HeightMap map;
        if (System.getProperty("welt.benchmark.fancySource","noise").equals("stock")) {
            HeightMapTileFactory factory=TileFactoryFactory.createFancyTileFactory(197,Terrain.GRASS,-64,320,58,62,false,20f,1);
            map=factory.getHeightMap();template=(FancyTheme)factory.getTheme();
        } else {
            map=BitmapWorldCreationBenchmark.proceduralMap("noise");map.setSeed(197);
            template=new FancyTheme(-64,320,62,map,Terrain.GRASS);
        }
        // FancyTheme deliberately creates a random local climate perturbation.
        // Pin its effective seed in this fixture so separate JVMs create the same world.
        SumHeightMap perturbation=(SumHeightMap)template.prepareNativeGeneration().sources().get(4);
        NoiseHeightMap perturbationNoise=(NoiseHeightMap)perturbation.getHeightMap2();
        perturbationNoise.setSeed(7331-perturbationNoise.getSeedOffset());
        int warmups=Integer.getInteger("welt.benchmark.creationWarmups",10);
        if(warmups<5||warmups>100) throw new IllegalArgumentException("Warmups must be 5..100");
        boolean nativeMode=args.length>0&&args[0].equals("rust");
        if(args.length>0&&args[0].equals("compare")) {
            double[] javaTimes=new double[9],rustTimes=new double[9],ratios=new double[9];
            long[] ja=new long[9],ra=new long[9];
            long nativeCalls = 0;
            for(int trial=-warmups;trial<9;trial++) {
                Result j=null,r=null;
                for(int pass=0;pass<2;pass++) {boolean nativePass=((trial+pass)&1)!=0;if(nativePass)r=run(map,true);else j=run(map,false);}
                nativeCalls += r.calls;
                long jh=hash(j.state),rh=hash(r.state); if(jh!=rh)throw new AssertionError("Complete created-world mismatch"); checksum=rh;
                if(trial>=0){javaTimes[trial]=j.nanos/1e6;rustTimes[trial]=r.nanos/1e6;ratios[trial]=(double)j.nanos/r.nanos;ja[trial]=j.allocated;ra[trial]=r.allocated;}
            }
            Arrays.sort(javaTimes);Arrays.sort(rustTimes);Arrays.sort(ratios);Arrays.sort(ja);Arrays.sort(ra);
            System.out.printf(Locale.ROOT,"worldCreation source=%s paired tiles=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d checksum=%d nativeCalls=%d%n","fancy-"+System.getProperty("welt.benchmark.fancySource","noise"),SIDE*SIDE,javaTimes[4],rustTimes[4],ratios[4],ratios[0],ratios[8],ja[4],ra[4],checksum,nativeCalls);
        } else {
            double[] times=new double[9];long[] allocated=new long[9]; long nativeCalls=0;
            for(int trial=-warmups;trial<9;trial++){Result result=run(map,nativeMode);checksum=hash(result.state);nativeCalls+=result.calls;if(trial>=0){times[trial]=result.nanos/1e6;allocated[trial]=result.allocated;}}
            Arrays.sort(times);Arrays.sort(allocated);
            System.out.printf(Locale.ROOT,"worldCreation source=%s mode=%s tiles=%d medianMs=%.3f allocatedBytes=%d checksum=%d nativeCalls=%d%n","fancy-"+System.getProperty("welt.benchmark.fancySource","noise"),nativeMode?"rust":"java",SIDE*SIDE,times[4],allocated[4],checksum,nativeCalls);
        }
        if (Boolean.getBoolean("welt.native.generationProfile")) {
            long[] p=HeightMapTileFactory.nativeGenerationProfile();
            System.out.printf(Locale.ROOT,"generationProfile calls=%d preparationMs=%.3f nativeMs=%.3f applicationMs=%.3f%n",p[0],p[1]/1e6,p[2]/1e6,p[3]/1e6);
        }
    }
}
