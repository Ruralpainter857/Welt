package org.pepsoft.worldpainter;

import jdk.jfr.Recording;
import org.junit.Test;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.util.TextProgressReceiver;
import org.pepsoft.worldpainter.exporting.WorldExportSettings;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.plugins.PlatformManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;

/**
 * Opt-in, fixed-worker whole-world export campaign with JVM warmup, alternating
 * order, per-stage CPU timings, JFR, and decompressed Anvil chunk parity.
 * Run from WPCore with -Dwelt.export.campaign=true.
 */
public final class ExportFullWorldCampaignTest extends AbstractTool {
    private static final Mode[] MODES = {
            new Mode("java", false, false, false),
            new Mode("export", true, false, false),
            new Mode("frost", true, true, false),
            new Mode("resources", true, false, true),
            new Mode("frost-resources", true, true, true)
    };

    @Test
    public void compareAlternatingFullWorldExports() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.export.campaign"));
        assumeTrue("welt_slices must be available for native campaigns", NativeLoader.areSlicesAvailable());
        final String oldHome = System.getProperty("user.home");
        final String oldThreads = System.getProperty("org.pepsoft.worldpainter.threads");
        final String oldExport = System.getProperty(Native.EXPORT_KEY);
        final String oldFrost = System.getProperty(Native.FROST_EXPORT_KEY);
        final String oldResources = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        final Path root = Files.createTempDirectory("welt-full-export-campaign-");
        try {
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("org.pepsoft.worldpainter.threads", "4");
            Files.createDirectories(root.resolve("home"));
            initialisePlatform();
            final File fixture = new File("../WPGUI/src/test/resources/Generated World.world").getCanonicalFile();
            final World2 world;
            try (FileInputStream input = new FileInputStream(fixture)) {
                final WorldIO worldIO = new WorldIO();
                worldIO.load(input);
                world = worldIO.getWorld();
            }
            for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
                Terrain.setCustomMaterial(i, world.getMixedMaterial(i));
            }

            final Map<String, List<Long>> measuredWallNanos = new LinkedHashMap<>();
            final Map<String, Path> parityOutputs = new LinkedHashMap<>();
            for (Mode mode : MODES) measuredWallNanos.put(mode.name, new ArrayList<>());

            // Two complete exports per mode warm class loading, JIT code and
            // native library paths before any campaign timing is retained.
            for (int warmup = 0; warmup < 2; warmup++) {
                final Mode[] order = MODES.clone();
                if ((warmup & 1) != 0) reverse(order);
                for (Mode mode : order) {
                    runExport(world, root, mode, "warmup-" + warmup, false);
                }
            }

            // Keep the profiled pass outside the timed campaign because JFR
            // sampling adds measurable overhead to a short whole-world export.
            final RunResult profile = runExport(world, root, MODES[0], "profile-java", true);
            printTimings("java-jfr", -1, profile);

            // Reverse the order on alternating rounds to limit thermal and cache bias.
            for (int round = 0; round < 5; round++) {
                final Mode[] order = MODES.clone();
                if ((round & 1) != 0) reverse(order);
                for (Mode mode : order) {
                    final RunResult result = runExport(world, root, mode,
                            "round-" + round, false);
                    measuredWallNanos.get(mode.name).add(result.wallNanos);
                    if (round == 4) parityOutputs.put(mode.name, result.output);
                    printTimings(mode.name, round, result);
                }
            }

            final Path javaOutput = parityOutputs.get("java");
            final Map<String, Map<Integer, byte[]>> javaChunks = readRegions(javaOutput);
            for (Mode mode : MODES) {
                final Map<String, Map<Integer, byte[]>> candidate = readRegions(parityOutputs.get(mode.name));
                assertRegionsEqual("Java/" + mode.name, javaChunks, candidate);
                System.out.println("Parity Java/" + mode.name + ": " + countChunks(candidate)
                        + " chunks NBT décompressés identiques.");
            }

