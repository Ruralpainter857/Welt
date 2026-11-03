package org.pepsoft.worldpainter.operations;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.MaskedPlaneAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.BitmapBrush;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.RotatedBrush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.painting.TerrainPaint;
import static org.junit.Assert.*;

public class LargeTerrainStrokeParityTest {
    private final Map<String, String> properties = new HashMap<>();

    @Before public void prepare() {
        if (Boolean.getBoolean("welt.test.requireLargeTerrain")) assertTrue(NativeLoader.areSlicesAvailable());
        else org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        for (String key : new String[] {Native.GEN_KEY, "welt.native.largeTerrainBrush"}) properties.put(key, System.getProperty(key));
        System.setProperty("welt.native.largeTerrainBrush", "true");
    }

    @After public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    @Test public void movingLargeStrokesPreserveEveryPlaneEventsAndUndoRedo() throws Exception {
        for (int radius : new int[] {128, 383, 511}) compare(LargeTerrainStrokeBenchmark.brush(radius), radius, true);
    }

    @Test public void cachedRotatedAndBitmapBrushesKeepTheirActualFootprints() throws Exception {
        var square = SymmetricBrush.LINEAR_SQUARE.clone();
        square.setRadius(270);
        compare(RotatedBrush.rotate(square, 31), 511, false);
        var image = new BufferedImage(17, 13, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 13; y++) for (int x = 0; x < 17; x++) image.getRaster().setSample(x, y, 0, (x * 37 + y * 19) & 255);
        var bytes = new ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "png", bytes));
        var bitmap = new BitmapBrush(new ByteArrayInputStream(bytes.toByteArray()), "Large terrain");
        bitmap.setRadius(511);
        compare(bitmap, 511, false);
    }

    private static Map<String, Integer> events(Dimension dimension) {
        var events = new TreeMap<String, Integer>();
        for (Tile tile : dimension.getTiles()) tile.addListener((Tile.Listener) Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class}, (proxy, method, args) -> {
                    events.merge(tile.getX() + "," + tile.getY() + ":" + method.getName(), 1, Integer::sum);
                    return null;
                }));
        return events;
    }

    private static void compare(Brush brush, int fixtureRadius, boolean history) throws Exception {
        Dimension java = LargeMountainStrokeBenchmark.fixture(fixtureRadius, false);
        Dimension rust = LargeMountainStrokeBenchmark.fixture(fixtureRadius, false);
        if (java.getTile(1, 1) != null) java.removeTile(1, 1);
        if (rust.getTile(1, 1) != null) rust.removeTile(1, 1);
        java.unregisterUndoManager(); rust.unregisterUndoManager();
        var a = new UndoManager(8); var b = new UndoManager(8);
        java.registerUndoManager(a); rust.registerUndoManager(b);
        var javaEvents = events(java); var rustEvents = events(rust);
        var paint = LargeTerrainStrokeBenchmark.paint(brush);
        long calls = MaskedPlaneAccess.completedSampledTerrainCalls();
        for (int tick = 0; tick < 3; tick++) {
            int x = -3 + tick * 31, y = 5 - tick * 19;
            java.armSavePoint(); rust.armSavePoint();
            java.setEventsInhibited(true); rust.setEventsInhibited(true);
            try {
                System.setProperty(Native.GEN_KEY, "false"); paint.apply(java, x, y, .93f);
                System.setProperty(Native.GEN_KEY, "true"); paint.apply(rust, x, y, .93f);
            } finally { java.setEventsInhibited(false); rust.setEventsInhibited(false); }
            LargeHeightStrokeParityTest.same(java, rust);
        }
        assertTrue("Native tile transactions required", MaskedPlaneAccess.completedSampledTerrainCalls() > calls);
        assertEquals(javaEvents, rustEvents);
        if (history) {
            for (int tick = 0; tick < 3; tick++) { assertTrue(a.undo()); assertTrue(b.undo()); LargeHeightStrokeParityTest.same(java, rust); }
            for (int tick = 0; tick < 3; tick++) { assertTrue(a.redo()); assertTrue(b.redo()); LargeHeightStrokeParityTest.same(java, rust); }
        }
    }

    @Test public void unsupportedCallbacksAreRejectedBeforeSamplingOrEditing() throws Exception {
        Dimension dimension = LargeMountainStrokeBenchmark.fixture(383, false);
        var paint = LargeTerrainStrokeBenchmark.paint(LargeTerrainStrokeBenchmark.brush(383));
        var apply = TerrainPaint.class.getDeclaredMethod("applyNativeTerrainBrush", Dimension.class, int.class, int.class,
                float.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class);
        apply.setAccessible(true);
        System.setProperty(Native.GEN_KEY, "true");
        dimension.setEventsInhibited(true);
        try {
            long hash = LargeMountainStrokeBenchmark.digest(dimension), calls = MaskedPlaneAccess.completedSampledTerrainCalls();
            int[] callbacks = {0};
            paint.setFilter((x, y, strength) -> { callbacks[0]++; return strength; });
            assertFalse((boolean) apply.invoke(paint, dimension, -3, 5, .93f, -386, -378, 380, 388, false, false));
            paint.setFilter(null);
            Brush delegate = LargeTerrainStrokeBenchmark.brush(383);
            paint.setBrush((Brush) Proxy.newProxyInstance(Brush.class.getClassLoader(), new Class<?>[] {Brush.class}, (proxy, method, args) -> {
                if (method.getName().equals("getStrength") || method.getName().equals("getFullStrength")) callbacks[0]++;
                return method.invoke(delegate, args);
            }));
            assertFalse((boolean) apply.invoke(paint, dimension, -3, 5, .93f, -386, -378, 380, 388, false, false));
            assertEquals(0, callbacks[0]);
            assertEquals(calls, MaskedPlaneAccess.completedSampledTerrainCalls());
            assertEquals(hash, LargeMountainStrokeBenchmark.digest(dimension));
        } finally { dimension.setEventsInhibited(false); }
    }

    @Test public void zeroNanAndThresholdStrengthsLeaveTheWorldUnchanged() throws Exception {
        Dimension dimension = LargeMountainStrokeBenchmark.fixture(383, false);
        var paint = LargeTerrainStrokeBenchmark.paint(LargeTerrainStrokeBenchmark.brush(383));
        System.setProperty(Native.GEN_KEY, "true");
        dimension.setEventsInhibited(true);
        try {
            long hash = LargeMountainStrokeBenchmark.digest(dimension), calls = MaskedPlaneAccess.completedSampledTerrainCalls();
            for (float level : new float[] {0f, -.1f, Float.NaN, .75f}) paint.apply(dimension, -3, 5, level);
            assertEquals(hash, LargeMountainStrokeBenchmark.digest(dimension));
            assertEquals(calls, MaskedPlaneAccess.completedSampledTerrainCalls());
        } finally { dimension.setEventsInhibited(false); }
    }

    @Test public void customTilesAndUnsupportedBoundsRefuseBeforeAnyMaskCallback() throws Exception {
        Dimension dimension = LargeMountainStrokeBenchmark.fixture(383, false);
        dimension.removeTile(0, 0);
        dimension.addTile(new Tile(0, 0, dimension.getMinHeight(), dimension.getMaxHeight()) { });
        System.setProperty(Native.GEN_KEY, "true");
        dimension.setEventsInhibited(true);
        try {
            int[] callbacks = {0};
            long hash = LargeMountainStrokeBenchmark.digest(dimension);
            org.pepsoft.worldpainter.MaskedPlaneAccess.TerrainMask mask = (x, y) -> { callbacks[0]++; return true; };
            assertFalse(MaskedPlaneAccess.tryApplySampledTerrain(dimension, org.pepsoft.worldpainter.Terrain.SAND,
                    -386, -378, 767, 767, mask));
            assertFalse(MaskedPlaneAccess.tryApplySampledTerrain(dimension, org.pepsoft.worldpainter.Terrain.SAND,
                    Integer.MAX_VALUE - 10, 0, 767, 767, mask));
            assertFalse(MaskedPlaneAccess.tryApplySampledTerrain(dimension, org.pepsoft.worldpainter.Terrain.SAND,
                    0, 0, 1025, 1, mask));
            assertEquals(0, callbacks[0]);
            assertEquals(hash, LargeMountainStrokeBenchmark.digest(dimension));
        } finally { dimension.setEventsInhibited(false); }
    }
}
