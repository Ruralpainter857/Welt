package org.pepsoft.worldpainter.layers.bo2;

import org.junit.*;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class StructureProjectReloadParityTest {
    private final Map<String,String> properties=new HashMap<>();
    @Before public void remember() {
        Assume.assumeTrue(Boolean.getBoolean("welt.test.requireStructureProjects"));
        for(String key:List.of("wp.native.gen","welt.native.structure","welt.native.structureProjects")) properties.put(key,System.getProperty(key));
    }
    @After public void restore() { properties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);}); }
    private static void mode(boolean enabled) {
        System.setProperty("wp.native.gen",Boolean.toString(enabled));
        System.setProperty("welt.native.structure",Boolean.toString(enabled));
        System.setProperty("welt.native.structureProjects",Boolean.toString(enabled));
        if(enabled)assertTrue("Release snapshot JNI must be available",NativeSlices.isStructureSnapshotAvailable());
    }
    private static Structure reference(byte[] bytes) throws IOException { mode(false);return Structure.load("Project parity",new ByteArrayInputStream(bytes)); }
    private static Object read(byte[] bytes) throws Exception {try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes))){return in.readObject();}}
    private static void same(Structure expected,Structure actual){StructureCompactLoadingParityTest.same(expected,actual);}
    private static Map<Point3i,Material> blocks(Structure object)throws Exception {
        var field=Structure.class.getDeclaredField("blocks");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Point3i,Material> map=(Map<Point3i,Material>)field.get(object);return map;
    }
    @Test public void denseSparseEntitiesMetadataAndCompatibleResaving()throws Exception {
        for(byte[] fixture:new byte[][]{StructureLoadBenchmark.fixture(8,false),StructureLoadBenchmark.fixture(24,true),
                StructureCompactLoadingParityTest.encode(StructureCompactLoadingParityTest.unusual())}) {
            Structure expected=reference(fixture);byte[] saved=StructureCompactLoadingParityTest.serialize(expected);
            mode(true);long before=StructureCompactBlocks.completedObjects();Structure actual=(Structure)read(saved);
            assertEquals(before+1,StructureCompactBlocks.completedObjects());assertTrue(actual.getBlockIndexStorageBytes()>=0);assertNull(blocks(actual));
            same(expected,actual);same(actual,actual.clone());
            expected.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR,false);actual.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR,false);same(expected,actual);
            byte[] compatible=StructureCompactLoadingParityTest.serialize(actual);mode(false);
            Structure restored=(Structure)read(compatible);assertEquals(-1,restored.getBlockIndexStorageBytes());same(expected,restored);
        }
    }
    @Test public void historicalClassStreamsWorkInBothDirections()throws Exception {
        mode(true);new StructureCompactLoadingParityTest().serializesThroughTheHistoricalStructureClassInBothDirections();
    }
    @Test public void nullMaterialsAndCustomMapsRetainHistoricalStorage()throws Exception {
        for(boolean custom:new boolean[]{false,true}) {
            Structure expected=reference(StructureLoadBenchmark.fixture(3,false));
            if(custom){var field=Structure.class.getDeclaredField("blocks");field.setAccessible(true);field.set(expected,new LinkedHashMap<>(blocks(expected)));}
            else blocks(expected).put(new Point3i(0,0,0),null);
            byte[] saved=StructureCompactLoadingParityTest.serialize(expected);mode(true);
            long before=StructureCompactBlocks.completedObjects();Structure actual=(Structure)read(saved);
            assertEquals(before,StructureCompactBlocks.completedObjects());assertEquals(-1,actual.getBlockIndexStorageBytes());same(expected,actual);
            expected.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR,false);actual.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR,false);same(expected,actual);
        }
    }
    @Test public void oneTransactionPerSharedObjectAndIndependentReaders()throws Exception {
        Structure first=reference(StructureLoadBenchmark.fixture(6,false)),second=reference(StructureLoadBenchmark.fixture(8,true));
        byte[] saved=StructureCompactLoadingParityTest.serialize(new Structure[]{first,second,first});mode(true);
        var workers=Executors.newFixedThreadPool(2);long before=StructureCompactBlocks.completedObjects();
        try {
            var jobs=new ArrayList<Future<Structure[]>>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->(Structure[])read(saved)));
            for(var job:jobs){var actual=job.get(30,TimeUnit.SECONDS);assertSame(actual[0],actual[2]);same(first,actual[0]);same(second,actual[1]);assertNull(blocks(actual[0]));}
            assertEquals(before+8,StructureCompactBlocks.completedObjects());
        }finally{workers.shutdownNow();}
    }
    @Test public void widePalettesAndExtremeOutsideCoordinatesRemainExact()throws Exception {
        Structure expected=reference(StructureLoadBenchmark.fixture(8,false));
        Map<Point3i,Material> original=blocks(expected);
        for(int i=0;i<257;i++)original.put(new Point3i(i&7,(i>>3)&7,i>>6),Material.get("welt:project_palette_"+i));
        original.put(new Point3i(Integer.MIN_VALUE,Integer.MAX_VALUE,-7),Material.STONE);
        byte[] saved=StructureCompactLoadingParityTest.serialize(expected);mode(true);
        Structure actual=(Structure)read(saved);assertNull(blocks(actual));same(expected,actual);
        assertEquals(expected.getMaterial(Integer.MIN_VALUE,Integer.MAX_VALUE,-7),actual.getMaterial(Integer.MIN_VALUE,Integer.MAX_VALUE,-7));
        assertEquals(expected.getMask(Integer.MIN_VALUE,Integer.MAX_VALUE,-7),actual.getMask(Integer.MIN_VALUE,Integer.MAX_VALUE,-7));
        mode(false);same(expected,(Structure)read(StructureCompactLoadingParityTest.serialize(actual)));
    }
    @Test public void invalidNativeSnapshotsRejectWithoutChangingInput() {
        mode(true);byte[] source=new byte[48];ByteBuffer data=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0,0x52505357).putInt(4,1).putInt(8,1).putInt(12,2).putInt(16,2).putInt(20,2).putInt(24,1);
        assertNotNull(NativeSlices.packStructureSnapshot(source));data.putInt(44,1);byte[] before=source.clone();
        assertNull(NativeSlices.packStructureSnapshot(source));assertArrayEquals(before,source);
        for(int length=0;length<source.length;length++)assertNull(NativeSlices.packStructureSnapshot(Arrays.copyOf(source,length)));
    }
}
