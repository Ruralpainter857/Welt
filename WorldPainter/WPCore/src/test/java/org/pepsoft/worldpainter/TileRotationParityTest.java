package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Populate;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import java.util.HashSet;
import java.util.concurrent.Executors;
import org.pepsoft.util.undo.UndoManager;

import static org.junit.Assert.*;

public class TileRotationParityTest {
    static final Layer NIBBLES = new Layer("rotation-nibble", "Rotation nibble", "", Layer.DataSize.NIBBLE, false, 1) {
        @Override public int getDefaultValue() { return 6; }
    };
    static final Layer BYTES = new Layer("rotation-byte", "Rotation byte", "", Layer.DataSize.BYTE, false, 2) {
        @Override public int getDefaultValue() { return 19; }
    };
    static final CoordinateTransform[] ROTATIONS = {
            CoordinateTransform.ROTATE_CLOCKWISE_90_DEGREES,
            CoordinateTransform.ROTATE_180_DEGREES,
            CoordinateTransform.ROTATE_CLOCKWISE_270_DEGREES
    };

    @Test
    public void allPlanesMatchJavaForThreeRotationsAndBothHeightFormats() {
        assertTrue("release JNI library is required", NativeLoader.areSlicesAvailable());
        final String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean tall : new boolean[] {false, true}) {
                final Tile source = fixture(tall);
                for (CoordinateTransform transform : ROTATIONS) {
                    System.setProperty(Native.GEN_KEY, "false");
                    final Tile expected = source.transform(transform);
                    System.setProperty(Native.GEN_KEY, "true");
                    assertNotNull("native path must execute, not silently fall back",
                            TileRotationAccess.rotate(source, transform, transform.transform(source.getX() << 7, source.getY() << 7)));
                    final Tile actual = source.transform(transform);
                    assertEquals(expected.getX(), actual.getX());
                    assertEquals(expected.getY(), actual.getY());
                    assertEquals(new HashSet<>(expected.getLayers()), new HashSet<>(actual.getLayers()));
                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y));
                            assertEquals(expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
                            assertEquals(expected.getTerrain(x, y), actual.getTerrain(x, y));
                            assertEquals(expected.getBitLayerValue(Frost.INSTANCE, x, y), actual.getBitLayerValue(Frost.INSTANCE, x, y));
                            assertEquals(expected.getBitLayerValue(Populate.INSTANCE, x, y), actual.getBitLayerValue(Populate.INSTANCE, x, y));
                            assertEquals(expected.getLayerValue(NIBBLES, x, y), actual.getLayerValue(NIBBLES, x, y));
                            assertEquals(expected.getLayerValue(BYTES, x, y), actual.getLayerValue(BYTES, x, y));
                        }
                    }
                }
            }
        } finally {
            if (previous == null) System.clearProperty(Native.GEN_KEY);
            else System.setProperty(Native.GEN_KEY, previous);
        }
    }

    @Test
    public void invalidNativeProgramLeavesAllBytesUntouched() {
        final String previous = System.getProperty(Native.GEN_KEY);
        System.setProperty(Native.GEN_KEY, "true");
        try {
            final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(16);
            for (int i = 0; i < 16; i++) buffer.put(i, (byte) (i + 1));
            assertFalse(org.pepsoft.worldpainter.nativeapi.NativeSlices.rotateTilePlanes(buffer));
            for (int i = 0; i < 16; i++) assertEquals((byte) (i + 1), buffer.get(i));
        } finally {
            if (previous == null) System.clearProperty(Native.GEN_KEY);
            else System.setProperty(Native.GEN_KEY, previous);
        }
    }

    static Tile fixture(boolean tall) {
        final Tile tile = new Tile(-3, 7, tall ? -64 : 0, tall ? 512 : 256);
        final Terrain[] terrains = Terrain.values();
        tile.inhibitEvents();
        try {
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    tile.setRawHeight(x, y, (x * 513 + y * 791) % (tall ? 140000 : 65536));
                    tile.setWaterLevel(x, y, tile.getMinHeight() + (x * 7 + y * 3) % 230);
                    tile.setTerrain(x, y, terrains[(x * 3 + y) % terrains.length]);
                    tile.setBitLayerValue(Frost.INSTANCE, x, y, (x * 3 + y) % 7 == 0);
                    tile.setLayerValue(NIBBLES, x, y, (x + y * 3) % 16);
                    tile.setLayerValue(BYTES, x, y, (x * 5 + y) % 256);
                }
            }
            tile.setBitLayerValue(Populate.INSTANCE, 16, 32, true);
            tile.setBitLayerValue(Populate.INSTANCE, 127, 127, true);
        } finally {
            tile.releaseEvents();
        }
        return tile;
    }

    @Test
    public void workerBuffersRemainIndependentAndRotatedTilesSupportUndo() throws Exception {
        final String previous = System.getProperty(Native.GEN_KEY);
        final var workers = Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            final java.util.List<java.util.concurrent.Future<?>> results = new java.util.ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                results.add(workers.submit(() -> {
                    final Tile source = fixture(true);
                    final Tile rotated = source.transform(ROTATIONS[0]);
                    assertEquals(source.getRawHeight(9, 11), rotated.getRawHeight(116, 9));
                    final int original = rotated.getRawHeight(116, 9);
                    final UndoManager undo = new UndoManager();
                    rotated.register(undo);
                    undo.armSavePoint();
                    rotated.setRawHeight(116, 9, 123456);
                    assertTrue(undo.undo());
                    assertEquals(original, rotated.getRawHeight(116, 9));
                    assertTrue(undo.redo());
                    assertEquals(123456, rotated.getRawHeight(116, 9));
                    final Tile blank = new Tile(0, 0, 0, 256).transform(ROTATIONS[2]);
                    assertTrue(blank.getLayers().isEmpty());
                    assertEquals(0, blank.getRawHeight(3, 5));
                }));
            }
            for (var result : results) result.get();
        } finally {
            workers.shutdownNow();
            if (previous == null) System.clearProperty(Native.GEN_KEY);
            else System.setProperty(Native.GEN_KEY, previous);
        }
    }
}
