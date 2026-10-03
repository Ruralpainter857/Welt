package org.pepsoft.worldpainter;

import java.util.*;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Filter;
import static org.junit.Assert.*;

public class ProceduralTileGenerationParityTest {
    private static final Layer CHUNK = new Layer("welt.test.generation.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) { };
    @Test public void completeProceduralTilesPreserveStorageAndRandomStream() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY), flag=System.getProperty("welt.native.proceduralGeneration");
        try {
            System.setProperty("welt.native.proceduralGeneration","true");
            for (String source : new String[]{"noise","fnl","affine","slope","displacement","affine-displacement"}) {
                HeightMap map=BitmapWorldCreationBenchmark.proceduralMap(source);
                for (int min : new int[]{0,-64}) {
                    for (int tx : new int[]{-1,0,1}) compare(map,min,min==0?256:320,tx,-tx,true);
                    map.setSeed(89173);
                    compare(map,min,min==0?256:320,0,0,true);
                }
            }
            HeightMap tree=BitmapWorldCreationBenchmark.proceduralMap("noise");
            compare(new TransformingHeightMap("Translation",tree,1,1,31,-47,0),-64,320,-1,1,true);
            compare(new TransformingHeightMap("Negative",tree,-1.7f,.65f,31,-47,.37f),-64,320,-1,1,true);
            compare(new ConstantHeightMap(-1000),-64,320,0,0,true);
            compare(new ConstantHeightMap(100000),0,256,0,0,true);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.proceduralGeneration",flag);}
    }
    @Test public void transformedDisplacementPreservesLiveSettingsAndFallbacks() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY), flag=System.getProperty("welt.native.proceduralGeneration");
        try {
            System.setProperty("welt.native.proceduralGeneration","true");
            HeightMap source=BitmapWorldCreationBenchmark.proceduralMap("displacement");
            for (float rotation : new float[]{0, .37f, (float)(Math.PI/2)}) {
                compare(new TransformingHeightMap("Warp",source,-1.7f,.65f,31,-47,rotation),-64,320,-1,1,true);
            }
            compare(new TransformingHeightMap("Translation",source,1,1,31,-47,0),0,256,-1,1,true);
            TransformingHeightMap transform=new TransformingHeightMap("Live",source,1.7f,.65f,31,-47,.37f);
            compare(transform,-64,320,1,-1,true);
            transform.setBaseHeightMap(BitmapWorldCreationBenchmark.proceduralMap("displacement"));
            transform.setSeed(-98723);
            compare(transform,-64,320,1,-1,true);
            HeightMap nested=new DisplacementHeightMap("Nested",source,new ConstantHeightMap(.37),new ConstantHeightMap(10));
            compare(new TransformingHeightMap("Unsupported",nested,1.7f,.65f,31,-47,.37f),-64,320,0,0,false);
            compare(transform,-64,320,131073,0,false);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.proceduralGeneration",flag);}
    }

    @Test public void arithmeticAndShapeProgramsPreserveCompleteTiles() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY), flag=System.getProperty("welt.native.proceduralGeneration");
        try {
            System.setProperty("welt.native.proceduralGeneration","true");
            HeightMap noise=BitmapWorldCreationBenchmark.proceduralMap("noise");
            for (HeightMap map : new HeightMap[]{
                    new DifferenceHeightMap(noise,new ConstantHeightMap(10)),
                    new ProductHeightMap(noise,new ConstantHeightMap(.7)),
                    new MinimisingHeightMap(noise,new ConstantHeightMap(90)),
                    new MaximisingHeightMap(noise,new ConstantHeightMap(90)),
                    new BandedHeightMap(31,43,19,177,false),new BandedHeightMap(31,43,19,177,true),
                    new ShelvingHeightMap(noise),new MandelbrotHeightMap()}) {
                compare(map,-64,320,-1,1,true);
            }
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.proceduralGeneration",flag);}
    }
    @Test public void generationWorkersKeepIndependentBuffers() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY), flag=System.getProperty("welt.native.proceduralGeneration");
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            System.setProperty("welt.native.proceduralGeneration","true");
            System.setProperty(Native.GEN_KEY,"false");
            List<Tile> expected=new ArrayList<>();
            for (int i=0;i<8;i++) expected.add(deterministicFactory().createTile(i-4,4-i));
            System.setProperty(Native.GEN_KEY,"true");
            List<java.util.concurrent.Future<Tile>> results=new ArrayList<>();
            for (int i=0;i<8;i++) {
                final int coordinate=i-4;
                results.add(executor.submit(() -> {
                    long before=HeightMapTileFactory.completedNativeGeneratedTiles();
                    Tile tile=deterministicFactory().createTile(coordinate,-coordinate);
                    assertEquals(1,HeightMapTileFactory.completedNativeGeneratedTiles()-before);
                    return tile;
                }));
            }
            for (int i=0;i<8;i++) {
                Tile actual=results.get(i).get(), reference=expected.get(i);
                for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                    assertEquals(Float.floatToIntBits(reference.getHeight(x,y)),Float.floatToIntBits(actual.getHeight(x,y)));
                    assertEquals(reference.getWaterLevel(x,y),actual.getWaterLevel(x,y));
                    assertEquals(reference.getTerrain(x,y),actual.getTerrain(x,y));
                }
            }
        } finally {executor.shutdownNow();restore(Native.GEN_KEY,gen);restore("welt.native.proceduralGeneration",flag);}
    }
    private static HeightMapTileFactory deterministicFactory() {
        var ranges=new TreeMap<Integer,Terrain>();ranges.put(-65,Terrain.GRASS);ranges.put(100,Terrain.STONE);
        return new HeightMapTileFactory(197,BitmapWorldCreationBenchmark.proceduralMap("affine-displacement"),-64,320,false,
                new SimpleTheme(197,62,ranges,null,-64,320,false,false));
    }
    @Test public void unsupportedSourcesAndCoordinatesKeepJavaFallback() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY), flag=System.getProperty("welt.native.proceduralGeneration");
        try {
            System.setProperty("welt.native.proceduralGeneration","true");
            compare(new SlopeHeightMap(new SlopeHeightMap(new ConstantHeightMap(100), 1), 1),-64,320,0,0,false);
            compare(new ConstantHeightMap(100),-64,320,131073,0,false);
            System.setProperty("welt.native.proceduralGeneration","false");
            compare(BitmapWorldCreationBenchmark.proceduralMap("noise"),-64,320,0,0,false);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.proceduralGeneration",flag);}
    }
    private static SimpleTheme theme(int min,int max) {
        var ranges=new TreeMap<Integer,Terrain>();ranges.put(min-1,Terrain.CUSTOM_1);ranges.put(50,Terrain.GRASS);ranges.put(100,Terrain.STONE);
        Map<Filter,Layer> layers=new LinkedHashMap<>();
        layers.put((x,y,z,l)->Math.floorMod(z,16),Resources.INSTANCE);
        layers.put((x,y,z,l)->z>120?200:255,Biome.INSTANCE);
        layers.put((x,y,z,l)->z<50?0:z<130?7:15,Frost.INSTANCE);
        layers.put((x,y,z,l)->z<90?0:z<150?9:15,CHUNK);
        return new SimpleTheme(197,62,ranges,layers,min,max,true,true);
    }
    private static void compare(HeightMap map,int min,int max,int tx,int ty,boolean supported) throws Exception {
        long seed=map.getSeed();
        HeightMapTileFactory java=new HeightMapTileFactory(seed,map,min,max,false,theme(min,max));
        HeightMapTileFactory rust=new HeightMapTileFactory(seed,map,min,max,false,theme(min,max));
        var random=HeightMapImportBenchmark.random();
        random.setSeed(42);System.setProperty(Native.GEN_KEY,"false");Tile expected=java.createTile(tx,ty);long expectedNext=random.nextLong();
        long before=HeightMapTileFactory.completedNativeGeneratedTiles();
        random.setSeed(42);System.setProperty(Native.GEN_KEY,"true");Tile actual=rust.createTile(tx,ty);long actualNext=random.nextLong();
        assertEquals(map.getClass().getSimpleName()+" at "+tx+","+ty, supported?1:0,HeightMapTileFactory.completedNativeGeneratedTiles()-before);
        assertEquals("Random stream after complete generation",expectedNext,actualNext);
        assertEquals(expected.getLayers(),actual.getLayers());
        for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
            assertEquals(Float.floatToIntBits(expected.getHeight(x,y)),Float.floatToIntBits(actual.getHeight(x,y)));
            assertEquals(expected.getWaterLevel(x,y),actual.getWaterLevel(x,y));assertEquals(expected.getTerrain(x,y),actual.getTerrain(x,y));
            assertEquals(expected.getLayerValue(Resources.INSTANCE,x,y),actual.getLayerValue(Resources.INSTANCE,x,y));
            assertEquals(expected.getLayerValue(Biome.INSTANCE,x,y),actual.getLayerValue(Biome.INSTANCE,x,y));
            assertEquals(expected.getBitLayerValue(Frost.INSTANCE,x,y),actual.getBitLayerValue(Frost.INSTANCE,x,y));
            assertEquals(expected.getBitLayerValue(CHUNK,x,y),actual.getBitLayerValue(CHUNK,x,y));
        }
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}
