package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.impl.fancy.FancyTheme;

/** WHIM v5: source neighborhood, climate and packed final tile share one JNI transaction. */
final class NativeFancyGenerationAccess {
    private static final ThreadLocal<Worker> WORKERS=ThreadLocal.withInitial(Worker::new);
    private static final class Worker { ByteBuffer data; long calls, preparation, nativeTime, application; }
    static long completed() { return WORKERS.get().calls; }
    static long[] profile() { Worker w=WORKERS.get(); return new long[]{w.calls,w.preparation,w.nativeTime,w.application}; }
    static boolean fill(HeightMapTileFactory factory, Tile tile, int tx, int ty) {
        if (!Boolean.getBoolean("welt.native.fancyGeneration")) return false;
        boolean profiling=Boolean.getBoolean("welt.native.generationProfile");
        long started=profiling?System.nanoTime():0;
        FancyTheme theme=(FancyTheme)factory.getTheme();
        FancyTheme.GenerationPlan plan=theme.prepareNativeGeneration();
        if (plan==null || theme.getHeightMap()!=factory.getHeightMap()) return false;
        long x=(long)tx*128,y=(long)ty*128;
        if(x-5<Integer.MIN_VALUE||y-5<Integer.MIN_VALUE||x+132>Integer.MAX_VALUE||y+132>Integer.MAX_VALUE)return false;
        ProceduralTileSource[] programs=new ProceduralTileSource[5];
        for(int i=0;i<5;i++) {
            programs[i]=HeightMapTileFactory.prepareProceduralSource(plan.sources().get(i),(int)x-(i==0?5:0),
                    (int)y-(i==0?5:0),i==0?138:128,i==0?138:128);
            if(programs[i]==null)return false;
        }
        Layer[] layers=new Layer[9];
        int[] kinds={0,0,1,2,2,2,2,3,3},roles={0,1,2,3,3,3,3,3,3},offsets=new int[9];
        for(int i=0;i<6;i++) {
            layers[i+3]=plan.layers().get(i);
            if(layers[i+3].getDefaultValue()!=0||layers[i+3].getDataSize()!=(i<4?Layer.DataSize.NIBBLE:Layer.DataSize.BIT))return false;
        }
        int meta=400,bytes=0;
        for(int i=0;i<9;i++){offsets[i]=bytes;bytes+=SelectionCopyAccess.length(kinds[i]);}
        int size=meta+32+bytes;
        int[] sources=new int[5];
        for(int i=0;i<5;i++){sources[i]=size;size+=programs[i].bytes();}
        if(size>4194304)return false;
        Worker worker=WORKERS.get();
        if(worker.data==null||worker.data.capacity()<size)worker.data=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer data=worker.data;data.clear().limit(size);
        for(int i=0;i<256;i+=8)data.putLong(i,0);
        data.putInt(0,0x4d494857).putInt(4,5).putInt(8,size).putInt(12,9)
                .putInt(16,factory.getMinHeight()).putInt(20,factory.getMaxHeight())
                .putInt(28,plan.base()).putInt(32,Terrain.DESERT.ordinal()).putInt(36,Terrain.SANDSTONE.ordinal())
                .putInt(40,(int)x).putInt(44,(int)y).putInt(48,plan.water()).putInt(52,plan.desertHeight())
                .putInt(56,Terrain.BARE_GRASS.ordinal()).putInt(60,Terrain.BEACHES.ordinal())
                .putInt(64,plan.dirt()).putInt(68,plan.stone()).putInt(72,factory.getWaterHeight())
                .putInt(120,meta).putInt(124,256);
        for(int i=0;i<9;i++)data.putInt(256+i*16,kinds[i]).putInt(260+i*16,roles[i]).putInt(264+i*16,0).putInt(268+i*16,offsets[i]);
        data.putInt(meta,tx).putInt(meta+4,ty).putLong(meta+8,7).putLong(meta+16,0).putLong(meta+24,0);
        for(int i=0;i<5;i++){data.putInt(208+i*4,sources[i]);programs[i].write(data,sources[i]);}
        long prepared=profiling?System.nanoTime():0;
        if(!NativeSlices.importHeightMapTile(data))return false;
        long calculated=profiling?System.nanoTime():0;
        tile.applyOrderedPlanes(data,meta,layers,roles,kinds,offsets);
        if(profiling){worker.preparation+=prepared-started;worker.nativeTime+=calculated-prepared;worker.application+=System.nanoTime()-calculated;}
        worker.calls++;
        return true;
    }
}
