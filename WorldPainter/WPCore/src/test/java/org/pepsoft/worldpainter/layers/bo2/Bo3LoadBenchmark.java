package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;
import javax.vecmath.Point3i;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Full BO3 file loading, including optional external NBT; validation is outside timing. */
public final class Bo3LoadBenchmark {
    private static volatile Bo3Object[] heldLibrary;
    private static final Material[] MATERIALS = {Material.STONE, Material.get(17, 0), Material.get(20, 0), Material.get(35, 4)};

    private static String fixture(int side, String scenario) {
        StringBuilder text = new StringBuilder("# Synthetic complete-load fixture\nRotateRandomly: false\ncustom: preserved\n");
        String[] specs = {"1", "LOG:0", "20", "35:4"};
        for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
            int index = (z * side + y) * side + x;
            String coords = (x - side / 2) + "," + z + "," + (y - side / 2);
            if (scenario.equals("nbt") && (index & 127) == 0) {
                text.append("Block(").append(coords).append(",54,container.nbt)\n");
            } else if (!scenario.equals("flat") && (index & 7) == 0) {
                text.append("RandomBlock(").append(coords).append(",STONE,35,35:4,100)\n");
            } else {
                text.append("Block(").append(coords).append(',').append(specs[(x + y + z) & 3]).append(")\n");
            }
        }
        return text.toString();
    }

    private static void validate(Bo3Object object, File source, int side, String scenario) {
        if (!object.getDimensions().equals(new Point3i(side, side, side))) throw new AssertionError("Dimensions differ");
        if (!object.getOffset().equals(new Point3i(-side / 2, -side / 2, 0))) throw new AssertionError("Offset differs");
        if (object.getAttribute(Bo3Object.ATTRIBUTE_RANDOM_ROTATION)) throw new AssertionError("Rotation differs");
        if (!source.equals(object.getAttribute(Bo3Object.ATTRIBUTE_FILE))) throw new AssertionError("Source attribute differs");
        for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
            int index = (z * side + y) * side + x;
            if (!object.getMask(x, y, z)) throw new AssertionError("Missing block");
            Material material = object.getMaterial(x, y, z);
            if (scenario.equals("nbt") && (index & 127) == 0) {
                if (material != Material.get(54, 0)) throw new AssertionError("NBT block differs");
            } else if (!scenario.equals("flat") && (index & 7) == 0) {
                if (material != MATERIALS[0] && material != MATERIALS[3]) throw new AssertionError("Random alternative differs");
            } else if (material != MATERIALS[(x + y + z) & 3]) throw new AssertionError("Material differs");
        }
        List<TileEntity> tiles = object.getTileEntities();
        int expected = scenario.equals("nbt") ? (side * side * side + 127) / 128 : 0;
        if (expected == 0 ? tiles != null : tiles == null || tiles.size() != expected) throw new AssertionError("Tile entity count differs");
        if (tiles != null) for (TileEntity tile : tiles) {
            if (tile.getX() < 0 || tile.getX() >= side || tile.getY() < 0 || tile.getY() >= side || tile.getZ() < 0 || tile.getZ() >= side) throw new AssertionError("Tile entity offset differs");
        }
    }

    public static void main(String[] args) throws Exception {
        int side = Integer.getInteger("welt.benchmark.bo3Side", 16), objects = Integer.getInteger("welt.benchmark.bo3Objects", 4);
        int warmups = Integer.getInteger("welt.benchmark.bo3Warmups", 20);
        String scenario = System.getProperty("welt.benchmark.bo3Scenario", "flat");
        if (side < 2 || side > 64 || objects < 1 || objects > 16 || warmups < 5 || warmups > 100 || !Set.of("flat", "mixed", "nbt").contains(scenario)) throw new IllegalArgumentException("Invalid BO3 fixture parameters");
        Path directory = Files.createTempDirectory(Path.of("target"), "welt-bo3-benchmark-");
        Path source = directory.resolve("object.bo3"), nbt = directory.resolve("container.nbt");
        try {
            Files.writeString(source, fixture(side, scenario), StandardCharsets.US_ASCII);
            Map<String, Tag> values = new HashMap<>(Map.of("id", new StringTag("id", "welt:container"),
                    "x", new IntTag("x", 0), "y", new IntTag("y", 0), "z", new IntTag("z", 0),
                    "custom", new StringTag("custom", "preserved")));
            try (NBTOutputStream out = new NBTOutputStream(new GZIPOutputStream(Files.newOutputStream(nbt)))) {
                out.writeTag(new CompoundTag("", values));
            }
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().getId();
            double[] times = new double[9]; long[] allocations = new long[9];
            for (int trial = -warmups; trial < 9; trial++) {
                Bo3Object[] loaded = new Bo3Object[objects];
                long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                for (int i = 0; i < objects; i++) loaded[i] = Bo3Object.load("Benchmark", source.toFile());
                long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
                for (Bo3Object object : loaded) validate(object, source.toFile(), side, scenario);
                heldLibrary = loaded;
                if (trial >= 0) { times[trial] = elapsed / 1e6; allocations[trial] = allocated; }
            }
            System.gc(); Thread.sleep(150); Arrays.sort(times); Arrays.sort(allocations);
            System.out.printf(Locale.ROOT, "bo3Library scenario=%s side=%d objects=%d medianMs=%.3f callerAllocatedBytes=%d postGcHeapBytes=%d sourceBytes=%d heldObjects=%d%n",
                    scenario, side, objects, times[4], allocations[4], ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Files.size(source), heldLibrary.length);
        } finally {
            // Delete only the three paths created by this benchmark, never a recursive directory tree.
            Files.deleteIfExists(source); Files.deleteIfExists(nbt); Files.deleteIfExists(directory);
        }
    }
}
