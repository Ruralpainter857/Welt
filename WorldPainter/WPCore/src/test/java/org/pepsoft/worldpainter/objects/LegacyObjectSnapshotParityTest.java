package org.pepsoft.worldpainter.objects;

import org.junit.*;
import org.jnbt.*;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.layers.bo2.Schematic;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LegacyObjectSnapshotParityTest {
    private final Map<String, String> previous = new HashMap<>();
    @Before public void ready() {
        for (String key : new String[] {"wp.native.gen", "welt.native.objectSnapshots", "welt.native.objectTransforms"}) previous.put(key, System.getProperty(key));
        mode(true);
        int[] mapping = new int[65536]; mapping[16] = 1;
        boolean available = NativeLoader.areSlicesAvailable() && NativeSlices.transformLegacyMaterialVolume(
                new byte[] {1}, new byte[] {0}, null, mapping, 2, new int[] {1,1,1,1}, new byte[1]);
        available = available && NativeSlices.collectLegacyMaterialStates(new byte[] {1},new byte[] {0},null,new int[] {1,1,1}) != null;
        if (Boolean.getBoolean("welt.test.requireLegacySnapshots")) assertTrue("Release legacy snapshot bridge required", available);
        else org.junit.Assume.assumeTrue("Legacy snapshot bridge unavailable", available);
    }
    @After public void restore() { previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }); }
    private static void mode(boolean nativeMode) {
        for (String key : new String[] {"wp.native.gen", "welt.native.objectSnapshots", "welt.native.objectTransforms"}) System.setProperty(key, Boolean.toString(nativeMode));
    }
    private static CompoundTag tag() {
        Map<String, Tag> values = new HashMap<>();
        values.put("Materials", new StringTag("Materials", "Alpha"));
        values.put("Width", new ShortTag("Width", (short) 3)); values.put("Length", new ShortTag("Length", (short) 2)); values.put("Height", new ShortTag("Height", (short) 2));
        values.put("Blocks", new ByteArrayTag("Blocks", new byte[] {0,1,53,54,17,85,0,44,45,1,54,53}));
        values.put("Data", new ByteArrayTag("Data", new byte[] {0,0,1,2,4,0,15,0,15,0,3,0}));
        values.put("AddBlocks", new ByteArrayTag("AddBlocks", new byte[] {0,0,0,0x11,0,0}));
        Map<String, Tag> entity = new HashMap<>();
        entity.put("id", new StringTag("id", "minecraft:pig"));
        entity.put("Pos", new ListTag<>("Pos", DoubleTag.class, List.of(new DoubleTag("",1.5),new DoubleTag("",0.5),new DoubleTag("",0.5))));
        entity.put("Rotation", new ListTag<>("Rotation", FloatTag.class, List.of(new FloatTag("",0),new FloatTag("",0))));
        values.put("Entities", new ListTag<>("Entities", CompoundTag.class, List.of(new CompoundTag("",entity))));
        Map<String, Tag> tile = new HashMap<>(); tile.put("id",new StringTag("id","minecraft:chest"));
        tile.put("x",new IntTag("x",1)); tile.put("y",new IntTag("y",0)); tile.put("z",new IntTag("z",1));
        values.put("TileEntities",new ListTag<>("TileEntities",CompoundTag.class,List.of(new CompoundTag("",tile))));
        return new CompoundTag("Schematic", values);
    }
    private static void same(WPObject expected, WPObject actual) {
        assertEquals(expected.getDimensions(), actual.getDimensions()); assertEquals(expected.getName(), actual.getName()); assertEquals(expected.getAttributes(), actual.getAttributes());
        Point3i size = expected.getDimensions();
        for (int z = 0; z < size.z; z++) for (int y = 0; y < size.y; y++) for (int x = 0; x < size.x; x++) {
            assertEquals(expected.getMask(x,y,z), actual.getMask(x,y,z)); assertSame(expected.getMaterial(x,y,z), actual.getMaterial(x,y,z));
        }
        assertEquals(expected.getEntities() == null, actual.getEntities() == null);
        if (expected.getEntities() != null) {
            assertEquals(expected.getEntities().size(), actual.getEntities().size());
            for (int i=0;i<expected.getEntities().size();i++) assertArrayEquals(nbt(expected.getEntities().get(i).toNBT()),nbt(actual.getEntities().get(i).toNBT()));
        }
        assertEquals(expected.getTileEntities() == null, actual.getTileEntities() == null);
        if (expected.getTileEntities() != null) {
            assertEquals(expected.getTileEntities().size(), actual.getTileEntities().size());
            for (int i=0;i<expected.getTileEntities().size();i++) assertArrayEquals(nbt(expected.getTileEntities().get(i).toNBT()),nbt(actual.getTileEntities().get(i).toNBT()));
        }
    }
    private static byte[] nbt(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (NBTOutputStream output = new NBTOutputStream(bytes)) { output.writeTag(canonical(tag)); }
            return bytes.toByteArray();
        } catch (IOException error) { throw new AssertionError(error); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Tag canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            Map<String, Tag> sorted = new TreeMap<>();
            compound.getValue().forEach((key, value) -> sorted.put(key, canonical(value)));
            return new CompoundTag(compound.getName(), sorted);
        }
        if (tag instanceof ListTag<?> list) {
            List<Tag> values = new ArrayList<>();
            for (Tag value : list.getValue()) values.add(canonical(value));
            return new ListTag(list.getName(), list.getType(), values);
        }
        return tag;
    }
    @Test public void wideLegacyPalettesPreserveExtendedIdsAndMasks() {
        CompoundTag tag = tag(); Map<String, Tag> values = tag.getValue();
        values.put("Width",new ShortTag("Width",(short)5)); values.put("Length",new ShortTag("Length",(short)5)); values.put("Height",new ShortTag("Height",(short)12));
        byte[] blocks=new byte[300],data=new byte[300],add=new byte[150];
        for(int i=0;i<300;i++){int id=257+i;blocks[i]=(byte)id;add[i/2]|=(byte)((id>>>8)<<((i&1)*4));}
        values.put("Blocks",new ByteArrayTag("Blocks",blocks)); values.put("Data",new ByteArrayTag("Data",data)); values.put("AddBlocks",new ByteArrayTag("AddBlocks",add));
        mode(false); Schematic source=new Schematic("Wide",tag,null); GenericObject expected=new GenericObject(source);
        mode(true); long before=CompactMaterialVolume.completedLegacySnapshots(); GenericObject actual=new GenericObject(source);
        assertEquals(before+1,CompactMaterialVolume.completedLegacySnapshots()); same(expected,actual);
    }
    @Test public void completeSnapshotsAndComposedTransformsUseOneNativeTransaction() throws Exception {
        mode(false); Schematic source = new Schematic("Legacy", tag(), null);
        List<WPObject> objects = new ArrayList<>(); objects.add(source);
        for (int rotation = 0; rotation < 4; rotation++) objects.add(new RotatedObject(source, rotation, DefaultPlugin.JAVA_ANVIL_1_15));
        for (boolean axis : new boolean[] {false, true}) objects.add(new MirroredObject(source, axis, DefaultPlugin.JAVA_ANVIL_1_15));
        objects.add(new RotatedObject(new MirroredObject(new RotatedObject(source,3,DefaultPlugin.JAVA_ANVIL_1_15),true,DefaultPlugin.JAVA_ANVIL_1_15),1,DefaultPlugin.JAVA_ANVIL_1_15));
        for (WPObject object : objects) {
            mode(false); GenericObject expected = new GenericObject(object);
            mode(true); long count = CompactMaterialVolume.completedLegacySnapshots(); GenericObject actual = new GenericObject(object);
            assertEquals(count + 1, CompactMaterialVolume.completedLegacySnapshots()); same(expected, actual);
            same(actual, actual.clone()); assertEquals(count + 1, CompactMaterialVolume.completedLegacySnapshots());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(actual); }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { same(expected, (GenericObject) in.readObject()); }
        }
    }
    @Test public void editorMaterialClassificationPreservesFirstEncounterIdentityAndLiveEdits() {
        mode(false); CompoundTag tag=tag(); Schematic source=new Schematic("Classification",tag,null);
        for(int round=0;round<2;round++) {
            List<Material> expected=new ArrayList<>();IdentityHashMap<Material,Boolean> seen=new IdentityHashMap<>();
            source.visitBlocks((object,x,y,z,material)->{if(seen.put(material,Boolean.TRUE)==null)expected.add(material);return true;});
            mode(true);long before=CompactMaterialVolume.completedLegacyClassifications();List<Material> actual=new ArrayList<>();
            IdentityHashMap<Material,Boolean> actualSeen=new IdentityHashMap<>();
            CompactObjectMaterials.forEach(source,material->{if(actualSeen.put(material,Boolean.TRUE)==null)actual.add(material);});assertEquals(before+1,CompactMaterialVolume.completedLegacyClassifications());
            assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++)assertSame(expected.get(i),actual.get(i));
            ((ByteArrayTag)tag.getTag("Blocks")).getValue()[1]=2;
        }
    }
    private static final class RandomObject extends NamedObjectWithAttributes {
        final Random random=new Random(812); int reads;
        RandomObject(){super("Random");}
        @Override public Point3i getDimensions(){return new Point3i(3,2,2);}
        @Override public boolean getMask(int x,int y,int z){return true;}
        @Override public Material getMaterial(int x,int y,int z){reads++;return random.nextBoolean()?Material.STONE:Material.AIR;}
        @Override public List<org.pepsoft.minecraft.Entity> getEntities(){return null;}
        @Override public List<org.pepsoft.minecraft.TileEntity> getTileEntities(){return null;}
        @Override public RandomObject clone(){return new RandomObject();}
        @Override public boolean visitBlocks(BlockVisitor visitor){
            for(int z=1;z>=0;z--)for(int x=2;x>=0;x--)for(int y=1;y>=0;y--)
                if(!visitor.visitBlock(this,x,y,z,getMaterial(x,y,z)))return false;
            return true;
        }
    }
    @Test public void unsupportedRandomSourcesKeepTheirTraversalAndEveryDraw(){
        RandomObject expectedSource=new RandomObject(), actualSource=new RandomObject();
        List<Material> expected=new ArrayList<>(),actual=new ArrayList<>();
        expectedSource.visitBlocks((object,x,y,z,material)->{expected.add(material);return true;});
        long count=CompactMaterialVolume.completedLegacyClassifications();
        CompactObjectMaterials.forEach(actualSource,actual::add);
        assertEquals(expected,actual);assertEquals(12,actualSource.reads);
        assertEquals(expectedSource.random.nextLong(),actualSource.random.nextLong());
        assertEquals(count,CompactMaterialVolume.completedLegacyClassifications());
    }
    @Test public void mutableSourceAndDimensionsKeepSnapshotAndFallbackSemantics() {
        mode(false); CompoundTag tag = tag(); Schematic source = new Schematic("Live", tag, null);
        mode(true); GenericObject first = new GenericObject(source);
        ((ByteArrayTag) tag.getTag("Blocks")).getValue()[1] = 2;
        mode(false); GenericObject expected = new GenericObject(source);
        mode(true); GenericObject second = new GenericObject(source); same(expected, second);
        assertSame(Material.STONE, first.getMaterial(1,0,0));
        source.getDimensions().x++; assertNull(source.copyCompactVolume()); source.getDimensions().x--;
    }
    @Test public void invalidTransactionsPreserveOutputAndRejectAliases() {
        byte[] blocks = new byte[12], data = new byte[12], output = new byte[12]; Arrays.fill(blocks, (byte) 1); Arrays.fill(output, (byte) 7);
        int[] mapping = new int[65536]; mapping[16] = 1;
        assertFalse(NativeSlices.transformLegacyMaterialVolume(blocks, data, null, mapping, 2, new int[] {3,2,2,1,7}, output));
        for (byte value : output) assertEquals(7, value);
        assertFalse(NativeSlices.transformLegacyMaterialVolume(blocks, data, null, mapping, 2, new int[] {3,2,2,1}, blocks));
        mapping[16] = -1; assertFalse(NativeSlices.transformLegacyMaterialVolume(blocks, data, null, mapping, 2, new int[] {3,2,2,1}, output));
        for (byte value : output) assertEquals(7, value);
    }
    @Test public void independentWorkersReuseTheirOwnPalettePreparation() throws Exception {
        mode(false); Schematic source = new Schematic("Workers", tag(), null); GenericObject expected = new GenericObject(source);
        mode(true); long before = CompactMaterialVolume.completedLegacySnapshots();
        long classificationsBefore=CompactMaterialVolume.completedLegacyClassifications();
        List<Material> expectedMaterials=new ArrayList<>();source.visitBlocks((object,x,y,z,material)->{if(!expectedMaterials.contains(material))expectedMaterials.add(material);return true;});
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++) jobs.add(pool.submit(() -> {
                same(expected,new GenericObject(source));List<Material> values=new ArrayList<>();
                CompactObjectMaterials.forEach(source,material->{if(!values.contains(material))values.add(material);});
                assertEquals(expectedMaterials,values);
            }));
            for (Future<?> job : jobs) job.get();
            assertEquals(before + 12, CompactMaterialVolume.completedLegacySnapshots());
            assertEquals(classificationsBefore+12,CompactMaterialVolume.completedLegacyClassifications());
        } finally { pool.shutdownNow(); }
    }
}
