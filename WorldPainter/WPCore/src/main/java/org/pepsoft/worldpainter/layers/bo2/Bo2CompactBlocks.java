package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.objects.WPObject;
import javax.vecmath.Point3i;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable material plane plus the original callback order and historical branch metadata. */
final class Bo2CompactBlocks {
    private static final AtomicLong COMPLETED=new AtomicLong();
    private final StructureCompactBlocks materials;
    private final int[] coordinates;
    private final int[][] branches;
    private final Point3i sourceOrigin;
    private Bo2CompactBlocks(StructureCompactBlocks materials,int[] coordinates,int[][] branches,Point3i sourceOrigin) {
        this.materials=materials;this.coordinates=coordinates;this.branches=branches;this.sourceOrigin=new Point3i(sourceOrigin);
    }
    static long completedObjects(){return COMPLETED.get();}
    static Bo2CompactBlocks capture(Map<Point3i,Bo2BlockSpec> blocks,Point3i origin,Point3i dimensions) {
        if(!NativeSlices.isBo2ProjectSnapshotAvailable() || blocks==null || blocks.getClass()!=HashMap.class
                || blocks.isEmpty() || blocks.size()>262144 || origin==null || dimensions==null)return null;
        int count=blocks.size();byte[] source=new byte[32+count*16];
        ByteBuffer records=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        records.putInt(0,0x52505357).putInt(4,1).putInt(8,count).putInt(12,dimensions.x).putInt(16,dimensions.y).putInt(20,dimensions.z);
        int[] coordinates=new int[count*3];int[][] branches=new int[count][];
        IdentityHashMap<Material,Integer> ids=new IdentityHashMap<>();ArrayList<Material> palette=new ArrayList<>();
        int index=0;
        for(var entry:blocks.entrySet()) {
            Point3i point=entry.getKey();Bo2BlockSpec spec=entry.getValue();
            if(point==null || point.getClass()!=Point3i.class || spec==null || spec.getClass()!=Bo2BlockSpec.class
                    || spec.getCoords()!=point || spec.getMaterial()==null)return null;
            Material material=spec.getMaterial();Integer id=ids.get(material);
            if(id==null){if(palette.size()==65535)return null;id=palette.size();ids.put(material,id);palette.add(material);}
            int at=32+index*16;
            records.putInt(at,point.x+origin.x).putInt(at+4,point.y+origin.y).putInt(at+8,point.z+origin.z).putInt(at+12,id);
            coordinates[index*3]=point.x;coordinates[index*3+1]=point.y;coordinates[index*3+2]=point.z;
            branches[index]=spec.getBranch();index++;
        }
        records.putInt(24,palette.size());byte[] frame=NativeSlices.packBo2ProjectSnapshot(source);if(frame==null)return null;
        var result=new Bo2CompactBlocks(new StructureCompactBlocks(frame,palette.toArray(new Material[0])),coordinates,branches,origin);
        COMPLETED.incrementAndGet();return result;
    }
    Material material(int x,int y,int z,Point3i origin){return materials.material(x-origin.x+sourceOrigin.x,y-origin.y+sourceOrigin.y,z-origin.z+sourceOrigin.z);}
    boolean mask(int x,int y,int z,Point3i origin){return materials.mask(x-origin.x+sourceOrigin.x,y-origin.y+sourceOrigin.y,z-origin.z+sourceOrigin.z,false);}
    long storageBytes(){return materials.storageBytes();}
    boolean visit(Bo2Object object,WPObject.BlockVisitor visitor,Point3i origin) {
        for(int i=0;i<branches.length;i++) {
            int x=coordinates[i*3],y=coordinates[i*3+1],z=coordinates[i*3+2];
            if(!visitor.visitBlock(object,x+origin.x,y+origin.y,z+origin.z,
                    materials.material(x+sourceOrigin.x,y+sourceOrigin.y,z+sourceOrigin.z)))return false;
        }
        return true;
    }
    Map<Point3i,Bo2BlockSpec> historicalMap() {
        Map<Point3i,Bo2BlockSpec> result=new HashMap<>();
        for(int i=0;i<branches.length;i++) {
            var point=new Point3i(coordinates[i*3],coordinates[i*3+1],coordinates[i*3+2]);
            result.put(point,new Bo2BlockSpec(point,materials.material(point.x+sourceOrigin.x,point.y+sourceOrigin.y,point.z+sourceOrigin.z),branches[i]));
        }
        return result;
    }
}
