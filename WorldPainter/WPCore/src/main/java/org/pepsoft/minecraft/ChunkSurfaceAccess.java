package org.pepsoft.minecraft;

import java.nio.*;
import java.util.IdentityHashMap;
import java.util.function.ToIntFunction;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.pepsoft.util.PackedArrayCube;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.pepsoft.minecraft.Constants.*;
import static org.pepsoft.minecraft.Material.*;

/** One read-only native projection per chunk, with section palettes and worker-owned metadata. */
public final class ChunkSurfaceAccess {
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static final AtomicLong CALLS=new AtomicLong();
    private static final LongAdder PREPARATION=new LongAdder(), NATIVE=new LongAdder();
    public record Profile(long preparationNanos,long nativeNanos) { }
    public static Profile profile(){return new Profile(PREPARATION.sum(),NATIVE.sum());}
    public static void resetProfile(){PREPARATION.reset();NATIVE.reset();}
    private record Semantics(int flags,int snow) { }
    private static final class Scratch {
        ByteBuffer frame;int[][] indexes=new int[0][];long[][] packed=new long[0][];
        final IdentityHashMap<Material,Semantics> semantics=new IdentityHashMap<>();
    }
    private ChunkSurfaceAccess() { }
    public static long completedCalls(){return CALLS.get();}
    public record Result(ByteBuffer data,int output) {
        public float height(int x,int z){return data.getFloat(output+(x*16+z)*16);}
        public int water(int x,int z){return data.getInt(output+(x*16+z)*16+4);}
        public int terrain(int x,int z){return data.getInt(output+(x*16+z)*16+8);}
        public int flags(int x,int z){return data.getInt(output+(x*16+z)*16+12);}
    }
    /** The result is invalidated by the next call on this worker. Unsupported storage retains Java. */
    public static Result analyze(Chunk chunk,int floor,int top,int worldMin,int defaultWater,int bedrock,
                                 int terrains,boolean deep,ToIntFunction<String> terrainMapping) {
        if(!(Boolean.getBoolean("welt.native.mapSurface") || Boolean.getBoolean("welt.native.mapSurfacePacked")) || !NativeLoader.areSlicesAvailable()
                || (chunk.getClass()!=MC118AnvilChunk.class && chunk.getClass()!=MC115AnvilChunk.class))return null;
        boolean profile=Boolean.getBoolean("welt.profile.mapImport");
        long preparationStart=profile?System.nanoTime():0;
        int min=chunk.getMinHeight(),max=chunk.getMaxHeight(),n=(max-min)>>4;
        if(min>=max || (min&15)!=0 || (max&15)!=0 || n<1 || n>256 || floor<min || floor>top || top>=max)return null;
        Scratch scratch=SCRATCH.get();if(scratch.indexes.length!=n){scratch.indexes=new int[n][];scratch.packed=new long[n][];}
        @SuppressWarnings("unchecked") PackedArrayCube<Material>[] cubes=(PackedArrayCube<Material>[])new PackedArrayCube<?>[n];
        Material[] uniform=new Material[n];int[] counts=new int[n];int palettes=0;
        PackedMaterialSection[] sources=new PackedMaterialSection[n];boolean hasPacked=false;
        for(int i=0;i<n;i++){
            if(chunk instanceof MC118AnvilChunk c){int index=(min>>4)+c.undergroundSections+i;
                if(index<0 || index>=c.getSections().length)return null;
                var section=c.getSections()[index];cubes[i]=section==null?null:section.materials;
                uniform[i]=section==null || section.singleMaterial==null?AIR:section.singleMaterial;
                sources[i]=section==null?null:section.packedMaterials;hasPacked|=sources[i]!=null;
            }else{var c=(MC115AnvilChunk)chunk;if(i>=c.getSections().length)return null;
                var section=c.getSections()[i];cubes[i]=section==null?null:section.materials;uniform[i]=AIR;
            }
            if(cubes[i]!=null && !cubes[i].hasPaletteIndexStorage())return null;
            counts[i]=sources[i]!=null?sources[i].palette.length:cubes[i]==null?1:cubes[i].getPaletteIndexCount();
            if(counts[i]<1 || counts[i]>65536)return null;
            palettes+=counts[i];
        }
        long size=64L+n*16L+palettes*12L+4096L;if(size>16*1024*1024)return null;
        int output=(int)size-4096;
        if(scratch.frame==null || scratch.frame.capacity()<size)scratch.frame=ByteBuffer.allocateDirect((int)size).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer d=scratch.frame;d.clear().limit((int)size);for(int i=0;i<64+n*16;i+=4)d.putInt(i,0);
        d.putInt(0,0x46534d57).putInt(4,hasPacked?2:1).putInt(8,(int)size).putInt(12,n).putInt(16,min)
                .putInt(20,floor).putInt(24,top).putInt(28,worldMin).putInt(32,defaultWater).putInt(36,bedrock)
                .putInt(40,deep?1:0).putInt(44,terrains).putInt(48,64+n*16).putInt(52,output).putInt(56,256);
        int cursor=64+n*16;
        if(scratch.semantics.size()>8192)scratch.semantics.clear();
        try{
            for(int s=0;s<n;s++){
                scratch.indexes[s]=cubes[s]==null?null:cubes[s].getPaletteIndexesForBulkUpdate();
                scratch.packed[s]=sources[s]==null?null:sources[s].words;
                d.putInt(64+s*16,cursor).putInt(68+s*16,counts[s]).putInt(72+s*16,sources[s]!=null?2:cubes[s]==null?1:0);
                for(int p=0;p<counts[s];p++){
                    Material material=sources[s]!=null?sources[s].palette[p]:cubes[s]==null?uniform[s]:cubes[s].getPaletteValue(p);
                    if(material==null)material=AIR;
                    Semantics semantics=scratch.semantics.get(material);
                    if(semantics==null){
                        String name=material.name;
                        int flags=(material==AIR?1:0)|(material.natural?2:0)
                                |((name==MC_SNOW || name==MC_ICE)?4:0)
                                |((name==MC_ICE || name==MC_FROSTED_ICE || material.watery
                                || (((name==MC_WATER || name==MC_LAVA) && material.getProperty(LEVEL)==0))
                                || material.is(WATERLOGGED))?8:0)|(name==MC_LAVA?16:0)|(material.isNamed(MC_SNOW)?32:0);
                        semantics=new Semantics(flags,material.isNamed(MC_SNOW)?material.getProperty(LAYERS):0);
                        if(scratch.semantics.size()>=8192)scratch.semantics.clear();
                        scratch.semantics.put(material,semantics);
                    }
                    d.putInt(cursor,semantics.flags).putInt(cursor+4,terrainMapping.applyAsInt(material.name)).putInt(cursor+8,semantics.snow);cursor+=12;
                }
            }
            long nativeStart=profile?System.nanoTime():0;
            int code=hasPacked?nativeAnalyzeSections(scratch.indexes,scratch.packed,d,(int)size):nativeAnalyze(scratch.indexes,d,(int)size);
            if(code!=0)return null;
            if(profile){PREPARATION.add(nativeStart-preparationStart);NATIVE.add(System.nanoTime()-nativeStart);}
            CALLS.incrementAndGet();return new Result(d,output);
        }catch(UnsatisfiedLinkError | IllegalArgumentException | NullPointerException ex){return null;}
        finally{java.util.Arrays.fill(scratch.indexes,null);java.util.Arrays.fill(scratch.packed,null);}
    }
    private static native int nativeAnalyze(int[][] sections,ByteBuffer frame,int length);
    private static native int nativeAnalyzeSections(int[][] sections,long[][] packed,ByteBuffer frame,int length);
}
