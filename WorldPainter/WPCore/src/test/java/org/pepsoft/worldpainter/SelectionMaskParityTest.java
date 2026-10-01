package org.pepsoft.worldpainter;

import java.util.Random;
import java.util.Arrays;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.selection.*;
import static org.junit.Assert.*;

public class SelectionMaskParityTest {
    @Test public void maskNativeAndJavaFallbackKeepExactPlanesAndPresence() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int initial = 0; initial < 4; initial++) for (boolean add : new boolean[] {false, true}) {
                Tile java = fixture(initial), rust = fixture(initial);
                java.inhibitEvents(); rust.inhibitEvents(); byte[] mask = new byte[2048];
                for (int pass = 0; pass < 4; pass++) {
                    if (pass == 0) new Random(74).nextBytes(mask);
                    else Arrays.fill(mask, (byte) (pass == 1 ? 0 : pass == 2 ? 255 : 0x55));
                    System.setProperty(Native.GEN_KEY, "false"); assertTrue(java.editSelectionMask(mask, add));
                    System.setProperty(Native.GEN_KEY, "true");
                    assertNotNull("The WSEL v2 JNI must execute", SelectionTileAccess.editMask(add,
                            rust.bitLayerData.get(SelectionChunk.INSTANCE), rust.bitLayerData.get(SelectionBlock.INSTANCE), mask));
                    assertTrue(rust.editSelectionMask(mask, add));
                    assertEquals(java.bitLayerData, rust.bitLayerData); assertEquals(java.getLayers(), rust.getLayers());
                }
                java.releaseEvents(); rust.releaseEvents();
            }
        } finally { restore(old); }
    }
    @Test public void compoundSelectionPreservesUndoAndOtherLayers() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true"); Tile tile = fixture(3); UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
            byte[] mask = new byte[2048]; mask[0] = 1; tile.inhibitEvents();
            assertTrue(tile.canEditSelectionMask()); assertTrue(tile.editSelectionMask(mask, false)); tile.releaseEvents();
            assertFalse(tile.getBitLayerValue(SelectionChunk.INSTANCE, 0, 0));
            assertTrue(tile.getBitLayerValue(SelectionBlock.INSTANCE, 0, 0));
            assertTrue(tile.getBitLayerValue(SelectionBlock.INSTANCE, 1, 0));
            assertTrue(tile.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 1, 2));
            assertTrue(undo.undo()); assertTrue(tile.getBitLayerValue(SelectionChunk.INSTANCE, 0, 0));
            assertTrue(undo.redo()); assertFalse(tile.getBitLayerValue(SelectionChunk.INSTANCE, 0, 0));
            assertFalse(tile.editSelectionMask(mask, false));
        } finally { restore(old); }
    }
    private static Tile fixture(int initial) {
        Tile tile = new Tile(0, 0, -64, 320);
        if ((initial & 1) != 0) tile.setBitLayerValue(SelectionChunk.INSTANCE, 0, 0, true);
        if ((initial & 2) != 0) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
            if ((x * 7 + y) % 19 == 0) tile.setBitLayerValue(SelectionBlock.INSTANCE, x, y, true);
        tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 1, 2, true); return tile;
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
