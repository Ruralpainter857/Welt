package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.Snapshot;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;

import java.io.*;
import java.util.*;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE;

/**
 * Checks the tile undo history against an independent model of the logical state of every frame: after every edit,
 * save point, undo, redo, pruning, snapshot and serialisation the complete state of every tile (heights, water,
 * terrain, every numeric and bit layer value, layer presence) must equal the model. Runs on short, tall and very tall
 * tiles with negative coordinates, implicit and uniform (shared) layers, and history limits small enough to prune.
 */
public class TileHistoryStorageTest {
    @Test
    public void shortTilesMatchModel() throws Exception {
        new Script(0, 256, 1L).run(260);
    }

    @Test
    public void tallTilesMatchModel() throws Exception {
        new Script(-64, 320, 2L).run(200);
    }

    @Test
    public void veryTallTilesMatchModel() throws Exception {
        new Script(-1024, 3072, 3L).run(140);
    }

    /** Editing one byte layer after a save point copies that layer only; the others stay shared with history. */
    @Test
    public void layerEditCopiesOnlyThatLayer() {
        org.junit.Assume.assumeTrue(TileHistoryStorage.ENABLED);
        final Tile tile = new Tile(-1, -1, 0, 256);
        fill(tile, Biome.INSTANCE, 9);
        fill(tile, DeciduousForest.INSTANCE, 5);
        final UndoManager undoManager = new UndoManager(10);
        tile.register(undoManager);
        undoManager.armSavePoint();
        final byte[] biomeBefore = tile.layerData.get(Biome.INSTANCE), forestBefore = tile.layerData.get(DeciduousForest.INSTANCE);
        tile.setLayerValue(DeciduousForest.INSTANCE, 3, 4, 11);
        assertSame("untouched layer must remain shared with history", biomeBefore, tile.layerData.get(Biome.INSTANCE));
        assertNotSame(forestBefore, tile.layerData.get(DeciduousForest.INSTANCE));
        tile.setLayerValue(DeciduousForest.INSTANCE, 4, 4, 12);
        final byte[] forestEdited = tile.layerData.get(DeciduousForest.INSTANCE);
        tile.setLayerValue(DeciduousForest.INSTANCE, 5, 4, 13);
        assertSame("second write in the same frame must not copy again", forestEdited, tile.layerData.get(DeciduousForest.INSTANCE));
        assertTrue(undoManager.undo());
        assertEquals(5, tile.getLayerValue(DeciduousForest.INSTANCE, 3, 4));
        assertEquals(9, tile.getLayerValue(Biome.INSTANCE, 3, 4));
        assertTrue(undoManager.redo());
        assertEquals(13, tile.getLayerValue(DeciduousForest.INSTANCE, 5, 4));
        tile.unregister();
    }

