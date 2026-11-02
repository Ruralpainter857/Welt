package org.pepsoft.worldpainter;

import org.pepsoft.util.undo.BufferKey;
import org.pepsoft.util.undo.Snapshot;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.*;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;

/**
 * Reproducible editor scenario for tile storage and undo history: seeded tile set (negative coordinates, implicit,
 * uniform and non-uniform BYTE/NIBBLE/BIT/BIT_PER_CHUNK layers), localised brush strokes with save points, snapshots
 * read before and after edits, undo/redo and a redo-invalidating edit. Prints wall time, allocations, retained heap,
 * direct memory, an exact breakdown of what the undo history retains and per-step state digests for A/B parity.
 *
 * <p>Usage: {@code java ... org.pepsoft.worldpainter.TileHistoryBenchmark [profile] [runs] [warmups]} where profile is
 * {@code short}, {@code tall}, {@code noise} (poorly compressible short tiles), {@code focus} or {@code focus-tall}
 * (three quarters of the strokes repeatedly edit the four tiles around the origin).
 */
public final class TileHistoryBenchmark {
    public static void main(String[] args) throws Exception {
        final String profile = (args.length > 0) ? args[0] : "short";
        final int runs = (args.length > 1) ? Integer.parseInt(args[1]) : 5;
        final int warmups = (args.length > 2) ? Integer.parseInt(args[2]) : 2;
        final Scenario.Config config = Scenario.Config.forProfile(profile);
        System.out.println("profile=" + profile + " tiles=" + config.tileCount() + " strokes=" + config.strokes
                + " undoFrames=" + config.undoFrames + " heights=" + config.minHeight + ".." + config.maxHeight
                + " storage=" + TileStorageInfo.describe());
        for (int i = 0; i < warmups; i++) {
            new Scenario(config).run(false);
        }
        final List<Scenario.Result> results = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            final Scenario.Result result = new Scenario(config).run(i == 0);
            results.add(result);
            System.out.println("run " + i + ": " + result);
        }
        results.sort(Comparator.comparingLong(r -> r.editNanos));
        final Scenario.Result median = results.get(results.size() / 2);
        System.out.println("median: " + median);
        System.out.println("digest: " + median.digests);
        System.out.println("rssPeakBytes=" + peakWorkingSet());
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

    static final class Scenario {
        Scenario(Config config) {
            this.config = config;
            random = new Random(config.seed);
        }

