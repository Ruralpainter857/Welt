package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.objects.WPObject;
import javax.vecmath.Point3i;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinate-only native storage. Original specifications own materials, RNG and entities. */
final class Bo3CompactBlocks {
    private static final AtomicLong COMPLETED=new AtomicLong();
    private final CompactCoordinateIndex positions;
    private final Bo3BlockSpec[] ordered;
    private final Point3i sourceOrigin;
    private Bo3CompactBlocks(CompactCoordinateIndex positions,Bo3BlockSpec[] ordered,Point3i origin){this.positions=positions;this.ordered=ordered;sourceOrigin=new Point3i(origin);}
    static long completedObjects(){return COMPLETED.get();}
    static Bo3CompactBlocks capture(Map<Point3i,Bo3BlockSpec> blocks,Point3i origin,Point3i dimensions) {
        if(!NativeSlices.isBo3ProjectSnapshotAvailable() || blocks==null || blocks.getClass()!=HashMap.class || blocks.isEmpty()
                || blocks.size()>262144 || origin==null || dimensions==null)return null;
        int count=blocks.size();Bo3BlockSpec[] ordered=new Bo3BlockSpec[count];
        byte[] source=new byte[32+count*16];ByteBuffer records=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        records.putInt(0,0x52505357).putInt(4,2).putInt(8,count).putInt(12,dimensions.x).putInt(16,dimensions.y).putInt(20,dimensions.z).putInt(24,count);
        int index=0;
        for(var entry:blocks.entrySet()){
            Point3i point=entry.getKey();Bo3BlockSpec spec=entry.getValue();
            if(point==null || point.getClass()!=Point3i.class || spec==null || spec.getClass()!=Bo3BlockSpec.class || spec.getCoords()!=point)return null;
            int at=32+index*16;records.putInt(at,point.x+origin.x).putInt(at+4,point.y+origin.y).putInt(at+8,point.z+origin.z).putInt(at+12,index);
            ordered[index++]=spec;
        }
        byte[] frame=NativeSlices.packBo3ProjectSnapshot(source);if(frame==null)return null;
        var result=new Bo3CompactBlocks(new CompactCoordinateIndex(frame,count),ordered,origin);COMPLETED.incrementAndGet();return result;
    }
    Bo3BlockSpec spec(int x,int y,int z,Point3i origin){int index=positions.index(x-origin.x+sourceOrigin.x,y-origin.y+sourceOrigin.y,z-origin.z+sourceOrigin.z);return index<0?null:ordered[index];}
    boolean mask(int x,int y,int z,Point3i origin){return positions.index(x-origin.x+sourceOrigin.x,y-origin.y+sourceOrigin.y,z-origin.z+sourceOrigin.z)>=0;}
    long storageBytes(){return positions.storageBytes();}
    java.util.stream.Stream<Bo3BlockSpec> stream(){return Arrays.stream(ordered);}
    boolean visit(Bo3Object object,WPObject.BlockVisitor visitor,Point3i origin){for(Bo3BlockSpec spec:ordered){Point3i point=spec.getCoords();if(!visitor.visitBlock(object,point.x+origin.x,point.y+origin.y,point.z+origin.z,spec.getMaterial()))return false;}return true;}
    Map<Point3i,Bo3BlockSpec> historicalMap(){Map<Point3i,Bo3BlockSpec> result=new HashMap<>();for(Bo3BlockSpec spec:ordered)result.put(spec.getCoords(),spec);return result;}
}
