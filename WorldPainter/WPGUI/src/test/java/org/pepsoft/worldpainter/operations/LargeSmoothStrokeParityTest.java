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
import org.pepsoft.worldpainter.SmoothHeightAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.BitmapBrush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Complete smoothing ticks retain immutable neighbour reads across many packed tiles. */
public class LargeSmoothStrokeParityTest {
    private final Map<String, String> properties = new HashMap<>();
    private Configuration configuration;

    @Before public void prepare() {
        configuration = Configuration.getInstance();
        if (Boolean.getBoolean("welt.test.requireLargeSmooth")) assertTrue(NativeLoader.areSlicesAvailable());
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

    private static void stroke(BrushPipelineTestView view, Smooth brush, Dimension dimension, boolean nativeMode) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        LargeSmoothStrokeBenchmark.bind(view, dimension);
        dimension.armSavePoint();
        try { brush.tick(-3, 5, false, true, .85f); }
        finally { LargeSmoothStrokeBenchmark.bind(view, null); }
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

    @Test public void themedAndUnthemedSmoothingKeepPlanesRandomUndoAndNotifications() throws Exception {
        for (boolean theme : new boolean[]{false, true}) {
            Dimension java = LargeHeightStrokeBenchmark.fixture(255), rust = LargeHeightStrokeBenchmark.fixture(255);
            java.unregisterUndoManager(); rust.unregisterUndoManager();
            UndoManager a = new UndoManager(8), b = new UndoManager(8);
            java.registerUndoManager(a); rust.registerUndoManager(b);
            Map<String, Integer> expectedEvents = events(java), actualEvents = events(rust);
            BrushPipelineTestView view = new BrushPipelineTestView();
            Smooth brush = LargeSmoothStrokeBenchmark.brush(view, 255, theme);
            long themedCalls = HeightBrushAccess.completedThemedCalls(), compactCalls = SmoothHeightAccess.completedCalls();
            for (int tick = 0; tick < 2; tick++) {
                LargeHeightStrokeParityTest.random().setSeed(197 + tick);
                stroke(view, brush, java, false);
                long random = LargeHeightStrokeParityTest.random().nextLong();
                LargeHeightStrokeParityTest.random().setSeed(197 + tick);
                stroke(view, brush, rust, true);
                assertEquals(random, LargeHeightStrokeParityTest.random().nextLong());
                LargeHeightStrokeParityTest.same(java, rust);
            }
            assertEquals(theme ? 2 : 0, HeightBrushAccess.completedThemedCalls() - themedCalls);
            assertEquals(theme ? 0 : 2, SmoothHeightAccess.completedCalls() - compactCalls);
            assertEquals(expectedEvents, actualEvents);
            for (int tick = 0; tick < 2; tick++) { assertTrue(a.undo()); assertTrue(b.undo()); LargeHeightStrokeParityTest.same(java, rust); }
        }
    }

    @Test public void maximumBitmapFootprintKeepsTheFiveCellBorderExact() throws Exception {
        var image = new java.awt.image.BufferedImage(17, 13, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 13; y++) for (int x = 0; x < 17; x++) image.getRaster().setSample(x, y, 0, 32 + Math.floorMod(x * 13 + y * 17, 224));
        var png = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "png", png));
        for (boolean theme : new boolean[]{false, true}) {
            Dimension java = LargeHeightStrokeBenchmark.fixture(506), rust = LargeHeightStrokeBenchmark.fixture(506);
            BrushPipelineTestView view = new BrushPipelineTestView();
            Smooth brush = LargeSmoothStrokeBenchmark.brush(view, 506, theme);
            brush.setBrush(new BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()), "Large smooth bitmap"));
            long themedCalls = HeightBrushAccess.completedThemedCalls(), compactCalls = SmoothHeightAccess.completedCalls();
            LargeHeightStrokeParityTest.random().setSeed(197);
            stroke(view, brush, java, false);
            long random = LargeHeightStrokeParityTest.random().nextLong();
            LargeHeightStrokeParityTest.random().setSeed(197);
            stroke(view, brush, rust, true);
            assertEquals(theme ? 1 : 0, HeightBrushAccess.completedThemedCalls() - themedCalls);
            assertEquals(theme ? 0 : 1, SmoothHeightAccess.completedCalls() - compactCalls);
            assertEquals(random, LargeHeightStrokeParityTest.random().nextLong());
            LargeHeightStrokeParityTest.same(java, rust);
        }
    }

    @Test public void haloPluginsAndCustomBrushesRefuseWithoutMutation() throws Exception {
        Dimension dimension = LargeHeightStrokeBenchmark.fixture(255);
        BrushPipelineTestView view = new BrushPipelineTestView();
        Smooth brush = LargeSmoothStrokeBenchmark.brush(view, 255, true);
        Method nativeMethod = Smooth.class.getDeclaredMethod("smoothNative", Dimension.class, int.class, int.class,
                int.class, float.class, boolean.class);
        nativeMethod.setAccessible(true);
        System.setProperty(Native.GEN_KEY, "true");
        dimension.setEventsInhibited(true);
        try {
            long hash = LargeSmoothStrokeBenchmark.digest(dimension);
            System.setProperty("welt.native.largeHeightBrush", "false");
            assertFalse((boolean) nativeMethod.invoke(brush, dimension, 0, 0, 255, .85f, true));
            assertEquals(hash, LargeSmoothStrokeBenchmark.digest(dimension));
            System.setProperty("welt.native.largeHeightBrush", "true");
            dimension.removeTile(new java.awt.Point(-3, 0));
            dimension.addTile(new Tile(-3, 0, -64, 320) {}); // Only the halo touches this tile.
            hash = LargeSmoothStrokeBenchmark.digest(dimension);
            for (boolean theme : new boolean[]{false, true}) {
                LargeHeightStrokeParityTest.random().setSeed(197);
                assertFalse((boolean) nativeMethod.invoke(brush, dimension, 0, 0, 255, .85f, theme));
                long random = LargeHeightStrokeParityTest.random().nextLong();
                LargeHeightStrokeParityTest.random().setSeed(197);
                assertEquals(LargeHeightStrokeParityTest.random().nextLong(), random);
                assertEquals(hash, LargeSmoothStrokeBenchmark.digest(dimension));
            }
            int[] callbacks = {0}; Brush delegate = SymmetricBrush.LINEAR_CIRCLE.clone();
            brush.setBrush((Brush) Proxy.newProxyInstance(Brush.class.getClassLoader(), new Class<?>[]{Brush.class},
                    (proxy, method, args) -> { if (method.getName().equals("getFullStrength")) callbacks[0]++; return method.invoke(delegate, args); }));
            assertFalse((boolean) nativeMethod.invoke(brush, dimension, 0, 0, 255, .85f, true));
            assertEquals(0, callbacks[0]);
        } finally { dimension.setEventsInhibited(false); }
    }
}
