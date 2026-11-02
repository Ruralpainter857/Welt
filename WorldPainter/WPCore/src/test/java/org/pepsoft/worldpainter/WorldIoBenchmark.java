package org.pepsoft.worldpainter;

import org.pepsoft.util.WPCustomObjectInputStream;
import org.pepsoft.util.plugins.PluginManager;
import org.pepsoft.worldpainter.objects.AbstractObject;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Open/save profile of synthetic projects (see {@link WorldIoFixtures}) through the real {@link WorldIO} entry points,
 * as the application calls them (unbuffered file streams), plus a breakdown of the same work into serialisation,
 * compression and disk I/O phases. Prints wall time, allocations of the calling thread (and a lower bound for the
 * compression workers), sampled peak heap above the phase start, retained heap after loading, file size and a
 * canonical digest of the world at the start, before saving and after loading.
 *
 * <p>Usage: {@code WorldIoBenchmark profile [side] [runs] [warmups] [file]}; profile is {@code uniform}, {@code noise}
 * or {@code layers}; side is the number of tiles along each edge of the surface. With a file argument the world is
 * saved there (and kept) for cross-build checks. {@code -Dwio.fixtureFile=<file>} starts from the world in that file
 * (generating and saving it there on a first run), so that two builds start from the same world;
 * {@code -Dwio.breakdown=false} skips the phase breakdown.
 */
public final class WorldIoBenchmark {
    public static void main(String[] args) throws Exception {
        final String profile = args[0];
        final int side = (args.length > 1) ? Integer.parseInt(args[1]) : 32;
        final int runs = (args.length > 2) ? Integer.parseInt(args[2]) : 3;
        final int warmups = (args.length > 3) ? Integer.parseInt(args[3]) : 1;
        final Path dir = Path.of("target", "world-io");
        Files.createDirectories(dir);
        final Path file = (args.length > 4) ? Path.of(args[4]) : dir.resolve("bench-" + profile + "-" + ProcessHandle.current().pid() + ".world");
        final HeapSampler sampler = new HeapSampler();
        sampler.start();

        // With -Dwio.fixtureFile the starting world is loaded from that file (created there first if absent), so that
        // the reference and the new build start from exactly the same world: generated fixtures contain random ids
        long t0 = System.nanoTime();
        World2 world;
        final String fixtureFile = System.getProperty("wio.fixtureFile");
        if (fixtureFile == null) {
            world = WorldIoFixtures.create(profile, side);
        } else if (Files.isRegularFile(Path.of(fixtureFile))) {
            final WorldIO fixtureIo = new WorldIO();
            fixtureIo.load(new FileInputStream(fixtureFile));
            world = fixtureIo.getWorld();
        } else {
            world = WorldIoFixtures.create(profile, side);
            Files.createDirectories(Path.of(fixtureFile).toAbsolutePath().getParent());
            new WorldIO(world).save(new FileOutputStream(fixtureFile));
            System.out.println("fixture file created; rerun to measure from it");
            return;
        }
        final long fixtureNanos = System.nanoTime() - t0;
        int tiles = 0;
        for (Dimension dimension: world.getDimensions()) {
            tiles += dimension.getTileCount();
        }
        System.out.printf(Locale.ROOT, "profile=%s side=%d dimensions=%d tiles=%d fixture=%.0fms (%s) io=%s digestStart=%s%n", profile, side,
                world.getDimensions().size(), tiles, fixtureNanos / 1e6, (fixtureFile != null) ? "loaded from fixture file" : "generated",
                IoInfo.describe(), Long.toHexString(WorldCanonicalDigest.of(world)));

        final List<Map<String, Long>> results = new ArrayList<>();
        long digestBefore = 0, digestAfter = 0;
        for (int run = 0; run < warmups + runs; run++) {
            final Map<String, Long> r = new LinkedHashMap<>();
            // Full save as the application does it
            gc();
            sampler.reset();
            long alloc = allocated();
            t0 = System.nanoTime();
            new WorldIO(world).save(new FileOutputStream(file.toFile()));
            r.put("saveMs", (System.nanoTime() - t0) / 1_000_000);
            r.put("saveAllocMiB", (allocated() - alloc) >> 20);
            r.put("saveWorkerAllocMiB", sampler.workerAllocated() >> 20);
            r.put("savePeakMiB", sampler.peakAboveStart() >> 20);
            r.put("fileKiB", Files.size(file) >> 10);
            digestBefore = WorldCanonicalDigest.of(world); // After saving: prepareForSaving() side effects included

            // Save breakdown: serialisation only, compression only, disk write only
            final boolean breakdown = ! "false".equals(System.getProperty("wio.breakdown"));
            final byte[] raw = breakdown ? serialise(world) : null;
            if (breakdown) {
            final CountingStream counter = new CountingStream();
            t0 = System.nanoTime();
            try (ObjectOutputStream out = new ObjectOutputStream(counter)) {
                out.writeObject(world);
            }
            r.put("serialiseMs", (System.nanoTime() - t0) / 1_000_000);
            r.put("rawKiB", counter.count >> 10);
            t0 = System.nanoTime();
            try (GZIPOutputStream gzip = new GZIPOutputStream(new CountingStream())) {
                gzip.write(raw);
            }
            r.put("deflateMs", (System.nanoTime() - t0) / 1_000_000);
            final byte[] compressed = Files.readAllBytes(file);
            final Path copy = file.resolveSibling(file.getFileName() + ".copy");
            t0 = System.nanoTime();
            try (OutputStream out = new FileOutputStream(copy.toFile())) {
                out.write(compressed);
            }
            r.put("writeMs", (System.nanoTime() - t0) / 1_000_000);
            Files.delete(copy);
            }

            // Full load as the application does it, with the previous world released
            world = null;
            gc();
            final long baseline = usedHeap();
            sampler.reset();
            alloc = allocated();
            t0 = System.nanoTime();
            final WorldIO io = new WorldIO();
            io.load(new FileInputStream(file.toFile()));
            r.put("loadMs", (System.nanoTime() - t0) / 1_000_000);
            r.put("loadAllocMiB", (allocated() - alloc) >> 20);
            r.put("loadPeakMiB", sampler.peakAboveStart() >> 20);
            world = io.getWorld();
            gc();
            r.put("retainedMiB", (usedHeap() - baseline) >> 20);
            digestAfter = WorldCanonicalDigest.of(world);

            // Load breakdown: disk read only, decompression only, deserialisation only
            if (breakdown) {
            t0 = System.nanoTime();
            final byte[] fromDisk = Files.readAllBytes(file);
            r.put("readMs", (System.nanoTime() - t0) / 1_000_000);
            t0 = System.nanoTime();
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(fromDisk))) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            r.put("inflateMs", (System.nanoTime() - t0) / 1_000_000);
            t0 = System.nanoTime();
            deserialise(raw);
            r.put("deserialiseMs", (System.nanoTime() - t0) / 1_000_000);
            }
            gc();

