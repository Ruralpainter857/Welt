package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Filter;
import static org.junit.Assert.*;

public class BitmapTileGenerationParityTest {
    private static final Layer CHUNK = new Layer("welt.test.generation.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) { };
    @Test public void freshTilesPreserveStorageMaterialsLayersAndRandomStream() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY), flag=System.getProperty("welt.native.bitmapGeneration");
        try {
            System.setProperty("welt.native.bitmapGeneration","true");
            for(int min:new int[]{0,-64}) {
                BufferedImage image=new BufferedImage(96,80,BufferedImage.TYPE_USHORT_GRAY);
                for(int y=0;y<80;y++)for(int x=0;x<96;x++)image.getRaster().setSample(x,y,0,(x*193+y*79+x*y*13)&255);
                BitmapHeightMap bitmap=BitmapHeightMap.build().withImage(image).now();
                for(HeightMap map:new HeightMap[]{bitmap,new BicubicHeightMap(bitmap,true),new BicubicHeightMap(bitmap),
                        new TransformingHeightMap("Affine",new BicubicHeightMap(bitmap,true),1.7f,.65f,31,-47,.37f),
                        new TransformingHeightMap("Negative",new BicubicHeightMap(bitmap),-1.7f,.65f,31,-47,.37f),
                        new TransformingHeightMap("Translation",bitmap,1,1,31,-47,0)}) {
                    for(int tx:new int[]{-1,0}) compare(map,min,min==0?256:320,tx,-tx,true);
                    image.getRaster().setSample(0,0,0,177);
                    compare(map,min,min==0?256:320,0,0,true);
                }
            }
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.bitmapGeneration",flag);}
    }
    @Test public void unsupportedFlagsAndFootprintsPreserveJavaFallback() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.bitmapGeneration");
        try {
            System.setProperty("welt.native.bitmapGeneration","true");
            HeightMap map=BitmapWorldCreationBenchmark.map();
            TransformingHeightMap large=new TransformingHeightMap("Large",((TransformingHeightMap)map).getBaseHeightMap(),.01f,.01f,0,0,.37f);
            compare(large,-64,320,0,0,false);
            compare(map,-64,320,Integer.MAX_VALUE,0,false);
            BicubicHeightMap replaced = new BicubicHeightMap(BitmapHeightMap.build().withImage(new BufferedImage(96,80,BufferedImage.TYPE_USHORT_GRAY)).now(), true);
            replaced.replace(0, BitmapHeightMap.build().withImage(new BufferedImage(64,48,BufferedImage.TYPE_USHORT_GRAY)).now());
            compare(new TransformingHeightMap("Replaced extent", replaced, 1.7f, .65f, 31, -47, .37f), -64, 320, 0, 0, false);
            System.setProperty("welt.native.bitmapGeneration","false");
            compare(map,-64,320,0,0,false);
        }finally{restore(Native.GEN_KEY,gen);restore("welt.native.bitmapGeneration",flag);}
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
        HeightMapTileFactory java=new HeightMapTileFactory(197,map,min,max,false,theme(min,max));
        HeightMapTileFactory rust=new HeightMapTileFactory(197,map,min,max,false,theme(min,max));
        var random=HeightMapImportBenchmark.random();
        random.setSeed(42);System.setProperty(Native.GEN_KEY,"false");Tile expected=java.createTile(tx,ty);long expectedNext=random.nextLong();
        long before=HeightMapTileFactory.completedNativeBitmapTiles();
        random.setSeed(42);System.setProperty(Native.GEN_KEY,"true");Tile actual=rust.createTile(tx,ty);long actualNext=random.nextLong();
        assertEquals(supported?1:0,HeightMapTileFactory.completedNativeBitmapTiles()-before);
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
