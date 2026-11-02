package org.pepsoft.worldpainter.exporting;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.*;
import org.junit.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.exporting.HeightMapExporter.Format;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import static org.junit.Assert.*;

/** Real PNG/TIFF samples, cache ownership and compatibility with custom sources. */
public class HeightMapStreamingParityTest {
    private final Map<String,String> properties = new LinkedHashMap<>();
    @Before public void prepare() {
        boolean loaded = NativeLoader.areSlicesAvailable();
        if (Boolean.getBoolean("welt.test.requireStreamingHeightmap")) assertTrue("Release JNI required",loaded);
        org.junit.Assume.assumeTrue(loaded);
        for (String key : new String[]{Native.GEN_KEY,Native.RENDER_KEY,"welt.native.heightmapImage","welt.heightmap.streaming"}) properties.put(key,System.getProperty(key));
        System.setProperty(Native.GEN_KEY,"false");
    }
    @After public void restore() { properties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);}); }
    private static Dimension fixture(int min,int max,int limit,int[] reads) {
        var ranges=new TreeMap<Integer,Terrain>();ranges.put(min-1,Terrain.GRASS);
        var factory=new HeightMapTileFactory(197,new ConstantHeightMap(64),min,max,false,new SimpleTheme(197,62,ranges,null,min,max,false,false));
        var d=new Dimension(new World2(DefaultPlugin.JAVA_ANVIL_1_19,min,max),"Image",197,factory,Dimension.Anchor.NORMAL_DETAIL,false);
        for(int ty=-1;ty<=1;ty++)for(int tx=-1;tx<=1;tx++) {
            if(tx==0&&ty==0)continue;
            Tile tile=reads!=null&&tx==-1&&ty==-1?new Tile(tx,ty,min,max){
                @Override public synchronized int getRawHeight(int x,int y){reads[0]++;return super.getRawHeight(x,y);}
            }:new Tile(tx,ty,min,max);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)tile.setRawHeight(x,y,Math.floorMod((x+tx*128)*193+(y+ty*128)*79+x*y*13,limit));
            d.addTile(tile);
        }
        return d;
    }
    private record Result(BufferedImage image,String description,long calls,long streamed,int sampleFormat){}
    private static Result export(Dimension d,Format format,String extension,boolean streaming,boolean nativeMode)throws Exception {
        System.setProperty("welt.heightmap.streaming",Boolean.toString(streaming));System.setProperty(Native.RENDER_KEY,Boolean.toString(nativeMode));System.setProperty("welt.native.heightmapImage",Boolean.toString(nativeMode));
        Path directory=Path.of("target","heightmap-streaming-work","test");Files.createDirectories(directory);
        Path file=Files.createTempFile(directory,"samples-","."+extension);
        try {
            long calls=HeightMapImageAccess.completedTiles(),count=HeightMapExporter.completedStreamingExports();
            var exporter=new HeightMapExporter(d,format);assertTrue(exporter.exportToFile(file.toFile()));
            calls=HeightMapImageAccess.completedTiles()-calls;count=HeightMapExporter.completedStreamingExports()-count;
            BufferedImage image=null;
            if(format==Format.FLOAT_NORMALISED||format==Format.FLOAT_ONE_TO_ONE) {
                try(var input=ImageIO.createImageInputStream(file.toFile())) {
                    var readers=ImageIO.getImageReaders(input);
                    while(readers.hasNext()) {
                        var reader=readers.next();
                        try {if(reader.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageReader")){reader.setInput(input);image=reader.read(0);break;}}
                        finally {reader.dispose();}
                    }
                }
            } else image=ImageIO.read(file.toFile());
            int sampleFormat=0;
            if(extension.equalsIgnoreCase("tiff"))try(var metadataInput=ImageIO.createImageInputStream(file.toFile())) {
                var metadataReaders=ImageIO.getImageReaders(metadataInput);
                while(metadataReaders.hasNext()) {
                    var reader=metadataReaders.next();
                    try {if(reader.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageReader")) {
                        reader.setInput(metadataInput);
                        TIFFField field=TIFFDirectory.createFromMetadata(reader.getImageMetadata(0)).getTIFFField(BaselineTIFFTagSet.TAG_SAMPLE_FORMAT);
                        sampleFormat=field==null?BaselineTIFFTagSet.SAMPLE_FORMAT_UNSIGNED_INTEGER:field.getAsInt(0);break;
                    }} finally {reader.dispose();}
                }
                assertTrue("TIFF sample format required",sampleFormat>0);
            }
            assertNotNull("Decoded heightmap required",image);return new Result(image,exporter.getFormatDescription(),calls,count,sampleFormat);
        } catch (Exception failure) {
            Files.copy(file,directory.resolve("failed-"+format+"-stream-"+streaming+"."+extension),StandardCopyOption.REPLACE_EXISTING);
            throw new AssertionError("format="+format+" extension="+extension+" streaming="+streaming+" native="+nativeMode,failure);
        } finally {Files.deleteIfExists(file);}
    }
    private static void samples(Dimension d,Format format,BufferedImage reference,BufferedImage actual) {
        assertEquals(reference.getWidth(),actual.getWidth());assertEquals(reference.getHeight(),actual.getHeight());
        assertEquals(reference.getSampleModel().getSampleSize(0),actual.getSampleModel().getSampleSize(0));
        float[] range=d.getHeightRange();
        for(int y=0;y<actual.getHeight();y++)for(int x=0;x<actual.getWidth();x++) {
            Tile tile=d.getTile(d.getLowestX()+x/128,d.getLowestY()+y/128);
            double expected=tile==null?0:switch(format){
                case INTEGER_HIGH_RESOLUTION->tile.getRawHeight(x&127,y&127);
                case INTEGER_LOW_RESOLUTION->tile.getIntHeight(x&127,y&127)-d.getMinHeight();
                case FLOAT_ONE_TO_ONE->tile.getHeight(x&127,y&127);
                case FLOAT_NORMALISED->(tile.getHeight(x&127,y&127)-range[0])/(range[1]-range[0]);
            };
            long bits=Double.doubleToLongBits(actual.getRaster().getSampleDouble(x,y,0));
            assertEquals("Independent sample at "+x+","+y,Double.doubleToLongBits(expected),bits);
            assertEquals(Double.doubleToLongBits(reference.getRaster().getSampleDouble(x,y,0)),bits);
        }
    }
    @Test public void allFormatsAndDepthsPreserveHolesAndNegativeCoordinates()throws Exception {
        for(int scenario=0;scenario<3;scenario++) {
            int min=scenario==0?0:-64,max=scenario==0?256:320;
            Dimension d=fixture(min,max,scenario==1?60000:(max-min-1)*256,null);
            for(Format format:Format.values()) {
                String[] extensions=format==Format.INTEGER_LOW_RESOLUTION||format==Format.INTEGER_HIGH_RESOLUTION
                        ?new HeightMapExporter(d,format).getSupportedFileExtensions().toArray(String[]::new):new String[]{"tiff"};
                for(String extension:extensions) {
                    Result dense=export(d,format,extension,false,false),stream=export(d,format,extension,true,true);
                    assertEquals(0,dense.calls);assertEquals(0,dense.streamed);assertEquals(1,stream.streamed);
                    assertEquals("One native conversion per existing tile",d.getTiles().size(),stream.calls);
                    assertEquals(dense.description,stream.description);assertEquals("TIFF signedness/float format",dense.sampleFormat,stream.sampleFormat);samples(d,format,dense.image,stream.image);
                }
            }
        }
    }
    @Test public void zeroNormalisationAlsoWorksWithTheJavaConverter()throws Exception {
        Dimension d=fixture(-64,320,60000,null);
        for(Tile tile:d.getTiles())for(int y=0;y<128;y++)for(int x=0;x<128;x++)tile.setHeight(x,y,64);
        for(Format format:new Format[]{Format.FLOAT_NORMALISED,Format.FLOAT_ONE_TO_ONE}) {
            Result dense=export(d,format,"tiff",false,false),stream=export(d,format,"tiff",true,false);
            assertEquals(1,stream.streamed);assertEquals(0,stream.calls);samples(d,format,dense.image,stream.image);
        }
    }
    @Test public void customTileRetainsDenseTraversalAndCallbackCount()throws Exception {
        int[] reads={0};Dimension d=fixture(-64,320,60000,reads);
        reads[0]=0;Result dense=export(d,Format.INTEGER_HIGH_RESOLUTION,"png",false,false);int expected=reads[0];
        reads[0]=0;Result fallback=export(d,Format.INTEGER_HIGH_RESOLUTION,"png",true,true);
        assertEquals(0,fallback.streamed);assertTrue(expected>0);assertEquals(expected,reads[0]);samples(d,Format.INTEGER_HIGH_RESOLUTION,dense.image,fallback.image);
    }
    @Test public void returnedRastersSurviveBandReuseAndClose()throws Exception {
        Dimension d=fixture(-64,320,60000,null);System.setProperty("welt.heightmap.streaming","true");System.setProperty(Native.RENDER_KEY,"true");System.setProperty("welt.native.heightmapImage","true");
        ImageWriter writer=ImageIO.getImageWritersByFormatName("png").next();
        HeightMapRenderedImage image=HeightMapRenderedImage.create(d,ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY),writer,Format.INTEGER_HIGH_RESOLUTION,-64,0,1);
        writer.dispose();assertNotNull(image);long before=HeightMapImageAccess.completedTiles();
        Raster first=image.getData(new Rectangle(0,0,384,1));int value=first.getSample(0,0,0);assertEquals(3,HeightMapImageAccess.completedTiles()-before);
        image.getData(new Rectangle(0,64,384,1));assertEquals(3,HeightMapImageAccess.completedTiles()-before);
        Raster hole=image.getData(new Rectangle(0,128,384,1));assertEquals(0,hole.getSample(128,128,0));
        image.getTile(0,2);assertEquals(8,HeightMapImageAccess.completedTiles()-before);assertEquals(value,first.getSample(0,0,0));assertEquals(384L*128*2,image.cachedBytes());
        WritableRaster partial=Raster.createWritableRaster(new ComponentSampleModel(DataBuffer.TYPE_USHORT,200,20,1,200,new int[]{0}),new Point(50,120));
        assertSame(partial,image.copyData(partial));
        for(int y=120;y<140;y++)for(int x=50;x<250;x++){Tile tile=d.getTile(-1+x/128,-1+y/128);assertEquals(tile==null?0:tile.getRawHeight(x&127,y&127),partial.getSample(x,y,0));}
        image.close();image.close();assertEquals(0,image.cachedBytes());assertEquals(value,first.getSample(0,0,0));assertThrows(IllegalStateException.class,()->image.getData(new Rectangle(0,0,1,1)));
    }
    @Test public void unknownImageProvidersKeepTheDenseContract() {
        Dimension d=fixture(-64,320,60000,null);System.setProperty("welt.heightmap.streaming","true");
        ImageWriter unknown=new ImageWriter(null){
            public IIOMetadata getDefaultStreamMetadata(ImageWriteParam p){return null;}
            public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier t,ImageWriteParam p){return null;}
            public IIOMetadata convertStreamMetadata(IIOMetadata m,ImageWriteParam p){return null;}
            public IIOMetadata convertImageMetadata(IIOMetadata m,ImageTypeSpecifier t,ImageWriteParam p){return null;}
            public void write(IIOMetadata m,IIOImage i,ImageWriteParam p)throws IOException{throw new UnsupportedOperationException();}
        };
        assertNull(HeightMapRenderedImage.create(d,ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_USHORT_GRAY),unknown,Format.INTEGER_HIGH_RESOLUTION,-64,0,1));unknown.dispose();
    }
}