        Result run(boolean analyse) throws Exception {
            final Result result = new Result();
            gc();
            final long heapBefore = usedHeap();
            final UndoManager undoManager = new UndoManager(config.undoFrames);
            grid = new Tile[config.maxTileY - config.minTileY + 1][config.maxTileX - config.minTileX + 1];
            for (int ty = config.minTileY; ty <= config.maxTileY; ty++) {
                for (int tx = config.minTileX; tx <= config.maxTileX; tx++) {
                    final Tile tile = new Tile(tx, ty, config.minHeight, config.maxHeight);
                    initialise(tile);
                    tile.addListener(events);
                    tiles.put(new Point(tx, ty), tile);
                    grid[ty - config.minTileY][tx - config.minTileX] = tile;
                    tile.register(undoManager);
                }
            }
            undoManager.savePoint();
            gc();
            result.initialRetained = usedHeap() - heapBefore;
            result.digests.add(digest());

            // Digests and snapshot reads are verification, not editing: their time and allocations are excluded
            final long allocatedBefore = allocatedBytes();
            final long start = System.nanoTime();
            Snapshot snapshot = null;
            Map<Point, TileSnapshot> snapshotTiles = null;
            long snapshotDigestBefore = 0;
            for (int stroke = 0; stroke < config.strokes; stroke++) {
                undoManager.armSavePoint();
                stroke(stroke);
                if (stroke == config.strokes / 3) {
                    // Like the editor: take a snapshot, then a save point so that it stays static
                    snapshot = undoManager.getSnapshot();
                    undoManager.savePoint();
                    final Map<Point, TileSnapshot> snapshots = new HashMap<>();
                    for (Map.Entry<Point, Tile> entry: tiles.entrySet()) {
                        snapshots.put(entry.getKey(), new TileSnapshot(entry.getValue(), snapshot));
                    }
                    snapshotTiles = snapshots;
                    // Read half of the snapshot tiles before further edits; the rest is read lazily afterwards
                    snapshotDigestBefore = verify(() -> digest(snapshots, true));
                }
                if ((stroke % 10) == 9) {
                    result.digests.add(verify(this::digest));
                }
            }
            final Map<Point, TileSnapshot> snapshots = snapshotTiles;
            final long snapshotDigestAfter = verify(() -> digest(snapshots, true));
            result.digests.add(verify(() -> digest(snapshots, false)));
            if (snapshotDigestAfter != snapshotDigestBefore) {
                throw new AssertionError("Snapshot changed after later edits");
            }
            result.strokeNanos = System.nanoTime() - start - verificationNanos;
            final long strokeAllocated = allocatedBytes() - allocatedBefore - verificationBytes;
            gc();
            result.historyWithSnapshot = usedHeap() - heapBefore;
            // The snapshot has been checked before and after the edits; release it so that what remains is the tiles
            // and their history
            snapshot = null;
            snapshotTiles = null;
            snapshots.clear();
            gc();
            result.historyRetained = usedHeap() - heapBefore;
            if (analyse) {
                result.breakdown = HistoryInspector.breakdown(undoManager);
            }

            // Undo/redo, then an edit that must invalidate the remaining redo frames
            verificationNanos = verificationBytes = 0;
            final long undoAllocatedBefore = allocatedBytes();
            final long undoStart = System.nanoTime();
            for (int i = 0; i < config.undos; i++) {
                if (!undoManager.undo()) {
                    throw new AssertionError("Undo " + i + " unavailable");
                }
                readAll();
            }
            result.digests.add(verify(this::digest));
            for (int i = 0; i < config.redos; i++) {
                if (!undoManager.redo()) {
                    throw new AssertionError("Redo " + i + " unavailable");
                }
                readAll();
            }
            result.digests.add(verify(this::digest));
            // Save/reload cycle while a history frame is current
            final byte[][] saved = new byte[1][];
            result.digests.add(verify(() -> {
                try {
                    saved[0] = serialise(tiles);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                return digest(deserialise(saved[0]), false);
            }));
            if (analyse && (System.getProperty("th.serialFile") != null)) {
                final java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("th.serialFile"));
                if (java.nio.file.Files.exists(file)) {
                    final long other = digest(deserialise(java.nio.file.Files.readAllBytes(file)), false);
                    System.out.println("serialCompat=" + ((other == digest(deserialise(saved[0]), false)) ? "OK" : "MISMATCH") + " (file written by another build, read by " + TileStorageInfo.describe() + ")");
                } else {
                    java.nio.file.Files.write(file, saved[0]);
                    System.out.println("serialCompat=written by " + TileStorageInfo.describe());
                }
            }
            saved[0] = null;
            undoManager.armSavePoint();
            stroke(config.strokes);
            if (undoManager.redo()) {
                throw new AssertionError("Redo still possible after a new edit");
            }
            result.digests.add(verify(this::digest));
            int undone = 0;
            while (undoManager.undo()) {
                undone++;
                readAll();
            }
            result.digests.add(verify(this::digest));
            result.digests.add((long) undone);
            result.digests.add(events.count);
            result.digests.add(events.hash);
            result.undoNanos = System.nanoTime() - undoStart - verificationNanos;
            result.editNanos = result.strokeNanos + result.undoNanos;
            result.allocatedBytes = strokeAllocated + allocatedBytes() - undoAllocatedBefore - verificationBytes;
            result.directBytes = directMemory();
            for (Tile tile: tiles.values()) {
                tile.unregister();
            }
            gc();
            result.afterUnregister = usedHeap() - heapBefore;
            result.tilePayload = payload(tiles.values());
            return result;
        }

        private static byte[] serialise(Map<Point, Tile> tiles) throws java.io.IOException {
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeInt(tiles.size());
                for (Tile tile: tiles.values()) {
                    out.writeObject(tile);
                }
            }
            return bytes.toByteArray();
        }

