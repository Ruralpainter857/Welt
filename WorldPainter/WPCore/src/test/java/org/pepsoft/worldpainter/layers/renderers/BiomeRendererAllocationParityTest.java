package org.pepsoft.worldpainter.layers.renderers;

import org.junit.Test;
import org.pepsoft.util.ColourUtils;
import org.pepsoft.worldpainter.biomeschemes.CustomBiome;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BiomeRendererAllocationParityTest {
    @Test
    public void preservesPixelsForDirectAndConvertedCustomPatterns() {
        final CustomBiomeManager manager = new CustomBiomeManager();
        final List<CustomBiome> biomes = new ArrayList<>();
        final BufferedImage[] patterns = {
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB),
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB_PRE),
                new BufferedImage(16, 16, BufferedImage.TYPE_4BYTE_ABGR),
                new BufferedImage(16, 16, BufferedImage.TYPE_BYTE_INDEXED)
        };
        final int[] biomeIds = new int[patterns.length];
        for (int patternIndex = 0; patternIndex < patterns.length; patternIndex++) {
            final int id = manager.getNextId();
            assertTrue("custom biome ID should be available", id >= 0);
            biomeIds[patternIndex] = id;
            final CustomBiome biome = new CustomBiome("Pattern " + patternIndex, id);
            fillPattern(patterns[patternIndex], patternIndex);
            biome.setPattern(patterns[patternIndex]);
            biomes.add(biome);
            manager.setCustomBiomes(new ArrayList<>(biomes));
        }

        final BiomeRenderer renderer = new BiomeRenderer(manager, org.pepsoft.worldpainter.ColourScheme.DEFAULT);
        for (int patternIndex = 0; patternIndex < patterns.length; patternIndex++) {
            final BufferedImage pattern = patterns[patternIndex];
            final int id = biomeIds[patternIndex];
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    final int base = 0x005a93c7;
                    final int oldRgb = pattern.getRGB(x, y);
                    final int expected = ((oldRgb & 0xff000000) != 0)
                            ? ColourUtils.mix(base, oldRgb) : base;
                    assertEquals("pattern " + patternIndex + " at " + x + "," + y,
                            expected, renderer.getPixelColour(x, y, base, id));
                }
            }
            final int x = 3 + patternIndex;
            final int y = 7 + patternIndex;
            pattern.setRGB(x, y, 0x8064a2d8 + patternIndex);
            final int updatedRgb = pattern.getRGB(x, y);
            final int expectedUpdated = ((updatedRgb & 0xff000000) != 0)
                    ? ColourUtils.mix(0x005a93c7, updatedRgb) : 0x005a93c7;
            assertEquals("updated pattern " + patternIndex,
                    expectedUpdated, renderer.getPixelColour(x, y, 0x005a93c7, id));
        }
    }

    private static void fillPattern(BufferedImage image, int salt) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                final int alpha = ((x * 13 + y * 7 + salt * 31) & 3) * 85;
                final int red = (x * 17 + salt * 23) & 0xff;
                final int green = (y * 29 + salt * 41) & 0xff;
                final int blue = (x * 11 + y * 19 + salt * 53) & 0xff;
                image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
    }
}
