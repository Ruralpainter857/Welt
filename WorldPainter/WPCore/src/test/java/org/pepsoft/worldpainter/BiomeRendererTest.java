package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.ColourUtils;
import org.pepsoft.worldpainter.biomeschemes.CustomBiome;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.biomeschemes.StaticBiomeInfo;
import org.pepsoft.worldpainter.layers.renderers.BiomeRenderer;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BiomeRendererTest {
    @Test
    public void defaultSchemePatternsMatchBiomeInfo() {
        final BiomeRenderer first = new BiomeRenderer(null, ColourScheme.DEFAULT);
        final BiomeRenderer second = new BiomeRenderer(null, ColourScheme.DEFAULT);
        assertMatchesBiomeInfo(first, ColourScheme.DEFAULT);
        assertMatchesBiomeInfo(second, ColourScheme.DEFAULT);
    }

    @Test
    public void customSchemePatternsDoNotUseDefaultColours() {
        final ColourScheme customScheme = material -> ColourScheme.DEFAULT.getColour(material) ^ 0x00123456;
        final BiomeRenderer renderer = new BiomeRenderer(null, customScheme);
        assertMatchesBiomeInfo(renderer, customScheme);
    }

    @Test
    public void customBiomesDoNotMutateSharedDefaultPatterns() {
        final int customId = firstAvailableCustomBiomeId();
        final int customColour = 0x002a4b6c;
        final CustomBiome customBiome = new CustomBiome("Test biome", customId, customColour);
        final CustomBiomeManager customBiomeManager = new CustomBiomeManager();
        customBiomeManager.setCustomBiomes(Collections.singletonList(customBiome));
        final BiomeRenderer customRenderer = new BiomeRenderer(customBiomeManager, ColourScheme.DEFAULT);
        final BiomeRenderer defaultRenderer = new BiomeRenderer(null, ColourScheme.DEFAULT);
        final int underlyingColour = 0xff123456;

        assertEquals(ColourUtils.mix(underlyingColour, 0xff000000 | customColour),
                customRenderer.getPixelColour(3, 7, underlyingColour, customId));
        assertEquals(underlyingColour, defaultRenderer.getPixelColour(3, 7, underlyingColour, customId));
    }

    private static int firstAvailableCustomBiomeId() {
        for (int biome = 0; biome < 255; biome++) {
            if (!StaticBiomeInfo.INSTANCE.isBiomePresent(biome)) {
                try {
                    new CustomBiome("Test biome", biome);
                    return biome;
                } catch (IllegalArgumentException ignored) {
                    // Keep looking until an absent biome has a valid custom ID.
                }
            }
        }
        fail("no custom biome ID available for the test");
        throw new AssertionError("unreachable");
    }

    private static void assertMatchesBiomeInfo(BiomeRenderer renderer, ColourScheme colourScheme) {
        final BiomeScheme biomeInfo = StaticBiomeInfo.INSTANCE;
        final int underlyingColour = 0xff123456;
        for (int biome = 0; biome < 255; biome++) {
            if (!biomeInfo.isBiomePresent(biome)) {
                assertEquals("absent biome " + biome, underlyingColour,
                        renderer.getPixelColour(0, 0, underlyingColour, biome));
                continue;
            }

            final boolean[][] pattern = biomeInfo.getPattern(biome);
            final int colour = biomeInfo.getColour(biome, colourScheme);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    final int rgb = ((pattern != null) && pattern[x][y] ? 0 : colour) | 0xff000000;
                    final int expected = ColourUtils.mix(underlyingColour, rgb);
                    assertEquals("biome " + biome + " at " + x + "," + y, expected,
                            renderer.getPixelColour(x, y, underlyingColour, biome));
                }
            }
        }
        assertEquals("unset biome", underlyingColour, renderer.getPixelColour(0, 0, underlyingColour, 255));
    }
}
