package org.pepsoft.minecraft;

import org.jnbt.*;
import org.junit.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Lossless values and original-reader fallback across the whole NBT type system. */
public class ChunkTagReaderParityTest {
    private String previous, previousKernel;
    @Before public void enable() {
        previous=System.getProperty("welt.native.chunkNbt");previousKernel=System.getProperty("welt.native.chunkNbtKernel");
        System.setProperty("welt.native.chunkNbt","true");System.setProperty("welt.native.chunkNbtKernel","true");
    }
    @After public void restore() { restore("welt.native.chunkNbt",previous);restore("welt.native.chunkNbtKernel",previousKernel); }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static byte[] bytes(Tag tag) throws IOException {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(NBTOutputStream out=new NBTOutputStream(buffer)){out.writeTag(tag);}return buffer.toByteArray();
    }
    private static Tag java(byte[] bytes) throws IOException {try(NBTInputStream in=new NBTInputStream(new ByteArrayInputStream(bytes))){return in.readTag();}}
    private static Tag nativeTag(byte[] bytes) throws IOException {return ChunkTagReader.read(()->new ByteArrayInputStream(bytes));}
    private static void same(Tag expected,Tag actual) {
        assertEquals(expected.getClass(),actual.getClass());assertEquals(expected.getName(),actual.getName());
        if(expected instanceof EndTag)return;
        Object a=value(expected),b=value(actual);
        if(a instanceof byte[])assertArrayEquals((byte[])a,(byte[])b);
        else if(a instanceof int[])assertArrayEquals((int[])a,(int[])b);
        else if(a instanceof long[])assertArrayEquals((long[])a,(long[])b);
        else if(a instanceof Float)assertEquals(Float.floatToRawIntBits((Float)a),Float.floatToRawIntBits((Float)b));
        else if(a instanceof Double)assertEquals(Double.doubleToRawLongBits((Double)a),Double.doubleToRawLongBits((Double)b));
        else if(expected instanceof ListTag<?> left){ListTag<?> right=(ListTag<?>)actual;assertEquals(left.getType(),right.getType());assertEquals(left.getValue().size(),right.getValue().size());for(int i=0;i<left.getValue().size();i++)same(left.getValue().get(i),right.getValue().get(i));}
        else if(expected instanceof CompoundTag left){Map<String,Tag> right=((CompoundTag)actual).getValue();assertEquals(left.getValue().keySet(),right.keySet());left.getValue().forEach((name,value)->same(value,right.get(name)));}
        else assertEquals(a,b);
    }
    private static Object value(Tag tag){try{return tag.getClass().getMethod("getValue").invoke(tag);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static Tag random(Random random,String name,int depth) {
        int type=random.nextInt(depth==0?9:13);
        return switch(type) {
            case 0 -> new ByteTag(name,(byte)random.nextInt());
            case 1 -> new ShortTag(name,(short)random.nextInt());
            case 2 -> new IntTag(name,random.nextInt());
            case 3 -> new LongTag(name,random.nextLong());
            case 4 -> new FloatTag(name,Float.intBitsToFloat(random.nextInt()));
            case 5 -> new DoubleTag(name,Double.longBitsToDouble(random.nextLong()));
            case 6 -> new StringTag(name,"water\0雪🙂"+random.nextInt());
            case 7 -> {byte[] a=new byte[random.nextInt(200)];random.nextBytes(a);yield new ByteArrayTag(name,a);}
            case 8 -> {long[] a=new long[random.nextInt(120)];for(int i=0;i<a.length;i++)a[i]=random.nextLong();yield new LongArrayTag(name,a);}
            case 9 -> {Map<String,Tag> a=new HashMap<>();for(int i=0,n=random.nextInt(8);i<n;i++)a.put("tag"+i,random(random,"tag"+i,depth-1));yield new CompoundTag(name,a);}
            case 10 -> {List<CompoundTag> a=new ArrayList<>();for(int i=0,n=random.nextInt(5);i<n;i++)a.add(new CompoundTag("",Map.of("child",random(random,"child",depth-1))));yield new ListTag<>(name,CompoundTag.class,a);}
            case 11 -> new ListTag<>(name,EndTag.class,List.of());
            default -> {int[] a=new int[random.nextInt(120)];for(int i=0;i<a.length;i++)a[i]=random.nextInt();yield new IntArrayTag(name,a);}
        };
    }
    @Test public void allTypesNestedTreesArraysAndWorkerReuseMatch() throws Exception {
        Random random=new Random(7331);long calls=ChunkTagReader.completedCalls();
        for(int i=0;i<300;i++){byte[] input=bytes(random(random,"root-é雪🙂",4));same(java(input),nativeTag(input));}
        assertEquals(300,ChunkTagReader.completedCalls()-calls);
        byte[] end={0};same(java(end),nativeTag(end));
        byte[] invalidUtf={8,0,1,(byte)255,0,2,(byte)192,(byte)175};same(java(invalidUtf),nativeTag(invalidUtf));
        // Preserve raw noncanonical NaN bits rather than serialising them through DataOutput.
        byte[] nan={5,0,0,127,(byte)192,0,3};same(java(nan),nativeTag(nan));
    }
    @Test public void duplicateNamesAndIgnoredTrailingBytesMatch() throws Exception {
        byte[] duplicate={10,0,0,1,0,1,97,1,1,0,1,97,2,0,99,98};
        long calls=ChunkTagReader.completedCalls();same(java(duplicate),nativeTag(duplicate));assertEquals(calls+1,ChunkTagReader.completedCalls());
        byte[] input=bytes(new IntTag("root",42));
        same(java(input),ChunkTagReader.read(()->new InputStream(){final ByteArrayInputStream data=new ByteArrayInputStream(input);@Override public int read() throws IOException {if(data.available()==0)throw new IOException("trailer");return data.read();}@Override public int read(byte[] b,int off,int len) throws IOException {if(data.available()==0)throw new IOException("trailer");return data.read(b,off,len);}}));
    }
    private static String outcome(byte[] input,boolean nativeMode) {
        try {Tag result=nativeMode?nativeTag(input):java(input);return "tag:"+result;}
        catch(Exception e){return e.getClass().getName()+":"+e.getMessage();}
    }
    @Test public void malformedTruncatedAndUnusualLengthsReplayJava() throws Exception {
        byte[] input=bytes(new CompoundTag("root",Map.of("a",new LongArrayTag("a",new long[]{Long.MIN_VALUE,Long.MAX_VALUE}),"b",new StringTag("b","snow"))));
        for(int length=0;length<input.length;length++){byte[] shortInput=Arrays.copyOf(input,length);assertEquals(outcome(shortInput,false),outcome(shortInput,true));}
        for(byte[] malformed:new byte[][]{{8,0,0,(byte)128,0},{9,0,0,1,-1,-1,-1,-1},{9,0,0,0,0,0,0,1},{13,0,0},{7,0,0,-1,-1,-1,-1}})assertEquals(outcome(malformed,false),outcome(malformed,true));
        String largeName="n".repeat(40000);same(java(bytes(new IntTag(largeName,7))),nativeTag(bytes(new IntTag(largeName,7))));
        List<ByteTag> many=new ArrayList<>();for(int i=0;i<8192;i++)many.add(new ByteTag("",(byte)i));byte[] tooMany=bytes(new ListTag<>("many",ByteTag.class,many));
        long calls=ChunkTagReader.completedCalls();same(java(tooMany),nativeTag(tooMany));assertEquals(calls,ChunkTagReader.completedCalls());
        byte[] large=bytes(new ByteArrayTag("large",new byte[4*1024*1024]));same(java(large),nativeTag(large));assertEquals(calls,ChunkTagReader.completedCalls());
    }
    @Test public void compressedRegionTagsAreOwnedAndReencodeExactly() throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("welt-nbt-region-");
        File file=root.resolve("r.-1.0.mca").toFile();
        Tag original=new CompoundTag("root",Map.of(
                "custom",new CompoundTag("custom",Map.of("material",new StringTag("material","custom:雪_block"),"properties",new CompoundTag("properties",Map.of("variant",new StringTag("variant","strange"))))),
                "entities",new ListTag<>("entities",CompoundTag.class,List.of(new CompoundTag("",Map.of("id",new StringTag("id","minecraft:pig"))))),
                "bytes",new ByteArrayTag("bytes",new byte[]{1,2,3}),
                "ints",new IntArrayTag("ints",new int[]{Integer.MIN_VALUE,0,Integer.MAX_VALUE}),
                "longs",new LongArrayTag("longs",new long[]{Long.MIN_VALUE,0,Long.MAX_VALUE})));
        try(RegionFile region=new RegionFile(file)){try(OutputStream out=region.getChunkDataOutputStream(31,0)){out.write(bytes(original));}}
        byte[] before=java.nio.file.Files.readAllBytes(file.toPath());
        try(RegionFile region=new RegionFile(file,true)) {
            System.setProperty("welt.native.chunkNbt","false");Tag expected=ChunkTagReader.read(region,31,0);
            System.setProperty("welt.native.chunkNbt","true");long calls=ChunkTagReader.completedCalls();Tag actual=ChunkTagReader.read(region,31,0);
            assertEquals(calls+1,ChunkTagReader.completedCalls());same(expected,actual);assertArrayEquals(bytes(expected),bytes(actual));
            // Reuse both worker buffers; already returned tag arrays must remain owned by that tree.
            nativeTag(bytes(new ByteArrayTag("replacement",new byte[100000])));
            same(expected,actual);
            ((ByteArrayTag)((CompoundTag)actual).getTag("bytes")).getValue()[0]=99;
            Tag reread=ChunkTagReader.read(region,31,0);same(expected,reread);
            assertNull(ChunkTagReader.read(region,30,0));
        }
        assertArrayEquals(before,java.nio.file.Files.readAllBytes(file.toPath()));
    }
    @Test public void rejectedNativeInputDoesNotWriteDirectoryAndGroupedControlMatches() throws Exception {
        int[] directory=new int[4+8192*8];Arrays.fill(directory,12345);
        assertFalse(NativeSlices.indexChunkNbt(new byte[]{13,0,0},3,directory));for(int word:directory)assertEquals(12345,word);
        assertFalse(NativeSlices.indexChunkNbt(new byte[]{0},2,directory));assertFalse(NativeSlices.indexChunkNbt(new byte[]{0},1,new int[1]));
        System.setProperty("welt.native.chunkNbtKernel","false");long calls=ChunkTagReader.completedCalls();byte[] input=bytes(random(new Random(31),"root",4));same(java(input),nativeTag(input));assertEquals(calls,ChunkTagReader.completedCalls());
        assertNull(ChunkTagReader.read(()->null));
    }
}
