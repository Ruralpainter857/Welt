package org.pepsoft.worldpainter;

import org.junit.Test;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.pepsoft.worldpainter.HeightMap;
import org.pepsoft.worldpainter.heightMaps.AbstractHeightMap;
import org.pepsoft.worldpainter.heightMaps.BicubicHeightMap;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.TransformingHeightMap;
import org.pepsoft.worldpainter.importing.HeightMapImporter;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

public final class HeightMapImporterBitmapParityTest {
    private static final String DISABLE_BATCH_TILE_INITIALISATION_PROPERTY =
            "welt.import.disableBatchTileInitialisation";

    @BeforeClass
    public static void initialiseConfiguration() {
        previousConfiguration = Configuration.getInstance();
        final Configuration configuration = new Configuration();
        configuration.setDefaultPlatform(TestData.PLATFORM);
        Configuration.setInstance(configuration);
    }

    @AfterClass
    public static void restoreConfiguration() {
        Configuration.setInstance(previousConfiguration);
    }

    @Test
    public void bitmapImportMatchesPerCellReferenceForSupportedWrappers() throws Exception {
        for (int imageType : new int[] {
                BufferedImage.TYPE_BYTE_GRAY,
                BufferedImage.TYPE_USHORT_GRAY,
                BufferedImage.TYPE_3BYTE_BGR
        }) {
            final BufferedImage image = createImage(imageType, 141, 137);
            assertImportParity(BitmapHeightMap.build().withImage(image).now());
            assertImportParity(new BicubicHeightMap(BitmapHeightMap.build().withImage(image).now()));
            assertImportParity(new BicubicHeightMap(BitmapHeightMap.build().withImage(image).now(), true));
            assertImportParity(TransformingHeightMap.build()
                    .withHeightMap(BitmapHeightMap.build().withImage(image).now())
                    .withOffset(-19, 11).now());
            assertImportParity(TransformingHeightMap.build()
                    .withHeightMap(new BicubicHeightMap(BitmapHeightMap.build().withImage(image).now(), true))
                    .withOffset(13, -7).now());
        }
    }

