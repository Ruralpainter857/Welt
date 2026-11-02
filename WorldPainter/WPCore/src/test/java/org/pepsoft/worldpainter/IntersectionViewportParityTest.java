package org.pepsoft.worldpainter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.tunnel.TunnelLayer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Complete pixel arrays of linked dimensions with intersections, rendered by Java and by the native viewport frame:
 * surface and inverted ceiling (with missing ceiling tiles), tunnel roofs in each supported mode with and without
 * noise (and floor tiles without detail tile), all zoom levels, light origins, hidden layers and settings or tiles
 * changed between two draws. Native tile counters prove which tiles used the native transaction; cases Java must keep
 * (an unsupported roof mode set after the renderer was created) are checked to fall back completely.
 */
public class IntersectionViewportParityTest {
    @Before
    public void requireNative() {
        org.junit.Assume.assumeTrue("welt_slices required", NativeLoader.areSlicesAvailable());
        previous.put(Native.RENDER_KEY, System.getProperty(Native.RENDER_KEY));
        previous.put("welt.native.viewport", System.getProperty("welt.native.viewport"));
        previous.put(Native.GEN_KEY, System.getProperty(Native.GEN_KEY));
        System.setProperty(Native.GEN_KEY, "false");
    }

    @After
    public void restore() {
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    public void ceilingAndSurfaceIntersectionsMatchJavaAtEveryZoom() {
        final IntersectionViewportFixtures.Worlds worlds = worlds(TunnelLayer.Mode.CONSTANT_DEPTH, false);
        for (int zoom: new int[] {0, -1, -2, -3, -7}) {
            assertParity("surface zoom " + zoom, worlds.surface, worlds.surfaceTiles, zoom, null, true);
            assertParity("ceiling zoom " + zoom, worlds.ceiling, worlds.ceilingTiles, zoom, null, true);
        }
    }

    @Test
    public void ceilingIntersectionFollowsLightLayersAndChangesBetweenDraws() {
        final IntersectionViewportFixtures.Worlds worlds = worlds(TunnelLayer.Mode.CONSTANT_DEPTH, false);
        final Pair pair = new Pair(worlds.surface, 0, renderer -> {
            renderer.setLightOrigin(TileRenderer.LightOrigin.NORTHEAST);
            renderer.setContourLines(false);
            renderer.setHiddenLayers(new HashSet<>(Collections.singleton(Frost.INSTANCE)));
        });
        final int intersections = pair.assertParity("first draw", worlds.surfaceTiles, true);
        assertTrue("fixture must intersect", intersections > 1000);
        // Settings and linked tiles change between two draws of the same renderers
        worlds.ceiling.setCeilingHeight(worlds.ceiling.getCeilingHeight() - 9);
        worlds.ceiling.removeTile(new java.awt.Point(worlds.ceilingTiles.get(0).getX(), worlds.ceilingTiles.get(0).getY()));
        final Tile ceilingTile = worlds.ceilingTiles.get(1);
        ceilingTile.setHeight(5, 7, ceilingTile.getHeight(5, 7) + 30);
        assertNotEquals("changes must change the intersections", intersections, pair.assertParity("after changes", worlds.surfaceTiles, true));
        final Pair zoomed = new Pair(worlds.ceiling, -2, renderer -> renderer.setLightOrigin(TileRenderer.LightOrigin.ABOVE));
        zoomed.assertParity("zoomed ceiling", worlds.ceilingTiles, true);
    }

    @Test
    public void tunnelRoofIntersectionsMatchJavaInEveryMode() {
        for (TunnelLayer.Mode mode: new TunnelLayer.Mode[] {TunnelLayer.Mode.CONSTANT_DEPTH, TunnelLayer.Mode.FIXED_HEIGHT, TunnelLayer.Mode.INVERTED_DEPTH}) {
            for (boolean noise: new boolean[] {false, true}) {
                final IntersectionViewportFixtures.Worlds worlds = worlds(mode, noise);
                for (int zoom: new int[] {0, -1, -4}) {
                    assertParity(mode + " noise=" + noise + " zoom " + zoom, worlds.floor, worlds.floorTiles, zoom, null, nativeTunnelNoise || ! noise);
                }
            }
        }
    }

    @Test
    public void tunnelRoofSettingsChangedBetweenDrawsAndUnsupportedModesFallBack() {
        final IntersectionViewportFixtures.Worlds worlds = worlds(TunnelLayer.Mode.CONSTANT_DEPTH, false);
        final Pair pair = new Pair(worlds.floor, 0, renderer -> renderer.setContourLines(false));
        final int intersections = pair.assertParity("first draw", worlds.floorTiles, true);
        assertTrue("fixture must intersect", intersections > 1000);
        worlds.tunnel.setRoofLevel(worlds.tunnel.getRoofLevel() - 7);
        worlds.tunnel.setRoofMax(worlds.tunnel.getRoofMax() - 100);
        final Tile detail = worlds.surfaceTiles.get(3);
        detail.setHeight(9, 9, detail.getHeight(9, 9) - 25);
        assertNotEquals("changes must change the intersections", intersections, pair.assertParity("after setting changes", worlds.floorTiles, true));
        // The helper fixed its roof noise when the renderers were created; changing it on the layer changes neither
        worlds.tunnel.setRoofNoise(new NoiseSettings(5L, 12, 3, 2f));
        pair.assertParity("after noise change", worlds.floorTiles, true);
        // The renderer only excludes this mode when it is created; set later, Java must render every tile
        worlds.tunnel.setRoofMode(TunnelLayer.Mode.FIXED_HEIGHT_ABOVE_FLOOR);
        pair.assertParity("unsupported mode", worlds.floorTiles, false);
    }

    private static IntersectionViewportFixtures.Worlds worlds(TunnelLayer.Mode roofMode, boolean noise) {
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        return IntersectionViewportFixtures.create(4, roofMode, noise, platform.minZ, platform.standardMaxHeight);
    }

    private void assertParity(String label, Dimension dimension, List<Tile> tiles, int zoom, java.util.function.Consumer<TileRenderer> settings, boolean nativeExpected) {
        new Pair(dimension, zoom, settings).assertParity(label, tiles, nativeExpected);
    }

    /** A Java and a native renderer of the same dimension, drawing into their own images. */
    private static final class Pair {
        Pair(Dimension dimension, int zoom, java.util.function.Consumer<TileRenderer> settings) {
            java = IntersectionViewportFixtures.renderer(dimension, zoom);
            fused = IntersectionViewportFixtures.renderer(dimension, zoom);
            if (settings != null) {
                settings.accept(java);
                settings.accept(fused);
            }
            this.zoom = zoom;
        }

        /** @return The number of opaque black pixels: with contour lines off these are only intersections. */
        int assertParity(String label, List<Tile> tiles, boolean nativeExpected) {
            final int tilePixels = 128 >> -zoom, side = 8, origin = 3;
            final BufferedImage javaImage = new BufferedImage(side * tilePixels, side * tilePixels, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage fusedImage = new BufferedImage(side * tilePixels, side * tilePixels, BufferedImage.TYPE_INT_ARGB);
            final long nativeBefore = fused.completedNativeViewportTiles();
            select(false);
            for (Tile tile: tiles) {
                java.renderTile(tile, javaImage, (tile.getX() + origin) * tilePixels, (tile.getY() + origin) * tilePixels);
            }
            select(true);
            for (Tile tile: tiles) {
                fused.renderTile(tile, fusedImage, (tile.getX() + origin) * tilePixels, (tile.getY() + origin) * tilePixels);
            }
            final int[] expected = pixels(javaImage), actual = pixels(fusedImage);
            final int mismatch = Arrays.mismatch(expected, actual);
            if (mismatch >= 0) {
                fail(label + ": pixel " + (mismatch % javaImage.getWidth()) + "," + (mismatch / javaImage.getWidth()) + " expected "
                        + Integer.toHexString(expected[mismatch]) + " but was " + Integer.toHexString(actual[mismatch]));
            }
            assertEquals(label + ": tiles rendered natively", nativeExpected ? tiles.size() : 0, fused.completedNativeViewportTiles() - nativeBefore);
            assertTrue(label + ": no pixel drawn", Arrays.stream(actual).anyMatch(pixel -> pixel != 0));
            return (int) Arrays.stream(actual).filter(pixel -> pixel == 0xff000000).count();
        }

        private static void select(boolean nativeRendering) {
            System.setProperty(Native.RENDER_KEY, Boolean.toString(nativeRendering));
            System.setProperty("welt.native.viewport", Boolean.toString(nativeRendering));
        }

        private static int[] pixels(BufferedImage image) {
            return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        }

        private final TileRenderer java, fused;
        private final int zoom;
    }

    private final Map<String, String> previous = new HashMap<>();

    /** Tunnel roofs with noise are rendered natively too. */
    private static final boolean nativeTunnelNoise = true;
}
