package org.pepsoft.minecraft;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MC115AnvilChunkLightingTest {
    @Test
    public void roundTripsSkyAndBlockLightNibblesAcrossSection() {
        final MC115AnvilChunk chunk = new MC115AnvilChunk(0, 0, 16);

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunk.setSkyLightLevel(x, y, z, (x + 3 * z + 5 * y) & 0x0f);
                    chunk.setBlockLightLevel(x, y, z, (7 * x + 5 * z + 11 * y) & 0x0f);
                }
            }
        }

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals((x + 3 * z + 5 * y) & 0x0f,
                            chunk.getSkyLightLevel(x, y, z));
                    assertEquals((7 * x + 5 * z + 11 * y) & 0x0f,
                            chunk.getBlockLightLevel(x, y, z));
                }
            }
        }
    }
}
