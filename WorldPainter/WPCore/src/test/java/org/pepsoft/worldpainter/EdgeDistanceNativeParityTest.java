package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.NotPresent;
import org.pepsoft.worldpainter.layers.tunnel.TunnelLayer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Verifies the Rust edge-distance slice against the original Java algorithm. */
public final class EdgeDistanceNativeParityTest {
    @Test
    public void jniKernelMatchesTheJavaEdgeWalkBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        Native.setExportEnabled(true);
        try {
            final int width = 17;
            final int height = 13;
            final float maxDistance = 5.5f;
            final byte[] mask = new byte[width * height];
            for (int y = 3; y < 10; y++) {
                for (int x = 4; x < 13; x++) {
                    if (!((x >= 7 && x <= 9) && (y >= 5 && y <= 7))) {
                        mask[y * width + x] = 1;
                    }
                }
            }
            final float[] nativeValues = NativeSlices.edgeDistances(width, height, maxDistance, mask);
            assertNotNull(nativeValues);
            final float[] javaValues = javaEdgeDistances(width, height, maxDistance, mask);
            assertEquals(javaValues.length, nativeValues.length);
            for (int i = 0; i < javaValues.length; i++) {
                assertEquals("index=" + i, Float.floatToRawIntBits(javaValues[i]),
                        Float.floatToRawIntBits(nativeValues[i]));
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void edgeHeightKernelMatchesGeometryUtilRasterizationBitForBit() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        Native.setExportEnabled(true);
        try {
            final int width = 23, height = 19, radius = 7;
            final byte[] sources = new byte[width * height];
            final float[] sourceHeights = new float[width * height];
            sources[8 * width + 9] = 1;
            sourceHeights[8 * width + 9] = 72.25f;
            sources[10 * width + 12] = 1;
            sourceHeights[10 * width + 12] = 81.5f;
            sources[0] = 1;
            sourceHeights[0] = -20.0f;

            final float[] nativeValues = NativeSlices.edgeHeights(width, height, radius,
                    -64.0f, sources, sourceHeights);
            assertNotNull(nativeValues);
            final float[] javaValues = javaEdgeHeights(width, height, radius, -64.0f,
                    sources, sourceHeights);
            for (int i = 0; i < javaValues.length; i++) {
                assertEquals("index=" + i, Float.floatToRawIntBits(javaValues[i]),
                        Float.floatToRawIntBits(nativeValues[i]));
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void tunnelRenderFlagEnablesDistanceKernelWithoutGeneralRenderKernels() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousRenderFlag = System.getProperty(Native.RENDER_KEY);
        final String previousTunnelFlag = System.getProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY);
        try {
            Native.setRenderEnabled(false);
            System.setProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY, "true");
            final int width = 15, height = 11;
            final byte[] mask = new byte[width * height];
            for (int y = 2; y < 9; y++) {
                for (int x = 3; x < 12; x++) {
                    if ((x != 7) || (y != 5)) {
                        mask[y * width + x] = 1;
                    }
                }
            }
            final float[] nativeValues = NativeSlices.edgeDistancesForRendering(width, height, 4.5f, mask);
            assertNotNull(nativeValues);
            final float[] javaValues = javaEdgeDistances(width, height, 4.5f, mask);
            for (int i = 0; i < javaValues.length; i++) {
                assertEquals("index=" + i, Float.floatToRawIntBits(javaValues[i]),
                        Float.floatToRawIntBits(nativeValues[i]));
            }
        } finally {
            restoreSystemProperty(Native.RENDER_KEY, previousRenderFlag);
            restoreSystemProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY, previousTunnelFlag);
        }
    }

