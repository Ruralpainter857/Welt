package org.pepsoft.worldpainter.selection;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.util.Random;
import static org.junit.Assert.*;

public class SelectionBrushParityTest {
    static final class Draws implements java.util.function.DoubleSupplier {
        final Random random = new Random(781); long count;
        @Override public double getAsDouble() { count++; return random.nextDouble(); }
    }
    @Test public void actualBrushOperationsKeepSelectionAndRandomDrawOrder() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        String oldCompact = System.getProperty("wp.native.gen.selectionCompact");
        System.setProperty("wp.native.gen.selectionCompact", "true");
        try {
            for (boolean filtered : new boolean[] {false, true}) for (int radius : new int[] {33, 127}) {
                Dimension expected = SelectionBrushBenchmark.fixture(), actual = SelectionBrushBenchmark.fixture();
                Draws javaDraws = new Draws(), rustDraws = new Draws();
                SelectionHelper java = new SelectionHelper(expected, javaDraws), rust = new SelectionHelper(actual, rustDraws);
                Brush source = SymmetricBrush.LINEAR_SQUARE.clone(); source.setRadius(radius); source.setLevel(0.7f);
                Brush brush = RotatedBrush.rotate(source, 17);
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                org.pepsoft.worldpainter.operations.Filter javaFilter = filtered ? (x, y, strength) -> expected.getBitLayerValueAt(SelectionBlock.INSTANCE, x, y) ? 0f : strength : null;
                org.pepsoft.worldpainter.operations.Filter rustFilter = filtered ? (x, y, strength) -> actual.getBitLayerValueAt(SelectionBlock.INSTANCE, x, y) ? 0f : strength : null;
                for (int stroke = 0; stroke < 12; stroke++) {
                    int cx = stroke % 3 == 0 ? -1 : 64, cy = stroke % 3 == 0 ? -1 : 64;
                    System.setProperty(Native.GEN_KEY, "false");
                    if ((stroke & 1) == 0) java.addToSelection(cx, cy, brush, javaFilter, 0.65f, null);
                    else java.removeFromSelection(cx, cy, brush, javaFilter, 0.65f, null);
                    System.setProperty(Native.GEN_KEY, "true");
                    if ((stroke & 1) == 0) rust.addToSelection(cx, cy, brush, rustFilter, 0.65f, null);
                    else rust.removeFromSelection(cx, cy, brush, rustFilter, 0.65f, null);
                    assertEquals("filtered=" + filtered + ", radius=" + radius + ", stroke=" + stroke, javaDraws.count, rustDraws.count); compare(expected, actual);
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false);
            }
        } finally {
            if (oldCompact == null) System.clearProperty("wp.native.gen.selectionCompact"); else System.setProperty("wp.native.gen.selectionCompact", oldCompact);
            if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
    }
    private static void compare(Dimension java, Dimension rust) {
        for (Tile expected : java.getTiles()) {
            Tile actual = rust.getTile(expected.getX(), expected.getY()); assertEquals(expected.getLayers(), actual.getLayers());
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                assertEquals(expected.getBitLayerValue(SelectionBlock.INSTANCE, x, y), actual.getBitLayerValue(SelectionBlock.INSTANCE, x, y));
            for (int x = 0; x < 128; x += 16) for (int y = 0; y < 128; y += 16)
                assertEquals(expected.getBitLayerValue(SelectionChunk.INSTANCE, x, y), actual.getBitLayerValue(SelectionChunk.INSTANCE, x, y));
        }
    }
}
