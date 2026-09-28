package org.pepsoft.worldpainter;

import jdk.jfr.Recording;
import org.junit.Test;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.minecraft.ChunkPaletteBuffer;
import org.jnbt.NBTInputStream;
import org.jnbt.Tag;
import org.pepsoft.util.TextProgressReceiver;
import org.pepsoft.worldpainter.exporting.WorldExportSettings;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.exporting.BlockPropertiesCalculator;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.exporters.ResourcesExporter;
import org.pepsoft.worldpainter.layers.exporters.FrostExporter;
import org.pepsoft.worldpainter.layers.plants.Plants;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.platforms.NativeFluidFlow;
import org.pepsoft.worldpainter.plugins.PlatformManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
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
import java.util.Random;
import java.lang.reflect.Field;
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
 * order, per-stage CPU timings, sampled peak JVM heap and process RSS, JFR, and decompressed
 * Anvil chunk parity.
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
    public void profileChunkCaptureOnFullExport() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.export.captureOnlyCampaign"));
        final String oldHome = System.getProperty("user.home");
        final String oldThreads = System.getProperty("org.pepsoft.worldpainter.threads");
        final String oldCaptureProfile = System.getProperty("welt.export.profileChunkCapture");
        final String oldPaletteIndexView = System.getProperty("welt.packedArrayCube.compactPaletteStorage");
        final String oldExport = System.getProperty(Native.EXPORT_KEY);
        final Path root = Files.createTempDirectory("welt-chunk-capture-campaign-");
        try {
            final boolean paletteIndexCampaign = Boolean.getBoolean("welt.export.paletteIndexCaptureOnlyCampaign");
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("org.pepsoft.worldpainter.threads",
                    paletteIndexCampaign ? "1" : "4");
            Native.setExportEnabled(false);
            Files.createDirectories(root.resolve("home"));
            initialisePlatform();
            final File fixture = new File("../WPGUI/src/test/resources/Generated World.world").getCanonicalFile();
            final World2 world;
            try (FileInputStream input = new FileInputStream(fixture)) {
                final WorldIO worldIO = new WorldIO();
                worldIO.load(input);
                world = worldIO.getWorld();
            }
            if (paletteIndexCampaign) {
                // Use the first palette-based writer and a single worker. The
                // static Plants RNG otherwise changes kelp ages by schedule.
                world.setPlatform(DefaultPlugin.JAVA_ANVIL_1_15);
            }
            for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
                Terrain.setCustomMaterial(i, world.getMixedMaterial(i));
            }

            if (!paletteIndexCampaign) {
                for (int warmup = 0; warmup < 2; warmup++) {
                for (boolean capture : ((warmup & 1) == 0)
                        ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    runCaptureExport(world, root, "warmup-" + warmup, capture);
                }
            }
            final List<Long> baseline = new ArrayList<>(), captured = new ArrayList<>();
            Path lastBaseline = null, lastCaptured = null;
            long preparationNanos = 0, chunks = 0, cells = 0, bytes = 0, fallbacks = 0;
            for (int round = 0; round < 5; round++) {
                for (boolean capture : ((round & 1) == 0)
                        ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    final CaptureRun run = runCaptureExport(world, root, "round-" + round, capture);
                    (capture ? captured : baseline).add(run.result.wallNanos);
                    if (capture) {
                        lastCaptured = run.result.output;
                        preparationNanos += run.profile.nanos;
                        chunks += run.profile.chunks;
                        cells += run.profile.cells;
                        bytes += run.profile.encodedBytes;
                        fallbacks += run.profile.fallbacks;
                    } else {
                        lastBaseline = run.result.output;
                    }
                    System.out.printf("Chunk capture %-8s round %d: %.3f s mural%n",
                            capture ? "actif" : "inactif", round,
                            run.result.wallNanos / 1_000_000_000.0);
                }
            }
            assertRegionsEqual("Java/capture", readRegions(lastBaseline), readRegions(lastCaptured));
            final long baselineMedian = median(baseline), capturedMedian = median(captured);
            System.out.printf("Capture ABI sur export complet: Java %.3f s, capture %.3f s, "
                            + "surcoût %.1f%%; préparation worker-cumulée %.3f s/run, "
                            + "%d chunks, %d cellules, %d octets, %d replis; parité NBT vérifiée. Sorties: %s%n",
                    baselineMedian / 1_000_000_000.0, capturedMedian / 1_000_000_000.0,
                    100.0 * ((double) capturedMedian / baselineMedian - 1.0),
                    preparationNanos / 5_000_000_000.0, chunks, cells, bytes, fallbacks, root);
            }

            final List<Long> legacyCapture = new ArrayList<>(), indexedCapture = new ArrayList<>();
            final List<Long> legacyHeapStarts = new ArrayList<>(), indexedHeapStarts = new ArrayList<>();
            final List<Long> legacyHeapPeaks = new ArrayList<>(), indexedHeapPeaks = new ArrayList<>();
            final List<Long> legacyHeapGrowth = new ArrayList<>(), indexedHeapGrowth = new ArrayList<>();
            final List<Long> legacyRssStarts = new ArrayList<>(), indexedRssStarts = new ArrayList<>();
            final List<Long> legacyRssPeaks = new ArrayList<>(), indexedRssPeaks = new ArrayList<>();
            final List<Long> legacyRssGrowth = new ArrayList<>(), indexedRssGrowth = new ArrayList<>();
            Path legacyOutput = null, indexedOutput = null;
            long legacyPreparation = 0, indexedPreparation = 0;
            for (int warmup = 0; warmup < 2; warmup++) {
                for (boolean indexed : ((warmup & 1) == 0)
                        ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    runCaptureExport(world, root, "palette-warmup-" + warmup
                            + (indexed ? "-indexed" : "-plain"), true, indexed);
                }
            }
            for (int round = 0; round < 5; round++) {
                for (boolean indexed : ((round & 1) == 0)
                        ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    final CaptureRun run = runCaptureExport(world, root,
                            "palette-round-" + round + (indexed ? "-indexed" : "-plain"),
                            true, indexed);
                    (indexed ? indexedCapture : legacyCapture).add(run.result.wallNanos);
                    (indexed ? indexedHeapStarts : legacyHeapStarts).add(run.result.heapBeforeBytes);
                    (indexed ? indexedHeapPeaks : legacyHeapPeaks).add(run.result.peakHeapBytes);
                    (indexed ? indexedHeapGrowth : legacyHeapGrowth).add(run.result.peakHeapGrowthBytes());
                    (indexed ? indexedRssStarts : legacyRssStarts).add(run.result.rssBeforeBytes);
                    (indexed ? indexedRssPeaks : legacyRssPeaks).add(run.result.peakRssBytes);
                    (indexed ? indexedRssGrowth : legacyRssGrowth).add(run.result.peakRssGrowthBytes());
                    if (indexed) {
                        indexedOutput = run.result.output;
                        indexedPreparation += run.profile.nanos;
                    } else {
                        legacyOutput = run.result.output;
                        legacyPreparation += run.profile.nanos;
                    }
                }
            }
            assertRegionsEqual("capture/compact-palette-storage",
                    readRegions(legacyOutput), readRegions(indexedOutput));
            final long legacyCaptureMedian = median(legacyCapture);
            final long indexedCaptureMedian = median(indexedCapture);
            System.out.printf("Stockage palette compact A/B export complet: sans compact %.3f s, compact %.3f s, "
                            + "ratio %.3fx; heap départ/pic/hausse %.1f/%.1f/%.1f vs %.1f/%.1f/%.1f MiB; "
                            + "RSS départ/pic/hausse %s/%s/%s vs %s/%s/%s; "
                            + "préparation %.3f / %.3f s cumulées; parité NBT sur %d chunks.%n",
                    legacyCaptureMedian / 1_000_000_000.0, indexedCaptureMedian / 1_000_000_000.0,
                    (double) legacyCaptureMedian / indexedCaptureMedian,
                    median(legacyHeapStarts) / 1048576.0, median(legacyHeapPeaks) / 1048576.0,
                    median(legacyHeapGrowth) / 1048576.0, median(indexedHeapStarts) / 1048576.0,
                    median(indexedHeapPeaks) / 1048576.0, median(indexedHeapGrowth) / 1048576.0,
                    formatMiB(medianAvailable(legacyRssStarts)), formatMiB(medianAvailable(legacyRssPeaks)),
                    formatMiB(medianAvailable(legacyRssGrowth)),
                    formatMiB(medianAvailable(indexedRssStarts)), formatMiB(medianAvailable(indexedRssPeaks)),
                    formatMiB(medianAvailable(indexedRssGrowth)),
                    legacyPreparation / 5_000_000_000.0, indexedPreparation / 5_000_000_000.0,
                    countChunks(readRegions(indexedOutput)));
        } finally {
            restore("user.home", oldHome);
            restore("org.pepsoft.worldpainter.threads", oldThreads);
            restore("welt.export.profileChunkCapture", oldCaptureProfile);
            restore("welt.packedArrayCube.compactPaletteStorage", oldPaletteIndexView);
            restore(Native.EXPORT_KEY, oldExport);
        }
    }

    @Test
    public void compareAlternatingFullWorldExports() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.export.campaign"));
        assumeTrue("welt_slices must be available for native campaigns", NativeLoader.areSlicesAvailable());
        final boolean modernChunkCampaign = Boolean.getBoolean("welt.export.modernChunkCampaign");
        final int workerCount = Integer.getInteger("welt.export.workerCount", modernChunkCampaign ? 1 : 4);
        if (workerCount < 1) {
            throw new IllegalArgumentException("Export worker count must be positive");
        }
        final String oldHome = System.getProperty("user.home");
        final String oldThreads = System.getProperty("org.pepsoft.worldpainter.threads");
        final String oldExport = System.getProperty(Native.EXPORT_KEY);
        final String oldFrost = System.getProperty(Native.FROST_EXPORT_KEY);
        final String oldResources = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        final String oldFluidFlow = System.getProperty(Native.FLUID_FLOW_EXPORT_KEY);
        final String oldFluidFlowProfile = System.getProperty("welt.export.profileFluidFlow");
        final String oldBlockPropertiesFrontier = System.getProperty("welt.export.blockPropertiesFrontier");
        final String oldBlockPropertiesFrontierProfile = System.getProperty("welt.export.profileBlockPropertiesFrontier");
        final String oldCaptureProfile = System.getProperty("welt.export.profileChunkCapture");
        final String oldDisableInitialHeightCache = System.getProperty("welt.export.disableInitialHeightCache");
        final Path root = Files.createTempDirectory("welt-full-export-campaign-");
        try {
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("org.pepsoft.worldpainter.threads", Integer.toString(workerCount));
            Files.createDirectories(root.resolve("home"));
            initialisePlatform();
            final File fixture = new File("../WPGUI/src/test/resources/Generated World.world").getCanonicalFile();
            final World2 world;
            try (FileInputStream input = new FileInputStream(fixture)) {
                final WorldIO worldIO = new WorldIO();
                worldIO.load(input);
                world = worldIO.getWorld();
            }
            final Mode[] modes = modernChunkCampaign
                    ? new Mode[]{MODES[0], MODES[1],
                            new Mode("fluid-flow", true, false, false, true),
                            new Mode("resources", true, false, true),
                            new Mode("resources-fluid", true, false, true, true),
                            MODES[2]}
                    : MODES;
            if (modernChunkCampaign) {
                world.setPlatform(DefaultPlugin.JAVA_ANVIL_1_15);
                // The saved fixture has no Frost bits, which would make the
                // Frost benchmark a no-op. Give every existing tile active
                // Frost columns so Java and native paths exercise real work.
                for (Tile tile : world.getDimension(DIM_NORMAL).getTiles()) {
                    tile.setBitLayerValue(Frost.INSTANCE);
                }
            }
            for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
                Terrain.setCustomMaterial(i, world.getMixedMaterial(i));
            }

            if (Boolean.getBoolean("welt.export.paletteIndexCacheOnlyCampaign")) {
                assumeTrue("Palette-index cache campaign requires the modern chunk fixture", modernChunkCampaign);
                runPaletteIndexCacheCampaign(world, root, modes[3], workerCount);
                return;
            }

            if (Boolean.getBoolean("welt.export.compactPaletteStorageCampaign")) {
                assumeTrue("Compact palette storage campaign requires the modern chunk fixture", modernChunkCampaign);
                runCompactPaletteStorageCampaign(world, root, modes[0], workerCount);
                return;
            }

            if (Boolean.getBoolean("welt.export.frostResourcesOnlyCampaign")) {
                assumeTrue("Frost + Resources campaign requires the modern chunk fixture", modernChunkCampaign);
                runNativeModeCampaign(world, root, modes[0], MODES[4], workerCount, "Frost + Resources");
                return;
            }

            if (Boolean.getBoolean("welt.export.frostOnlyCampaign")) {
                assumeTrue("Frost-only campaign requires the modern chunk fixture", modernChunkCampaign);
                runNativeModeCampaign(world, root, modes[0], MODES[2], workerCount, "Frost");
                return;
            }

            if (Boolean.getBoolean("welt.export.javaRepeatOnly")) {
                final Mode javaMode = modes[0];
                runExport(world, root, javaMode, "java-repeat-warmup-0", false);
                runExport(world, root, javaMode, "java-repeat-warmup-1", false);
                final RunResult first = runExport(world, root, javaMode, "java-repeat-a", false);
                final RunResult second = runExport(world, root, javaMode, "java-repeat-b", false);
                assertRegionsEqual("java-repeat", readRegions(first.output), readRegions(second.output));
                System.out.printf("Java repeat export: %.3f s then %.3f s; deterministic NBT parity on %d chunks.%n",
                        first.wallNanos / 1_000_000_000.0, second.wallNanos / 1_000_000_000.0,
                        countChunks(readRegions(second.output)));
                return;
            }

            if (Boolean.getBoolean("welt.export.allocationProfileOnly")) {
                final Mode javaMode = new Mode("java", false, false, false);
                final Mode resourcesMode = new Mode("resources-native", true, false, true);
                for (int warmup = 0; warmup < 2; warmup++) {
                    if ((warmup & 1) == 0) {
                        runExport(world, root, javaMode, "allocation-warmup-java-" + warmup, false);
                        runExport(world, root, resourcesMode, "allocation-warmup-native-" + warmup, false);
                    } else {
                        runExport(world, root, resourcesMode, "allocation-warmup-native-" + warmup, false);
                        runExport(world, root, javaMode, "allocation-warmup-java-" + warmup, false);
                    }
                }
                final RunResult javaProfile = runExport(world, root, javaMode, "allocation-profile-java", true);
                printTimings("allocation-java", -1, javaProfile);
                final Path nativeRecordingFile = root.resolve("resources-native-allocations.jfr");
                final RunResult nativeProfile;
                try (Recording recording = new Recording()) {
                    recording.setName("Welt full export native Resources allocations");
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                    recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
                    recording.enable("jdk.ObjectAllocationSample");
                    recording.start();
                    try {
                        nativeProfile = runExport(world, root, resourcesMode,
                                "allocation-profile-native", false);
                    } finally {
                        recording.stop();
                        recording.dump(nativeRecordingFile);
                    }
                }
                printTimings("allocation-native", -1, nativeProfile);
                assertRegionsEqual("allocation-profile-java-native",
                        readRegions(javaProfile.output), readRegions(nativeProfile.output));
                System.out.println("Java/native allocation JFRs captured after warmup; NBT parity on "
                        + countChunks(readRegions(nativeProfile.output)) + " chunks.");
                return;
            }

            if (Boolean.getBoolean("welt.export.resourcesProfileOnly")) {
                final List<Long> javaNanos = new ArrayList<>(), nativeNanos = new ArrayList<>();
                final List<Long> javaHeapPeaks = new ArrayList<>(), nativeHeapPeaks = new ArrayList<>();
                final List<Long> javaHeapGrowth = new ArrayList<>(), nativeHeapGrowth = new ArrayList<>();
                final List<Long> javaRssPeaks = new ArrayList<>(), nativeRssPeaks = new ArrayList<>();
                final List<Long> javaRssGrowth = new ArrayList<>(), nativeRssGrowth = new ArrayList<>();
                Path javaOutput = null, nativeOutput = null;
                for (int warmup = 0; warmup < 2; warmup++) {
                    final boolean reverse = (warmup & 1) != 0;
                    for (int position = 0; position < 2; position++) {
                        final boolean useNative = (position == 1) != reverse;
                        runExport(world, root, useNative ? modes[3] : modes[0],
                                "resource-ab-warmup-" + warmup + "-" + useNative, false);
                    }
                }
                for (int round = 0; round < 5; round++) {
                    final boolean reverse = (round & 1) != 0;
                    for (int position = 0; position < 2; position++) {
                        final boolean useNative = (position == 1) != reverse;
                        final RunResult result = runExport(world, root,
                                useNative ? modes[3] : modes[0],
                                "resource-ab-round-" + round + "-" + useNative, false);
                        printTimings(useNative ? "resources-rust" : "resources-java", round, result);
                        (useNative ? nativeNanos : javaNanos).add(result.wallNanos);
                        (useNative ? nativeHeapPeaks : javaHeapPeaks).add(result.peakHeapBytes);
                        (useNative ? nativeHeapGrowth : javaHeapGrowth).add(result.peakHeapGrowthBytes());
                        (useNative ? nativeRssPeaks : javaRssPeaks).add(result.peakRssBytes);
                        (useNative ? nativeRssGrowth : javaRssGrowth).add(result.peakRssGrowthBytes());
                        if (round == 4) {
                            if (useNative) nativeOutput = result.output;
                            else javaOutput = result.output;
                        }
                    }
                }
                AssertionError parityFailure = null;
                try {
                    assertRegionsEqual("resource-ab/java", readRegions(javaOutput), readRegions(nativeOutput));
                } catch (AssertionError failure) {
                    parityFailure = failure;
                }
                final long javaMedian = median(javaNanos), nativeMedian = median(nativeNanos);
                System.out.printf("Resources full-export A/B (5 alternating, %d workers): Java %.3f s, "
                                + "Rust %.3f s, ratio %.3fx; runs Java %s, Rust %s; "
                                + "heap peak/growth %.1f/%.1f vs %.1f/%.1f MiB; "
                                + "RSS peak/growth %s/%s vs %s/%s; NBT %s on %d chunks.%n",
                        workerCount, javaMedian / 1_000_000_000.0, nativeMedian / 1_000_000_000.0,
                        (double) javaMedian / nativeMedian, seconds(javaNanos), seconds(nativeNanos),
                        median(javaHeapPeaks) / 1048576.0, median(javaHeapGrowth) / 1048576.0,
                        median(nativeHeapPeaks) / 1048576.0, median(nativeHeapGrowth) / 1048576.0,
                        formatMiB(medianAvailable(javaRssPeaks)),
                        formatMiB(medianAvailable(javaRssGrowth)),
                        formatMiB(medianAvailable(nativeRssPeaks)),
                        formatMiB(medianAvailable(nativeRssGrowth)),
                        (parityFailure == null) ? "parity" : "mismatch",
                        countChunks(readRegions(nativeOutput)));
                if (parityFailure != null) {
                    throw parityFailure;
                }

                final String previousResourceProfile = System.getProperty("welt.export.profileResourcesNative");
                final RunResult nativeProfileResult;
                final long[] nativeProfile;
                try {
                    System.setProperty("welt.export.profileResourcesNative", "true");
                    ResourcesExporter.resetNativeProfile();
                    nativeProfileResult = runExport(world, root, modes[3], "resource-profile-native", false);
                    nativeProfile = ResourcesExporter.nativeProfileSnapshot();
                } finally {
                    restore("welt.export.profileResourcesNative", previousResourceProfile);
                }
                assertRegionsEqual("resource-profile/java", readRegions(javaOutput),
                        readRegions(nativeProfileResult.output));
                printTimings("resource-profile-native", -1, nativeProfileResult);
                System.out.printf("Resources kernel detail over %d chunks: preparation %.3f s, "
                                + "JNI+copy+Rust %.3f s (input copy %.3f s, Rust total %.3f s, "
                                + "Rust setup %.3f s, material/Perlin scan %.3f s, %,d Perlin samples), "
                                + "Java apply %.3f s; in-place palette chunks %d "
                                + "(live views %d, view misses %d, native palette rejects %d)%n",
                        nativeProfile[3], nativeProfile[0] / 1_000_000_000.0,
                        nativeProfile[1] / 1_000_000_000.0,
                        nativeProfile[4] / 1_000_000_000.0,
                        nativeProfile[5] / 1_000_000_000.0,
                        nativeProfile[7] / 1_000_000_000.0,
                        nativeProfile[8] / 1_000_000_000.0,
                        nativeProfile[9], nativeProfile[2] / 1_000_000_000.0,
                        nativeProfile[6], nativeProfile[10], nativeProfile[11], nativeProfile[12]);
                return;
            }

            if (Boolean.getBoolean("welt.export.fluidFlowProfileOnly")) {
                assumeTrue("fluid-flow profiling requires a modern chunk campaign", modernChunkCampaign);
                final Mode javaMode = modes[0];
                final Mode fluidMode = modes[2];
                final List<Long> javaNanos = new ArrayList<>(), fluidNanos = new ArrayList<>();
                final List<Long> javaHeapPeaks = new ArrayList<>(), fluidHeapPeaks = new ArrayList<>();
                final List<Long> javaHeapGrowth = new ArrayList<>(), fluidHeapGrowth = new ArrayList<>();
                final List<Long> javaRssPeaks = new ArrayList<>(), fluidRssPeaks = new ArrayList<>();
                final List<Long> javaRssGrowth = new ArrayList<>(), fluidRssGrowth = new ArrayList<>();
                Path javaOutput = null, fluidOutput = null;
                for (int warmup = 0; warmup < 2; warmup++) {
                    final boolean reverse = (warmup & 1) != 0;
                    for (int position = 0; position < 2; position++) {
                        final boolean useFluid = (position == 1) != reverse;
                        runExport(world, root, useFluid ? fluidMode : javaMode,
                                "fluid-ab-warmup-" + warmup + "-" + useFluid, false);
                    }
                }
                for (int round = 0; round < 5; round++) {
                    final boolean reverse = (round & 1) != 0;
                    for (int position = 0; position < 2; position++) {
                        final boolean useFluid = (position == 1) != reverse;
                        final RunResult result = runExport(world, root,
                                useFluid ? fluidMode : javaMode,
                                "fluid-ab-round-" + round + "-" + useFluid, false);
                        (useFluid ? fluidNanos : javaNanos).add(result.wallNanos);
                        (useFluid ? fluidHeapPeaks : javaHeapPeaks).add(result.peakHeapBytes);
                        (useFluid ? fluidHeapGrowth : javaHeapGrowth).add(result.peakHeapGrowthBytes());
                        (useFluid ? fluidRssPeaks : javaRssPeaks).add(result.peakRssBytes);
                        (useFluid ? fluidRssGrowth : javaRssGrowth).add(result.peakRssGrowthBytes());
                        if (round == 4) {
                            if (useFluid) fluidOutput = result.output;
                            else javaOutput = result.output;
                        }
                    }
                }
                assertRegionsEqual("fluid-flow-ab/java", readRegions(javaOutput), readRegions(fluidOutput));
                final long javaMedian = median(javaNanos), fluidMedian = median(fluidNanos);
                System.out.printf("Fluid-flow full-export A/B (5 alternating, %d workers): Java %.3f s, "
                                + "Welt %.3f s, ratio %.3fx; heap peak/growth %.1f/%.1f vs %.1f/%.1f MiB; "
                                + "RSS peak/growth %s/%s vs %s/%s; runs Java %s, Welt %s; NBT parity on %d chunks.%n",
                        workerCount, javaMedian / 1_000_000_000.0, fluidMedian / 1_000_000_000.0,
                        (double) javaMedian / fluidMedian,
                        median(javaHeapPeaks) / 1048576.0, median(javaHeapGrowth) / 1048576.0,
                        median(fluidHeapPeaks) / 1048576.0, median(fluidHeapGrowth) / 1048576.0,
                        formatMiB(medianAvailable(javaRssPeaks)),
                        formatMiB(medianAvailable(javaRssGrowth)),
                        formatMiB(medianAvailable(fluidRssPeaks)),
                        formatMiB(medianAvailable(fluidRssGrowth)),
                        seconds(javaNanos), seconds(fluidNanos), countChunks(readRegions(fluidOutput)));
                final String previousFluidProfile = System.getProperty("welt.export.profileFluidFlow");
                try {
                    System.setProperty("welt.export.profileFluidFlow", "true");
                    NativeFluidFlow.resetProfile();
                    final RunResult profile = runExport(world, root, fluidMode, "fluid-profile", false);
                    printTimings("fluid-flow-profile", -1, profile);
                    final long[] stats = NativeFluidFlow.profileSnapshot();
                    System.out.printf("Fluid-flow details: %d native chunks (%d snapshots, %d live palettes), "
                                    + "%d Java fallbacks; preparation %.3f s, JNI+Rust %.3f s, "
                                    + "application %.3f s.%n",
                            stats[0], stats[5], stats[6], stats[1],
                            stats[2] / 1_000_000_000.0, stats[3] / 1_000_000_000.0,
                            stats[4] / 1_000_000_000.0);
                } finally {
                    restore("welt.export.profileFluidFlow", previousFluidProfile);
                    NativeFluidFlow.resetProfile();
                }
                return;
            }

            if (!modernChunkCampaign || Boolean.getBoolean("welt.export.includeFrontierCampaign")) {
            // Measure the exact changed-cell frontier against the existing
            // rectangular scan on complete exports, and compare decompressed
            // chunk NBT before including the candidate in the main campaign.
            final Mode javaOnly = new Mode("frontier", false, false, false);
            if (Boolean.getBoolean("welt.export.heightCacheOnly")) {
                final List<Long> cachedNanos = new ArrayList<>(), uncachedNanos = new ArrayList<>();
                final List<Long> cachedHeapPeaks = new ArrayList<>(), uncachedHeapPeaks = new ArrayList<>();
                final List<Long> cachedHeapGrowth = new ArrayList<>(), uncachedHeapGrowth = new ArrayList<>();
                final List<Long> cachedRssPeaks = new ArrayList<>(), uncachedRssPeaks = new ArrayList<>();
                final List<Long> cachedRssGrowth = new ArrayList<>(), uncachedRssGrowth = new ArrayList<>();
                Path cachedOutput = null, uncachedOutput = null;
                System.setProperty("welt.export.blockPropertiesFrontier", "false");
                for (int warmup = 0; warmup < 2; warmup++) {
                    final boolean reverse = (warmup & 1) != 0;
                    for (int position = 0; position < 2; position++) {
                        final boolean cached = (position == 1) != reverse;
                        System.setProperty("welt.export.disableInitialHeightCache", Boolean.toString(!cached));
                        runExport(world, root, javaOnly, "height-cache-warmup-" + warmup + "-" + cached, false);
                    }
                }
                for (int round = 0; round < 5; round++) {
                    final boolean reverse = (round & 1) != 0;
                    for (int position = 0; position < 2; position++) {
                        final boolean cached = (position == 1) != reverse;
                        System.setProperty("welt.export.disableInitialHeightCache", Boolean.toString(!cached));
                        final RunResult result = runExport(world, root, javaOnly,
                                "height-cache-round-" + round + "-" + cached, false);
                        (cached ? cachedNanos : uncachedNanos).add(result.wallNanos);
                        (cached ? cachedHeapPeaks : uncachedHeapPeaks).add(result.peakHeapBytes);
                        (cached ? cachedHeapGrowth : uncachedHeapGrowth).add(result.peakHeapGrowthBytes());
                        (cached ? cachedRssPeaks : uncachedRssPeaks).add(result.peakRssBytes);
                        (cached ? cachedRssGrowth : uncachedRssGrowth).add(result.peakRssGrowthBytes());
                        if (round == 4) {
                            if (cached) cachedOutput = result.output;
                            else uncachedOutput = result.output;
                        }
                    }
                }
                assertRegionsEqual("initial-height-cache", readRegions(uncachedOutput), readRegions(cachedOutput));
                final long cachedMedian = median(cachedNanos), uncachedMedian = median(uncachedNanos);
                System.out.printf("Cache de hauteur export complet: sans cache %.3f s, cache %.3f s, ratio %.3fx; "
                                + "heap pic/hausse %.1f/%.1f vs %.1f/%.1f MiB; RSS pic/hausse %s/%s vs %s/%s; "
                                + "essais sans cache %s, cache %s; parité NBT sur %d chunks.%n",
                        uncachedMedian / 1_000_000_000.0, cachedMedian / 1_000_000_000.0,
                        (double) uncachedMedian / cachedMedian,
                        median(uncachedHeapPeaks) / 1048576.0, median(uncachedHeapGrowth) / 1048576.0,
                        median(cachedHeapPeaks) / 1048576.0, median(cachedHeapGrowth) / 1048576.0,
                        formatMiB(medianAvailable(uncachedRssPeaks)),
                        formatMiB(medianAvailable(uncachedRssGrowth)),
                        formatMiB(medianAvailable(cachedRssPeaks)),
                        formatMiB(medianAvailable(cachedRssGrowth)),
                        seconds(uncachedNanos), seconds(cachedNanos), countChunks(readRegions(cachedOutput)));
                return;
            }
            final List<Long> rectangleNanos = new ArrayList<>(), frontierNanos = new ArrayList<>();
            final List<Long> rectangleHeapPeaks = new ArrayList<>(), frontierHeapPeaks = new ArrayList<>();
            final List<Long> rectangleHeapGrowth = new ArrayList<>(), frontierHeapGrowth = new ArrayList<>();
            final List<Long> rectangleRssPeaks = new ArrayList<>(), frontierRssPeaks = new ArrayList<>();
            final List<Long> rectangleRssGrowth = new ArrayList<>(), frontierRssGrowth = new ArrayList<>();
            Path rectangleOutput = null, frontierOutput = null;
            for (int warmup = 0; warmup < 2; warmup++) {
                final boolean reverse = (warmup & 1) != 0;
                for (int position = 0; position < 2; position++) {
                    final boolean frontier = (position == 1) != reverse;
                    System.setProperty("welt.export.blockPropertiesFrontier", Boolean.toString(frontier));
                    runExport(world, root, javaOnly,
                            "frontier-warmup-" + warmup + "-" + frontier, false);
                }
            }
            for (int round = 0; round < 5; round++) {
                final boolean reverse = (round & 1) != 0;
                for (int position = 0; position < 2; position++) {
                    final boolean frontier = (position == 1) != reverse;
                    System.setProperty("welt.export.blockPropertiesFrontier", Boolean.toString(frontier));
                    final RunResult result = runExport(world, root, javaOnly,
                            "frontier-round-" + round + "-" + frontier, false);
                    (frontier ? frontierNanos : rectangleNanos).add(result.wallNanos);
                    (frontier ? frontierHeapPeaks : rectangleHeapPeaks).add(result.peakHeapBytes);
                    (frontier ? frontierHeapGrowth : rectangleHeapGrowth).add(result.peakHeapGrowthBytes());
                    (frontier ? frontierRssPeaks : rectangleRssPeaks).add(result.peakRssBytes);
                    (frontier ? frontierRssGrowth : rectangleRssGrowth).add(result.peakRssGrowthBytes());
                    if (round == 4) {
                        if (frontier) frontierOutput = result.output;
                        else rectangleOutput = result.output;
                    }
                }
            }
            assertRegionsEqual("rectangle/changed-frontier",
                    readRegions(rectangleOutput), readRegions(frontierOutput));
            final long rectangleMedian = median(rectangleNanos), frontierMedian = median(frontierNanos);
            System.out.printf("Frontière de blocs export complet: rectangle %.3f s, frontière %.3f s, "
                            + "ratio %.3fx; heap pic/hausse %.1f/%.1f vs %.1f/%.1f MiB; "
                            + "RSS pic/hausse %s/%s vs %s/%s; mesures rectangle %s, frontière %s; "
                            + "parité NBT sur %d chunks.%n",
                    rectangleMedian / 1_000_000_000.0, frontierMedian / 1_000_000_000.0,
                    (double) rectangleMedian / frontierMedian,
                    median(rectangleHeapPeaks) / 1048576.0, median(rectangleHeapGrowth) / 1048576.0,
                    median(frontierHeapPeaks) / 1048576.0, median(frontierHeapGrowth) / 1048576.0,
                    formatMiB(medianAvailable(rectangleRssPeaks)),
                    formatMiB(medianAvailable(rectangleRssGrowth)),
                    formatMiB(medianAvailable(frontierRssPeaks)),
                    formatMiB(medianAvailable(frontierRssGrowth)),
                    seconds(rectangleNanos), seconds(frontierNanos), countChunks(readRegions(frontierOutput)));
            System.setProperty("welt.export.blockPropertiesFrontier", "true");
            System.setProperty("welt.export.profileBlockPropertiesFrontier", "true");
            for (boolean frontier : new boolean[]{false, true}) {
                System.setProperty("welt.export.blockPropertiesFrontier", Boolean.toString(frontier));
                BlockPropertiesCalculator.resetFrontierProfile();
                runExport(world, root, javaOnly, "frontier-profile-" + frontier, false);
                final long[] profile = BlockPropertiesCalculator.frontierProfileSnapshot();
                System.out.printf("Profil frontières %s: %d passes, %,d cellules rectangle, "
                                + "%,d évaluées (%.1f%%), %,d modifications%n",
                        frontier ? "actives" : "rectangle", profile[0], profile[1], profile[2],
                        profile[1] == 0 ? 0.0 : 100.0 * profile[2] / profile[1], profile[3]);
            }
            if (oldBlockPropertiesFrontierProfile == null) {
                System.clearProperty("welt.export.profileBlockPropertiesFrontier");
            } else {
                System.setProperty("welt.export.profileBlockPropertiesFrontier", oldBlockPropertiesFrontierProfile);
            }
            System.setProperty("welt.export.blockPropertiesFrontier", "true");
            } else {
                System.setProperty("welt.export.blockPropertiesFrontier",
                        Boolean.toString(Boolean.getBoolean("welt.export.enableFrontierInCampaign")));
            }
            if (Boolean.getBoolean("welt.export.frontierOnly")) {
                return;
            }

            final Map<String, List<Long>> measuredWallNanos = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredHeapPeaks = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredHeapGrowth = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredRssPeaks = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredRssGrowth = new LinkedHashMap<>();
            final Map<String, Path> parityOutputs = new LinkedHashMap<>();
            for (Mode mode : modes) {
                measuredWallNanos.put(mode.name, new ArrayList<>());
                measuredHeapPeaks.put(mode.name, new ArrayList<>());
                measuredHeapGrowth.put(mode.name, new ArrayList<>());
                measuredRssPeaks.put(mode.name, new ArrayList<>());
                measuredRssGrowth.put(mode.name, new ArrayList<>());
            }

            // Two complete exports per mode warm class loading, JIT code and
            // native library paths before any campaign timing is retained.
            for (int warmup = 0; warmup < 2; warmup++) {
                final Mode[] order = modes.clone();
                if ((warmup & 1) != 0) reverse(order);
                for (Mode mode : order) {
                    runExport(world, root, mode, "warmup-" + warmup, false);
                }
            }

            // Keep the profiled pass outside the timed campaign because JFR
            // sampling adds measurable overhead to a short whole-world export.
            final RunResult profile = runExport(world, root, MODES[0], "profile-java", true);
            printTimings("java-jfr", -1, profile);
            if (modernChunkCampaign) {
                final Path nativeRecordingFile = root.resolve("resources-fluid-native.jfr");
                try (Recording recording = new Recording()) {
                    recording.setName("Welt native resources and fluid export");
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                    recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(1_000));
                    recording.enable("jdk.ObjectAllocationSample");
                    recording.start();
                    try {
                        runExport(world, root,
                                new Mode("profile-resources-fluid-native", true, false, true, true),
                                "profile-resources-fluid-native", false);
                    } finally {
                        recording.stop();
                        recording.dump(nativeRecordingFile);
                    }
                }
                System.out.println("Native resources/fluid JFR recording retained at " + nativeRecordingFile);
            }

            // Split the native Resources path into Java input preparation,
            // the complete JNI call (including JNI copies and Rust compute),
            // and Java result application. This diagnostic pass is excluded
            // from all timed medians below.
            final String previousResourceProfile = System.getProperty("welt.export.profileResourcesNative");
            try {
                System.setProperty("welt.export.profileResourcesNative", "true");
                ResourcesExporter.resetNativeProfile();
                final RunResult resourceProfile = runExport(world, root,
                        new Mode("resources-profile", true, false, true),
                        "profile-resources", false);
                printTimings("resources-profile", -1, resourceProfile);
                final long[] nativeProfile = ResourcesExporter.nativeProfileSnapshot();
                System.out.printf("Resources native split over %d chunks: Java preparation %.3f s, "
                                + "JNI+copy+Rust %.3f s (input copies %.3f s, Rust kernel %.3f s; "
                                + "Rust setup %.3f s, material/Perlin scan %.3f s, %,d Perlin samples), "
                                + "application %.3f s; in-place palette chunks %d "
                                + "(live views %d, view misses %d, native palette rejects %d)%n",
                        nativeProfile[3], nativeProfile[0] / 1_000_000_000.0,
                        nativeProfile[1] / 1_000_000_000.0,
                        nativeProfile[4] / 1_000_000_000.0,
                        nativeProfile[5] / 1_000_000_000.0,
                        nativeProfile[7] / 1_000_000_000.0,
                        nativeProfile[8] / 1_000_000_000.0,
                        nativeProfile[9],
                        nativeProfile[2] / 1_000_000_000.0,
                        nativeProfile[6], nativeProfile[10], nativeProfile[11], nativeProfile[12]);
                if (modernChunkCampaign) {
                    org.junit.Assert.assertTrue("modern campaign must exercise in-place palette updates",
                            nativeProfile[6] > 0);
                }
            } finally {
                if (previousResourceProfile == null) {
                    System.clearProperty("welt.export.profileResourcesNative");
                } else {
                    System.setProperty("welt.export.profileResourcesNative", previousResourceProfile);
                }
            }

            if (modernChunkCampaign) {
                final String previousFluidProfile = System.getProperty("welt.export.profileFluidFlow");
                try {
                    System.setProperty("welt.export.profileFluidFlow", "true");
                    NativeFluidFlow.resetProfile();
                    final RunResult fluidProfile = runExport(world, root,
                            new Mode("fluid-flow-profile", true, false, false, true),
                            "profile-fluid-flow", false);
                    printTimings("fluid-flow-profile", -1, fluidProfile);
                    final long[] fluidProfileStats = NativeFluidFlow.profileSnapshot();
                    System.out.printf("Passe fluide native: %d chunks natifs (%d snapshots, %d palettes live), "
                                    + "%d replis Java; "
                                    + "préparation %.3f s, JNI+Rust %.3f s, application %.3f s%n",
                            fluidProfileStats[0], fluidProfileStats[5], fluidProfileStats[6],
                            fluidProfileStats[1], fluidProfileStats[2] / 1_000_000_000.0,
                            fluidProfileStats[3] / 1_000_000_000.0, fluidProfileStats[4] / 1_000_000_000.0);
                    org.junit.Assert.assertTrue("modern campaign must exercise native fluid chunks",
                            fluidProfileStats[0] > 0);

                    NativeFluidFlow.resetProfile();
                    final RunResult combinedProfile = runExport(world, root,
                            new Mode("resources-fluid-profile", true, false, true, true),
                            "profile-resources-fluid", false);
                    printTimings("resources-fluid-profile", -1, combinedProfile);
                    final long[] combinedFluidStats = NativeFluidFlow.profileSnapshot();
                    System.out.printf("Passe fluide combinée: %d chunks natifs (%d snapshots, %d palettes live), "
                                    + "%d replis Java; préparation %.3f s, JNI+Rust %.3f s, application %.3f s%n",
                            combinedFluidStats[0], combinedFluidStats[5], combinedFluidStats[6],
                            combinedFluidStats[1], combinedFluidStats[2] / 1_000_000_000.0,
                            combinedFluidStats[3] / 1_000_000_000.0,
                            combinedFluidStats[4] / 1_000_000_000.0);
                    org.junit.Assert.assertTrue("combined resources/fluid mode must exercise native chunks",
                            combinedFluidStats[0] > 0);
                } finally {
                    if (previousFluidProfile == null) {
                        System.clearProperty("welt.export.profileFluidFlow");
                    } else {
                        System.setProperty("welt.export.profileFluidFlow", previousFluidProfile);
                    }
                    NativeFluidFlow.resetProfile();
                }
            }

            if (Boolean.getBoolean("welt.export.profileOnly")) {
                return;
            }

            // Reverse the order on alternating rounds to limit thermal and cache bias.
            for (int round = 0; round < 5; round++) {
                final Mode[] order = modes.clone();
                if ((round & 1) != 0) reverse(order);
                for (Mode mode : order) {
                    final RunResult result = runExport(world, root, mode,
                            "round-" + round, false);
                    measuredWallNanos.get(mode.name).add(result.wallNanos);
                    measuredHeapPeaks.get(mode.name).add(result.peakHeapBytes);
                    measuredHeapGrowth.get(mode.name).add(result.peakHeapGrowthBytes());
                    measuredRssPeaks.get(mode.name).add(result.peakRssBytes);
                    measuredRssGrowth.get(mode.name).add(result.peakRssGrowthBytes());
                    if (round == 4) parityOutputs.put(mode.name, result.output);
                    printTimings(mode.name, round, result);
                }
            }

            final String comparisonBase = modes[0].name;
            final Map<String, Map<Integer, byte[]>> baselineChunks =
                    readRegions(parityOutputs.get(comparisonBase));
            for (Mode mode : modes) {
                final Map<String, Map<Integer, byte[]>> candidate = readRegions(parityOutputs.get(mode.name));
                assertRegionsEqual(comparisonBase + "/" + mode.name, baselineChunks, candidate);
                System.out.println("Parity " + comparisonBase + "/" + mode.name + ": " + countChunks(candidate)
                        + " chunks NBT décompressés identiques.");
            }

            if (Boolean.getBoolean("welt.export.captureCampaign")) {
                final List<Long> baselineNanos = new ArrayList<>();
                final List<Long> capturedNanos = new ArrayList<>();
                Path lastBaseline = null, lastCaptured = null;
                for (int warmup = 0; warmup < 2; warmup++) {
                    for (boolean capture : ((warmup & 1) == 0)
                            ? new boolean[]{false, true} : new boolean[]{true, false}) {
                        runCaptureExport(world, root, "capture-warmup-" + warmup, capture);
                    }
                }
                long profiledCaptureNanos = 0, profiledChunks = 0, profiledCells = 0;
                long profiledBytes = 0, profiledFallbacks = 0;
                for (int round = 0; round < 5; round++) {
                    for (boolean capture : ((round & 1) == 0)
                            ? new boolean[]{false, true} : new boolean[]{true, false}) {
                        final CaptureRun run = runCaptureExport(world, root,
                                "capture-round-" + round, capture);
                        (capture ? capturedNanos : baselineNanos).add(run.result.wallNanos);
                        if (capture) {
                            lastCaptured = run.result.output;
                            profiledCaptureNanos += run.profile.nanos;
                            profiledChunks += run.profile.chunks;
                            profiledCells += run.profile.cells;
                            profiledBytes += run.profile.encodedBytes;
                            profiledFallbacks += run.profile.fallbacks;
                        } else {
                            lastBaseline = run.result.output;
                        }
                        System.out.printf("Capture profiling %-8s round %d: %.3f s mural%s%n",
                                capture ? "actif" : "inactif", round,
                                run.result.wallNanos / 1_000_000_000.0,
                                capture ? String.format("; préparation %.3f s, %d chunks, %d cellules, %d octets, %d replis Java",
                                        run.profile.nanos / 1_000_000_000.0, run.profile.chunks,
                                        run.profile.cells, run.profile.encodedBytes, run.profile.fallbacks) : "");
                    }
                }
                assertRegionsEqual("Java/capture", readRegions(lastBaseline), readRegions(lastCaptured));
                final long baseMedian = median(baselineNanos), captureMedian = median(capturedNanos);
                System.out.printf("Capture buffer sur export complet: médiane Java %.3f s, capture %.3f s, "
                                + "surcoût %.1f%%; préparation interne moyenne %.3f s sur 5 runs "
                                + "(%d chunks, %d cellules, %d octets, %d replis). Parité NBT vérifiée.%n",
                        baseMedian / 1_000_000_000.0, captureMedian / 1_000_000_000.0,
                        100.0 * ((double) captureMedian / baseMedian - 1.0),
                        profiledCaptureNanos / 5_000_000_000.0, profiledChunks, profiledCells,
                        profiledBytes, profiledFallbacks);
            }

            final long javaMedian = median(measuredWallNanos.get(comparisonBase));
            System.out.printf("Résultats exports complets (5 mesures, %d worker(s) fixes) :%n", workerCount);
            for (Mode mode : modes) {
                final long modeMedian = median(measuredWallNanos.get(mode.name));
                System.out.printf("  %-16s médiane %.3f s; ratio %s/Welt %.3fx; "
                                + "heap pic/hausse %.1f/%.1f MiB; RSS pic/hausse %s/%s; essais %s%n",
                        mode.name, modeMedian / 1_000_000_000.0,
                        comparisonBase, (double) javaMedian / modeMedian,
                        median(measuredHeapPeaks.get(mode.name)) / 1048576.0,
                        median(measuredHeapGrowth.get(mode.name)) / 1048576.0,
                        formatMiB(medianAvailable(measuredRssPeaks.get(mode.name))),
                        formatMiB(medianAvailable(measuredRssGrowth.get(mode.name))),
                        seconds(measuredWallNanos.get(mode.name)));
            }
            System.out.println("Fichiers, sorties et éventuel JFR conservés sous " + root);
        } finally {
            restore("user.home", oldHome);
            restore("org.pepsoft.worldpainter.threads", oldThreads);
            restore(Native.EXPORT_KEY, oldExport);
            restore(Native.FROST_EXPORT_KEY, oldFrost);
            restore(Native.RESOURCES_EXPORT_KEY, oldResources);
            restore(Native.FLUID_FLOW_EXPORT_KEY, oldFluidFlow);
            restore("welt.export.profileFluidFlow", oldFluidFlowProfile);
            restore("welt.export.blockPropertiesFrontier", oldBlockPropertiesFrontier);
            restore("welt.export.profileBlockPropertiesFrontier", oldBlockPropertiesFrontierProfile);
            restore("welt.export.profileChunkCapture", oldCaptureProfile);
            restore("welt.export.disableInitialHeightCache", oldDisableInitialHeightCache);
        }
    }

    private static CaptureRun runCaptureExport(World2 world, Path root, String round, boolean capture)
            throws Exception {
        return runCaptureExport(world, root, round, capture, false);
    }

    private static void runPaletteIndexCacheCampaign(World2 world, Path root, Mode resourcesMode,
                                                     int workerCount) throws Exception {
        final String property = "welt.packedArrayCube.reuseLastPaletteIndex";
        final String previous = System.getProperty(property);
        final List<Long> lookupNanos = new ArrayList<>(), cachedNanos = new ArrayList<>();
        final List<Long> lookupHeapPeaks = new ArrayList<>(), cachedHeapPeaks = new ArrayList<>();
        final List<Long> lookupHeapGrowth = new ArrayList<>(), cachedHeapGrowth = new ArrayList<>();
        final List<Long> lookupRssPeaks = new ArrayList<>(), cachedRssPeaks = new ArrayList<>();
        final List<Long> lookupRssGrowth = new ArrayList<>(), cachedRssGrowth = new ArrayList<>();
        final List<Long> pairedRatios = new ArrayList<>();
        final List<Long> lookupTerrainNanos = new ArrayList<>(), cachedTerrainNanos = new ArrayList<>();
        final List<Long> pairedTerrainRatios = new ArrayList<>();
        Path lookupOutput = null, cachedOutput = null;
        try {
            for (int warmup = 0; warmup < 2; warmup++) {
                final boolean cacheFirst = (warmup & 1) != 0;
                for (int position = 0; position < 2; position++) {
                    final boolean cached = (position == 0) == cacheFirst;
                    System.setProperty(property, Boolean.toString(cached));
                    runExport(world, root, resourcesMode,
                            "palette-index-cache-warmup-" + warmup + (cached ? "-cached" : "-lookup"), false);
                }
            }

            for (int round = 0; round < 5; round++) {
                final boolean cacheFirst = (round & 1) != 0;
                RunResult lookup = null, cached = null;
                for (int position = 0; position < 2; position++) {
                    final boolean useCache = (position == 0) == cacheFirst;
                    System.setProperty(property, Boolean.toString(useCache));
                    final RunResult result = runExport(world, root, resourcesMode,
                            "palette-index-cache-round-" + round + (useCache ? "-cached" : "-lookup"), false);
                    printTimings(useCache ? "palette-cache" : "palette-lookup", round, result);
                    if (useCache) cached = result;
                    else lookup = result;
                }
                lookupNanos.add(lookup.wallNanos);
                cachedNanos.add(cached.wallNanos);
                lookupHeapPeaks.add(lookup.peakHeapBytes);
                cachedHeapPeaks.add(cached.peakHeapBytes);
                lookupHeapGrowth.add(lookup.peakHeapGrowthBytes());
                cachedHeapGrowth.add(cached.peakHeapGrowthBytes());
                lookupRssPeaks.add(lookup.peakRssBytes);
                cachedRssPeaks.add(cached.peakRssBytes);
                lookupRssGrowth.add(lookup.peakRssGrowthBytes());
                cachedRssGrowth.add(cached.peakRssGrowthBytes());
                pairedRatios.add(Math.round(lookup.wallNanos * 1_000_000.0 / cached.wallNanos));
                final long lookupTerrain = stageNanos(lookup, "TERRAIN_GENERATION");
                final long cachedTerrain = stageNanos(cached, "TERRAIN_GENERATION");
                lookupTerrainNanos.add(lookupTerrain);
                cachedTerrainNanos.add(cachedTerrain);
                pairedTerrainRatios.add(Math.round(lookupTerrain * 1_000_000.0 / cachedTerrain));
                if (round == 4) {
                    lookupOutput = lookup.output;
                    cachedOutput = cached.output;
                }
            }

            final Map<String, Map<Integer, byte[]>> lookupChunks = readRegions(lookupOutput);
            final Map<String, Map<Integer, byte[]>> cachedChunks = readRegions(cachedOutput);
            assertRegionsEqual("palette-index-last-value-cache", lookupChunks, cachedChunks);
            System.out.printf("Palette-index last-value cache full Resources export A/B (%d alternating, %d workers): "
                            + "identity-map lookup %.3f s, cached %.3f s, paired full-export ratio %.3fx; "
                            + "terrain median %.3f vs %.3f s, paired ratio %.3fx; "
                            + "heap peak/growth %.1f/%.1f vs %.1f/%.1f MiB; RSS peak/growth %s/%s vs %s/%s; "
                            + "runs lookup %s, cached %s; exact NBT parity on %d chunks.%n",
                    lookupNanos.size(), workerCount,
                    median(lookupNanos) / 1_000_000_000.0, median(cachedNanos) / 1_000_000_000.0,
                    median(pairedRatios) / 1_000_000.0,
                    median(lookupTerrainNanos) / 1_000_000_000.0,
                    median(cachedTerrainNanos) / 1_000_000_000.0,
                    median(pairedTerrainRatios) / 1_000_000.0,
                    median(lookupHeapPeaks) / 1048576.0, median(lookupHeapGrowth) / 1048576.0,
                    median(cachedHeapPeaks) / 1048576.0, median(cachedHeapGrowth) / 1048576.0,
                    formatMiB(medianAvailable(lookupRssPeaks)), formatMiB(medianAvailable(lookupRssGrowth)),
                    formatMiB(medianAvailable(cachedRssPeaks)), formatMiB(medianAvailable(cachedRssGrowth)),
                    seconds(lookupNanos), seconds(cachedNanos), countChunks(cachedChunks));
        } finally {
            restore(property, previous);
        }
    }

    private static void runCompactPaletteStorageCampaign(World2 world, Path root,
                                                         Mode javaMode, int workerCount)
            throws Exception {
        final String property = "welt.packedArrayCube.compactPaletteStorage";
        final String previous = System.getProperty(property);
        final List<Long> plainNanos = new ArrayList<>(), compactNanos = new ArrayList<>();
        final List<Long> plainHeapPeaks = new ArrayList<>(), compactHeapPeaks = new ArrayList<>();
        final List<Long> plainHeapGrowth = new ArrayList<>(), compactHeapGrowth = new ArrayList<>();
        final List<Long> plainRssPeaks = new ArrayList<>(), compactRssPeaks = new ArrayList<>();
        final List<Long> plainRssGrowth = new ArrayList<>(), compactRssGrowth = new ArrayList<>();
        final List<Long> pairedRatios = new ArrayList<>();
        Path plainOutput = null, compactOutput = null;
        try {
            for (int warmup = 0; warmup < 2; warmup++) {
                final boolean reverse = (warmup & 1) != 0;
                for (int position = 0; position < 2; position++) {
                    final boolean compact = (position == 1) != reverse;
                    System.setProperty(property, Boolean.toString(compact));
                    runExport(world, root, javaMode,
                            "compact-palette-warmup-" + warmup + (compact ? "-compact" : "-plain"), false);
                }
            }
            for (int round = 0; round < 5; round++) {
                final boolean reverse = (round & 1) != 0;
                RunResult plain = null, compact = null;
                for (int position = 0; position < 2; position++) {
                    final boolean useCompact = (position == 1) != reverse;
                    System.setProperty(property, Boolean.toString(useCompact));
                    final RunResult result = runExport(world, root, javaMode,
                            "compact-palette-round-" + round + (useCompact ? "-compact" : "-plain"), false);
                    printTimings(useCompact ? "java-compact-palette" : "java-object-palette", round, result);
                    (useCompact ? compactNanos : plainNanos).add(result.wallNanos);
                    (useCompact ? compactHeapPeaks : plainHeapPeaks).add(result.peakHeapBytes);
                    (useCompact ? compactHeapGrowth : plainHeapGrowth).add(result.peakHeapGrowthBytes());
                    (useCompact ? compactRssPeaks : plainRssPeaks).add(result.peakRssBytes);
                    (useCompact ? compactRssGrowth : plainRssGrowth).add(result.peakRssGrowthBytes());
                    if (useCompact) compact = result;
                    else plain = result;
                }
                pairedRatios.add(Math.round(plain.wallNanos * 1_000_000.0 / compact.wallNanos));
                if (round == 4) {
                    plainOutput = plain.output;
                    compactOutput = compact.output;
                }
            }
            final Map<String, Map<Integer, byte[]>> plainChunks = readRegions(plainOutput);
            final Map<String, Map<Integer, byte[]>> compactChunks = readRegions(compactOutput);
            assertRegionsEqual("Java/compact-palette-storage", plainChunks, compactChunks);
            final long plainMedian = median(plainNanos), compactMedian = median(compactNanos);
            System.out.printf("Java compact palette full-export A/B (%d alternating, %d workers): "
                            + "object palette %.3f s, compact palette %.3f s, paired ratio %.3fx; "
                            + "runs %s / %s; heap peak/growth %.1f/%.1f vs %.1f/%.1f MiB; "
                            + "RSS peak/growth %s/%s vs %s/%s; exact NBT parity on %d chunks.%n",
                    plainNanos.size(), workerCount, plainMedian / 1_000_000_000.0,
                    compactMedian / 1_000_000_000.0, median(pairedRatios) / 1_000_000.0,
                    seconds(plainNanos), seconds(compactNanos),
                    median(plainHeapPeaks) / 1048576.0, median(plainHeapGrowth) / 1048576.0,
                    median(compactHeapPeaks) / 1048576.0, median(compactHeapGrowth) / 1048576.0,
                    formatMiB(medianAvailable(plainRssPeaks)),
                    formatMiB(medianAvailable(plainRssGrowth)),
                    formatMiB(medianAvailable(compactRssPeaks)),
                    formatMiB(medianAvailable(compactRssGrowth)), countChunks(compactChunks));
        } finally {
            restore(property, previous);
        }
    }

    private static void runNativeModeCampaign(World2 world, Path root, Mode javaMode,
                                              Mode frostMode, int workerCount, String label) throws Exception {
        final List<Long> javaNanos = new ArrayList<>(), frostNanos = new ArrayList<>();
        final List<Long> javaHeapPeaks = new ArrayList<>(), frostHeapPeaks = new ArrayList<>();
        final List<Long> javaHeapGrowth = new ArrayList<>(), frostHeapGrowth = new ArrayList<>();
        final List<Long> javaRssPeaks = new ArrayList<>(), frostRssPeaks = new ArrayList<>();
        final List<Long> javaRssGrowth = new ArrayList<>(), frostRssGrowth = new ArrayList<>();
        Path javaOutput = null, frostOutput = null;
        final boolean reverseOrder = Boolean.getBoolean("welt.export.frostReverseOrder");
        final String modeName = frostMode.name;

        for (int warmup = 0; warmup < 2; warmup++) {
            if (((warmup & 1) == 0) != reverseOrder) {
                runExport(world, root, javaMode, modeName + "-ab-warmup-java-" + warmup, false);
                runExport(world, root, frostMode, modeName + "-ab-warmup-native-" + warmup, false);
            } else {
                runExport(world, root, frostMode, modeName + "-ab-warmup-native-" + warmup, false);
                runExport(world, root, javaMode, modeName + "-ab-warmup-java-" + warmup, false);
            }
        }

        for (int round = 0; round < 5; round++) {
            final Mode[] order = (((round & 1) == 0) != reverseOrder)
                    ? new Mode[]{javaMode, frostMode} : new Mode[]{frostMode, javaMode};
            for (Mode mode : order) {
                final boolean frost = mode == frostMode;
                final RunResult result = runExport(world, root, mode,
                        "frost-ab-round-" + round, false);
                printTimings(frost ? modeName + "-native" : modeName + "-java", round, result);
                (frost ? frostNanos : javaNanos).add(result.wallNanos);
                (frost ? frostHeapPeaks : javaHeapPeaks).add(result.peakHeapBytes);
                (frost ? frostHeapGrowth : javaHeapGrowth).add(result.peakHeapGrowthBytes());
                (frost ? frostRssPeaks : javaRssPeaks).add(result.peakRssBytes);
                (frost ? frostRssGrowth : javaRssGrowth).add(result.peakRssGrowthBytes());
                if (round == 4) {
                    if (frost) frostOutput = result.output;
                    else javaOutput = result.output;
                }
            }
        }

        final Map<String, Map<Integer, byte[]>> javaChunks = readRegions(javaOutput);
        final Map<String, Map<Integer, byte[]>> frostChunks = readRegions(frostOutput);
        assertRegionsEqual(label + "/java", javaChunks, frostChunks);
        final long javaMedian = median(javaNanos), frostMedian = median(frostNanos);
        System.out.printf("%s full-export A/B (5 alternating, %d workers): Java %.3f s, "
                        + "Welt %.3f s, ratio %.3fx; runs Java %s, Welt %s; "
                        + "heap peak/growth %.1f/%.1f vs %.1f/%.1f MiB; "
                        + "RSS peak/growth %s/%s vs %s/%s; NBT parity on %d chunks.%n",
                label, workerCount, javaMedian / 1_000_000_000.0, frostMedian / 1_000_000_000.0,
                (double) javaMedian / frostMedian, seconds(javaNanos), seconds(frostNanos),
                median(javaHeapPeaks) / 1048576.0, median(javaHeapGrowth) / 1048576.0,
                median(frostHeapPeaks) / 1048576.0, median(frostHeapGrowth) / 1048576.0,
                formatMiB(medianAvailable(javaRssPeaks)), formatMiB(medianAvailable(javaRssGrowth)),
                formatMiB(medianAvailable(frostRssPeaks)), formatMiB(medianAvailable(frostRssGrowth)),
                countChunks(frostChunks));

        final String previousFrostProfile = System.getProperty("welt.export.profileFrostNative");
        try {
            System.setProperty("welt.export.profileFrostNative", "true");
            FrostExporter.resetNativeProfile();
            final RunResult profile = runExport(world, root, frostMode, modeName + "-profile", false);
            printTimings(modeName + "-profile", -1, profile);
            final Map<String, Map<Integer, byte[]>> profileChunks = readRegions(profile.output);
            assertRegionsEqual(label + "/profile", javaChunks, profileChunks);
            final long[] profileStats = FrostExporter.nativeProfileSnapshot();
            System.out.printf("Frost batch detail for %s: preparation %.3f s, JNI+Rust %.3f s, "
                            + "Java application/fallback %.3f s; %d JNI calls, %d native columns, "
                            + "%d fallback columns, %d packed cells.%n",
                    label, profileStats[0] / 1_000_000_000.0,
                    profileStats[1] / 1_000_000_000.0, profileStats[2] / 1_000_000_000.0,
                    profileStats[3], profileStats[4], profileStats[5], profileStats[6]);
        } finally {
            restore("welt.export.profileFrostNative", previousFrostProfile);
            FrostExporter.resetNativeProfile();
        }
    }

    private static CaptureRun runCaptureExport(World2 world, Path root, String round,
                                                boolean capture, boolean compactPaletteStorage)
            throws Exception {
        final String previousCaptureProfile = System.getProperty("welt.export.profileChunkCapture");
        final String previousPaletteIndexView = System.getProperty("welt.packedArrayCube.compactPaletteStorage");
        ChunkPaletteBuffer.resetCaptureProfile();
        System.setProperty("welt.export.profileChunkCapture", Boolean.toString(capture));
        System.setProperty("welt.packedArrayCube.compactPaletteStorage", Boolean.toString(compactPaletteStorage));
        try {
            final Mode mode = capture ? new Mode("java-capture", false, false, false) : MODES[0];
            final RunResult result = runExport(world, root, mode, round, false);
            return new CaptureRun(result, ChunkPaletteBuffer.captureProfile());
        } finally {
            restore("welt.export.profileChunkCapture", previousCaptureProfile);
            restore("welt.packedArrayCube.compactPaletteStorage", previousPaletteIndexView);
        }
    }

    private static RunResult runExport(World2 world, Path root, Mode mode, String round,
                                       boolean recordJfr) throws Exception {
        resetPlantRandomStream();
        Native.setExportEnabled(mode.export);
        System.setProperty(Native.FROST_EXPORT_KEY, Boolean.toString(mode.frost));
        System.setProperty(Native.RESOURCES_EXPORT_KEY, Boolean.toString(mode.resources));
        System.setProperty(Native.FLUID_FLOW_EXPORT_KEY, Boolean.toString(mode.fluidFlow));
        final Path output = root.resolve(round + "-" + mode.name);
        Files.createDirectories(output);
        System.gc();
        Thread.sleep(100L);
        final WorldExporter exporter = PlatformManager.getInstance().getExporter(world,
                new WorldExportSettings(singleton(DIM_NORMAL), null, null));
        final Path jfrPath = root.resolve("java-baseline.jfr");
        final MemorySampler memorySampler = new MemorySampler();
        memorySampler.start();
        final long start = System.nanoTime();
        final Map<Integer, ChunkFactory.Stats> stats;
        final long wallNanos;
        try {
            if (recordJfr) {
                try (Recording recording = new Recording()) {
                    recording.setName("Welt full export Java baseline");
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                    recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
                    recording.enable("jdk.ObjectAllocationSample");
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
        } finally {
            wallNanos = System.nanoTime() - start;
            memorySampler.stop();
        }
        return new RunResult(wallNanos, memorySampler.heapBeforeBytes(), memorySampler.peakHeapBytes(),
                memorySampler.rssBeforeBytes(), memorySampler.peakRssBytes(),
                output, stats, recordJfr ? jfrPath : null);
    }

    private static void resetPlantRandomStream() throws ReflectiveOperationException {
        final Field randomField = Plants.class.getDeclaredField("RANDOM");
        randomField.setAccessible(true);
        ((Random) randomField.get(null)).setSeed(0x57454c54L);
    }

    private static void printTimings(String mode, int round, RunResult result) {
        final Map<String, Long> stages = new HashMap<>();
        result.stats.values().forEach(dimension -> dimension.timings.forEach((stage, duration) ->
                stages.merge(String.valueOf(stage), duration.get(), Long::sum)));
        System.out.printf("Export %-16s round %d: %.3f s mural", mode, round,
                result.wallNanos / 1_000_000_000.0);
        System.out.printf("; heap pic %.1f MiB (+%.1f MiB)",
                result.peakHeapBytes / 1048576.0, result.peakHeapGrowthBytes() / 1048576.0);
        System.out.printf("; RSS pic %s (+%s)", formatMiB(result.peakRssBytes),
                formatMiB(result.peakRssGrowthBytes()));
        stages.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> System.out.printf("; %s %.3f s CPU cumulé",
                        entry.getKey(), entry.getValue() / 1_000_000_000.0));
        if (result.jfr != null) System.out.print("; JFR=" + result.jfr);
        System.out.println();
    }

    private static long stageNanos(RunResult result, String stageName) {
        long total = 0;
        for (ChunkFactory.Stats dimension : result.stats.values()) {
            for (Map.Entry<Object, AtomicLong> timing : dimension.timings.entrySet()) {
                if (stageName.equals(String.valueOf(timing.getKey()))) {
                    total += timing.getValue().get();
                }
            }
        }
        return total;
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
            chunks.put(index, canonicaliseTransientTickTimes(expanded.toByteArray()));
        }
        return chunks;
    }

    /** Normalises scheduled tick times; the exported world advances its tick clock between runs. */
    private static byte[] canonicaliseTransientTickTimes(byte[] nbt) throws IOException {
        if (nbt.length < 3 || (nbt[0] & 0xff) != 10) {
            throw new IOException("Chunk root is not an NBT compound");
        }
        final byte[] canonical = nbt.clone();
        final int rootNameLength = ((canonical[1] & 0xff) << 8) | (canonical[2] & 0xff);
        final int payloadOffset = 3 + rootNameLength;
        if (payloadOffset > canonical.length) {
            throw new IOException("Truncated chunk root name");
        }
        final int end = canonicaliseNbtPayload(canonical, 10, payloadOffset, false);
        if (end != canonical.length) {
            throw new IOException("Trailing bytes after chunk NBT root: " + (canonical.length - end));
        }
        return canonical;
    }

    private static int canonicaliseNbtPayload(byte[] bytes, int type, int offset,
                                               boolean tickEntry) throws IOException {
        final int start = offset;
        switch (type) {
            case 1 -> offset += 1;
            case 2 -> offset += 2;
            case 3 -> offset += 4;
            case 4, 6 -> offset += 8;
            case 5 -> offset += 4;
            case 7, 11, 12 -> {
                final int count = readNbtInt(bytes, offset);
                if (count < 0) throw new IOException("Negative NBT array length");
                offset += 4 + count * (type == 7 ? 1 : type == 11 ? 4 : 8);
            }
            case 8 -> offset += 2 + readNbtUnsignedShort(bytes, offset);
            case 9 -> {
                if (offset + 5 > bytes.length) throw new IOException("Truncated NBT list header");
                final int childType = bytes[offset] & 0xff;
                final int count = readNbtInt(bytes, offset + 1);
                if (count < 0) throw new IOException("Negative NBT list length");
                offset += 5;
                for (int i = 0; i < count; i++) {
                    offset = canonicaliseNbtPayload(bytes, childType, offset, tickEntry);
                }
            }
            case 10 -> {
                while (true) {
                    if (offset >= bytes.length) throw new IOException("Truncated NBT compound");
                    final int childType = bytes[offset++] & 0xff;
                    if (childType == 0) break;
                    final int nameLength = readNbtUnsignedShort(bytes, offset);
                    offset += 2;
                    if (offset + nameLength > bytes.length) throw new IOException("Truncated NBT name");
                    final String name = new String(bytes, offset, nameLength, java.nio.charset.StandardCharsets.UTF_8);
                    offset += nameLength;
                    final int valueOffset = offset;
                    if (tickEntry && "t".equals(name) && childType == 3) {
                        if (valueOffset + 4 > bytes.length) throw new IOException("Truncated scheduled tick time");
                        Arrays.fill(bytes, valueOffset, valueOffset + 4, (byte) 0);
                    }
                    final boolean scheduledTicks = (childType == 9)
                            && ("LiquidTicks".equals(name) || "TileTicks".equals(name));
                    offset = canonicaliseNbtPayload(bytes, childType, offset, scheduledTicks);
                }
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
        if (offset < start || offset > bytes.length) throw new IOException("NBT tag exceeds chunk bounds");
        return offset;
    }

    private static int readNbtUnsignedShort(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 2 > bytes.length) throw new IOException("Truncated NBT short");
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int readNbtInt(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw new IOException("Truncated NBT integer");
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
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
                final byte[] expectedNbt = expectedChunks.get(chunk);
                final byte[] actualNbt = actualChunks.get(chunk);
                if (!Arrays.equals(expectedNbt, actualNbt)) {
                    try {
                        final List<String> differences = new ArrayList<>();
                        compareNbtTags(readNbtTag(expectedNbt), readNbtTag(actualNbt), "", differences);
                        differences.stream().limit(12).forEach(difference ->
                                System.err.println("NBT field difference: " + difference));
                    } catch (IOException | ReflectiveOperationException diagnosticFailure) {
                        System.err.println("Could not decode NBT difference: " + diagnosticFailure.getClass().getSimpleName());
                    }
                }
                assertArrayEquals(comparison + " " + region + " chunk=" + chunk,
                        expectedNbt, actualNbt);
            }
        }
    }

    private static Tag readNbtTag(byte[] nbt) throws IOException {
        try (NBTInputStream input = new NBTInputStream(new ByteArrayInputStream(nbt))) {
            return input.readTag();
        }
    }

    private static void compareNbtTags(Object expected, Object actual, String path,
                                       List<String> differences)
            throws ReflectiveOperationException {
        if (expected instanceof Tag expectedTag && actual instanceof Tag actualTag) {
            final String tagPath = path + "/" + expectedTag.getName();
            compareNbtTags(expectedTag.getClass().getMethod("getValue").invoke(expectedTag),
                    actualTag.getClass().getMethod("getValue").invoke(actualTag), tagPath, differences);
        } else if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            for (Object key : expectedMap.keySet()) {
                if (!actualMap.containsKey(key)) {
                    differences.add(path + "/" + key + " missing from actual NBT");
                } else {
                    compareNbtTags(expectedMap.get(key), actualMap.get(key), path + "/" + key, differences);
                }
            }
            for (Object key : actualMap.keySet()) {
                if (!expectedMap.containsKey(key)) differences.add(path + "/" + key + " added in actual NBT");
            }
        } else if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            if (expectedList.size() != actualList.size()) {
                differences.add(path + " list size " + expectedList.size() + " vs " + actualList.size());
            }
            for (int index = 0; index < Math.min(expectedList.size(), actualList.size())
                    && differences.size() < 12; index++) {
                compareNbtTags(expectedList.get(index), actualList.get(index), path + "[" + index + "]", differences);
            }
        } else if (expected != null && actual != null
                && expected.getClass().isArray() && actual.getClass().isArray()) {
            final int expectedLength = java.lang.reflect.Array.getLength(expected);
            final int actualLength = java.lang.reflect.Array.getLength(actual);
            if (expectedLength != actualLength) differences.add(path + " array size " + expectedLength + " vs " + actualLength);
            for (int index = 0; index < Math.min(expectedLength, actualLength)
                    && differences.size() < 12; index++) {
                final Object expectedValue = java.lang.reflect.Array.get(expected, index);
                final Object actualValue = java.lang.reflect.Array.get(actual, index);
                if (!java.util.Objects.equals(expectedValue, actualValue)) {
                    differences.add(path + "[" + index + "] " + expectedValue + " vs " + actualValue);
                }
            }
        } else if (!java.util.Objects.equals(expected, actual) && differences.size() < 12) {
            differences.add(path + " " + expected + " vs " + actual);
        }
    }

    private static int countChunks(Map<String, Map<Integer, byte[]>> regions) {
        return regions.values().stream().mapToInt(Map::size).sum();
    }

    private static long median(List<Long> values) {
        final long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    private static long medianAvailable(List<Long> values) {
        final List<Long> available = values.stream().filter(value -> value >= 0).toList();
        return available.isEmpty() ? -1L : median(available);
    }

    private static String formatMiB(long bytes) {
        return bytes < 0 ? "indisponible" : String.format("%.1f MiB", bytes / 1048576.0);
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
        private final boolean export, frost, resources, fluidFlow;

        private Mode(String name, boolean export, boolean frost, boolean resources) {
            this(name, export, frost, resources, false);
        }

        private Mode(String name, boolean export, boolean frost, boolean resources, boolean fluidFlow) {
            this.name = name;
            this.export = export;
            this.frost = frost;
            this.resources = resources;
            this.fluidFlow = fluidFlow;
        }
    }

    private static final class RunResult {
        private final long wallNanos;
        private final long heapBeforeBytes, peakHeapBytes;
        private final long rssBeforeBytes, peakRssBytes;
        private final Path output;
        private final Map<Integer, ChunkFactory.Stats> stats;
        private final Path jfr;

        private RunResult(long wallNanos, long heapBeforeBytes, long peakHeapBytes,
                          long rssBeforeBytes, long peakRssBytes,
                          Path output, Map<Integer, ChunkFactory.Stats> stats, Path jfr) {
            this.wallNanos = wallNanos;
            this.heapBeforeBytes = heapBeforeBytes;
            this.peakHeapBytes = peakHeapBytes;
            this.rssBeforeBytes = rssBeforeBytes;
            this.peakRssBytes = peakRssBytes;
            this.output = output;
            this.stats = stats;
            this.jfr = jfr;
        }

        private long peakHeapGrowthBytes() {
            return Math.max(0L, peakHeapBytes - heapBeforeBytes);
        }

        private long peakRssGrowthBytes() {
            return rssBeforeBytes < 0 || peakRssBytes < 0 ? -1L : Math.max(0L, peakRssBytes - rssBeforeBytes);
        }
    }

    private static final class MemorySampler {
        private static final long SAMPLE_PERIOD_MILLIS = 20L;

        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final AtomicLong peakHeapBytes = new AtomicLong();
        private final AtomicLong rssBeforeBytes = new AtomicLong(-1L);
        private final AtomicLong peakRssBytes = new AtomicLong(-1L);
        private volatile boolean sampling;
        private long heapBeforeBytes;
        private Thread thread;

        private void start() {
            heapBeforeBytes = usedHeapBytes();
            peakHeapBytes.set(heapBeforeBytes);
            final long residentBytes = org.pepsoft.worldpainter.nativeapi.NativeSlices.currentProcessResidentBytes();
            rssBeforeBytes.set(residentBytes);
            peakRssBytes.set(residentBytes);
            sampling = true;
            thread = new Thread(() -> {
                while (sampling) {
                    sample();
                    try {
                        Thread.sleep(SAMPLE_PERIOD_MILLIS);
                    } catch (InterruptedException ignored) {
                        // Stop is observed on the next loop condition.
                    }
                }
                sample();
            }, "welt-export-memory-sampler");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();
        }

        private long peakHeapBytes() {
            return peakHeapBytes.get();
        }

        private long heapBeforeBytes() {
            return heapBeforeBytes;
        }

        private long rssBeforeBytes() {
            return rssBeforeBytes.get();
        }

        private long peakRssBytes() {
            return peakRssBytes.get();
        }

        private void stop() {
            sampling = false;
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sample();
        }

        private void sample() {
            final long used = usedHeapBytes();
            peakHeapBytes.accumulateAndGet(used, Math::max);
            final long residentBytes = org.pepsoft.worldpainter.nativeapi.NativeSlices.currentProcessResidentBytes();
            if (residentBytes >= 0) {
                rssBeforeBytes.compareAndSet(-1L, residentBytes);
                peakRssBytes.accumulateAndGet(residentBytes, Math::max);
            }
        }

        private long usedHeapBytes() {
            return memoryBean.getHeapMemoryUsage().getUsed();
        }
    }

    private static final class CaptureRun {
        private final RunResult result;
        private final ChunkPaletteBuffer.CaptureProfile profile;

        private CaptureRun(RunResult result, ChunkPaletteBuffer.CaptureProfile profile) {
            this.result = result;
            this.profile = profile;
        }
    }
}
