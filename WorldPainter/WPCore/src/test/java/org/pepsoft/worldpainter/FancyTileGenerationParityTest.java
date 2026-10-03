package org.pepsoft.worldpainter;

import java.util.*;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.impl.fancy.FancyTheme;
import static org.junit.Assert.*;

public class FancyTileGenerationParityTest {
    @Test public void climateBoundariesSteepSlopesAndSnowPreserveCompleteTiles() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.fancyGeneration");
        try {
            System.setProperty("welt.native.fancyGeneration","true");
            for(int min:new int[]{0,-64})for(int height:new int[]{57,61,63,80,200}) {
                HeightMap map=new ConstantHeightMap(height);
                FancyTheme theme=new FancyTheme(min,min==0?256:320,62,map,Terrain.CUSTOM_3);
                theme.setForestMap(new ConstantHeightMap(.5));
                theme.setHumidityMap(new ConstantHeightMap(70));
                for(int temperature:new int[]{35,15,-15}) {
                    theme.setTemperatureMap(new ConstantHeightMap(temperature));
                    compare(map,theme,min,min==0?256:320,-1,1,true);
                }
            }
            HeightMap steep=new BandedHeightMap(3,60,3,80,false);
            FancyTheme theme=new FancyTheme(-64,320,62,steep,Terrain.GRASS);
            theme.setForestMap(new ConstantHeightMap(.5));theme.setHumidityMap(new ConstantHeightMap(70));
            theme.setTemperatureMap(new ConstantHeightMap(35));
            compare(steep,theme,-64,320,0,0,true);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.fancyGeneration",flag);}
    }
    @Test public void allSupportedSourceModesAndStockFancyFactoryMatchJava() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.fancyGeneration"),nine=System.getProperty(Native.NINE_PATCH_GEN_KEY);
        try {
            System.setProperty("welt.native.fancyGeneration","true");Native.setNinePatchGenEnabled(true);
            for(String source:new String[]{"noise","fnl","affine","slope","displacement"}) {
                HeightMap map=BitmapWorldCreationBenchmark.proceduralMap(source);
                FancyTheme theme=new FancyTheme(-64,320,62,map,Terrain.GRASS);
                for(int tx:new int[]{-1,0})compare(map,theme,-64,320,tx,-tx,true);
                theme.setHumidityMap(new ConstantHeightMap(30));theme.setTemperatureMap(new ConstantHeightMap(-30));
                compare(map,theme,-64,320,0,0,true);
            }
            HeightMap displaced=BitmapWorldCreationBenchmark.proceduralMap("displacement");
            for (HeightMap maximum : new HeightMap[]{new MaximisingHeightMap(displaced,new ConstantHeightMap(80)),
                    new MaximisingHeightMap(new ConstantHeightMap(80),displaced)}) {
                compare(maximum,new FancyTheme(-64,320,62,maximum,Terrain.GRASS),-64,320,-1,1,true);
            }
            HeightMapTileFactory factory=TileFactoryFactory.createFancyTileFactory(42,Terrain.GRASS,0,256,58,62,false,20f,1);
            compare(factory.getHeightMap(),(FancyTheme)factory.getTheme(),0,256,-1,1,true);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.fancyGeneration",flag);restore(Native.NINE_PATCH_GEN_KEY,nine);}
    }
    @Test public void unsupportedSourcesAndFlagsKeepJavaFallback() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.fancyGeneration");
        try {
            System.setProperty("welt.native.fancyGeneration","true");
            HeightMap map=new ConstantHeightMap(80);
            FancyTheme theme=new FancyTheme(-64,320,62,map,Terrain.GRASS);
            theme.setTemperatureMap(new SlopeHeightMap(new SlopeHeightMap(new ConstantHeightMap(20))));
            compare(map,theme,-64,320,0,0,false);
            theme=new FancyTheme(-64,320,62,map,Terrain.GRASS);
            compare(new ConstantHeightMap(80),theme,-64,320,0,0,false);
            compare(map,theme,-64,320,131073,0,false);
            System.setProperty("welt.native.fancyGeneration","false");
            compare(map,theme,-64,320,0,0,false);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.fancyGeneration",flag);}
    }
    @Test public void concurrentWorkersPreserveIndependentSourceAndOutputBuffers() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.fancyGeneration");
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            System.setProperty("welt.native.fancyGeneration","true");System.setProperty(Native.GEN_KEY,"false");
            List<HeightMapTileFactory> factories=new ArrayList<>();List<Tile> expected=new ArrayList<>();
            for(int i=0;i<8;i++) {
                HeightMap map=BitmapWorldCreationBenchmark.proceduralMap((i&1)==0?"affine":"displacement");
                FancyTheme theme=new FancyTheme(-64,320,62,map,Terrain.GRASS);
                theme.setTemperatureMap(new ConstantHeightMap((i&1)==0?-30:35));
                HeightMapTileFactory f=new HeightMapTileFactory(197,map,-64,320,false,theme);
                factories.add(f);expected.add(f.createTile(i-4,4-i));
            }
            System.setProperty(Native.GEN_KEY,"true");
            List<java.util.concurrent.Future<Tile>> results=new ArrayList<>();
            for(int i=0;i<8;i++) {
                final int index=i;
                results.add(executor.submit(()->{
                    long before=HeightMapTileFactory.completedNativeFancyTiles();
                    Tile tile=factories.get(index).createTile(index-4,4-index);
                    assertEquals(1,HeightMapTileFactory.completedNativeFancyTiles()-before);
                    return tile;
                }));
            }
            for(int i=0;i<8;i++)assertTiles(expected.get(i),results.get(i).get());
        } finally {executor.shutdownNow();restore(Native.GEN_KEY,gen);restore("welt.native.fancyGeneration",flag);}
    }
    @Test public void customRasterCallbacksRetainLegacyCellOrder() throws Exception {
        String gen=System.getProperty(Native.GEN_KEY),flag=System.getProperty("welt.native.fancyGeneration"),batch=System.getProperty("wp.fancyTheme.freshTileBatch");
        var model=new java.awt.image.ComponentSampleModel(java.awt.image.DataBuffer.TYPE_DOUBLE,128,128,1,128,new int[]{0}) {
            int reads;
            @Override public double getSampleDouble(int x,int y,int band,java.awt.image.DataBuffer buffer) {
                reads++;return x==1&&y==0?reads:35;
            }
        };
        var raster=java.awt.image.Raster.createWritableRaster(model,new java.awt.Point(0,0));
        var color=new java.awt.image.ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY),
                false,false,java.awt.Transparency.OPAQUE,java.awt.image.DataBuffer.TYPE_DOUBLE);
        var image=new java.awt.image.BufferedImage(color,raster,false,null);
        HeightMap map=new ConstantHeightMap(80);
        FancyTheme theme=new FancyTheme(-64,320,62,map,Terrain.GRASS);
        theme.setTemperatureMap(BitmapHeightMap.build().withImage(image).now());
        theme.setHumidityMap(new ConstantHeightMap(70));theme.setForestMap(new ConstantHeightMap(.5));
        HeightMapTileFactory factory=new HeightMapTileFactory(197,map,-64,320,false,theme);
        try {
            System.setProperty("wp.fancyTheme.freshTileBatch","false");System.setProperty(Native.GEN_KEY,"false");
            model.reads=0;Tile expected=factory.createTile(0,0);int expectedReads=model.reads;
            System.setProperty("wp.fancyTheme.freshTileBatch","true");System.setProperty(Native.GEN_KEY,"true");
            System.setProperty("welt.native.fancyGeneration","true");
            assertFalse(theme.supportsFreshTileBatch());
            long before=HeightMapTileFactory.completedNativeFancyTiles();model.reads=0;Tile actual=factory.createTile(0,0);
            assertEquals(0,HeightMapTileFactory.completedNativeFancyTiles()-before);
            assertEquals(16384,expectedReads);assertEquals(expectedReads,model.reads);assertTiles(expected,actual);
        } finally {restore(Native.GEN_KEY,gen);restore("welt.native.fancyGeneration",flag);restore("wp.fancyTheme.freshTileBatch",batch);}
    }
    private static void compare(HeightMap map,FancyTheme theme,int min,int max,int tx,int ty,boolean supported) throws Exception {
        HeightMapTileFactory java=new HeightMapTileFactory(197,map,min,max,false,theme);
        HeightMapTileFactory rust=new HeightMapTileFactory(197,map,min,max,false,theme);
        var random=HeightMapImportBenchmark.random();
        System.setProperty(Native.GEN_KEY,"false");random.setSeed(42);Tile expected=java.createTile(tx,ty);long expectedNext=random.nextLong();
        long before=HeightMapTileFactory.completedNativeFancyTiles();
        System.setProperty(Native.GEN_KEY,"true");random.setSeed(42);Tile actual=rust.createTile(tx,ty);long actualNext=random.nextLong();
        assertEquals(map.getClass().getSimpleName(),supported?1:0,HeightMapTileFactory.completedNativeFancyTiles()-before);
        assertEquals(expectedNext,actualNext);
        assertTiles(expected,actual);
    }
    static void assertTiles(Tile expected,Tile actual) {
        assertEquals(expected.getLayers(),actual.getLayers());
        for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
            assertEquals("Height",Float.floatToRawIntBits(expected.getHeight(x,y)),Float.floatToRawIntBits(actual.getHeight(x,y)));
            assertEquals("Water",expected.getWaterLevel(x,y),actual.getWaterLevel(x,y));
            assertEquals("Terrain",expected.getTerrain(x,y),actual.getTerrain(x,y));
            for(Layer layer:expected.getLayers())if(layer.getDataSize()==Layer.DataSize.BIT||layer.getDataSize()==Layer.DataSize.BIT_PER_CHUNK)
                assertEquals(layer.getName(),expected.getBitLayerValue(layer,x,y),actual.getBitLayerValue(layer,x,y));
            else assertEquals(layer.getName(),expected.getLayerValue(layer,x,y),actual.getLayerValue(layer,x,y));
        }
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}
