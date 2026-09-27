package org.pepsoft.worldpainter;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.Test;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.minecraft.ChunkPaletteBuffer;
import org.pepsoft.util.TextProgressReceiver;
import org.pepsoft.worldpainter.exporting.WorldExportSettings;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.exporting.BlockPropertiesCalculator;
import org.pepsoft.worldpainter.layers.exporters.ResourcesExporter;
import org.pepsoft.worldpainter.layers.plants.Plants;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
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
        final String oldHome = System.getProperty("user.home");
        final String oldThreads = System.getProperty("org.pepsoft.worldpainter.threads");
        final String oldExport = System.getProperty(Native.EXPORT_KEY);
        final String oldFrost = System.getProperty(Native.FROST_EXPORT_KEY);
        final String oldResources = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        final String oldBlockPropertiesFrontier = System.getProperty("welt.export.blockPropertiesFrontier");
        final String oldBlockPropertiesFrontierProfile = System.getProperty("welt.export.profileBlockPropertiesFrontier");
        final String oldCaptureProfile = System.getProperty("welt.export.profileChunkCapture");
        final String oldDisableInitialHeightCache = System.getProperty("welt.export.disableInitialHeightCache");
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
            if (Boolean.getBoolean("welt.export.frontierOnly")) {
                return;
            }

            final Map<String, List<Long>> measuredWallNanos = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredHeapPeaks = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredHeapGrowth = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredRssPeaks = new LinkedHashMap<>();
            final Map<String, List<Long>> measuredRssGrowth = new LinkedHashMap<>();
            final Map<String, Path> parityOutputs = new LinkedHashMap<>();
            for (Mode mode : MODES) {
                measuredWallNanos.put(mode.name, new ArrayList<>());
                measuredHeapPeaks.put(mode.name, new ArrayList<>());
                measuredHeapGrowth.put(mode.name, new ArrayList<>());
                measuredRssPeaks.put(mode.name, new ArrayList<>());
                measuredRssGrowth.put(mode.name, new ArrayList<>());
            }

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

            // Split the native Resources path into Java input preparation,
            // the complete JNI call (including JNI copies and Rust compute),
            // and Java result application. This diagnostic pass is excluded
            // from all timed medians below.
            final String previousResourceProfile = System.getProperty("welt.export.profileResourcesNative");
            try {
                System.setProperty("welt.export.profileResourcesNative", "true");
                ResourcesExporter.resetNativeProfile();
                final RunResult resourceProfile = runExport(world, root,
                        new Mode("resources-profile", true, false, true), "profile-resources", false);
                printTimings("resources-profile", -1, resourceProfile);
                final long[] nativeProfile = ResourcesExporter.nativeProfileSnapshot();
                System.out.printf("Resources native split over %d chunks: Java preparation %.3f s, "
                                + "JNI+copy+Rust %.3f s (input copies %.3f s, Rust kernel %.3f s), "
                                + "Java apply %.3f s%n",
                        nativeProfile[3], nativeProfile[0] / 1_000_000_000.0,
                        nativeProfile[1] / 1_000_000_000.0,
                        nativeProfile[4] / 1_000_000_000.0,
                        nativeProfile[5] / 1_000_000_000.0,
                        nativeProfile[2] / 1_000_000_000.0);
            } finally {
                if (previousResourceProfile == null) {
                    System.clearProperty("welt.export.profileResourcesNative");
                } else {
                    System.setProperty("welt.export.profileResourcesNative", previousResourceProfile);
                }
            }

            // Reverse the order on alternating rounds to limit thermal and cache bias.
            for (int round = 0; round < 5; round++) {
                final Mode[] order = MODES.clone();
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

            final Path javaOutput = parityOutputs.get("java");
            final Map<String, Map<Integer, byte[]>> javaChunks = readRegions(javaOutput);
            for (Mode mode : MODES) {
                final Map<String, Map<Integer, byte[]>> candidate = readRegions(parityOutputs.get(mode.name));
                assertRegionsEqual("Java/" + mode.name, javaChunks, candidate);
                System.out.println("Parity Java/" + mode.name + ": " + countChunks(candidate)
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

            final long javaMedian = median(measuredWallNanos.get("java"));
            System.out.println("Résultats exports complets (5 mesures, 4 workers fixes) :");
            for (Mode mode : MODES) {
                final long modeMedian = median(measuredWallNanos.get(mode.name));
                System.out.printf("  %-16s médiane %.3f s; ratio Java/Welt %.3fx; "
                                + "heap pic/hausse %.1f/%.1f MiB; RSS pic/hausse %s/%s; essais %s%n",
                        mode.name, modeMedian / 1_000_000_000.0,
                        (double) javaMedian / modeMedian,
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
        private RecordingStream rssRecording;

        private void start() {
            heapBeforeBytes = usedHeapBytes();
            peakHeapBytes.set(heapBeforeBytes);
            try {
                rssRecording = new RecordingStream();
                rssRecording.enable("jdk.ResidentSetSize").withPeriod(Duration.ofMillis(SAMPLE_PERIOD_MILLIS));
                rssRecording.onEvent("jdk.ResidentSetSize", this::sampleRss);
                rssRecording.startAsync();
                final long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (rssBeforeBytes.get() < 0 && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                rssRecording = null;
            }
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
            if (rssRecording != null) {
                rssRecording.close();
                rssRecording = null;
            }
        }

        private void sample() {
            final long used = usedHeapBytes();
            peakHeapBytes.accumulateAndGet(used, Math::max);
        }

        private void sampleRss(RecordedEvent event) {
            final long size = event.getLong("size");
            rssBeforeBytes.compareAndSet(-1L, size);
            peakRssBytes.accumulateAndGet(size, Math::max);
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
