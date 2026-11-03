package org.pepsoft.worldpainter.threedeeview;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.*;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.NotPresent;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

/** Exact whole-image comparisons against Java2D, including all four viewing directions. */
public class IsometricRenderParityTest {
    private final Map<String, String> previous = new HashMap<>();
    @Before public void prepare() {
        boolean available = !GraphicsEnvironment.isHeadless() && NativeLoader.areSlicesAvailable();
        if (Boolean.getBoolean("welt.test.requireIsometric")) assertTrue("Screen and release JNI required", available);
        else Assume.assumeTrue(available);
        for (String key : new String[]{Native.GEN_KEY, Native.RENDER_KEY, "welt.native.isometric", "welt.native.viewport"}) previous.put(key, System.getProperty(key));
        System.setProperty(Native.GEN_KEY, "false");
        System.setProperty("welt.native.viewport", "false");
    }
    @After public void restore() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    @Test public void reliefWaterLavaFrostAndMissingColumnsMatchInEveryDirection() {
        Dimension dimension = IsometricSceneBenchmark.scene();
        for (int rotation = 0; rotation < 4; rotation++) {
            var java = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), rotation,
                    Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
            var rust = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), rotation,
                    Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
            for (Tile tile : dimension.getTiles()) {
                select(false); BufferedImage expected = java.render(tile);
                select(true); long before = IsometricRenderAccess.completedTiles(); BufferedImage actual = rust.render(tile);
                assertEquals("Native coverage", 1, IsometricRenderAccess.completedTiles() - before);
                compare(expected, actual, "rotation=" + rotation + " tile=" + tile.getX() + "," + tile.getY());
            }
        }
    }
    @Test public void customMaterialsAnchorsAndLayerVisibilityRemainExactAfterEdits() {
        Dimension dimension = IsometricSceneBenchmark.scene(); Tile tile = dimension.getTile(0, 0);
        MixedMaterial[] saved = {Terrain.getCustomMaterial(0), Terrain.getCustomMaterial(1), Terrain.getCustomMaterial(2)};
        MixedMaterial.Row[] rows = {new MixedMaterial.Row(Material.STONE, 600, 1f), new MixedMaterial.Row(Material.DIRT, 400, .7f)};
        try {
            Terrain.setCustomMaterial(0, new MixedMaterial("Fixed colour", rows, -1, 0x92618e, 1f));
            Terrain.setCustomMaterial(1, new MixedMaterial("Blobs", rows, -1, null, 1f));
            Terrain.setCustomMaterial(2, new MixedMaterial("Layered", rows, -1, null, new NoiseSettings(0, 7, 2, 1f), -.7, .3, true));
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) tile.setTerrain(x,y, Terrain.getCustomTerrain(x % 3));
            dimension.setTopLayerMinDepth(7); dimension.setTopLayerVariation(1);
            for (Dimension.LayerAnchor anchor : Dimension.LayerAnchor.values()) {
                dimension.setTopLayerAnchor(anchor);
                for (Tile3DRenderer.LayerVisibilityMode mode : Tile3DRenderer.LayerVisibilityMode.values()) {
                    Set<org.pepsoft.worldpainter.layers.Layer> hidden = new HashSet<>(Arrays.asList(Frost.INSTANCE, TileRenderer.FLUIDS_AS_LAYER));
                    var java = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), 1, mode, hidden);
                    var rust = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), 1, mode, hidden);
                    for (int edit = 0; edit < 2; edit++) {
                        tile.setHeight(7, 9, 65 + edit * 31); tile.setWaterLevel(7,9, 105 - edit * 11);
                        tile.setBitLayerValue(NotPresent.INSTANCE, 32,32, edit != 0);
                        dimension.setCoverSteepTerrain(edit != 0);
                        select(false); BufferedImage expected = java.render(tile);
                        select(true); long before = IsometricRenderAccess.completedTiles(); BufferedImage actual = rust.render(tile);
                        assertEquals(1, IsometricRenderAccess.completedTiles() - before);
                        compare(expected, actual, anchor + " " + mode + " edit=" + edit);
                    }
                }
            }
        } finally { for (int i = 0; i < saved.length; i++) Terrain.setCustomMaterial(i, saved[i]); }
    }
    @Test public void customColourSchemesKeepJavaCallbacksAndExactPixels() {
        Dimension dimension = IsometricSceneBenchmark.scene(); Tile tile = dimension.getTile(-1,-1);
        ColourScheme custom = material -> ColourScheme.DEFAULT.getColour(material) ^ 0x000f0f0f;
        var java = new Tile3DRenderer(dimension, custom, new CustomBiomeManager(), 3, Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
        var rust = new Tile3DRenderer(dimension, custom, new CustomBiomeManager(), 3, Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
        select(false); BufferedImage expected = java.render(tile);
        select(true); long before = IsometricRenderAccess.completedTiles(); BufferedImage actual = rust.render(tile);
        assertEquals(0, IsometricRenderAccess.completedTiles() - before); compare(expected, actual, "custom colour scheme fallback");
    }
    @Test public void customTilesAndNeighbourHeightCallbacksRetainTheCompleteJavaPath() {
        Dimension dimension = IsometricSceneBenchmark.scene();
        dimension.addTile(new Tile(-2, -1, -64, 320) {
            @Override public int getIntHeight(int x, int y) { return 12; }
        });
        assertFallback(dimension, dimension.getTile(-1,-1), "custom neighbour height");
        Tile custom = new Tile(0, 0, -64, 320) {
            @Override public Terrain getTerrain(int x, int y) { return Terrain.STONE; }
        };
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) custom.setHeight(x,y,68);
        dimension.removeTile(new java.awt.Point(0,0)); dimension.addTile(custom);
        assertFallback(dimension, custom, "custom centre tile terrain");
    }
    @Test public void randomCustomMaterialsAreNeverPreparedAsNativeColumns() {
        Dimension dimension = IsometricSceneBenchmark.scene(); Tile tile = dimension.getTile(0,0);
        MixedMaterial saved = Terrain.getCustomMaterial(3);
        try {
            MixedMaterial noise = new MixedMaterial("Random material", new MixedMaterial.Row[]{
                    new MixedMaterial.Row(Material.STONE,600,1f), new MixedMaterial.Row(Material.DIRT,400,1f)}, -1, null);
            assertEquals(MixedMaterial.Mode.NOISE, noise.getMode()); Terrain.setCustomMaterial(3,noise);
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) tile.setTerrain(x,y,Terrain.getCustomTerrain(3));
            select(true); long before = IsometricRenderAccess.completedTiles();
            BufferedImage image = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), 0,
                    Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet()).render(tile);
            assertNotSame(ThreeDeeView.TILE_NOT_RENDERABLE,image); assertEquals(256,image.getWidth());
            assertEquals("Random state must remain on the original Java path",0,IsometricRenderAccess.completedTiles()-before);
        } finally { Terrain.setCustomMaterial(3,saved); }
    }
    private static void assertFallback(Dimension dimension, Tile tile, String label) {
        var java = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), 0,
                Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
        var rust = new Tile3DRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), 0,
                Tile3DRenderer.LayerVisibilityMode.SURFACE, Collections.emptySet());
        select(false); BufferedImage expected = java.render(tile);
        select(true); long before = IsometricRenderAccess.completedTiles(); BufferedImage actual = rust.render(tile);
        assertEquals(label,0,IsometricRenderAccess.completedTiles()-before); compare(expected,actual,label);
    }
    static void select(boolean nativeMode) {
        System.setProperty(Native.RENDER_KEY, Boolean.toString(nativeMode));
        System.setProperty("welt.native.isometric", Boolean.toString(nativeMode));
    }
    static void compare(BufferedImage expected, BufferedImage actual, String label) {
        assertNotSame("Java scene failed", ThreeDeeView.TILE_NOT_RENDERABLE, expected);
        assertNotSame("Native scene failed", ThreeDeeView.TILE_NOT_RENDERABLE, actual);
        assertEquals(label, expected.getWidth(), actual.getWidth()); assertEquals(label, expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) for (int x = 0; x < expected.getWidth(); x++) {
            int a = expected.getRGB(x,y), b = actual.getRGB(x,y);
            if (a != b) fail(label + " pixel=" + x + "," + y + " expected=" + Integer.toHexString(a) + " actual=" + Integer.toHexString(b));
        }
    }
}
