package org.pepsoft.worldpainter;

import java.awt.image.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import org.junit.*;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class BitmapGraphGenerationParityTest {
    private final HashMap<String,String> properties=new HashMap<>();
    @Before public void enable() {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("welt.test.requireBitmapGraph"));
        for(String key:new String[]{Native.GEN_KEY,"welt.native.coordinateGraphGeneration","welt.native.proceduralGeneration"}) {
            properties.put(key,System.getProperty(key));System.setProperty(key,"true");
        }
    }
    @After public void restore() { properties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);}); }
    private static ByteBuffer frame() throws Exception {
        var field=NativeTileGenerationAccess.class.getDeclaredField("WORKERS");field.setAccessible(true);
        Object worker=((ThreadLocal<?>)field.get(null)).get();var data=worker.getClass().getDeclaredField("data");data.setAccessible(true);
        return ((ByteBuffer)data.get(worker)).duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void compare(HeightMap map,int tx,int ty) throws Exception {
        ProceduralTileGenerationParityTest.compare(map,-64,320,tx,ty,true);
        ByteBuffer frame=frame();int samples=frame.getInt(112),program=frame.getInt(208);
        assertEquals(4,frame.getInt(program+4));
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)assertEquals("Raw bitmap expression height at "+x+","+y,
            Double.doubleToLongBits(map.getHeight(tx*128+x,ty*128+y)),Double.doubleToLongBits(frame.getDouble(samples+(x+y*128)*8)));
    }
    private static HeightMap transformed(HeightMap map) { return new TransformingHeightMap("Domain",map,1.7f,.65f,31,-47,.37f); }
    @Test public void allPrecisionsKeepRawHeightsAndAllTilePlanes() throws Exception {
        for(int type:new int[]{DataBuffer.TYPE_BYTE,DataBuffer.TYPE_USHORT,DataBuffer.TYPE_SHORT,DataBuffer.TYPE_INT,DataBuffer.TYPE_FLOAT,DataBuffer.TYPE_DOUBLE}) {
            var image=GraphBitmapWindowsTest.image(type);image.getRaster().setSample(1,0,0,13);
            var bitmap=BitmapHeightMap.build().withImage(image).withRepeat(true).now();
            compare(new SumHeightMap(transformed(new BicubicHeightMap(bitmap)),new NoiseHeightMap(45,.9,3,63)),-1,1);
        }
        compare(BitmapWorldCreationBenchmark.proceduralMap("image-noise"),-1,1);
    }
    @Test public void currentPixelsChannelsAndSeedsReplacePreviousTransactionValues() throws Exception {
        var image=new BufferedImage(31,17,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<17;y++)for(int x=0;x<31;x++)image.setRGB(x,y,0xff000000|((x*7)&255)<<16|((y*11)&255)<<8|((x+y)*3&255));
        for(int channel:new int[]{0,1,2,3}) {
            var bitmap=BitmapHeightMap.build().withImage(image).withChannel(channel).withRepeat(true).now();
            HeightMap map=new SumHeightMap(transformed(new BicubicHeightMap(bitmap)),new NoiseHeightMap(45,.9,3,63));
            compare(map,0,0);image.setRGB(0,0,0x427190ab);compare(map,0,0);map.setSeed(-89173);compare(map,1,-1);
        }
    }
    @Test public void boundedAndRepeatedEdgesSharedLeavesAndDeformationAreExact() throws Exception {
        var bitmap=BitmapHeightMap.build().withImage(GraphBitmapWindowsTest.image(DataBuffer.TYPE_USHORT)).now();
        for(boolean repeat:new boolean[]{false,true})compare(new SumHeightMap(transformed(new BicubicHeightMap(bitmap,repeat)),new ConstantHeightMap(7)),-1,1);
        compare(new SumHeightMap(new TransformingHeightMap("A",bitmap,1,1,-17,31,0),new TransformingHeightMap("B",bitmap,1,1,19,-7,0)),0,0);
        var second=BitmapHeightMap.build().withImage(GraphBitmapWindowsTest.image(DataBuffer.TYPE_BYTE)).withRepeat(true).now();
        compare(new DifferenceHeightMap(transformed(new BicubicHeightMap(bitmap)),transformed(new BicubicHeightMap(second))),0,0);
        ByteBuffer multi=frame();assertEquals(2,multi.getInt(multi.getInt(208)+40));        var repeated=BitmapHeightMap.build().withImage(GraphBitmapWindowsTest.image(DataBuffer.TYPE_BYTE)).withRepeat(true).now();
        compare(new SumHeightMap(new DisplacementHeightMap(new BicubicHeightMap(repeated),new ConstantHeightMap(.37),new NoiseHeightMap(13,.9,2,7)),new ConstantHeightMap(7)),-1,1);
    }
    @Test public void invalidPixelDescriptorDoesNotMutateTheTransaction() throws Exception {
        compare(BitmapWorldCreationBenchmark.proceduralMap("image-noise"),0,0);
        ByteBuffer source=frame();int length=source.getInt(8),program=source.getInt(208),table=program+source.getInt(program+36);
        ByteBuffer invalid=ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);source.position(0).limit(length);invalid.put(source).position(0);
        invalid.putInt(table+20,-1);byte[] before=new byte[length];invalid.duplicate().get(before);
        assertFalse(org.pepsoft.worldpainter.nativeapi.NativeSlices.importHeightMapTile(invalid));byte[] after=new byte[length];invalid.duplicate().get(after);assertArrayEquals(before,after);
        compare(BitmapWorldCreationBenchmark.proceduralMap("image-noise"),1,-1);
    }
    @Test public void previousCompilerBytesCannotLeakIntoImageDescriptors() throws Exception {
        var field=CoordinateHeightMapSource.class.getDeclaredField("COMPILERS");field.setAccessible(true);
        Object compiler=((ThreadLocal<?>)field.get(null)).get();var bytes=compiler.getClass().getDeclaredField("bytes");bytes.setAccessible(true);
        java.util.Arrays.fill((byte[])bytes.get(compiler),(byte)0x5a);
        compare(BitmapWorldCreationBenchmark.proceduralMap("image-noise"),0,0);
        ByteBuffer data=frame();int program=data.getInt(208),table=program+data.getInt(program+36),count=data.getInt(program+40);
        for(int i=0;i<count;i++)assertEquals(0,data.getInt(table+i*32+28));
    }
    @Test public void workerCompilerReleasesImageReferencesOnSuccessAndFallback() throws Exception {
        var field=CoordinateHeightMapSource.class.getDeclaredField("COMPILERS");field.setAccessible(true);
        Object compiler=((ThreadLocal<?>)field.get(null)).get();var images=compiler.getClass().getDeclaredField("bitmaps");images.setAccessible(true);
        assertNotNull(CoordinateHeightMapSource.prepare(BitmapWorldCreationBenchmark.proceduralMap("image-noise"),0,0,128,128));
        assertTrue(((java.util.Map<?,?>)images.get(compiler)).isEmpty());
        var bitmap=BitmapHeightMap.build().withImage(GraphBitmapWindowsTest.image(DataBuffer.TYPE_BYTE)).withRepeat(true).now();
        HeightMap huge=new SumHeightMap(new TransformingHeightMap("Huge",bitmap,.000001f,.000001f,0,0,0),new ConstantHeightMap(7));
        assertNull(CoordinateHeightMapSource.prepare(huge,0,0,128,128));
        assertTrue(((java.util.Map<?,?>)images.get(compiler)).isEmpty());
        HeightMap unsupported=new SlopeHeightMap(new NoiseHeightMap(45,.9,2,63),1.75f);
        assertNull(CoordinateHeightMapSource.prepare(new SumHeightMap(bitmap,unsupported),0,0,128,128));
        assertTrue(((java.util.Map<?,?>)images.get(compiler)).isEmpty());
    }
    @Test public void oversizedWindowsRetainTheCompleteJavaRoute() throws Exception {
        var bitmap=BitmapHeightMap.build().withImage(GraphBitmapWindowsTest.image(DataBuffer.TYPE_BYTE)).withRepeat(true).now();
        HeightMap map=new SumHeightMap(new TransformingHeightMap("Huge",new BicubicHeightMap(bitmap),.000001f,.000001f,0,0,0),new ConstantHeightMap(7));
        assertNull(CoordinateHeightMapSource.prepare(map,0,0,128,128));
        ProceduralTileGenerationParityTest.compare(map,-64,320,0,0,false);
    }
}
