package org.pepsoft.minecraft;

import org.junit.Test;
import org.pepsoft.util.PackedArrayCube;
import static org.junit.Assert.*;

public class PackedMaterialSectionTest {
    @Test public void paddedWidthsMatchOriginalDecoderWithoutExpandedStorage() {
        for(int count:new int[]{1,16,17,33,129,513,2049,4097,65536}) {
            int bits=Math.max(4,32-Integer.numberOfLeadingZeros(count-1)),perWord=64/bits;
            Material[] palette=new Material[count];for(int i=0;i<count;i++)palette[i]=(i%2==0)?Material.AIR:Material.STONE;
            long[] words=new long[(4096+perWord-1)/perWord];
            for(int i=0;i<4096;i++)words[i/perWord]|=(long)(i%count)<<((i%perWord)*bits);
            PackedMaterialSection view=PackedMaterialSection.tryCreate(words,palette);assertNotNull(view);assertSame(words,view.words);assertSame(palette,view.palette);
            PackedArrayCube<Material> original=new PackedArrayCube<>(16,words,palette,4,false,Material.class);
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertSame(original.getValue(x,z,y),view.get(x,z,y));
            assertEquals(original.isEmpty(),view.isEmpty());
        }
    }
    @Test public void unusualLengthsAndInvalidPaletteIndicesKeepTheOriginalDecoder() {
        assertNull(PackedMaterialSection.tryCreate(new long[255],new Material[]{Material.AIR}));
        long[] invalid=new long[256];invalid[0]=15;
        assertNull(PackedMaterialSection.tryCreate(invalid,new Material[]{Material.AIR,Material.STONE}));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test public void readOnlyChunksRetainPackedWordsAndExactBlockReads() {
        MC118AnvilChunk original=new MC118AnvilChunk(-3,2,-64,320);
        for(int y=-64;y<90;y++)for(int x=0;x<16;x++)for(int z=0;z<16;z++)original.setMaterial(x,y,z,(x+y+z)%7==0?Material.STONE:Material.AIR);
        original.setNamedBiome(0,12,0,"welt:test_biome");original.setNamedBiome(1,12,1,"minecraft:forest");
        var tags=original.toMultipleNBT();
        MC118AnvilChunk packed=new MC118AnvilChunk((java.util.Map)tags,-64,320,true,true);
        boolean found=false;for(var section:packed.getSections())if(section!=null && section.packedMaterials!=null){found=true;assertNull(section.materials);}
        assertTrue(found);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            assertEquals(original.getHighestNonAirBlock(x,z),packed.getHighestNonAirBlock(x,z));
            for(int y=-64;y<320;y++)assertSame(original.getMaterial(x,y,z),packed.getMaterial(x,y,z));
        }
        Material before=packed.getMaterial(0,-64,0);packed.setMaterial(0,-64,0,Material.LAVA);assertSame(before,packed.getMaterial(0,-64,0));
        assertEquals(original.getHighestNonAirBlock(),packed.getHighestNonAirBlock());
        for(int y=-16;y<80;y++)for(int x=0;x<4;x++)for(int z=0;z<4;z++)assertEquals(original.getNamedBiome(x,y,z),packed.getNamedBiome(x,y,z));
        MC118AnvilChunk editing=new MC118AnvilChunk((java.util.Map)tags,-64,320,false,true);
        for(var section:editing.getSections())if(section!=null)assertNull(section.packedMaterials);
        assertNotNull(packed.toMultipleNBT());
    }
}
