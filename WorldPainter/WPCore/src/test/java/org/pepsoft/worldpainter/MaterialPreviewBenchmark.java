package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import org.pepsoft.minecraft.Material;

/** Measures preview model construction, classification, colour lookup and raster writes. */
public final class MaterialPreviewBenchmark {
    private static volatile BufferedImage held;
    public static MixedMaterial fixture() {
        return new MixedMaterial("Preview benchmark", new MixedMaterial.Row[] {
            new MixedMaterial.Row(Material.STONE, 400, 1.0f),
            new MixedMaterial.Row(Material.DIRT, 250, 0.7f),
            new MixedMaterial.Row(Material.GRASS_BLOCK, 200, 1.8f),
            new MixedMaterial.Row(Material.SAND, 150, 0.5f)
        }, -1, null, 1.0f);
    }
    public static void main(String[] args) {
        MixedMaterial source = fixture();
        if (Boolean.getBoolean("welt.benchmark.previewLayered")) {
            source = new MixedMaterial("Layered benchmark",source.getRows(),-1,null,
                Boolean.getBoolean("welt.benchmark.previewVariation") ? new NoiseSettings(0,37,3,1.3f) : null,-0.85,0,true);
        }
        int width = Integer.getInteger("welt.benchmark.previewWidth", 512);
        int height = Integer.getInteger("welt.benchmark.previewHeight", 256);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        if (Boolean.getBoolean("welt.benchmark.previewSolo")) {
            boolean nativeMode=Boolean.getBoolean("welt.benchmark.previewNative");
            for (int i=0;i<20;i++) held=render(source,width,height,nativeMode);
            for (int i=0;i<3;i++) { System.gc();try {Thread.sleep(100);}catch(InterruptedException e){throw new AssertionError(e);} }
            long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p -> p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
            System.out.println("native="+nativeMode+" checksum="+digest(held)+" heap="+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()+" direct="+direct);
            return;
        }        for (int i = 0; i < 20; i++) render(source, width, height, (i & 1) != 0);
        long checksum = digest(render(source, width, height, false));
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 2; j++) {
                boolean nativeMode = ((i + j) & 1) != 0;
                long beforeBytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                long start = System.nanoTime();
                BufferedImage image = render(source, width, height, nativeMode);
                long nanos = System.nanoTime() - start;
                long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeBytes;
                if (digest(image) != checksum) throw new AssertionError("Preview mismatch");
                held = image;
                System.out.println("sample=" + i + " native=" + nativeMode + " nanos=" + nanos + " allocated=" + bytes);
            }
        }
        for (int i = 0; i < 3; i++) { System.gc(); try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); } }
        System.out.println("nativeTransactions=" + MaterialPreviewAccess.nativeTransactions() + " checksum=" + checksum + " heap=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }
    private static BufferedImage render(MixedMaterial source, int width, int height, boolean nativeMode) {
        System.setProperty(org.pepsoft.worldpainter.nativeapi.Native.GEN_KEY, Boolean.toString(nativeMode));
        System.setProperty("welt.native.materialPreview", Boolean.toString(nativeMode));
        MixedMaterial.Row[] rows=java.util.Arrays.stream(source.getRows())
                .map(row -> new MixedMaterial.Row(row.material,row.occurrence,row.scale)).toArray(MixedMaterial.Row[]::new);
        MixedMaterial fresh=source.getMode()==MixedMaterial.Mode.BLOBS
                ? new MixedMaterial(source.getName(),rows,-1,null,source.getScale())
                : new MixedMaterial(source.getName(),rows,-1,null,source.getVariation(),source.getLayerXSlope(),source.getLayerYSlope(),source.isRepeat());
        return fresh.createPreview(width, height, ColourScheme.DEFAULT);
    }
    private static long digest(BufferedImage image) {
        long hash = 1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) hash = hash * 31 + image.getRGB(x,y);
        return hash;
    }
}
