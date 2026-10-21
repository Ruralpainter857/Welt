package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.pepsoft.worldpainter.biomeschemes.Minecraft1_21Biomes.*;

final class AutoBiomeAccess {
    // ABI v1 : en-tête de 64 octets, puis profondeur, flags, biome courant et biome du terrain par cellule.
    static final int BYTES = 64 + 16384 * 8;
    private static final Terrain[] TERRAINS = Terrain.values();
    private static final int[] BIOMES = {BIOME_FROZEN_RIVER, BIOME_COLD_TAIGA, BIOME_FROZEN_OCEAN,
            BIOME_ICE_PLAINS, BIOME_RIVER, BIOME_SWAMPLAND, BIOME_JUNGLE, BIOME_OCEAN, BIOME_DEEP_OCEAN, BIOME_FOREST};
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    static Scratch prepare(int constantBiome, int defaultBiome) {
        Scratch scratch = SCRATCH.get();
        ByteBuffer buffer = scratch.buffer;
        buffer.clear();
        buffer.putInt(0, 0x4d424157).putInt(4, 1).putInt(8, constantBiome).putInt(12, 0);
        for (int i = 0; i < BIOMES.length; i++) buffer.putInt(16 + i * 4, BIOMES[i]);
        buffer.putInt(56, 0).putInt(60, 0);
        for (Terrain terrain : TERRAINS) {
            int biome = terrain.isConfigured() && terrain.getDefaultBiome() != -1 ? terrain.getDefaultBiome() : defaultBiome;
            scratch.biomes[terrain.ordinal()] = biome;
            scratch.forest[terrain.ordinal()] = biome != BIOME_DESERT && biome != BIOME_DESERT_HILLS
                    && biome != BIOME_DESERT_M && biome != BIOME_MESA && biome != BIOME_MESA_BRYCE
                    && biome != BIOME_MESA_PLATEAU && biome != BIOME_MESA_PLATEAU_F
                    && biome != BIOME_MESA_PLATEAU_F_M && biome != BIOME_MESA_PLATEAU_M;
        }
        return scratch;
    }

    static boolean bake(Scratch scratch) { return NativeSlices.bakeAutoBiomes(scratch.buffer); }

    static final class Scratch {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final int[] biomes = new int[TERRAINS.length];
        final boolean[] forest = new boolean[TERRAINS.length];
    }
}
