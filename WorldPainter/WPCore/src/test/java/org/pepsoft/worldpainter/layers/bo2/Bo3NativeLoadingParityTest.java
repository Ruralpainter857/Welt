package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.minecraft.TileEntity;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class Bo3NativeLoadingParityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String previousGen, previousFlag;
    @Before public void remember() { previousGen=System.getProperty("wp.native.gen");previousFlag=System.getProperty("welt.native.bo3"); }
    @After public void restore() { restore("wp.native.gen",previousGen);restore("welt.native.bo3",previousFlag); }
    private static void restore(String key,String value) { if(value==null)System.clearProperty(key);else System.setProperty(key,value); }
    private static void mode(boolean nativeMode) { System.setProperty("wp.native.gen",Boolean.toString(nativeMode));System.setProperty("welt.native.bo3",Boolean.toString(nativeMode)); }
    private File file(String text) throws Exception { File file=temporary.newFile();Files.writeString(file.toPath(),text,StandardCharsets.US_ASCII);return file; }
    @SuppressWarnings("unchecked") private static Map<Point3i,Bo3BlockSpec> blocks(Bo3Object object) throws Exception { var field=Bo3Object.class.getDeclaredField("blocks");field.setAccessible(true);return (Map<Point3i,Bo3BlockSpec>)field.get(object); }
    private static Object field(Object object,String name) throws Exception { var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
    private static void sameTile(TileEntity a,TileEntity b) {
        if(a==null){assertNull(b);return;}assertNotNull(b);assertEquals(a.getClass(),b.getClass());assertEquals(a.getX(),b.getX());assertEquals(a.getY(),b.getY());assertEquals(a.getZ(),b.getZ());
        Map<String,Tag> x=a.toNBT().getValue(),y=b.toNBT().getValue();assertEquals(x.keySet(),y.keySet());
        for(String key:x.keySet()) { Tag left=x.get(key),right=y.get(key);assertEquals(left.getClass(),right.getClass());
            if(left instanceof StringTag t)assertEquals(t.getValue(),((StringTag)right).getValue());
            else if(left instanceof IntTag t)assertEquals(t.getValue(),((IntTag)right).getValue());
            else if(left instanceof ByteArrayTag t)assertArrayEquals(t.getValue(),((ByteArrayTag)right).getValue());
            else fail("Unhandled fixture tag");
        }
    }
    private static void same(Bo3Object a,Bo3Object b) throws Exception {
        assertEquals(a.getDimensions(),b.getDimensions());assertEquals(a.getOffset(),b.getOffset());assertEquals(a.getAttributes(),b.getAttributes());assertEquals(field(a,"properties"),field(b,"properties"));
        Map<Point3i,Bo3BlockSpec> left=blocks(a),right=blocks(b);assertEquals(left.keySet(),right.keySet());
        for(Point3i coords:left.keySet()) {
            Bo3BlockSpec x=left.get(coords),y=right.get(coords);assertEquals(x.getCoords(),y.getCoords());
            Bo3BlockSpec.RandomBlock[] vx=(Bo3BlockSpec.RandomBlock[])field(x,"randomBlocks"),vy=(Bo3BlockSpec.RandomBlock[])field(y,"randomBlocks");
            if(vx==null){assertNull(vy);assertEquals(x.getMaterial(),y.getMaterial());sameTile((TileEntity)field(x,"tileEntity"),(TileEntity)field(y,"tileEntity"));}
            else {assertNotNull(vy);assertEquals(vx.length,vy.length);for(int i=0;i<vx.length;i++){assertEquals(vx[i].chance,vy[i].chance);assertSame(vx[i].material,vy[i].material);sameTile(vx[i].tileEntity,vy[i].tileEntity);}}
        }
    }
    private Bo3Object compare(File file) throws Exception {
        mode(false);Bo3Object expected=Bo3Object.load("Parity",file);long before=Bo3NativeParser.completedObjects();mode(true);Bo3Object actual=Bo3Object.load("Parity",file);assertEquals(before+1,Bo3NativeParser.completedObjects());same(expected,actual);return actual;
    }
    @Test public void completeDenseMixedObjectsKeepAllAlternativesAndSave() throws Exception {
        for(String scenario:List.of("flat","mixed")) {
            Bo3Object actual=compare(file(Bo3LoadBenchmark.fixture(8,scenario)));same(actual,actual.clone());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(actual);}
            try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))){same(actual,(Bo3Object)in.readObject());}
        }
    }
    @Test public void sourceOrderMetadataAxesDuplicatesAndExtremesArePreserved() throws Exception {
        compare(file(" # ignored\r\nRotateRandomly: true\rkey: first\nkey: last: value\nunknown warning\nBranch(ignored)\nBlock(-2,7,3,STONE)\nRandomBlock(+1,5,-4,1,-3,35: 4,101)\nBlock(-2,7,3,20,,,)\n"));
        compare(file("Block(-2147483648,0,0,1)\nBlock(2147483647,0,0,1)\n"));
    }
    @Test public void repeatedExternalNbtKeepsIndependentPayloadAndCoordinates() throws Exception {
        File nbt=temporary.newFile("entity.nbt");
        Map<String,Tag> values=new HashMap<>(Map.of("id",new StringTag("id","welt:container"),"x",new IntTag("x",0),"y",new IntTag("y",0),"z",new IntTag("z",0),"custom",new ByteArrayTag("custom",new byte[]{1,2,3})));
        try(NBTOutputStream out=new NBTOutputStream(new GZIPOutputStream(new FileOutputStream(nbt)))){out.writeTag(new CompoundTag("",new HashMap<>(Map.of("wrapper",new CompoundTag("wrapper",values)))));}
        Bo3Object actual=compare(file("Block(0,0,0,54,entity.nbt)\nBlock(1,2,3,54,entity.nbt)\nRandomBlock(2,3,4,54,entity.nbt,0,1,100)\n"));
        List<TileEntity> tiles=new ArrayList<>();for(Bo3BlockSpec spec:blocks(actual).values())tiles.addAll(spec.getTileEntities());assertEquals(3,tiles.size());
        assertNotSame(tiles.get(0),tiles.get(1));((ByteArrayTag)tiles.get(0).toNBT().getTag("custom")).getValue()[0]=99;
        for(int i=1;i<tiles.size();i++)assertEquals(1,((ByteArrayTag)tiles.get(i).toNBT().getTag("custom")).getValue()[0]);
    }
    @Test public void originalErrorsAndPrefixPrecedenceRemainAvailable() throws Exception {
        for(String text:List.of("","Block(0, 0,0,1)","BlockCheck(0,0,0)","Block(0,0,0,unknown)","RandomBlock(0,0,0,1,missing.nbt,5)","RandomBlock(0,0,0,1,missing.nbt,bad)")) {
            File file=file(text);Exception reference=null;
            for(boolean nativeMode:new boolean[]{false,true}) {mode(nativeMode);try{Bo3Object.load("Parity",file);fail("Malformed fixture accepted");}catch(IOException|RuntimeException e){if(!nativeMode)reference=e;else{assertEquals(reference.getClass(),e.getClass());assertEquals(reference.getMessage(),e.getMessage());}}}
        }
    }
    @Test public void independentWorkersShareNoTransactionTemplates() throws Exception {
        File file=file(Bo3LoadBenchmark.fixture(4,"mixed"));mode(true);var workers=Executors.newFixedThreadPool(4);
        try {List<Future<Bo3Object>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(workers.submit(()->Bo3Object.load("Parity",file)));Bo3Object first=jobs.get(0).get();for(Future<Bo3Object> job:jobs)same(first,job.get());}finally{workers.shutdownNow();}
    }
}
