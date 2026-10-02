package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.CombinedLayer;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** One compact transaction per tile, with Java's random stream recorded in original call order. */
public final class CombinedLayerAccess {
    private static final int AREA = 16384, MAX_BYTES = 4 * 1024 * 1024;
    private static final ThreadLocal<ByteBuffer> BUFFER = new ThreadLocal<>();

    private CombinedLayerAccess() { }

    /** A missing native symbol replays recorded draws through the original Java implementation. */
    public record Result(Set<Layer> layers, DoubleSupplier random) { }

    public static Result apply(Tile tile, CombinedLayer source, DoubleSupplier random) {
        if (tile.getClass() != Tile.class || source.getClass() != CombinedLayer.class
                || !tile.hasLayer(source) || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return null;
        List<Layer> targets = new ArrayList<>();
        List<Integer> roles = new ArrayList<>();
        List<Float> factors = new ArrayList<>();
        List<Integer> constants = new ArrayList<>();
        Set<Layer> unique = new HashSet<>();
        int terrainPass = -1, biomePass = -1;
        if (source.isApplyTerrainAndBiomeOnExport()) {
            if (source.getTerrain() != null) {
                terrainPass = targets.size();
                targets.add(null); roles.add(2); factors.add(0f); constants.add(source.getTerrain().ordinal());
            }
            if (source.getBiome() != -1) {
                if (source.getBiome() < 0 || source.getBiome() > 255) return null;
                biomePass = targets.size();
                targets.add(Biome.INSTANCE); roles.add(3); factors.add(0f); constants.add(source.getBiome());
                unique.add(Biome.INSTANCE);
            }
        }
        for (Layer target : source.getLayers()) {
            if (target.equals(source) || !unique.add(target)) return null;
            int bits = bits(target);
            Float factor = source.getFactors().get(target);
            if (bits < 0 || factor == null || target.getDefaultValue() < 0
                    || target.getDefaultValue() > target.dataSize.maxValue) return null;
            targets.add(target); roles.add(bits < 4 ? 1 : 0); factors.add(factor); constants.add(0);
        }
        int count = targets.size();
        if (count == 0) return null;
        int sourceOffset = 32 + count * 32;
        int rngOffset = sourceOffset + AREA / 2, maxDraws = 0;
        for (int i = 0; i < count; i++) {
            int b = targets.get(i) == null ? 8 : bits(targets.get(i));
            rngOffset += bytes(b);
            if (roles.get(i) != 0) maxDraws += AREA;
        }
        long required = (long) rngOffset + maxDraws * 8L;
        if (count > 128 || required > MAX_BYTES) return null;
        ByteBuffer data = BUFFER.get();
        if (data == null || data.capacity() < required) {
            data = ByteBuffer.allocateDirect((int) required).order(ByteOrder.LITTLE_ENDIAN);
            BUFFER.set(data);
        }
        data.clear().limit((int) required);
        data.putInt(0, 0x424c4357).putInt(4, 1).putInt(8, count).putInt(12, sourceOffset)
                .putInt(16, rngOffset).putInt(20, 0).putInt(24, 0).putInt(28, 0);
        Layer[] layers = targets.toArray(new Layer[0]);
        synchronized (tile) {
            tile.copyCombinedLayerPlane(source, 4, data, sourceOffset);
            int offset = sourceOffset + AREA / 2;
            for (int i = 0; i < count; i++) {
                int d = 32 + i * 32, b = layers[i] == null ? 8 : bits(layers[i]);
                boolean present = tile.copyCombinedLayerPlane(layers[i], b, data, offset);
                data.putInt(d, roles.get(i)).putInt(d + 4, b).putFloat(d + 8, factors.get(i))
                        .putInt(d + 12, constants.get(i)).putInt(d + 16, offset)
                        .putInt(d + 20, present ? 1 : 0).putInt(d + 24, 0).putInt(d + 28, 0);
                offset += bytes(b);
            }
            int draws = 0;
            // Terrain and biome are interleaved; subsequent bit layers each have their own full pass.
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                int value = value(data, sourceOffset, x + y * 128);
                if (value > 0 && value < 8) {
                    if (terrainPass >= 0) data.putDouble(rngOffset + draws++ * 8, random.getAsDouble());
                    if (biomePass >= 0) data.putDouble(rngOffset + draws++ * 8, random.getAsDouble());
                }
            }
            for (int i = 0; i < count; i++) if (roles.get(i) == 1) {
                float factor = factors.get(i);
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                    float strength = Math.min(value(data, sourceOffset, x + y * 128) / 15.0f * factor, 1.0f);
                    if (!(strength > 0.95f)) data.putDouble(rngOffset + draws++ * 8, random.getAsDouble());
                }
            }
            data.putInt(20, draws).limit(rngOffset + draws * 8);
            if (!NativeSlices.applyCombinedLayer(data)) {
                ByteBuffer saved = data;
                int savedDraws = draws, savedOffset = rngOffset;
                return new Result(null, new DoubleSupplier() {
                    private int cursor;
                    @Override public double getAsDouble() {
                        return cursor < savedDraws ? saved.getDouble(savedOffset + cursor++ * 8) : random.getAsDouble();
                    }
                });
            }
            Set<Layer> added = new HashSet<>();
            for (int i = 0; i < count; i++) {
                int d = 32 + i * 32;
                if (roles.get(i) < 2 && data.getInt(d + 24) != 0) added.add(layers[i]);
                if (data.getInt(d + 28) != 0) tile.applyCombinedLayerPlane(layers[i],
                        data.getInt(d + 4), data, data.getInt(d + 16));
            }
            tile.clearLayerData(source);
            return new Result(added, random);
        }
    }

    private static int value(ByteBuffer data, int source, int cell) {
        return (data.get(source + cell / 2) >>> ((cell & 1) * 4)) & 15;
    }
    private static int bits(Layer layer) {
        return switch (layer.dataSize) {
            case BIT_PER_CHUNK -> 0;
            case BIT -> 1;
            case NIBBLE -> 4;
            case BYTE -> 8;
            default -> -1;
        };
    }
    private static int bytes(int bits) { return bits == 0 ? 8 : AREA * bits / 8; }
}