    @Test
    public void benchmarkBatchedTileWritesAgainstPerCellSetters() throws Exception {
        final HeightMap bitmap = BitmapHeightMap.build().withImage(
                createImage(BufferedImage.TYPE_USHORT_GRAY, 512, 512)).now();
        final int rounds = 7;
        final double[] batchMillis = new double[rounds];
        final double[] perCellSetterMillis = new double[rounds];
        for (int warmup = 0; warmup < 3; warmup++) {
            timeImport(bitmap, true);
            timeImport(bitmap, false);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                batchMillis[round] = timeImport(bitmap, true);
                perCellSetterMillis[round] = timeImport(bitmap, false);
            } else {
                perCellSetterMillis[round] = timeImport(bitmap, false);
                batchMillis[round] = timeImport(bitmap, true);
            }
        }
        Arrays.sort(batchMillis);
        Arrays.sort(perCellSetterMillis);
        final BenchmarkMemorySupport.Snapshot batchMemory = BenchmarkMemorySupport.measure(
                () -> importMap(bitmap, true));
        final BenchmarkMemorySupport.Snapshot perCellSetterMemory = BenchmarkMemorySupport.measure(
                () -> importMap(bitmap, false));
        System.out.printf("Heightmap import 512x512: batched tile writes %.3f ms, per-cell setters %.3f ms, "
                        + "speedup %.3fx; batch_memory=[%s], per_cell_memory=[%s]%n",
                batchMillis[rounds / 2], perCellSetterMillis[rounds / 2],
                perCellSetterMillis[rounds / 2] / batchMillis[rounds / 2], batchMemory, perCellSetterMemory);
    }

    @Test
    public void batchedFullTileImportMatchesPerCellPathAtNegativeCoordinates() throws Exception {
        final HeightMap bitmap = BitmapHeightMap.build().withImage(
                createImage(BufferedImage.TYPE_USHORT_GRAY, 256, 256)).now();
        final HeightMap map = TransformingHeightMap.build()
                .withHeightMap(bitmap).withOffset(-128, -128).now();
        final Dimension batch = importMap(map, true);
        final Dimension perCell = importMap(map, false);
        final Rectangle extent = map.getExtent();
        final int tileX1 = extent.x >> Constants.TILE_SIZE_BITS;
        final int tileY1 = extent.y >> Constants.TILE_SIZE_BITS;
        final int tileX2 = (extent.x + extent.width - 1) >> Constants.TILE_SIZE_BITS;
        final int tileY2 = (extent.y + extent.height - 1) >> Constants.TILE_SIZE_BITS;
        for (int tileX = tileX1; tileX <= tileX2; tileX++) {
            for (int tileY = tileY1; tileY <= tileY2; tileY++) {
                final Tile batchTile = batch.getTile(tileX, tileY);
                final Tile perCellTile = perCell.getTile(tileX, tileY);
                assertNotNull(batchTile);
                assertNotNull(perCellTile);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        final String cell = "tile=" + tileX + ',' + tileY + " cell=" + x + ',' + y;
                        assertEquals(cell, Float.floatToRawIntBits(perCellTile.getHeight(x, y)),
                                Float.floatToRawIntBits(batchTile.getHeight(x, y)));
                        assertEquals(cell, perCellTile.getWaterLevel(x, y), batchTile.getWaterLevel(x, y));
                        assertEquals(cell, perCellTile.getTerrain(x, y), batchTile.getTerrain(x, y));
                    }
                }
            }
        }
    }

    private static double timeImport(HeightMap map, boolean batchTileInitialisation) throws Exception {
        final long start = System.nanoTime();
        importMap(map, batchTileInitialisation);
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static void assertImportParity(HeightMap acceleratedMap) throws Exception {
        final HeightMap referenceMap = new PerCellReferenceHeightMap(acceleratedMap);
        final Rectangle extent = acceleratedMap.getExtent();
        final Dimension acceleratedDimension = importMap(acceleratedMap);
        final Dimension referenceDimension = importMap(referenceMap);
        final int tileX1 = extent.x >> Constants.TILE_SIZE_BITS;
        final int tileY1 = extent.y >> Constants.TILE_SIZE_BITS;
        final int tileX2 = (extent.x + extent.width - 1) >> Constants.TILE_SIZE_BITS;
        final int tileY2 = (extent.y + extent.height - 1) >> Constants.TILE_SIZE_BITS;
        for (int tileX = tileX1; tileX <= tileX2; tileX++) {
            for (int tileY = tileY1; tileY <= tileY2; tileY++) {
                final Tile acceleratedTile = acceleratedDimension.getTile(tileX, tileY);
                final Tile referenceTile = referenceDimension.getTile(tileX, tileY);
                assertNotNull(acceleratedTile);
                assertNotNull(referenceTile);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        final String cell = "tile=" + tileX + ',' + tileY + " cell=" + x + ',' + y
                                + " map=" + acceleratedMap.getClass().getSimpleName();
                        assertEquals(cell, Float.floatToRawIntBits(referenceTile.getHeight(x, y)),
                                Float.floatToRawIntBits(acceleratedTile.getHeight(x, y)));
                        assertEquals(cell, referenceTile.getWaterLevel(x, y),
                                acceleratedTile.getWaterLevel(x, y));
                        assertEquals(cell, referenceTile.getTerrain(x, y),
                                acceleratedTile.getTerrain(x, y));
                    }
                }
            }
        }
    }

    private static Dimension importMap(HeightMap map) throws Exception {
        return importMap(map, !Boolean.getBoolean(DISABLE_BATCH_TILE_INITIALISATION_PROPERTY));
    }

    private static Dimension importMap(HeightMap map, boolean batchTileInitialisation) throws Exception {
        final String previous = System.getProperty(DISABLE_BATCH_TILE_INITIALISATION_PROPERTY);
        if (batchTileInitialisation) {
            System.clearProperty(DISABLE_BATCH_TILE_INITIALISATION_PROPERTY);
        } else {
            System.setProperty(DISABLE_BATCH_TILE_INITIALISATION_PROPERTY, "true");
        }
        try {
            final World2 world = new World2(TestData.PLATFORM, TestData.MIN_HEIGHT, TestData.MAX_HEIGHT);
            final SimpleTheme theme = SimpleTheme.createSingleTerrain(
                    Terrain.GRASS, TestData.MIN_HEIGHT, TestData.MAX_HEIGHT, 62);
            final HeightMapTileFactory tileFactory = new HeightMapTileFactory(19L,
                    new ConstantHeightMap(62.0), TestData.MIN_HEIGHT, TestData.MAX_HEIGHT, false, theme);
            final Dimension dimension = new Dimension(world, "Import parity", 19L,
                    tileFactory, NORMAL_DETAIL);
            final HeightMapImporter importer = new HeightMapImporter();
            importer.setHeightMap(map);
            importer.setTileFactory(tileFactory);
            importer.setMinHeight(TestData.MIN_HEIGHT);
            importer.setMaxHeight(TestData.MAX_HEIGHT);
            importer.setWorldLowLevel(TestData.MIN_HEIGHT);
            importer.setWorldHighLevel(180);
            importer.setWorldWaterLevel(62);
            importer.setImageLowLevel(0.0);
            importer.setImageHighLevel(65535.0);
            importer.importToDimension(dimension, true, null);
            return dimension;
        } finally {
            if (previous == null) {
                System.clearProperty(DISABLE_BATCH_TILE_INITIALISATION_PROPERTY);
            } else {
                System.setProperty(DISABLE_BATCH_TILE_INITIALISATION_PROPERTY, previous);
            }
        }
    }

    private static BufferedImage createImage(int imageType, int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, imageType);
        final int maxSample = image.getRaster().getSampleModel().getSampleSize(0) > 8 ? 65535 : 255;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0,
                        Math.floorMod(x * 971 + y * 313 + x * y * 17, maxSample + 1));
            }
        }
        return image;
    }

    private static final class PerCellReferenceHeightMap extends AbstractHeightMap {
        private PerCellReferenceHeightMap(HeightMap delegate) {
            this.delegate = delegate;
        }

        @Override
        public double getHeight(int x, int y) {
            return delegate.getHeight(x, y);
        }

        @Override
        public double getHeight(float x, float y) {
            return delegate.getHeight(x, y);
        }

        @Override
        public Rectangle getExtent() {
            return delegate.getExtent();
        }

        @Override
        public javax.swing.Icon getIcon() {
            return delegate.getIcon();
        }

        @Override
        public double[] getRange() {
            return delegate.getRange();
        }

        @Override
        public void setSeed(long seed) {
            super.setSeed(seed);
            delegate.setSeed(seed);
        }

        private final HeightMap delegate;
    }

    private static Configuration previousConfiguration;
}
