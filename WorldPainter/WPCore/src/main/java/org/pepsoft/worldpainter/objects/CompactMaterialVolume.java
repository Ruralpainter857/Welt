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
    /** Decode mutable legacy byte planes once per snapshot; transform only distinct materials in Java. */
    public static CompactMaterialVolume captureLegacySchematic(byte[] blocks, byte[] data, byte[] add,
            int[] parameters, org.pepsoft.worldpainter.Platform[] platforms) {
        if (!NativeSlices.isIndexedObjectTransformsAvailable() || parameters == null || parameters.length < 4 || parameters.length > 68
                || parameters[0] < 1 || parameters[1] < 1 || parameters[2] < 1) return null;
        long countLong = (long) parameters[0] * parameters[1] * parameters[2];
        if (countLong < 1 || countLong > 16 * 1024 * 1024 || blocks == null || data == null
                || blocks.length < countLong || data.length < countLong
                || add != null && add.length < (countLong + 1) / 2) return null;
        int count = (int) countLong;
        LegacyWorker worker = LEGACY_WORKERS.get();
        java.util.Arrays.fill(worker.mapping, -1);
        worker.seen.clear(); worker.ids.clear(); worker.palette.clear();
        worker.palette.add(null); worker.ids.put(null, 0);
        for (int i = 0; i < count; i++) {
            if (blocks[i] == 0) continue;
            int high = add == null ? 0 : (add[i / 2] >>> ((i & 1) * 4)) & 15;
            int state = (((blocks[i] & 255) | (high << 8)) << 4) | (data[i] & 15);
            worker.seen.set(state);
        }
        for (int state = worker.seen.nextSetBit(0); state >= 0; state = worker.seen.nextSetBit(state + 1)) {
            Material material = Material.get(state >>> 4, state & 15);
            Integer id = worker.ids.get(material);
            if (id == null) {
                id = worker.palette.size(); worker.ids.put(material, id); worker.palette.add(material);
            }
            worker.mapping[state] = id;
        }
        Material[] transformed = transformPalette(worker.palette.toArray(new Material[0]), parameters, platforms);
        if (transformed == null) return null;
        parameters[3] = transformed.length <= 256 ? 1 : 2;
        byte[] output = new byte[count * parameters[3]];
        if (!NativeSlices.transformLegacyMaterialVolume(blocks, data, add, worker.mapping,
                transformed.length, parameters, output)) return null;
        LEGACY_COMPLETED.incrementAndGet();
        return new CompactMaterialVolume(transformed, output, count);
    }

    public static boolean forEachLegacyMaterial(byte[] blocks, byte[] data, byte[] add, int[] dimensions,
            java.util.function.Consumer<Material> consumer) {
        int[] states = NativeSlices.collectLegacyMaterialStates(blocks, data, add, dimensions);
        if (states == null) return false;
        LEGACY_CLASSIFIED.incrementAndGet();
        for (int state : states) consumer.accept(Material.get(state >>> 4, state & 15));
        return true;
    }
    static long completedLegacyClassifications(){return LEGACY_CLASSIFIED.get();}
    private static final java.util.concurrent.atomic.AtomicLong LEGACY_CLASSIFIED=new java.util.concurrent.atomic.AtomicLong();
    static long completedLegacySnapshots() { return LEGACY_COMPLETED.get(); }
    private static final java.util.concurrent.atomic.AtomicLong LEGACY_COMPLETED = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<LegacyWorker> LEGACY_WORKERS = ThreadLocal.withInitial(LegacyWorker::new);
    private static final class LegacyWorker {
        final int[] mapping = new int[65536];
        final java.util.BitSet seen = new java.util.BitSet(65536);
        final IdentityHashMap<Material, Integer> ids = new IdentityHashMap<>();
        final ArrayList<Material> palette = new ArrayList<>();
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