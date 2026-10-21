package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.function.DoubleSupplier;

/** Complete discrete strokes including random draws, packed writes and event release. */
public final class DitheredDiscreteBenchmark {
    static final class Draws implements DoubleSupplier {
        private final Random random=new Random(7162);long count;
        void reset(){random.setSeed(7162);count=0;}
        public double getAsDouble(){count++;return random.nextDouble();}
    }
    private static volatile Object held;
    static long hash(Dimension dimension, Layer layer){long result=1;for(Tile tile:dimension.getTiles()){
        result=result*31+tile.getX();result=result*31+tile.getY();for(int y=0;y<128;y++)for(int x=0;x<128;x++)result=result*31+tile.getLayerValue(layer,x,y);
    }return result;}
    public static void main(String[] args) throws Exception {
        int radius=Integer.getInteger("welt.benchmark.ditherRadius",127),warmups=Integer.getInteger("welt.benchmark.ditherWarmups",10);
        String kind=System.getProperty("welt.benchmark.ditherLayer","biome");
        if(radius<1||radius>511||warmups<5||warmups>40||!java.util.Set.of("biome","nibble").contains(kind))throw new IllegalArgumentException();
        String scenario=System.getProperty("welt.benchmark.ditherMode","compare");
        if(!java.util.Set.of("compare","java","rust").contains(scenario))throw new IllegalArgumentException();
        Layer layer=kind.equals("biome")?Biome.INSTANCE:Resources.INSTANCE;int target=kind.equals("biome")?42:12;
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        double[][] times=new double[2][9];long[][] bytes=new long[2][9];long[] hashes=new long[2],draws=new long[2];
        boolean global=Boolean.getBoolean("welt.benchmark.ditherGlobalRandom");
        if(Boolean.getBoolean("welt.benchmark.ditherPreload") && !org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable())throw new AssertionError("Release slices required");
        Dimension[] worlds={NibbleLayerPaintParityTest.fixture(),NibbleLayerPaintParityTest.fixture()};
        Draws[] random={new Draws(),new Draws()};DiscreteLayerPaint[] paint=global?new DiscreteLayerPaint[]{new DiscreteLayerPaint(layer,target),new DiscreteLayerPaint(layer,target)}:new DiscreteLayerPaint[]{new DiscreteLayerPaint(layer,target,random[0]),new DiscreteLayerPaint(layer,target,random[1])};
        for(var p:paint){var brush=SymmetricBrush.COSINE_CIRCLE.clone();brush.setRadius(radius);brush.setLevel(0.63f);p.setBrush(brush);p.setDither(true);}
        for(int trial=-warmups;trial<9;trial++)for(int turn=0;turn<2;turn++){
            int mode=(trial&1)^turn;if(!scenario.equals("compare")&&mode!=(scenario.equals("java")?0:1))continue;System.setProperty("welt.native.ditheredDiscrete",Boolean.toString(mode==1));random[mode].reset();
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
            worlds[mode].setEventsInhibited(true);
            try{for(int pass=0;pass<2;pass++)for(int y=-128;y<=128;y+=128)for(int x=-128;x<=128;x+=128){
                if(pass==0)paint[mode].apply(worlds[mode],x,y,0.74f);else paint[mode].remove(worlds[mode],x,y,0.74f);
            }}finally{worlds[mode].setEventsInhibited(false);}
            long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            held=worlds[mode];hashes[mode]=hash(worlds[mode],layer);draws[mode]=random[mode].count;
            if(trial>=0){times[mode][trial]=elapsed/1e6;bytes[mode][trial]=allocated;}
        }
        if(!scenario.equals("compare")){int mode=scenario.equals("java")?0:1;worlds[1-mode]=null;paint[1-mode]=null;random[1-mode]=null;
            System.gc();Thread.sleep(150);Arrays.sort(times[mode]);Arrays.sort(bytes[mode]);long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p->p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
            System.out.printf(Locale.ROOT,"ditherMemory mode=%s layer=%s radius=%d medianMs=%.4f allocatedBytes=%d heapBytes=%d directBytes=%d draws=%d%n",scenario,kind,radius,times[mode][4],bytes[mode][4],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),direct,draws[mode]);return;}
        if(!global && (hashes[0]!=hashes[1]||draws[0]!=draws[1]))throw new AssertionError("Stroke or random state mismatch");
        double[] ratios=new double[9];for(int i=0;i<9;i++)ratios[i]=times[0][i]/times[1][i];Arrays.sort(ratios);
        for(int mode=0;mode<2;mode++){Arrays.sort(times[mode]);Arrays.sort(bytes[mode]);}
        System.out.printf(Locale.ROOT,"ditheredStroke layer=%s radius=%d strokes=18 javaMs=%.4f groupedMs=%.4f pairedRatio=%.3f range=%.3f..%.3f allocatedBytes=%d/%d draws=%d checksum=%d globalRandom=%s%n",kind,radius,times[0][4],times[1][4],ratios[4],ratios[0],ratios[8],bytes[0][4],bytes[1][4],global?-1:draws[0],hashes[0],global);
    }
}