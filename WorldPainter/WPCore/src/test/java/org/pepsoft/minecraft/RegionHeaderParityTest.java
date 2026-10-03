package org.pepsoft.minecraft;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

public class RegionHeaderParityTest {
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    @Test public void bothTableSizesDecodeEveryWordAndRejectedEntryKeepsJava() throws Exception {
        String flag=System.getProperty("welt.native.regionHeader"),kernel=System.getProperty("welt.native.regionHeaderKernel");
        try{
            System.setProperty("welt.native.regionHeader","true");System.setProperty("welt.native.regionHeaderKernel","true");
            for(int sectors:new int[]{1,2}){
                byte[] bytes=new byte[sectors*4096];ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                for(int i=0;i<bytes.length/4;i++)data.putInt(i*4,i*982451653);
                Path path=Files.createTempFile("welt-region-header-",".mca");Files.write(path,bytes);
                for(boolean reject:new boolean[]{false,true}){
                    int[] offsets=new int[1024],timestamps=new int[1024];long calls=RegionHeaderAccess.completedCalls();
                    try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"r")){
                        assertTrue(RegionHeaderAccess.read(file,sectors,offsets,timestamps,reject?b->false:NativeSlices::decodeRegionHeader));
                        assertEquals(bytes.length,file.getFilePointer());
                    }
                    assertEquals(reject?0:1,RegionHeaderAccess.completedCalls()-calls);
                    for(int i=0;i<1024;i++){assertEquals(data.getInt(i*4),offsets[i]);assertEquals(sectors==2?data.getInt(4096+i*4):0,timestamps[i]);}
                    assertArrayEquals(bytes,Files.readAllBytes(path));
                }
            }
        }finally{restore("welt.native.regionHeader",flag);restore("welt.native.regionHeaderKernel",kernel);}
    }
    @Test public void validRegionsPreservePayloadFreeSectorsAndReadOnlyBytes() throws Exception {
        String flag=System.getProperty("welt.native.regionHeader"),kernel=System.getProperty("welt.native.regionHeaderKernel");
        try{
            System.setProperty("welt.native.regionHeaderKernel","true");Path root=Files.createTempDirectory("welt-region-payload-");
            Path source=root.resolve("r.-2.3.mca");System.setProperty("welt.native.regionHeader","false");
            try(RegionFile region=new RegionFile(source.toFile())){
                try(DataOutputStream out=region.getChunkDataOutputStream(4,7)){for(int i=0;i<10000;i++)out.writeInt(i*214013);}
            }
            byte[] before=Files.readAllBytes(source);
            for(boolean nativeMode:new boolean[]{false,true}){
                System.setProperty("welt.native.regionHeader",Boolean.toString(nativeMode));
                try(RegionFile region=new RegionFile(source.toFile(),true)){
                    assertEquals(1,region.getChunkCount());assertTrue(region.containsChunk(4,7));assertFalse(region.containsChunk(5,7));
                    try(DataInputStream in=region.getChunkDataInputStream(4,7)){for(int i=0;i<10000;i++)assertEquals(i*214013,in.readInt());assertEquals(-1,in.read());}
                }
                assertArrayEquals(before,Files.readAllBytes(source));
            }
            long[] lengths=new long[2];
            for(int mode=0;mode<2;mode++){
                Path directory=root.resolve("write"+mode);Files.createDirectories(directory);Path target=directory.resolve("r.-2.3.mca");Files.write(target,before);
                System.setProperty("welt.native.regionHeader",Boolean.toString(mode==1));
                try(RegionFile region=new RegionFile(target.toFile())){
                    try(DataOutputStream out=region.getChunkDataOutputStream(5,7)){out.writeUTF("A second chunk");}
                    assertEquals(2,region.getChunkCount());
                    try(DataInputStream in=region.getChunkDataInputStream(5,7)){assertEquals("A second chunk",in.readUTF());}
                }
                lengths[mode]=Files.size(target);
            }
            assertEquals(lengths[0],lengths[1]);
        }finally{restore("welt.native.regionHeader",flag);restore("welt.native.regionHeaderKernel",kernel);}
    }
    private static Class<?> failure(Path path,boolean nativeMode) throws Exception {
        System.setProperty("welt.native.regionHeader",Boolean.toString(nativeMode));
        try(RegionFile ignored=new RegionFile(path.toFile(),true)){return null;}
        catch(IOException|RuntimeException e){return e.getClass();}
    }
    @Test public void truncatedHeadersAndInvalidOffsetsKeepOriginalErrorOrder() throws Exception {
        String flag=System.getProperty("welt.native.regionHeader"),kernel=System.getProperty("welt.native.regionHeaderKernel");
        try{
            System.setProperty("welt.native.regionHeaderKernel","true");Path root=Files.createTempDirectory("welt-region-corrupt-");
            for(int size:new int[]{0,1,4095,4096,4100,8191,8192})for(boolean negative:new boolean[]{false,true}){
                Path directory=root.resolve(size+"-"+negative);Files.createDirectories(directory);Path file=directory.resolve("r.0.0.mca");byte[] bytes=new byte[size];
                if(negative&&size>=4)ByteBuffer.wrap(bytes).putInt(-255);Files.write(file,bytes);
                assertEquals("Size "+size+" negative "+negative,failure(file,false),failure(file,true));
            }
        }finally{restore("welt.native.regionHeader",flag);restore("welt.native.regionHeaderKernel",kernel);}
    }
    @Test public void nativeWrapperRejectsInvalidBuffersWithoutMutation(){
        byte[] original=new byte[4097];Arrays.fill(original,(byte)73);
        ByteBuffer direct=ByteBuffer.allocateDirect(original.length);direct.put(original).flip();assertFalse(NativeSlices.decodeRegionHeader(direct));
        byte[] actual=new byte[original.length];direct.get(actual);assertArrayEquals(original,actual);
        assertFalse(NativeSlices.decodeRegionHeader(ByteBuffer.allocate(8192)));
        assertFalse(NativeSlices.decodeRegionHeader(ByteBuffer.allocateDirect(8192).asReadOnlyBuffer()));
    }
}