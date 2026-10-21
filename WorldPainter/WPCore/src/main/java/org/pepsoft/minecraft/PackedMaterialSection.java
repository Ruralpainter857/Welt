package org.pepsoft.minecraft;

/** Read-only borrowed view of validated NBT longs; the owning chunk keeps the arrays unchanged. */
final class PackedMaterialSection {
    final long[] words;
    final Material[] palette;
    final int bits, perWord;
    final long mask;
    private PackedMaterialSection(long[] words,Material[] palette,int bits) {
        this.words=words;this.palette=palette;this.bits=bits;perWord=64/bits;mask=(1L<<bits)-1;
    }
    /** Unusual lengths or invalid indices use the original decoder and its original diagnostics. */
    static PackedMaterialSection tryCreate(long[] words,Material[] palette) {
        if(palette.length<1 || palette.length>65536)return null;
        int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.length-1)),perWord=64/bits;
        if(words.length!=(4096+perWord-1)/perWord)return null;
        PackedMaterialSection source=new PackedMaterialSection(words,palette,bits);
        for(int i=0;i<4096;i++)if(source.index(i)>=palette.length)return null;
        return source;
    }
    int index(int position){return bits==4?(int)((words[position>>>4]>>>((position&15)<<2))&15):(int)((words[position/perWord]>>>((position%perWord)*bits))&mask);}
    Material get(int x,int z,int y){return palette[index(x+z*16+y*256)];}
    boolean isEmpty(){for(int i=0;i<4096;i++){Material m=palette[index(i)];if(m!=null)return false;}return true;}
}
