package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class CoordinateHeightMapGenerationParityTest {
    private final java.util.Map<String,String> savedProperties=new java.util.HashMap<>();
    @org.junit.Before public void saveNativeProperties() {
        for(String key:new String[]{Native.GEN_KEY,"welt.native.coordinateGraphGeneration","welt.native.proceduralGeneration"})
            savedProperties.put(key,System.getProperty(key));
    }
    @org.junit.After public void restoreNativeProperties() {
        savedProperties.forEach((key,value) -> {if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
    }
    private static void ready(){org.junit.Assume.assumeTrue(Boolean.getBoolean("welt.test.requireCoordinateGraph"));
        System.setProperty("welt.native.coordinateGraphGeneration","true");System.setProperty("welt.native.proceduralGeneration","true");}
    private static HeightMap transform(HeightMap map){return new TransformingHeightMap("Inner",map,1.7f,.65f,31,-47,.37f);}
    private static ByteBuffer lastFrame()throws Exception{var field=NativeTileGenerationAccess.class.getDeclaredField("WORKERS");field.setAccessible(true);
        Object worker=((ThreadLocal<?>)field.get(null)).get();var data=worker.getClass().getDeclaredField("data");data.setAccessible(true);return((ByteBuffer)data.get(worker)).duplicate().order(ByteOrder.LITTLE_ENDIAN);}
    private static void rawAndStorage(HeightMap map,int tx,int ty)throws Exception{
        ProceduralTileGenerationParityTest.compare(map,-64,320,tx,ty,true);ByteBuffer frame=lastFrame();int samples=frame.getInt(112);
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertEquals("Unquantised graph height",Double.doubleToLongBits(map.getHeight(tx*128+x,ty*128+y)),Double.doubleToLongBits(frame.getDouble(samples+(x+y*128)*8)));
    }
    @Test public void composedOperatorsMatchRawSamplesAndAllTilePlanes()throws Exception{ready();
        for(int op=0;op<5;op++)for(boolean transformedFirst:new boolean[]{false,true}){
            HeightMap source=transform(BitmapWorldCreationBenchmark.proceduralMap("noise")),other=new NoiseHeightMap(45,.9,3,63);
            HeightMap left=transformedFirst?source:other,right=transformedFirst?other:source;
            HeightMap map=switch(op){case 0->new SumHeightMap(left,right);case 1->new DifferenceHeightMap(left,right);case 2->new ProductHeightMap(left,right);
                case 3->new MinimisingHeightMap(left,right);default->new MaximisingHeightMap(left,right);};rawAndStorage(map,-1,1);
        }
    }
    @Test public void nestedAffineStatesAndCoordinateDomainsRemainExact()throws Exception{ready();
        for(float angle:new float[]{0,.37f,(float)(Math.PI/2),(float)Math.PI}){
            HeightMap inner=new TransformingHeightMap("Nested",new NoiseHeightMap(130,.7,3,17),-1.7f,.65f,31,-47,angle);
            rawAndStorage(new TransformingHeightMap("Outer",new SumHeightMap(inner,new ConstantHeightMap(7)),.7f,1.3f,-19,11,-.23f),-1,0);
        }
        for(int offset:new int[]{16777217,Integer.MIN_VALUE,Integer.MAX_VALUE}){
            HeightMap integer=new SumHeightMap(new TransformingHeightMap("Integer",new NoiseHeightMap(130,.7,3,17),1,1,offset,-offset,0),new ConstantHeightMap(7));
            rawAndStorage(integer,0,0);
            HeightMap floating=transform(integer);rawAndStorage(floating,-1,1);
        }
        rawAndStorage(new SumHeightMap(transform(new NoiseHeightMap(130,.7,3,17)),new ConstantHeightMap(-0.0)),131073,-131073);
    }
    @Test public void cacheTracksChildAndSeedChanges()throws Exception{ready();var map=new SumHeightMap(transform(new NoiseHeightMap(130,.7,3,17)),new ConstantHeightMap(7));
        rawAndStorage(map,0,0);map.setSeed(-89173);rawAndStorage(map,1,-1);map.setHeightMap1(transform(new NoiseHeightMap(80,1.9,4,-37)));rawAndStorage(map,-1,1);
    }
    @Test public void unsupportedOrNonFiniteGraphsRetainJavaFallback()throws Exception{ready();
        HeightMap custom=new AbstractHeightMap(){public double getHeight(int x,int y){return 50;}public double getHeight(float x,float y){return 50;}
            public javax.swing.Icon getIcon(){return null;}public double[] getRange(){return new double[]{50,50};}};
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(transform(custom),new ConstantHeightMap(7)),0,0,128,128));
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(new TransformingHeightMap("Singular",new NoiseHeightMap(100,1,3,0),0,1,0,0,0),new ConstantHeightMap(7)),0,0,128,128));
        var cyclic=new SumHeightMap(new NoiseHeightMap(100,1,3,0),new ConstantHeightMap(7));cyclic.setHeightMap1(cyclic);assertNull(CoordinateHeightMapSource.prepare(cyclic,0,0,128,128));
        assertNull(CoordinateHeightMapSource.prepare(new NoiseHeightMap(100,1,3,0),0,0,128,128));
    }
    @Test public void invalidProgramsDoNotMutateTheTransaction()throws Exception{ready();rawAndStorage(BitmapWorldCreationBenchmark.proceduralMap("nested-affine"),0,0);
        ByteBuffer source=lastFrame();int length=source.getInt(8),patch=source.getInt(208),count=source.getInt(patch+12);
        ByteBuffer invalid=ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);source.position(0).limit(length);invalid.put(source).position(0);
        invalid.putInt(patch+128+(count-1)*96+4,count);byte[] before=new byte[length];invalid.duplicate().get(before);
        assertFalse(org.pepsoft.worldpainter.nativeapi.NativeSlices.importHeightMapTile(invalid));byte[] after=new byte[length];invalid.duplicate().get(after);assertArrayEquals(before,after);
    }
    @Test public void nestedDeformationPreservesRawHeightsAndStorage() throws Exception {
        ready();
        HeightMap warped=BitmapWorldCreationBenchmark.proceduralMap("nested-warp");
        for(int tx:new int[]{-1,0,1,131073}) rawAndStorage(warped,tx,-tx);
        for(boolean warpedFirst:new boolean[]{false,true}) {
            HeightMap other=transform(new NoiseHeightMap(35,1.3,2,19));
            HeightMap left=warpedFirst?warped:other, right=warpedFirst?other:warped;
            rawAndStorage(new DifferenceHeightMap(left,right),-1,1);
            rawAndStorage(new ProductHeightMap(left,right),1,-1);
            rawAndStorage(new MinimisingHeightMap(left,right),-1,1);
            rawAndStorage(new MaximisingHeightMap(left,right),1,-1);
        }
        warped.setSeed(-173);rawAndStorage(warped,-1,1);
    }
    @Test public void deformationControlsPreserveFloatDomainAndLiveChanges() throws Exception {
        ready();
        var angle=new TransformingHeightMap("Translated angle",new NoiseHeightMap(Math.PI*2,1.3,2,31),1,1,16777217,-16777217,0);
        var displacement=new DisplacementHeightMap(new NoiseHeightMap(130,.7,3,17),angle,new ConstantHeightMap(13));
        var map=new SumHeightMap(displacement,new ConstantHeightMap(7));
        rawAndStorage(map,131073,-131073);
        displacement.setAngleMap(new ConstantHeightMap(-13.7));
        displacement.setDistanceMap(new SumHeightMap(transform(new NoiseHeightMap(30,.7,2,19)),new ConstantHeightMap(-45)));
        rawAndStorage(transform(map),-1,1);
        displacement.setBaseHeightMap(transform(new NoiseHeightMap(90,1.9,4,-7)));
        rawAndStorage(map,-1,1);
        displacement.setAngleMap(new ConstantHeightMap(1.0e20));rawAndStorage(map,-1,1);
    }
    @Test public void hugeDynamicAnglesKeepTheJavaRouteAndRandomState() throws Exception {
        ready();
        HeightMap angle=new SumHeightMap(new ConstantHeightMap(1.0e20),new NoiseHeightMap(7,1.3,2,17));
        HeightMap map=new SumHeightMap(new DisplacementHeightMap(new NoiseHeightMap(130,.7,3,17),angle,new ConstantHeightMap(13)),new ConstantHeightMap(7));
        ProceduralTileGenerationParityTest.compare(map,-64,320,-1,1,false);
    }
    @Test public void invalidThirdChildPreservesNativeFrameAndPreviousCache() throws Exception {
        ready();rawAndStorage(BitmapWorldCreationBenchmark.proceduralMap("nested-warp"),0,0);
        ByteBuffer source=lastFrame();int length=source.getInt(8),patch=source.getInt(208),count=source.getInt(patch+12);
        ByteBuffer invalid=ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        source.position(0).limit(length);invalid.put(source).position(0);
        int node=-1;for(int i=0;i<count;i++)if(invalid.getInt(patch+128+i*96)==9)node=i;
        assertTrue(node>=0);invalid.putInt(patch+128+node*96+40,node);
        byte[] before=new byte[length];invalid.duplicate().get(before);
        assertFalse(org.pepsoft.worldpainter.nativeapi.NativeSlices.importHeightMapTile(invalid));
        byte[] after=new byte[length];invalid.duplicate().get(after);assertArrayEquals(before,after);
        rawAndStorage(BitmapWorldCreationBenchmark.proceduralMap("nested-warp"),1,-1);
    }
    @Test public void fastNoiseCompositionsPreserveEveryNoiseAndFractalMode() throws Exception {
        ready();
        for(var type:org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.NoiseType.values())
            for(var fractal:new org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.FractalType[]{
                    org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.FractalType.None,
                    org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.FractalType.FBm,
                    org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.FractalType.Ridged,
                    org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.FractalType.PingPong}) {
                var noise=new FastNoiseLiteHeightMap(130,.7,3,17);noise.setNoiseType(type);noise.setFractalType(fractal);
                rawAndStorage(new SumHeightMap(transform(noise),new ConstantHeightMap(7)),-1,1);
            }
    }
    @Test public void fastNoiseLiveSettingsAndDomainsRemainExact() throws Exception {
        ready();var noise=new FastNoiseLiteHeightMap(160,.7,3,Long.MAX_VALUE);
        var map=new SumHeightMap(transform(noise),new NoiseHeightMap(45,.9,3,63));
        rawAndStorage(map,-1,1);noise.setSeed(Long.MAX_VALUE);rawAndStorage(map,1,-1);
        noise.setHeight(93.75);noise.setScale(1.9);noise.setOctaves(1);
        noise.setNoiseType(org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.NoiseType.Cellular);
        noise.setFractalType(org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite.FractalType.PingPong);
        rawAndStorage(map,131073,-131073);
        rawAndStorage(new SumHeightMap(new TransformingHeightMap("FNL integer translation",noise,1,1,16777217,Integer.MIN_VALUE,0),new ConstantHeightMap(7)),0,0);
        var uniform=new FastNoiseLiteHeightMap(93,Double.MAX_VALUE,3,17);
        rawAndStorage(new SumHeightMap(transform(uniform),new ConstantHeightMap(7)),0,0);
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(transform(new FastNoiseLiteHeightMap(93,Double.MIN_VALUE,3,17)),new ConstantHeightMap(7)),0,0,128,128));
    }
    @Test public void nestedFastNoiseDeformationKeepsRawSamplesAndAllPlanes() throws Exception {
        ready();var map=BitmapWorldCreationBenchmark.proceduralMap("nested-fnl");
        rawAndStorage(map,-1,1);map.setSeed(-173);rawAndStorage(map,1,-1);
    }
    @Test public void transformedLinearBandsPreserveRawSamplesAndSmoothBandsKeepJava() throws Exception {
        ready();
        for(int first:new int[]{1,31,16777217}){
            var bands=new BandedHeightMap(first,-13.75,19,177.125,false);
            rawAndStorage(new SumHeightMap(transform(bands),new ConstantHeightMap(7)),-1,1);
            rawAndStorage(new SumHeightMap(new TransformingHeightMap("Integer bands",bands,1,1,16777217,Integer.MIN_VALUE,0),new ConstantHeightMap(7)),0,0);
        }
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(transform(new BandedHeightMap(31,43,19,177,true)),new ConstantHeightMap(7)),0,0,128,128));
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(transform(new BandedHeightMap(0,43,19,177,true)),new ConstantHeightMap(7)),0,0,128,128));
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(transform(new BandedHeightMap(Integer.MAX_VALUE,43,19,177,true)),new ConstantHeightMap(7)),0,0,128,128));
    }
    @Test public void transformedMandelbrotKeepsFloatArithmeticAndIterationLimit() throws Exception {
        ready();
        var fractal=new TransformingHeightMap("Fractal",new MandelbrotHeightMap(),150,200,7,-11,.23f);
        rawAndStorage(new SumHeightMap(fractal,new ConstantHeightMap(7)),-1,1);
        rawAndStorage(new DisplacementHeightMap(new SumHeightMap(fractal,new ConstantHeightMap(7)),new ConstantHeightMap(.37),new ConstantHeightMap(13)),0,0);
        rawAndStorage(BitmapWorldCreationBenchmark.proceduralMap("nested-shapes"),-1,1);
    }
    @Test public void coordinateProgramsAndBuffersRemainWorkerLocal() throws Exception {
        ready();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        String gen=System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY,"false");
            Tile[] expected=new Tile[8];
            for(int i=0;i<8;i++) expected[i]=workerFactory(i).createTile(i-4,4-i);
            System.setProperty(Native.GEN_KEY,"true");
            var results=new java.util.ArrayList<java.util.concurrent.Future<Tile>>();
            for(int i=0;i<8;i++) {
                final int index=i;
                results.add(executor.submit(() -> {
                    long before=HeightMapTileFactory.completedNativeGeneratedTiles();
                    var factory=workerFactory(index);
                    Tile tile=factory.createTile(index-4,4-index);
                    assertEquals(1,HeightMapTileFactory.completedNativeGeneratedTiles()-before);
                    ByteBuffer frame=lastFrame(); int samples=frame.getInt(112);
                    for(int y=0;y<128;y++)for(int x=0;x<128;x++) assertEquals(
                            Double.doubleToLongBits(factory.getHeightMap().getHeight((index-4)*128+x,(4-index)*128+y)),
                            Double.doubleToLongBits(frame.getDouble(samples+(x+y*128)*8)));
                    return tile;
                }));
            }
            for(int i=0;i<8;i++) {
                Tile actual=results.get(i).get(30,java.util.concurrent.TimeUnit.SECONDS);
                for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                    assertEquals(Float.floatToIntBits(expected[i].getHeight(x,y)),Float.floatToIntBits(actual.getHeight(x,y)));
                    assertEquals(expected[i].getWaterLevel(x,y),actual.getWaterLevel(x,y));
                    assertEquals(expected[i].getTerrain(x,y),actual.getTerrain(x,y));
                }
            }
        } finally {
            executor.shutdownNow();
            if(gen==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,gen);
        }
    }
    private static HeightMapTileFactory workerFactory(int index) {
        var terrain=new java.util.TreeMap<Integer,Terrain>(); terrain.put(-65,Terrain.GRASS);
        var theme=new SimpleTheme(197,62,terrain,null,-64,320,false,false);
        return new HeightMapTileFactory(197+index,BitmapWorldCreationBenchmark.proceduralMap(
                index%5==0?"nested-shapes":index%5==1?"nested-fnl":index%5==2?"nested-warp":index%5==3?"nested-affine":"nested-translate"),-64,320,false,theme);
    }
    @Test public void samplingMatrixSnapshotCannotChangeTheMap(){var map=new TransformingHeightMap("Matrix",new NoiseHeightMap(100,1,3,0),1.7f,.65f,31,-47,.37f);
        double before=map.getHeight(13,17);double[] matrix=new double[6];assertTrue(map.copySamplingMatrix(matrix));java.util.Arrays.fill(matrix,999);
        assertEquals(Double.doubleToLongBits(before),Double.doubleToLongBits(map.getHeight(13,17)));
    }
}