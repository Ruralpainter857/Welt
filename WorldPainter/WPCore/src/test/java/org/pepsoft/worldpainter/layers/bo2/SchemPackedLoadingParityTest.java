package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.junit.*;
import org.pepsoft.worldpainter.objects.WPObject;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.Assert.*;

public class SchemPackedLoadingParityTest {
    private String oldGen,oldFlag;
    @Before public void remember(){oldGen=System.getProperty("wp.native.gen");oldFlag=System.getProperty("welt.native.schem");}
    @After public void restore(){restore("wp.native.gen",oldGen);restore("welt.native.schem",oldFlag);}
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static void mode(boolean nativeMode){System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.schem",Boolean.toString(nativeMode));}
    private static byte[] encode(int[] values){
        var out=new ByteArrayOutputStream();
        for(int value:values){do{int low=value&127;value>>>=7;out.write(low|(value==0?0:128));}while(value!=0);}
        return out.toByteArray();
    }
    private static CompoundTag fixture(int version,int paletteSize,boolean sparse,boolean waterlogged,boolean explicitOffset,boolean integers){
        int width=3,length=5,height=4;int[] blocks=new int[width*length*height];
        for(int i=width*length;i<blocks.length;i++)blocks[i]=(i-width*length)%paletteSize;
        blocks[blocks.length-1]=paletteSize-1;
        Map<String,Tag> palette=new LinkedHashMap<>();
        for(int i=0;i<paletteSize;i++){
            if(sparse&&i==17)continue;
            String name=i==0?"minecraft:air":i==1?"minecraft:stone":"welt:custom_"+i+"[facing=north]";
            if(waterlogged&&i==paletteSize-1&&i!=0)name="minecraft:oak_leaves[persistent=true,waterlogged=true]";
            palette.put(name,new IntTag(name,i));
        }
        Map<String,Tag> tile=new LinkedHashMap<>();tile.put("Id",new StringTag("Id","minecraft:chest"));
        tile.put("Pos",new IntArrayTag("Pos",new int[]{1,2,3}));tile.put("CustomName",new StringTag("CustomName","Stored chest"));
        String tileKey=version==1?"TileEntities":"BlockEntities";
        var tiles=new ListTag<>(tileKey,CompoundTag.class,List.of(new CompoundTag("",tile)));
        Map<String,Tag> tags=new LinkedHashMap<>();
        tags.put("Version",new IntTag("Version",version));tags.put("Width",new ShortTag("Width",(short)width));
        tags.put("Height",new ShortTag("Height",(short)height));tags.put("Length",new ShortTag("Length",(short)length));
        if(explicitOffset)tags.put("Offset",new IntArrayTag("Offset",new int[]{-1,-2,-3}));
        tags.put("Metadata",new CompoundTag("Metadata",new LinkedHashMap<>(Map.of("Name",new StringTag("Name","Parity object")))));
        if(version==3){
            Map<String,Tag> group=new LinkedHashMap<>();group.put("Palette",new CompoundTag("Palette",palette));
            group.put("Data",integers?new IntArrayTag("Data",blocks):new ByteArrayTag("Data",encode(blocks)));group.put(tileKey,tiles);
            tags.put("Blocks",new CompoundTag("Blocks",group));
        }else{
            tags.put("Palette",new CompoundTag("Palette",palette));tags.put("BlockData",integers?new IntArrayTag("BlockData",blocks):new ByteArrayTag("BlockData",encode(blocks)));tags.put(tileKey,tiles);
        }
        if(version!=1){
            Map<String,Tag> entity=new LinkedHashMap<>();entity.put("Id",new StringTag("Id","minecraft:pig"));
            entity.put("Pos",new ListTag<>("Pos",DoubleTag.class,List.of(new DoubleTag("",.25),new DoubleTag("",1.5),new DoubleTag("",2.75))));
            if(version==3)entity.put("Data",new CompoundTag("Data",new LinkedHashMap<>(Map.of("CustomName",new StringTag("CustomName","Stored entity")))));
            else entity.put("CustomName",new StringTag("CustomName","Stored entity"));
            tags.put("Entities",new ListTag<>("Entities",CompoundTag.class,List.of(new CompoundTag("",entity))));
        }
        return new CompoundTag("Schematic",tags);
    }
    private static void sameTag(Tag a,Tag b){
        assertEquals(a.getClass(),b.getClass());assertEquals(a.getName(),b.getName());
        if(a instanceof CompoundTag left){
            Map<String,Tag> right=((CompoundTag)b).getValue();assertEquals(left.getValue().keySet(),right.keySet());
            left.getValue().forEach((key,value)->sameTag(value,right.get(key)));
        }else if(a instanceof ListTag<?> left){
            ListTag<?> right=(ListTag<?>)b;assertEquals(left.getType(),right.getType());assertEquals(left.getValue().size(),right.getValue().size());
            for(int i=0;i<left.getValue().size();i++)sameTag(left.getValue().get(i),right.getValue().get(i));
        }else try{
            Object x=a.getClass().getMethod("getValue").invoke(a),y=b.getClass().getMethod("getValue").invoke(b);
            if(x instanceof byte[])assertArrayEquals((byte[])x,(byte[])y);
            else if(x instanceof int[])assertArrayEquals((int[])x,(int[])y);
            else if(x instanceof long[])assertArrayEquals((long[])x,(long[])y);
            else if(x instanceof Double)assertEquals(Double.doubleToRawLongBits((Double)x),Double.doubleToRawLongBits((Double)y));
            else if(x instanceof Float)assertEquals(Float.floatToRawIntBits((Float)x),Float.floatToRawIntBits((Float)y));
            else assertEquals(x,y);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void same(WPObject a,WPObject b){
        assertEquals(a.getName(),b.getName());assertEquals(a.getDimensions(),b.getDimensions());assertEquals(a.getOffset(),b.getOffset());
        assertEquals(a.getAttributes(),b.getAttributes());assertEquals(a.getAllMaterials(),b.getAllMaterials());
        Point3i d=a.getDimensions();for(int z=0;z<d.z;z++)for(int y=0;y<d.y;y++)for(int x=0;x<d.x;x++){
            assertEquals(a.getMask(x,y,z),b.getMask(x,y,z));assertEquals(a.getMaterial(x,y,z),b.getMaterial(x,y,z));
        }
        assertEquals(a.getTileEntities().size(),b.getTileEntities().size());
        for(int i=0;i<a.getTileEntities().size();i++)sameTag(a.getTileEntities().get(i).toNBT(),b.getTileEntities().get(i).toNBT());
        if(a.getEntities()==null)assertNull(b.getEntities());else{
            assertEquals(a.getEntities().size(),b.getEntities().size());
            for(int i=0;i<a.getEntities().size();i++){
                sameTag(a.getEntities().get(i).toNBT(),b.getEntities().get(i).toNBT());
                assertArrayEquals(a.getEntities().get(i).getRelPos(),b.getEntities().get(i).getRelPos(),0);
            }
        }
    }
    @Test public void preservesVersionsPaletteWidthsHolesEntitiesAndProperties(){
        for(int version=1;version<=3;version++)for(int size:new int[]{1,256,257})for(boolean explicit:new boolean[]{false,true}){
            mode(false);Schem expected=new Schem(fixture(version,size,size>17,true,explicit,false),"Fallback");
            mode(true);long before=SchemPackedAccess.completedObjects();Schem actual=new Schem(fixture(version,size,size>17,true,explicit,false),"Fallback");
            assertEquals(before+1,SchemPackedAccess.completedObjects());same(expected,actual);same(actual,actual.clone());
            assertEquals(60L*(size<=256?1:2),actual.getBlockIndexStorageBytes());
        }
    }
    @Test public void preservesAirPropertiesTrailingBytesAndOverflowingFifthGroup(){
        Schem reference=null;
        for(boolean nativeMode:new boolean[]{false,true}){
            mode(nativeMode);CompoundTag tag=fixture(1,4,false,false,false,false);
            CompoundTag palette=(CompoundTag)tag.getTag("Palette");palette.getValue().remove("minecraft:stone");
            palette.setTag("minecraft:air[waterlogged=true]",new IntTag("minecraft:air[waterlogged=true]",1));
            byte[] bytes=((ByteArrayTag)tag.getTag("BlockData")).getValue();byte[] trailing=Arrays.copyOf(bytes,bytes.length+3);
            trailing[bytes.length]=(byte)128;trailing[bytes.length+1]=(byte)128;trailing[bytes.length+2]=(byte)128;
            tag.setTag("BlockData",new ByteArrayTag("BlockData",trailing));
            long before=SchemPackedAccess.completedObjects();Schem object=new Schem(tag,"Air properties");
            if(!nativeMode)reference=object;else{same(reference,object);assertEquals(before+1,SchemPackedAccess.completedObjects());}
        }
        for(boolean nativeMode:new boolean[]{false,true}){
            mode(nativeMode);CompoundTag tag=fixture(1,1,false,false,false,false);byte[] bytes=new byte[64];
            Arrays.fill(bytes,0,4,(byte)128);bytes[4]=16;tag.setTag("BlockData",new ByteArrayTag("BlockData",bytes));
            long before=SchemPackedAccess.completedObjects();Schem object=new Schem(tag,"Overflow");
            if(!nativeMode)reference=object;else{same(reference,object);assertEquals(before+1,SchemPackedAccess.completedObjects());}
        }
    }
    @Test public void retainsLegacyIntArrayAndMalformedErrorBehaviour(){
        mode(false);Schem expected=new Schem(fixture(2,257,false,false,false,true),"Fallback");
        mode(true);long before=SchemPackedAccess.completedObjects();Schem actual=new Schem(fixture(2,257,false,false,false,true),"Fallback");
        assertEquals(before,SchemPackedAccess.completedObjects());same(expected,actual);assertEquals(240,actual.getBlockIndexStorageBytes());
        for(byte[] source:new byte[][]{new byte[0],{(byte)128},{(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,0}}){
            Throwable reference=null;
            for(boolean nativeMode:new boolean[]{false,true}){
                mode(nativeMode);CompoundTag tag=fixture(1,1,false,false,false,false);tag.setTag("BlockData",new ByteArrayTag("BlockData",source));
                try{new Schem(tag,"Broken");fail("Malformed source accepted");}catch(RuntimeException error){
                    if(!nativeMode)reference=error;else{assertEquals(reference.getClass(),error.getClass());assertEquals(reference.getMessage(),error.getMessage());}
                }
            }
        }
    }
    private static byte[] serialize(Object value)throws IOException{
        var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(value);}return bytes.toByteArray();
    }
    @Test public void preservesProjectRoundTripsAndHistoricalReader()throws Exception{
        mode(true);Schem compact=new Schem(fixture(3,257,true,true,false,false),"Fallback");
        byte[] encoded=serialize(compact);
        try(var in=new ObjectInputStream(new ByteArrayInputStream(encoded))){Schem restored=(Schem)in.readObject();same(compact,restored);assertEquals(120,restored.getBlockIndexStorageBytes());}
        mode(false);try(var in=new ObjectInputStream(new ByteArrayInputStream(encoded))){Schem restored=(Schem)in.readObject();same(compact,restored);assertEquals(240,restored.getBlockIndexStorageBytes());}
        Path directory=java.nio.file.Files.createTempDirectory(Path.of("target").toAbsolutePath(),"legacy-schem-");
        Path source=directory.resolve("Schem.java"),classes=java.nio.file.Files.createDirectories(directory.resolve("classes"));
        try(var input=getClass().getResourceAsStream("/org/pepsoft/worldpainter/layers/bo2/legacy-schem.java")){
            assertNotNull("Historical source fixture missing",input);java.nio.file.Files.copy(input,source);
        }
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull("JDK compiler required",compiler);
        var diagnostics=new ByteArrayOutputStream();
        assertEquals("Historical source compilation failed",0,compiler.run(null,diagnostics,diagnostics,
                "-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",classes.toString(),source.toString()));
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                synchronized(getClassLoadingLock(name)){
                    if(name.equals(Schem.class.getName())){Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}
                    return super.loadClass(name,resolve);
                }
            }
        }){
            Class<?> historical=loader.loadClass(Schem.class.getName());
            try(var in=new ObjectInputStream(new ByteArrayInputStream(encoded)){
                @Override protected Class<?> resolveClass(ObjectStreamClass type)throws IOException,ClassNotFoundException{
                    return type.getName().equals(Schem.class.getName())?historical:super.resolveClass(type);
                }
            }){same(compact,(WPObject)in.readObject());}
            Object original=historical.getConstructor(CompoundTag.class,String.class).newInstance(fixture(3,257,true,true,false,false),"Fallback");
            mode(true);try(var in=new ObjectInputStream(new ByteArrayInputStream(serialize(original)))){Schem restored=(Schem)in.readObject();same((WPObject)original,restored);assertEquals(120,restored.getBlockIndexStorageBytes());}
        }
    }
    @Test public void usesIndependentArraysAcrossWorkers()throws Exception{
        mode(true);var workers=Executors.newFixedThreadPool(4);
        try{var jobs=new ArrayList<java.util.concurrent.Future<Schem>>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->new Schem(fixture(3,257,true,false,false,false),"Worker")));
            Schem first=jobs.get(0).get();for(var job:jobs)same(first,job.get());
        }finally{workers.shutdownNow();}
    }
    @Test public void refusesAliasedOutputsAndInvalidPayloadBeforeMutation(){
        mode(true);byte[] bytes={0,0};int[] summary=new int[8];byte[] output={99,99};
        assertFalse(NativeSlices.decodeSchematic(bytes,null,new byte[]{1},bytes,summary,2,1,1));
        assertFalse(NativeSlices.decodeSchematic(bytes,null,new byte[]{1},output,summary,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertFalse(NativeSlices.decodeSchematic(new byte[]{0,3},null,new byte[]{1},output,summary,2,1,1));assertArrayEquals(new byte[]{99,99},output);
    }
}
