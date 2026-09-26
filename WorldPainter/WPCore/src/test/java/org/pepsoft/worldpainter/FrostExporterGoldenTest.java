package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.exporting.MinecraftWorld;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.exporters.FrostExporter;

import java.awt.Rectangle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.pepsoft.minecraft.Constants.MC_AIR;
import static org.pepsoft.minecraft.Constants.MC_ICE;
import static org.pepsoft.minecraft.Constants.MC_SNOW;
import static org.pepsoft.minecraft.Constants.MC_WATER;
import static org.pepsoft.minecraft.Material.*;

/** Cross-language reference for the production FrostExporter column behavior. */
public final class FrostExporterGoldenTest {
    private static final Rectangle DIMENSION_AREA = new Rectangle(0, 0, 128, 128);
    private static final Rectangle COLUMN_AREA = new Rectangle(0, 0, 1, 1);

    @Test
    public void productionFrostExporterMatchesCheckedInReference() throws Exception {
        final String requestedOutput = System.getProperty("welt.frost.golden.output");
        final Path generated = Files.createTempFile("welt-frost-golden-", ".txt");
        try {
            dump(generated);
            if ((requestedOutput != null) && !requestedOutput.isBlank()) {
                Files.copy(generated, Path.of(requestedOutput), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            final Path checkedIn = Path.of("..", "..", "welt-native", "golden", "frost-export-golden.txt");
            assertEquals("Run with -Dwelt.frost.golden.output=<path> to regenerate the Java golden",
                    Files.readString(checkedIn, StandardCharsets.UTF_8),
                    Files.readString(generated, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(generated);
        }
    }

    private static void dump(Path output) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("# Generated from production FrostExporter.addFeatures, JDK 17\n");
            writer.write("# case minZ maxZ highest frostEverywhere frostLayer snowUnderTrees mode randomLayers heightFloatBits heightInt frostBitCount\n");
            writeCase(writer, "water-flat", true, false, true,
                    FrostExporter.FrostSettings.MODE_FLAT, 62, world -> {
                        world.setMaterialAt(0, 0, 60, WATER);
                        world.setMaterialAt(0, 0, 61, AIR);
                        world.setMaterialAt(0, 0, 62, AIR);
                    });
            writeCase(writer, "snow-flat", true, false, true,
                    FrostExporter.FrostSettings.MODE_FLAT, 62, world -> { });
            writeCase(writer, "snow-smooth-existing", true, false, true,
                    FrostExporter.FrostSettings.MODE_SMOOTH, 62, world -> {
                        world.setMaterialAt(0, 0, 63, SNOW.withProperty(LAYERS, 3));
                    });
            writeCase(writer, "snow-smooth-all-elevations", true, false, true,
                    FrostExporter.FrostSettings.MODE_SMOOTH_AT_ALL_ELEVATIONS, 62, world -> {
                        world.setMaterialAt(0, 0, 58, GRASS_BLOCK);
                        for (int z = 59; z <= 62; z++) {
                            world.setMaterialAt(0, 0, z, AIR);
                        }
                    });
            writeCase(writer, "snow-under-leaves", true, false, true,
                    FrostExporter.FrostSettings.MODE_FLAT, 62, world -> {
                        world.setMaterialAt(0, 0, 63, AIR);
                        world.setMaterialAt(0, 0, 64, Material.get("minecraft:oak_leaves"));
                        world.setMaterialAt(0, 0, 65, Material.get("minecraft:oak_leaves"));
                    });
            writeCase(writer, "frost-layer-absent", false, false, true,
                    FrostExporter.FrostSettings.MODE_FLAT, 62, world -> { });
        }
    }

    private static void writeCase(BufferedWriter writer, String name, boolean frostEverywhere,
                                  boolean frostLayerPresent, boolean snowUnderTrees, int mode,
                                  int terrainHeight, WorldSetup setup) throws IOException {
        final Dimension dimension = TestData.createDimension(DIMENSION_AREA, terrainHeight);
        final MinecraftWorld world = TestData.createMinecraftWorld(COLUMN_AREA, terrainHeight, GRASS_BLOCK);
        setup.configure(world);
        final int minZ = TestData.MIN_HEIGHT;
        final int maxZ = TestData.MAX_HEIGHT;
        final int highest = world.getHighestNonAirBlock(0, 0);
        final Material[] original = new Material[maxZ - minZ + 1];
        for (int z = minZ; z <= maxZ; z++) {
            original[z - minZ] = world.getMaterialAt(0, 0, z);
        }

        final FrostExporter.FrostSettings settings = new FrostExporter.FrostSettings();
        settings.setFrostEverywhere(frostEverywhere);
        settings.setSnowUnderTrees(snowUnderTrees);
        settings.setMode(mode);
        final FrostExporter exporter = new FrostExporter(dimension, TestData.PLATFORM, settings);
        exporter.addFeatures(COLUMN_AREA, COLUMN_AREA, world);

        final boolean actualFrostLayer = dimension.getBitLayerValueAt(Frost.INSTANCE, 0, 0);
        final float heightFloat = dimension.getHeightAt(0, 0);
        final int heightInt = dimension.getIntHeightAt(0, 0);
        final int frostBitCount = dimension.getBitLayerCount(Frost.INSTANCE, 0, 0, 1);
        writer.write(String.format(Locale.ROOT, "case %s %d %d %d %d %d %d %d 2 %08x %d %d%n",
                name, minZ, maxZ, highest, frostEverywhere ? 1 : 0,
                frostLayerPresent || actualFrostLayer ? 1 : 0, snowUnderTrees ? 1 : 0, mode,
                Float.floatToRawIntBits(heightFloat), heightInt, frostBitCount));
        for (int z = minZ; z <= maxZ; z++) {
            final Material before = original[z - minZ];
            final Material after = world.getMaterialAt(0, 0, z);
            final int flags = flags(before);
            final int snowLayers = before.isNamed(MC_SNOW) ? before.getProperty(LAYERS, 1) : 0;
            final int[] update = update(before, after);
            writer.write(String.format(Locale.ROOT, "cell %d %d %d %d %d%n",
                    z, flags, snowLayers, update[0], update[1]));
        }
    }

    private static int flags(Material material) {
        int result = 0;
        if (material.isNamed(MC_WATER) && material.getProperty(LAYERS, 0) == 0) result |= 1;
        if (material.containsWater()) result |= 1 << 1;
        if (material.insubstantial) result |= 1 << 2;
        if (material.canSupportSnow) result |= 1 << 3;
        if (material.leafBlock) result |= 1 << 4;
        if (material.sustainsLeaves) result |= 1 << 5;
        if (material.empty) result |= 1 << 6;
        if ((material == GRASS) || (material == FERN)) result |= 1 << 7;
        return result;
    }

    private static int[] update(Material before, Material after) {
        if (Objects.equals(before, after)) return new int[] {0, 0};
        if (after.isNamed(MC_AIR)) return new int[] {1, 0};
        if (after.isNamed(MC_ICE)) return new int[] {2, 0};
        if (after.isNamed(MC_SNOW)) return new int[] {3, after.getProperty(LAYERS, 1)};
        throw new AssertionError("Unexpected FrostExporter replacement: " + before + " -> " + after);
    }

    @FunctionalInterface
    private interface WorldSetup {
        void configure(MinecraftWorld world);
    }
}
