package org.pepsoft.worldpainter;

import jdk.jfr.Recording;
import org.junit.Test;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.util.TextProgressReceiver;
import org.pepsoft.worldpainter.exporting.WorldExportSettings;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.plugins.PlatformManager;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assume.assumeTrue;
import static org.pepsoft.worldpainter.exporting.WorldExportSettings.EXPORT_EVERYTHING;

/** Opt-in JFR profile for an export: run with -Dwelt.blockproperties.profile=true. */
public final class BlockPropertiesProfileTest extends AbstractTool {
    @Test
    public void profileExportAndReportChunkFactoryStages() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.blockproperties.profile"));
        final String oldUserHome = System.getProperty("user.home");
        final String oldExportFlag = System.getProperty(Native.EXPORT_KEY);
        final Path root = Files.createTempDirectory("welt-block-properties-profile-");
        try {
            System.setProperty("user.home", root.resolve("home").toString());
            Native.setExportEnabled(false);
            Files.createDirectories(root.resolve("home"));
            initialisePlatform();

            final File fixture = new File("../WPGUI/src/test/resources/Generated World.world").getCanonicalFile();
            final WorldIO worldIO = new WorldIO();
            try (FileInputStream input = new FileInputStream(fixture)) {
                worldIO.load(input);
            }
            final World2 world = worldIO.getWorld();
            for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
                Terrain.setCustomMaterial(i, world.getMixedMaterial(i));
            }
            final Path recordingFile = root.resolve("block-properties-java.jfr");
            final List<Map<Integer, ChunkFactory.Stats>> campaigns = new ArrayList<>();
            try (Recording recording = new Recording()) {
                recording.setName("Welt block properties baseline");
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(1_000));
                recording.start();
                try {
                    for (int run = 0; run < 3; run++) {
                        final File output = root.resolve("export-" + run).toFile();
                        Files.createDirectories(output.toPath());
                        final WorldExporter exporter = PlatformManager.getInstance()
                                .getExporter(world, EXPORT_EVERYTHING);
                        campaigns.add(exporter.export(output, world.getName(),
                                exporter.selectBackupDir(output, world.getName()),
                                new TextProgressReceiver()));
                    }
                } finally {
                    recording.stop();
                    recording.dump(recordingFile);
                }
            }
            System.out.println("JFR recording retained at " + recordingFile);
            for (int run = 0; run < campaigns.size(); run++) {
                final List<Map.Entry<Object, AtomicLong>> timings = new ArrayList<>();
                campaigns.get(run).values().forEach(dimension -> timings.addAll(dimension.timings.entrySet()));
                timings.sort(Map.Entry.comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed());
                System.out.println("Export campaign " + run + ":");
                for (Map.Entry<Object, AtomicLong> timing : timings) {
                    System.out.println("  ChunkFactory stage " + timing.getKey() + ": "
                            + timing.getValue().get() / 1_000_000 + " ms aggregate CPU");
                }
            }
            System.out.println("Export outputs retained under " + root);
        } finally {
            restore("user.home", oldUserHome);
            restore(Native.EXPORT_KEY, oldExportFlag);
        }
    }

    private static void restore(final String key, final String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
