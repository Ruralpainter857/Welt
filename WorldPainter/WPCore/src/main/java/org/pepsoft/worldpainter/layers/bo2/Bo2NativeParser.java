package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Consumes one bounded source-order frame, preserving the historical object representation. */
final class Bo2NativeParser {
    private static final VarHandle WORD = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final AtomicLong COMPLETED = new AtomicLong();
    record Decoded(Map<String,String> properties, Map<Point3i,Bo2BlockSpec> blocks, Point3i origin, Point3i dimensions) { }
    static long completedObjects() { return COMPLETED.get(); }
    static void completed() { COMPLETED.incrementAndGet(); }
    private static int word(byte[] frame,int offset) { return (int)WORD.get(frame,offset); }
    private static String span(byte[] source,int offset,int length) {
        if(offset<0||length<0||(long)offset+length>source.length)throw new IllegalArgumentException("Invalid native BO2 metadata span");
        return new String(source,offset,length,StandardCharsets.US_ASCII);
    }
    static Decoded decode(byte[] source) {
        byte[] frame=NativeSlices.parseBo2(source);
        if(frame==null)return null;
        if(frame.length<64||word(frame,0)!=0x57424f32||word(frame,4)!=1||word(frame,48)!=frame.length||word(frame,52)!=source.length)
            throw new IllegalArgumentException("Invalid native BO2 frame");
        int count=word(frame,8),propertiesCount=word(frame,12),meta=word(frame,44);
        if(count<1||count>262144||propertiesCount<0||propertiesCount>65536||word(frame,40)!=64
                ||meta!=64+count*32||(long)meta+propertiesCount*16L!=frame.length)
            throw new IllegalArgumentException("Invalid native BO2 frame layout");
        Map<String,String> properties=new HashMap<>();
        for(int i=0;i<propertiesCount;i++) {
            int at=meta+i*16;
            properties.put(span(source,word(frame,at),word(frame,at+4)),span(source,word(frame,at+8),word(frame,at+12)));
        }
        Map<Point3i,Bo2BlockSpec> blocks=new HashMap<>();
        for(int i=0;i<count;i++) {
            int at=64+i*32;
            Point3i coords=new Point3i(word(frame,at),word(frame,at+4),word(frame,at+8));
            int flag=word(frame,at+20);
            if(flag!=0&&flag!=1)throw new IllegalArgumentException("Invalid native BO2 branch marker");
            int[] branch=flag==0?null:new int[]{word(frame,at+24),word(frame,at+28)};
            blocks.put(coords,new Bo2BlockSpec(coords,Material.get(word(frame,at+12),word(frame,at+16)),branch));
        }
        int minX=word(frame,16),minY=word(frame,20),minZ=word(frame,24);
        return new Decoded(properties,blocks,new Point3i(-minX,-minY,-minZ),
                new Point3i(word(frame,28)-minX+1,word(frame,32)-minY+1,word(frame,36)-minZ+1));
    }
}