    @Test
    public void dimensionBakeMatchesJavaAcrossTileBoundary() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 256, 128), 62);
            final Tile left = dimension.getTile(0, 0);
            final Tile right = dimension.getTile(1, 0);
            for (int y = 20; y < 81; y++) {
                for (int x = 90; x < 128; x++) {
                    left.setBitLayerValue(Frost.INSTANCE, x, y, true);
                }
                for (int x = 0; x < 36; x++) {
                    right.setBitLayerValue(Frost.INSTANCE, x, y, true);
                }
            }

            Native.setExportEnabled(true);
            final HeightMap nativeMap = dimension.getDistancesToEdge(Frost.INSTANCE, 12.5f);
            Native.setExportEnabled(false);
            final HeightMap javaMap = dimension.getDistancesToEdge(Frost.INSTANCE, 12.5f);
            for (int y = 20; y < 81; y++) {
                for (int x = 90; x < 164; x++) {
                    final int worldX = x;
                    final int worldY = y;
                    assertEquals("x=" + worldX + " y=" + worldY,
                            Double.doubleToRawLongBits(javaMap.getHeight(worldX, worldY)),
                            Double.doubleToRawLongBits(nativeMap.getHeight(worldX, worldY)));
                }
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void bitPerChunkLayerBakeMatchesJava() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
            final Tile tile = dimension.getTile(0, 0);
            for (int chunkY = 2; chunkY < 6; chunkY++) {
                for (int chunkX = 2; chunkX < 6; chunkX++) {
                    if (!((chunkX == 3) && (chunkY == 3))) {
                        tile.setBitLayerValue(NotPresent.INSTANCE, chunkX << 4, chunkY << 4, true);
                    }
                }
            }

            Native.setExportEnabled(true);
            final HeightMap nativeMap = dimension.getDistancesToEdge(NotPresent.INSTANCE, 9.5f);
            Native.setExportEnabled(false);
            final HeightMap javaMap = dimension.getDistancesToEdge(NotPresent.INSTANCE, 9.5f);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    if (tile.getBitLayerValue(NotPresent.INSTANCE, x, y)) {
                        assertEquals("x=" + x + " y=" + y,
                                Double.doubleToRawLongBits(javaMap.getHeight(x, y)),
                                Double.doubleToRawLongBits(nativeMap.getHeight(x, y)));
                    }
                }
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void floatingTunnelEdgeHeightBakeMatchesJavaAcrossLayerHoles() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            final TileFactory terrainFactory = TestData.createTileFactory(62);
            final World2 world = new World2(TestData.PLATFORM, TestData.SEED, terrainFactory);
            final Dimension dimension = world.getDimension(Dimension.Anchor.NORMAL_DETAIL);
            dimension.addTile(terrainFactory.createTile(0, 0));

            final int floorId = 7;
            final TileFactory floorFactory = TestData.createTileFactory(41);
            final Dimension floor = new Dimension(world, "Floating floor", TestData.SEED, floorFactory,
                    new Dimension.Anchor(Constants.DIM_NORMAL, Dimension.Role.FLOATING_FLOOR, false, floorId));
            floor.addTile(floorFactory.createTile(0, 0));
            world.addDimension(floor);

            final TunnelLayer layer = new TunnelLayer("Floating parity", TunnelLayer.LayerMode.FLOATING,
                    null, TestData.PLATFORM);
            layer.setFloorDimensionId(floorId);
            for (int y = 18; y < 106; y++) {
                for (int x = 21; x < 109; x++) {
                    if (!((x >= 49 && x <= 68) && (y >= 43 && y <= 79))) {
                        dimension.setBitLayerValueAt(layer, x, y, true);
                    }
                }
            }

            Native.setExportEnabled(true);
            final HeightMap nativeMap = dimension.getEdgeHeights(layer, 6.25f);
            Native.setExportEnabled(false);
            final HeightMap javaMap = dimension.getEdgeHeights(layer, 6.25f);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    assertEquals("x=" + x + " y=" + y,
                            Double.doubleToRawLongBits(javaMap.getHeight(x, y)),
                            Double.doubleToRawLongBits(nativeMap.getHeight(x, y)));
                }
            }
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void tunnelFloorMapRenderingMatchesWhenRenderAccelerationIsEnabled() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousRenderFlag = System.getProperty(Native.RENDER_KEY);
        final String previousTunnelFlag = System.getProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY);
        try {
            final TunnelRenderFixture fixture = createTunnelRenderFixture();
            final int[] javaPixels = renderTunnelMap(fixture, false);
            final int[] nativePixels = renderTunnelMap(fixture, true);
            assertEquals(javaPixels.length, nativePixels.length);
            org.junit.Assert.assertArrayEquals(javaPixels, nativePixels);
        } finally {
            restoreSystemProperty(Native.RENDER_KEY, previousRenderFlag);
            restoreSystemProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY, previousTunnelFlag);
        }
    }

    @Test
    public void benchmarkCompleteTunnelMapRenderingWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.tunnels.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousRenderFlag = System.getProperty(Native.RENDER_KEY);
        final String previousTunnelFlag = System.getProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY);
        try {
            final TunnelRenderFixture fixture = createTunnelRenderFixture();
            org.junit.Assert.assertArrayEquals(renderTunnelMap(fixture, false), renderTunnelMap(fixture, true));

            for (int i = 0; i < 4; i++) {
                renderTunnelMap(fixture, (i & 1) != 0);
            }
            final long[] javaNanos = new long[5];
            final long[] nativeNanos = new long[5];
            for (int sample = 0; sample < javaNanos.length; sample++) {
                final boolean javaFirst = (sample & 1) == 0;
                for (int order = 0; order < 2; order++) {
                    final boolean nativeMode = (order == 0) ? !javaFirst : javaFirst;
                    final long start = System.nanoTime();
                    renderTunnelMap(fixture, nativeMode);
                    final long elapsed = System.nanoTime() - start;
                    if (nativeMode) {
                        nativeNanos[sample] = elapsed;
                    } else {
                        javaNanos[sample] = elapsed;
                    }
                }
            }
            java.util.Arrays.sort(javaNanos);
            java.util.Arrays.sort(nativeNanos);
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> renderTunnelMap(fixture, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> renderTunnelMap(fixture, true));
            System.out.printf("Complete 4x4-tile cave render Java median %.3f ms, Rust/JNI median %.3f ms, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaNanos[2] / 1_000_000.0, nativeNanos[2] / 1_000_000.0,
                    (double) javaNanos[2] / nativeNanos[2], javaMemory, nativeMemory);
        } finally {
            restoreSystemProperty(Native.RENDER_KEY, previousRenderFlag);
            restoreSystemProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY, previousTunnelFlag);
        }
    }

    private static TunnelRenderFixture createTunnelRenderFixture() {
        final TileFactory tileFactory = TestData.createTileFactory(62);
        final World2 world = new World2(TestData.PLATFORM, TestData.SEED, tileFactory);
        final Dimension detailDimension = world.getDimension(Dimension.Anchor.NORMAL_DETAIL);
        final TunnelLayer layer = new TunnelLayer("Render benchmark", TunnelLayer.LayerMode.CAVE,
                null, TestData.PLATFORM);
        layer.setFloorDimensionId(7);
        layer.setFloorMode(TunnelLayer.Mode.FIXED_HEIGHT);
        layer.setFloorLevel(25);
        layer.setFloorWallDepth(12);
        layer.setRoofMode(TunnelLayer.Mode.FIXED_HEIGHT);
        layer.setRoofLevel(75);
        layer.setRoofWallDepth(12);
        detailDimension.setCustomLayers(java.util.Collections.singletonList(layer));

        final Dimension floorDimension = new Dimension(world, "Cave floor", TestData.SEED,
                tileFactory, new Dimension.Anchor(Constants.DIM_NORMAL, Dimension.Role.CAVE_FLOOR, false, 7));
        world.addDimension(floorDimension);
        final List<Tile> floorTiles = new ArrayList<>();
        for (int tileY = 0; tileY < 4; tileY++) {
            for (int tileX = 0; tileX < 4; tileX++) {
                final Tile detailTile = tileFactory.createTile(tileX, tileY);
                detailDimension.addTile(detailTile);
                final Tile floorTile = tileFactory.createTile(tileX, tileY);
                floorDimension.addTile(floorTile);
                floorTiles.add(floorTile);
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    final int worldY = (tileY << Constants.TILE_SIZE_BITS) | y;
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        final int worldX = (tileX << Constants.TILE_SIZE_BITS) | x;
                        final boolean insideOuter = (worldX >= 24) && (worldX < 488)
                                && (worldY >= 24) && (worldY < 488);
                        final boolean insideHole = (worldX >= 196) && (worldX < 324)
                                && (worldY >= 176) && (worldY < 352);
                        if (insideOuter && !insideHole) {
                            detailTile.setBitLayerValue(layer, x, y, true);
                        }
                    }
                }
            }
        }
        return new TunnelRenderFixture(floorDimension, floorTiles);
    }

    private static int[] renderTunnelMap(TunnelRenderFixture fixture, boolean nativeEnabled) {
        Native.setRenderEnabled(false);
        System.setProperty(NativeSlices.TUNNEL_EDGE_RENDER_KEY, Boolean.toString(nativeEnabled));
        final int width = 4 * Constants.TILE_SIZE;
        final BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        final TileRenderer renderer = new TileRenderer(fixture.floorDimension,
                ColourScheme.DEFAULT, null, 0, true, null);
        for (Tile tile : fixture.tiles) {
            renderer.renderTile(tile, image, tile.getX() * Constants.TILE_SIZE,
                    tile.getY() * Constants.TILE_SIZE);
        }
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData().clone();
    }

    private static void restoreSystemProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static final class TunnelRenderFixture {
        private final Dimension floorDimension;
        private final List<Tile> tiles;

        private TunnelRenderFixture(Dimension floorDimension, List<Tile> tiles) {
            this.floorDimension = floorDimension;
            this.tiles = tiles;
        }
    }

    private static float[] javaEdgeDistances(int width, int height, float maxDistance, byte[] mask) {
        final int radius = (int) Math.ceil(maxDistance);
        final float[] distances = new float[width * height];
        java.util.Arrays.fill(distances, maxDistance);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                if ((mask[index] != 0) || !hasPaintedNeighbour(mask, width, height, x, y)) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    final int targetY = y + dy;
                    if ((targetY < 0) || (targetY >= height)) {
                        continue;
                    }
                    for (int dx = -radius; dx <= radius; dx++) {
                        final int targetX = x + dx;
                        if ((targetX < 0) || (targetX >= width)) {
                            continue;
                        }
                        final int target = targetY * width + targetX;
                        if (mask[target] != 0) {
                            final float distance = (float) Math.sqrt((double) dx * dx + (double) dy * dy);
                            if (distance < distances[target]) {
                                distances[target] = distance;
                            }
                        }
                    }
                }
            }
        }
        return distances;
    }

    private static float[] javaEdgeHeights(int width, int height, int radius, float minHeight,
                                           byte[] sources, float[] sourceHeights) {
        final float[] values = new float[width * height];
        java.util.Arrays.fill(values, minHeight);
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] == 0) {
                continue;
            }
            final int sourceIndex = i;
            final int sourceX = i % width, sourceY = i / width;
            org.pepsoft.worldpainter.util.GeometryUtil.visitFilledCircle(radius, (dx, dy, distance) -> {
                final int x = sourceX + dx, y = sourceY + dy;
                if ((x >= 0) && (x < width) && (y >= 0) && (y < height)) {
                    final int target = y * width + x;
                    if (sourceHeights[sourceIndex] > values[target]) {
                        values[target] = sourceHeights[sourceIndex];
                    }
                }
                return true;
            });
        }
        return values;
    }

    private static boolean hasPaintedNeighbour(byte[] mask, int width, int height, int x, int y) {
        return ((x > 0) && (mask[y * width + x - 1] != 0))
                || ((x + 1 < width) && (mask[y * width + x + 1] != 0))
                || ((y > 0) && (mask[(y - 1) * width + x] != 0))
                || ((y + 1 < height) && (mask[(y + 1) * width + x] != 0));
    }

    private static void restoreFlag(String previousFlag) {
        restoreSystemProperty(Native.EXPORT_KEY, previousFlag);
    }
}
