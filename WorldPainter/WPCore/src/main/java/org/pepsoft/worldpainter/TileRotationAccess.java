package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;

import static org.pepsoft.worldpainter.Tile.TileBuffer.*;

/** Whole-tile rotation boundary; the caller holds the source tile monitor. */
final class TileRotationAccess {
    private static final int MAGIC = 0x544f5257, CELLS = 128 * 128;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ThreadLocal<ByteBuffer> BUFFERS = new ThreadLocal<>();

    private TileRotationAccess() { }

    static Tile rotate(Tile source, CoordinateTransform transform, Point coordinates) {
        final int turns = transform == CoordinateTransform.ROTATE_CLOCKWISE_90_DEGREES ? 1
                : transform == CoordinateTransform.ROTATE_180_DEGREES ? 2
                : transform == CoordinateTransform.ROTATE_CLOCKWISE_270_DEGREES ? 3 : 0;
        if (turns == 0 || source.getClass() != Tile.class
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return null;
        }
        final boolean tall = source.getMaxHeight() - source.getMinHeight() > 256;
        source.ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        source.ensureReadable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        source.ensureReadable(TERRAIN);
        source.ensureReadable(LAYER_DATA);
        source.ensureReadable(BIT_LAYER_DATA);
        final List<Layer> layers = source.getLayers();
        final int count = 3 + layers.size();
        if (count > 128) {
            return null;
        }
        int total = 16 + count * 16 + CELLS * (tall ? 7 : 4);
        for (Layer layer : layers) {
            final int bytes = planeLength(layer);
            if (bytes == 0) {
                return null;
            }
            final byte[] values = source.layerData.get(layer);
            if ((layer.getDataSize() == Layer.DataSize.NIBBLE || layer.getDataSize() == Layer.DataSize.BYTE)
                    && (values == null || values.length != bytes)) {
                return null;
            }
            total += bytes;
        }
        if (total > MAX_BYTES) {
            return null;
        }
        ByteBuffer buffer = BUFFERS.get();
        if (buffer == null || buffer.capacity() < total) {
            int capacity = 1;
            while (capacity < total) {
                capacity <<= 1;
            }
            buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
            BUFFERS.set(buffer);
        }
        buffer.clear().limit(total);
        buffer.putInt(MAGIC).putInt(1).putInt(turns).putInt(count);
        int offset = 16 + count * 16;
        descriptor(buffer, 0, 128, tall ? 32 : 16, offset);
        buffer.position(offset);
        if (tall) {
            buffer.asIntBuffer().put(source.tallHeightMap);
        } else {
            buffer.asShortBuffer().put(source.heightMap);
        }
        offset += CELLS * (tall ? 4 : 2);
        descriptor(buffer, 1, 128, 8, offset);
        buffer.position(offset).put(source.terrain);
        offset += CELLS;
        descriptor(buffer, 2, 128, tall ? 16 : 8, offset);
        buffer.position(offset);
        if (tall) {
            buffer.asShortBuffer().put(source.tallWaterLevel);
        } else {
            buffer.put(source.waterLevel);
        }
        offset += CELLS * (tall ? 2 : 1);
        for (int i = 0; i < layers.size(); i++) {
            final Layer layer = layers.get(i);
            final int bits = bits(layer), side = layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK ? 8 : 128;
            final int bytes = planeLength(layer);
            descriptor(buffer, i + 3, side, bits, offset);
            buffer.position(offset);
            if (bits == 1) {
                final byte[] values = source.bitLayerData.get(layer).toByteArray();
                final int used = Math.min(values.length, bytes);
                buffer.put(values, 0, used);
                for (int remaining = used; remaining < bytes; remaining++) {
                    buffer.put((byte) 0);
                }
            } else {
                buffer.put(source.layerData.get(layer));
            }
            offset += bytes;
        }
        buffer.position(0);
        if (!NativeSlices.rotateTilePlanes(buffer)) {
            return null;
        }
        // Publish only after the entire native operation succeeds. Tile.init()
        // then interns uniform buffers and establishes its ordinary COW state.
        final Tile target = new Tile(coordinates.x >> 7, coordinates.y >> 7,
                source.getMinHeight(), source.getMaxHeight(), false);
        buffer.position(16 + count * 16);
        if (tall) {
            target.tallHeightMap = new int[CELLS];
            buffer.asIntBuffer().get(target.tallHeightMap);
            buffer.position(buffer.position() + CELLS * 4);
        } else {
            target.heightMap = new short[CELLS];
            buffer.asShortBuffer().get(target.heightMap);
            buffer.position(buffer.position() + CELLS * 2);
        }
        target.terrain = new byte[CELLS];
        buffer.get(target.terrain);
        if (tall) {
            target.tallWaterLevel = new short[CELLS];
            buffer.asShortBuffer().get(target.tallWaterLevel);
            buffer.position(buffer.position() + CELLS * 2);
        } else {
            target.waterLevel = new byte[CELLS];
            buffer.get(target.waterLevel);
        }
        target.layerData = new HashMap<>();
        target.bitLayerData = new HashMap<>();
        for (Layer layer : layers) {
            final byte[] values = new byte[planeLength(layer)];
            buffer.get(values);
            if (bits(layer) == 1) {
                final BitSet bitSet = BitSet.valueOf(values);
                if (!bitSet.isEmpty()) {
                    target.bitLayerData.put(layer, bitSet);
                }
            } else {
                final int defaultValue = layer.getDefaultValue();
                final byte defaultByte = bits(layer) == 4
                        ? (byte) (defaultValue | (defaultValue << 4)) : (byte) defaultValue;
                for (byte value : values) {
                    if (value != defaultByte) {
                        target.layerData.put(layer, values);
                        break;
                    }
                }
            }
        }
        return target;
    }

    private static int bits(Layer layer) {
        return switch (layer.getDataSize()) {
            case BIT, BIT_PER_CHUNK -> 1;
            case NIBBLE -> 4;
            case BYTE -> 8;
            default -> 0;
        };
    }

    private static int planeLength(Layer layer) {
        return layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK ? 8 : CELLS * bits(layer) / 8;
    }

    private static void descriptor(ByteBuffer buffer, int plane, int side, int bits, int offset) {
        final int start = 16 + plane * 16;
        buffer.putInt(start, side).putInt(start + 4, bits)
                .putInt(start + 8, offset).putInt(start + 12, side * side * bits / 8);
    }
}
