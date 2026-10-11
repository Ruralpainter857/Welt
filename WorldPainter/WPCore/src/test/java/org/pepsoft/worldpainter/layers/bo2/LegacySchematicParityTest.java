package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.junit.*;
import javax.vecmath.Point3i;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LegacySchematicParityTest {
    private String previousGen, previousFlag;
    @Before public void remember() {previousGen=System.getProperty("wp.native.gen");previousFlag=System.getProperty("welt.native.legacySchematic");}
    @After public void restore() {restore("wp.native.gen",previousGen);restore("welt.native.legacySchematic",previousFlag);}
    private static void restore(String key,String value) {if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
    private static void mode(boolean nativeMode) {System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.legacySchematic",Boolean.toString(nativeMode));}
    private static byte[] nbt(Schematic value) throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(NBTOutputStream out=new NBTOutputStream(bytes)){out.writeTag(value.toNBT());}return bytes.toByteArray();}
    private static void same(Schematic a,Schematic b) throws Exception {
        assertEquals(a.getDimensions(),b.getDimensions());assertEquals(a.getOffset(),b.getOffset());assertEquals(a.getAttributes(),b.getAttributes());assertArrayEquals(nbt(a),nbt(b));
        Point3i d=a.getDimensions();for(int z=0;z<d.z;z++)for(int x=0;x<d.x;x++)for(int y=0;y<d.y;y++){assertEquals(a.getMask(x,y,z),b.getMask(x,y,z));assertSame(a.getMaterial(x,y,z),b.getMaterial(x,y,z));}
        assertEquals(a.getEntities()==null,b.getEntities()==null);assertEquals(a.getTileEntities()==null,b.getTileEntities()==null);
        if(a.getEntities()!=null)assertEquals(a.getEntities().size(),b.getEntities().size());if(a.getTileEntities()!=null)assertEquals(a.getTileEntities().size(),b.getTileEntities().size());
    }
    private static CompoundTag tag(byte[] ids,byte[] data,byte[] add) {
        Map<String,Tag> values=new HashMap<>(Map.of("Materials",new StringTag("Materials","Alpha"),"Width",new ShortTag("Width",(short)3),"Length",new ShortTag("Length",(short)2),"Height",new ShortTag("Height",(short)1),"Blocks",new ByteArrayTag("Blocks",ids),"Data",new ByteArrayTag("Data",data),"custom",new ByteArrayTag("custom",new byte[]{1,2,3})));
        if(add!=null)values.put("AddBlocks",new ByteArrayTag("AddBlocks",add));return new CompoundTag("Schematic",values);
    }
    private static Schematic compare(CompoundTag tag) throws Exception {
        mode(false);Schematic expected=new Schematic("Parity",tag,null);long before=LegacySchematicAccess.completedAnalyses();mode(true);Schematic actual=new Schematic("Parity",tag,null);boolean extended = tag.getTag("AddBlocks") instanceof ByteArrayTag a && java.util.stream.IntStream.range(0,a.getValue().length).anyMatch(i->a.getValue()[i]!=0);assertEquals(before+(extended?1:0),LegacySchematicAccess.completedAnalyses());same(expected,actual);return actual;
    }
    @Test public void completeCompressedAndRawLibrariesPreserveAccessCloneAndProjectReload() throws Exception {
        for(boolean compressed:new boolean[]{false,true})for(boolean noise:new boolean[]{false,true}) {
            byte[] source=LegacySchematicBenchmark.fixture(8,5,3,compressed,noise);mode(false);Schematic expected=Schematic.load("Parity",new ByteArrayInputStream(source));mode(true);long before=LegacySchematicAccess.completedAnalyses();Schematic actual=Schematic.load("Parity",new ByteArrayInputStream(source));assertEquals(before+1,LegacySchematicAccess.completedAnalyses());same(expected,actual);same(expected,actual.clone());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(actual);}before=LegacySchematicAccess.completedAnalyses();
            try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){same(expected,(Schematic)in.readObject());}assertEquals(before+1,LegacySchematicAccess.completedAnalyses());
        }
    }
    @Test public void addNibblesMaskAndEmptyPlanesKeepHistoricalSemantics() throws Exception {
        compare(tag(new byte[]{0,1,44,0,44,1},new byte[]{15,0,0,0,0,0},new byte[]{1,1,1}));
        compare(tag(new byte[6],new byte[6],new byte[]{17,17,17}));
        CompoundTag multi=tag(new byte[6],new byte[6],null);multi.getValue().put("Height",new ShortTag("Height",(short)2));multi.getValue().put("Blocks",new ByteArrayTag("Blocks",new byte[]{0,0,0,0,0,0,1,0,1,0,0,0}));multi.getValue().put("Data",new ByteArrayTag("Data",new byte[12]));compare(multi);
    }
    @Test public void explicitOffsetsConnectionsAndEntityMetadataStayEquivalent() throws Exception {
        CompoundTag source=tag(new byte[]{85,1,102,44,85,1},new byte[6],new byte[]{0,16,0});source.getValue().put("WEOffsetX",new IntTag("WEOffsetX",-1));source.getValue().put("WEOffsetY",new IntTag("WEOffsetY",0));source.getValue().put("WEOffsetZ",new IntTag("WEOffsetZ",-1));
        Map<String,Tag> entity=new HashMap<>(Map.of("id",new StringTag("id","welt:entity"),"Pos",new ListTag<>("Pos",DoubleTag.class,List.of(new DoubleTag("",0.5),new DoubleTag("",0.0),new DoubleTag("",0.5)))));
        Map<String,Tag> tile=new HashMap<>(Map.of("id",new StringTag("id","welt:container"),"x",new IntTag("x",1),"y",new IntTag("y",0),"z",new IntTag("z",1)));
        source.getValue().put("Entities",new ListTag<>("Entities",CompoundTag.class,List.of(new CompoundTag("",entity))));source.getValue().put("TileEntities",new ListTag<>("TileEntities",CompoundTag.class,List.of(new CompoundTag("",tile))));compare(source);
        source.getValue().put("WEOffsetX",new IntTag("WEOffsetX",-9));compare(source);
    }
    @Test public void malformedArraysRetainTheOriginalException() throws Exception {
        for(CompoundTag source:List.of(tag(new byte[]{1,1,1,1,1,1},new byte[0],null),tag(new byte[]{1,1,1,1,1,1},new byte[6],new byte[0]))) {
            RuntimeException expected=null;for(boolean nativeMode:new boolean[]{false,true}){mode(nativeMode);try{new Schematic("Parity",source,null);fail("Malformed source accepted");}catch(RuntimeException e){if(!nativeMode)expected=e;else{assertEquals(expected.getClass(),e.getClass());assertEquals(expected.getMessage(),e.getMessage());}}}
        }
    }
    @Test public void liveArrayEditsAndIndependentWorkersRetainLookupBehaviour() throws Exception {
        CompoundTag source=tag(new byte[]{44,1,44,1,44,1},new byte[6],new byte[]{1,1,1});Schematic actual=compare(source);
        ((ByteArrayTag)source.getTag("Blocks")).getValue()[0]=45;assertSame(org.pepsoft.minecraft.Material.get(301,0),actual.getMaterial(0,0,0));
        mode(true);var workers=Executors.newFixedThreadPool(4);try{List<Future<Schematic>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->new Schematic("Parity",source,null)));Schematic first=jobs.get(0).get();for(var job:jobs)same(first,job.get());}finally{workers.shutdownNow();}
    }
}
