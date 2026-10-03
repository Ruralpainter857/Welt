package org.pepsoft.worldpainter;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Arrays;
import org.junit.Test;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.biomeschemes.CustomBiome;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class ViewportRenderParityTest {
    @Test public void allLightingDirectionsMatchWithMissingAndPresentNeighbors() {
        for (TileRenderer.LightOrigin light : TileRenderer.LightOrigin.values()) compare(light, false, false, false, false);
    }
    @Test public void hiddenFluidsPreserveFrostAndFluidShadingRules() { compare(TileRenderer.LightOrigin.NORTHEAST, true, false, false, false); }
    @Test public void voidMissingChunksAndBlocksPreserveAlphaAndPriority() { compare(TileRenderer.LightOrigin.SOUTHWEST, false, true, false, false); }
    @Test public void hiddenLayersStillPreserveVoidAndMissingMasks() { compare(TileRenderer.LightOrigin.ABOVE, false, true, true, false); }
    @Test public void unsupportedMixedTerrainKeepsJavaFallback() { compare(TileRenderer.LightOrigin.NORTHWEST, false, false, false, true); }
    @Test public void unzoomedAndClippedDestinationPixelsMatch() {
        compare(TileRenderer.LightOrigin.SOUTHEAST, false, true, false, false, true);
    }
    @Test public void indexedBiomesAnnotationsAndLiveCustomPatternsMatch() {
        String gen = System.getProperty(Native.GEN_KEY), render = System.getProperty(Native.RENDER_KEY), flag = System.getProperty("welt.native.viewport");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension d = fixture(false, false);
            BufferedImage rgb = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            BufferedImage alpha = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB_PRE);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                rgb.setRGB(x, y, 0x010305 + x * 257 + y * 65536);
                alpha.setRGB(x, y, (x % 3 == 0 ? 0 : x % 3 == 1 ? 0x01000000 : 0xff000000) | 0x123456);
            }
            CustomBiome first = new CustomBiome("RGB", 200), second = new CustomBiome("Alpha", 201);
            first.setPattern(rgb); second.setPattern(alpha);
            CustomBiomeManager manager = new CustomBiomeManager(); manager.setCustomBiomes(List.of(first, second));
            for (Tile tile : d.getTiles()) {
                tile.inhibitEvents();
                try {
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                        tile.setLayerValue(Biome.INSTANCE, x, y, (x + y * 7) & 255);
                        tile.setLayerValue(Annotations.INSTANCE, x, y, (x & 7) == 0 ? (x / 8 + y) & 15 : 0);
                    }
                } finally { tile.releaseEvents(); }
            }
            for (TileRenderer.LightOrigin light : TileRenderer.LightOrigin.values()) {
                TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, manager, 0, true, null);
                TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, manager, 0, true, null);
                java.setLightOrigin(light); rust.setLightOrigin(light);
                BufferedImage expected = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                BufferedImage actual = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                int[] before = null;
                for (int pass = 0; pass < 2; pass++) {
                    for (Tile tile : d.getTiles()) {
                        int dx = (tile.getX() + 1) << 7, dy = (tile.getY() + 1) << 7;
                        System.setProperty(Native.RENDER_KEY, "false"); java.renderTile(tile, expected, dx, dy);
                        System.setProperty(Native.RENDER_KEY, "true"); System.setProperty("welt.native.viewport", "true"); rust.renderTile(tile, actual, dx, dy);
                    }
                    int[] pixels = ((DataBufferInt) actual.getRaster().getDataBuffer()).getData();
                    assertArrayEquals(((DataBufferInt) expected.getRaster().getDataBuffer()).getData(), pixels);
                    if (pass == 0) {
                        before = pixels.clone();
                        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) rgb.setRGB(x, y, rgb.getRGB(x, y) ^ 0x00ffffff);
                        alpha.setRGB(5, 5, alpha.getRGB(5, 5) ^ 0x00ffffff);
                    } else assertFalse("Live custom RGB pattern changes must be visible", Arrays.equals(before, pixels));
                }
                assertEquals(8L, rust.completedNativeViewportTiles());
            }
        } finally { restore(Native.GEN_KEY, gen); restore(Native.RENDER_KEY, render); restore("welt.native.viewport", flag); }
    }
    @Test public void customPaintPreservesOpacityPatternsAndGlobalCoordinates() {
        BufferedImage alpha = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB_PRE);
        BufferedImage tall = new BufferedImage(5, 9, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            alpha.setRGB(x, y, ((x + y) % 4 * 85 << 24) | (x * 17 << 16) | (y * 17 << 8) | 0x39);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 5; x++) tall.setRGB(x, y, x * 16384 + y * 257);
        for (Object paint : new Object[] {new Color(0x17395b), alpha, tall})
            for (float opacity : new float[] {0, .37f, 1, Float.NaN}) customPaintPair(paint, opacity, true);
        customPaintPair(new BufferedImage(257, 257, BufferedImage.TYPE_INT_RGB), .65f, false);
    }
    private static void customPaintPair(Object paint, float opacity, boolean supported) {
        String gen = System.getProperty(Native.GEN_KEY), render = System.getProperty(Native.RENDER_KEY), flag = System.getProperty("welt.native.viewport");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(true, false);
            CustomLayer nibble = new CustomLayer("Paint nibble", "Parity", Layer.DataSize.NIBBLE, 101, paint) { };
            CustomLayer bit = new CustomLayer("Paint bit", "Parity", Layer.DataSize.BIT, 102, paint) { };
            CustomLayer chunk = new CustomLayer("Paint chunk", "Parity", Layer.DataSize.BIT_PER_CHUNK, 103, paint) { };
            nibble.setOpacity(opacity); bit.setOpacity(opacity); chunk.setOpacity(opacity);
            for (Tile tile : d.getTiles()) {
                tile.inhibitEvents();
                try { for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                    tile.setLayerValue(nibble, x, y, (x + y * 3) & 15);
                    tile.setBitLayerValue(bit, x, y, ((x * 3 + y) & 7) == 0);
                    tile.setBitLayerValue(chunk, x, y, (x / 16 + y / 16) % 3 == 0);
                } } finally { tile.releaseEvents(); }
            }
            for (TileRenderer.LightOrigin light : TileRenderer.LightOrigin.values()) {
                TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
                TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
                java.setLightOrigin(light); rust.setLightOrigin(light);
                BufferedImage expected = new BufferedImage(250, 252, BufferedImage.TYPE_INT_ARGB);
                BufferedImage actual = new BufferedImage(250, 252, BufferedImage.TYPE_INT_ARGB);
                for (Tile tile : d.getTiles()) {
                    int dx = ((tile.getX() + 1) << 7) - 8, dy = ((tile.getY() + 1) << 7) - 4;
                    System.setProperty(Native.RENDER_KEY, "false"); java.renderTile(tile, expected, dx, dy);
                    System.setProperty(Native.RENDER_KEY, "true"); System.setProperty("welt.native.viewport", "true"); rust.renderTile(tile, actual, dx, dy);
                }
                assertArrayEquals("Custom paint opacity=" + opacity + " light=" + light,
                        ((DataBufferInt) expected.getRaster().getDataBuffer()).getData(), ((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
                assertEquals(supported ? 4L : 0L, rust.completedNativeViewportTiles());
            }
        } finally { restore(Native.GEN_KEY, gen); restore(Native.RENDER_KEY, render); restore("welt.native.viewport", flag); }
    }

    @Test public void rendererSnapshotsAndWideTextureFailureStayJavaCompatible() {
        String gen = System.getProperty(Native.GEN_KEY), render = System.getProperty(Native.RENDER_KEY), flag = System.getProperty("welt.native.viewport");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(false, false);
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) image.setRGB(x, y, 0xff17395b);
            CustomLayer layer = new CustomLayer("Snapshot", "Parity", Layer.DataSize.NIBBLE, 101, image) { };
            for (Tile tile : d.getTiles()) for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) tile.setLayerValue(layer, x, y, 15);
            TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
            TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
            BufferedImage expected = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), actual = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            for (int pass = 0; pass < 2; pass++) {
                for (Tile tile : d.getTiles()) {
                    int dx = (tile.getX() + 1) << 7, dy = (tile.getY() + 1) << 7;
                    System.setProperty(Native.RENDER_KEY, "false"); java.renderTile(tile, expected, dx, dy);
                    System.setProperty(Native.RENDER_KEY, "true"); System.setProperty("welt.native.viewport", "true"); rust.renderTile(tile, actual, dx, dy);
                }
                assertArrayEquals(((DataBufferInt) expected.getRaster().getDataBuffer()).getData(), ((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
                org.pepsoft.worldpainter.layers.renderers.PaintRenderer snapshot = (org.pepsoft.worldpainter.layers.renderers.PaintRenderer) layer.getRenderer();
                snapshot.getRed()[0] = 255; snapshot.getAlpha()[1] = Float.NaN;
                image.setRGB(2, 2, 0xffabcdef);
            }
            assertEquals(8L, rust.completedNativeViewportTiles());
            for (boolean nativeMode : new boolean[] {false, true}) {
                System.setProperty(Native.RENDER_KEY, Boolean.toString(nativeMode));
                TileRenderer renderer = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
                try {
                    new CustomLayer("Wide", "Original failure", Layer.DataSize.NIBBLE, 102,
                            new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)) { };
                    fail("Wide PaintRenderer construction must retain its original indexing failure");
                } catch (ArrayIndexOutOfBoundsException expectedFailure) { assertEquals(0L, renderer.completedNativeViewportTiles()); }
            }
        } finally { restore(Native.GEN_KEY, gen); restore(Native.RENDER_KEY, render); restore("welt.native.viewport", flag); }
    }

    private static Dimension fixture(boolean masks, boolean unsupported) {
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS, p.minZ, p.standardMaxHeight, 62, 62, false, false);
        Dimension d = new Dimension(new World2(p, p.minZ, p.standardMaxHeight), "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        for (int ty = -1; ty <= 0; ty++) for (int tx = -1; tx <= 0; tx++) {
            Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setHeight(x, y, 58 + ((x * 3 + y * 7) & 63) / 4f);
                tile.setWaterLevel(x, y, 64);
                tile.setTerrain(x, y, (x & 32) == 0 ? Terrain.GRASS : Terrain.SAND);
                tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3) & 15);
                tile.setLayerValue(DeciduousForest.INSTANCE, x, y, (x * 7 + y) & 15);
                tile.setLayerValue(PineForest.INSTANCE, x, y, (x + y * 5) & 15);
                tile.setBitLayerValue(River.INSTANCE, x, y, ((x + y) & 15) == 0);
                tile.setBitLayerValue(Frost.INSTANCE, x, y, ((x + y) & 7) == 0);
                tile.setBitLayerValue(ReadOnly.INSTANCE, x, y, (x / 16 + y / 16) % 7 == 0);
                tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, x < 32 && y < 32);
                if (masks) {
                    tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, x, y, x < 48 && y < 48);
                    tile.setBitLayerValue(NotPresent.INSTANCE, x, y, x >= 16 && x < 32 && y >= 16 && y < 32);
                    tile.setBitLayerValue(NotPresentBlock.INSTANCE, x, y, x < 64 && ((x + y) & 31) == 0);
                }
            }
            tile.setHeight(3, 3, p.minZ); tile.setWaterLevel(3, 3, p.minZ);
            if (unsupported) tile.setTerrain(64, 64, Terrain.STONE_MIX);
            tile.releaseEvents(); d.addTile(tile);
        }
        return d;
    }
    private static void compare(TileRenderer.LightOrigin light, boolean hideFluid, boolean masks, boolean hideLayers, boolean unsupported) {
        compare(light, hideFluid, masks, hideLayers, unsupported, false);
    }
    private static void compare(TileRenderer.LightOrigin light, boolean hideFluid, boolean masks, boolean hideLayers, boolean unsupported, boolean clipped) {
        String gen = System.getProperty(Native.GEN_KEY), render = System.getProperty(Native.RENDER_KEY), flag = System.getProperty("welt.native.viewport");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(masks, unsupported);
            TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
            TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), 0, true, null);
            java.setLightOrigin(light); rust.setLightOrigin(light);
            java.setHideAllLayers(hideLayers); rust.setHideAllLayers(hideLayers);
            if (hideFluid) {
                java.setHiddenLayers(new HashSet<>(Set.of(TileRenderer.FLUIDS_AS_LAYER)));
                rust.setHiddenLayers(new HashSet<>(Set.of(TileRenderer.FLUIDS_AS_LAYER)));
            }
            BufferedImage expected = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            BufferedImage actual = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            for (Tile tile : d.getTiles()) {
                int dx = ((tile.getX() + 1) << 7) - (clipped ? 8 : 0), dy = ((tile.getY() + 1) << 7) - (clipped ? 4 : 0);
                System.setProperty(Native.RENDER_KEY, "false"); java.renderTile(tile, expected, dx, dy);
                System.setProperty(Native.RENDER_KEY, "true"); System.setProperty("welt.native.viewport", "true"); rust.renderTile(tile, actual, dx, dy);
            }
            assertArrayEquals(((DataBufferInt) expected.getRaster().getDataBuffer()).getData(), ((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
            assertEquals(unsupported ? 0L : 4L, rust.completedNativeViewportTiles());
        } finally { restore(Native.GEN_KEY, gen); restore(Native.RENDER_KEY, render); restore("welt.native.viewport", flag); }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
