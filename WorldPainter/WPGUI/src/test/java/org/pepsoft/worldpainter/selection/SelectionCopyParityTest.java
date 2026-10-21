package org.pepsoft.worldpainter.selection;

import java.util.*;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Independent scalar oracle preserving read/write order during overlapping copies. */
public class SelectionCopyParityTest {
    private static final Set<Layer> SKIP = Set.of(Biome.INSTANCE, SelectionChunk.INSTANCE, SelectionBlock.INSTANCE,
            NotPresent.INSTANCE, NotPresentBlock.INSTANCE, Annotations.INSTANCE, FloodWithLava.INSTANCE);
    static void scalar(Dimension d, SelectionOptions o, int dx, int dy) {
        SelectionHelper helper = new SelectionHelper(d); var b = helper.getSelectionBounds();
        int x1 = b.x >> 7, x2 = (b.x + b.width - 1) >> 7, y1 = b.y >> 7, y2 = (b.y + b.height - 1) >> 7;
        for (int ix = 0; ix <= x2 - x1; ix++) for (int iy = 0; iy <= y2 - y1; iy++) {
            int tx = dx > 0 ? x2 - ix : x1 + ix, ty = dy > 0 ? y2 - iy : y1 + iy;
            Tile t = d.getTile(tx, ty); if (t == null) continue;
            for (int ixp = 0; ixp < 128; ixp++) for (int iyp = 0; iyp < 128; iyp++) {
                int x = dx > 0 ? 127 - ixp : ixp, y = dy > 0 ? 127 - iyp : iyp;
                if (!t.getBitLayerValue(SelectionChunk.INSTANCE, x, y) && !t.getBitLayerValue(SelectionBlock.INSTANCE, x, y)) continue;
                int wx = (tx << 7) + x + dx, wy = (ty << 7) + y + dy;
                if (o.copyHeights) d.setRawHeightAt(wx, wy, t.getRawHeight(x, y));
                if (o.copyTerrain) d.setTerrainAt(wx, wy, t.getTerrain(x, y));
                if (o.copyFluids) {
                    d.setWaterLevelAt(wx, wy, t.getWaterLevel(x, y));
                    d.setBitLayerValueAt(FloodWithLava.INSTANCE, wx, wy, t.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
                }
                if (o.copyLayers) {
                    if (o.removeExistingLayers) d.clearLayerData(wx, wy, SKIP);
                    Map<Layer, Integer> values = t.getLayersAt(x, y);
                    values.forEach((layer, value) -> {
                        if (SKIP.contains(layer)) return;
                        if (layer.dataSize == Layer.DataSize.BIT || layer.dataSize == Layer.DataSize.BIT_PER_CHUNK)
                            d.setBitLayerValueAt(layer, wx, wy, value != 0);
                        else d.setLayerValueAt(layer, wx, wy, value);
                    });
                }
                if (o.copyAnnotations) d.setLayerValueAt(Annotations.INSTANCE, wx, wy, t.getLayerValue(Annotations.INSTANCE, x, y));
                if (o.copyBiomes) d.setLayerValueAt(Biome.INSTANCE, wx, wy, t.getLayerValue(Biome.INSTANCE, x, y));
            }
        }
    }
    static void same(Dimension a, Dimension b) {
        assertEquals(a.getTileCoords(), b.getTileCoords());
        for (Tile ta : a.getTiles()) {
            Tile tb = b.getTile(ta.getX(), ta.getY()); assertEquals(ta.getLayers(), tb.getLayers());
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                assertEquals(ta.getRawHeight(x, y), tb.getRawHeight(x, y));
                assertEquals(ta.getWaterLevel(x, y), tb.getWaterLevel(x, y));
                assertEquals(ta.getTerrain(x, y), tb.getTerrain(x, y));
                for (Layer l : ta.getLayers()) {
                    if (l.dataSize == Layer.DataSize.BIT || l.dataSize == Layer.DataSize.BIT_PER_CHUNK)
                        assertEquals(ta.getBitLayerValue(l, x, y), tb.getBitLayerValue(l, x, y));
                    else assertEquals(ta.getLayerValue(l, x, y), tb.getLayerValue(l, x, y));
                }
            }
        }
    }
    @Test public void overlappingCopiesKeepAllPlanesAndMutationOrder() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int dx : new int[] {-129, -17, 0, 17, 129}) for (int dy : new int[] {-19, 0, 19}) {
                if (dx == 0 && dy == 0) continue;
                for (boolean clear : new boolean[] {false, true}) {
                    Dimension a = SelectionCopyBenchmark.fixture(2), b = SelectionCopyBenchmark.fixture(2);
                    SelectionOptions o = new SelectionOptions(); o.setCopyAnnotations(true); o.setRemoveExistingLayers(clear);
                    a.setEventsInhibited(true); scalar(a, o, dx, dy); a.setEventsInhibited(false);
                    SelectionCopyBenchmark.copy(b, o, dx, dy, true); same(a, b);
                }
            }
        } finally { restore(old); }
    }
    @Test public void copyOptionsAndMissingTilesKeepTheUntouchedPlanes() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int mode = 0; mode < 6; mode++) {
                Dimension a = SelectionCopyBenchmark.fixture(2), b = SelectionCopyBenchmark.fixture(2);
                a.removeTile(0, 0); b.removeTile(0, 0);
                SelectionOptions o = new SelectionOptions(); o.setCopyHeights(mode == 0); o.setCopyTerrain(mode == 1);
                o.setCopyFluids(mode == 2); o.setCopyLayers(mode == 3); o.setCopyBiomes(mode == 4); o.setCopyAnnotations(mode == 5);
                a.setEventsInhibited(true); scalar(a, o, 17, -19); a.setEventsInhibited(false);
                SelectionCopyBenchmark.copy(b, o, 17, -19, true); same(a, b);
            }
        } finally { restore(old); }
    }
    @Test public void copyRetainsUndoAndRedoForEveryPlane() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = SelectionCopyBenchmark.fixture(2), a = SelectionCopyBenchmark.fixture(2), b = SelectionCopyBenchmark.fixture(2);
            SelectionOptions o = new SelectionOptions(); o.setCopyAnnotations(true);
            UndoManager undo = new UndoManager(); b.registerUndoManager(undo); undo.armSavePoint();
            SelectionCopyBenchmark.copy(a, o, 17, -19, false); SelectionCopyBenchmark.copy(b, o, 17, -19, true); same(a, b);
            assertTrue(undo.undo()); same(before, b); assertTrue(undo.redo()); same(a, b);
        } finally { restore(old); }
    }
    @Test public void preparedPlanUsesOneNativeCallPerSourceTile() throws Exception {
        org.junit.Assume.assumeTrue(org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension expected = SelectionCopyBenchmark.fixture(2), actual = SelectionCopyBenchmark.fixture(2);
            SelectionOptions o = new SelectionOptions(); o.setCopyAnnotations(true);
            SelectionCopyBenchmark.copy(expected, o, 17, -19, false);
            System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            var plan = org.pepsoft.worldpainter.SelectionCopyAccess.prepare(actual, 17, -19, true, true, true, true, true, true, true);
            assertNotNull(plan);
            for (int tx = 0; tx >= -1; tx--) for (int ty = -1; ty <= 0; ty++) assertTrue(plan.copyTile(actual.getTile(tx, ty), 17, -19));
            actual.setEventsInhibited(false); assertEquals(4, plan.getNativeCalls()); same(expected, actual);
        } finally { restore(old); }
    }

    @Test public void absentPlanesAndChunkDefaultsKeepLayerPresence() throws Exception {
        String old = System.getProperty(Native.GEN_KEY);
        Layer bitDefault = new Layer("welt.test.copy.bitdefault", "BitDefault", "", Layer.DataSize.BIT_PER_CHUNK, false, 32) {
            @Override public int getDefaultValue() { return 1; }
        };
        try {
            for (int dx : new int[] {17, 129}) {
                Dimension expected = SelectionCopyBenchmark.fixture(2), actual = SelectionCopyBenchmark.fixture(2);
                for (Dimension d : new Dimension[] {expected, actual}) {
                    d.getTile(0, 0).clearLayerData(Resources.INSTANCE);
                    d.getTile(0, 0).clearLayerData(SelectionCopyBenchmark.DEFAULT);
                    d.getTile(0, 0).clearLayerData(Frost.INSTANCE);
                    d.getTile(0, 0).clearLayerData(Biome.INSTANCE);
                    d.getTile(-1, -1).setBitLayerValue(bitDefault, 17, 17, true);
                    d.getTile(0, -1).setBitLayerValue(bitDefault, 17, 17, true);
                }
                SelectionOptions o = new SelectionOptions(); o.setCopyAnnotations(true);
                SelectionCopyBenchmark.copy(expected, o, dx, 19, false); SelectionCopyBenchmark.copy(actual, o, dx, 19, true);
                same(expected, actual);
            }
        } finally { restore(old); }
    }
    @Test public void unsupportedStorageFallsBackBeforeAnyNativeChanges() {
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true"); Dimension d = SelectionCopyBenchmark.fixture(2);
            d.removeTile(0, 0); d.addTile(new Tile(0, 0, d.getMinHeight(), d.getMaxHeight()) {}); d.setEventsInhibited(true);
            assertNull(org.pepsoft.worldpainter.SelectionCopyAccess.prepare(d, 17, -19, true, true, true, true, true, true, true));
            d.setEventsInhibited(false);
        } finally { restore(old); }
    }

    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
