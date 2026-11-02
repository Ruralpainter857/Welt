package org.pepsoft.worldpainter;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import static org.junit.Assert.*;

public class GraphBitmapWindowsTest {
    static BufferedImage image(int type) {
        int width=7,height=5,bits=DataBuffer.getDataTypeSize(type);
        var model=new ComponentSampleModel(type,width,height,1,width,new int[]{0});
        DataBuffer data=switch(type) {
            case DataBuffer.TYPE_BYTE->new DataBufferByte(width*height);
            case DataBuffer.TYPE_USHORT->new DataBufferUShort(width*height);
            case DataBuffer.TYPE_SHORT->new DataBufferShort(width*height);
            case DataBuffer.TYPE_INT->new DataBufferInt(width*height);
            case DataBuffer.TYPE_FLOAT->new DataBufferFloat(width*height);
            case DataBuffer.TYPE_DOUBLE->new DataBufferDouble(width*height);
            default->throw new AssertionError();
        };
        var raster=Raster.createWritableRaster(model,data,null);
        var colours=new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),new int[]{bits},false,false,Transparency.OPAQUE,type);
        BufferedImage image=new BufferedImage(colours,raster,false,null);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)raster.setSample(x,y,0,(x*3+y*5-4)*1.25);
        if(type==DataBuffer.TYPE_INT)raster.setSample(0,0,0,-1);
        if(type==DataBuffer.TYPE_FLOAT||type==DataBuffer.TYPE_DOUBLE){raster.setSample(0,0,0,-0.0);raster.setSample(1,0,0,Double.NaN);}
        return image;
    }
    private static ByteBuffer packet(GraphBitmapWindows.Window window) {
        ByteBuffer data=ByteBuffer.allocate(window.bytes()).order(ByteOrder.LITTLE_ENDIAN);window.write(data,0);return data;
    }
    private static double read(GraphBitmapWindows.Window window,ByteBuffer data,int x,int y) {
        assertTrue(x>=window.x()&&x<window.x()+window.width());assertTrue(y>=window.y()&&y<window.y()+window.height());
        int at=((x-window.x())+(y-window.y())*window.width())*window.stride();
        return switch(window.kind()) {
            case 1->data.get(at)&255;case 2->data.getShort(at)&65535;case 3->data.getShort(at);
            case 4->data.getInt(at)&0xffffffffL;case 5->data.getFloat(at);case 6->data.getDouble(at);default->throw new AssertionError();
        };
    }
    @Test public void typedWindowsPreservePrecisionWrappingOutsideValuesAndLiveChanges() {
        for(int type:new int[]{DataBuffer.TYPE_BYTE,DataBuffer.TYPE_USHORT,DataBuffer.TYPE_SHORT,DataBuffer.TYPE_INT,DataBuffer.TYPE_FLOAT,DataBuffer.TYPE_DOUBLE})
            for(boolean repeat:new boolean[]{false,true}) {
                BufferedImage source=image(type);
                var bitmap=BitmapHeightMap.build().withImage(source).withRepeat(repeat).now();
                var windows=GraphBitmapWindows.prepare(bitmap,-2,-2,11,9);assertNotNull(windows);
                var window=windows.get(bitmap);assertNotNull(window);ByteBuffer data=packet(window);
                for(int y=-2;y<7;y++)for(int x=-2;x<9;x++)assertEquals(Double.doubleToLongBits(bitmap.getHeight(x,y)),Double.doubleToLongBits(read(window,data,x,y)));
                source.getRaster().setSample(2,2,0,17);data=packet(window);
                assertEquals(Double.doubleToLongBits(bitmap.getHeight(2,2)),Double.doubleToLongBits(read(window,data,2,2)));
                assertEquals(11*9*window.stride(),window.bytes());
            }
    }
    @Test public void affineBicubicWindowContainsEveryJavaLatticeNeighbour() {
        var source=new BufferedImage(768,512,BufferedImage.TYPE_USHORT_GRAY);
        for(int y=0;y<512;y++)for(int x=0;x<768;x++)source.getRaster().setSample(x,y,0,(x*3+y*7)&127);
        var bitmap=BitmapHeightMap.build().withImage(source).now();
        var transform=new TransformingHeightMap("Image",new BicubicHeightMap(bitmap),1.7f,.65f,-173,-211,.37f);
        HeightMap root=new SumHeightMap(transform,new NoiseHeightMap(45,.9,3,63));
        double[] matrix=new double[6];assertTrue(transform.copySamplingMatrix(matrix));AffineTransform affine=new AffineTransform(matrix);
        for(int tile:new int[]{-2,-1,0,1}) {
            var windows=GraphBitmapWindows.prepare(root,tile*128,-tile*128,128,128);assertNotNull(windows);
            var window=windows.get(bitmap);assertTrue((long)window.width()*window.height()<=GraphBitmapWindows.MAX_CELLS);
            ByteBuffer data=packet(window);Point2D.Float point=new Point2D.Float(),mapped=new Point2D.Float();
            for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                point.setLocation(tile*128+x,-tile*128+y);affine.transform(point,mapped);
                int ix=(int)Math.floor(mapped.x-Math.signum(mapped.x)/2),iy=(int)Math.floor(mapped.y-Math.signum(mapped.y)/2);
                for(int dy=-1;dy<=2;dy++)for(int dx=-1;dx<=2;dx++) {
                    int sx=Math.max(0,Math.min(767,ix+dx)),sy=Math.max(0,Math.min(511,iy+dy));
                    assertEquals(Double.doubleToLongBits(bitmap.getHeight(sx,sy)),Double.doubleToLongBits(read(window,data,sx,sy)));
                }
            }
        }
    }
    @Test public void sharedLeavesUseOneUnionWindowAndOversizedDomainsKeepFallback() {
        var bitmap=BitmapHeightMap.build().withImage(image(DataBuffer.TYPE_BYTE)).now();
        HeightMap root=new SumHeightMap(new TransformingHeightMap("A",bitmap,1,1,3,4,0),new TransformingHeightMap("B",bitmap,1,1,-7,-5,0));
        var windows=GraphBitmapWindows.prepare(root,0,0,128,128);assertNotNull(windows);assertEquals(1,windows.size());
        var window=windows.get(bitmap);assertEquals(-3,window.x());assertEquals(7+127,window.x()+window.width()-1);
        assertNull(GraphBitmapWindows.prepare(new TransformingHeightMap("Huge",bitmap,.000001f,.000001f,0,0,0),0,0,128,128));
        assertNull(GraphBitmapWindows.prepare(bitmap,0,0,4097,1));
        assertNull(GraphBitmapWindows.prepare(bitmap,Integer.MAX_VALUE-2,0,128,128));
    }
    @Test public void unsupportedMapsAndChangedExtentsAreNotSampled() {
        HeightMap custom=new AbstractHeightMap(){public double getHeight(int x,int y){throw new AssertionError("Unexpected callback");}public double getHeight(float x,float y){throw new AssertionError("Unexpected callback");}public javax.swing.Icon getIcon(){return null;}public double[] getRange(){throw new AssertionError("Unexpected callback");}};
        assertNull(GraphBitmapWindows.prepare(custom,0,0,128,128));
        var bitmap=BitmapHeightMap.build().withImage(image(DataBuffer.TYPE_BYTE)).now();bitmap.getExtent().x=1;
        assertNull(GraphBitmapWindows.prepare(bitmap,0,0,128,128));
    }
}
