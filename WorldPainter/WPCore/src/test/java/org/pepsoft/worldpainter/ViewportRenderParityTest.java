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
    @Test public void indexedBiomesAnnotationsAndLiveCustomPatternsMatch() { indexedPair(0); }
    @Test public void zoomedIndexedBiomesAnnotationsAndLiveCustomPatternsMatch() {
        for (int zoom : new int[] {-1, -2, -3, -7}) indexedPair(zoom);
    }
    private static void indexedPair(int zoom) {
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
                    // Keep a live custom pattern visible even in the one-pixel tile fixture.
                    tile.setLayerValue(Biome.INSTANCE, 0, 0, 200);
                    tile.setLayerValue(Annotations.INSTANCE, 0, 0, 0);
                    tile.setHeight(0, 0, 71);
                    tile.setBitLayerValue(ReadOnly.INSTANCE, 0, 0, false);
                    tile.setBitLayerValue(Frost.INSTANCE, 0, 0, false);
                } finally { tile.releaseEvents(); }
            }
            for (TileRenderer.LightOrigin light : TileRenderer.LightOrigin.values()) {
                TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, manager, zoom, true, null);
                TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, manager, zoom, true, null);
                java.setLightOrigin(light); rust.setLightOrigin(light);
                BufferedImage expected = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                BufferedImage actual = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                int[] before = null;
                for (int pass = 0; pass < 2; pass++) {
                    for (Tile tile : d.getTiles()) {
                        int dx = (tile.getX() + 1) * (128 >> -zoom), dy = (tile.getY() + 1) * (128 >> -zoom);
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
        customPaintPair(paint, opacity, supported, 0);
    }
    @Test public void zoomedCustomPaintUsesPhysicalAndGlobalCoordinates() {
        BufferedImage image = new BufferedImage(5, 9, BufferedImage.TYPE_INT_ARGB_PRE);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 5; x++) image.setRGB(x, y, ((x + y) % 4 * 85 << 24) | (x * 51 << 16) | (y * 28 << 8) | 0x39);
        for (int zoom : new int[] {-1, -2, -3, -7}) customPaintPair(image, .37f, true, zoom);
    }
    private static void customPaintPair(Object paint, float opacity, boolean supported, int zoom) {
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
                TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), zoom, true, null);
                TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), zoom, true, null);
                java.setLightOrigin(light); rust.setLightOrigin(light);
                BufferedImage expected = new BufferedImage(250, 252, BufferedImage.TYPE_INT_ARGB);
                BufferedImage actual = new BufferedImage(250, 252, BufferedImage.TYPE_INT_ARGB);
                for (Tile tile : d.getTiles()) {
                    int size = 128 >> -zoom;
                    int dx = (tile.getX() + 1) * size - (zoom == 0 ? 8 : 0), dy = (tile.getY() + 1) * size - (zoom == 0 ? 4 : 0);
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
        compare(light, hideFluid, masks, hideLayers, unsupported, clipped, 0);
    }
    @Test public void zoomedCompositionPreservesMasksLightingAndFallback() {
        for (int zoom : new int[] {-1, -2, -3, -7}) for (TileRenderer.LightOrigin light : TileRenderer.LightOrigin.values())
            compare(light, false, true, false, false, true, zoom);
        compare(TileRenderer.LightOrigin.NORTHWEST, true, true, true, false, false, -2);
        compare(TileRenderer.LightOrigin.NORTHWEST, false, true, false, true, false, -2);
    }
    private static void compare(TileRenderer.LightOrigin light, boolean hideFluid, boolean masks, boolean hideLayers, boolean unsupported, boolean clipped, int zoom) {
        String gen = System.getProperty(Native.GEN_KEY), render = System.getProperty(Native.RENDER_KEY), flag = System.getProperty("welt.native.viewport");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(masks, unsupported);
            TileRenderer java = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), zoom, true, null);
            TileRenderer rust = new TileRenderer(d, ColourScheme.DEFAULT, new CustomBiomeManager(), zoom, true, null);
            java.setLightOrigin(light); rust.setLightOrigin(light);
            java.setHideAllLayers(hideLayers); rust.setHideAllLayers(hideLayers);
            if (hideFluid) {
                java.setHiddenLayers(new HashSet<>(Set.of(TileRenderer.FLUIDS_AS_LAYER)));
                rust.setHiddenLayers(new HashSet<>(Set.of(TileRenderer.FLUIDS_AS_LAYER)));
            }
            BufferedImage expected = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            BufferedImage actual = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            for (Tile tile : d.getTiles()) {
                int size = 128 >> -zoom;
                int dx = (tile.getX() + 1) * size - (clipped ? (zoom == 0 ? 8 : 1) : 0), dy = (tile.getY() + 1) * size - (clipped ? (zoom == 0 ? 4 : 1) : 0);
                System.setProperty(Native.RENDER_KEY, "false"); java.renderTile(tile, expected, dx, dy);
                System.setProperty(Native.RENDER_KEY, "true"); System.setProperty("welt.native.viewport", "true"); rust.renderTile(tile, actual, dx, dy);
            }
            assertArrayEquals(((DataBufferInt) expected.getRaster().getDataBuffer()).getData(), ((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
            assertEquals(unsupported ? 0L : 4L, rust.completedNativeViewportTiles());
        } finally { restore(Native.GEN_KEY, gen); restore(Native.RENDER_KEY, render); restore("welt.native.viewport", flag); }
    }
    @Test public void sampledSnapshotsMatchGettersForNormalAndTallTiles() {
        for (int min : new int[] {0, -64}) {
            Tile tile = new Tile(-1, 0, min, min == 0 ? 128 : 320);
            tile.inhibitEvents();
            try {
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                    tile.setHeight(x, y, min + 8 + ((x * 3 + y * 7) & 255) / 4f);
                    tile.setWaterLevel(x, y, min + 42);
                    tile.setTerrain(x, y, (x & 4) == 0 ? Terrain.GRASS : Terrain.SAND);
                    tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3) & 15);
                    tile.setBitLayerValue(Frost.INSTANCE, x, y, ((x + y) & 3) == 0);
                    tile.setBitLayerValue(ReadOnly.INSTANCE, x, y, (x / 16 + y / 16) % 3 == 0);
                }
            } finally { tile.releaseEvents(); }
            for (int shift = 1; shift <= 7; shift++) {
                int width = 128 >> shift, area = width * width;
                java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(area * 41).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                tile.copySampledRenderPoints(shift, data, 0, area, area * 21);
                for (int row = 0; row < width; row++) for (int col = 0; col < width; col++) {
                    int x = col << shift, y = row << shift, point = col + row * width;
                    assertEquals(tile.getTerrain(x, y).ordinal(), data.get(point) & 255);
                    for (int neighbor = 0; neighbor < 5; neighbor++) {
                        int nx = x + (neighbor == 2 ? -1 : neighbor == 3 ? 1 : 0);
                        int ny = y + (neighbor == 1 ? -1 : neighbor == 4 ? 1 : 0);
                        boolean inside = nx >= 0 && ny >= 0 && nx < 128 && ny < 128;
                        int height = inside ? tile.getIntHeight(nx, ny) : org.pepsoft.minecraft.Constants.DEFAULT_WATER_LEVEL;
                        int wet = inside && tile.getWaterLevel(nx, ny) > height ? tile.getWaterLevel(nx, ny) : Integer.MIN_VALUE;
                        assertEquals(height, data.getInt(area + (point * 5 + neighbor) * 4));
                        assertEquals(wet, data.getInt(area * 21 + (point * 5 + neighbor) * 4));
                    }
                }
                for (Layer layer : new Layer[] {Frost.INSTANCE, ReadOnly.INSTANCE, Resources.INSTANCE, Biome.INSTANCE}) {
                    int bits = layer.dataSize == Layer.DataSize.BIT ? 1 : layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 0 : layer.dataSize == Layer.DataSize.NIBBLE ? 4 : 8;
                    int bytes = bits == 0 ? 8 : (area * bits + 7) / 8;
                    java.nio.ByteBuffer plane = java.nio.ByteBuffer.allocate(bytes + 2);
                    plane.put(0, (byte) 93); plane.put(bytes + 1, (byte) 71);
                    tile.copySampledLayerPlane(layer, bits, shift, plane, 1);
                    assertEquals(93, plane.get(0)); assertEquals(71, plane.get(bytes + 1));
                    for (int row = 0; row < width; row++) for (int col = 0; col < width; col++) {
                        int x = col << shift, y = row << shift;
                        int index = bits == 0 ? x / 16 + y / 16 * 8 : col + row * width;
                        int stride = bits == 0 ? 1 : bits;
                        int actual = (plane.get(1 + index * stride / 8) >>> (index * stride & 7)) & ((1 << stride) - 1);
                        assertEquals(bits <= 1 ? (tile.getBitLayerValue(layer, x, y) ? 1 : 0) : tile.getLayerValue(layer, x, y), actual);
                    }
                }
            }
        }
    }

    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
