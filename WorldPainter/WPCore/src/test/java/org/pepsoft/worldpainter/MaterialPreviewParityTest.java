package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class MaterialPreviewParityTest {
    private MixedMaterial.Row[] rows() {
        return new MixedMaterial.Row[] {
            new MixedMaterial.Row(Material.STONE, 23, 0.55f),
            new MixedMaterial.Row(Material.get("welt:preview_custom"), 17, 1.7f),
            new MixedMaterial.Row(Material.DIRT, 31, 0.8f),
            new MixedMaterial.Row(Material.SAND, 17, 1.2f)
        };
    }
    private void requireLibrary() {
        boolean available = NativeLoader.areSlicesAvailable();
        if (Boolean.getBoolean("welt.test.requireMaterialPreview")) assertTrue("Release JNI library required", available);
        org.junit.Assume.assumeTrue(available);
    }
    @Test public void blobsMatchOriginalPixelAndCallbackOrder() {
        requireLibrary();
        for (float scale : new float[] {0.3f, 1f, 3.7f}) compare(new MixedMaterial("blobs", rows(), -1, null, scale), 137, 93, true);
    }
    @Test public void layeredNoiseAndSlopesMatchOriginal() {
        requireLibrary();
        for (boolean repeat : new boolean[] {false, true}) {
            for (int roughness : new int[] {-1, 0, 3, 9}) {
                NoiseSettings noise = roughness == -1 ? null : new NoiseSettings(9876L, 37, roughness, 1.3f);
                compare(new MixedMaterial("layers", rows(), -1, null, noise, repeat ? -0.85 : 0, repeat ? 1.75 : 0, repeat), 129, 87, true);
            }
        }
    }
    @Test public void simpleAndUnsupportedSizeRetainJavaPath() {
        requireLibrary();
        compare(new MixedMaterial("simple", new MixedMaterial.Row(Material.STONE, 1, 1), -1, null), 93, 67, false);
        compare(new MixedMaterial("large", rows(), -1, null, 1f), 1025, 5, false);
        compare(new MixedMaterial("tiny", rows(), -1, null, 1f), 16, 16, false);
    }
    @Test public void randomModePreservesEveryDraw() throws Exception {
        requireLibrary();
        MixedMaterial material = new MixedMaterial("random", rows(), -1, null);
        Field field = MixedMaterial.class.getDeclaredField("random");
        field.setAccessible(true);
        Random random = (Random) field.get(material);
        random.setSeed(12345);
        BufferedImage expected = original(material, 97, 67, ColourScheme.DEFAULT);
        long next = random.nextLong();
        random.setSeed(12345);
        String oldGen = System.getProperty(Native.GEN_KEY), oldFlag = System.getProperty("welt.native.materialPreview");
        try {
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.materialPreview", "true");
            long before = MaterialPreviewAccess.nativeTransactions();
            assertImage(expected, material.createPreview(97, 67, ColourScheme.DEFAULT));
            assertEquals(before, MaterialPreviewAccess.nativeTransactions());
            assertEquals(next, random.nextLong());
        } finally { restore(Native.GEN_KEY, oldGen); restore("welt.native.materialPreview", oldFlag); }
    }
    @Test public void framesRemainIndependentAcrossWorkers() throws Exception {
        requireLibrary();
        String oldGen = System.getProperty(Native.GEN_KEY), oldFlag = System.getProperty("welt.native.materialPreview");
        var executor = Executors.newFixedThreadPool(2);
        try {
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.materialPreview", "true");
            var first = executor.submit(() -> { MixedMaterial m = new MixedMaterial("first", rows(), -1, null, 0.7f); for (int i=0;i<4;i++) assertImage(original(m,131,89,ColourScheme.DEFAULT),m.createPreview(131,89,ColourScheme.DEFAULT)); });
            var second = executor.submit(() -> { MixedMaterial m = new MixedMaterial("second", rows(), -1, null, new NoiseSettings(0,43,4,2f), -1.1, 0, true); for (int i=0;i<4;i++) assertImage(original(m,99,97,ColourScheme.DEFAULT),m.createPreview(99,97,ColourScheme.DEFAULT)); });
            first.get(); second.get();
        } finally { executor.shutdownNow(); restore(Native.GEN_KEY, oldGen); restore("welt.native.materialPreview", oldFlag); }
    }
    @Test public void nestedColourCallbacksDoNotOverwriteTheOuterFrame() {
        requireLibrary();
        MixedMaterial outer = new MixedMaterial("outer",rows(),-1,null,0.7f);
        MixedMaterial inner = new MixedMaterial("inner",rows(),-1,null,2.5f);
        BufferedImage expected = original(outer,129,93,ColourScheme.DEFAULT);
        String oldGen=System.getProperty(Native.GEN_KEY), oldFlag=System.getProperty("welt.native.materialPreview");
        try {
            System.setProperty(Native.GEN_KEY,"true");System.setProperty("welt.native.materialPreview","true");
            boolean[] nested={false};long before=MaterialPreviewAccess.nativeTransactions();
            BufferedImage actual=outer.createPreview(129,93,m -> {
                if (!nested[0]) { nested[0]=true;assertImage(original(inner,139,99,ColourScheme.DEFAULT),inner.createPreview(139,99,ColourScheme.DEFAULT)); }
                return ColourScheme.DEFAULT.getColour(m);
            });
            assertImage(expected,actual);assertEquals(before+1,MaterialPreviewAccess.nativeTransactions());
        } finally { restore(Native.GEN_KEY,oldGen);restore("welt.native.materialPreview",oldFlag); }
    }
    private void compare(MixedMaterial material, int width, int height, boolean nativeExpected) {
        List<Material> expectedOrder = new ArrayList<>(), actualOrder = new ArrayList<>();
        BufferedImage expected = original(material,width,height,m -> { expectedOrder.add(m); return System.identityHashCode(m); });
        String oldGen = System.getProperty(Native.GEN_KEY), oldFlag = System.getProperty("welt.native.materialPreview");
        try {
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.materialPreview", "true");
            long before = MaterialPreviewAccess.nativeTransactions();
            BufferedImage actual = material.createPreview(width,height,m -> { actualOrder.add(m); return System.identityHashCode(m); });
            assertImage(expected,actual);
            assertEquals(expectedOrder.size(),actualOrder.size());
            for (int i=0;i<expectedOrder.size();i++) assertSame("callback " + i,expectedOrder.get(i),actualOrder.get(i));
            assertEquals(before + (nativeExpected ? 1 : 0),MaterialPreviewAccess.nativeTransactions());
        } finally { restore(Native.GEN_KEY,oldGen); restore("welt.native.materialPreview",oldFlag); }
    }
    private static BufferedImage original(MixedMaterial m,int width,int height,ColourScheme colours) {
        BufferedImage image = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for (int x=0;x<width;x++) for (int y=0;y<height;y++) image.setRGB(x,y,colours.getColour(m.getMaterial(0L,x,0,height-y-1)));
        return image;
    }
    private static void assertImage(BufferedImage a,BufferedImage b) {
        assertEquals(a.getWidth(),b.getWidth());assertEquals(a.getHeight(),b.getHeight());
        for (int y=0;y<a.getHeight();y++) for (int x=0;x<a.getWidth();x++) assertEquals("pixel " + x + "," + y,a.getRGB(x,y),b.getRGB(x,y));
    }
    private static void restore(String key,String value) { if(value==null) System.clearProperty(key); else System.setProperty(key,value); }
}
