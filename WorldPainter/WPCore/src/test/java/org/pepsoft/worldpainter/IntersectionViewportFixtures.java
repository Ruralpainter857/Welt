package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.tunnel.TunnelLayer;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static org.pepsoft.worldpainter.Dimension.Role.CAVE_FLOOR;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL_CEILING;

/**
 * Real linked dimensions for intersection rendering: a surface with an inverted ceiling dimension (with missing ceiling
 * tiles), and a cave tunnel layer on the surface with its {@code CAVE_FLOOR} dimension, built the way
 * {@code TunnelLayerDialog} does. Heights follow fixed formulas so that intersections cover part of every tile, and the
 * floor dimension extends one ring beyond the surface so that some floor tiles have no detail tile.
 */
final class IntersectionViewportFixtures {
    private IntersectionViewportFixtures() {
        // Utility class
    }

    static final class Worlds {
        World2 world;
        Dimension surface, ceiling, floor;
        TunnelLayer tunnel;
        final List<Tile> surfaceTiles = new ArrayList<>(), ceilingTiles = new ArrayList<>(), floorTiles = new ArrayList<>();
    }

    static Worlds create(int side, TunnelLayer.Mode roofMode, boolean roofNoise, int minHeight, int maxHeight) {
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        final Worlds worlds = new Worlds();
        final TileFactory surfaceFactory = TileFactoryFactory.createFlatTileFactory(31L, Terrain.GRASS, minHeight, maxHeight, 62, 62, false, false);
        worlds.world = new World2(platform, 31L, surfaceFactory);
        worlds.surface = worlds.world.getDimension(NORMAL_DETAIL);
        final TileFactory ceilingFactory = TileFactoryFactory.createFlatTileFactory(32L, Terrain.STONE, minHeight, maxHeight, 70, minHeight, false, false);
        worlds.ceiling = new Dimension(worlds.world, "Ceiling", 32L, ceilingFactory, NORMAL_DETAIL_CEILING);
        worlds.ceiling.setCeilingHeight(maxHeight - 106);
        worlds.world.addDimension(worlds.ceiling);

        worlds.tunnel = new TunnelLayer("Tunnel", TunnelLayer.LayerMode.CAVE, Color.ORANGE, platform);
        worlds.tunnel.setRoofMode(roofMode);
        worlds.tunnel.setRoofLevel(roofMode == TunnelLayer.Mode.FIXED_HEIGHT ? 44 : 18);
        worlds.tunnel.setRoofMin(minHeight + 4);
        worlds.tunnel.setRoofMax(maxHeight - 40);
        worlds.tunnel.setRoofNoise(roofNoise ? new NoiseSettings(0L, 6, 2, 1.5f) : null);
        worlds.tunnel.setFloorMode(TunnelLayer.Mode.CUSTOM_DIMENSION);
        worlds.tunnel.setFloorDimensionId(0);
        final List<CustomLayer> customLayers = new ArrayList<>();
        customLayers.add(worlds.tunnel);
        worlds.surface.setCustomLayers(customLayers);
        final TileFactory floorFactory = TileFactoryFactory.createFlatTileFactory(33L, Terrain.STONE, minHeight, maxHeight, 40, minHeight, false, false);
        worlds.floor = new Dimension(worlds.world, "Tunnel Floor", 33L, floorFactory, new Dimension.Anchor(0, CAVE_FLOOR, false, 0));
        worlds.world.addDimension(worlds.floor);

        final int origin = side / 2;
        for (int ty = -origin - 1; ty < side - origin + 1; ty++) {
            for (int tx = -origin - 1; tx < side - origin + 1; tx++) {
                final boolean inner = (tx >= -origin) && (tx < side - origin) && (ty >= -origin) && (ty < side - origin);
                if (inner) {
                    final Tile surface = surfaceFactory.createTile(tx, ty);
                    fill(surface, 64, 20, 3, 7, Terrain.GRASS, true);
                    worlds.surface.addTile(surface);
                    worlds.surfaceTiles.add(surface);
                    if (Math.floorMod(tx + 2 * ty, 5) != 0) { // Missing ceiling tiles
                        final Tile ceiling = ceilingFactory.createTile(tx, ty);
                        fill(ceiling, 72, 24, 5, 2, Terrain.STONE, false);
                        worlds.ceiling.addTile(ceiling);
                        worlds.ceilingTiles.add(ceiling);
                    }
                }
                final Tile floor = floorFactory.createTile(tx, ty);
                fill(floor, 42, 14, 2, 5, Terrain.STONE, false);
                worlds.floor.addTile(floor);
                worlds.floorTiles.add(floor);
            }
        }
        return worlds;
    }

    private static void fill(Tile tile, int base, int amplitude, int fx, int fy, Terrain terrain, boolean layers) {
        tile.inhibitEvents();
        try {
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    final int wx = (tile.getX() << 7) + x, wy = (tile.getY() << 7) + y;
                    final double wave = Math.sin(wx * fx / 97.0) + Math.cos(wy * fy / 113.0);
                    tile.setHeight(x, y, (float) (base + amplitude * wave / 2 + ((wx * 7 + wy * 3) & 3) / 4.0));
                    tile.setWaterLevel(x, y, base - amplitude / 3);
                    tile.setTerrain(x, y, (((wx >> 5) + (wy >> 5)) & 3) == 0 ? Terrain.SAND : terrain);
                    if (layers) {
                        tile.setBitLayerValue(Frost.INSTANCE, x, y, ((wx + wy) & 15) == 0);
                        tile.setLayerValue(DeciduousForest.INSTANCE, x, y, (wx * 3 + wy) & 15);
                        tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, x, y, (x > 120) && (y > 120));
                        tile.setBitLayerValue(NotPresent.INSTANCE, x, y, (x < 4) && (y > 124));
                    }
                }
            }
        } finally {
            tile.releaseEvents();
        }
    }

    static TileRenderer renderer(Dimension dimension, int zoom) {
        final TileRenderer renderer = new TileRenderer(dimension, ColourScheme.DEFAULT, new CustomBiomeManager(), zoom, true, null);
        renderer.setContourLines(true);
        return renderer;
    }
}
