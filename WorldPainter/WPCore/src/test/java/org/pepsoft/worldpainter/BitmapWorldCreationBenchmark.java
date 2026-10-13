package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Filter;

/** Whole creation operation: source sampling, tile storage, theme and dimension insertion. */
public final class BitmapWorldCreationBenchmark {
    private static volatile long checksum;
    private static volatile State retainedState;
    private record State(HeightMapTileFactory factory, Dimension dimension) { }
    private record Result(long nanos, long allocated, State state, long calls) { }
    private static final int SIDE = Integer.getInteger("welt.benchmark.creationSide", 4);
    static SimpleTheme theme(int min, int max) {
        var terrain = new TreeMap<Integer, Terrain>();
        terrain.put(min - 1, Terrain.CUSTOM_1); terrain.put(50, Terrain.GRASS); terrain.put(100, Terrain.STONE);
        Map<Filter, Layer> layers = new LinkedHashMap<>();
        layers.put((x,y,z,l) -> Math.floorMod(z,16), Resources.INSTANCE);
        layers.put((x,y,z,l) -> z > 80 ? 15 : 0, Frost.INSTANCE);
        layers.put((x,y,z,l) -> z > 120 ? 200 : 255, Biome.INSTANCE);
        return new SimpleTheme(197, 62, terrain, layers, min, max, true, true);
    }
    private static String sourceName() { return System.getProperty("welt.benchmark.creationSource", "bitmap"); }
    static HeightMap proceduralMap(String source) {
        HeightMap map = new SumHeightMap(new ConstantHeightMap(32), new SumHeightMap(
                new NoiseHeightMap(100, 1.7, 4, 17), new NoiseHeightMap(60, .7, 3, -123)));
        return switch(source) {
            case "noise" -> map;
            case "sum-displacement" -> new SumHeightMap(proceduralMap("displacement"),new ConstantHeightMap(17));
            case "difference-displacement" -> new DifferenceHeightMap(new ConstantHeightMap(180),proceduralMap("displacement"));
            case "product-displacement" -> new ProductHeightMap(proceduralMap("displacement"),new ConstantHeightMap(.7));
            case "minimum-displacement" -> new MinimisingHeightMap(new ConstantHeightMap(90),proceduralMap("displacement"));
            case "fnl" -> new SumHeightMap(new ConstantHeightMap(32), new FastNoiseLiteHeightMap(160, .7, 3, 17));
            case "nested-shapes" -> new SumHeightMap(new TransformingHeightMap("Bands",new BandedHeightMap(31,43,19,177,false),1.7f,.65f,31,-47,.37f),
                    new TransformingHeightMap("Fractal",new MandelbrotHeightMap(),150,200,7,-11,.23f));
            case "nested-fnl" -> new SumHeightMap(new ConstantHeightMap(32),new DisplacementHeightMap("FNL deformation",
                    new TransformingHeightMap("FNL base",new FastNoiseLiteHeightMap(160,.7,3,17),1.7f,.65f,31,-47,.37f),
                    new TransformingHeightMap("FNL angle",new FastNoiseLiteHeightMap(Math.PI*2,1.3,2,19),.8f,1.1f,-11,7,-.23f),
                    new SumHeightMap(new FastNoiseLiteHeightMap(64,.5,3,-321),new ConstantHeightMap(3))));
            case "nested-warp" -> new SumHeightMap(new DisplacementHeightMap("Outer deformation",
                    new DisplacementHeightMap("Inner deformation", map,
                            new ConstantHeightMap(.37), new ConstantHeightMap(13)),
                    new TransformingHeightMap("Angle", new NoiseHeightMap(Math.PI*2, .9, 3, 177),
                            1.3f, .8f, 19, -31, .23f),
                    new SumHeightMap(new NoiseHeightMap(64, .5, 3, -321), new ConstantHeightMap(3))),
                    new ConstantHeightMap(17));
            case "nested-affine" -> new SumHeightMap(new TransformingHeightMap("First", map, 1.7f, .65f, 31, -47, .37f),
                    new TransformingHeightMap("Second", new NoiseHeightMap(45, .9, 3, 63), .7f, 1.3f, -19, 11, -.23f));
            case "nested-translate" -> new ProductHeightMap(new SumHeightMap(new TransformingHeightMap("Translation", map, 1, 1, 173, -211, 0),
                    new ConstantHeightMap(7)), new ConstantHeightMap(.8));
            case "affine" -> new TransformingHeightMap("Creation", map, 1.7f, .65f, 31, -47, .37f);
            case "slope" -> new SlopeHeightMap(map, 3.7f);
            case "affine-displacement" -> new TransformingHeightMap("Creation", proceduralMap("displacement"),
                    1.7f, .65f, 31, -47, .37f);
            case "displacement" -> new DisplacementHeightMap("Creation", map,
                    new NoiseHeightMap(Math.PI * 2, .9, 3, 177), new NoiseHeightMap(64, .5, 3, -321));
            default -> throw new IllegalArgumentException("Unknown creation source");
        };
    }
    static HeightMap map() {
        if (!sourceName().equals("bitmap")) return proceduralMap(sourceName());
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_USHORT_GRAY);
        for (int y=0;y<512;y++) for(int x=0;x<512;x++) image.getRaster().setSample(x,y,0,(x*193+y*79+x*y*13)&255);
        return new TransformingHeightMap("Creation",new BicubicHeightMap(BitmapHeightMap.build().withImage(image).now(),true),1.7f,.65f,31,-47,.37f);
    }
    private static State fixture(HeightMap map) {
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        HeightMapTileFactory factory = new HeightMapTileFactory(197,map,p.minZ,p.standardMaxHeight,false,theme(p.minZ,p.standardMaxHeight));
        Dimension dimension = new Dimension(new World2(p,p.minZ,p.standardMaxHeight),"Creation",197,factory,Dimension.Anchor.NORMAL_DETAIL,false);
        return new State(factory,dimension);
    }
    private static Result run(HeightMap map, boolean nativeMode) {
        System.setProperty(Native.GEN_KEY,Boolean.toString(nativeMode));
        System.setProperty("welt.native.bitmapGeneration", Boolean.toString(nativeMode));
        System.setProperty("welt.native.proceduralGeneration", Boolean.toString(nativeMode));
        State state=fixture(map);
        long callsBefore = HeightMapTileFactory.completedNativeGeneratedTiles();
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        for(int y=-SIDE/2;y<SIDE/2;y++) for(int x=-SIDE/2;x<SIDE/2;x++) state.dimension.addTile(state.factory.createTile(x,y));
        long nanos=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        long calls = HeightMapTileFactory.completedNativeGeneratedTiles() - callsBefore;
        if (nativeMode && calls != SIDE * SIDE) throw new AssertionError("Every generated tile must use the native transaction");
        return new Result(nanos,allocated,state,calls);
    }
    private static long hash(State state) {
        long hash=1;
        for(int ty=-SIDE/2;ty<SIDE/2;ty++) for(int tx=-SIDE/2;tx<SIDE/2;tx++) {
            Tile tile=state.dimension.getTile(tx,ty);
            for(int y=0;y<128;y++) for(int x=0;x<128;x++) {
                hash=hash*31+Float.floatToIntBits(tile.getHeight(x,y)); hash=hash*31+tile.getWaterLevel(x,y);
                hash=hash*31+tile.getTerrain(x,y).ordinal();
                hash=hash*31+tile.getLayerValue(Resources.INSTANCE,x,y); hash=hash*31+tile.getLayerValue(Biome.INSTANCE,x,y);
                hash=hash*31+(tile.getBitLayerValue(Frost.INSTANCE,x,y)?1:0);
            }
        }
        return hash;
    }
    public static void main(String[] args) {
        if (SIDE < 2 || SIDE > 16 || (SIDE & 1) != 0) throw new IllegalArgumentException("Creation side must be even and 2..16");
        HeightMap map=map();
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
            System.out.printf(Locale.ROOT,"worldCreation source=%s paired tiles=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d checksum=%d nativeCalls=%d%n",sourceName(),SIDE*SIDE,javaTimes[4],rustTimes[4],ratios[4],ratios[0],ratios[8],ja[4],ra[4],checksum,nativeCalls);
        } else {
            double[] times=new double[9];long[] allocated=new long[9]; long nativeCalls=0;
            for(int trial=-warmups;trial<9;trial++){Result result=run(map,nativeMode);checksum=hash(result.state);retainedState=result.state;nativeCalls+=result.calls;if(trial>=0){times[trial]=result.nanos/1e6;allocated[trial]=result.allocated;}}
            Arrays.sort(times);Arrays.sort(allocated);
            System.out.printf(Locale.ROOT,"worldCreation source=%s mode=%s tiles=%d medianMs=%.3f allocatedBytes=%d checksum=%d nativeCalls=%d%n",sourceName(),nativeMode?"rust":"java",SIDE*SIDE,times[4],allocated[4],checksum,nativeCalls);
        }
        if (Boolean.getBoolean("welt.benchmark.creationMemory")) {
            for (int i=0;i<3;i++) { System.gc(); try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
            long direct=0; for (var pool:ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) if(pool.getName().equals("direct")) direct+=pool.getMemoryUsed();
            System.out.printf(Locale.ROOT,"creationMemory retainedTiles=%d heapBytes=%d directBytes=%d%n",retainedState.dimension.getTiles().size(),ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),direct);
        }
        if (Boolean.getBoolean("welt.native.generationProfile")) {
            long[] p=HeightMapTileFactory.nativeGenerationProfile();
            System.out.printf(Locale.ROOT,"generationProfile calls=%d preparationMs=%.3f nativeMs=%.3f applicationMs=%.3f%n",p[0],p[1]/1e6,p[2]/1e6,p[3]/1e6);
        }
    }
}
