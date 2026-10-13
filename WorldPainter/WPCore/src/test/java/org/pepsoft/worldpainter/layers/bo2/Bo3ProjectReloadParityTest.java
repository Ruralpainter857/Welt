package org.pepsoft.worldpainter.layers.bo2;

import org.junit.*;
import org.pepsoft.minecraft.*;
import org.jnbt.*;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import static org.junit.Assert.*;

public class Bo3ProjectReloadParityTest {
    private final Map<String,String> saved=new HashMap<>();
    @Before public void remember(){Assume.assumeTrue(Boolean.getBoolean("welt.test.requireBo3Projects"));for(String key:List.of("wp.native.gen","welt.native.bo3Projects"))saved.put(key,System.getProperty(key));}
    @After public void restore(){saved.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    private static void mode(boolean enabled){System.setProperty("wp.native.gen",Boolean.toString(enabled));System.setProperty("welt.native.bo3Projects",Boolean.toString(enabled));}
    private static Random random()throws Exception{var field=Bo3BlockSpec.class.getDeclaredField("RANDOM");field.setAccessible(true);return(Random)field.get(null);}
    private static Map<Point3i,Bo3BlockSpec> blocks(Bo3Object object)throws Exception{var field=Bo3Object.class.getDeclaredField("blocks");field.setAccessible(true);@SuppressWarnings("unchecked") Map<Point3i,Bo3BlockSpec> result=(Map<Point3i,Bo3BlockSpec>)field.get(object);return result;}
    private static Bo3Object construct(Class<?> type,Map<Point3i,Bo3BlockSpec> blocks,Point3i origin,Point3i dims)throws Exception{
        var ctor=type.getDeclaredConstructor(String.class,Map.class,Map.class,Point3i.class,Point3i.class,Map.class);ctor.setAccessible(true);
        return(Bo3Object)ctor.newInstance("Parity",new HashMap<>(Map.of("RotateRandomly","false")),blocks,origin,dims,new HashMap<>());
    }
    private static Bo3Object fixture()throws Exception{
        mode(false);var map=new HashMap<Point3i,Bo3BlockSpec>();
        Point3i a=new Point3i(-3,2,-5),b=new Point3i(-2,2,-5),c=new Point3i(-1,3,-4);
        TileEntity tile=TileEntity.fromNBT(new CompoundTag("",new HashMap<>(Map.of("id",new StringTag("id","welt:container"),"x",new IntTag("x",0),"y",new IntTag("y",0),"z",new IntTag("z",0)))));
        map.put(a,new Bo3BlockSpec(a,new Bo3BlockSpec.RandomBlock[]{new Bo3BlockSpec.RandomBlock(Material.STONE,tile,25),new Bo3BlockSpec.RandomBlock(Material.get(35,4),null,100)}));
        map.put(b,new Bo3BlockSpec(b,Material.AIR,null));map.put(c,new Bo3BlockSpec(c,Material.STONE,null));
        return construct(Bo3Object.class,map,new Point3i(3,-2,5),new Point3i(8,8,8));
    }
    private static byte[] save(Object object)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new ObjectOutputStream(bytes)){out.writeObject(object);}return bytes.toByteArray();}
    private static Object read(byte[] bytes)throws Exception{try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes))){return in.readObject();}}
    private static Bo3Object compactSameMap(Bo3Object source)throws Exception{
        mode(true);var origin=Bo3Object.class.getDeclaredField("origin");origin.setAccessible(true);
        Bo3CompactBlocks snapshot=Bo3CompactBlocks.capture(blocks(source),(Point3i)origin.get(source),source.getDimensions());assertNotNull(snapshot);
        Bo3Object result=source.clone();var field=Bo3Object.class.getDeclaredField("compactBlocks");field.setAccessible(true);field.set(result,snapshot);
        field=Bo3Object.class.getDeclaredField("blocks");field.setAccessible(true);field.set(result,null);return result;
    }
    private static List<String> trace(Bo3Object object,int limit)throws Exception{
        var result=new ArrayList<String>();for(int i=0;i<30;i++){assertTrue(object.getMask(0,0,0));result.add(object.getMaterial(0,0,0).toString());}
        object.visitBlocks((source,x,y,z,material)->{result.add(x+","+y+","+z+":"+material);return result.size()<30+limit;});return result;
    }
    @Test public void packingMasksAndCallbacksPreserveEveryRandomDraw()throws Exception{
        Bo3Object source=fixture();Random random=random();random.setSeed(89173);Bo3Object compact=compactSameMap(source);
        assertEquals(new Random(89173).nextLong(),random.nextLong());
        for(int limit:new int[]{1,2,20}){random.setSeed(42891);var expected=trace(source,limit);long next=random.nextLong();random.setSeed(42891);assertEquals(expected,trace(compact,limit));assertEquals(next,random.nextLong());}
        random.setSeed(9823);for(int i=0;i<100;i++)compact.getMask(0,0,0);assertEquals(new Random(9823).nextLong(),random.nextLong());
    }
    @Test public void entitiesCloneExceptionsAndCompatibleResavingRemainExact()throws Exception{
        Bo3Object source=fixture(),compact=compactSameMap(source);assertEquals(source.getAttributes(),compact.getAttributes());assertEquals(source.getDimensions(),compact.getDimensions());
        var e=source.getTileEntities();var a=compact.getTileEntities();assertEquals(e.size(),a.size());for(int i=0;i<e.size();i++){assertEquals(e.get(i).getId(),a.get(i).getId());assertEquals(e.get(i).getX(),a.get(i).getX());assertEquals(e.get(i).getY(),a.get(i).getY());assertEquals(e.get(i).getZ(),a.get(i).getZ());}
        Exception expected=null;try{source.getMaterial(-100,0,0);fail();}catch(NullPointerException error){expected=error;}try{compact.getMaterial(-100,0,0);fail();}catch(NullPointerException error){assertEquals(expected.getMessage(),error.getMessage());}
        byte[] bytes=save(compact);mode(false);Bo3Object restored=(Bo3Object)read(bytes);assertEquals(-1,restored.getBlockIndexStorageBytes());
        random().setSeed(123);var trace=trace(restored,20);long next=random().nextLong();random().setSeed(123);assertEquals(trace,trace(compact.clone(),20));assertEquals(next,random().nextLong());
    }
    @Test public void projectReloadPreservesSharedObjectsWithoutAdvancingRandom()throws Exception{
        Bo3Object source=fixture();byte[] bytes=save(new Bo3Object[]{source,source});mode(true);random().setSeed(19473);long before=Bo3CompactBlocks.completedObjects();var restored=(Bo3Object[])read(bytes);
        assertSame(restored[0],restored[1]);assertNull(blocks(restored[0]));assertEquals(before+1,Bo3CompactBlocks.completedObjects());assertEquals(new Random(19473).nextLong(),random().nextLong());
    }
    @Test public void opaqueIndicesCoverMoreThan65535Specifications()throws Exception{
        var map=new HashMap<Point3i,Bo3BlockSpec>();for(int y=0;y<256;y++)for(int x=0;x<256;x++){var point=new Point3i(x,y,0);map.put(point,new Bo3BlockSpec(point,Material.STONE,null));}
        mode(true);var compact=Bo3CompactBlocks.capture(map,new Point3i(),new Point3i(256,256,1));assertNotNull(compact);assertEquals(65536*4,compact.storageBytes());assertSame(map.get(new Point3i(255,255,0)),compact.spec(255,255,0,new Point3i()));
    }
    private static final class CustomSpec extends Bo3BlockSpec {
        private static final long serialVersionUID=1L;
        CustomSpec(Point3i point){super(point,Material.STONE,null);}
    }
    @Test public void unsupportedMapsAndSpecificationsPreserveTheJavaRoute()throws Exception {
        for(boolean customMap:new boolean[]{false,true}){
            Bo3Object source=fixture();Map<Point3i,Bo3BlockSpec> map=blocks(source);
            if(customMap){var field=Bo3Object.class.getDeclaredField("blocks");field.setAccessible(true);field.set(source,new LinkedHashMap<>(map));}
            else{Point3i point=map.keySet().iterator().next();map.put(point,new CustomSpec(point));}
            byte[] bytes=save(source);mode(true);long before=Bo3CompactBlocks.completedObjects();Bo3Object restored=(Bo3Object)read(bytes);
            assertEquals(before,Bo3CompactBlocks.completedObjects());assertNotNull(blocks(restored));assertEquals(-1,restored.getBlockIndexStorageBytes());
        }
    }
    @Test public void signedCloneOriginChangesKeepSpecIdentityAndMasks()throws Exception {
        Bo3Object source=fixture(),compact=compactSameMap(source);var field=Bo3Object.class.getDeclaredField("origin");field.setAccessible(true);
        field.set(source,new Point3i(Integer.MAX_VALUE,Integer.MIN_VALUE,-7));field.set(compact,new Point3i(Integer.MAX_VALUE,Integer.MIN_VALUE,-7));
        for(var entry:blocks(source).entrySet()){
            Point3i point=entry.getKey();int x=point.x+Integer.MAX_VALUE,y=point.y+Integer.MIN_VALUE,z=point.z-7;
            assertEquals(source.getMask(x,y,z),compact.getMask(x,y,z));random().setSeed(9427);Material expected=source.getMaterial(x,y,z);long next=random().nextLong();random().setSeed(9427);assertSame(expected,compact.getMaterial(x,y,z));assertEquals(next,random().nextLong());
        }
    }
    @Test public void historicalClassStillReadsAndWritesTheSameProjectFields()throws Exception{
        Bo3Object source=fixture();byte[] bytes=save(compactSameMap(source));Path dir=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"legacy-bo3-project-");Path java=dir.resolve("Bo3Object.java"),classes=Files.createDirectories(dir.resolve("classes"));
        try(var in=getClass().getResourceAsStream("/org/pepsoft/worldpainter/layers/bo2/legacy-bo3-object.java")){assertNotNull(in);Files.copy(in,java);}
        var diagnostics=new ByteArrayOutputStream();assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,"-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",classes.toString(),java.toString()));
        try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{if(!name.equals(Bo3Object.class.getName()))return super.loadClass(name,resolve);Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}
        }){
            Class<?> legacy=loader.loadClass(Bo3Object.class.getName());Object historical;
            try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes)){
                @Override protected Class<?> resolveClass(ObjectStreamClass type)throws IOException,ClassNotFoundException{return type.getName().equals(Bo3Object.class.getName())?legacy:super.resolveClass(type);}
            }){historical=in.readObject();}
            byte[] old=save(historical);mode(true);long before=Bo3CompactBlocks.completedObjects();Bo3Object restored=(Bo3Object)read(old);assertEquals(before+1,Bo3CompactBlocks.completedObjects());assertNull(blocks(restored));assertEquals(source.getDimensions(),restored.getDimensions());
        }
    }
}
