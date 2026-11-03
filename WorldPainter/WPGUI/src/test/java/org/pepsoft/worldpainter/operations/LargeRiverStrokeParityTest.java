package org.pepsoft.worldpainter.operations;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.RiverAccess;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.brushes.BitmapBrush;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.RotatedBrush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/**
 * Complete River Paint drags with large brushes: the native WLRV v2 transaction against the Java traversal, tick by
 * tick, for every plane, the drag state (water level, depth, fluid), tile notifications and undo/redo; and the cases
 * which must keep the Java traversal without any edit or brush callback.
 */
public class LargeRiverStrokeParityTest {
    private final Map<String, String> properties = new HashMap<>();
    private Configuration configuration;

    @Before public void prepare() {
        configuration = Configuration.getInstance();
        if (Boolean.getBoolean("welt.test.requireLargeRiver")) assertTrue(NativeLoader.areSlicesAvailable());
        else org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        Configuration.setInstance(new Configuration());
        for (String key: new String[] {Native.GEN_KEY, Native.RENDER_KEY, "welt.native.largeRiver"}) properties.put(key, System.getProperty(key));
        System.setProperty(Native.RENDER_KEY, "false");
        System.setProperty("welt.native.largeRiver", "true");
    }

    @After public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        Configuration.setInstance(configuration);
    }

    @Test public void dragsAtRadius383KeepAllPlanesTheFallingLevelNotificationsAndUndo() throws Exception {
        for (boolean tall: new boolean[] {true, false}) {
            for (boolean lava: new boolean[] {false, true}) {
                compareDrag(383, tall, lava, () -> SymmetricBrush.LINEAR_CIRCLE.clone(), LargeRiverStrokeBenchmark.TICKS, false);
            }
        }
    }

    @Test public void dragsAtRadius511AndARiseAttemptKeepTheLowestLevel() throws Exception {
        // Before the last tick, the ground around its edge (where the strength falls to 25%, about 383 blocks from its
        // centre) is raised in both dimensions: that edge would allow a higher level than the one reached
        final int[][] ticks = LargeRiverStrokeBenchmark.TICKS;
        final int[] last = ticks[ticks.length - 1];
        compareDrag(511, true, false, () -> SymmetricBrush.LINEAR_CIRCLE.clone(), ticks, true, dimension -> { }, (tick, dimension) -> {
            if (tick != ticks.length - 1) return;
            for (int x = last[0] - 470; x <= last[0] + 470; x++) for (int y = last[1] - 470; y <= last[1] + 470; y++) {
                final long dx = x - last[0], dy = y - last[1], distance = dx * dx + dy * dy;
                if ((distance >= 300L * 300L) && (distance <= 470L * 470L)) dimension.setHeightAt(x, y, 150f);
            }
        });
    }

    /** Note: under -Xcheck:jni, Java2D rotates the rotated brush raster to zeros (independently of Welt); run without it. */
    @Test public void bitmapAndRotatedStockBrushesUseTheirEffectiveFootprint() throws Exception {
        final var image = new java.awt.image.BufferedImage(17, 13, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 13; y++) for (int x = 0; x < 17; x++) image.getRaster().setSample(x, y, 0, Math.floorMod(x * 37 + y * 23, 256));
        final var png = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "png", png));
        compareDrag(511, false, false, () -> new BitmapBrush(new java.io.ByteArrayInputStream(png.toByteArray()), "Large river bitmap"), LargeRiverStrokeBenchmark.TICKS, false);
        compareDrag(330, true, true, () -> RotatedBrush.rotate(SymmetricBrush.LINEAR_SQUARE.clone(), 33), LargeRiverStrokeBenchmark.TICKS, false);
    }

    @Test public void sharedUniformWaterPlanesAreCopiedOnWriteAndUndone() throws Exception {
        // Built for a larger brush, so that some tiles stay out of reach of the drag
        final Dimension java = LargeRiverStrokeBenchmark.fixture(511, true), rust = LargeRiverStrokeBenchmark.fixture(511, true);
        final Method compact = Tile.class.getDeclaredMethod("compactUniformWaterLevel");
        compact.setAccessible(true);
        for (Dimension dimension: new Dimension[] {java, rust}) {
            dimension.unregisterUndoManager();
            for (Tile tile: dimension.getTiles()) {
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) tile.setWaterLevel(x, y, 62);
                compact.invoke(tile);
            }
            dimension.setWaterLevelAt(-3, 5, 62);
            dimension.registerUndoManager(new UndoManager(8));
        }
        final Tile untouched = rust.getTile(4, 4);
        assertNotNull(untouched);
        drag(java, rust, 383, () -> SymmetricBrush.LINEAR_CIRCLE.clone(), LargeRiverStrokeBenchmark.TICKS, false, (tick, dimension) -> { });
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) assertEquals("a tile sharing the plane", 62, untouched.getWaterLevel(x, y));
    }

    /** Fresh Java and native fixtures, then the drag compared tick by tick. */
    private void compareDrag(int radius, boolean tall, boolean lava, java.util.function.Supplier<Brush> brush, int[][] ticks, boolean expectRiseAttempt) throws Exception {
        compareDrag(radius, tall, lava, brush, ticks, expectRiseAttempt, dimension -> { }, (tick, dimension) -> { });
    }

    private void compareDrag(int radius, boolean tall, boolean lava, java.util.function.Supplier<Brush> brush, int[][] ticks, boolean expectRiseAttempt,
                             java.util.function.Consumer<Dimension> prepare, java.util.function.BiConsumer<Integer, Dimension> beforeTick) throws Exception {
        final RiverPaint probe = LargeRiverStrokeBenchmark.brush(new BrushPipelineTestView(), radius);
        probe.setBrush(brush.get());
        final int footprint = probe.getEffectiveRadius();
        final Dimension java = LargeRiverStrokeBenchmark.fixture(footprint, tall), rust = LargeRiverStrokeBenchmark.fixture(footprint, tall);
        if (lava) {
            java.setBitLayerValueAt(FloodWithLava.INSTANCE, ticks[0][0], ticks[0][1], true);
            rust.setBitLayerValueAt(FloodWithLava.INSTANCE, ticks[0][0], ticks[0][1], true);
        }
        prepare.accept(java);
        prepare.accept(rust);
        drag(java, rust, radius, brush, ticks, expectRiseAttempt, beforeTick);
    }

    private void drag(Dimension java, Dimension rust, int radius, java.util.function.Supplier<Brush> brush, int[][] ticks, boolean expectRiseAttempt,
                      java.util.function.BiConsumer<Integer, Dimension> beforeTick) throws Exception {
        java.unregisterUndoManager(); rust.unregisterUndoManager();
        final UndoManager javaUndo = new UndoManager(ticks.length + 2), rustUndo = new UndoManager(ticks.length + 2);
        java.registerUndoManager(javaUndo); rust.registerUndoManager(rustUndo);
        final Map<String, Integer> javaEvents = events(java), rustEvents = events(rust);
        final BrushPipelineTestView view = new BrushPipelineTestView();
        final RiverPaint javaBrush = LargeRiverStrokeBenchmark.brush(view, radius), rustBrush = LargeRiverStrokeBenchmark.brush(view, radius);
        javaBrush.setBrush(brush.get()); rustBrush.setBrush(brush.get());
        final int effective = rustBrush.getEffectiveRadius();
        final int[] first = ticks[0];
        assertTrue("the brush floods its first centre", javaBrush.getFullStrength(first[0], first[1], first[0], first[1]) > 0.25f);
        assertTrue("effective radius " + effective, (effective > 255) && (effective <= 511));
        final long calls = RiverAccess.completedLargeCalls(), digestBefore = LargeMountainStrokeBenchmark.digest(java);
        final List<Integer> levels = new ArrayList<>();
        boolean riseAttempted = false;
        for (int tick = 0; tick < ticks.length; tick++) {
            final int[] centre = ticks[tick];
            beforeTick.accept(tick, java);
            beforeTick.accept(tick, rust);
            final int before = (tick == 0) ? Integer.MAX_VALUE : (int) field(javaBrush, "previousWaterLevel");
            if ((tick > 0) && (edgeLevel(java, javaBrush, centre[0], centre[1], effective) > before)) riseAttempted = true;
            tick(view, javaBrush, java, centre, tick == 0, false);
            tick(view, rustBrush, rust, centre, tick == 0, true);
            for (String name: new String[] {"previousWaterLevel", "depth", "lava"}) {
                assertEquals("tick " + tick + " " + name, field(javaBrush, name), field(rustBrush, name));
            }
            levels.add((int) field(javaBrush, "previousWaterLevel"));
            LargeHeightStrokeParityTest.same(java, rust);
        }
        assertTrue("a drag with a depth", (float) field(javaBrush, "depth") >= 0);
        assertEquals("one native transaction per tick", ticks.length, RiverAccess.completedLargeCalls() - calls);
        for (int i = 1; i < levels.size(); i++) assertTrue("the level never rises: " + levels, levels.get(i) <= levels.get(i - 1));
        if (expectRiseAttempt) assertTrue("a tick whose edge allowed a higher level: " + levels, riseAttempted);
        assertEquals("notifications", javaEvents, rustEvents);
        final long digestAfter = LargeMountainStrokeBenchmark.digest(java);
        // A tick which changes nothing adds no undo step; both histories must have the same steps
        int steps = 0;
        while (true) {
            final boolean undone = javaUndo.undo();
            assertEquals("undo step " + steps, undone, rustUndo.undo());
            if (! undone) break;
            steps++;
            LargeHeightStrokeParityTest.same(java, rust);
        }
        assertNotEquals("the drag edited the dimension (levels " + levels + ", depth " + field(javaBrush, "depth") + ")", digestBefore, digestAfter);
        assertTrue("undo steps: " + steps, (steps > 0) && (steps <= ticks.length));
        for (int step = 0; step < steps; step++) {
            assertTrue(javaUndo.redo()); assertTrue(rustUndo.redo());
        }
        assertFalse(javaUndo.redo()); assertFalse(rustUndo.redo());
        LargeHeightStrokeParityTest.same(java, rust);
    }

    @Test public void unsupportedLargeStrokesRefuseWithoutEditsOrBrushCallbacks() throws Exception {
        final Dimension dimension = LargeRiverStrokeBenchmark.fixture(383, true);
        final BrushPipelineTestView view = new BrushPipelineTestView();
        final RiverPaint brush = LargeRiverStrokeBenchmark.brush(view, 383);
        final int[] callbacks = {0};
        final Brush counting = countingBrush(SymmetricBrush.LINEAR_CIRCLE.clone(), callbacks);
        final Method method = RiverPaint.class.getDeclaredMethod("applyNativeRiverPaint", Dimension.class, int.class, int.class, int.class);
        method.setAccessible(true);
        System.setProperty(Native.GEN_KEY, "true");
        final long calls = RiverAccess.completedLargeCalls(), hash = LargeMountainStrokeBenchmark.digest(dimension);
        final Object level = field(brush, "previousWaterLevel"), depth = field(brush, "depth");
        // A custom brush
        brush.setBrush(counting);
        assertFalse((boolean) method.invoke(brush, dimension, -3, 5, 383));
        assertEquals("no callback of a custom brush", 0, callbacks[0]);
        // A filter
        brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        brush.setFilter((x, y, strength) -> strength);
        assertFalse((boolean) method.invoke(brush, dimension, -3, 5, 383));
        brush.setFilter(null);
        // The option disabled
        System.setProperty("welt.native.largeRiver", "false");
        assertFalse((boolean) method.invoke(brush, dimension, -3, 5, 383));
        System.setProperty("welt.native.largeRiver", "true");
        // Beyond the largest frame
        assertFalse((boolean) method.invoke(brush, dimension, -3, 5, 512));
        // Coordinates whose region would overflow
        assertFalse((boolean) method.invoke(brush, dimension, Integer.MAX_VALUE - 10, 5, 383));
        assertFalse((boolean) method.invoke(brush, dimension, -3, Integer.MIN_VALUE + 10, 383));
        // Origins whose region would overflow as longs, on stock tiles: refused before any strength is asked for
        final int[] asked = {0};
        dimension.setEventsInhibited(true);
        try {
            for (long[] origin: new long[][] {{Long.MAX_VALUE, -386}, {-386, Long.MAX_VALUE}, {Long.MAX_VALUE, Long.MAX_VALUE},
                    {Integer.MAX_VALUE - 765L, -386}, {-386, Integer.MAX_VALUE - 765L}, {Long.MIN_VALUE, -386}, {-386, Long.MIN_VALUE}}) {
                assertNull("origin " + origin[0] + "," + origin[1],
                        RiverAccess.tryApplyLarge(dimension, origin[0], origin[1], 767, (dx, dy) -> { asked[0]++; return 1f; }, 62, 3f, false));
            }
            // The highest origins which fit are not refused for their coordinates (there are no tiles there to edit)
            assertNotNull(RiverAccess.tryApplyLarge(dimension, Integer.MAX_VALUE - 766L, Integer.MAX_VALUE - 766L, 767, (dx, dy) -> 0f, 62, 3f, false));
        } finally {
            dimension.setEventsInhibited(false);
        }
        assertEquals("no strength asked for", 0, asked[0]);
        assertEquals("nothing edited", hash, LargeMountainStrokeBenchmark.digest(dimension));
        assertEquals("only the empty region at the highest origin was processed", calls + 1, RiverAccess.completedLargeCalls());
        // A custom tile in the region: refused before any strength is asked for
        final Tile custom = new Tile(-1, -1, -64, 320) { };
        dimension.removeTile(new Point(-1, -1));
        dimension.addTile(custom);
        final long customHash = LargeMountainStrokeBenchmark.digest(dimension);
        callbacks[0] = 0;
        brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        assertFalse((boolean) method.invoke(brush, dimension, -3, 5, 383));
        assertEquals(customHash, LargeMountainStrokeBenchmark.digest(dimension));
        assertEquals("no native transaction", calls + 1, RiverAccess.completedLargeCalls());
        assertNotEquals("the custom tile changed the digest", hash, customHash);
        // Directly: no strength is asked for when the region cannot be edited
        asked[0] = 0;
        dimension.setEventsInhibited(true);
        try {
            assertNull(RiverAccess.tryApplyLarge(dimension, -386, -378, 767, (dx, dy) -> { asked[0]++; return 1f; }, 62, 3f, false));
            assertNull(RiverAccess.tryApplyLarge(dimension, Integer.MAX_VALUE - 5L, 0, 767, (dx, dy) -> { asked[0]++; return 1f; }, 62, 3f, false));
            assertNull(RiverAccess.tryApplyLarge(dimension, 0, 0, 1025, (dx, dy) -> { asked[0]++; return 1f; }, 62, 3f, false));
            assertNull(RiverAccess.tryApplyLarge(dimension, 0, 0, 768, (dx, dy) -> { asked[0]++; return 1f; }, 62, 3f, false));
        } finally {
            dimension.setEventsInhibited(false);
        }
        assertEquals(0, asked[0]);
        assertEquals(customHash, LargeMountainStrokeBenchmark.digest(dimension));
        assertEquals("drag state unchanged", level, field(brush, "previousWaterLevel"));
        assertEquals("drag state unchanged", depth, field(brush, "depth"));
    }

    private static void tick(BrushPipelineTestView view, RiverPaint brush, Dimension dimension, int[] centre, boolean first, boolean nativeMode) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        LargeMountainStrokeBenchmark.bind(view, dimension);
        dimension.armSavePoint();
        try {
            brush.tick(centre[0], centre[1], false, first, 1.0f);
        } finally {
            LargeMountainStrokeBenchmark.bind(view, null);
        }
    }

    /** The level step 1 of the Java tick would find on the current state, before clamping to the previous level. */
    private static int edgeLevel(Dimension dimension, RiverPaint brush, int centreX, int centreY, int r) {
        int level = Integer.MAX_VALUE;
        for (int x = centreX - r; x <= centreX + r; x++) for (int y = centreY - r; y <= centreY + r; y++) {
            int height;
            if ((! floods(brush, centreX, centreY, x, y, r)) && (dimension.getWaterLevelAt(x, y) < (height = dimension.getIntHeightAt(x, y)))
                    && (height < level) && (floods(brush, centreX, centreY, x - 1, y, r) || floods(brush, centreX, centreY, x, y - 1, r)
                    || floods(brush, centreX, centreY, x + 1, y, r) || floods(brush, centreX, centreY, x, y + 1, r))) {
                level = height;
            }
        }
        return level;
    }

    private static boolean floods(RiverPaint brush, int centreX, int centreY, int x, int y, int r) {
        return (Math.abs(x - centreX) <= r) && (Math.abs(y - centreY) <= r) && (brush.getFullStrength(centreX, centreY, x, y) > 0.25f);
    }

    private static Map<String, Integer> events(Dimension dimension) {
        final Map<String, Integer> result = new TreeMap<>();
        for (Tile tile: dimension.getTiles()) tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                new Class<?>[] {Tile.Listener.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "listener";
                        };
                    }
                    result.merge(tile.getX() + "," + tile.getY() + ":" + method.getName(), 1, Integer::sum);
                    return null;
                }));
        return result;
    }

    private static Brush countingBrush(Brush delegate, int[] callbacks) {
        return (Brush) Proxy.newProxyInstance(Brush.class.getClassLoader(), new Class<?>[] {Brush.class}, (proxy, call, args) -> {
            if (call.getName().equals("getFullStrength") || call.getName().equals("getStrength")) callbacks[0]++;
            if (call.getName().equals("clone")) return proxy;
            return call.invoke(delegate, args);
        });
    }

    private static Object field(Object object, String name) throws Exception {
        final Field field = RiverPaint.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
