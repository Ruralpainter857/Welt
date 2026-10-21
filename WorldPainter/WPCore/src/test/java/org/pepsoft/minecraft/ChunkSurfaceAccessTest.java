package org.pepsoft.minecraft;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.pepsoft.minecraft.Material.*;

/** Exercises both section formats and compares the complete column projection to the Java scan. */
public class ChunkSurfaceAccessTest {
    @Test public void surfacesPreserveFluidSnowCustomBlocksAndInputArrays() {
        String nativeSaved=System.getProperty("welt.native.mapSurface");
        String storageSaved=System.getProperty("welt.packedArrayCube.compactPaletteStorage");
        try {
            System.setProperty("welt.native.mapSurface","true");
            System.setProperty("welt.packedArrayCube.compactPaletteStorage","true");
            for(Chunk chunk:new Chunk[]{new MC115AnvilChunk(-5,7,256),new MC118AnvilChunk(-5,7,-64,320)}) {
                int floor=chunk.getMinHeight();
                for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                    if(x==15)continue;
                    int height=40+x;
                    chunk.setMaterial(x,height,z,STONE);
                    chunk.setMaterial(x,height-3,z,BRICKS);
                    switch(z%6) {
                        case 0 -> chunk.setMaterial(x,height+1,z,SNOW.withProperty(LAYERS,x%8+1));
                        case 1 -> chunk.setMaterial(x,height+2,z,LAVA);
                        case 2 -> chunk.setMaterial(x,height+2,z,WATER.withProperty(LEVEL,3));
                        case 3 -> chunk.setMaterial(x,height+2,z,Material.get("minecraft:oak_leaves").withProperty(WATERLOGGED,true));
                        case 4 -> chunk.setMaterial(x,height+2,z,Material.get("welt:custom_structure"));
                        case 5 -> chunk.setMaterial(x,height+2,z,Material.get("minecraft:cave_air"));
                    }
                }
                for(boolean deep:new boolean[]{false,true}) {
                    float[] heights=new float[256];int[] water=new int[256],flags=new int[256];
                    for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                        int c=x*16+z;float height=-Float.MAX_VALUE;int level=Integer.MIN_VALUE,bits=0;
                        for(int y=Math.min(chunk.getMaxHeight()-1,chunk.getHighestNonAirBlock(x,z));y>=floor;y--) {
                            Material m=chunk.getMaterial(x,y,z);String name=m.name;
                            if(!m.natural)bits|=height==-Float.MAX_VALUE?8:16;
                            if(name==Constants.MC_SNOW || name==Constants.MC_ICE)bits|=1;
                            if(level==Integer.MIN_VALUE && (name==Constants.MC_ICE || name==Constants.MC_FROSTED_ICE || m.watery
                                    || ((name==Constants.MC_WATER || name==Constants.MC_LAVA)&&m.getProperty(LEVEL)==0) || m.is(WATERLOGGED))) {
                                level=y;if(name==Constants.MC_LAVA)bits|=2;
                            } else if(height==-Float.MAX_VALUE && name==Constants.MC_STONE) {
                                height=y-.4375f;if(level==Integer.MIN_VALUE)level=y>=62?62:floor;if(!deep)break;
                            }
                        }
                        int rounded=Math.round(height);
                        if(height!=-Float.MAX_VALUE && rounded<chunk.getMaxHeight()-1) {
                            Material above=chunk.getMaterial(x,rounded+1,z);
                            if(above.isNamed(Constants.MC_SNOW))height+=above.getProperty(LAYERS)*.125;
                        }
                        if(level==Integer.MIN_VALUE)level=height>=61.5f?62:floor;
                        if(height==-Float.MAX_VALUE)bits|=4;
                        heights[c]=height;water[c]=level;flags[c]=bits;
                    }
                    Material[] before=new Material[(chunk.getMaxHeight()-floor)*256];
                    for(int y=floor;y<chunk.getMaxHeight();y++)for(int x=0;x<16;x++)for(int z=0;z<16;z++)before[(y-floor)*256+x*16+z]=chunk.getMaterial(x,y,z);
                    ChunkSurfaceAccess.Result result=ChunkSurfaceAccess.analyze(chunk,floor,chunk.getMaxHeight()-1,floor,62,0,2,deep,name->name==Constants.MC_STONE?1:-1);
                    assertNotNull("Native projection must execute for "+chunk.getClass().getSimpleName(),result);
                    for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                        int c=x*16+z;assertEquals(Float.floatToIntBits(heights[c]),Float.floatToIntBits(result.height(x,z)));
                        assertEquals(water[c],result.water(x,z));assertEquals(flags[c],result.flags(x,z));
                        assertEquals(heights[c]==-Float.MAX_VALUE?0:1,result.terrain(x,z));
                    }
                    for(int y=floor;y<chunk.getMaxHeight();y++)for(int x=0;x<16;x++)for(int z=0;z<16;z++)assertSame(before[(y-floor)*256+x*16+z],chunk.getMaterial(x,y,z));
                }
            }
        } finally {
            if(nativeSaved==null)System.clearProperty("welt.native.mapSurface");else System.setProperty("welt.native.mapSurface",nativeSaved);
            if(storageSaved==null)System.clearProperty("welt.packedArrayCube.compactPaletteStorage");else System.setProperty("welt.packedArrayCube.compactPaletteStorage",storageSaved);
        }
    }
}
