package org.pepsoft.worldpainter.objects;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.Test;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.Box;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class MinecraftWorldObjectStorageParityTest {
    private static Class<?> legacyClass;
    private static final Material[] MATERIALS={Material.AIR,null,Material.STONE,Material.get(53,1),Material.get(53,2),Material.get("welt:preview_custom")};
    private MinecraftWorldObject world(boolean compact,Material[] lowest) {
        System.setProperty("welt.compact.previewWorld",Boolean.toString(compact));
        return new MinecraftWorldObject("Preview fixture",new Box(-8,10,-20,7,-4,12),128,7,lowest,new javax.vecmath.Point3i(-3,2,-1));
    }
    @Test public void writesQueriesResetAndMasksMatchTheLegacyRepresentation() {
        String flag=System.getProperty("welt.compact.previewWorld");
        try {
            Material[] low={Material.STONE,null,Material.get(53,1)};
            MinecraftWorldObject expected=world(false,low),actual=world(true,low);
            Random random=new Random(17);
            for(int i=0;i<5000;i++){
                int x=random.nextInt(24)-11,y=random.nextInt(33)-23,z=random.nextInt(22)-7;
                Material material=MATERIALS[random.nextInt(MATERIALS.length)];
                expected.setMaterialAt(x,y,z,material);actual.setMaterialAt(x,y,z,material);
            }
            compare(expected,actual);
            assertSame(Material.AIR,actual.getMaterialAt(-100,0,0));
            assertEquals(Integer.MIN_VALUE,actual.getHighestNonAirBlock(-100,0));
            low[0]=Material.SAND;expected.reset();actual.reset();compare(expected,actual);
            assertSame(Material.SAND,actual.getMaterial(0,0,0));assertFalse(actual.getMask(0,0,1));
            assertTrue("AIR remains a present mask value",actual.getMask(0,0,15));
            assertEquals(11,actual.getHighestNonAirBlock(-8,-20));
            assertThrows(ArrayIndexOutOfBoundsException.class,()->actual.getMaterial(-1,0,0));
            assertThrows(ArrayIndexOutOfBoundsException.class,()->actual.getMaterial(0,27,0));
        }finally{restore(flag);}
    }
    @Test public void chunkCopyAndVirtualChunkEditsKeepCoordinatesAndMaterialProperties() {
        String flag=System.getProperty("welt.compact.previewWorld");try{
            MinecraftWorldObject expected=world(false,null),actual=world(true,null);
            Chunk chunk=(Chunk)java.lang.reflect.Proxy.newProxyInstance(Chunk.class.getClassLoader(),new Class<?>[]{Chunk.class},(proxy,method,args)->switch(method.getName()){
                case "getxPos"->0;case "getzPos"->-1;case "getHighestNonAirBlock"->3;
                case "getMaterial"->MATERIALS[Math.floorMod((int)args[0]+(int)args[1]+(int)args[2],MATERIALS.length)];default->throw new UnsupportedOperationException(method.getName());
            });
            expected.addChunk(chunk);actual.addChunk(chunk);compare(expected,actual);
            expected.getChunkForEditing(-1,0).setMaterial(15,2,3,Material.get(53,2));
            actual.getChunkForEditing(-1,0).setMaterial(15,2,3,Material.get(53,2));compare(expected,actual);
        }finally{restore(flag);}
    }
    @Test public void palettePromotionUsesOneNativeTransactionAndRetainsIdentity() {
        String flag=System.getProperty("welt.compact.previewWorld"),gen=System.getProperty(Native.GEN_KEY);
        try {
            boolean available=NativeLoader.areSlicesAvailable();if(Boolean.getBoolean("welt.test.requirePreviewWorld"))assertTrue(available);org.junit.Assume.assumeTrue(available);
            System.setProperty(Native.GEN_KEY,"true");MinecraftWorldObject actual=world(true,null);MinecraftWorldObject expected=world(false,null);
            long before=MutableMaterialVolume.nativePromotions();
            for(int i=0;i<300;i++){Material material=Material.get("welt:preview_palette_"+i);int x=i%18,y=(i/18)%27,z=i/(18*27);expected.setMaterialAt(x-8,y-20,z-4,material);actual.setMaterialAt(x-8,y-20,z-4,material);}
            assertEquals(before+1,MutableMaterialVolume.nativePromotions());compare(expected,actual);
            MinecraftWorldObject copy=actual.clone();copy.reset();assertSame(Material.AIR,copy.getMaterial(0,0,0));assertNotSame(copy.getMaterial(0,0,0),actual.getMaterial(0,0,0));
        }finally{restore(flag);if(gen==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,gen);}
    }
    @Test public void clonesAreIndependentAndAcceptAnAbsentLowestColumn() {
        String flag=System.getProperty("welt.compact.previewWorld");try{
            for(boolean compact:new boolean[]{false,true}){
                MinecraftWorldObject original=world(compact,null);original.setMaterialAt(-8,-20,-4,Material.STONE);
                MinecraftWorldObject clone=original.clone();clone.setMaterialAt(-8,-20,-4,Material.SAND);
                assertSame(Material.STONE,original.getMaterialAt(-8,-20,-4));assertSame(Material.SAND,clone.getMaterialAt(-8,-20,-4));
                clone.getOffset().x=99;assertEquals(-3,original.getOffset().x);
                clone.reset();assertSame(Material.STONE,original.getMaterialAt(-8,-20,-4));
            }
        }finally{restore(flag);}
    }
    @Test public void historicalSerializationLimitationIsPreserved() throws Exception {
        String flag=System.getProperty("welt.compact.previewWorld");try{
            Class<?> legacy=legacy();assertEquals(5054283000984305244L,ObjectStreamClass.lookup(legacy).getSerialVersionUID());
            Object old=legacy.getConstructor(String.class,Box.class,int.class,int.class).newInstance("Old preview",new Box(-8,10,-20,7,-4,12),128,7);
            assertEquals(Box.class.getName(),assertThrows(NotSerializableException.class,()->write(old)).getMessage());
            for(boolean compact:new boolean[]{false,true}){
                MinecraftWorldObject modern=world(compact,null);
                assertEquals(Box.class.getName(),assertThrows(NotSerializableException.class,()->write(modern)).getMessage());
            }
        }finally{restore(flag);}
    }
    @Test public void javaPromotionAndEmptyDimensionsRetainValues() {
        String gen=System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY,"false");long before=MutableMaterialVolume.nativePromotions();
            Material[] column=new Material[300];for(int i=0;i<column.length;i++)column[i]=Material.get("welt:java_palette_"+i);
            MutableMaterialVolume volume=new MutableMaterialVolume(2,3,300,column);
            for(int x=0;x<2;x++)for(int y=0;y<3;y++)for(int z=0;z<300;z++)assertSame(column[z],volume.get(x,y,z));
            MutableMaterialVolume copy=volume.copy();volume.reset(null);assertSame(column[299],copy.get(1,2,299));assertSame(Material.AIR,volume.get(1,2,299));
            assertEquals(before,MutableMaterialVolume.nativePromotions());
            new MutableMaterialVolume(0,3,0,column);new MutableMaterialVolume(2,0,0,column);
            assertThrows(ArrayIndexOutOfBoundsException.class,()->new MutableMaterialVolume(2,3,0,column));
            assertThrows(ArrayIndexOutOfBoundsException.class,()->new MutableMaterialVolume(2,3,2,column));
        }finally{if(gen==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,gen);}
    }
    @Test public void densePaletteFallbackPreservesEveryMaterial() {
        String gen=System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY,"false");
            Material[] materials=new Material[65535];
            MutableMaterialVolume volume=new MutableMaterialVolume(1,1,materials.length,null);
            for(int i=0;i<materials.length;i++) {
                materials[i]=Material.get("welt:overflow_palette_"+i);
                volume.set(0,0,i,materials[i]);
            }
            for(int i=0;i<materials.length;i++)assertSame(materials[i],volume.get(0,0,i));
            MutableMaterialVolume copy=volume.copy();volume.set(0,0,0,null);
            assertSame(materials[0],copy.get(0,0,0));assertNull(volume.get(0,0,0));
            volume.reset(null);assertSame(Material.AIR,volume.get(0,0,materials.length-1));
            volume.reset(materials);
            for(int i=0;i<materials.length;i++)assertSame(materials[i],volume.get(0,0,i));
        }finally{if(gen==null)System.clearProperty(Native.GEN_KEY);else System.setProperty(Native.GEN_KEY,gen);}
    }
    @Test public void legacyConversionChecksShapeAndPreservesNulls() {
        Material[][][] source={{{Material.AIR,null,Material.STONE}},{{Material.SAND,Material.get(53,1),null}}};
        MutableMaterialVolume volume=MutableMaterialVolume.fromLegacy(source,2,1,3);
        assertNotNull(volume);Material[][][] restored=volume.toLegacy();
        for(int x=0;x<2;x++)for(int z=0;z<3;z++)assertSame(source[x][0][z],restored[x][0][z]);
        assertNull(MutableMaterialVolume.fromLegacy(source,2,1,4));
        assertNull(MutableMaterialVolume.fromLegacy(new Material[][][]{null},1,1,1));
        assertNull(MutableMaterialVolume.fromLegacy(null,1,1,1));
    }
    @Test public void compactStorageIsDefaultAndCanBeDisabled() throws Exception {
        String flag=System.getProperty("welt.compact.previewWorld");
        try {
            var field=MinecraftWorldObject.class.getDeclaredField("compactBlocks");field.setAccessible(true);
            System.clearProperty("welt.compact.previewWorld");
            MinecraftWorldObject normal=new MinecraftWorldObject("Default preview",new Box(0,2,0,2,0,4),4,1);
            assertNotNull(field.get(normal));assertSame(Material.AIR,normal.getMaterial(1,1,3));
            System.setProperty("welt.compact.previewWorld","false");
            MinecraftWorldObject fallback=new MinecraftWorldObject("Fallback preview",new Box(0,2,0,2,0,4),4,1);
            assertNull(field.get(fallback));assertSame(Material.AIR,fallback.getMaterial(1,1,3));
        }finally{restore(flag);}
    }
    private static void compare(MinecraftWorldObject expected,MinecraftWorldObject actual){
        Box a=expected.getVolume(),b=actual.getVolume(); assertEquals(a.getX1(),b.getX1()); assertEquals(a.getY1(),b.getY1()); assertEquals(a.getZ1(),b.getZ1()); assertEquals(a.getWidth(),b.getWidth()); assertEquals(a.getLength(),b.getLength()); assertEquals(a.getHeight(),b.getHeight());assertEquals(expected.getDimensions(),actual.getDimensions());assertEquals(expected.getOffset(),actual.getOffset());
        for(int x=-8;x<10;x++)for(int y=-20;y<7;y++){
            assertEquals(expected.getHighestNonAirBlock(x,y),actual.getHighestNonAirBlock(x,y));
            for(int z=-4;z<12;z++){
                Material material=expected.getMaterialAt(x,y,z);assertSame(material,actual.getMaterialAt(x,y,z));
                assertSame(material,actual.getMaterial(x+8,y+20,z+4));assertEquals(expected.getMask(x+8,y+20,z+4),actual.getMask(x+8,y+20,z+4));
                if(material!=null){assertEquals(expected.getBlockTypeAt(x,y,z),actual.getBlockTypeAt(x,y,z));assertEquals(expected.getDataAt(x,y,z),actual.getDataAt(x,y,z));}
            }
        }
    }
    private static byte[] write(Object object)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(object);}return bytes.toByteArray();}
    private static Object read(byte[] bytes,Class<?> legacy)throws Exception{
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes)){
            protected Class<?> resolveClass(ObjectStreamClass type)throws IOException,ClassNotFoundException{return legacy!=null&&type.getName().equals(MinecraftWorldObject.class.getName())?legacy:super.resolveClass(type);}
        }){return in.readObject();}
    }
    private static synchronized Class<?> legacy()throws Exception{
        if(legacyClass!=null)return legacyClass;Path root=Files.createTempDirectory(Path.of("target"),"legacy-preview-world-");
        Path source=root.resolve("MinecraftWorldObject.java"),classes=Files.createDirectory(root.resolve("classes"));
        try(InputStream input=MinecraftWorldObjectStorageParityTest.class.getResourceAsStream("/legacy-minecraft-world-object.java")){assertNotNull(input);Files.copy(input,source);}
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        try(var files=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)){
            assertTrue(compiler.getTask(null,files,null,List.of("-encoding","UTF-8","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,files.getJavaFileObjects(source.toFile())).call());
        }
        String name=MinecraftWorldObject.class.getName();
        URLClassLoader loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},MinecraftWorldObject.class.getClassLoader()){
            protected synchronized Class<?> loadClass(String requested,boolean resolve)throws ClassNotFoundException{
                if(!requested.equals(name))return super.loadClass(requested,resolve);Class<?> loaded=findLoadedClass(requested);if(loaded==null)loaded=findClass(requested);if(resolve)resolveClass(loaded);return loaded;
            }
        };
        return legacyClass=loader.loadClass(name);
    }
    private static void restore(String flag){if(flag==null)System.clearProperty("welt.compact.previewWorld");else System.setProperty("welt.compact.previewWorld",flag);}
}
