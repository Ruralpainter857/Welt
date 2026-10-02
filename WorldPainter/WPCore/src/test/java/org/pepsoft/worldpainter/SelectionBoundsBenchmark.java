package org.pepsoft.worldpainter;

import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.pepsoft.worldpainter.Constants.*;

/** Complete selection-bounds queries; the frozen pre-port oracle is retained for parity tests. */
public final class SelectionBoundsBenchmark {
    private static volatile int checksum;
    static Rectangle legacyBounds(Dimension dimension) {
        int[] lowestX = {Integer.MAX_VALUE};
        int[] highestX = {Integer.MIN_VALUE};
        int[] lowestY = {Integer.MAX_VALUE};
        int[] highestY = {Integer.MIN_VALUE};
        dimension.visitTiles().forSelection().andDo(tile -> {
                    int tileX = tile.getX(), tileY = tile.getY();
                    if (((tileX << TILE_SIZE_BITS) >= lowestX[0])
                            && (((tileX + 1) << TILE_SIZE_BITS) < highestX[0])
                            && (((tileY) << TILE_SIZE_BITS) >= lowestY[0])
                            && (((tileY + 1) << TILE_SIZE_BITS) < highestY[0])) {
                        // Tiles which lie within the already established bounds can be safely skipped
                        return;
                    }
                    boolean tileHasChunkSelection = tile.hasLayer(SelectionChunk.INSTANCE);
                    boolean tileHasBlockSelection = tile.hasLayer(SelectionBlock.INSTANCE);
                    for (int chunkX = 0; chunkX < TILE_SIZE; chunkX += 16) {
                        for (int chunkY = 0; chunkY < TILE_SIZE; chunkY += 16) {
                            if (tileHasChunkSelection && tile.getBitLayerValue(SelectionChunk.INSTANCE, chunkX, chunkY)) {
                                int x1 = (tileX << TILE_SIZE_BITS) | chunkX;
                                int x2 = x1 + 15;
                                int y1 = (tileY << TILE_SIZE_BITS) | chunkY;
                                int y2 = y1 + 15;
                                if (x1 < lowestX[0]) {
                                    lowestX[0] = x1;
                                }
                                if (x2 > highestX[0]) {
                                    highestX[0] = x2;
                                }
                                if (y1 < lowestY[0]) {
                                    lowestY[0] = y1;
                                }
                                if (y2 > highestY[0]) {
                                    highestY[0] = y2;
                                }
                            } else if (tileHasBlockSelection) {
                                for (int dx = 0; dx < 16; dx++) {
                                    for (int dy = 0; dy < 16; dy++) {
                                        if (tile.getBitLayerValue(SelectionBlock.INSTANCE, chunkX + dx, chunkY + dy)) {
                                            final int x = ((tileX << TILE_SIZE_BITS) | chunkX) + dx;
                                            final int y = ((tileY << TILE_SIZE_BITS) | chunkY) + dy;
                                            if (x < lowestX[0]) {
                                                lowestX[0] = x;
                                            }
                                            if (x > highestX[0]) {
                                                highestX[0] = x;
                                            }
                                            if (y < lowestY[0]) {
                                                lowestY[0] = y;
                                            }
                                            if (y > highestY[0]) {
                                                highestY[0] = y;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
        if (lowestX[0] != Integer.MAX_VALUE) {
            return new Rectangle(lowestX[0], lowestY[0], highestX[0] - lowestX[0] + 1, highestY[0] - lowestY[0] + 1);
        } else {
            return null;
        }

    }
    static Dimension fixture() {
        System.setProperty(Native.GEN_KEY,"false");
        Dimension world=TestData.createDimension(new Rectangle(0,0,128,128),62);
        world.removeTile(0,0);
        for(int tx=-8;tx<8;tx++)for(int ty=-8;ty<8;ty++) {
            Tile tile=new Tile(tx,ty,-64,320);tile.inhibitEvents();
            for(int x=0;x<128;x++)for(int y=0;y<128;y++)
                if(((x*971+y*353+tx*7+ty*11)&15)==0)tile.setBitLayerValue(SelectionBlock.INSTANCE,x,y,true);
            tile.setBitLayerValue(SelectionChunk.INSTANCE,32,48,true);
            tile.releaseEvents();world.addTile(tile);
        }
        return world;
    }
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("compare")) { compare(); return; }
        Dimension world=fixture();
        boolean rust=args.length>0&&args[0].equals("rust");
        System.setProperty(Native.GEN_KEY,Boolean.toString(rust));
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        double[] times=new double[7];long[] allocations=new long[7];
        for(int trial=-5;trial<7;trial++) {
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
            for(int query=0;query<20;query++) {Rectangle bounds=rust?SelectionBoundsAccess.getBounds(world):legacyBounds(world);checksum=bounds==null?0:bounds.hashCode();}
            if(trial>=0){times[trial]=(System.nanoTime()-start)/1e6;allocations[trial]=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;}
        }
        Arrays.sort(times);Arrays.sort(allocations);
        long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        System.out.printf(java.util.Locale.ROOT,"%s selection_bounds_20_queries_ms=%.3f allocated_bytes=%d direct_buffer_bytes=%d checksum=%d%n",rust?"rust":"java",times[3],allocations[3],direct,checksum);
    }

    /** Alternate complete queries over the same world in one warmed JVM. */
    private static void compare() {
        Dimension world = fixture();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[][] times = new double[2][15]; long[][] allocations = new long[2][15];
        for (int trial = -10; trial < 15; trial++) for (int order = 0; order < 2; order++) {
            int engine = (trial & 1) == 0 ? order : 1 - order;
            System.setProperty(Native.GEN_KEY, Boolean.toString(engine == 1));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int query = 0; query < 20; query++) {
                Rectangle bounds = engine == 1 ? SelectionBoundsAccess.getBounds(world) : legacyBounds(world);
                checksum = bounds == null ? 0 : bounds.hashCode();
            }
            if (trial >= 0) {
                times[engine][trial] = (System.nanoTime() - start) / 1e6;
                allocations[engine][trial] = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            }
        }
        double[] ratios = new double[15];
        for (int trial = 0; trial < 15; trial++) ratios[trial] = times[0][trial] / times[1][trial];
        for (int engine = 0; engine < 2; engine++) {
            Arrays.sort(times[engine]); Arrays.sort(allocations[engine]);
            System.out.printf(java.util.Locale.ROOT,"%s paired_selection_bounds_20_queries_ms=%.3f allocated_bytes=%d checksum=%d%n",
                    engine == 0 ? "java" : "rust", times[engine][7], allocations[engine][7], checksum);
        }
        Arrays.sort(ratios);
        System.out.printf(java.util.Locale.ROOT,"paired_ratio_median=%.3f min=%.3f max=%.3f%n",ratios[7],ratios[0],ratios[14]);
    }
}
