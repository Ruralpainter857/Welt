package org.pepsoft.worldpainter.exporting;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.management.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/** End-to-end sparse-image memory-cap proof; this cold run is not a speedup benchmark. */
public final class HeightMapStreamingMemoryBenchmark {
    private static volatile long checksum;
    public static void main(String[] args) throws Exception {
        boolean streaming=args.length>0&&args[0].equals("streamed");
        int side=Integer.getInteger("welt.benchmark.streamingSide",64);
        if(side<2||side>128||(side&1)!=0)throw new IllegalArgumentException("Side must be even and 2..128");
        boolean nativeMode=Boolean.parseBoolean(System.getProperty("welt.benchmark.streamingNative","true"));
        System.setProperty(Native.GEN_KEY,"false");System.setProperty(Native.RENDER_KEY,Boolean.toString(nativeMode));
        System.setProperty("welt.native.heightmapImage",Boolean.toString(nativeMode));System.setProperty("welt.heightmap.streaming",Boolean.toString(streaming));
        if(nativeMode&&!NativeLoader.areSlicesAvailable())throw new AssertionError("Release image JNI required");
        var ranges=new TreeMap<Integer,Terrain>();ranges.put(-65,Terrain.GRASS);
        var factory=new HeightMapTileFactory(197,new ConstantHeightMap(64),-64,320,false,new SimpleTheme(197,62,ranges,null,-64,320,false,false));
        var world=new World2(DefaultPlugin.JAVA_ANVIL_1_19,-64,320);
        var dimension=new Dimension(world,"Bounded image",197,factory,Dimension.Anchor.NORMAL_DETAIL,false);
        for(int corner=0;corner<2;corner++) {
            int at=corner==0?-side/2:side/2-1;Tile tile=new Tile(at,at,-64,320);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)tile.setHeight(x,y,corner==0?64.25f:171.5f);
            dimension.addTile(tile);
        }
        int width=side*128,height=width;
        Path directory=Path.of("target","heightmap-streaming-work","memory");Files.createDirectories(directory);
        Path file=Files.createTempFile(directory,"bounded-",".tiff");
        var allocations=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!allocations.isThreadAllocatedMemoryEnabled())allocations.setThreadAllocatedMemoryEnabled(true);
        System.gc();long baseline=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)pool.resetPeakUsage();
        long before=allocations.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
        long calls=HeightMapImageAccess.completedTiles(),streams=HeightMapExporter.completedStreamingExports();
        try {
            try {
                if(!new HeightMapExporter(dimension,HeightMapExporter.Format.FLOAT_ONE_TO_ONE).exportToFile(file.toFile()))throw new AssertionError("TIFF writer required");
            } catch(OutOfMemoryError exhausted) {
                if(streaming)throw exhausted;
                boolean raster=false;
                for(StackTraceElement frame:exhausted.getStackTrace())if(frame.getClassName().equals("java.awt.image.DataBufferFloat"))raster=true;
                if(!raster)throw exhausted;
                System.out.printf(Locale.ROOT,"boundedImage mode=dense width=%d height=%d denseRasterBytes=%d heapLimitBytes=%d denseRasterDoesNotFit=true%n",width,height,(long)width*height*4,Runtime.getRuntime().maxMemory());
                return;
            }
            double elapsed=(System.nanoTime()-start)/1e6;
            long allocated=allocations.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            long peak=0;for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)peak+=pool.getPeakUsage().getUsed();
            long nativeCalls=HeightMapImageAccess.completedTiles()-calls,streamed=HeightMapExporter.completedStreamingExports()-streams;
            if(streaming&&streamed!=1||!streaming&&streamed!=0||nativeMode&&nativeCalls!=2)throw new AssertionError("Unexpected complete path coverage");
            long hash=1,verified=0;
            try(var input=ImageIO.createImageInputStream(file.toFile())) {
                var readers=ImageIO.getImageReaders(input);javax.imageio.ImageReader reader=null;
                while(readers.hasNext()) {var candidate=readers.next();if(candidate.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageReader")){reader=candidate;break;}candidate.dispose();}
                if(reader==null)throw new AssertionError("JDK float TIFF reader required");
                try {
                    reader.setInput(input);if(reader.getWidth(0)!=width||reader.getHeight(0)!=height)throw new AssertionError("Image extent mismatch");
                    for(int row=0;row<height;row+=128) {
                        var parameter=reader.getDefaultReadParam();parameter.setSourceRegion(new Rectangle(0,row,width,128));
                        BufferedImage image=reader.read(0,parameter);
                        for(int y=0;y<128;y++)for(int x=0;x<width;x++) {
                            float expected=row==0&&x<128?64.25f:row==height-128&&x>=width-128?171.5f:0f;
                            int bits=Float.floatToRawIntBits(image.getRaster().getSampleFloat(x,y,0));
                            if(bits!=Float.floatToRawIntBits(expected))throw new AssertionError("Height sample mismatch at "+x+","+(row+y));
                            hash=hash*31+bits;verified++;
                        }
                    }
                } finally {reader.dispose();}
            }
            checksum=hash;long direct=0;for(var pool:ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))if(pool.getName().equals("direct"))direct+=pool.getMemoryUsed();
            System.gc();long retained=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            System.out.printf(Locale.ROOT,"boundedImage mode=%s width=%d height=%d writeMs=%.3f allocatedBytes=%d heapBaselineBytes=%d heapPoolPeakSumBytes=%d heapRetainedBytes=%d directBytes=%d heapLimitBytes=%d fileBytes=%d samplesVerified=%d checksum=%d nativeCalls=%d streamingExports=%d%n",streaming?"streamed":"dense",width,height,elapsed,allocated,baseline,peak,retained,direct,Runtime.getRuntime().maxMemory(),Files.size(file),verified,checksum,nativeCalls,streamed);
        } finally {Files.deleteIfExists(file);}
    }
}
