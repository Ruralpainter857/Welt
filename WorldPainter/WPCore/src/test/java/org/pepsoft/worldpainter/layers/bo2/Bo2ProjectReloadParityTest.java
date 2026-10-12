package org.pepsoft.worldpainter.layers.bo2;

import org.junit.*;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.objects.WPObject;
import javax.vecmath.Point3i;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class Bo2ProjectReloadParityTest {
    private final Map<String,String> properties=new HashMap<>();
    @Before public void remember(){Assume.assumeTrue(Boolean.getBoolean("welt.test.requireBo2Projects"));for(String key:List.of("wp.native.gen","welt.native.bo2Projects"))properties.put(key,System.getProperty(key));}
    @After public void restore(){properties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    private static void mode(boolean enabled){System.setProperty("wp.native.gen",Boolean.toString(enabled));System.setProperty("welt.native.bo2Projects",Boolean.toString(enabled));if(enabled)assertTrue(NativeSlices.isBo2ProjectSnapshotAvailable());}
    private static Bo2Object reference(boolean sparse)throws IOException{mode(false);return Bo2Object.load("Parity",new ByteArrayInputStream(Bo2LoadBenchmark.fixture(8,sparse)));}
    private static byte[] save(Object object)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(object);}return bytes.toByteArray();}
    private static Object read(byte[] bytes)throws Exception{try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes))){return in.readObject();}}
    private static Map<Point3i,Bo2BlockSpec> blocks(Bo2Object object)throws Exception{var field=Bo2Object.class.getDeclaredField("blocks");field.setAccessible(true);@SuppressWarnings("unchecked") Map<Point3i,Bo2BlockSpec> result=(Map<Point3i,Bo2BlockSpec>)field.get(object);return result;}
    private static List<String> visits(WPObject object,int limit){var result=new ArrayList<String>();object.visitBlocks((o,x,y,z,m)->{assertSame(object,o);result.add(x+","+y+","+z+":"+m);return result.size()<limit;});return result;}
    private static void same(WPObject expected,WPObject actual){
        assertEquals(expected.getName(),actual.getName());assertEquals(expected.getDimensions(),actual.getDimensions());assertEquals(expected.getOffset(),actual.getOffset());assertEquals(expected.getAttributes(),actual.getAttributes());
        var d=expected.getDimensions();for(int z=-1;z<=d.z;z++)for(int y=-1;y<=d.y;y++)for(int x=-1;x<=d.x;x++){
            assertEquals(expected.getMask(x,y,z),actual.getMask(x,y,z));if(expected.getMask(x,y,z))assertEquals(expected.getMaterial(x,y,z),actual.getMaterial(x,y,z));}
        var e=visits(expected,Integer.MAX_VALUE);var a=visits(actual,Integer.MAX_VALUE);Collections.sort(e);Collections.sort(a);assertEquals(e,a);
    }
    @Test public void compactReloadPreservesBranchesMetadataVisitOrderClonesAndResaving()throws Exception{
        for(boolean sparse:new boolean[]{false,true}){
            Bo2Object source=reference(sparse);byte[] saved=save(source);mode(false);Bo2Object expected=(Bo2Object)read(saved);
            mode(true);long before=Bo2CompactBlocks.completedObjects();Bo2Object actual=(Bo2Object)read(saved);
            assertEquals(before+1,Bo2CompactBlocks.completedObjects());assertNull(blocks(actual));assertTrue(actual.getBlockIndexStorageBytes()>0);same(expected,actual);same(actual,actual.clone());
            byte[] compatible=save(actual);mode(false);Bo2Object restored=(Bo2Object)read(compatible);
            assertEquals(-1,restored.getBlockIndexStorageBytes());assertEquals(blocks(expected).keySet(),blocks(restored).keySet());
            for(var entry:blocks(expected).entrySet()){var spec=blocks(restored).get(entry.getKey());assertEquals(entry.getValue().getMaterial(),spec.getMaterial());assertArrayEquals(entry.getValue().getBranch(),spec.getBranch());assertEquals(entry.getValue().getCoords(),spec.getCoords());}
        }
    }
    @Test public void absentMaterialsAndEarlyVisitorTerminationKeepOriginalBehavior()throws Exception{
        Bo2Object expected=reference(true);mode(true);
        var originField=Bo2Object.class.getDeclaredField("origin");originField.setAccessible(true);
        Bo2CompactBlocks captured=Bo2CompactBlocks.capture(blocks(expected),(Point3i)originField.get(expected),expected.getDimensions());assertNotNull(captured);
        Bo2Object actual=expected.clone();var compactField=Bo2Object.class.getDeclaredField("compactBlocks");compactField.setAccessible(true);compactField.set(actual,captured);
        var mapField=Bo2Object.class.getDeclaredField("blocks");mapField.setAccessible(true);mapField.set(actual,null);
        for(int limit:new int[]{1,3,17})assertEquals(visits(expected,limit),visits(actual,limit));
        Exception original=null;try{expected.getMaterial(-100,0,0);fail();}catch(NullPointerException error){original=error;}
        try{actual.getMaterial(-100,0,0);fail();}catch(NullPointerException error){assertEquals(original.getClass(),error.getClass());assertEquals(original.getMessage(),error.getMessage());}
    }
    private static final class CustomSpec extends Bo2BlockSpec {
        private static final long serialVersionUID=1L;
        CustomSpec(Point3i point){super(point,Material.STONE,null);}
    }
    @Test public void unsupportedSpecificationsAndCustomMapsKeepJavaStorage()throws Exception{
        for(int variant=0;variant<3;variant++){
            Bo2Object expected=reference(false);var map=blocks(expected);Point3i point=map.keySet().iterator().next();
            if(variant==0)map.put(point,new CustomSpec(point));
            else if(variant==1)map.put(point,new Bo2BlockSpec(new Point3i(point),Material.STONE,new int[]{-3,7}));
            else{var field=Bo2Object.class.getDeclaredField("blocks");field.setAccessible(true);field.set(expected,new LinkedHashMap<>(map));}
            byte[] saved=save(expected);mode(true);long before=Bo2CompactBlocks.completedObjects();Bo2Object actual=(Bo2Object)read(saved);
            assertEquals(before,Bo2CompactBlocks.completedObjects());assertEquals(-1,actual.getBlockIndexStorageBytes());same(expected,actual);
        }
    }
    @Test public void clonedOriginChangesKeepSignedCoordinateMapping()throws Exception{
        Bo2Object expected=reference(false);byte[] saved=save(expected);mode(true);Bo2Object actual=((Bo2Object)read(saved)).clone();expected=expected.clone();
        var field=Bo2Object.class.getDeclaredField("origin");field.setAccessible(true);field.set(expected,new Point3i(Integer.MAX_VALUE,Integer.MIN_VALUE,-7));field.set(actual,new Point3i(Integer.MAX_VALUE,Integer.MIN_VALUE,-7));
        var e=visits(expected,Integer.MAX_VALUE);var a=visits(actual,Integer.MAX_VALUE);Collections.sort(e);Collections.sort(a);assertEquals(e,a);
        Point3i original=blocks(expected).keySet().iterator().next();int x=original.x+Integer.MAX_VALUE,y=original.y+Integer.MIN_VALUE,z=original.z-7;
        assertEquals(expected.getMask(x,y,z),actual.getMask(x,y,z));assertEquals(expected.getMaterial(x,y,z),actual.getMaterial(x,y,z));
    }
    @Test public void concurrentReadersAndSharedProjectObjectsUseOneTransactionEach()throws Exception{
        Bo2Object source=reference(false);byte[] saved=save(new Bo2Object[]{source,source});mode(true);var workers=Executors.newFixedThreadPool(2);long before=Bo2CompactBlocks.completedObjects();
        try{var jobs=new ArrayList<Future<Bo2Object[]>>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->(Bo2Object[])read(saved)));
            for(var job:jobs){var actual=job.get(30,TimeUnit.SECONDS);assertSame(actual[0],actual[1]);assertNull(blocks(actual[0]));same(source,actual[0]);}assertEquals(before+4,Bo2CompactBlocks.completedObjects());
        }finally{workers.shutdownNow();}
    }
    @Test public void historicalClassReadsNewSavesAndWritesReadableProjects()throws Exception{
        Bo2Object source=reference(false);byte[] saved=save(source);mode(true);Bo2Object compact=(Bo2Object)read(saved);saved=save(compact);
        Path directory=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"legacy-bo2-project-");Path java=directory.resolve("Bo2Object.java"),classes=Files.createDirectories(directory.resolve("classes"));
        try(var in=getClass().getResourceAsStream("/org/pepsoft/worldpainter/layers/bo2/legacy-bo2-object.java")){assertNotNull(in);Files.copy(in,java);}
        var diagnostics=new ByteArrayOutputStream();assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,"-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",classes.toString(),java.toString()));
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{if(!name.equals(Bo2Object.class.getName()))return super.loadClass(name,resolve);Class<?> result=findLoadedClass(name);if(result==null)result=findClass(name);if(resolve)resolveClass(result);return result;}
        }){
            Class<?> legacy=loader.loadClass(Bo2Object.class.getName());
            try(var in=new ObjectInputStream(new ByteArrayInputStream(saved)){
                @Override protected Class<?> resolveClass(ObjectStreamClass type)throws IOException,ClassNotFoundException{return type.getName().equals(Bo2Object.class.getName())?legacy:super.resolveClass(type);}
            }){same(compact,(WPObject)in.readObject());}
            mode(false);Object original=legacy.getMethod("load",String.class,InputStream.class).invoke(null,"Parity",new ByteArrayInputStream(Bo2LoadBenchmark.fixture(8,false)));
            byte[] old=save(original);mode(true);long before=Bo2CompactBlocks.completedObjects();Bo2Object restored=(Bo2Object)read(old);assertEquals(before+1,Bo2CompactBlocks.completedObjects());same((WPObject)original,restored);
        }
    }
}
