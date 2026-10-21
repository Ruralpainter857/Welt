package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.ColourUtils;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.NotPresent;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.util.Arrays;
import java.util.Random;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Bit-exact and opt-in batch benchmark coverage for TileRenderer shading. */
public final class NativeRenderShadingParityTest {
    private static final int PIXELS = 128 * 128;
    private static volatile Object tileRendererMemorySink;

    private static final class Java2dFallbackImage extends BufferedImage {
        private Java2dFallbackImage(int width, int height) {
            super(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public int getType() {
            return BufferedImage.TYPE_CUSTOM;
        }
    }

    @Test
    public void tileRenderHeightSnapshotMatchesScalarGettersForBothStorageLayouts() {
        assertRenderHeightSnapshotMatches(new Tile(0, 0, 0, 256));
        assertRenderHeightSnapshotMatches(new Tile(0, 0, -128, 384));
    }

    @Test
    public void tileLayerSnapshotsMatchScalarGetters() {
        final Tile tile = new Tile(0, 0, 0, 256);
        assertFalse(tile.copyLayerValues(Biome.INSTANCE, 0, 0,
                Constants.TILE_SIZE, Constants.TILE_SIZE, new byte[PIXELS], 0));

        tile.inhibitEvents();
        try {
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    if (((x * 31 + y * 17) & 15) == 0) {
                        tile.setLayerValue(Biome.INSTANCE, x, y, (x * 7 + y * 11) & 0xff);
                    }
                    if (((x * 13 + y * 5) & 31) == 0) {
                        tile.setBitLayerValue(Frost.INSTANCE, x, y, true);
                    }
                }
            }
        } finally {
            tile.releaseEvents();
        }