            final String label = (run < warmups) ? "warmup" : "run";
            System.out.println(label + " " + run + ": " + r + " digestEqual=" + (digestBefore == digestAfter));
            if (run >= warmups) {
                results.add(r);
            }
        }
        final Map<String, Long> median = new LinkedHashMap<>();
        for (String key: results.get(0).keySet()) {
            final long[] values = results.stream().mapToLong(r -> r.get(key)).sorted().toArray();
            median.put(key, values[values.length / 2]);
        }
        System.out.println("median: " + median);
        System.out.println("digestBefore=" + Long.toHexString(digestBefore) + " digestAfter=" + Long.toHexString(digestAfter) + " equal=" + (digestBefore == digestAfter));
        System.out.println("rssPeakMiB=" + (peakWorkingSet() >> 20));
        if (args.length <= 4) {
            Files.deleteIfExists(file);
        }
        sampler.stop = true;
    }

    static byte[] serialise(Object object) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(object);
        }
        return bytes.toByteArray();
    }

    static Object deserialise(byte[] raw) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new WPCustomObjectInputStream(new ByteArrayInputStream(raw), PluginManager.getPluginClassLoader(), AbstractObject.class)) {
            return in.readObject();
        }
    }

    static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    static long peakWorkingSet() {
        try {
            final Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64").redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return Long.parseLong(output);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Samples used heap every millisecond; reports the highest value above the value at the last reset. */
    static final class HeapSampler extends Thread {
        HeapSampler() {
            setDaemon(true);
            setName("heap-sampler");
        }

        @Override
        public void run() {
            final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            int tick = 0;
            while (! stop) {
                final long used = usedHeap();
                if (used > peak) {
                    peak = used;
                }
                if ((++tick & 1) == 0) {
                    // Compression worker allocations (lower bound: last value seen before each worker ends)
                    for (java.lang.management.ThreadInfo info: threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
                        if ((info != null) && info.getThreadName().equals("Parallel gzip")) {
                            workers.merge(info.getThreadId(), threads.getThreadAllocatedBytes(info.getThreadId()), Math::max);
                        }
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        synchronized void reset() {
            start = usedHeap();
            peak = start;
            workers.clear();
        }

        long workerAllocated() {
            return workers.values().stream().mapToLong(Long::longValue).filter(v -> v > 0).sum();
        }

        long peakAboveStart() {
            return Math.max(0, peak - start);
        }

        volatile boolean stop;
        volatile long peak, start;
        final Map<Long, Long> workers = new java.util.concurrent.ConcurrentHashMap<>();
    }

    static final class CountingStream extends OutputStream {
        @Override public void write(int b) { count++; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
        long count;
    }

    /** Reports the IO configuration when the build under test exposes one (by reflection, absent in the reference). */
    static final class IoInfo {
        static String describe() {
            try {
                return (String) Class.forName("org.pepsoft.worldpainter.WorldIO").getDeclaredMethod("describeIo").invoke(null);
            } catch (ReflectiveOperationException e) {
                return "reference";
            }
        }
    }
}
