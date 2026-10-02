package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
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
