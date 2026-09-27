package org.pepsoft.worldpainter;

import org.junit.Test;
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
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;

/** Opt-in whole-export chunk check: run with -Dwelt.resource.parity=true. */
public final class ResourceExporterChunkParityTest extends AbstractTool {
    @Test
    public void nativeResourceExportMatchesEveryJavaChunkNbt() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.resource.parity"));
        assumeTrue("welt_slices is built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String oldUserHome = System.getProperty("user.home");
        final String oldThreads = System.getProperty("org.pepsoft.worldpainter.threads");
        final String oldFlag = System.getProperty(Native.EXPORT_KEY);
        final Path root = Files.createTempDirectory("welt-resource-chunk-parity-");
        try {
            System.setProperty("user.home", root.resolve("home").toString());
            System.setProperty("org.pepsoft.worldpainter.threads", "4");
            Files.createDirectories(root.resolve("home"));
            initialisePlatform();
            final File fixture = new File("../WPGUI/src/test/resources/Generated World.world").getCanonicalFile();
            final WorldIO worldIO = new WorldIO();
            worldIO.load(new FileInputStream(fixture));
            final World2 world = worldIO.getWorld();
            for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
                final MixedMaterial material = world.getMixedMaterial(i);
                Terrain.setCustomMaterial(i, material);
            }

            final Path javaRoot = root.resolve("java");
            final Path nativeRoot = root.resolve("native");
            Files.createDirectories(javaRoot);
            Files.createDirectories(nativeRoot);
            Native.setExportEnabled(false);
            export(world, javaRoot.toFile());
            Native.setExportEnabled(true);
            export(world, nativeRoot.toFile());

            final Map<String, Map<Integer, byte[]>> javaRegions = readRegions(javaRoot);
            final Map<String, Map<Integer, byte[]>> nativeRegions = readRegions(nativeRoot);
            assertEquals("region files", javaRegions.keySet(), nativeRegions.keySet());
            for (String region : javaRegions.keySet()) {
                final Map<Integer, byte[]> javaChunks = javaRegions.get(region);
                final Map<Integer, byte[]> nativeChunks = nativeRegions.get(region);
                assertEquals(region + " chunk indexes", javaChunks.keySet(), nativeChunks.keySet());
                for (int chunk : javaChunks.keySet()) {
                    assertArrayEquals(region + " chunk index=" + chunk,
                            javaChunks.get(chunk), nativeChunks.get(chunk));
                }
            }
            System.out.println("Compared " + javaRegions.values().stream().mapToInt(Map::size).sum()
                    + " decompressed chunk NBT payloads; outputs retained in " + root);
        } finally {
            restore("user.home", oldUserHome);
            restore("org.pepsoft.worldpainter.threads", oldThreads);
            restore(Native.EXPORT_KEY, oldFlag);
        }
    }

    private static void export(final World2 world, final File baseDir) throws Exception {
        final WorldExporter exporter = PlatformManager.getInstance().getExporter(world,
                new WorldExportSettings(singleton(DIM_NORMAL), null, null));
        final File backupDir = exporter.selectBackupDir(baseDir, world.getName());
        exporter.export(baseDir, world.getName(), backupDir, new TextProgressReceiver());
    }

    private static Map<String, Map<Integer, byte[]>> readRegions(final Path root) throws IOException {
        final Map<String, Map<Integer, byte[]>> regions = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".mca")).toList()) {
                final String name = root.relativize(path).toString();
                regions.put(name, readRegion(path));
            }
        }
        return regions;
    }

    private static Map<Integer, byte[]> readRegion(final Path path) throws IOException {
        final byte[] file = Files.readAllBytes(path);
        if (file.length < 8192) {
            throw new IOException("Truncated region header: " + path);
        }
        final Map<Integer, byte[]> chunks = new HashMap<>();
        for (int index = 0; index < 1024; index++) {
            final int header = index * 4;
            final int sector = ((file[header] & 0xff) << 16)
                    | ((file[header + 1] & 0xff) << 8) | (file[header + 2] & 0xff);
            if (sector == 0) {
                continue;
            }
            final int offset = sector * 4096;
            if (offset < 8192 || offset + 5 > file.length) {
                throw new IOException("Invalid chunk offset in " + path + " at index " + index);
            }
            final DataInputStream input = new DataInputStream(new ByteArrayInputStream(file, offset,
                    file.length - offset));
            final int length = input.readInt();
            final int compression = input.readUnsignedByte();
            if (length < 1 || offset + 4L + length > file.length) {
                throw new IOException("Invalid chunk length in " + path + " at index " + index);
            }
            final byte[] compressed = input.readNBytes(length - 1);
            final ByteArrayInputStream payload = new ByteArrayInputStream(compressed);
            final ByteArrayOutputStream expanded = new ByteArrayOutputStream();
            try (var decompressor = switch (compression) {
                case 1 -> new GZIPInputStream(payload);
                case 2 -> new InflaterInputStream(payload);
                case 3 -> payload;
                default -> throw new IOException("Unknown chunk compression " + compression + " in " + path);
            }) {
                decompressor.transferTo(expanded);
            }
            chunks.put(index, expanded.toByteArray());
        }
        return chunks;
    }

    private static void restore(final String key, final String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
