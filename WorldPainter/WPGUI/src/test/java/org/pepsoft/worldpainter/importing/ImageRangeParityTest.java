package org.pepsoft.worldpainter.importing;

import org.junit.Test;
import org.pepsoft.worldpainter.ImageRangeAccess;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.File;
import static org.junit.Assert.*;

public class ImageRangeParityTest {
    private static void mode(boolean nativeMode) { System.setProperty("welt.native.imageRanges", Boolean.toString(nativeMode)); }
    private static void required() {
        org.junit.Assume.assumeTrue("Optional release image-range JNI", Boolean.getBoolean("welt.test.requireImageRanges"));
        mode(true); System.setProperty("welt.native.floatingImageRanges", "true"); assertTrue(NativeSlices.isImageRangesAvailable());
    }
    private static void equal(double[] expected, double[] actual) {
        assertNotNull(actual); assertEquals(Double.doubleToLongBits(expected[0]), Double.doubleToLongBits(actual[0]));
        assertEquals(Double.doubleToLongBits(expected[1]), Double.doubleToLongBits(actual[1]));
    }
    private static void compare(BufferedImage image, int band, boolean mask) {
        mode(false); double[] expected=BitmapHeightMap.build().withImage(image).withChannel(band).now().getRange();
        mode(true); assertNotNull("Must use typed adapter: " + image.getRaster().getClass().getName() + " / " + image.getRaster().getSampleModel().getClass().getName() + " type=" + image.getRaster().getTransferType() + " size=" + image.getWidth() + "x" + image.getHeight(), ImageRangeAccess.range(image.getRaster(),band,true,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,Double.MAX_VALUE));
        equal(expected,BitmapHeightMap.build().withImage(image).withChannel(band).now().getRange());
        if(mask){mode(false);var java=new MaskImporter(null,new File("fixture.png"),image);double[] range={java.getImageLowValue(),java.getImageHighValue()};
            mode(true);var rust=new MaskImporter(null,new File("fixture.png"),image);equal(range,new double[]{rust.getImageLowValue(),rust.getImageHighValue()});}
    }
    @Test public void completeCallerRangesMatchAcrossStripesAndSubimages() {
        required();for(String kind:new String[]{"byte","ushort","float","double"}){
            BufferedImage image=ImageRangeBenchmark.fixture(kind,257,521);compare(image,0,true);compare(image.getSubimage(7,11,137,173),0,true);
        }
        for(int type:new int[]{BufferedImage.TYPE_3BYTE_BGR,BufferedImage.TYPE_4BYTE_ABGR}){
            BufferedImage image=new BufferedImage(67,89,type);for(int y=0;y<89;y++)for(int x=0;x<67;x++)image.setRGB(x,y,(x*131+y*103)|0x93000000);
            for(int band=0;band<image.getRaster().getNumBands();band++)compare(image.getSubimage(3,7,51,61),band,false);
        }
    }
    @Test public void floatingOrderSignedZeroNanAndEarlyExitStayExact() {
        required();for(String kind:new String[]{"float","double"}){
            BufferedImage image=ImageRangeBenchmark.fixture(kind,2,3);
            double[] data={Double.NaN,-0.0,+0.0,1.0,Double.POSITIVE_INFINITY,-100.0};
            for(int i=0;i<data.length;i++)image.getRaster().setSample(i%2,i/2,0,data[i]);compare(image,0,true);
            for(int i=0;i<data.length;i++)image.getRaster().setSample(i%2,i/2,0,i==1?-0.0:i==0?Double.NaN:+0.0);compare(image,0,true);
            for(int i=0;i<data.length;i++)image.getRaster().setSample(i%2,i/2,0,Double.NaN);compare(image,0,true);
        }
    }
    @Test public void signedShortUnsignedIntAndInitialSeedsArePreserved() {
        required();var shortModel=new ComponentSampleModel(DataBuffer.TYPE_SHORT,3,2,1,3,new int[]{0});
        Raster shortRaster=Raster.createWritableRaster(shortModel,new DataBufferShort(new short[]{-3,0,5,2,-1,9},6),null);
        for(boolean unsigned:new boolean[]{false,true}){
            double[] expected={Double.MAX_VALUE,-Double.MAX_VALUE};for(int x=0;x<3;x++)for(int y=0;y<2;y++){double v=unsigned?(shortRaster.getSample(x,y,0)&0xffffffffL):shortRaster.getSampleDouble(x,y,0);if(v<expected[0])expected[0]=v;if(v>expected[1])expected[1]=v;}
            equal(expected,ImageRangeAccess.range(shortRaster,0,unsigned,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,Double.MAX_VALUE));
        }
        var model=new ComponentSampleModel(DataBuffer.TYPE_INT,3,2,1,3,new int[]{0});
        var raster=Raster.createWritableRaster(model,new DataBufferInt(new int[]{-3,-4,-5,-6,-7,-8},6),null);
        var colours=new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),false,false,Transparency.OPAQUE,DataBuffer.TYPE_INT);
        BufferedImage image=new BufferedImage(colours,raster,false,null);compare(image,0,true);
        assertEquals(Integer.MAX_VALUE,new MaskImporter(null,new File("fixture.png"),image).getImageLowValue(),0.0);
    }
    @Test public void customRasterAndPackedLayoutsFallBack() {
        mode(true);SampleModel model=new ComponentSampleModel(DataBuffer.TYPE_BYTE,3,2,1,3,new int[]{0});
        Raster custom=new WritableRaster(model,new DataBufferByte(6),new Point()){
            @Override public int getSample(int x,int y,int band){return 42;}
            @Override public double getSampleDouble(int x,int y,int band){return 42.0;}
        };
        assertNull(ImageRangeAccess.range(custom,0,true,Double.MAX_VALUE,-Double.MAX_VALUE,0,255));
        BufferedImage packed=new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB);
        assertNull(ImageRangeAccess.range(packed.getRaster(),0,true,Double.MAX_VALUE,-Double.MAX_VALUE,0,255));
    }
    @Test public void customImageDimensionsAndFloatingDefaultUseJava() {
        mode(true);BufferedImage custom=new BufferedImage(3,1,BufferedImage.TYPE_BYTE_GRAY){@Override public int getWidth(){return 2;}};
        custom.getRaster().setSample(0,0,0,5);custom.getRaster().setSample(1,0,0,7);custom.getRaster().setSample(2,0,0,200);
        assertNull(ImageRangeAccess.range(custom,0,true,Double.MAX_VALUE,-Double.MAX_VALUE,0,255));
        equal(new double[]{5,7},BitmapHeightMap.build().withImage(custom).now().getRange());
        var mask=new MaskImporter(null,new File("fixture.png"),custom);equal(new double[]{5,7},new double[]{mask.getImageLowValue(),mask.getImageHighValue()});
        System.setProperty("welt.native.floatingImageRanges","false");
        assertNull(ImageRangeAccess.range(ImageRangeBenchmark.fixture("float",3,2),0,false,Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE,Double.MAX_VALUE));
    }

    @Test public void parallelAnalysesKeepSeedsAndOutputsPrivate() throws Exception {
        required();BufferedImage image=ImageRangeBenchmark.fixture("ushort",257,521);
        mode(false);double[] expected=BitmapHeightMap.build().withImage(image).now().getRange();mode(true);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{java.util.List<java.util.concurrent.Future<?>> tasks=new java.util.ArrayList<>();
            for(int i=0;i<8;i++)tasks.add(pool.submit(()->equal(expected,ImageRangeAccess.range(image,0,true,Double.MAX_VALUE,-Double.MAX_VALUE,0,65535))));
            for(var task:tasks)task.get();
        }finally{pool.shutdownNow();}
    }
    @Test public void bankOffsetsPaddingAndTranslatedChildrenMatch() {
        required();int[] banks={2,0,1},offsets={1,3,2};
        SampleModel model=new BandedSampleModel(DataBuffer.TYPE_BYTE,7,11,9,banks,offsets);
        DataBuffer data=new DataBufferByte(new byte[][]{new byte[256],new byte[256],new byte[256]},100,new int[]{3,7,11});
        WritableRaster raster=Raster.createWritableRaster(model,data,null);
        for(int b=0;b<3;b++)for(int y=0;y<11;y++)for(int x=0;x<7;x++)raster.setSample(x,y,b,b*31+x*11+y*3);
        for(int b=0;b<3;b++){
            double[] expected={Double.MAX_VALUE,-Double.MAX_VALUE};for(int x=0;x<7;x++)for(int y=0;y<11;y++){double v=raster.getSampleDouble(x,y,b);if(v<expected[0])expected[0]=v;if(v>expected[1])expected[1]=v;}
            equal(expected,ImageRangeAccess.range(raster,b,true,Double.MAX_VALUE,-Double.MAX_VALUE,0,255));
            Raster child=raster.createChild(2,3,3,5,-17,29,null);double[] childRange={Double.MAX_VALUE,-Double.MAX_VALUE};
            for(int x=-17;x< -14;x++)for(int y=29;y<34;y++){double v=child.getSampleDouble(x,y,b);if(v<childRange[0])childRange[0]=v;if(v>childRange[1])childRange[1]=v;}
            equal(childRange,ImageRangeAccess.range(child,b,true,Double.MAX_VALUE,-Double.MAX_VALUE,0,255));
        }
    }
    @Test public void unsignedSignedShortMaskStopsOnlyAtExactBounds() {
        required();var model=new ComponentSampleModel(DataBuffer.TYPE_SHORT,2,2,1,2,new int[]{0});
        Raster raster=Raster.createWritableRaster(model,new DataBufferShort(new short[]{0,-1,-5,-6},4),null);
        equal(new double[]{0,4294967295.0},ImageRangeAccess.range(raster,0,true,Integer.MAX_VALUE,Integer.MIN_VALUE,0,65535,true));
    }
    @Test public void bridgeRejectsWrongPrimitiveKindsBeforeBorrowing() throws Exception {
        required();var method=NativeSlices.class.getDeclaredMethod("nativeScanImageRange",Object.class,int[].class,double.class,double.class,double[].class);
        method.setAccessible(true);double[] result={42,43};int[] layout={4,0,0,1,1,0,0,1,1,0,0};
        assertEquals(-1,((Integer)method.invoke(null,new byte[1],layout,0.0,255.0,result)).intValue());equal(new double[]{42,43},result);
        layout[0]=0;assertEquals(-1,((Integer)method.invoke(null,"not an array",layout,0.0,255.0,result)).intValue());equal(new double[]{42,43},result);
    }
    @Test public void invalidStripesNeverMutateResult() {
        required();double[] result={42,43};mode(true);
        assertEquals(-1,NativeSlices.scanImageRange(new byte[1],new int[]{0,1,0,1,1,0,0,2,1,1,0},0,255,result));equal(new double[]{42,43},result);
        assertEquals(-1,NativeSlices.scanImageRange(result,new int[]{5,0,0,1,1,0,0,2,1,1,0},0,255,result));equal(new double[]{42,43},result);
    }
}