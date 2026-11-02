package org.pepsoft.worldpainter.objects;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Preview-builder-owned palette storage. Columns are contiguous along Z.
 * Publish the completed world to readers after rendering, as for the original mutable world.
 * AIR and null remain distinct values; material identity and properties are retained.
 */
final class MutableMaterialVolume {
    private final int width, length, height, cells;
    private byte[] narrow;
    private char[] wide;
    private Material[] dense;
    private Material[] palette;
    private IdentityHashMap<Material,Integer> identifiers;
    private int paletteSize;
    private Material lastMaterial;
    private int lastIdentifier;
    private static final LongAdder NATIVE_PROMOTIONS=new LongAdder();

    MutableMaterialVolume(int width,int length,int height,Material[] lowest) {
        this.width=width;this.length=length;this.height=height;
        cells=Math.toIntExact((long)width*length*height);
        reset(lowest);
    }
    private MutableMaterialVolume(MutableMaterialVolume source) {
        width=source.width;length=source.length;height=source.height;cells=source.cells;
        narrow=source.narrow==null?null:source.narrow.clone();wide=source.wide==null?null:source.wide.clone();
        dense=source.dense==null?null:source.dense.clone();palette=source.palette==null?null:source.palette.clone();
        identifiers=source.identifiers==null?null:new IdentityHashMap<>(source.identifiers);
        paletteSize=source.paletteSize;lastMaterial=source.lastMaterial;lastIdentifier=source.lastIdentifier;
    }
    MutableMaterialVolume copy(){return new MutableMaterialVolume(this);}
    private int index(int x,int y,int z) {
        if(x<0||x>=width||y<0||y>=length||z<0||z>=height)throw new ArrayIndexOutOfBoundsException();
        return (x*length+y)*height+z;
    }
    Material get(int x,int y,int z){return get(index(x,y,z));}
    private Material get(int index){return dense!=null?dense[index]:palette[narrow!=null?narrow[index]&255:wide[index]];}
    void set(int x,int y,int z,Material material) {
        int index=index(x,y,z);
        if(dense!=null){dense[index]=material;return;}
        int identifier=identifier(material);
        if(dense!=null)dense[index]=material;
        else if(narrow!=null)narrow[index]=(byte)identifier;
        else wide[index]=(char)identifier;
    }
    private int identifier(Material material) {
        if(material==Material.AIR)return 0;if(material==null)return 1;
        if(material==lastMaterial)return lastIdentifier;
        Integer existing=identifiers.get(material);
        if(existing!=null){lastMaterial=material;return lastIdentifier=existing;}
        if(paletteSize==65536){materialiseDense();return -1;}
        if(paletteSize==256) {
            char[] output=new char[cells];
            if(NativeSlices.widenMaterialIndices(narrow,output))NATIVE_PROMOTIONS.increment();
            else for(int i=0;i<cells;i++)output[i]=(char)(narrow[i]&255);
            wide=output;narrow=null;
        }
        if(paletteSize==palette.length)palette=Arrays.copyOf(palette,Math.min(65536,palette.length*2));
        int identifier=paletteSize++;palette[identifier]=material;identifiers.put(material,identifier);
        lastMaterial=material;return lastIdentifier=identifier;
    }
    private void materialiseDense() {
        Material[] output=new Material[cells];for(int i=0;i<cells;i++)output[i]=get(i);
        dense=output;narrow=null;wide=null;palette=null;identifiers=null;lastMaterial=null;
    }
    void reset(Material[] lowest) {
        if(width>0&&length>0&&lowest!=null&&lowest.length>height)throw new ArrayIndexOutOfBoundsException();
        if(narrow==null)narrow=new byte[cells];else Arrays.fill(narrow,(byte)0);
        wide=null;dense=null;palette=new Material[16];palette[0]=Material.AIR;
        paletteSize=2;identifiers=new IdentityHashMap<>();lastMaterial=null;
        if(width==0||length==0||lowest==null||lowest.length==0)return;
        int[] ids=new int[lowest.length];
        for(int i=0;i<ids.length;i++)if(dense==null)ids[i]=identifier(lowest[i]);
        if(dense!=null){Arrays.fill(dense,Material.AIR);for(int p=0;p<cells;p+=height)System.arraycopy(lowest,0,dense,p,lowest.length);}
        else if(narrow!=null){byte[] column=new byte[ids.length];for(int i=0;i<ids.length;i++)column[i]=(byte)ids[i];for(int p=0;p<cells;p+=height)System.arraycopy(column,0,narrow,p,column.length);}
        else {char[] column=new char[ids.length];for(int i=0;i<ids.length;i++)column[i]=(char)ids[i];for(int p=0;p<cells;p+=height)System.arraycopy(column,0,wide,p,column.length);}
    }
    Material[][][] toLegacy() {
        Material[][][] output=new Material[width][length][height];
        for(int x=0;x<width;x++)for(int y=0;y<length;y++)for(int z=0;z<height;z++)output[x][y][z]=get(x,y,z);
        return output;
    }
    static MutableMaterialVolume fromLegacy(Material[][][] source,int width,int length,int height) {
        if(source==null||source.length!=width)return null;
        for(Material[][] slice:source){if(slice==null||slice.length!=length)return null;for(Material[] column:slice)if(column==null||column.length!=height)return null;}
        MutableMaterialVolume result=new MutableMaterialVolume(width,length,height,null);
        for(int x=0;x<width;x++)for(int y=0;y<length;y++)for(int z=0;z<height;z++)result.set(x,y,z,source[x][y][z]);
        return result;
    }
    static long nativePromotions(){return NATIVE_PROMOTIONS.sum();}
}