            final long javaMedian = median(measuredWallNanos.get("java"));
            System.out.println("Résultats exports complets (5 mesures, 4 workers fixes) :");
            for (Mode mode : MODES) {
                final long modeMedian = median(measuredWallNanos.get(mode.name));
                System.out.printf("  %-16s médiane %.3f s; ratio Java/Welt %.3fx; essais %s%n",
                        mode.name, modeMedian / 1_000_000_000.0,
                        (double) javaMedian / modeMedian, seconds(measuredWallNanos.get(mode.name)));
            }
            System.out.println("Fichiers, sorties et éventuel JFR conservés sous " + root);
        } finally {
            restore("user.home", oldHome);
            restore("org.pepsoft.worldpainter.threads", oldThreads);
            restore(Native.EXPORT_KEY, oldExport);
            restore(Native.FROST_EXPORT_KEY, oldFrost);
            restore(Native.RESOURCES_EXPORT_KEY, oldResources);
        }
    }

    private static RunResult runExport(World2 world, Path root, Mode mode, String round,
                                       boolean recordJfr) throws Exception {
        Native.setExportEnabled(mode.export);
        System.setProperty(Native.FROST_EXPORT_KEY, Boolean.toString(mode.frost));
        System.setProperty(Native.RESOURCES_EXPORT_KEY, Boolean.toString(mode.resources));
        final Path output = root.resolve(round + "-" + mode.name);
        Files.createDirectories(output);
        final WorldExporter exporter = PlatformManager.getInstance().getExporter(world,
                new WorldExportSettings(singleton(DIM_NORMAL), null, null));
        final Path jfrPath = root.resolve("java-baseline.jfr");
        final long start = System.nanoTime();
        final Map<Integer, ChunkFactory.Stats> stats;
        if (recordJfr) {
            try (Recording recording = new Recording()) {
                recording.setName("Welt full export Java baseline");
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
                recording.start();
                try {
                    stats = exporter.export(output.toFile(), world.getName(),
                            exporter.selectBackupDir(output.toFile(), world.getName()),
                            new TextProgressReceiver());
                } finally {
                    recording.stop();
                    recording.dump(jfrPath);
                }
            }
        } else {
            stats = exporter.export(output.toFile(), world.getName(),
                    exporter.selectBackupDir(output.toFile(), world.getName()),
                    new TextProgressReceiver());
        }
        return new RunResult(System.nanoTime() - start, output, stats, recordJfr ? jfrPath : null);
    }

    private static void printTimings(String mode, int round, RunResult result) {
        final Map<String, Long> stages = new HashMap<>();
        result.stats.values().forEach(dimension -> dimension.timings.forEach((stage, duration) ->
                stages.merge(String.valueOf(stage), duration.get(), Long::sum)));
        System.out.printf("Export %-16s round %d: %.3f s mural", mode, round,
                result.wallNanos / 1_000_000_000.0);
        stages.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> System.out.printf("; %s %.3f s CPU cumulé",
                        entry.getKey(), entry.getValue() / 1_000_000_000.0));
        if (result.jfr != null) System.out.print("; JFR=" + result.jfr);
        System.out.println();
    }

    private static Map<String, Map<Integer, byte[]>> readRegions(Path root) throws IOException {
        final Map<String, Map<Integer, byte[]>> regions = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".mca")).toList()) {
                regions.put(path.getFileName().toString(), readRegion(path));
            }
        }
        return regions;
    }

    private static Map<Integer, byte[]> readRegion(Path path) throws IOException {
        final byte[] file = Files.readAllBytes(path);
        if (file.length < 8192) throw new IOException("Truncated region header: " + path);
        final Map<Integer, byte[]> chunks = new HashMap<>();
        for (int index = 0; index < 1024; index++) {
            final int header = index * 4;
            final int sector = ((file[header] & 0xff) << 16)
                    | ((file[header + 1] & 0xff) << 8) | (file[header + 2] & 0xff);
            if (sector == 0) continue;
            final int offset = sector * 4096;
            if (offset < 8192 || offset + 5 > file.length) throw new IOException("Invalid offset: " + path);
            final DataInputStream input = new DataInputStream(new ByteArrayInputStream(file, offset,
                    file.length - offset));
            final int length = input.readInt();
            final int compression = input.readUnsignedByte();
            if (length < 1 || offset + 4L + length > file.length) throw new IOException("Invalid length: " + path);
            final ByteArrayInputStream payload = new ByteArrayInputStream(input.readNBytes(length - 1));
            final ByteArrayOutputStream expanded = new ByteArrayOutputStream();
            try (var decompressor = switch (compression) {
                case 1 -> new GZIPInputStream(payload);
                case 2 -> new InflaterInputStream(payload);
                case 3 -> payload;
                default -> throw new IOException("Unknown compression " + compression + " in " + path);
            }) {
                decompressor.transferTo(expanded);
            }
            chunks.put(index, expanded.toByteArray());
        }
        return chunks;
    }

    private static void assertRegionsEqual(String comparison,
                                           Map<String, Map<Integer, byte[]>> expected,
                                           Map<String, Map<Integer, byte[]>> actual) {
        assertEquals(comparison + " region files", expected.keySet(), actual.keySet());
        for (String region : expected.keySet()) {
            final Map<Integer, byte[]> expectedChunks = expected.get(region);
            final Map<Integer, byte[]> actualChunks = actual.get(region);
            assertEquals(comparison + " " + region + " indexes", expectedChunks.keySet(), actualChunks.keySet());
            for (int chunk : expectedChunks.keySet()) {
                assertArrayEquals(comparison + " " + region + " chunk=" + chunk,
                        expectedChunks.get(chunk), actualChunks.get(chunk));
            }
        }
    }

    private static int countChunks(Map<String, Map<Integer, byte[]>> regions) {
        return regions.values().stream().mapToInt(Map::size).sum();
    }

    private static long median(List<Long> values) {
        final long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    private static String seconds(List<Long> values) {
        return Arrays.toString(values.stream().map(value -> String.format("%.3f", value / 1_000_000_000.0))
                .toArray());
    }

    private static void reverse(Mode[] modes) {
        for (int left = 0, right = modes.length - 1; left < right; left++, right--) {
            final Mode swap = modes[left];
            modes[left] = modes[right];
            modes[right] = swap;
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    private static final class Mode {
        private final String name;
        private final boolean export, frost, resources;

        private Mode(String name, boolean export, boolean frost, boolean resources) {
            this.name = name;
            this.export = export;
            this.frost = frost;
            this.resources = resources;
        }
    }

    private static final class RunResult {
        private final long wallNanos;
        private final Path output;
        private final Map<Integer, ChunkFactory.Stats> stats;
        private final Path jfr;

        private RunResult(long wallNanos, Path output, Map<Integer, ChunkFactory.Stats> stats, Path jfr) {
            this.wallNanos = wallNanos;
            this.output = output;
            this.stats = stats;
            this.jfr = jfr;
        }
    }
}
