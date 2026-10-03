package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.List;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class BitmapPreviewParityTest {
    @Test public void exactHeightsMatchLiveRepeatedAndExtendedImages() {
        String previous = System.getProperty(Native.GEN_KEY);
        System.setProperty(Native.GEN_KEY, "true");
        try {
            for (int type : new int[] {BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_GRAY,
                    BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_BYTE_INDEXED}) {
                BufferedImage image = image(type);
                for (boolean repeat : new boolean[] {true, false}) {
                    BitmapHeightMap bitmap = BitmapHeightMap.build().withImage(image).withChannel(0).now();
                    TransformingHeightMap map = new TransformingHeightMap("Parity", new BicubicHeightMap(bitmap, repeat), 1.7f, .65f, 31, -47, .37f);
                    BitmapPreviewAccess access = new BitmapPreviewAccess();
                    for (int shift : new int[] {0, 1}) for (int origin : new int[] {-128, 0, 128}) compare(access, map, origin, -origin, shift);
                    image.getRaster().setSample(0, 0, 0, 7);
                    compare(access, map, 0, 0, 0);
                    map.setBaseHeightMap(new BicubicHeightMap(BitmapHeightMap.build().withImage(image(BufferedImage.TYPE_USHORT_GRAY)).now(), repeat));
                    compare(access, map, 0, 0, 0);
                }
            }
        } finally { restore(previous); }
    }
    @Test public void floatDoubleAndUnsignedIntegerSamplesKeepExactHeights() {
        String previous = System.getProperty(Native.GEN_KEY);
        System.setProperty(Native.GEN_KEY, "true");
        try {
            for (int type : new int[] {java.awt.image.DataBuffer.TYPE_FLOAT, java.awt.image.DataBuffer.TYPE_DOUBLE, java.awt.image.DataBuffer.TYPE_INT}) {
                java.awt.image.ComponentSampleModel model = new java.awt.image.ComponentSampleModel(type, 64, 48, 1, 64, new int[] {0});
                java.awt.image.WritableRaster raster = java.awt.image.Raster.createWritableRaster(model, model.createDataBuffer(), null);
                java.awt.image.ComponentColorModel colours = new java.awt.image.ComponentColorModel(
                        java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY), false, false, java.awt.Transparency.OPAQUE, type);
                BufferedImage image = new BufferedImage(colours, raster, false, null);
                for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) {
                    double value = type == java.awt.image.DataBuffer.TYPE_INT ? (int) (x * 134217728 + y * 1048577)
                            : (x - 32) * .37 + (y - 24) * .19;
                    raster.setSample(x, y, 0, value);
                }
                raster.setSample(0, 0, 0, -0.0);
                for (boolean repeat : new boolean[] {true, false}) {
                    TransformingHeightMap map = new TransformingHeightMap("Numeric", new BicubicHeightMap(
                            BitmapHeightMap.build().withImage(image).now(), repeat), 1.7f, .65f, 31, -47, .37f);
                    compare(new BitmapPreviewAccess(), map, -128, -128, 0);
                    compare(new BitmapPreviewAccess(), map, 0, 0, 0);
                }
            }
        } finally { restore(previous); }
    }

    @Test public void oversizedAndUnsupportedPathsLeaveOutputUnchanged() {
        String previous = System.getProperty(Native.GEN_KEY);
        System.setProperty(Native.GEN_KEY, "true");
        try {
            BicubicHeightMap bitmap = new BicubicHeightMap(BitmapHeightMap.build().withImage(image(BufferedImage.TYPE_USHORT_GRAY)).now(), true);
            BitmapPreviewAccess access = new BitmapPreviewAccess();
            double[] values = new double[16384]; java.util.Arrays.fill(values, 73);
            assertFalse(access.fill(new TransformingHeightMap("Large", bitmap, .01f, .01f, 0, 0, .3f), 0, 0, 0, values));
            assertFalse(access.fill(new TransformingHeightMap("Translation", bitmap, 1, 1, 7, -11, 0), 0, 0, 0, values));
            assertFalse(access.fill(new ConstantHeightMap(42), 0, 0, 0, values));
            assertFalse(access.fill(null, 0, 0, 0, values));
            for (double value : values) assertEquals(73, value, 0);
        } finally { restore(previous); }
    }
    @Test public void independentWorkersPreserveExactResults() throws Exception {
        String previous = System.getProperty(Native.GEN_KEY);
        System.setProperty(Native.GEN_KEY, "true");
        var pool = Executors.newFixedThreadPool(3);
        try {
            TransformingHeightMap map = new TransformingHeightMap("Concurrent", new BicubicHeightMap(
                    BitmapHeightMap.build().withImage(image(BufferedImage.TYPE_USHORT_GRAY)).now(), true), 1.7f, .65f, 31, -47, .37f);
            Callable<Boolean> work = () -> { BitmapPreviewAccess access = new BitmapPreviewAccess();
                for (int i = 0; i < 3; i++) compare(access, map, i * 128 - 128, -128, 0); return true; };
            for (var result : pool.invokeAll(List.of(work, work, work))) assertTrue(result.get());
        } finally { pool.shutdownNow(); restore(previous); }
    }
    private static BufferedImage image(int type) {
        BufferedImage image = new BufferedImage(64, 48, type);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++)
            for (int channel = 0; channel < image.getRaster().getNumBands(); channel++)
                image.getRaster().setSample(x, y, channel, (x * 193 + y * 79 + x * y * 13 + channel * 31) & 65535);
        return image;
    }
    private static void compare(BitmapPreviewAccess access, HeightMap map, int originX, int originY, int shift) {
        double[] output = new double[16384];
        assertTrue("Expected supported native patch", access.fill(map, originX, originY, shift, output));
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            double expected = map.getHeight(originX + (x << shift), originY + (y << shift));
            assertEquals("Unquantised height at " + x + "," + y, Double.doubleToLongBits(expected), Double.doubleToLongBits(output[x + y * 128]));
        }
    }
    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