        final byte[] biomeValues = new byte[PIXELS];
        final byte[] frostValues = new byte[PIXELS];
        assertTrue(tile.copyLayerValues(Biome.INSTANCE, 0, 0,
                Constants.TILE_SIZE, Constants.TILE_SIZE, biomeValues, 0));
        tile.copyBitLayerValues(Frost.INSTANCE, 0, 0,
                Constants.TILE_SIZE, Constants.TILE_SIZE, frostValues, 0);
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                final int index = x * Constants.TILE_SIZE + y;
                assertEquals(tile.getLayerValue(Biome.INSTANCE, x, y), biomeValues[index] & 0xff);
                assertEquals(tile.getBitLayerValue(Frost.INSTANCE, x, y), frostValues[index] != 0);
            }
        }
    }

    @Test
    public void nativeTileShadingMatchesJavaForAllBrightnessBands() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(true);
        try {
            final int[] original = new int[PIXELS];
            final long[] amounts = new long[PIXELS];
            final int[] candidates = {0, 1, 64, 128, 192, 255, 256, 257, 512, 4096, Integer.MAX_VALUE};
            final Random random = new Random(0x57656c745f52314cL);
            for (int i = 0; i < PIXELS; i++) {
                original[i] = random.nextInt();
                final int terrain = candidates[i % candidates.length];
                final int fluid = candidates[(i * 7 + 3) % candidates.length];
                amounts[i] = ((long) fluid << 32) | (terrain & 0xffff_ffffL);
            }

            final int[] expected = original.clone();
            for (int i = 0; i < expected.length; i++) {
                final int terrain = (int) amounts[i];
                final int fluid = (int) (amounts[i] >>> 32);
                if (terrain != 256 || fluid != 256) {
                    final int alpha = expected[i] & 0xff00_0000;
                    expected[i] = ColourUtils.multiply(
                            ColourUtils.multiply(expected[i], terrain), fluid) | alpha;
                }
            }
            final int[] actual = original.clone();
            assertTrue("native shading batch should be available", NativeSlices.shadeColours(actual, amounts));
            assertArrayEquals(expected, actual);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void nativeCompactTileShadingMatchesJavaAt16BitBoundaries() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(true);
        try {
            final int[] original = new int[PIXELS];
            final int[] amounts = new int[PIXELS];
            final int[] candidates = {0, 1, 64, 128, 192, 255, 256, 257, 512, 4096, 65_534, 65_535};
            final Random random = new Random(0x57656c745f52314cL);
            for (int i = 0; i < PIXELS; i++) {
                original[i] = random.nextInt();
                final int terrain = candidates[i % candidates.length];
                final int fluid = candidates[(i * 7 + 3) % candidates.length];
                amounts[i] = (fluid << 16) | terrain;
            }

            final int[] expected = original.clone();
            for (int i = 0; i < expected.length; i++) {
                final int terrain = amounts[i] & 0xffff;
                final int fluid = amounts[i] >>> 16;
                if (terrain != 256 || fluid != 256) {
                    final int alpha = expected[i] & 0xff00_0000;
                    expected[i] = ColourUtils.multiply(
                            ColourUtils.multiply(expected[i], terrain), fluid) | alpha;
                }
            }
            final int[] actual = original.clone();
            assertTrue("native compact shading batch should be available",
                    NativeSlices.shadeColoursCompact(actual, amounts));
            assertArrayEquals(expected, actual);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void tileRendererNativePathMatchesTheJavaImage() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        try {
            final Dimension dimension = TestData.createDimension(
                    new Rectangle(0, 0, Constants.TILE_SIZE, Constants.TILE_SIZE), 64);
            final Tile tile = dimension.getTile(0, 0);
            tile.inhibitEvents();
            try {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        final float height = 32 + ((x * 7 + y * 11) % 96);
                        tile.setHeight(x, y, height);
                        tile.setWaterLevel(x, y, 62 + ((x + y) % 24));
                        if (((x & 31) == 0) && ((y & 31) == 0)) {
                            tile.setLayerValue(Biome.INSTANCE, x, y, 1 + ((x + y) & 7));
                        }
                        if (x == 4 && y == 8) {
                            tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, x, y, true);
                        } else if (x == 12 && y == 5) {
                            tile.setBitLayerValue(NotPresent.INSTANCE, x, y, true);
                        }
                    }
                }
            } finally {
                tile.releaseEvents();
            }
            assertTrue("stored Biome data should remain in the render layer list",
                    tile.getLayers().contains(Biome.INSTANCE));
            final int[] javaPixels = renderTile(dimension, tile, false);
            final int[] nativePixels = renderTile(dimension, tile, true);
            assertArrayEquals(javaPixels, nativePixels);
            assertEquals("void pixel should stay transparent", 0, nativePixels[4 | (8 << Constants.TILE_SIZE_BITS)]);
            assertEquals("not-present pixel should stay transparent", 0,
                    nativePixels[12 | (5 << Constants.TILE_SIZE_BITS)]);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkNativeTileShadingWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.shade.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(true);
        try {
            final int[] original = new int[PIXELS];
            final long[] amounts = new long[PIXELS];
            final Random random = new Random(0x57656c745f52314cL);
            for (int i = 0; i < PIXELS; i++) {
                original[i] = random.nextInt() & 0x00ff_ffff;
                final int terrain = 32 + random.nextInt(481);
                final int fluid = 32 + random.nextInt(481);
                amounts[i] = ((long) fluid << 32) | (terrain & 0xffff_ffffL);
            }
            final int[] javaPixels = new int[PIXELS];
            final int[] nativePixels = new int[PIXELS];
            for (int i = 0; i < 5; i++) {
                System.arraycopy(original, 0, javaPixels, 0, PIXELS);
                System.arraycopy(original, 0, nativePixels, 0, PIXELS);
                shadeJavaInPlace(javaPixels, amounts);
                NativeSlices.shadeColours(nativePixels, amounts);
            }
            final long[] javaNanos = new long[9];
            final long[] nativeNanos = new long[9];
            for (int sample = 0; sample < javaNanos.length; sample++) {
                System.arraycopy(original, 0, javaPixels, 0, PIXELS);
                long start = System.nanoTime();
                for (int iteration = 0; iteration < 8; iteration++) {
                    shadeJavaInPlace(javaPixels, amounts);
                }
                javaNanos[sample] = (System.nanoTime() - start) / 8;

                System.arraycopy(original, 0, nativePixels, 0, PIXELS);
                start = System.nanoTime();
                for (int iteration = 0; iteration < 8; iteration++) {
                    assertTrue(NativeSlices.shadeColours(nativePixels, amounts));
                }
                nativeNanos[sample] = (System.nanoTime() - start) / 8;
            }
            Arrays.sort(javaNanos);
            Arrays.sort(nativeNanos);
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> shadeJavaInPlace(javaPixels, amounts));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> NativeSlices.shadeColours(nativePixels, amounts));
            System.out.printf("Tile shading Java median %.3f ms, Rust/JNI median %.3f ms, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaNanos[4] / 1_000_000.0, nativeNanos[4] / 1_000_000.0,
                    (double) javaNanos[4] / nativeNanos[4], javaMemory, nativeMemory);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkFullTileRenderingWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.tile.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        try {
            final Dimension dimension = TestData.createDimension(
                    new Rectangle(0, 0, Constants.TILE_SIZE, Constants.TILE_SIZE), 64);
            final Tile tile = dimension.getTile(0, 0);
            tile.inhibitEvents();
            try {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        tile.setHeight(x, y, 32 + ((x * 7 + y * 11) % 96));
                        tile.setWaterLevel(x, y, 62 + ((x + y) % 24));
                        tile.setLayerValue(Biome.INSTANCE, x, y, 1 + ((x * 3 + y * 5) & 7));
                        if (((x + y) & 7) == 0) {
                            tile.setBitLayerValue(Frost.INSTANCE, x, y, true);
                        }
                        if (x == 4 && y == 8) {
                            tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, x, y, true);
                        } else if (x == 12 && y == 5) {
                            tile.setBitLayerValue(NotPresent.INSTANCE, x, y, true);
                        }
                    }
                }
            } finally {
                tile.releaseEvents();
            }
            final TileRenderer javaRenderer = new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null);
            final TileRenderer nativeRenderer = new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null);
            final BufferedImage javaImage = new BufferedImage(
                    Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage nativeImage = new BufferedImage(
                    Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < 4; i++) {
                Native.setRenderEnabled(false);
                javaRenderer.renderTile(tile, javaImage, 0, 0);
                Native.setRenderEnabled(true);
                nativeRenderer.renderTile(tile, nativeImage, 0, 0);
            }
            final int renderIterations = Math.max(1,
                    Integer.getInteger("welt.render.tile.iterations", 3));
            final long[] javaNanos = new long[9];
            final long[] nativeNanos = new long[9];
            for (int sample = 0; sample < javaNanos.length; sample++) {
                if ((sample & 1) == 0) {
                    Native.setRenderEnabled(false);
                    long start = System.nanoTime();
                    for (int iteration = 0; iteration < renderIterations; iteration++) {
                        javaRenderer.renderTile(tile, javaImage, 0, 0);
                    }
                    javaNanos[sample] = (System.nanoTime() - start) / renderIterations;

                    Native.setRenderEnabled(true);
                    start = System.nanoTime();
                    for (int iteration = 0; iteration < renderIterations; iteration++) {
                        nativeRenderer.renderTile(tile, nativeImage, 0, 0);
                    }
                    nativeNanos[sample] = (System.nanoTime() - start) / renderIterations;
                } else {
                    Native.setRenderEnabled(true);
                    long start = System.nanoTime();
                    for (int iteration = 0; iteration < renderIterations; iteration++) {
                        nativeRenderer.renderTile(tile, nativeImage, 0, 0);
                    }
                    nativeNanos[sample] = (System.nanoTime() - start) / renderIterations;

                    Native.setRenderEnabled(false);
                    start = System.nanoTime();
                    for (int iteration = 0; iteration < renderIterations; iteration++) {
                        javaRenderer.renderTile(tile, javaImage, 0, 0);
                    }
                    javaNanos[sample] = (System.nanoTime() - start) / renderIterations;
                }
            }
            Arrays.sort(javaNanos);
            Arrays.sort(nativeNanos);
            assertArrayEquals(((DataBufferInt) javaImage.getRaster().getDataBuffer()).getData(),
                    ((DataBufferInt) nativeImage.getRaster().getDataBuffer()).getData());
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(() -> {
                Native.setRenderEnabled(false);
                javaRenderer.renderTile(tile, javaImage, 0, 0);
            });
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(() -> {
                Native.setRenderEnabled(true);
                nativeRenderer.renderTile(tile, nativeImage, 0, 0);
            });
            System.out.printf("Full TileRenderer Java median %.3f ms, Rust/JNI median %.3f ms, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaNanos[4] / 1_000_000.0, nativeNanos[4] / 1_000_000.0,
                    (double) javaNanos[4] / nativeNanos[4], javaMemory, nativeMemory);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void directArgbTileCopyMatchesJava2dForTransparentPixelsAndOffsets() {
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(false);
        try {
            final Dimension dimension = TestData.createDimension(
                    new Rectangle(0, 0, Constants.TILE_SIZE, Constants.TILE_SIZE), 64);
            final Tile tile = dimension.getTile(0, 0);
            tile.inhibitEvents();
            try {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        tile.setHeight(x, y, 32 + ((x * 7 + y * 11) % 96));
                        tile.setWaterLevel(x, y, 62 + ((x + y) % 24));
                    }
                }
                tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, 4, 8, true);
                tile.setBitLayerValue(NotPresent.INSTANCE, 12, 5, true);
            } finally {
                tile.releaseEvents();
            }

            final int imageWidth = Constants.TILE_SIZE + 8;
            final int imageHeight = Constants.TILE_SIZE + 10;
            final int dx = 3, dy = 5;
            final int initialColour = 0xff375a9c;
            final BufferedImage directImage = new BufferedImage(
                    imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage java2dImage = new BufferedImage(
                    imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB_PRE);
            Arrays.fill(((DataBufferInt) directImage.getRaster().getDataBuffer()).getData(), initialColour);
            Arrays.fill(((DataBufferInt) java2dImage.getRaster().getDataBuffer()).getData(), initialColour);

            new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                    .renderTile(tile, directImage, dx, dy);
            new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                    .renderTile(tile, java2dImage, dx, dy);

            assertArrayEquals(java2dImage.getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth),
                    directImage.getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth));
            assertEquals(initialColour, directImage.getRGB(0, 0));
            assertEquals(0, directImage.getRGB(dx + 4, dy + 8));
            assertEquals(0, directImage.getRGB(dx + 12, dy + 5));

            final int clippedWidth = Constants.TILE_SIZE - 4;
            final int clippedHeight = Constants.TILE_SIZE - 6;
            final BufferedImage clippedDirectImage = new BufferedImage(
                    clippedWidth, clippedHeight, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage clippedJava2dImage = new BufferedImage(
                    clippedWidth, clippedHeight, BufferedImage.TYPE_INT_ARGB_PRE);
            Arrays.fill(((DataBufferInt) clippedDirectImage.getRaster().getDataBuffer()).getData(), initialColour);
            Arrays.fill(((DataBufferInt) clippedJava2dImage.getRaster().getDataBuffer()).getData(), initialColour);
            new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                    .renderTile(tile, clippedDirectImage, -2, -4);
            new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                    .renderTile(tile, clippedJava2dImage, -2, -4);
            assertArrayEquals(clippedJava2dImage.getRGB(0, 0, clippedWidth, clippedHeight,
                            null, 0, clippedWidth),
                    clippedDirectImage.getRGB(0, 0, clippedWidth, clippedHeight,
                            null, 0, clippedWidth));
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkFullWorldViewDirectArgbCopyWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.view.direct-copy.benchmark"));
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(false);
        try {
            final int tilesPerSide = 3;
            final int viewSize = tilesPerSide * Constants.TILE_SIZE;
            final Dimension dimension = TestData.createDimension(
                    new Rectangle(0, 0, viewSize, viewSize), 64);
            final Tile[] tiles = dimension.getTiles().toArray(new Tile[0]);
            for (Tile tile : tiles) {
                tile.inhibitEvents();
                try {
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            final int worldX = (tile.getX() << Constants.TILE_SIZE_BITS) + x;
                            final int worldY = (tile.getY() << Constants.TILE_SIZE_BITS) + y;
                            tile.setHeight(x, y, 32 + ((worldX * 7 + worldY * 11) & 95));
                            tile.setWaterLevel(x, y, 62 + ((worldX + worldY) & 15));
                        }
                    }
                } finally {
                    tile.releaseEvents();
                }
            }

            final TileRenderer directRenderer = new TileRenderer(
                    dimension, ColourScheme.DEFAULT, null, 0, true, null);
            final TileRenderer java2dRenderer = new TileRenderer(
                    dimension, ColourScheme.DEFAULT, null, 0, true, null);
            final BufferedImage directImage = new BufferedImage(
                    viewSize, viewSize, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage java2dImage = new Java2dFallbackImage(viewSize, viewSize);
            for (int warmup = 0; warmup < 4; warmup++) {
                renderView(directRenderer, directImage, tiles);
                renderView(java2dRenderer, java2dImage, tiles);
            }
            assertArrayEquals(java2dImage.getRGB(0, 0, viewSize, viewSize, null, 0, viewSize),
                    directImage.getRGB(0, 0, viewSize, viewSize, null, 0, viewSize));

            final int iterations = Math.max(1, Integer.getInteger("welt.render.view.iterations", 4));
            final long[] directNanos = new long[9];
            final long[] java2dNanos = new long[9];
            for (int sample = 0; sample < directNanos.length; sample++) {
                if ((sample & 1) == 0) {
                    long start = System.nanoTime();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        renderView(directRenderer, directImage, tiles);
                    }
                    directNanos[sample] = (System.nanoTime() - start) / iterations;

                    start = System.nanoTime();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        renderView(java2dRenderer, java2dImage, tiles);
                    }
                    java2dNanos[sample] = (System.nanoTime() - start) / iterations;
                } else {
                    long start = System.nanoTime();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        renderView(java2dRenderer, java2dImage, tiles);
                    }
                    java2dNanos[sample] = (System.nanoTime() - start) / iterations;

                    start = System.nanoTime();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        renderView(directRenderer, directImage, tiles);
                    }
                    directNanos[sample] = (System.nanoTime() - start) / iterations;
                }
            }
            Arrays.sort(directNanos);
            Arrays.sort(java2dNanos);
            final BenchmarkMemorySupport.Snapshot directMemory = BenchmarkMemorySupport.measure(
                    () -> renderView(directRenderer, directImage, tiles));
            final BenchmarkMemorySupport.Snapshot java2dMemory = BenchmarkMemorySupport.measure(
                    () -> renderView(java2dRenderer, java2dImage, tiles));
            System.out.printf("Full 3x3 view direct ARGB copy median %.3f ms, Java2D median %.3f ms, "
                            + "ratio %.3fx direct_memory=[%s] java2d_memory=[%s] "
                            + "worker_allocated_direct=%.1f KiB worker_allocated_java2d=%.1f KiB%n",
                    directNanos[4] / 1_000_000.0, java2dNanos[4] / 1_000_000.0,
                    (double) java2dNanos[4] / directNanos[4], directMemory, java2dMemory,
                    directMemory.allocatedBytes() / 1024.0, java2dMemory.allocatedBytes() / 1024.0);
            assertArrayEquals(java2dImage.getRGB(0, 0, viewSize, viewSize, null, 0, viewSize),
                    directImage.getRGB(0, 0, viewSize, viewSize, null, 0, viewSize));
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkTileRendererConstructionMemoryWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.tile-construction.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final Dimension dimension = TestData.createDimension(
                new Rectangle(0, 0, Constants.TILE_SIZE, Constants.TILE_SIZE), 64);
        final Tile tile = dimension.getTile(0, 0);
        final BufferedImage image = new BufferedImage(
                Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        final int rendererCount = 8;
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        try {
            for (int i = 0; i < 2; i++) {
                renderOneTile(dimension, tile, image, false);
                renderOneTile(dimension, tile, image, true);
            }
            final BenchmarkMemorySupport.Snapshot javaMemory = measureTileRendererConstructionAndFirstRender(
                    dimension, tile, image, rendererCount, false);
            final BenchmarkMemorySupport.Snapshot nativeMemory = measureTileRendererConstructionAndFirstRender(
                    dimension, tile, image, rendererCount, true);
            final long savedBytes = nativeMemory.allocatedBytes() - javaMemory.allocatedBytes();
            System.out.printf("TileRenderer construction plus first render count=%d java=[%s] native=[%s] "
                            + "native_extra=%.2f MiB, %.2f MiB/renderer%n",
                    rendererCount, javaMemory, nativeMemory,
                    savedBytes / 1_048_576.0, savedBytes / (1_048_576.0 * rendererCount));
        } finally {
            restoreFlag(previousFlag);
        }
    }

    private static BenchmarkMemorySupport.Snapshot measureTileRendererConstructionAndFirstRender(
            Dimension dimension, Tile tile, BufferedImage image, int rendererCount,
            boolean nativeEnabled) throws Exception {
        final TileRenderer[] renderers = new TileRenderer[rendererCount];
        final BenchmarkMemorySupport.Snapshot snapshot;
        try {
            snapshot = BenchmarkMemorySupport.measure(() -> {
                Native.setRenderEnabled(nativeEnabled);
                for (int i = 0; i < rendererCount; i++) {
                    final TileRenderer renderer = new TileRenderer(
                            dimension, ColourScheme.DEFAULT, null, 0, true, null);
                    renderers[i] = renderer;
                    renderer.renderTile(tile, image, 0, 0);
                }
                tileRendererMemorySink = renderers;
            });
        } finally {
            tileRendererMemorySink = null;
        }
        return snapshot;
    }

    private static void renderOneTile(Dimension dimension, Tile tile, BufferedImage image,
                                      boolean nativeEnabled) {
        Native.setRenderEnabled(nativeEnabled);
        new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                .renderTile(tile, image, 0, 0);
    }

    private static void shadeJavaInPlace(int[] pixels, long[] amounts) {
        for (int i = 0; i < pixels.length; i++) {
            final int terrain = (int) amounts[i];
            final int fluid = (int) (amounts[i] >>> 32);
            final int alpha = pixels[i] & 0xff00_0000;
            if (terrain != 256 || fluid != 256) {
                pixels[i] = ColourUtils.multiply(ColourUtils.multiply(pixels[i], terrain), fluid) | alpha;
            }
        }
    }

    private static int[] renderTile(Dimension dimension, Tile tile, boolean nativeEnabled) {
        Native.setRenderEnabled(nativeEnabled);
        final BufferedImage image = new BufferedImage(
                Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                .renderTile(tile, image, 0, 0);
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData().clone();
    }

    private static void renderView(TileRenderer renderer, BufferedImage image, Tile[] tiles) {
        for (Tile tile : tiles) {
            renderer.renderTile(tile, image,
                    tile.getX() << Constants.TILE_SIZE_BITS,
                    tile.getY() << Constants.TILE_SIZE_BITS);
        }
    }

    private static void assertRenderHeightSnapshotMatches(Tile tile) {
        final int minHeight = tile.getMinHeight();
        final int heightRange = tile.getMaxHeight() - minHeight;
        tile.inhibitEvents();
        try {
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    tile.setHeight(x, y, minHeight + ((x * 13 + y * 7) % heightRange) + 0.25f);
                    tile.setWaterLevel(x, y, minHeight + ((x * 5 + y * 11) % heightRange));
                }
            }
        } finally {
            tile.releaseEvents();
        }
        final float[] heights = new float[PIXELS];
        final int[] intHeights = new int[PIXELS];
        final int[] waterLevels = new int[PIXELS];
        tile.copyRenderHeightDataTo(heights, intHeights, waterLevels);
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                final int index = x | (y << Constants.TILE_SIZE_BITS);
                final float height = tile.getHeight(x, y);
                assertEquals(Float.floatToRawIntBits(height), Float.floatToRawIntBits(heights[index]));
                assertEquals(tile.getIntHeight(x, y), intHeights[index]);
                assertEquals(tile.getWaterLevel(x, y), waterLevels[index]);
            }
        }
    }

    private static void restoreFlag(String previousFlag) {
        if (previousFlag == null) {
            System.clearProperty(Native.RENDER_KEY);
        } else {
            System.setProperty(Native.RENDER_KEY, previousFlag);
        }
    }
}
