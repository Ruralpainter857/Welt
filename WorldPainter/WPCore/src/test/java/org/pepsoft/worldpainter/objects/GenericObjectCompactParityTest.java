package org.pepsoft.worldpainter.objects;

import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.io.*;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class GenericObjectCompactParityTest {
    private void enabled(Checked action) throws Exception {
        String gen=System.getProperty("wp.native.gen"), compact=System.getProperty("welt.native.objectSnapshots");
        try { System.setProperty("wp.native.gen","true"); System.setProperty("welt.native.objectSnapshots","true");
            org.junit.Assume.assumeTrue("Native library unavailable",NativeSlices.packMaterialVolume(new int[]{0},1,new byte[1])); action.run();
        } finally { restore("wp.native.gen",gen); restore("welt.native.objectSnapshots",compact); }
    }
    private static void restore(String key,String value) { if(value==null)System.clearProperty(key);else System.setProperty(key,value); }
    private interface Checked { void run() throws Exception; }
    private static Object storage(GenericObject object,String name) throws Exception { Field f=GenericObject.class.getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    private static byte[] save(Object value) throws Exception { ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(value);}return bytes.toByteArray(); }
    private static GenericObject load(byte[] bytes) throws Exception { try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))){return (GenericObject)in.readObject();} }
    @Test public void absenceAirIdentityAndImmutableCopies() throws Exception { enabled(()->{
        Material custom=Material.get("welt:custom","facing","north");Material[] input={null,Material.AIR,Material.STONE,custom,null,Material.AIR};
        GenericObject object=new GenericObject("Selection",3,2,1,input);assertNull(storage(object,"data"));assertNotNull(storage(object,"compact"));
        input[2]=Material.AIR;assertSame(Material.STONE,object.getMaterial(2,0,0));assertFalse(object.getMask(0,0,0));assertTrue(object.getMask(1,0,0));assertSame(custom,object.getMaterial(0,1,0));
        GenericObject copy=new GenericObject(object);assertSame(storage(object,"compact"),storage(copy,"compact"));assertSame(custom,copy.getMaterial(0,1,0));
        assertSame(custom,object.getMaterial(3,0,0));
        try{object.getMaterial(-1,0,0);fail();}catch(ArrayIndexOutOfBoundsException expected){}
    }); }
    @Test public void widePaletteAndProjectCompatibility() throws Exception { enabled(()->{
        Material[] input=new Material[300];for(int i=0;i<input.length;i++)input[i]=Material.get("welt:palette_"+i);
        GenericObject object=new GenericObject("Wide",10,5,6,input);assertNull(storage(object,"data"));
        for(int i=0;i<300;i++)assertSame(input[i],object.getMaterial(i%10,(i/10)%5,i/50));
        byte[] nativeSave=save(object);System.setProperty("welt.native.objectSnapshots","false");GenericObject javaRead=load(nativeSave);assertNotNull(storage(javaRead,"data"));
        byte[] javaSave=save(javaRead);System.setProperty("welt.native.objectSnapshots","true");GenericObject nativeRead=load(javaSave);assertNull(storage(nativeRead,"data"));
        for(int i=0;i<300;i++)assertSame(input[i],nativeRead.getMaterial(i%10,(i/10)%5,i/50));
    }); }
    @Test public void schematicSnapshotsUseBothIndexRepresentations() throws Exception { enabled(()->{
        String old=System.getProperty("welt.native.schem");
        try{for(boolean packed:new boolean[]{false,true})for(int version:new int[]{1,2,3}){
            System.setProperty("welt.native.schem",Boolean.toString(packed));
            var source=org.pepsoft.worldpainter.layers.bo2.Schem.load(new ByteArrayInputStream(org.pepsoft.worldpainter.layers.bo2.SchemLoadBenchmark.fixture(8,version)),"Fixture");
            GenericObject copy=new GenericObject(source);assertNull(storage(copy,"data"));assertNotNull(storage(copy,"compact"));
            for(int z=0;z<8;z++)for(int y=0;y<8;y++)for(int x=0;x<8;x++){
                assertEquals(source.getMask(x,y,z),copy.getMask(x,y,z));
                assertSame(source.getMask(x,y,z)?source.getMaterial(x,y,z):null,copy.getMaterial(x,y,z));
            }
        }}finally{restore("welt.native.schem",old);}
    }); }
    @Test public void malformedPackingUsesFallback() throws Exception { enabled(()->{
        assertFalse(NativeSlices.packMaterialVolume(new int[]{-1},2,new byte[1]));assertFalse(NativeSlices.packMaterialVolume(new int[]{2},2,new byte[1]));
        assertFalse(NativeSlices.packMaterialVolume(new int[]{0},257,new byte[1]));
        GenericObject empty=new GenericObject("Empty",0,1,1,new Material[0]);assertNotNull(storage(empty,"data"));assertNull(storage(empty,"compact"));
    }); }
}