        private static Map<Point, Tile> deserialise(byte[] bytes) {
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
                final Map<Point, Tile> result = new TreeMap<>();
                for (int i = in.readInt(); i > 0; i--) {
                    final Tile tile = (Tile) in.readObject();
                    result.put(new Point(tile.getX(), tile.getY()), tile);
                }
                return result;
            } catch (java.io.IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        private long verify(java.util.function.LongSupplier verification) {
            final long allocated = allocatedBytes(), t0 = System.nanoTime();
            final long value = verification.getAsLong();
            verificationNanos += System.nanoTime() - t0;
            verificationBytes += allocatedBytes() - allocated;
            return value;
        }

        private static long payload(Collection<? extends Tile> tiles) {
            final Map<Object, Boolean> seen = new IdentityHashMap<>();
            long total = 0;
            for (Tile tile: tiles) {
                for (Object buffer: new Object[] {tile.heightMap, tile.tallHeightMap, tile.terrain, tile.waterLevel, tile.tallWaterLevel, tile.layerData, tile.bitLayerData}) {
                    total += TileStorageInfo.retainedBytes(buffer, seen);
                }
            }
            return total;
        }

        private void initialise(Tile tile) {
            final int tx = tile.getX(), ty = tile.getY();
            final int kind = Math.floorMod(tx * 7 + ty * 3, 6);
            tile.inhibitEvents();
            try {
                for (int y = 0; y < TILE_SIZE; y++) {
                    for (int x = 0; x < TILE_SIZE; x++) {
                        final int wx = tx * TILE_SIZE + x, wy = ty * TILE_SIZE + y;
                        tile.setHeight(x, y, terrainHeight(wx, wy));
                        if (kind != 5) {
                            tile.setWaterLevel(x, y, config.minHeight + 62);
                        } else {
                            tile.setWaterLevel(x, y, config.minHeight + 50 + ((wx ^ wy) & 15));
                        }
                        tile.setTerrain(x, y, TERRAINS[config.noise ? random.nextInt(TERRAINS.length) : Math.floorMod((wx >> 5) + (wy >> 6), TERRAINS.length)]);
                        if (kind != 0) {
                            tile.setLayerValue(Biome.INSTANCE, x, y, config.noise ? random.nextInt(64) : Math.floorMod((wx >> 4) * 3 + (wy >> 5), 50));
                        }
                        if ((kind == 1) || (kind == 2) || (kind == 4)) {
                            tile.setLayerValue(DeciduousForest.INSTANCE, x, y, config.noise ? random.nextInt(16) : Math.floorMod((wx + wy) >> 3, 16));
                        }
                        if (kind == 2) {
                            tile.setLayerValue(PineForest.INSTANCE, x, y, 7); // Uniform: shared buffer
                        }
                        if (kind == 3) {
                            tile.setLayerValue(Annotations.INSTANCE, x, y, Math.floorMod(wx >> 2, 16));
                        }
                        if ((kind == 1) || (kind == 3)) {
                            tile.setBitLayerValue(Frost.INSTANCE, x, y, config.noise ? random.nextBoolean() : (((wx >> 3) + (wy >> 3)) & 1) == 0);
                        }
                        if ((kind == 4) && ((x & 15) == 0) && ((y & 15) == 0)) {
                            tile.setBitLayerValue(Populate.INSTANCE, x, y, ((x + y) & 16) == 0);
                        }
                        if ((kind == 5) && (x < 32)) {
                            tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, true);
                        }
                    }
                }
            } finally {
                tile.releaseEvents();
            }
        }

        private float terrainHeight(int wx, int wy) {
            if (config.noise) {
                return config.minHeight + 40 + random.nextFloat() * (config.maxHeight - config.minHeight - 80);
            }
            final double h = 64 + 18 * Math.sin(wx / 37.0 + phase1) + 11 * Math.cos(wy / 23.0 + phase2)
                    + 6 * Math.sin((wx + wy) / 11.0) + random.nextFloat() * 0.4;
            return (float) (config.minHeight + h);
        }

