package org.pepsoft.worldpainter.importing;

import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Complete image-range discovery, including the real caller's object construction. */
public final class ImageRangeBenchmark {
    private static volatile Object held;
    public static BufferedImage fixture(String kind, int width, int height) {
        BufferedImage image;
        if (kind.equals("byte")) image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        else if (kind.equals("ushort")) image = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        else {
            int type = kind.equals("float") ? DataBuffer.TYPE_FLOAT : DataBuffer.TYPE_DOUBLE;
            DataBuffer data = type == DataBuffer.TYPE_FLOAT ? new DataBufferFloat(width * height) : new DataBufferDouble(width * height);
            var model = new ComponentSampleModel(type, width, height, 1, width, new int[]{0});
            var raster = Raster.createWritableRaster(model, data, null);
            var colours = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, type);
            image = new BufferedImage(colours, raster, false, null);
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double value = kind.equals("byte") ? 20 + (x * 29 + y * 17) % 200
                    : kind.equals("ushort") ? 1024 + (x * 29 + y * 17) % 32000
                    : ((x * 29 + y * 17) % 8191) / 8191.0 - 0.5;
            image.getRaster().setSample(x, y, 0, value);
        }
        return image;
    }
    public static void main(String[] args) throws Exception {
        int size = Integer.getInteger("welt.benchmark.rangeSize", 1024);
        String kind = System.getProperty("welt.benchmark.rangeKind", "ushort");
        String caller = System.getProperty("welt.benchmark.rangeCaller", "bitmap");
        int warmups = Integer.getInteger("welt.benchmark.rangeWarmups", 12);
        if (size < 1 || size > 8192 || warmups < 5 || warmups > 100
                || !java.util.Set.of("byte","ushort","float","double").contains(kind)
                || !java.util.Set.of("bitmap","mask").contains(caller)) throw new IllegalArgumentException();
        String scenario=System.getProperty("welt.benchmark.rangeMode","compare");
        if(!java.util.Set.of("compare","java","rust").contains(scenario))throw new IllegalArgumentException();
        BufferedImage image = fixture(kind, size, size);
        if (Boolean.getBoolean("welt.test.requireImageRanges")) {
            System.setProperty("welt.native.imageRanges", "true");
            if (org.pepsoft.worldpainter.ImageRangeAccess.range(image.getRaster(), 0, true, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE) == null) throw new AssertionError("Native image adapter required");
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean(); bean.setThreadAllocatedMemoryEnabled(true);
        double[][] times = new double[2][9]; long[][] bytes = new long[2][9]; long[][] values = new long[2][2];
        for (int trial = -warmups; trial < 9; trial++) for (int turn = 0; turn < 2; turn++) {
            int mode = (trial & 1) ^ turn; if(!scenario.equals("compare") && mode!=(scenario.equals("java")?0:1))continue; System.setProperty("welt.native.imageRanges", Boolean.toString(mode == 1));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            double low, high;
            if (caller.equals("bitmap")) { var map = BitmapHeightMap.build().withImage(image).now(); var range = map.getRange(); low=range[0]; high=range[1]; held=map; }
            else { var importer = new MaskImporter(null, new File("range-fixture.png"), image); low=importer.getImageLowValue(); high=importer.getImageHighValue(); held=importer; }
            long elapsed = System.nanoTime()-start, allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            values[mode][0]=Double.doubleToLongBits(low); values[mode][1]=Double.doubleToLongBits(high);
            if(trial>=0){times[mode][trial]=elapsed/1e6;bytes[mode][trial]=allocated;}
        }
        if(!scenario.equals("compare")){int mode=scenario.equals("java")?0:1;System.gc();Thread.sleep(150);Arrays.sort(times[mode]);Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT,"imageRangeMemory mode=%s kind=%s caller=%s size=%d medianMs=%.4f allocatedBytes=%d heapBytes=%d%n",scenario,kind,caller,size,times[mode][4],bytes[mode][4],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());return;}
        if(!Arrays.equals(values[0],values[1])) throw new AssertionError("Image range mismatch");
        double[] ratios=new double[9];for(int i=0;i<9;i++)ratios[i]=times[0][i]/times[1][i]; Arrays.sort(ratios);
        for(int mode=0;mode<2;mode++){Arrays.sort(times[mode]);Arrays.sort(bytes[mode]);}
        System.out.printf(Locale.ROOT,"imageRange kind=%s caller=%s size=%d javaMs=%.4f rustMs=%.4f pairedRatio=%.3f range=%.3f..%.3f allocatedBytes=%d/%d values=%s%n",kind,caller,size,times[0][4],times[1][4],ratios[4],ratios[0],ratios[8],bytes[0][4],bytes[1][4],Arrays.toString(values[0]));
    }
}