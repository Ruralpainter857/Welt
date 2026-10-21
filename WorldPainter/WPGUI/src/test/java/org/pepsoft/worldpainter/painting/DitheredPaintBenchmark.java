package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Actual terrain and bit-layer stroke batches, including event release. */
public final class DitheredPaintBenchmark {
    static Paint paint(String kind,DitheredDiscreteBenchmark.Draws random,boolean global){
        return switch(kind){case "terrain"->global?new TerrainPaint(Terrain.CUSTOM_96):new TerrainPaint(Terrain.CUSTOM_96,random);
            case "bit"->global?new BitLayerPaint(Frost.INSTANCE):new BitLayerPaint(Frost.INSTANCE,random);
            case "chunk"->global?new BitLayerPaint(Populate.INSTANCE):new BitLayerPaint(Populate.INSTANCE,random);
            default->throw new IllegalArgumentException();};
    }
    static long hash(Dimension world,String kind){long value=1;for(Tile tile:world.getTiles()){
        value=value*31+tile.getX();value=value*31+tile.getY();for(int y=0;y<128;y++)for(int x=0;x<128;x++)
            value=value*31+(kind.equals("terrain")?tile.getTerrain(x,y).ordinal():tile.getBitLayerValue(kind.equals("bit")?Frost.INSTANCE:Populate.INSTANCE,x,y)?1:0);
    }return value;}
    private static volatile Object held;
    public static void main(String[] args)throws Exception{
        String kind=System.getProperty("welt.benchmark.ditherPaint","terrain"),modeName=System.getProperty("welt.benchmark.ditherPaintMode","compare");
        int radius=Integer.getInteger("welt.benchmark.ditherPaintRadius",127),warmups=Integer.getInteger("welt.benchmark.ditherPaintWarmups",10);
        boolean global=Boolean.getBoolean("welt.benchmark.ditherPaintGlobal");
        if(!java.util.Set.of("terrain","bit","chunk").contains(kind)||!java.util.Set.of("compare","java","rust").contains(modeName)||radius<1||radius>511||warmups<5||warmups>40)throw new IllegalArgumentException();
        if(Boolean.getBoolean("welt.benchmark.ditherPaintPreload")&&!org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable())throw new AssertionError();
        Dimension[] worlds={NibbleLayerPaintParityTest.fixture(),NibbleLayerPaintParityTest.fixture()};
        DitheredDiscreteBenchmark.Draws[] random={new DitheredDiscreteBenchmark.Draws(),new DitheredDiscreteBenchmark.Draws()};
        Paint[] paints={paint(kind,random[0],global),paint(kind,random[1],global)};
        for(Paint p:paints){var brush=SymmetricBrush.COSINE_CIRCLE.clone();brush.setRadius(radius);brush.setLevel(0.63f);p.setBrush(brush);p.setDither(true);}
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        double[][] times=new double[2][9];long[][] allocated=new long[2][9];long[] hashes=new long[2],draws=new long[2];
        for(int trial=-warmups;trial<9;trial++)for(int turn=0;turn<2;turn++){
            int mode=(trial&1)^turn;if(!modeName.equals("compare")&&mode!=(modeName.equals("java")?0:1))continue;
            System.setProperty("welt.native.ditheredPaint",Boolean.toString(mode==1));random[mode].reset();
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();worlds[mode].setEventsInhibited(true);
            try{for(int pass=0;pass<2;pass++)for(int y=-128;y<=128;y+=128)for(int x=-128;x<=128;x+=128){
                if(pass==1&&!kind.equals("terrain"))paints[mode].remove(worlds[mode],x,y,0.74f);else paints[mode].apply(worlds[mode],x,y,0.74f);
            }}finally{worlds[mode].setEventsInhibited(false);}
            long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            held=worlds[mode];hashes[mode]=hash(worlds[mode],kind);draws[mode]=random[mode].count;
            if(trial>=0){times[mode][trial]=elapsed/1e6;allocated[mode][trial]=bytes;}
        }
        if(!modeName.equals("compare")){int mode=modeName.equals("java")?0:1;worlds[1-mode]=null;paints[1-mode]=null;random[1-mode]=null;System.gc();Thread.sleep(150);
            Arrays.sort(times[mode]);Arrays.sort(allocated[mode]);long direct=ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p->p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
            System.out.printf(Locale.ROOT,"ditherPaintMemory kind=%s mode=%s radius=%d medianMs=%.4f allocatedBytes=%d heapBytes=%d directBytes=%d%n",kind,modeName,radius,times[mode][4],allocated[mode][4],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),direct);return;}
        if(!global&&(hashes[0]!=hashes[1]||draws[0]!=draws[1]))throw new AssertionError("Paint or draw stream mismatch");
        double[] ratios=new double[9];for(int i=0;i<9;i++)ratios[i]=times[0][i]/times[1][i];Arrays.sort(ratios);
        for(int mode=0;mode<2;mode++){Arrays.sort(times[mode]);Arrays.sort(allocated[mode]);}
        System.out.printf(Locale.ROOT,"ditherPaint kind=%s radius=%d strokes=18 javaMs=%.4f groupedMs=%.4f pairedRatio=%.3f range=%.3f..%.3f allocatedBytes=%d/%d draws=%d global=%s%n",kind,radius,times[0][4],times[1][4],ratios[4],ratios[0],ratios[8],allocated[0][4],allocated[1][4],global?-1:draws[0],global);
    }
}