        private void stroke(int index) {
            final int kind = index % 7;
            final int radius = 12 + random.nextInt(29);
            final boolean focused = config.focus && (random.nextInt(4) != 0);
            final int cx = focused ? (random.nextInt(2 * TILE_SIZE) - TILE_SIZE) : (config.minTileX * TILE_SIZE + random.nextInt((config.maxTileX - config.minTileX + 1) * TILE_SIZE));
            final int cy = focused ? (random.nextInt(2 * TILE_SIZE) - TILE_SIZE) : (config.minTileY * TILE_SIZE + random.nextInt((config.maxTileY - config.minTileY + 1) * TILE_SIZE));
            final int value = random.nextInt(16);
            final Set<Tile> touched = new LinkedHashSet<>();
            for (int wy = cy - radius; wy <= cy + radius; wy++) {
                for (int wx = cx - radius; wx <= cx + radius; wx++) {
                    final Tile tile = tileAt(wx, wy);
                    if ((tile != null) && touched.add(tile)) {
                        tile.inhibitEvents();
                    }
                }
            }
            try {
                for (int wy = cy - radius; wy <= cy + radius; wy++) {
                    for (int wx = cx - radius; wx <= cx + radius; wx++) {
                        final int dx = wx - cx, dy = wy - cy;
                        final double distance = Math.sqrt(dx * dx + dy * dy);
                        if (distance > radius) {
                            continue;
                        }
                        final Tile tile = tileAt(wx, wy);
                        if (tile == null) {
                            continue;
                        }
                        final int x = Math.floorMod(wx, TILE_SIZE), y = Math.floorMod(wy, TILE_SIZE);
                        final float strength = (float) (1.0 - distance / radius);
                        switch (kind) {
                            case 0 -> tile.setHeight(x, y, Math.min(config.maxHeight - 1, tile.getHeight(x, y) + 3 * strength));
                            case 1 -> tile.setLayerValue(DeciduousForest.INSTANCE, x, y, Math.min(15, Math.round(value * strength + 1)));
                            case 2 -> tile.setLayerValue(Biome.INSTANCE, x, y, value + 1);
                            case 3 -> tile.setTerrain(x, y, TERRAINS[value % TERRAINS.length]);
                            case 4 -> tile.setBitLayerValue(Frost.INSTANCE, x, y, strength > 0.3f);
                            case 5 -> tile.setWaterLevel(x, y, config.minHeight + 60 + value);
                            default -> {
                                tile.setHeight(x, y, Math.max(config.minHeight, tile.getHeight(x, y) - 2 * strength));
                                tile.setLayerValue(PineForest.INSTANCE, x, y, value);
                                if ((x & 15) == 0 && (y & 15) == 0) {
                                    tile.setBitLayerValue(Populate.INSTANCE, x, y, (value & 1) == 0);
                                }
                            }
                        }
                    }
                }
            } finally {
                touched.forEach(Tile::releaseEvents);
            }
        }

        private Tile tileAt(int wx, int wy) {
            final int tx = Math.floorDiv(wx, TILE_SIZE) - config.minTileX, ty = Math.floorDiv(wy, TILE_SIZE) - config.minTileY;
            return ((ty >= 0) && (ty < grid.length) && (tx >= 0) && (tx < grid[0].length)) ? grid[ty][tx] : null;
        }

        private void readAll() {
            // Rendering-like read of every tile after undo/redo
            long sink = 0;
            for (Tile tile: tiles.values()) {
                sink += tile.getRawHeight(5, 9) + tile.getWaterLevel(3, 4) + tile.getTerrain(7, 7).ordinal()
                        + tile.getLayerValue(Biome.INSTANCE, 1, 2) + (tile.getBitLayerValue(Frost.INSTANCE, 9, 9) ? 1 : 0);
            }
            blackhole ^= sink;
        }

        private long digest() {
            return digest(tiles, false);
        }

        private long digest(Map<Point, ? extends Tile> tileMap, boolean everyOther) {
            long hash = 1125899906842597L;
            int index = 0;
            for (Map.Entry<Point, ? extends Tile> entry: new TreeMap<>(tileMap).entrySet()) {
                if (everyOther && ((index++ & 1) != 0)) {
                    continue;
                }
                hash = 31 * hash + TileDigest.of(entry.getValue());
            }
            return hash;
        }

