package org.pepsoft.worldpainter.layers.bo2;

import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.vecmath.Point3i;
import static org.junit.Assert.*;

public class Bo2CompactLoadingParityTest {
    private final Map<String,String> saved=new HashMap<>();
    @Before public void remember(){Assume.assumeTrue(Boolean.getBoolean("welt.test.requireBo2CompactLoad"));for(String key:List.of("wp.native.gen","welt.native.bo2","welt.native.bo2CompactLoad","welt.native.bo2Projects"))saved.put(key,System.getProperty(key));}
    @After public void restore(){saved.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    private static void mode(boolean enabled){System.setProperty("wp.native.gen",Boolean.toString(enabled));System.setProperty("welt.native.bo2",Boolean.toString(enabled));System.setProperty("welt.native.bo2CompactLoad",Boolean.toString(enabled));System.setProperty("welt.native.bo2Projects","false");}
    private static Bo2Object load(byte[] source,boolean compact)throws IOException{mode(compact);return Bo2Object.load("Parity",new ByteArrayInputStream(source));}
    private static Map<Point3i,Bo2BlockSpec> blocks(Bo2Object object)throws Exception{var field=Bo2Object.class.getDeclaredField("blocks");field.setAccessible(true);@SuppressWarnings("unchecked") Map<Point3i,Bo2BlockSpec> result=(Map<Point3i,Bo2BlockSpec>)field.get(object);return result;}
    private static List<String> visits(Bo2Object object){var values=new ArrayList<String>();object.visitBlocks((o,x,y,z,m)->{values.add(x+","+y+","+z+":"+m);return true;});Collections.sort(values);return values;}
    private static void same(Bo2Object expected,Bo2Object actual)throws Exception{
        assertEquals(expected.getName(),actual.getName());assertEquals(expected.getDimensions(),actual.getDimensions());assertEquals(expected.getOffset(),actual.getOffset());assertEquals(expected.getAttributes(),actual.getAttributes());
        var field=Bo2Object.class.getDeclaredField("properties");field.setAccessible(true);assertEquals(field.get(expected),field.get(actual));assertEquals(visits(expected),visits(actual));
        Point3i d=expected.getDimensions();if(d.x>0&&d.y>0&&d.z>0&&(long)d.x*d.y*d.z<=262144)for(int z=0;z<d.z;z++)for(int y=0;y<d.y;y++)for(int x=0;x<d.x;x++){assertEquals(expected.getMask(x,y,z),actual.getMask(x,y,z));if(expected.getMask(x,y,z))assertSame(expected.getMaterial(x,y,z),actual.getMaterial(x,y,z));}
    }
    private static Bo2Object javaRoundTrip(Bo2Object source)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(source);}mode(false);try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){return(Bo2Object)in.readObject();}}
    @Test public void denseSparseMetadataBranchesCloneAndHistoricalSavingRemainEquivalent()throws Exception{
        for(boolean sparse:new boolean[]{false,true}){
            byte[] bytes=Bo2LoadBenchmark.fixture(8,sparse);Bo2Object expected=load(bytes,false);long parsed=Bo2NativeParser.completedObjects(),packed=Bo2CompactBlocks.completedObjects();Bo2Object actual=load(bytes,true);
            assertEquals(parsed+1,Bo2NativeParser.completedObjects());assertEquals(packed+1,Bo2CompactBlocks.completedObjects());assertNull(blocks(actual));assertTrue(actual.getBlockIndexStorageBytes()>0);same(expected,actual);same(actual,actual.clone());
            Bo2Object restored=javaRoundTrip(actual);assertEquals(blocks(expected).keySet(),blocks(restored).keySet());for(var entry:blocks(expected).entrySet()){Bo2BlockSpec spec=blocks(restored).get(entry.getKey());assertSame(entry.getValue().getMaterial(),spec.getMaterial());assertArrayEquals(entry.getValue().getBranch(),spec.getBranch());assertEquals(entry.getKey(),spec.getCoords());}
        }
    }
    @Test public void duplicateCoordinatesPreserveLastBranchAndAllSourceBounds()throws Exception{
        byte[] bytes="[META]\nrandomRotation=false\nkey=first\nkey= last = value\n[DATA]\n-2,3,4:1.0#-5@6\n7,-8,9:35.4\n-2,3,4:20.0#3@7\n".getBytes(StandardCharsets.US_ASCII);
        Bo2Object expected=load(bytes,false),actual=load(bytes,true);same(expected,actual);Bo2Object restored=javaRoundTrip(actual);assertArrayEquals(new int[]{3,7},blocks(restored).get(new Point3i(-2,3,4)).getBranch());
    }
    @Test public void extremeWrappedBoundsAndConnectingMaterialsKeepJavaBehavior()throws Exception{
        for(String text:List.of("[META]\n[DATA]\n-2147483648,0,0:1\n2147483647,0,0:1\n","[META]\n[DATA]\n0,0,0:85\n1,0,0:1\n2,0,0:101\n3,0,0:1\n")){
            byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);same(load(bytes,false),load(bytes,true));
        }
    }
    @Test public void malformedRecordsAndUnsupportedTextKeepOriginalExceptions()throws Exception{
        for(String text:List.of("[META]\n[DATA]\nbad:1\n","[META]\n[DATA]\n0,0,0:-1\n","[META]\n[DATA]\n0,0,0:1\n0,0,0:bad\n")){
            byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);RuntimeException expected=null;
            try{load(bytes,false);fail();}catch(RuntimeException error){expected=error;}
            try{load(bytes,true);fail();}catch(RuntimeException error){assertEquals(expected.getClass(),error.getClass());assertEquals(expected.getMessage(),error.getMessage());}
        }
    }
    @Test public void independentWorkersKeepCompactResultsSeparate()throws Exception{
        byte[] bytes=Bo2LoadBenchmark.fixture(8,false);Bo2Object expected=load(bytes,false);mode(true);var workers=Executors.newFixedThreadPool(2);long before=Bo2CompactBlocks.completedObjects();
        try{var jobs=new ArrayList<Future<Bo2Object>>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->Bo2Object.load("Parity",new ByteArrayInputStream(bytes))));for(var job:jobs){Bo2Object actual=job.get(30,TimeUnit.SECONDS);assertNull(blocks(actual));same(expected,actual);}assertEquals(before+4,Bo2CompactBlocks.completedObjects());}finally{workers.shutdownNow();}
    }
}
