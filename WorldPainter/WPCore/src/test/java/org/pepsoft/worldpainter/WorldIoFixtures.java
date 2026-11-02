package org.pepsoft.worldpainter;

import org.pepsoft.minecraft.Material;
import org.pepsoft.util.AttributeKey;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.groundcover.GroundCoverLayer;

import java.awt.*;
import java.util.List;
import java.util.*;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.Dimension.Anchor.*;

/**
 * Synthetic WorldPainter projects with fixed seeds for open/save measurements and round trip checks. No user file is
 * involved. Profiles:
 * <ul>
 *     <li>{@code uniform}: flat terrain, so that heights, water and terrain are uniform (shared) buffers;
 *     <li>{@code noise}: generated noisy terrain with beaches;
 *     <li>{@code layers}: noisy terrain plus many layers (BYTE, NIBBLE, BIT, BIT_PER_CHUNK, a custom ground cover
 *     layer), a custom mixed material terrain, attributes, and extra Nether, End and cave dimensions.
 * </ul>
 * Tiles span negative and positive coordinates.
 */
final class WorldIoFixtures {
    private WorldIoFixtures() {
        // Utility class
    }

    static World2 create(String profile, int side) {
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        final int minHeight = platform.minZ, maxHeight = platform.standardMaxHeight;
        final long seed = 20261102L;
        final TileFactory factory = profile.equals("uniform")
                ? TileFactoryFactory.createFlatTileFactory(seed, Terrain.GRASS, minHeight, maxHeight, 64, 62, false, false)
                : TileFactoryFactory.createNoiseTileFactory(seed, Terrain.GRASS, minHeight, maxHeight, 58, 62, false, true, 20, 1.0);
        final World2 world = new World2(platform, seed, factory);
        world.setName("io-" + profile);
        final Dimension surface = world.getDimension(NORMAL_DETAIL);
        addTiles(surface, factory, -side / 2, -side / 2, side, side);
        if (profile.equals("layers")) {
            final Random random = new Random(seed);
            final MixedMaterial custom = MixedMaterial.create("io-custom", Material.MOSSY_COBBLESTONE);
            Terrain.setCustomMaterial(0, custom);
            final GroundCoverLayer cover = new GroundCoverLayer("io-cover", MixedMaterial.create("io-snow", Material.SNOW), java.awt.Color.WHITE);
            final List<CustomLayer> customLayers = new ArrayList<>();
            customLayers.add(cover);
            surface.setCustomLayers(customLayers);
            surface.setAttribute(new AttributeKey<>("io.test.dimension"), "surface-" + seed);
            world.setAttribute(new AttributeKey<>("io.test.world"), 42);
            for (Tile tile: surface.getTiles()) {
                paint(tile, random, cover);
            }
            final TileFactory netherFactory = TileFactoryFactory.createNoiseTileFactory(seed + 1, Terrain.NETHERRACK, 0, 256, 64, 32, true, false, 30, 1.0);
            final Dimension nether = new Dimension(world, "Nether", seed + 1, netherFactory, NETHER_DETAIL);
            addTiles(nether, netherFactory, -side / 8, -side / 8, side / 4, side / 4);
            world.addDimension(nether);
            final TileFactory endFactory = TileFactoryFactory.createFlatTileFactory(seed + 2, Terrain.END_STONE, 0, 256, 48, 0, false, false);
            final Dimension end = new Dimension(world, "End", seed + 2, endFactory, END_DETAIL);
            addTiles(end, endFactory, -2, -2, 4, 4);
            world.addDimension(end);
            final TileFactory caveFactory = TileFactoryFactory.createFlatTileFactory(seed + 3, Terrain.STONE, minHeight, maxHeight, 20, 0, false, false);
            final Dimension caves = new Dimension(world, "Caves", seed + 3, caveFactory, NORMAL_DETAIL_CEILING);
            addTiles(caves, caveFactory, -side / 8, 0, side / 4, side / 8);
            world.addDimension(caves);
        }
        return world;
    }

    private static void addTiles(Dimension dimension, TileFactory factory, int x0, int y0, int width, int height) {
        dimension.setEventsInhibited(true);
        try {
            for (int y = y0; y < y0 + height; y++) {
                for (int x = x0; x < x0 + width; x++) {
                    dimension.addTile(factory.createTile(x, y));
                }
            }
        } finally {
            dimension.setEventsInhibited(false);
        }
    }

    private static void paint(Tile tile, Random random, Layer cover) {
        final int tx = tile.getX(), ty = tile.getY();
        final int kind = Math.floorMod(tx * 5 + ty * 3, 7);
        tile.inhibitEvents();
        try {
            final int phase = random.nextInt(64);
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    final int wx = tx * TILE_SIZE + x, wy = ty * TILE_SIZE + y;
                    if (kind != 0) {
                        tile.setLayerValue(Biome.INSTANCE, x, y, Math.floorMod((wx >> 5) + (wy >> 6) + phase, 50));
                    }
                    if ((kind & 1) == 1) {
                        tile.setLayerValue(DeciduousForest.INSTANCE, x, y, Math.floorMod((wx * 7 + wy * 3) >> 3, 16));
                    }
                    if (kind == 2) {
                        tile.setLayerValue(PineForest.INSTANCE, x, y, 9); // Uniform layer
                    }
                    if (kind >= 4) {
                        tile.setBitLayerValue(Frost.INSTANCE, x, y, ((wx ^ wy) & 8) == 0);
                    }
                    if ((kind == 5) && ((x & 15) == 0) && ((y & 15) == 0)) {
                        tile.setBitLayerValue(Populate.INSTANCE, x, y, true);
                    }
                    if ((kind == 3) && (x < 64)) {
                        tile.setBitLayerValue(cover, x, y, true);
                        tile.setTerrain(x, y, Terrain.CUSTOM_1);
                    }
                    if ((kind == 6) && (y < 20)) {
                        tile.setLayerValue(Annotations.INSTANCE, x, y, 1 + (x & 7));
                    }
                }
            }
        } finally {
            tile.releaseEvents();
        }
    }
}
