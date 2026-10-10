package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.junit.*;
import org.pepsoft.worldpainter.objects.WPObject;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class StructureCompactLoadingParityTest {
    private String previousGen, previousFlag;
    @Before public void remember() { previousGen = System.getProperty("wp.native.gen"); previousFlag = System.getProperty("welt.native.structure"); }
    @After public void restore() { restore("wp.native.gen", previousGen); restore("welt.native.structure", previousFlag); }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    private static void mode(boolean nativeMode) { System.setProperty("wp.native.gen", Boolean.toString(nativeMode)); System.setProperty("welt.native.structure", Boolean.toString(nativeMode)); }
    private static Structure load(byte[] bytes, boolean nativeMode) throws IOException { mode(nativeMode); return Structure.load("Parity", new ByteArrayInputStream(bytes)); }
    private static CompoundTag root(byte[] bytes) throws IOException {
        try (NBTInputStream in = new NBTInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) { return (CompoundTag) in.readTag(); }
    }
    private static byte[] encode(CompoundTag root) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (NBTOutputStream out = new NBTOutputStream(new GZIPOutputStream(bytes))) { out.writeTag(root); }
        return bytes.toByteArray();
    }
    private static void sameTag(Tag expected, Tag actual) {
        assertEquals(expected.getClass(), actual.getClass()); assertEquals(expected.getName(), actual.getName());
        if (expected instanceof ListTag<?> list) assertEquals(list.getType(), ((ListTag<?>) actual).getType());
        Object e, a;
        try { e = expected.getClass().getMethod("getValue").invoke(expected); a = actual.getClass().getMethod("getValue").invoke(actual); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        if (e instanceof Map<?,?> em && a instanceof Map<?,?> am) {
            assertEquals(em.keySet(), am.keySet()); for (Object key : em.keySet()) sameTag((Tag) em.get(key), (Tag) am.get(key));
        } else if (e instanceof List<?> el && a instanceof List<?> al) {
            assertEquals(el.size(), al.size()); for (int i = 0; i < el.size(); i++) sameTag((Tag) el.get(i), (Tag) al.get(i));
        } else if (e instanceof byte[] values) assertArrayEquals(values, (byte[]) a);
        else if (e instanceof int[] values) assertArrayEquals(values, (int[]) a);
        else if (e instanceof long[] values) assertArrayEquals(values, (long[]) a);
        else if (e instanceof Double value) assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits((Double) a));
        else if (e instanceof Float value) assertEquals(Float.floatToRawIntBits(value), Float.floatToRawIntBits((Float) a));
        else assertEquals(e, a);
    }
    private static void same(WPObject expected, WPObject actual) {
        try {
            var eRoot = expected.getClass().getDeclaredField("root"); var aRoot = actual.getClass().getDeclaredField("root");
            eRoot.setAccessible(true); aRoot.setAccessible(true); sameTag((Tag) eRoot.get(expected), (Tag) aRoot.get(actual));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        assertEquals(expected.getDimensions(), actual.getDimensions()); assertEquals(expected.getOffset(), actual.getOffset());
        assertEquals(expected.getName(), actual.getName()); assertEquals(expected.getAttributes(), actual.getAttributes());
        var dimensions = expected.getDimensions();
        for (int z = -1; z <= dimensions.z; z++) for (int x = -1; x <= dimensions.x; x++) for (int y = -1; y <= dimensions.y; y++) {
            assertEquals(expected.getMaterial(x, y, z), actual.getMaterial(x, y, z));
            assertEquals(expected.getMask(x, y, z), actual.getMask(x, y, z));
        }
        assertEquals(expected.getEntities() == null, actual.getEntities() == null);
        assertEquals(expected.getTileEntities() == null, actual.getTileEntities() == null);
        if (expected.getEntities() != null) {
            assertEquals(expected.getEntities().size(), actual.getEntities().size());
            for (int i = 0; i < expected.getEntities().size(); i++) {
                sameTag(expected.getEntities().get(i).toNBT(), actual.getEntities().get(i).toNBT());
                assertEquals(expected.getEntities().get(i).getId(), actual.getEntities().get(i).getId());
                assertArrayEquals(expected.getEntities().get(i).getPos(), actual.getEntities().get(i).getPos(), 0);
                assertArrayEquals(expected.getEntities().get(i).getRelPos(), actual.getEntities().get(i).getRelPos(), 0);
            }
        }
        if (expected.getTileEntities() != null) {
            assertEquals(expected.getTileEntities().size(), actual.getTileEntities().size());
            for (int i = 0; i < expected.getTileEntities().size(); i++) {
                var e = expected.getTileEntities().get(i); var a = actual.getTileEntities().get(i);
                sameTag(e.toNBT(), a.toNBT()); assertEquals(e.getId(), a.getId()); assertEquals(e.getX(), a.getX()); assertEquals(e.getY(), a.getY()); assertEquals(e.getZ(), a.getZ());
            }
        }
    }
    @Test public void preservesDenseAndSparseCompleteObjectsAndClones() throws Exception {
        for (boolean sparse : new boolean[]{false, true}) {
            byte[] bytes = StructureLoadBenchmark.fixture(8, sparse);
            Structure expected = load(bytes, false); long calls = StructureCompactBlocks.completedObjects();
            Structure actual = load(bytes, true); assertEquals(calls + 1, StructureCompactBlocks.completedObjects());
            assertTrue(actual.getBlockIndexStorageBytes() > 0); same(expected, actual); same(expected, actual.clone());
            expected.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, false); actual.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, false); same(expected, actual);
            actual.clone().setName("Independent"); assertEquals("Parity", actual.getName());
        }
    }
    @SuppressWarnings("unchecked")
    private static CompoundTag unusual() throws IOException {
        CompoundTag root = root(StructureLoadBenchmark.fixture(3, true));
        var palette = new ArrayList<>(((ListTag<CompoundTag>) root.getTag("palette")).getValue());
        palette.add(new CompoundTag("", new LinkedHashMap<>(Map.of("Name", new StringTag("Name", "minecraft:air"),
                "Properties", new CompoundTag("Properties", new LinkedHashMap<>(Map.of("custom", new StringTag("custom", "true"))))))));
        root.setTag("palette", new ListTag<>("palette", CompoundTag.class, palette));
        List<CompoundTag> blocks = new ArrayList<>();
        int[][] records = {{-1,0,0,1},{1,1,1,0},{1,1,1,3},{0,0,0,4},{3,0,0,2}};
        for (int i = 0; i < records.length; i++) {
            int[] p = records[i]; var tags = new LinkedHashMap<String,Tag>();
            tags.put("pos", new ListTag<>("pos", IntTag.class, List.of(new IntTag("", p[0]), new IntTag("", p[1]), new IntTag("", p[2]))));
            tags.put("state", new IntTag("state", p[3]));
            if (i == 1 || i == 2) tags.put("nbt", new CompoundTag("nbt", new LinkedHashMap<>(Map.of("id", new StringTag("id", "minecraft:chest"), "CustomName", new StringTag("CustomName", "Chest " + i)))));
            blocks.add(new CompoundTag("", tags));
        }
        root.setTag("blocks", new ListTag<>("blocks", CompoundTag.class, blocks));
        var entity = new LinkedHashMap<String,Tag>();
        entity.put("pos", new ListTag<>("pos", DoubleTag.class, List.of(new DoubleTag("", -0.5), new DoubleTag("", 1.25), new DoubleTag("", 2.0))));
        entity.put("nbt", new CompoundTag("nbt", new LinkedHashMap<>(Map.of("id", new StringTag("id", "minecraft:pig")))));
        root.setTag("entities", new ListTag<>("entities", CompoundTag.class, List.of(new CompoundTag("", entity))));
        root.setTag("unicode", new StringTag("unicode", "Snowman ☃"));
        root.setTag("unknown", new LongArrayTag("unknown", new long[]{Long.MIN_VALUE, Long.MAX_VALUE}));
        return root;
    }
    @Test public void preservesDuplicatesOutsidePositionsExplicitAirAndEntities() throws Exception {
        byte[] bytes = encode(unusual()); Structure expected = load(bytes, false), actual = load(bytes, true);
        same(expected, actual); assertEquals(96, actual.getBlockIndexStorageBytes());
        assertNotNull(actual.getMaterial(-1, 0, 0)); assertNotNull(actual.getMaterial(3, 0, 0));
        assertEquals(2, actual.getTileEntities().size());
        expected.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, false); actual.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, false); same(expected, actual);
    }
    @Test public void emptyObjectsAndMalformedInputsRetainJavaBehavior() throws Exception {
        CompoundTag empty = root(StructureLoadBenchmark.fixture(2, true));
        empty.setTag("blocks", new ListTag<>("blocks", CompoundTag.class, List.of()));
        same(load(encode(empty), false), load(encode(empty), true));
        assertNull(NativeSlices.extractStructure(new byte[]{10,0,0,9}));
        CompoundTag broken = unusual();
        broken.setTag("blocks", new ListTag<>("blocks", CompoundTag.class, List.of(new CompoundTag("", new LinkedHashMap<>(Map.of(
                "pos", new ListTag<>("pos", IntTag.class, List.of(new IntTag("", 0), new IntTag("", 0), new IntTag("", 0))), "state", new IntTag("state", -1)))))));
        byte[] bytes = encode(broken); RuntimeException reference = null;
        for (boolean nativeMode : new boolean[]{false, true}) {
            try { load(bytes, nativeMode); fail("Invalid state accepted"); }
            catch (RuntimeException e) { if (!nativeMode) reference = e; else { assertEquals(reference.getClass(), e.getClass()); assertEquals(reference.getMessage(), e.getMessage()); } }
        }
    }
    private static byte[] serialize(Object value) throws IOException {
        var bytes = new ByteArrayOutputStream(); try (var out = new ObjectOutputStream(bytes)) { out.writeObject(value); } return bytes.toByteArray();
    }
    @Test public void serializesThroughTheHistoricalStructureClassInBothDirections() throws Exception {
        byte[] bytes = encode(unusual()); Structure compact = load(bytes, true); byte[] saved = serialize(compact);
        try (var in = new ObjectInputStream(new ByteArrayInputStream(saved))) { same(compact, (Structure) in.readObject()); }
        Path directory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "legacy-structure-");
        Path source = directory.resolve("Structure.java"), classes = Files.createDirectories(directory.resolve("classes"));
        try (var input = getClass().getResourceAsStream("/org/pepsoft/worldpainter/layers/bo2/legacy-structure.java")) { assertNotNull(input); Files.copy(input, source); }
        var diagnostics = new ByteArrayOutputStream(); var compiler = javax.tools.ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals("Historical compilation failed", 0, compiler.run(null, diagnostics, diagnostics, "-encoding", "UTF-8", "-cp", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(Structure.class.getName())) { Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name); if (resolve) resolveClass(type); return type; }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            Class<?> historical = loader.loadClass(Structure.class.getName());
            try (var in = new ObjectInputStream(new ByteArrayInputStream(saved)) {
                @Override protected Class<?> resolveClass(ObjectStreamClass type) throws IOException, ClassNotFoundException { return type.getName().equals(Structure.class.getName()) ? historical : super.resolveClass(type); }
            }) { same(compact, (WPObject) in.readObject()); }
            Object original = historical.getMethod("load", String.class, InputStream.class).invoke(null, "Parity", new ByteArrayInputStream(bytes));
            try (var in = new ObjectInputStream(new ByteArrayInputStream(serialize(original)))) { same((WPObject) original, (Structure) in.readObject()); }
        }
    }
    @Test @SuppressWarnings("unchecked") public void preservesWidePalettesAndNonCubicDimensions() throws Exception {
        CompoundTag root = root(StructureLoadBenchmark.fixture(3, false));
        var palette = new ArrayList<>(((ListTag<CompoundTag>) root.getTag("palette")).getValue());
        for (int i = palette.size(); i <= 256; i++) palette.add(new CompoundTag("", new LinkedHashMap<>(Map.of("Name", new StringTag("Name", "welt:wide_" + i)))));
        root.setTag("palette", new ListTag<>("palette", CompoundTag.class, palette));
        root.setTag("size", new ListTag<>("size", IntTag.class, List.of(new IntTag("", 3), new IntTag("", 4), new IntTag("", 5))));
        ((ListTag<CompoundTag>) root.getTag("blocks")).getValue().get(0).setTag("state", new IntTag("state", 256));
        byte[] bytes = encode(root); Structure expected = load(bytes, false); long before = StructureCompactBlocks.completedObjects();
        Structure actual = load(bytes, true); assertEquals(before + 1, StructureCompactBlocks.completedObjects());
        same(expected, actual); assertEquals(120, actual.getBlockIndexStorageBytes());
    }
    @Test public void preservesSparseHashCollisionsAndRepeatedSignedPositions() throws Exception {
        CompoundTag root = root(StructureLoadBenchmark.fixture(2, false));
        Random random = new Random(12345); List<CompoundTag> blocks = new ArrayList<>();
        int[][] positions = new int[1000][3];
        for (int[] p : positions) for (int axis = 0; axis < 3; axis++) p[axis] = random.nextInt(10001) - 5000;
        for (int i = 0; i < 2000; i++) {
            int[] p = positions[i % positions.length]; var tags = new LinkedHashMap<String,Tag>();
            tags.put("pos", new ListTag<>("pos", IntTag.class, List.of(new IntTag("", p[0]), new IntTag("", p[1]), new IntTag("", p[2]))));
            tags.put("state", new IntTag("state", i % 4)); blocks.add(new CompoundTag("", tags));
        }
        root.setTag("blocks", new ListTag<>("blocks", CompoundTag.class, blocks));
        byte[] bytes = encode(root); Structure expected = load(bytes, false); long before = StructureCompactBlocks.completedObjects();
        Structure actual = load(bytes, true); assertEquals(before + 1, StructureCompactBlocks.completedObjects()); same(expected, actual);
        for (boolean ignoreAir : new boolean[]{true, false}) {
            expected.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, ignoreAir); actual.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, ignoreAir);
            for (int[] p : positions) {
                assertEquals(expected.getMaterial(p[0], p[2], p[1]), actual.getMaterial(p[0], p[2], p[1]));
                assertEquals(expected.getMask(p[0], p[2], p[1]), actual.getMask(p[0], p[2], p[1]));
            }
        }
    }
    @Test public void preservesSparseLowestPlaneOffsetAndIntegerRounding() throws Exception {
        CompoundTag root = root(StructureLoadBenchmark.fixture(2, false));
        root.setTag("size", new ListTag<>("size", IntTag.class, List.of(new IntTag("", 20), new IntTag("", 20), new IntTag("", 20))));
        List<CompoundTag> blocks = new ArrayList<>();
        for (int[] p : new int[][]{{5,2,8}, {2,2,4}, {0,7,0}}) {
            var fields = new LinkedHashMap<String,Tag>();
            fields.put("pos", new ListTag<>("pos", IntTag.class, List.of(new IntTag("", p[0]), new IntTag("", p[1]), new IntTag("", p[2]))));
            fields.put("state", new IntTag("state", 1)); blocks.add(new CompoundTag("", fields));
        }
        root.setTag("blocks", new ListTag<>("blocks", CompoundTag.class, blocks));
        byte[] bytes = encode(root); Structure expected = load(bytes, false), actual = load(bytes, true);
        same(expected, actual); assertEquals(new javax.vecmath.Point3i(-3,-6,-2), actual.getOffset());
        assertEquals(80, actual.getBlockIndexStorageBytes());
    }
    @Test public void ignoresADamagedLaterGzipMemberLikeTheOriginalReader() throws Exception {
        byte[] first = StructureLoadBenchmark.fixture(8, false), second = StructureLoadBenchmark.fixture(2, false);
        second[second.length - 8] ^= 1;
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        Structure expected = load(combined, false); long before = StructureCompactBlocks.completedObjects();
        Structure actual = load(combined, true); same(expected, actual);
        assertEquals(before, StructureCompactBlocks.completedObjects()); assertEquals(-1, actual.getBlockIndexStorageBytes());
    }
    @Test public void usesIndependentStorageAcrossWorkers() throws Exception {
        mode(true); byte[] bytes = StructureLoadBenchmark.fixture(8, false); var workers = Executors.newFixedThreadPool(4);
        try { var jobs = new ArrayList<Future<Structure>>(); for (int i = 0; i < 4; i++) jobs.add(workers.submit(() -> Structure.load("Parity", new ByteArrayInputStream(bytes))));
            Structure first = jobs.get(0).get(); for (var job : jobs) same(first, job.get());
        } finally { workers.shutdownNow(); }
    }
}