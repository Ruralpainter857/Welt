package org.pepsoft.worldpainter.objects;

import org.junit.*;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.layers.bo2.Schem;
import org.pepsoft.worldpainter.layers.bo2.SchemLoadBenchmark;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

public class IndexedMaterialClassificationParityTest {
    private final Map<String,String> previous=new HashMap<>();
    @Before public void ready(){
        for(String key:new String[]{"wp.native.gen","welt.native.objectSnapshots","welt.native.schem"})previous.put(key,System.getProperty(key));
        System.setProperty("wp.native.gen","true");System.setProperty("welt.native.objectSnapshots","true");
        boolean available=NativeSlices.collectIndexedMaterialStates(null,new int[]{0},0,1,new int[]{1,1,1})!=null;
        if(Boolean.getBoolean("welt.test.requireIndexedClassification"))assertTrue("Indexed classification DLL required",available);
        else org.junit.Assume.assumeTrue("Indexed classification unavailable",available);
    }
    @After public void restore(){previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    private static Schem source(int version,boolean packed)throws Exception{
        System.setProperty("welt.native.schem",Boolean.toString(packed));
        Schem result=Schem.load(new ByteArrayInputStream(SchemLoadBenchmark.fixture(8,version)),"Modern");
        assertEquals(packed,field(result,"compactBlocks")!=null);return result;
    }
    private static Object field(Schem object,String name)throws Exception{Field field=Schem.class.getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void field(Schem object,String name,Object value)throws Exception{Field field=Schem.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static void compare(Schem object){
        IdentityHashMap<Material,Boolean> seen=new IdentityHashMap<>();List<Material> expected=new ArrayList<>();
        object.visitBlocks((source,x,y,z,material)->{if(seen.put(material,Boolean.TRUE)==null)expected.add(material);return true;});
        IdentityHashMap<Material,Boolean> emitted=new IdentityHashMap<>();List<Material> actual=new ArrayList<>();
        long before=CompactMaterialVolume.completedIndexedClassifications();
        CompactObjectMaterials.forEach(object,material->{if(emitted.put(material,Boolean.TRUE)==null)actual.add(material);});
        assertEquals(before+1,CompactMaterialVolume.completedIndexedClassifications());
        assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++)assertSame(expected.get(i),actual.get(i));
    }
    @Test public void versionsAndStorageFormsPreserveCustomMaterialOrder()throws Exception{
        for(int version:new int[]{1,2,3})for(boolean packed:new boolean[]{false,true})compare(source(version,packed));
    }
    @Test public void sparsePalettesAliasesAndLiveIndicesKeepMaskSemantics()throws Exception{
        Schem object=source(3,false);Material[] palette=(Material[])field(object,"palette");palette[1]=null;palette[2]=palette[3];compare(object);
        int[] indices=(int[])field(object,"blocks");indices[0]=3;compare(object);
        Arrays.fill(palette,Material.AIR);compare(object);
    }
    @Test public void widePaletteAndPackedHighBytesMatchIntegerSource()throws Exception{
        Schem object=source(3,false);Material[] palette=new Material[300];Arrays.fill(palette,Material.STONE);palette[0]=Material.AIR;
        int[] indices=new int[512];for(int i=0;i<indices.length;i++)indices[i]=i%300;
        field(object,"palette",palette);field(object,"blocks",indices);compare(object);
        byte[] packed=new byte[1024];for(int i=0;i<indices.length;i++){packed[i*2]=(byte)indices[i];packed[i*2+1]=(byte)(indices[i]>>>8);}
        field(object,"compactBlocks",packed);field(object,"compactStride",2);field(object,"blocks",null);compare(object);
    }
    @Test public void invalidIndicesDoNotEmitCallbacks()throws Exception{
        assertNull(NativeSlices.collectIndexedMaterialStates(null,new int[]{-1},0,2,new int[]{1,1,1}));
        assertNull(NativeSlices.collectIndexedMaterialStates(new byte[]{0},null,3,2,new int[]{1,1,1}));
        assertNull(NativeSlices.collectIndexedMaterialStates(new byte[]{0},new int[]{0},1,2,new int[]{1,1,1}));
        Schem object=source(3,false);int[] indices=(int[])field(object,"blocks");indices[indices.length-1]=9999;
        List<Material> emitted=new ArrayList<>();assertFalse(object.forEachClassificationMaterial(emitted::add));assertTrue(emitted.isEmpty());
    }
}
