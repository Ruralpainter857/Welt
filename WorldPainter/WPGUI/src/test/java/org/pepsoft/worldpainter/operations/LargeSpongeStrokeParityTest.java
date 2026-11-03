package org.pepsoft.worldpainter.operations;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.FluidBrushAccess;
import org.pepsoft.worldpainter.HeightTransform;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.BitmapBrush;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.RotatedBrush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Full GUI strokes must preserve fluids, all other planes, copy-on-write, history and notifications. */
public class LargeSpongeStrokeParityTest {
    private final Map<String, String> previous = new HashMap<>();
    private Configuration configuration;

    @Before public void prepare() {
        configuration = Configuration.getInstance();
        if (Boolean.getBoolean("welt.test.requireLargeSponge")) assertTrue(NativeLoader.areSlicesAvailable());
        else org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        Configuration.setInstance(new Configuration());
        for (String key : new String[]{Native.GEN_KEY, Native.RENDER_KEY, "welt.native.largeFluidBrush"}) previous.put(key, System.getProperty(key));
        System.setProperty(Native.RENDER_KEY, "false");
        System.setProperty("welt.native.largeFluidBrush", "true");
    }

    @After public void restore() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        Configuration.setInstance(configuration);
    }

    private static void stroke(BrushPipelineTestView view, Sponge brush, Dimension dimension, boolean nativeMode, boolean reset) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        LargeMountainStrokeBenchmark.bind(view, dimension);
        dimension.armSavePoint();
        try { brush.tick(-3, 5, reset, true, .85f); }
        finally { LargeMountainStrokeBenchmark.bind(view, null); }
    }

    private static Map<String, Integer> events(Dimension dimension) {
        Map<String, Integer> result = new TreeMap<>();
        for (Tile tile : dimension.getTiles()) tile.addListener((Tile.Listener) Proxy.newProxyInstance(
                Tile.Listener.class.getClassLoader(), new Class<?>[]{Tile.Listener.class}, (proxy, method, args) -> {
                    result.merge(tile.getX() + "," + tile.getY() + ":" + method.getName(), 1, Integer::sum); return null;
                }));
        return result;
    }

    @Test public void dryResetStrokesKeepEveryPlaneNotificationsAndUndoExact() throws Exception {
        compare(383, null, false);
    }

    @Test public void rotatedSquareKeepsCornerFootprintExact() throws Exception {
        compare(255, RotatedBrush.rotate(SymmetricBrush.LINEAR_SQUARE.clone(), 31), false);
    }

    @Test public void maximumBitmapAndShortHeightPlanesStayExact() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(13, 9, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 13; x++) image.getRaster().setSample(x, y, 0, (x * 31 + y * 17) & 255);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", png);
        compare(511, new BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()), "Sponge bitmap"), true);
    }

    private static void compare(int radius, Brush shape, boolean shortPlanes) throws Exception {
        Dimension java = LargeSpongeStrokeBenchmark.fixture(radius), rust = LargeSpongeStrokeBenchmark.fixture(radius);
        if (shortPlanes) for (Dimension dimension : new Dimension[]{java, rust}) {
            dimension.unregisterUndoManager();
            dimension.setMaxHeight(192);
            for (Tile tile : dimension.getTiles()) tile.setMinMaxHeight(-64, 192, HeightTransform.IDENTITY);
            dimension.registerUndoManager(new org.pepsoft.util.undo.UndoManager(8));
        }
        BrushPipelineTestView view = new BrushPipelineTestView();
        Sponge brush = LargeSpongeStrokeBenchmark.brush(view, radius);
        if (shape != null) brush.setBrush(shape);
        java.unregisterUndoManager(); rust.unregisterUndoManager();
        var leftUndo = new org.pepsoft.util.undo.UndoManager(8);
        var rightUndo = new org.pepsoft.util.undo.UndoManager(8);
        java.registerUndoManager(leftUndo); rust.registerUndoManager(rightUndo);
        Map<String, Integer> left = events(java), right = events(rust);
        for (int tick = 0; tick < 4; tick++) {
            long calls = FluidBrushAccess.completedNativeCalls();
            stroke(view, brush, java, false, (tick & 1) != 0);
            assertEquals(calls, FluidBrushAccess.completedNativeCalls());
            stroke(view, brush, rust, true, (tick & 1) != 0);
            assertEquals("One native transaction per touched tile", LargeSpongeStrokeBenchmark.affectedTiles(rust, brush),
                    FluidBrushAccess.completedNativeCalls() - calls);
            LargeHeightStrokeParityTest.same(java, rust);
        }
        assertEquals(left, right);
        for (int tick = 0; tick < 4; tick++) {
            assertTrue(leftUndo.undo()); assertTrue(rightUndo.undo());
            LargeHeightStrokeParityTest.same(java, rust);
        }
        for (int tick = 0; tick < 4; tick++) {
            assertTrue(leftUndo.redo()); assertTrue(rightUndo.redo());
            LargeHeightStrokeParityTest.same(java, rust);
        }
    }

    @Test public void refusalPrecedesCallbacksAndEdits() throws Exception {
        Dimension dimension = LargeSpongeStrokeBenchmark.fixture(383);
        BrushPipelineTestView view = new BrushPipelineTestView();
        Sponge brush = LargeSpongeStrokeBenchmark.brush(view, 383);
        Method apply = Sponge.class.getDeclaredMethod("applyNativeSponge", Dimension.class, int.class, int.class, int.class, boolean.class, int.class);
        apply.setAccessible(true);
        System.setProperty(Native.GEN_KEY, "true");
        dimension.setEventsInhibited(true);
        try {
            long calls = FluidBrushAccess.completedNativeCalls();
            System.setProperty("welt.native.largeFluidBrush", "false");
            assertFalse((boolean) apply.invoke(brush, dimension, -3, 5, 383, false, 62));
            System.setProperty("welt.native.largeFluidBrush", "true");
            brush.setFilter((x, y, strength) -> strength);
            assertFalse((boolean) apply.invoke(brush, dimension, -3, 5, 383, false, 62));
            brush.setFilter(null);
            int[] callbacks = {0};
            Brush delegate = SymmetricBrush.LINEAR_CIRCLE.clone();
            brush.setBrush((Brush) Proxy.newProxyInstance(Brush.class.getClassLoader(), new Class<?>[]{Brush.class}, (proxy, method, args) -> {
                if (method.getName().equals("getStrength")) callbacks[0]++;
                return method.invoke(delegate, args);
            }));
            assertFalse((boolean) apply.invoke(brush, dimension, -3, 5, 383, false, 62));
            assertEquals(0, callbacks[0]);
            int[] samples = {0};
            assertFalse(FluidBrushAccess.tryApplySampled(dimension, Integer.MAX_VALUE, 0, 767, 767, (x,y) -> {samples[0]++; return 1;}, false, 62));
            dimension.removeTile(new java.awt.Point(-1, -1));
            dimension.addTile(new Tile(-1, -1, -64, 320) {});
            long customHash = LargeMountainStrokeBenchmark.digest(dimension);
            assertFalse(FluidBrushAccess.tryApplySampled(dimension, -386, -378, 767, 767, (x,y) -> {samples[0]++; return 1;}, false, 62));
            assertEquals(0, samples[0]); assertEquals(customHash, LargeMountainStrokeBenchmark.digest(dimension));
            assertEquals(calls, FluidBrushAccess.completedNativeCalls());
        } finally { dimension.setEventsInhibited(false); }
    }
}
