package org.pepsoft.worldpainter.selection;

import java.util.*;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

/** Oracle scalaire indépendant pour préserver l'ordre des lectures et des écritures en cas de chevauchement. */
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
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
