package org.pepsoft.worldpainter;

import com.twelvemonkeys.imageio.util.ImageTypeSpecifiers;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.pepsoft.worldpainter.exporting.HeightMapExporter.Format;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import static org.junit.Assert.*;

public class HeightMapImageRasterParityTest {
    private String previousRender, previousImage;
    @Before public void rememberProperties() {
        previousRender=System.getProperty("wp.native.render");
        previousImage=System.getProperty("welt.native.heightmapImage");
    }
    @After public void restoreProperties() {
        restore("wp.native.render",previousRender);
        restore("welt.native.heightmapImage",previousImage);
    }
    private static void restore(String key,String value) {
        if(value==null)System.clearProperty(key); else System.setProperty(key,value);
    }
    private static void enable() {
        System.setProperty("wp.native.render", "true");
        System.setProperty("welt.native.heightmapImage", "true");
    }
    private static void compare(int min, int max) {
        Tile tile = new Tile(-3, 2, min, max);
        for (int y=0; y<128; y++) for (int x=0; x<128; x++) {
            int raw = Math.floorMod(x*193+y*79+x*y*13, (max-min-1)*256);
            tile.setRawHeight(x, y, raw);
        }
        for (Format format : Format.values()) {
            boolean floats = format == Format.FLOAT_NORMALISED || format == Format.FLOAT_ONE_TO_ONE;
            var image = ImageTypeSpecifiers.createGrayscale(32, floats ? DataBuffer.TYPE_FLOAT : DataBuffer.TYPE_INT)
                    .createBufferedImage(132, 134);
            WritableRaster raster = image.getRaster();
            long before = HeightMapImageAccess.completedTiles();
            assertTrue(HeightMapImageAccess.writeTile(tile, raster, 2, 3, format, min, -7.25f, 31.75f));
            assertTrue(HeightMapImageAccess.completedTiles() > before);
            for (int y=0; y<134; y++) for (int x=0; x<132; x++) {
                double expected = 0;
                if (x>=2 && x<130 && y>=3 && y<131) expected = switch(format) {
                    case INTEGER_HIGH_RESOLUTION -> tile.getRawHeight(x-2,y-3);
                    case INTEGER_LOW_RESOLUTION -> tile.getIntHeight(x-2,y-3)-min;
                    case FLOAT_ONE_TO_ONE -> tile.getHeight(x-2,y-3);
                    case FLOAT_NORMALISED -> (tile.getHeight(x-2,y-3)+7.25f)/31.75f;
                };
                assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(raster.getSampleDouble(x,y,0)));
            }
        }
    }
    @Test public void preservesShortTallAndNegativeHeightPlanes() {
        enable(); try { compare(0,256); compare(-64,320); compare(-2032,2032); }
        finally { System.clearProperty("welt.native.heightmapImage"); System.clearProperty("wp.native.render"); }
    }
    @Test public void keepsScratchIndependentAcrossWorkers() throws Exception {
        enable(); var workers=Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0;i<4;i++) { final int min=-64-i*64; jobs.add(workers.submit(()->compare(min,320))); }
            for(var job:jobs)job.get();
        } finally { workers.shutdownNow(); System.clearProperty("welt.native.heightmapImage"); System.clearProperty("wp.native.render"); }
    }
    @Test public void preservesZeroScaleAndDisabledFallback() {
        enable(); try {
            Tile tile = new Tile(0,0,64,320);
            tile.setRawHeight(1,0,256);
            var raster = ImageTypeSpecifiers.createGrayscale(32,DataBuffer.TYPE_FLOAT).createBufferedImage(128,128).getRaster();
            assertTrue(HeightMapImageAccess.writeTile(tile,raster,0,0,Format.FLOAT_NORMALISED,64,64,0));
            assertTrue(Float.isNaN(raster.getSampleFloat(0,0,0)));
            assertEquals(Float.POSITIVE_INFINITY,raster.getSampleFloat(1,0,0),0);
            System.setProperty("wp.native.render","false");
            long before=HeightMapImageAccess.completedTiles();
            assertFalse(HeightMapImageAccess.writeTile(tile,raster,0,0,Format.FLOAT_NORMALISED,64,64,0));
            assertEquals(before,HeightMapImageAccess.completedTiles());
        } finally { System.clearProperty("welt.native.heightmapImage"); System.clearProperty("wp.native.render"); }
    }
    @Test public void rejectsCustomTilesAndMalformedFramesBeforeRasterWrites() {
        enable(); try {
            var raster = ImageTypeSpecifiers.createGrayscale(32,DataBuffer.TYPE_INT).createBufferedImage(128,128).getRaster();
            Tile tile = new Tile(0,0,0,256) { };
            assertFalse(HeightMapImageAccess.writeTile(tile,raster,0,0,Format.INTEGER_HIGH_RESOLUTION,0,0,1));
            ByteBuffer frame=ByteBuffer.allocateDirect(65568).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(0,0x45494857).putInt(4,1).putInt(8,16384).putInt(12,4).putInt(32,12345);
            assertFalse(NativeSlices.convertHeightmapImage(frame)); assertEquals(12345,frame.getInt(32));
        } finally { System.clearProperty("welt.native.heightmapImage"); System.clearProperty("wp.native.render"); }
    }
}