        private static void gc() throws InterruptedException {
            for (int i = 0; i < 4; i++) {
                System.gc();
                Thread.sleep(40);
            }
        }

        private static long usedHeap() {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }

        private static long allocatedBytes() {
            return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
        }

        private static long directMemory() {
            long total = 0;
            for (BufferPoolMXBean pool: ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                total += pool.getMemoryUsed();
            }
            return total;
        }

        private final Config config;
        private final Random random;
        private final double phase1 = 0.7, phase2 = 2.1;
        private final Map<Point, Tile> tiles = new TreeMap<>();
        private Tile[][] grid;
        private final EventLog events = new EventLog();
        private long verificationNanos, verificationBytes;
        static long blackhole;

        static final class Point implements Comparable<Point> {
            Point(int x, int y) { this.x = x; this.y = y; }
            @Override public boolean equals(Object o) { return (o instanceof Point p) && (p.x == x) && (p.y == y); }
            @Override public int hashCode() { return x * 31 + y; }
            @Override public int compareTo(Point o) { return (y != o.y) ? Integer.compare(y, o.y) : Integer.compare(x, o.x); }
            final int x, y;
        }

        static final class Config {
            static Config forProfile(String profile) {
                final Config config = new Config();
                switch (profile) {
                    case "short", "analyse" -> { }
                    case "tall" -> { config.minHeight = -64; config.maxHeight = 320; }
                    case "noise" -> config.noise = true;
                    case "focus" -> config.focus = true;
                    case "focus-tall" -> { config.focus = true; config.minHeight = -64; config.maxHeight = 320; }
                    default -> throw new IllegalArgumentException("Unknown profile " + profile);
                }
                return config;
            }

            int tileCount() {
                return (maxTileX - minTileX + 1) * (maxTileY - minTileY + 1);
            }

            long seed = 20261102L;
            int minTileX = -4, maxTileX = 3, minTileY = -3, maxTileY = 2;
            int minHeight = 0, maxHeight = 256;
            int strokes = 150, undoFrames = 101, undos = 30, redos = 10;
            boolean noise, focus;
        }

        static final class Result {
            @Override
            public String toString() {
                return String.format(Locale.ROOT, "edit=%.1fms (strokes=%.1fms undo/redo=%.1fms) alloc=%.1fMiB initial=%.1fMiB historyWithSnapshot=%.1fMiB history=%.1fMiB afterUnregister=%.1fMiB (tilePayload=%.1fMiB) direct=%dB%s",
                        editNanos / 1e6, strokeNanos / 1e6, undoNanos / 1e6, allocatedBytes / 1048576.0, initialRetained / 1048576.0,
                        historyWithSnapshot / 1048576.0, historyRetained / 1048576.0, afterUnregister / 1048576.0, tilePayload / 1048576.0, directBytes, (breakdown != null) ? ("\n" + breakdown) : "");
            }

            long editNanos, strokeNanos, undoNanos, allocatedBytes, initialRetained, historyRetained, afterUnregister, tilePayload, historyWithSnapshot, directBytes;
            String breakdown;
            final List<Long> digests = new ArrayList<>();

