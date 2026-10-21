package org.pepsoft.worldpainter;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import static org.junit.Assert.*;

public class SelectionBoundsParityTest {
    @Test public void completeBoundsMatchFrozenOracleForMixedSparseDenseAndEmptySelections() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int variant = 0; variant < 8; variant++) {
                Dimension world = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
                world.removeTile(0, 0);
                Random random = new Random(311);
                for (int tx = -2; tx <= 2; tx++) for (int ty = -2; ty <= 2; ty++) {
                    Tile tile = new Tile(tx, ty, -64, 320); tile.inhibitEvents();
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                        if (variant == 1 || variant == 2 && random.nextInt(29) == 0
                                || variant == 3 && random.nextBoolean() || variant == 4 && x == y
                                || variant == 5 && (x == 0 || y == 127)) {
                            tile.setBitLayerValue(SelectionBlock.INSTANCE, x, y, true);
                        }
                    }
                    if (variant == 6 || variant == 7) tile.setBitLayerValue(SelectionChunk.INSTANCE, 32, 96, true);
                    if (variant == 7) tile.setBitLayerValue(SelectionBlock.INSTANCE, 127, 0, true);
                    tile.releaseEvents(); world.addTile(tile);
                }
                System.setProperty(Native.GEN_KEY, "false");
                Rectangle expected = SelectionBoundsBenchmark.legacyBounds(world);
                assertEquals(expected, SelectionBoundsAccess.getBounds(world));
                System.setProperty(Native.GEN_KEY, "true");
                assertEquals(expected, SelectionBoundsAccess.getBounds(world));
            }
            Dimension world = SelectionBoundsBenchmark.fixture();
            Rectangle expected = SelectionBoundsBenchmark.legacyBounds(world);
            System.setProperty(Native.GEN_KEY, "true");
            assertEquals(expected, SelectionBoundsAccess.getBounds(world));
        } finally { restore(old); }
    }

    @Test public void coordinateWrappingAndOutOfPlaneBitsKeepLegacyResults() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int tx : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, 16777215, 16777216, -16777217}) {
                Dimension world = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62); world.removeTile(0, 0);
                Tile tile = new Tile(tx, -19, -64, 320);
                tile.setBitLayerValue(SelectionBlock.INSTANCE, 127, 7, true);
                world.addTile(tile);
                Rectangle expected = SelectionBoundsBenchmark.legacyBounds(world);
                System.setProperty(Native.GEN_KEY, "true"); assertEquals(expected, SelectionBoundsAccess.getBounds(world));
            }
            Dimension world = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
            Tile tile = world.getTile(0, 0);
            tile.setBitLayerValue(SelectionBlock.INSTANCE, 0, 128, true);
            tile.setBitLayerValue(SelectionChunk.INSTANCE, 1024, 0, true);
            System.setProperty(Native.GEN_KEY, "true");
            assertEquals(SelectionBoundsBenchmark.legacyBounds(world), SelectionBoundsAccess.getBounds(world));
        } finally { restore(old); }
    }

    @Test public void queriesAreReadOnlyAcrossUndoAndNormalEditing() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Tile tile = new Tile(0, 0, -64, 320);
            tile.setBitLayerValue(SelectionBlock.INSTANCE, 31, 57, true);
            UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
            int[] events = {0};
            tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                    new Class<?>[] {Tile.Listener.class}, (p, m, a) -> { events[0]++; return null; }));
            long bounds = tile.getNativeSelectionBounds();
            assertNotEquals(Long.MIN_VALUE, bounds);
            for (int query = 0; query < 50; query++) assertEquals(bounds, tile.getNativeSelectionBounds());
            assertEquals(0, events[0]); assertFalse(undo.undo());
            tile.setBitLayerValue(SelectionBlock.INSTANCE, 127, 0, true);
            assertNotEquals(bounds, tile.getNativeSelectionBounds());
            assertTrue(undo.undo()); assertEquals(bounds, tile.getNativeSelectionBounds());
            assertTrue(undo.redo()); assertNotEquals(bounds, tile.getNativeSelectionBounds());
        } finally { restore(old); }
    }

    @Test public void directReadOnlyAndInvalidFramesRespectTheAbi() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            ByteBuffer data = ByteBuffer.allocateDirect(4192).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(0, 0x4c455357).putInt(4, 3).putInt(20, 1);
            int bit = 42 + 57 * 128; data.put(96 + bit / 8, (byte) (1 << (bit & 7)));
            long expected = (1L << 32) | 42L | 42L << 8 | 57L << 16 | 57L << 24;
            byte[] before = new byte[4192]; data.duplicate().get(before);
            assertEquals(expected, NativeSlices.selectionBounds(data.asReadOnlyBuffer()));
            byte[] after = new byte[4192]; data.duplicate().get(after); assertArrayEquals(before, after);
            data.putInt(0, 0); assertEquals(Long.MIN_VALUE, NativeSlices.selectionBounds(data));
            assertEquals(Long.MIN_VALUE, NativeSlices.selectionBounds(ByteBuffer.allocate(4192)));
            Tile subclass = new Tile(0, 0, -64, 320) { };
            assertEquals(Long.MIN_VALUE, subclass.getNativeSelectionBounds());
        } finally { restore(old); }
    }

    @Test public void workerQueriesReuseTheSelectionEditBufferWithoutLeakingState() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "false");
            var worlds = new java.util.ArrayList<Dimension>();
            for (int worker = 0; worker < 4; worker++) worlds.add(TestData.createDimension(new Rectangle(0, 0, 128, 128), 62));
            System.setProperty(Native.GEN_KEY, "true");
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (Dimension world : worlds) futures.add(workers.submit(() -> {
                Tile tile = world.getTile(0, 0);
                for (int iteration = 0; iteration < 6; iteration++) {
                    Rectangle shape = new Rectangle(iteration * 11, iteration * 7, 17, 35);
                    tile.inhibitEvents();
                    try { assertTrue(tile.editSelectionShape(shape, (iteration & 1) == 0)); }
                    finally { tile.releaseEvents(); }
                    assertEquals(SelectionBoundsBenchmark.legacyBounds(world), SelectionBoundsAccess.getBounds(world));
                    assertEquals(SelectionBoundsBenchmark.legacyBounds(world), SelectionBoundsAccess.getBounds(world));
                }
            }));
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); restore(old); }
    }

    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
