package org.pepsoft.worldpainter.brushes;

import com.sun.management.ThreadMXBean;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import javax.imageio.ImageIO;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Measures changing intensity and preparing the first usable bitmap brush sample. */
public final class BitmapBrushBenchmark {
    private static volatile BitmapBrush held;
    public static byte[] fixture() throws Exception {
        BufferedImage image=new BufferedImage(97,83,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<83;y++)for(int x=0;x<97;x++) image.setRGB(x,y,((x*23+y*11)&255)<<24|((x*17+y*29)&255)<<16|((x*13+y*7)&255)<<8|((x*3+y*31)&255));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    private static long interact(BitmapBrush brush) {
        long hash=1;
        for(int turn=0;turn<2;turn++)for(float level:new float[]{0.375f,0.875f,0.5f,1f}) {
            brush.setLevel(level);
            hash=31*hash+Float.floatToRawIntBits(brush.getStrength(brush.getRadius()/3,-brush.getRadius()/4));
        }
        return hash;
    }
    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        byte[] source=fixture();int radius=Integer.getInteger("welt.benchmark.bitmapRadius",255);
        String mode=System.getProperty("welt.benchmark.bitmapMode","compare");
        BitmapBrush[] brushes=new BitmapBrush[2];
        for(int i=0;i<2;i++){brushes[i]=new BitmapBrush(new ByteArrayInputStream(source),"Benchmark bitmap");brushes[i].setRadius(radius);}
        ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        long expected=0;
        for(int trial=-10;trial<9;trial++)for(int j=0;j<2;j++) {
            int which=(trial&1)^j;
            if(!mode.equals("compare")&&which!=(mode.equals("native")?1:0))continue;
            System.setProperty(Native.GEN_KEY,Boolean.toString(which==1));System.setProperty("welt.native.bitmapBrush",Boolean.toString(which==1));
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId());long start=System.nanoTime();
            long hash=interact(brushes[which]);long nanos=System.nanoTime()-start;
            allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
            if(expected==0)expected=hash;else if(hash!=expected)throw new AssertionError("Brush checksum mismatch");
            if(trial>=0)System.out.println("sample="+trial+" native="+(which==1)+" nanos="+nanos+" allocated="+allocated);
            held=brushes[which];
        }
        for(int i=0;i<3;i++){System.gc();Thread.sleep(100);}
        long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p -> p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.println("nativeTransactions="+BitmapBrush.nativeTransactions()+" checksum="+expected+" heap="+ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()+" direct="+direct);
    }
}