            void add(long value) { digests.add(value); }
        }
    }

    /**
     * Multiset hash of every tile event, to compare event semantics between implementations. Order-insensitive because
     * the undo manager notifies the buffers of an undone frame in the iteration order of an identity-hashed map, which
     * differs between JVM runs.
     */
    static final class EventLog implements Tile.Listener {
        private void add(Tile tile, int type, long extra) {
            long event = ((tile.getX() * 31L + tile.getY()) * 31 + type) * 1_000_003L + extra;
            event *= 0x9E3779B97F4A7C15L;
            hash += event ^ (event >>> 29);
            count++;
        }

        @Override public void heightMapChanged(Tile tile) { add(tile, 1, 0); }
        @Override public void terrainChanged(Tile tile) { add(tile, 2, 0); }
        @Override public void waterLevelChanged(Tile tile) { add(tile, 3, 0); }
        @Override public void layerDataChanged(Tile tile, Set<Layer> changedLayers) {
            final List<String> ids = new ArrayList<>();
            changedLayers.forEach(layer -> ids.add(layer.getId()));
            Collections.sort(ids);
            add(tile, 4, ids.hashCode());
        }
        @Override public void allBitLayerDataChanged(Tile tile) { add(tile, 5, 0); }
        @Override public void allNonBitlayerDataChanged(Tile tile) { add(tile, 6, 0); }
        @Override public void seedsChanged(Tile tile) { add(tile, 7, 0); }

        long hash = 17, count;
    }

    static final class TileDigest {
        static long of(Tile tile) {
            long hash = 17;
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    hash = hash * 31 + tile.getRawHeight(x, y);
                    hash = hash * 31 + tile.getWaterLevel(x, y);
                    hash = hash * 31 + tile.getTerrain(x, y).ordinal();
                    for (Layer layer: NUMERIC_LAYERS) {
                        hash = hash * 31 + tile.getLayerValue(layer, x, y);
                    }
                    for (Layer layer: BIT_LAYERS) {
                        hash = hash * 31 + (tile.getBitLayerValue(layer, x, y) ? 1 : 0);
                    }
                }
            }
            final List<Layer> layers = new ArrayList<>(tile.getLayers());
            layers.sort(Comparator.comparing(Layer::getId));
            return hash * 31 + layers.toString().hashCode();
        }
    }

    /** Diagnostic only: reads the private history of the compiled UndoManager to attribute retained buffers. */
    static final class HistoryInspector {
        @SuppressWarnings("unchecked")
        static String breakdown(UndoManager undoManager) throws Exception {
            final Field historyField = UndoManager.class.getDeclaredField("history");
            historyField.setAccessible(true);
            final List<Map<BufferKey<?>, Object>> history = (List<Map<BufferKey<?>, Object>>) historyField.get(undoManager);
            final Map<Object, Boolean> seen = new IdentityHashMap<>();
            final Map<String, long[]> perType = new TreeMap<>();
            // Pages (16x16 cells) identical to the same page of the next version of the same buffer
            final Map<BufferKey<?>, Object> next = new HashMap<>();
            long pages = 0, unchangedPages = 0;
            for (int frame = history.size() - 1; frame >= 0; frame--) {
                for (Map.Entry<BufferKey<?>, Object> entry: history.get(frame).entrySet()) {
                    final String type = (entry.getKey() instanceof Tile.TileUndoBufferKey<?> key) ? key.buffer.name() : entry.getKey().getClass().getSimpleName();
                    final long[] stats = perType.computeIfAbsent(type, k -> new long[3]);
                    stats[0]++;
                    stats[1] += TileStorageInfo.retainedBytes(entry.getValue(), seen);
                    final Object newer = next.put(entry.getKey(), entry.getValue());
                    if (newer != null) {
                        final long[] pageStats = TileStorageInfo.comparePages(entry.getValue(), newer);
                        pages += pageStats[0];
                        unchangedPages += pageStats[1];
                    }
                }
            }
            final StringBuilder sb = new StringBuilder("history frames=" + history.size());
            long total = 0;
            for (Map.Entry<String, long[]> entry: perType.entrySet()) {
                sb.append(String.format(Locale.ROOT, "\n  %-16s versions=%5d uniqueBytes=%10d", entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
                total += entry.getValue()[1];
            }
            sb.append(String.format(Locale.ROOT, "\n  total uniqueBytes=%d (%.1fMiB); 16x16 pages identical to the next version: %d/%d (%.1f%%)",
                    total, total / 1048576.0, unchangedPages, pages, (pages > 0) ? (100.0 * unchangedPages / pages) : 0.0));
            return sb.toString();
        }
    }

    private static final Terrain[] TERRAINS = {Terrain.GRASS, Terrain.SAND, Terrain.STONE, Terrain.GRAVEL, Terrain.DIRT, Terrain.PERMADIRT, Terrain.SNOW};
    private static final Layer[] NUMERIC_LAYERS = {Biome.INSTANCE, DeciduousForest.INSTANCE, PineForest.INSTANCE, Annotations.INSTANCE};
    private static final Layer[] BIT_LAYERS = {Frost.INSTANCE, Populate.INSTANCE, FloodWithLava.INSTANCE};
}
