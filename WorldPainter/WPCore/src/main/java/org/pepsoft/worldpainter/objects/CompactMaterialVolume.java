package org.pepsoft.worldpainter.objects;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Immutable palette storage; null and an explicitly present air block remain distinct. */
public final class CompactMaterialVolume {
    private final Material[] palette;
    private final byte[] indices;
    private final int count, stride;

    private CompactMaterialVolume(Material[] palette, byte[] indices, int count) {
        this.palette = palette;
        this.indices = indices;
        this.count = count;
        stride = palette.length <= 256 ? 1 : 2;
    }
    static CompactMaterialVolume capture(Material[] materials) {
        if (!NativeSlices.isMaterialVolumeAvailable() || materials.length < 1 || materials.length > 16 * 1024 * 1024) return null;
        IdentityHashMap<Material, Integer> ids = new IdentityHashMap<>();
        ArrayList<Material> palette = new ArrayList<>();
        palette.add(null); ids.put(null, 0);
        int[] indices = new int[materials.length];
        for (int i = 0; i < materials.length; i++) {
            Material material = materials[i];
            Integer id = ids.get(material);
            if (id == null) {
                if (palette.size() == 65536) return null;
                id = palette.size(); ids.put(material, id); palette.add(material);
            }
            indices[i] = id;
        }
        int stride = palette.size() <= 256 ? 1 : 2;
        byte[] packed = new byte[materials.length * stride];
        if (!NativeSlices.packMaterialVolume(indices, palette.size(), packed)) return null;
        return new CompactMaterialVolume(palette.toArray(new Material[0]), packed, materials.length);
    }
    /** Read-only source arrays are borrowed once; output storage belongs to the snapshot. */
    public static CompactMaterialVolume captureSchematic(Material[] sourcePalette, byte[] packed, int[] integers, int sourceStride, int count) {
        if (!NativeSlices.isMaterialVolumeAvailable() || count < 1 || count > 16 * 1024 * 1024 || sourcePalette.length < 1 || sourcePalette.length > 65536) return null;
        IdentityHashMap<Material, Integer> ids = new IdentityHashMap<>();
        ArrayList<Material> palette = new ArrayList<>();palette.add(null);ids.put(null,0);
        int[] mapping = new int[sourcePalette.length];
        for(int i=0;i<sourcePalette.length;i++) {
            Material material=sourcePalette[i];
            if(material==null || material==Material.AIR) continue;
            Integer id=ids.get(material);
            if(id==null){if(palette.size()==65536)return null;id=palette.size();ids.put(material,id);palette.add(material);}
            mapping[i]=id;
        }
        byte[] output=new byte[count*(palette.size()<=256?1:2)];
        if(!NativeSlices.remapMaterialVolume(packed,integers,sourceStride,mapping,palette.size(),output))return null;
        return new CompactMaterialVolume(palette.toArray(new Material[0]),output,count);
    }
    /** One complete indexed-source transformation; source palette and voxel plane stay unchanged. */
    public static CompactMaterialVolume captureTransformedSchematic(Material[] sourcePalette,byte[] packed,int[] integers,int sourceStride,int count,int[] parameters,org.pepsoft.worldpainter.Platform[] platforms) {
        if(!NativeSlices.isIndexedObjectTransformsAvailable() || count<1 || count>16*1024*1024 || sourcePalette.length<1 || sourcePalette.length>65536)return null;
        IdentityHashMap<Material,Integer> ids=new IdentityHashMap<>();ArrayList<Material> palette=new ArrayList<>();palette.add(null);ids.put(null,0);
        int[] mapping=new int[sourcePalette.length];
        for(int i=0;i<sourcePalette.length;i++){
            Material material=sourcePalette[i];if(material==null || material==Material.AIR)continue;
            Integer id=ids.get(material);if(id==null){if(palette.size()==65536)return null;id=palette.size();ids.put(material,id);palette.add(material);}mapping[i]=id;
        }
        Material[] transformed=transformPalette(palette.toArray(new Material[0]),parameters,platforms);if(transformed==null)return null;
        parameters[3]=palette.size()<=256?1:2;
        byte[] output=new byte[count*parameters[3]];
        if(!NativeSlices.remapTransformedMaterialVolume(packed,integers,sourceStride,mapping,palette.size(),parameters,output))return null;
        return new CompactMaterialVolume(transformed,output,count);
    }
    Material get(int index) {
        if (index < 0 || index >= count) throw new ArrayIndexOutOfBoundsException(index);
        int p = index * stride;
        int id = indices[p] & 255;
        if (stride == 2) id |= (indices[p + 1] & 255) << 8;
        return palette[id];
    }
    CompactMaterialVolume transform(int[] parameters, org.pepsoft.worldpainter.Platform[] platforms) {
        Material[] transformed=transformPalette(palette,parameters,platforms);if(transformed==null)return null;
        parameters[3]=stride;byte[] output=new byte[indices.length];
        if(!NativeSlices.transformMaterialVolume(indices,parameters,output))return null;
        return new CompactMaterialVolume(transformed,output,count);
    }
    private static Material[] transformPalette(Material[] palette,int[] parameters,org.pepsoft.worldpainter.Platform[] platforms) {
        if(parameters==null || platforms==null || parameters.length!=4+platforms.length)return null;
        for(int i=4;i<parameters.length;i++)if(parameters[i]<0 || parameters[i]>5)return null;
        Material[] transformed=palette.clone();
        try {
            for(int i=1;i<transformed.length;i++)for(int j=0;j<platforms.length;j++) {
                int operation=parameters[j+4];
                if(operation<4){if(operation!=0)transformed[i]=transformed[i].rotate(operation,platforms[j]);}
                else transformed[i]=transformed[i].mirror(operation==5?org.pepsoft.minecraft.Direction.EAST:org.pepsoft.minecraft.Direction.SOUTH,platforms[j]);
            }
        }catch(RuntimeException unsupported){return null;}
        return transformed;
    }
    Material[] expand() {
        Material[] result = new Material[count];
        for (int i = 0; i < count; i++) result[i] = get(i);
        return result;
    }
}