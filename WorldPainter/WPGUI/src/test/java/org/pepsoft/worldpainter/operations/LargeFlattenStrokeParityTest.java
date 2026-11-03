package org.pepsoft.worldpainter.operations;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.BitmapBrush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Exact real flatten operations over negative coordinates, missing tiles and several undo frames. */
public class LargeFlattenStrokeParityTest {
    private final Map<String, String> properties = new HashMap<>();
    private Configuration configuration;

    @Before public void prepare() {
        configuration = Configuration.getInstance();
        if (Boolean.getBoolean("welt.test.requireLargeFlatten")) assertTrue("A release native library is required", NativeLoader.areSlicesAvailable());
        else org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        Configuration.setInstance(new Configuration());
        for (String key : new String[]{Native.GEN_KEY, Native.RENDER_KEY, "welt.native.largeHeightBrush"}) properties.put(key, System.getProperty(key));
        System.setProperty(Native.RENDER_KEY, "false");
        System.setProperty("welt.native.largeHeightBrush", "true");
    }

    @After public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        Configuration.setInstance(configuration);
    }

    private static void stroke(BrushPipelineTestView view, Flatten brush, Dimension dimension,
                               boolean nativeMode, boolean first) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        LargeFlattenStrokeBenchmark.bind(view, dimension);
        dimension.armSavePoint();
        try { brush.tick(-3, 5, false, first, .85f); }
        finally { LargeFlattenStrokeBenchmark.bind(view, null); }
    }

    private static Map<String, Integer> events(Dimension dimension) {
        Map<String, Integer> result = new TreeMap<>();
        for (Tile tile : dimension.getTiles()) tile.addListener((Tile.Listener) Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(), new Class<?>[]{Tile.Listener.class}, (proxy, method, args) -> {
                    result.merge(tile.getX() + "," + tile.getY() + ":" + method.getName(), 1, Integer::sum);
                    return null;
                }));
        return result;
    }

    @Test public void allFlattenModesKeepThemeRandomStateUndoAndNotifications() throws Exception {
        for (Flatten.Mode mode : Flatten.Mode.values()) {
            Dimension java = LargeHeightStrokeBenchmark.fixture(255), rust = LargeHeightStrokeBenchmark.fixture(255);
            java.unregisterUndoManager(); rust.unregisterUndoManager();
            UndoManager a = new UndoManager(8), b = new UndoManager(8);
            java.registerUndoManager(a); rust.registerUndoManager(b);
            Map<String, Integer> expectedEvents = events(java), actualEvents = events(rust);
            BrushPipelineTestView view = new BrushPipelineTestView();
            Flatten brush = LargeFlattenStrokeBenchmark.brush(view, 255, true);
            LargeFlattenStrokeBenchmark.mode(brush, mode);
            long calls = HeightBrushAccess.completedThemedCalls();
            for (int tick = 0; tick < 4; tick++) {
                LargeHeightStrokeParityTest.random().setSeed(197 + tick);
                stroke(view, brush, java, false, tick == 0);
                long random = LargeHeightStrokeParityTest.random().nextLong();
                LargeHeightStrokeParityTest.random().setSeed(197 + tick);
                stroke(view, brush, rust, true, tick == 0);
                assertEquals(mode + " random state", random, LargeHeightStrokeParityTest.random().nextLong());
                LargeHeightStrokeParityTest.same(java, rust);
            }
            assertEquals(mode + " grouped coverage", 4, HeightBrushAccess.completedThemedCalls() - calls);
            assertEquals(mode + " notifications", expectedEvents, actualEvents);
            for (int tick = 0; tick < 4; tick++) { assertTrue(a.undo()); assertTrue(b.undo()); LargeHeightStrokeParityTest.same(java, rust); }
        }
    }

    @Test public void unthemedFlattenModesKeepEveryPlaneAndLeaveThemeRandomUnchanged() throws Exception {
        for (Flatten.Mode mode : Flatten.Mode.values()) {
            Dimension java = LargeHeightStrokeBenchmark.fixture(255), rust = LargeHeightStrokeBenchmark.fixture(255);
            BrushPipelineTestView view = new BrushPipelineTestView();
            Flatten brush = LargeFlattenStrokeBenchmark.brush(view, 255, false);
            LargeFlattenStrokeBenchmark.mode(brush, mode);
            long calls = HeightBrushAccess.completedThemedCalls();
            LargeHeightStrokeParityTest.random().setSeed(197);
            stroke(view, brush, java, false, true);
            long random = LargeHeightStrokeParityTest.random().nextLong();
            LargeHeightStrokeParityTest.random().setSeed(197);
            stroke(view, brush, rust, true, true);
            assertEquals(random, LargeHeightStrokeParityTest.random().nextLong());
            assertEquals(calls, HeightBrushAccess.completedThemedCalls());
            LargeHeightStrokeParityTest.same(java, rust);
        }
    }

    @Test public void maximumBitmapFootprintKeepsOneThemedTransaction() throws Exception {
        var image = new java.awt.image.BufferedImage(17, 13, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 13; y++) for (int x = 0; x < 17; x++) image.getRaster().setSample(x, y, 0, 32 + Math.floorMod(x * 13 + y * 17, 224));
        var png = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "png", png));
        Dimension java = LargeHeightStrokeBenchmark.fixture(511), rust = LargeHeightStrokeBenchmark.fixture(511);
        BrushPipelineTestView view = new BrushPipelineTestView();
        Flatten brush = LargeFlattenStrokeBenchmark.brush(view, 511, true);
        brush.setBrush(new BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()), "Large flatten bitmap"));
        long calls = HeightBrushAccess.completedThemedCalls();
        LargeHeightStrokeParityTest.random().setSeed(197);
        stroke(view, brush, java, false, true);
        long random = LargeHeightStrokeParityTest.random().nextLong();
        LargeHeightStrokeParityTest.random().setSeed(197);
        stroke(view, brush, rust, true, true);
        assertEquals(1, HeightBrushAccess.completedThemedCalls() - calls);
        assertEquals(random, LargeHeightStrokeParityTest.random().nextLong());
        LargeHeightStrokeParityTest.same(java, rust);
    }

    @Test public void unsupportedLargeStrokesRefuseBeforeEditingOrCallingCustomStrengths() throws Exception {
        Dimension dimension = LargeHeightStrokeBenchmark.fixture(255);
        BrushPipelineTestView view = new BrushPipelineTestView();
        Flatten brush = LargeFlattenStrokeBenchmark.brush(view, 255, true);
        Method nativeMethod = Flatten.class.getDeclaredMethod("flattenNative", Dimension.class, int.class, int.class,
                int.class, float.class, boolean.class);
        nativeMethod.setAccessible(true);
        System.setProperty(Native.GEN_KEY, "true");
        long hash = LargeFlattenStrokeBenchmark.digest(dimension), calls = HeightBrushAccess.completedThemedCalls();
        dimension.setEventsInhibited(true);
        try {
            System.setProperty("welt.native.largeHeightBrush", "false");
            assertFalse((boolean) nativeMethod.invoke(brush, dimension, -3, 5, 255, .85f, true));
            assertEquals(hash, LargeFlattenStrokeBenchmark.digest(dimension));
            System.setProperty("welt.native.largeHeightBrush", "true");
            dimension.removeTile(new java.awt.Point(-1, -1));
            dimension.addTile(new Tile(-1, -1, -64, 320) {});
            hash = LargeFlattenStrokeBenchmark.digest(dimension);
            LargeHeightStrokeParityTest.random().setSeed(197);
            assertFalse((boolean) nativeMethod.invoke(brush, dimension, -3, 5, 255, .85f, true));
            long random = LargeHeightStrokeParityTest.random().nextLong();
            LargeHeightStrokeParityTest.random().setSeed(197);
            assertEquals(LargeHeightStrokeParityTest.random().nextLong(), random);
            assertEquals(hash, LargeFlattenStrokeBenchmark.digest(dimension));
            int[] callbacks = {0}; Brush delegate = SymmetricBrush.LINEAR_CIRCLE.clone();
            brush.setBrush((Brush) Proxy.newProxyInstance(Brush.class.getClassLoader(), new Class<?>[]{Brush.class},
                    (proxy, method, args) -> { if (method.getName().equals("getFullStrength")) callbacks[0]++; return method.invoke(delegate, args); }));
            assertFalse((boolean) nativeMethod.invoke(brush, dimension, -3, 5, 255, .85f, true));
            assertEquals(0, callbacks[0]);
            assertEquals(calls, HeightBrushAccess.completedThemedCalls());
        } finally { dimension.setEventsInhibited(false); }
    }
}