    /** History versions share 16x16 pages that did not change with the previous history version. */
    @Test
    public void historyVersionsSharePagesWithPreviousVersion() {
        org.junit.Assume.assumeTrue(TileHistoryStorage.ENABLED);
        final Tile tile = new Tile(2, -3, -64, 320);
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                tile.setHeight(x, y, (x * 7 + y * 3) % 200 - 30.5f);
            }
        }
        final UndoManager undoManager = new UndoManager(10);
        tile.register(undoManager);
        final List<TileHistoryStorage.GridVersion> versions = new ArrayList<>();
        for (int edit = 0; edit < 3; edit++) {
            versions.add(undoManager.getBuffer(new Tile.TileUndoBufferKey<>(tile, Tile.TileBuffer.TALL_HEIGHTMAP)));
            undoManager.armSavePoint();
            tile.setHeight(20 + edit, 20, 100.25f + edit); // Page (1, 1) only
        }
        // versions.get(1) was cooled after versions.get(0): all pages but (1, 1) are the same objects
        final Object[] older = pages(versions.get(0)), newer = pages(versions.get(1));
        for (int page = 0; page < 64; page++) {
            if (page == 9) {
                assertNotSame(older[page], newer[page]);
            } else {
                assertSame("page " + page, older[page], newer[page]);
            }
        }
        tile.unregister();
    }

    /** A layer array no longer used by the editable version is stored as pages in every history version holding it. */
    @Test
    public void retiredLayerArraysArePagedInAllHolders() {
        org.junit.Assume.assumeTrue(TileHistoryStorage.ENABLED);
        final Tile tile = new Tile(0, 0, 0, 256);
        fill(tile, Biome.INSTANCE, 9);
        fill(tile, PineForest.INSTANCE, 3);
        final UndoManager undoManager = new UndoManager(10);
        tile.register(undoManager);
        final byte[] biome = tile.layerData.get(Biome.INSTANCE);
        // Two frames in which only the pine forest changes: the biome array is shared by three versions
        for (int i = 0; i < 2; i++) {
            undoManager.armSavePoint();
            tile.setLayerValue(PineForest.INSTANCE, i, 0, 7 + i);
        }
        undoManager.armSavePoint();
        tile.setLayerValue(Biome.INSTANCE, 0, 0, 1);
        assertNotSame(biome, tile.layerData.get(Biome.INSTANCE));
        // Undo to the first frame: the biome layer reads back unchanged although its array is now paged
        assertTrue(undoManager.undo());
        assertTrue(undoManager.undo());
        assertTrue(undoManager.undo());
        System.gc();
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                assertEquals(9, tile.getLayerValue(Biome.INSTANCE, x, y));
                assertEquals(3, tile.getLayerValue(PineForest.INSTANCE, x, y));
            }
        }
        tile.unregister();
    }

    /** Saving while a history frame is current drops all-default layers there for good, as before. */
    @Test
    public void defaultLayerRemovalOnSaveSticksInHistoryFrame() throws Exception {
        final Tile tile = new Tile(-5, 6, 0, 256);
        fill(tile, Annotations.INSTANCE, 4);
        final UndoManager undoManager = new UndoManager(10);
        tile.register(undoManager);
        undoManager.armSavePoint();
        tile.setLayerValue(Annotations.INSTANCE, 1, 1, 6);
        undoManager.armSavePoint();
        fill(tile, Annotations.INSTANCE, 0); // All default
        assertTrue(tile.hasLayer(Annotations.INSTANCE));
        serialise(tile);
        assertFalse(tile.hasLayer(Annotations.INSTANCE));
        assertTrue(undoManager.undo());
        assertTrue(tile.hasLayer(Annotations.INSTANCE));
        assertTrue(undoManager.redo());
        System.gc();
        assertTrue(undoManager.undo());
        assertTrue(undoManager.redo());
        assertFalse("removal on save must persist in the frame", tile.hasLayer(Annotations.INSTANCE));
        tile.unregister();
    }

    /** Versions of frames pruned from the history, cleared or unregistered are not retained by anything else. */
    @Test
    public void prunedAndUnregisteredVersionsAreReleased() throws Exception {
        final Tile tile = new Tile(-7, -7, -64, 320);
        fill(tile, Biome.INSTANCE, 3);
        // Private (not globally shared default) height buffer, so that its release can be observed in both modes
        tile.setHeight(100, 100, 5);
        final UndoManager undoManager = new UndoManager(3);
        tile.register(undoManager);
        final Tile.TileUndoBufferKey<Object> heightKey = new Tile.TileUndoBufferKey<>(tile, Tile.TileBuffer.TALL_HEIGHTMAP);
        final Tile.TileUndoBufferKey<Object> layerKey = new Tile.TileUndoBufferKey<>(tile, Tile.TileBuffer.LAYER_DATA);
        final List<java.lang.ref.WeakReference<Object>> early = new ArrayList<>();
        for (int edit = 0; edit < 12; edit++) {
            if (edit < 2) {
                early.add(new java.lang.ref.WeakReference<>(undoManager.getBuffer(heightKey)));
                early.add(new java.lang.ref.WeakReference<>(undoManager.getBuffer(layerKey)));
            }
            undoManager.armSavePoint();
            tile.setHeight(edit, edit, 10 + edit);
            tile.setLayerValue(Biome.INSTANCE, edit, 0, 20 + edit);
        }
        assertReleased("pruned frames", early);
        final java.lang.ref.WeakReference<Object> current = new java.lang.ref.WeakReference<>(undoManager.getBuffer(heightKey));
        final java.lang.ref.WeakReference<Object> currentLayers = new java.lang.ref.WeakReference<>(undoManager.getBuffer(layerKey));
        tile.unregister();
        if (TileHistoryStorage.ENABLED) {
            // Without compact storage the current buffers are the tile's own arrays, which it keeps
            assertReleased("unregistered tile", Arrays.asList(current, currentLayers));
        }
        // The tile keeps its data after unregistering
        assertEquals(21, tile.getHeight(11, 11), 0f);
        assertEquals(31, tile.getLayerValue(Biome.INSTANCE, 11, 0));
        assertEquals(3, tile.getLayerValue(Biome.INSTANCE, 11, 1));
    }

    private static void assertReleased(String message, List<java.lang.ref.WeakReference<Object>> references) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
            if (references.stream().allMatch(reference -> reference.get() == null)) {
                return;
            }
            Thread.sleep(25);
        }
        fail(message + ": history versions still reachable");
    }

    private static Object[] pages(TileHistoryStorage.GridVersion version) {
        try {
            final java.lang.reflect.Field field = TileHistoryStorage.GridVersion.class.getDeclaredField("pages");
            field.setAccessible(true);
            final Object[] pages = (Object[]) field.get(version);
            assertNotNull("version should be cold", pages);
            return pages;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void fill(Tile tile, Layer layer, int value) {
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                tile.setLayerValue(layer, x, y, value);
            }
        }
    }

    static Tile serialise(Tile tile) throws IOException, ClassNotFoundException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(tile);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Tile) in.readObject();
        }
    }

    static final Layer[] NUMERIC = {Biome.INSTANCE, DeciduousForest.INSTANCE, PineForest.INSTANCE, Annotations.INSTANCE};
    static final Layer[] BITS = {Frost.INSTANCE, Populate.INSTANCE, FloodWithLava.INSTANCE};
    static final Terrain[] TERRAINS = {Terrain.GRASS, Terrain.SAND, Terrain.STONE, Terrain.SNOW, Terrain.BEDROCK};

    /** Logical content of one tile: one int per cell per plane. */
    static final class State {
        State(Tile tile) {
            planes = new int[3 + NUMERIC.length + BITS.length][TILE_SIZE * TILE_SIZE];
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    final int i = x + y * TILE_SIZE;
                    planes[0][i] = tile.getRawHeight(x, y);
                    planes[1][i] = tile.getWaterLevel(x, y);
                    planes[2][i] = tile.getTerrain(x, y).ordinal();
                    for (int l = 0; l < NUMERIC.length; l++) {
                        planes[3 + l][i] = tile.getLayerValue(NUMERIC[l], x, y);
                    }
                    for (int l = 0; l < BITS.length; l++) {
                        planes[3 + NUMERIC.length + l][i] = tile.getBitLayerValue(BITS[l], x, y) ? 1 : 0;
                    }
                }
            }
            layers = new HashSet<>(tile.getLayers());
        }

        State(State other) {
            planes = new int[other.planes.length][];
            for (int p = 0; p < planes.length; p++) {
                planes[p] = other.planes[p].clone();
            }
            layers = new HashSet<>(other.layers);
        }

        void assertMatches(String message, Tile tile) {
            final State actual = new State(tile);
            for (int p = 0; p < planes.length; p++) {
                if (! Arrays.equals(planes[p], actual.planes[p])) {
                    int i = Arrays.mismatch(planes[p], actual.planes[p]);
                    fail(message + ": plane " + p + " differs at " + (i % TILE_SIZE) + "," + (i / TILE_SIZE) + ": expected " + planes[p][i] + " but was " + actual.planes[p][i]);
                }
            }
            assertEquals(message + ": layers", layers, actual.layers);
        }

        final int[][] planes;
        final Set<Layer> layers;
    }

    /** Random edit script mirrored by a model of the undo manager's frames. */
    static final class Script {
        Script(int minHeight, int maxHeight, long seed) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            random = new Random(seed);
            tiles = new Tile[] {new Tile(-2, -1, minHeight, maxHeight), new Tile(-1, 0, minHeight, maxHeight), new Tile(3, -4, minHeight, maxHeight)};
        }

        void run(int steps) throws Exception {
            for (int t = 0; t < tiles.length; t++) {
                initialise(tiles[t], t);
            }
            frames.add(capture());
            for (Tile tile: tiles) {
                tile.register(undoManager);
            }
            check("registered");
            for (int step = 0; step < steps; step++) {
                final int op = random.nextInt(100);
                final String label = "step " + step;
                if (op < 55) {
                    undoManager.armSavePoint();
                    armed = true;
                    edit(label);
                } else if (op < 63) {
                    edit(label + " (no save point)");
                } else if (op < 75) {
                    final boolean done = undoManager.undo();
                    assertEquals(label + " undo", current > 0, done);
                    if (done) {
                        current--;
                    }
                    check(label + " after undo");
                } else if (op < 83) {
                    final boolean done = undoManager.redo();
                    assertEquals(label + " redo", current < frames.size() - 1, done);
                    if (done) {
                        current++;
                    }
                    check(label + " after redo");
                } else if (op < 89) {
                    takeSnapshot(random.nextBoolean());
                } else if (op < 93) {
                    System.gc(); // Drops weakly cached assembled history arrays
                    checkSnapshots(label + " after gc");
                } else if (op < 97) {
                    final int t = random.nextInt(tiles.length);
                    final Tile copy = serialise(tiles[t]);
                    // prepareForSaving() may drop all-default layers from the current frame, without changing values
                    final State before = frames.get(current)[t], after = new State(tiles[t]);
                    for (int p = 0; p < before.planes.length; p++) {
                        assertArrayEquals(label + " values changed by saving, plane " + p, before.planes[p], after.planes[p]);
                    }
                    frames.get(current)[t] = after;
                    after.assertMatches(label + " serialised copy", copy);
                    check(label + " after serialisation");
                } else {
                    checkSnapshots(label);
                }
                if ((step % 7) == 0) {
                    check(label);
                }
            }
            check("end");
            checkSnapshots("end");
            for (Tile tile: tiles) {
                tile.unregister();
            }
            for (int t = 0; t < tiles.length; t++) {
                frames.get(current)[t].assertMatches("after unregister", tiles[t]);
            }
        }

        private void initialise(Tile tile, int index) {
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    tile.setHeight(x, y, minHeight + 50 + ((x * 3 + y * 5 + index * 11) % 90) + 0.5f);
                    tile.setWaterLevel(x, y, minHeight + 62);
                    if (index != 0) {
                        tile.setTerrain(x, y, TERRAINS[((x >> 4) + (y >> 5)) % TERRAINS.length]);
                        tile.setLayerValue(Biome.INSTANCE, x, y, (x + y) & 63);
                    }
                    if (index == 1) {
                        tile.setLayerValue(PineForest.INSTANCE, x, y, 7); // Uniform: shared buffer
                        tile.setBitLayerValue(Frost.INSTANCE, x, y, ((x ^ y) & 4) == 0);
                    }
                    if ((index == 2) && ((x & 15) == 0) && ((y & 15) == 0)) {
                        tile.setBitLayerValue(Populate.INSTANCE, x, y, true);
                    }
                }
            }
        }

        private void edit(String label) {
            if (! armed) {
                // An edit in place of an existing frame: snapshots of that frame are not static (UndoManager only
                // promises that with a save point after the snapshot), so stop checking them
                snapshots.removeIf(snapshot -> snapshot.frame == current);
            }
            if (armed) {
                // The first write executes the armed save point
                clearRedo();
                frames.add(copy(frames.get(current)));
                current++;
                prune();
                armed = false;
            }
            clearRedo();
            final int t = random.nextInt(tiles.length);
            final Tile tile = tiles[t];
            final State state = frames.get(current)[t];
            final int kind = random.nextInt(9);
            final int size = 1 + random.nextInt(24);
            final int x0 = random.nextInt(TILE_SIZE - size), y0 = random.nextInt(TILE_SIZE - size);
            final int value = random.nextInt(1 << 16);
            tile.inhibitEvents();
            try {
                if (kind == 8) {
                    // Remove a whole layer, when present
                    final Layer layer = random.nextBoolean() ? NUMERIC[random.nextInt(NUMERIC.length)] : BITS[random.nextInt(BITS.length)];
                    if (tile.hasLayer(layer)) {
                        tile.clearLayerData(layer);
                        final boolean bit = (layer.getDataSize() == Layer.DataSize.BIT) || (layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK);
                        Arrays.fill(state.planes[bit ? 3 + NUMERIC.length + indexOf(BITS, layer) : 3 + indexOf(NUMERIC, layer)], bit ? 0 : layer.getDefaultValue());
                        state.layers.remove(layer);
                        return;
                    }
                }
                for (int y = y0; y < y0 + size; y++) {
                    for (int x = x0; x < x0 + size; x++) {
                        final int i = x + y * TILE_SIZE;
                        switch (kind) {
                            case 0 -> {
                                final int raw = Math.floorMod(state.planes[0][i] + 1 + (value & 0xfff), (maxHeight - minHeight - 1) * 256);
                                tile.setRawHeight(x, y, raw);
                                state.planes[0][i] = raw;
                            }
                            case 1 -> {
                                final int level = minHeight + Math.floorMod(state.planes[1][i] - minHeight + 1 + (value & 15), maxHeight - minHeight);
                                tile.setWaterLevel(x, y, level);
                                state.planes[1][i] = level;
                            }
                            case 2 -> {
                                Terrain terrain = TERRAINS[Math.floorMod(value + x, TERRAINS.length)];
                                if (terrain.ordinal() == state.planes[2][i]) {
                                    terrain = TERRAINS[Math.floorMod(value + x + 1, TERRAINS.length)];
                                }
                                tile.setTerrain(x, y, terrain);
                                state.planes[2][i] = terrain.ordinal();
                            }
                            case 3, 4, 5 -> {
                                final Layer layer = NUMERIC[(kind == 3) ? 0 : (kind == 4) ? 1 + (value & 1) : 3];
                                int layerValue = (value + x) % (layer.getDataSize().maxValue + 1);
                                if (layerValue == state.planes[3 + indexOf(NUMERIC, layer)][i]) {
                                    layerValue = (layerValue + 1) % (layer.getDataSize().maxValue + 1);
                                }
                                tile.setLayerValue(layer, x, y, layerValue);
                                state.planes[3 + indexOf(NUMERIC, layer)][i] = layerValue;
                                if (layerValue != layer.getDefaultValue()) {
                                    state.layers.add(layer);
                                }
                            }
                            default -> {
                                final Layer layer = BITS[kind - 6 + ((value & 1) * (kind == 7 ? 1 : 0))];
                                final int plane = 3 + NUMERIC.length + indexOf(BITS, layer);
                                // The first cell always changes, so that the edit is a real write
                                final boolean bit = ((x == x0) && (y == y0)) ? (state.planes[plane][i] == 0) : (((value + x + y) & 3) != 0);
                                tile.setBitLayerValue(layer, x, y, bit);
                                if (layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK) {
                                    for (int cy = y & ~15; cy < (y & ~15) + 16; cy++) {
                                        for (int cx = x & ~15; cx < (x & ~15) + 16; cx++) {
                                            state.planes[plane][cx + cy * TILE_SIZE] = bit ? 1 : 0;
                                        }
                                    }
                                } else {
                                    state.planes[plane][i] = bit ? 1 : 0;
                                }
                                if (bit) {
                                    state.layers.add(layer);
                                }
                            }
                        }
                    }
                }
            } finally {
                tile.releaseEvents();
            }
            if (random.nextInt(4) == 0) {
                check(label);
            } else {
                state.assertMatches(label + " tile " + t, tile);
            }
        }

        private void takeSnapshot(boolean readNow) {
            // As the editor does: snapshot, then a save point so that it stays static
            final Snapshot snapshot = undoManager.getSnapshot();
            final TileSnapshot[] views = new TileSnapshot[tiles.length];
            for (int t = 0; t < tiles.length; t++) {
                views[t] = new TileSnapshot(tiles[t], snapshot);
            }
            final LiveSnapshot live = new LiveSnapshot(views, current);
            snapshots.add(live);
            clearRedo();
            undoManager.savePoint();
            frames.add(copy(frames.get(current)));
            current++;
            prune();
            armed = false;
            if (readNow) {
                checkSnapshot("snapshot read before edits", live);
            }
        }

        private void checkSnapshots(String label) {
            for (LiveSnapshot snapshot: snapshots) {
                checkSnapshot(label, snapshot);
            }
        }

        private void checkSnapshot(String label, LiveSnapshot snapshot) {
            for (int t = 0; t < tiles.length; t++) {
                frames.get(snapshot.frame)[t].assertMatches(label + ": snapshot of frame " + snapshot.frame + " tile " + t, snapshot.views[t]);
            }
        }

        private void clearRedo() {
            while (frames.size() - 1 > current) {
                frames.remove(frames.size() - 1);
            }
            snapshots.removeIf(snapshot -> snapshot.frame >= frames.size());
        }

        private void prune() {
            while (frames.size() > MAX_FRAMES) {
                frames.remove(0);
                current--;
                for (Iterator<LiveSnapshot> i = snapshots.iterator(); i.hasNext(); ) {
                    final LiveSnapshot snapshot = i.next();
                    if (--snapshot.frame < 0) {
                        i.remove();
                    }
                }
            }
        }

        private void check(String label) {
            for (int t = 0; t < tiles.length; t++) {
                frames.get(current)[t].assertMatches(label + " tile " + t, tiles[t]);
            }
        }

        private State[] capture() {
            final State[] states = new State[tiles.length];
            for (int t = 0; t < tiles.length; t++) {
                states[t] = new State(tiles[t]);
            }
            return states;
        }

        private static State[] copy(State[] states) {
            final State[] copy = new State[states.length];
            for (int t = 0; t < states.length; t++) {
                copy[t] = new State(states[t]);
            }
            return copy;
        }

        private static int indexOf(Layer[] layers, Layer layer) {
            for (int i = 0; i < layers.length; i++) {
                if (layers[i] == layer) {
                    return i;
                }
            }
            throw new IllegalArgumentException();
        }

        private final int minHeight, maxHeight;
        private final Random random;
        private final Tile[] tiles;
        private final UndoManager undoManager = new UndoManager(MAX_FRAMES);
        private final List<State[]> frames = new ArrayList<>();
        private final List<LiveSnapshot> snapshots = new ArrayList<>();
        private int current;
        private boolean armed;

        private static final int MAX_FRAMES = 6;
    }

    static final class LiveSnapshot {
        LiveSnapshot(TileSnapshot[] views, int frame) {
            this.views = views;
            this.frame = frame;
        }

        final TileSnapshot[] views;
        int frame;
    }
}
