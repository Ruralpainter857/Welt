package org.pepsoft.worldpainter;

import java.util.Arrays;
import java.lang.management.ManagementFactory;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Measures complete world height-bound queries; fixture construction is excluded. */
public final class HeightStatisticsBenchmark {
    private static volatile int checksum;
    static Dimension fixture(boolean tall) {
        Dimension d=TestData.createDimension(new java.awt.Rectangle(0,0,128,128),62);
        d.removeTile(0,0);if(!tall)d.setMaxHeight(192);
        int cap=(d.getMaxHeight()-1-d.getMinHeight())*256;
        for(int tx=-8;tx<8;tx++)for(int ty=-8;ty<8;ty++) {
            Tile tile=new Tile(tx,ty,d.getMinHeight(),d.getMaxHeight());tile.inhibitEvents();
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)tile.setRawHeight(x,y,1+Math.floorMod(x*971+y*353+tx*347+ty*571,cap-2));
            tile.releaseEvents();d.addTile(tile);
        }
        return d;
    }
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("compare")) {
            compare(args.length < 2 || args[1].equals("tall"));
            return;
        }
        boolean rust=args.length>0&&args[0].equals("rust"),tall=args.length<2||args[1].equals("tall");
        System.setProperty(Native.GEN_KEY,Boolean.toString(rust));Dimension d=fixture(tall);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        double[] times=new double[7];long[] allocations=new long[7];
        for(int trial=-5;trial<7;trial++) {
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
            for(int query=0;query<20;query++){int[] range=d.getRawHeightRange();checksum=range[0]^range[1];}
            if(trial>=0){times[trial]=(System.nanoTime()-start)/1e6;allocations[trial]=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;}
        }
        Arrays.sort(times);Arrays.sort(allocations);
        System.out.printf(java.util.Locale.ROOT,"%s %s world_height_bounds_20_queries_ms=%.3f allocated_bytes=%d checksum=%d%n",
                rust?"Rust":"Java",tall?"tall":"short",times[3],allocations[3],checksum);
    }

    /** Alternates both implementations in the same warmed JVM and on the same world. */
    private static void compare(boolean tall) {
        Dimension world = fixture(tall);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[][] times = new double[2][15];
        long[][] allocations = new long[2][15];
        for (int trial = -10; trial < 15; trial++) {
            for (int order = 0; order < 2; order++) {
                int engine = (trial & 1) == 0 ? order : 1 - order;
                System.setProperty(Native.GEN_KEY, Boolean.toString(engine == 1));
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                long start = System.nanoTime();
                for (int query = 0; query < 20; query++) {
                    int[] range = world.getRawHeightRange();
                    checksum = range[0] ^ range[1];
                }
                double millis = (System.nanoTime() - start) / 1e6;
                long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
                if (trial >= 0) {
                    times[engine][trial] = millis;
                    allocations[engine][trial] = allocated;
                }
            }
        }
        double[] ratios = new double[15];
        for (int trial = 0; trial < 15; trial++) {
            ratios[trial] = times[0][trial] / times[1][trial];
        }
        for (int engine = 0; engine < 2; engine++) {
            Arrays.sort(times[engine]);
            Arrays.sort(allocations[engine]);
            System.out.printf(java.util.Locale.ROOT,
                    "%s %s paired_world_height_bounds_20_queries_ms=%.3f allocated_bytes=%d checksum=%d%n",
                    engine == 0 ? "Java" : "Rust", tall ? "tall" : "short",
                    times[engine][7], allocations[engine][7], checksum);
        }
        Arrays.sort(ratios);
        System.out.printf(java.util.Locale.ROOT,
                "paired_ratio_median=%.3f ratio_min=%.3f ratio_max=%.3f%n", ratios[7], ratios[0], ratios[14]);
    }
}
