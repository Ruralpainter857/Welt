package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.pepsoft.minecraft.Material;
import javax.vecmath.Point3i;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

/** Complete structure loading and editor block access, with validation outside timing. */
public final class StructureLoadBenchmark {
    private static final int SPARSE_MASK = Integer.getInteger("welt.benchmark.structureSparseMask", 15);
    private static volatile Structure[] heldLibrary;
    private static volatile long checksum;

    private static boolean present(int x, int y, int z, boolean sparse) {
        return !sparse || ((x * 13 + y * 11 + z * 5) & SPARSE_MASK) == 0;
    }

    private static int state(int x, int y, int z) {
        return (x * 17 + y * 7 + z * 3) & 3;
    }

    private static ListTag<IntTag> position(String name, int x, int y, int z) {
        return new ListTag<>(name, IntTag.class, List.of(new IntTag("", x), new IntTag("", y), new IntTag("", z)));
    }

    static byte[] fixture(int side, boolean sparse) throws IOException {
        List<CompoundTag> palette = new ArrayList<>();
        String[] names = {"minecraft:air", "minecraft:stone", "minecraft:oak_leaves", "welt:custom"};
        for (int i = 0; i < names.length; i++) {
            Map<String, Tag> entry = new LinkedHashMap<>();
            entry.put("Name", new StringTag("Name", names[i]));
            if (i >= 2) {
                String key = i == 2 ? "persistent" : "facing", value = i == 2 ? "true" : "north";
                entry.put("Properties", new CompoundTag("Properties", new LinkedHashMap<>(Map.of(key, new StringTag(key, value)))));
            }
            palette.add(new CompoundTag("", entry));
        }
        List<CompoundTag> blocks = new ArrayList<>();
        for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
            if (!present(x, y, z, sparse)) continue;
            Map<String, Tag> block = new LinkedHashMap<>();
            block.put("pos", position("pos", x, z, y));
            block.put("state", new IntTag("state", state(x, y, z)));
            blocks.add(new CompoundTag("", block));
        }
        Map<String, Tag> root = new LinkedHashMap<>();
        root.put("size", position("size", side, side, side));
        root.put("palette", new ListTag<>("palette", CompoundTag.class, palette));
        root.put("blocks", new ListTag<>("blocks", CompoundTag.class, blocks));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream out = new NBTOutputStream(new GZIPOutputStream(bytes))) {
            out.writeTag(new CompoundTag("", root));
        }
        return bytes.toByteArray();
    }

    private static long visit(Structure object, int side) {
        long hash = 1;
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            boolean mask = object.getMask(x, y, z);
            Material material = object.getMaterial(x, y, z);
            hash = hash * 31 + (mask ? 1 : 0);
            hash = hash * 31 + (material == null ? 0 : material.hashCode());
        }
        return hash;
    }

    private static void validate(Structure object, int side, boolean sparse) {
        if (!object.getDimensions().equals(new Point3i(side, side, side))) throw new AssertionError("Dimensions differ");
        String[] names = {"minecraft:air", "minecraft:stone", "minecraft:oak_leaves", "welt:custom"};
        for (boolean ignoreAir : new boolean[]{true, false}) {
            object.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, ignoreAir);
            for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
                boolean exists = present(x, y, z, sparse);
                int index = state(x, y, z);
                Material material = object.getMaterial(x, y, z);
                if (exists ? material == null || !material.name.equals(names[index]) : material != null)
                    throw new AssertionError("Material differs");
                if (object.getMask(x, y, z) != (exists && (!ignoreAir || index != 0)))
                    throw new AssertionError("Mask differs");
                if (exists && index == 2 && !"true".equals(material.getProperty("persistent"))) throw new AssertionError("Leaf property differs");
                if (exists && index == 3 && !"north".equals(material.getProperty("facing"))) throw new AssertionError("Custom property differs");
            }
        }
        object.setAttribute(Structure.ATTRIBUTE_IGNORE_AIR, null);
        Point3i expected = object.guestimateOffset();
        if (expected != null && !expected.equals(object.getOffset())) throw new AssertionError("Offset differs");
    }

    public static void main(String[] args) throws Exception {
        int side = Integer.getInteger("welt.benchmark.structureSide", 24);
        int objects = Integer.getInteger("welt.benchmark.structureObjects", 4);
        int warmups = Integer.getInteger("welt.benchmark.structureWarmups", 10);
        boolean sparse = Boolean.getBoolean("welt.benchmark.structureSparse");
        boolean access = "access".equals(System.getProperty("welt.benchmark.structureOperation", "load"));
        boolean saving = "save".equals(System.getProperty("welt.benchmark.structureOperation", "load"));
        boolean compare = args.length > 0 && args[0].equals("compare"), rust = args.length > 0 && args[0].equals("rust");
        if (SPARSE_MASK < 15 || SPARSE_MASK > 65535 || (SPARSE_MASK & (SPARSE_MASK + 1)) != 0 || side < 2 || side > 64 || objects < 1 || objects > 16 || warmups < 5 || warmups > 100)
            throw new IllegalArgumentException("Invalid fixture dimensions");
        byte[] bytes = fixture(side, sparse);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        double[] times = new double[9];
        long[] allocations = new long[9];
        double[] javaTimes = new double[9], rustTimes = new double[9], ratios = new double[9];
        long[] javaAllocations = new long[9], rustAllocations = new long[9];
        long storage = -1, callsAtStart = StructureCompactBlocks.completedObjects();
        for (int trial = -warmups; trial < 9; trial++) {
            for (int pass = 0; pass < (compare ? 2 : 1); pass++) {
                boolean nativeMode = compare ? ((trial + pass) & 1) != 0 : rust;
                System.setProperty("wp.native.gen", Boolean.toString(nativeMode));
                System.setProperty("welt.native.structure", Boolean.toString(nativeMode));
                long callsBefore = StructureCompactBlocks.completedObjects();
                Structure[] loaded = new Structure[objects];
                if (access || saving) for (int i = 0; i < objects; i++) loaded[i] = Structure.load("Benchmark", new ByteArrayInputStream(bytes));
                long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                long hash = 1; byte[] saved = null;
                if (access) for (Structure object : loaded) hash = hash * 31 + visit(object, side);
                else if (saving) {
                    var sink = new ByteArrayOutputStream();
                    try (var out = new ObjectOutputStream(new GZIPOutputStream(sink))) { out.writeObject(loaded); }
                    saved = sink.toByteArray();
                } else for (int i = 0; i < objects; i++) loaded[i] = Structure.load("Benchmark", new ByteArrayInputStream(bytes));
                long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
                long calls = StructureCompactBlocks.completedObjects() - callsBefore;
                if (calls != (nativeMode ? objects : 0)) throw new AssertionError("Unexpected native coverage");
                storage = nativeMode ? 0 : -1;
                for (Structure object : loaded) { validate(object, side, sparse); if (nativeMode) storage += object.getBlockIndexStorageBytes(); }
                if (saving) try (var in = new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(saved)))) {
                    Structure[] restored = (Structure[]) in.readObject();
                    if (restored.length != objects) throw new AssertionError("Saved library size differs");
                    for (Structure object : restored) validate(object, side, sparse);
                }
                heldLibrary = loaded;
                checksum = hash;
                if (trial >= 0) {
                    times[trial] = elapsed / 1e6; allocations[trial] = allocated;
                    if (nativeMode) { rustTimes[trial] = elapsed / 1e6; rustAllocations[trial] = allocated; }
                    else { javaTimes[trial] = elapsed / 1e6; javaAllocations[trial] = allocated; }
                }
            }
            if (compare && trial >= 0) ratios[trial] = javaTimes[trial] / rustTimes[trial];
        }
        System.gc();
        Thread.sleep(150);
        Arrays.sort(times);
        Arrays.sort(allocations);
        if (compare) {
            Arrays.sort(javaTimes); Arrays.sort(rustTimes); Arrays.sort(ratios);
            Arrays.sort(javaAllocations); Arrays.sort(rustAllocations);
            System.out.printf(Locale.ROOT,
                    "structurePaired javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f javaAllocated=%d rustAllocated=%d nativeCalls=%d%n",
                    javaTimes[4], rustTimes[4], ratios[4], ratios[0], ratios[8], javaAllocations[4], rustAllocations[4], StructureCompactBlocks.completedObjects() - callsAtStart);
        }
        if (compare) {
            System.out.printf(Locale.ROOT, "structureHeldLibrary operation=%s sparse=%s side=%d objects=%d postGcHeapBytes=%d compactIndexBytes=%d%n",
                    access ? "access" : saving ? "save" : "load", sparse, side, objects,
                    ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), storage);
            return;
        }
        System.out.printf(Locale.ROOT,
                "structureLibrary operation=%s sparse=%s side=%d objects=%d medianMs=%.3f callerAllocatedBytes=%d postGcHeapBytes=%d compressedSourceBytes=%d checksum=%d heldObjects=%d compactIndexBytes=%d%n",
                access ? "access" : saving ? "save" : "load", sparse, side, objects, times[4], allocations[4],
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), bytes.length, checksum, heldLibrary.length, storage);
    }
}