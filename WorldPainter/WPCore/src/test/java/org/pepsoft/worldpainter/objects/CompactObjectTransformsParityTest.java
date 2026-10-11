package org.pepsoft.worldpainter.objects;

import org.junit.Test;
import org.pepsoft.minecraft.*;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.DefaultPlugin.*;

public class CompactObjectTransformsParityTest {
    private interface Checked {void run() throws Exception;}
    private void enabled(Checked action) throws Exception {
        String[] keys={"wp.native.gen","welt.native.objectSnapshots","welt.native.objectTransforms"};String[] values=new String[3];
        try{for(int i=0;i<3;i++){values[i]=System.getProperty(keys[i]);System.setProperty(keys[i],"true");}
            boolean ready=NativeSlices.transformMaterialVolume(new byte[]{0},new int[]{1,1,1,1,0},new byte[1]);
            if(Boolean.getBoolean("welt.test.requireObjectTransforms"))assertTrue("Current release DLL required",ready);else org.junit.Assume.assumeTrue(ready);
            action.run();
        }finally{for(int i=0;i<3;i++){if(values[i]==null)System.clearProperty(keys[i]);else System.setProperty(keys[i],values[i]);}}
    }
    private static void indexedReady() {
        boolean ready=NativeSlices.remapTransformedMaterialVolume(null,new int[]{0},0,new int[]{0},1,new int[]{1,1,1,1,0},new byte[1]);
        if(Boolean.getBoolean("welt.test.requireObjectTransforms"))assertTrue("Current fused JNI symbol required",ready);else org.junit.Assume.assumeTrue(ready);
    }
    private GenericObject source(boolean wide) {
        Material[] data=new Material[7*5*3];
        Material[] palette={null,Material.AIR,Material.STONE,Material.get("minecraft:oak_stairs","facing","east","half","bottom","shape","straight","waterlogged","true"),Material.get("minecraft:oak_log","axis","x"),Material.get("welt:custom","facing","north")};
        for(int i=0;i<data.length;i++)data[i]=wide?Material.get("welt:entry_"+i,"facing","east"):palette[i%palette.length];
        Entity entity=new Entity("minecraft:pig");entity.setRelPos(new double[]{1.25,2,3.75});entity.setRot(new float[]{35,10});
        TileEntity tile=new TileEntity("minecraft:chest");tile.setX(2);tile.setY(1);tile.setZ(3);
        return new GenericObject("Transform",7,5,3,data,List.of(entity),List.of(tile),Map.of(WPObject.ATTRIBUTE_OFFSET.key,new Point3i(-2,-1,-3)));
    }
    private static void compare(WPObject expected,WPObject actual) {
        assertEquals(expected.getDimensions(),actual.getDimensions());assertEquals(expected.getAttributes(),actual.getAttributes());assertEquals(expected.getName(),actual.getName());Point3i d=expected.getDimensions();
        for(int z=0;z<d.z;z++)for(int y=0;y<d.y;y++)for(int x=0;x<d.x;x++){
            assertEquals(expected.getMask(x,y,z),actual.getMask(x,y,z));
            assertSame(expected.getMask(x,y,z)?expected.getMaterial(x,y,z):null,actual.getMaterial(x,y,z));
        }
        assertArrayEquals(expected.getEntities().get(0).getRelPos(),actual.getEntities().get(0).getRelPos(),0);
        assertArrayEquals(expected.getEntities().get(0).getRot(),actual.getEntities().get(0).getRot(),0);
        TileEntity e=expected.getTileEntities().get(0),a=actual.getTileEntities().get(0);assertEquals(e.getX(),a.getX());assertEquals(e.getY(),a.getY());assertEquals(e.getZ(),a.getZ());
    }
    @Test public void allRotationsMirrorsAndCompositions() throws Exception {enabled(()->{
        for(Platform platform:new Platform[]{JAVA_ANVIL,JAVA_ANVIL_1_15}) {
            GenericObject source=source(false);
            for(int steps=0;steps<4;steps++)for(boolean mirror:new boolean[]{false,true}) {
                WPObject rotated=new RotatedObject(source,steps,platform);
                assertNotNull(((CompactObjectSource)rotated).copyCompactVolume());compare(rotated,new GenericObject(rotated));
                WPObject combined=new RotatedObject(new MirroredObject(rotated,mirror,platform),3,platform);
                assertNotNull(((CompactObjectSource)combined).copyCompactVolume());compare(combined,new GenericObject(combined));
            }
            assertEquals(2,source.getTileEntities().get(0).getX());assertEquals(3,source.getTileEntities().get(0).getZ());
        }
    });}
    @Test public void spongeRootsTransformWithoutIntermediateObjects() throws Exception {enabled(()->{
        indexedReady();
        String old=System.getProperty("welt.native.schem");
        try{for(boolean packed:new boolean[]{false,true})for(int version:new int[]{1,2,3}){
            System.setProperty("welt.native.schem",Boolean.toString(packed));
            WPObject source=org.pepsoft.worldpainter.layers.bo2.Schem.load(new ByteArrayInputStream(org.pepsoft.worldpainter.layers.bo2.SchemLoadBenchmark.fixture(8,version)),"Sponge");
            for(int steps=0;steps<4;steps++)for(boolean mirror:new boolean[]{false,true}){
                WPObject transformed=new RotatedObject(new MirroredObject(new RotatedObject(source,steps,JAVA_ANVIL_1_15),mirror,JAVA_ANVIL),3,JAVA_ANVIL_1_15);
                assertNotNull(((CompactObjectSource)transformed).copyCompactVolume());GenericObject copy=new GenericObject(transformed);
                assertEquals(transformed.getAttributes(),copy.getAttributes());assertEquals(transformed.getName(),copy.getName());assertEquals(transformed.getDimensions(),copy.getDimensions());
                Point3i d=copy.getDimensions();for(int z=0;z<d.z;z++)for(int y=0;y<d.y;y++)for(int x=0;x<d.x;x++){
                    assertEquals(transformed.getMask(x,y,z),copy.getMask(x,y,z));assertSame(transformed.getMask(x,y,z)?transformed.getMaterial(x,y,z):null,copy.getMaterial(x,y,z));
                }
            }
        }}finally{if(old==null)System.clearProperty("welt.native.schem");else System.setProperty("welt.native.schem",old);}
        assertFalse(NativeSlices.remapTransformedMaterialVolume(null,new int[]{-1},0,new int[]{0},1,new int[]{1,1,1,1,1},new byte[1]));
    });}
    @Test public void fusedWidePaletteAndInvalidIndices() throws Exception {enabled(()->{
        indexedReady();
        int[] mapping=new int[258];mapping[257]=300;byte[] output=new byte[4];
        assertTrue(NativeSlices.remapTransformedMaterialVolume(new byte[]{0,0,1,1},null,2,mapping,301,new int[]{2,1,1,2,1},output));
        assertArrayEquals(new byte[]{0,0,44,1},output);
        assertTrue(NativeSlices.remapTransformedMaterialVolume(new byte[]{0,1},null,1,new int[]{300,0},301,new int[]{2,1,1,2,4},output));
        assertArrayEquals(new byte[]{0,0,44,1},output);
        Arrays.fill(output,(byte)9);assertFalse(NativeSlices.remapTransformedMaterialVolume(null,new int[]{0,-1},0,new int[]{0},301,new int[]{2,1,1,2,1},output));assertArrayEquals(new byte[]{9,9,9,9},output);
    });}
    @Test public void eachStepKeepsItsOwnPlatform() throws Exception {enabled(()->{
        GenericObject source=source(false);
        for(Platform inner:new Platform[]{JAVA_ANVIL,JAVA_ANVIL_1_15})for(Platform outer:new Platform[]{JAVA_ANVIL,JAVA_ANVIL_1_15}){
            WPObject chain=new RotatedObject(new MirroredObject(new RotatedObject(source,3,inner),true,outer),1,outer);
            assertNotNull(((CompactObjectSource)chain).copyCompactVolume());compare(chain,new GenericObject(chain));
        }
    });}
    @Test public void widePaletteAndSaveReload() throws Exception {enabled(()->{
        Material[] values=new Material[350];for(int i=0;i<350;i++)values[i]=Material.get("welt:wide_"+i,"facing","north");
        GenericObject source=new GenericObject("Wide",10,7,5,values);WPObject rotated=new RotatedObject(new MirroredObject(source,false,JAVA_ANVIL_1_15),1,JAVA_ANVIL_1_15);
        GenericObject copy=new GenericObject(rotated);assertNotNull(((CompactObjectSource)rotated).copyCompactVolume());
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(copy);}
        GenericObject restored;try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){restored=(GenericObject)in.readObject();}
        Point3i d=rotated.getDimensions();for(int z=0;z<d.z;z++)for(int y=0;y<d.y;y++)for(int x=0;x<d.x;x++){assertSame(rotated.getMaterial(x,y,z),copy.getMaterial(x,y,z));assertSame(copy.getMaterial(x,y,z),restored.getMaterial(x,y,z));}
    });}
    @Test public void concurrentTransactionsAndChainBound() throws Exception {enabled(()->{
        GenericObject source=source(false);
        WPObject chain=source;for(int i=0;i<64;i++)chain=new RotatedObject(chain,1,JAVA_ANVIL_1_15);
        assertNotNull(((CompactObjectSource)chain).copyCompactVolume());compare(chain,new GenericObject(chain));
        WPObject unsupported=new RotatedObject(chain,1,JAVA_ANVIL_1_15);assertNull(((CompactObjectSource)unsupported).copyCompactVolume());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{var jobs=new ArrayList<java.util.concurrent.Future<GenericObject>>();
            WPObject transformed=new RotatedObject(new MirroredObject(source,true,JAVA_ANVIL_1_15),1,JAVA_ANVIL_1_15);
            for(int i=0;i<32;i++)jobs.add(executor.submit(()->new GenericObject(transformed)));
            for(var job:jobs)compare(transformed,job.get());
        }finally{executor.shutdownNow();}
    });}
    @Test public void overriddenOrMutatedWrappersKeepJavaTraversal() throws Exception {enabled(()->{
        GenericObject source=source(false);RotatedObject custom=new RotatedObject(source,1,JAVA_ANVIL_1_15){@Override public Material getMaterial(int x,int y,int z){return Material.STONE;}};
        assertNull(custom.copyCompactVolume());compare(custom,new GenericObject(custom));
        RotatedObject changed=new RotatedObject(source,1,JAVA_ANVIL_1_15);changed.getDimensions().x++;assertNull(changed.copyCompactVolume());
        assertFalse(NativeSlices.transformMaterialVolume(new byte[]{0},new int[]{1,1,1,1,9},new byte[1]));
        byte[] same={0};assertFalse(NativeSlices.transformMaterialVolume(same,new int[]{1,1,1,1,0},same));
    });}
}