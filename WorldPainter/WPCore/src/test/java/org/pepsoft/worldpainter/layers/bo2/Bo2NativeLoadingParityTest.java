package org.pepsoft.worldpainter.layers.bo2;

import org.junit.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class Bo2NativeLoadingParityTest {
    private String previousGen,previousFlag;
    @Before public void remember(){previousGen=System.getProperty("wp.native.gen");previousFlag=System.getProperty("welt.native.bo2");}
    @After public void restore(){restore("wp.native.gen",previousGen);restore("welt.native.bo2",previousFlag);}
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static void mode(boolean nativeMode){System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.bo2",Boolean.toString(nativeMode));}
    private static Bo2Object load(byte[] bytes,boolean nativeMode)throws IOException{mode(nativeMode);return Bo2Object.load("Parity",new ByteArrayInputStream(bytes));}
    @SuppressWarnings("unchecked") private static Map<Point3i,Bo2BlockSpec> blocks(Bo2Object object)throws Exception{
        var field=Bo2Object.class.getDeclaredField("blocks");field.setAccessible(true);return (Map<Point3i,Bo2BlockSpec>)field.get(object);
    }
    private static void same(Bo2Object expected,Bo2Object actual)throws Exception{same(expected,actual,true);}
    private static void same(Bo2Object expected,Bo2Object actual,boolean compareVisitOrder)throws Exception{
        assertEquals(expected.getName(),actual.getName());assertEquals(expected.getDimensions(),actual.getDimensions());assertEquals(expected.getOffset(),actual.getOffset());assertEquals(expected.getAttributes(),actual.getAttributes());
        var field=Bo2Object.class.getDeclaredField("properties");field.setAccessible(true);assertEquals(field.get(expected),field.get(actual));
        var left=blocks(expected);var right=blocks(actual);assertEquals(left.keySet(),right.keySet());
        for(var entry:left.entrySet()){
            var spec=right.get(entry.getKey());assertEquals(entry.getValue().getMaterial(),spec.getMaterial());assertArrayEquals(entry.getValue().getBranch(),spec.getBranch());assertEquals(entry.getValue().getCoords(),spec.getCoords());
        }
        Point3i d=expected.getDimensions();
        if(d.x>0&&d.y>0&&d.z>0&&(long)d.x*d.y*d.z<=65536)for(int z=0;z<d.z;z++)for(int x=0;x<d.x;x++)for(int y=0;y<d.y;y++){
            assertEquals(expected.getMask(x,y,z),actual.getMask(x,y,z));if(expected.getMask(x,y,z))assertEquals(expected.getMaterial(x,y,z),actual.getMaterial(x,y,z));
        }
        List<String> e=new ArrayList<>(),a=new ArrayList<>();
        expected.visitBlocks((o,x,y,z,m)->{e.add(x+","+y+","+z+":"+m);return true;});
        actual.visitBlocks((o,x,y,z,m)->{a.add(x+","+y+","+z+":"+m);return true;});if(!compareVisitOrder){Collections.sort(e);Collections.sort(a);}assertEquals(e,a);
    }
    @Test public void preservesDenseSparseCloneAndHistoricalRepresentation()throws Exception{
        for(boolean sparse:new boolean[]{false,true}){
            byte[] bytes=Bo2LoadBenchmark.fixture(8,sparse);Bo2Object expected=load(bytes,false);long before=Bo2NativeParser.completedObjects();Bo2Object actual=load(bytes,true);
            assertEquals(before+1,Bo2NativeParser.completedObjects());same(expected,actual);same(expected,actual.clone());
            var output=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(output)){out.writeObject(actual);}
            try(var in=new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))){// HashMap deserialization rebuilds buckets and does not preserve iteration order.
                same(expected,(Bo2Object)in.readObject(),false);}
        }
    }
    @Test public void preservesLineEndingsMetadataBranchesAndDuplicateCoordinates()throws Exception{
        String text="Ignored\r [META] \n[META]\r\n randomRotation = FALSE \r needsFoundation=false\nspawnWater=true\nkey=first\nkey= last = value \n[DATA]\r\n-2,+3,4:1.0#-5@+6\r7,-8,9:35.4\n-2,3,4:20.0#3@7\n";
        byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);same(load(bytes,false),load(bytes,true));
        byte[] high="[META]\nkey=\u0080\n[DATA]\n0,0,0:1\n".getBytes(StandardCharsets.ISO_8859_1);same(load(high,false),load(high,true));
        byte[] extremes="[META]\n[DATA]\n-2147483648,0,0:1\n2147483647,0,0:1\n".getBytes(StandardCharsets.US_ASCII);same(load(extremes,false),load(extremes,true));
    }
    @Test public void connectingMaterialsKeepTheOriginalInference()throws Exception{
        byte[] bytes="[META]\n[DATA]\n0,0,0:85\n1,0,0:1\n2,0,0:101\n3,0,0:1\n".getBytes(StandardCharsets.US_ASCII);same(load(bytes,false),load(bytes,true));
    }
    @Test public void malformedInputRetainsOriginalExceptionAndFallback()throws Exception{
        for(String text:List.of("","[META]\nbroken\n[DATA]\n0,0,0:1","[META]\n[DATA]\n0, 0,0:1","[META]\n[DATA]\n0,0,0:1.0#2","[META]\n[DATA]\n2147483648,0,0:1")){
            byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);Exception reference=null;
            for(boolean nativeMode:new boolean[]{false,true})try{load(bytes,nativeMode);fail("Malformed BO2 accepted");}catch(IOException|RuntimeException e){if(!nativeMode)reference=e;else{assertEquals(reference.getClass(),e.getClass());assertEquals(reference.getMessage(),e.getMessage());}}
        }
        assertNull(NativeSlices.parseBo2(new byte[]{0}));
    }
    @Test public void closesTheCallerStreamOnceOnSuccessAndFailure()throws Exception{
        for(boolean nativeMode:new boolean[]{false,true})for(byte[] bytes:new byte[][]{Bo2LoadBenchmark.fixture(2,false),new byte[0]}){
            mode(nativeMode);class Counting extends ByteArrayInputStream{int closes;Counting(){super(bytes);}@Override public void close(){closes++;}}
            Counting source=new Counting();try{Bo2Object.load("Closing",source);}catch(IOException expected){}assertEquals(1,source.closes);
        }
    }
    @Test public void usesIndependentFramesAcrossWorkers()throws Exception{
        mode(true);byte[] bytes=Bo2LoadBenchmark.fixture(8,false);var workers=Executors.newFixedThreadPool(4);
        try{var jobs=new ArrayList<Future<Bo2Object>>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->Bo2Object.load("Parity",new ByteArrayInputStream(bytes))));Bo2Object first=jobs.get(0).get();for(var job:jobs)same(first,job.get());}finally{workers.shutdownNow();}
    }
}