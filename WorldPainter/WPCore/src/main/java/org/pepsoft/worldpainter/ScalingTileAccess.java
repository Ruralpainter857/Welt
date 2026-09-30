package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.heightMaps.DelegatingHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static org.pepsoft.worldpainter.Tile.TileBuffer.*;

/** Shared source snapshot, one native resampling pass, and a grouped destination edit. */
final class ScalingTileAccess {
    static final int TABLE = 2104;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private ScalingTileAccess() { }

    static boolean scale(Tile target, Map<Point, Tile> sources, Map<Point, Tile> borders,
                         TileFactory factory, float scale, List<Layer> layers) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || !target.canApplyScaledData() || factory.getClass() != HeightMapTileFactory.class
                || !Float.isFinite(scale) || scale <= 0 || layers.size() > 127
                || Math.abs((long) target.getMinHeight()) > 16_000_000L
                || Math.abs((long) target.getMaxHeight()) > 16_000_000L) return false;
        if (!isBuiltInMap(((HeightMapTileFactory) factory).getHeightMap(), new IdentityHashMap<>())) return false;
        final Scratch scratch = SCRATCH.get();
        final boolean integer = scale == 1f;
        final float inverse = 1f / scale;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < 128; i++) {
            final int worldX = (target.getX() << 7) + i, worldY = (target.getY() << 7) + i;
            scratch.x[i] = integer ? worldX : (float) ((double) (float) worldX * inverse);
            scratch.y[i] = integer ? worldY : (float) ((double) (float) worldY * inverse);
            if (!Float.isFinite(scratch.x[i]) || !Float.isFinite(scratch.y[i])
                    || Math.abs(scratch.x[i]) > 16_000_000f || Math.abs(scratch.y[i]) > 16_000_000f) return false;
            scratch.nx[i] = (int) ((float) worldX / scale);
            scratch.ny[i] = (int) ((float) worldY / scale);
            final int fx = floor(scratch.x[i], integer), fy = floor(scratch.y[i], integer);
            minX = Math.min(minX, Math.min(scratch.nx[i], fx - (integer ? 0 : 1)));
            minY = Math.min(minY, Math.min(scratch.ny[i], fy - (integer ? 0 : 1)));
            maxX = Math.max(maxX, Math.max(scratch.nx[i], fx + (integer ? 0 : 2)));
            maxY = Math.max(maxY, Math.max(scratch.ny[i], fy + (integer ? 0 : 2)));
        }
        final int width = maxX - minX + 1, height = maxY - minY + 1;
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return false;
        for (Layer layer : layers) {
            if (layer.dataSize != Layer.DataSize.BIT && layer.dataSize != Layer.DataSize.BIT_PER_CHUNK
                    && layer.dataSize != Layer.DataSize.NIBBLE && layer.dataSize != Layer.DataSize.BYTE) return false;
            if (layer.getDefaultValue() < 0 || layer.getDefaultValue() > layer.dataSize.maxValue) return false;
        }
        final int area = width * height, count = layers.size() + 1;
        final long totalLong = TABLE + count * 32L + (area + 16384L) * (4 + layers.size());
        if (totalLong > 8 * 1024 * 1024) return false;
        final int total = (int) totalLong;
        if (scratch.buffer == null || scratch.buffer.capacity() < total) {
            int capacity = 1; while (capacity < total) capacity <<= 1;
            scratch.buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
        }
        final ByteBuffer buffer = scratch.buffer;
        buffer.clear().limit(total);
        buffer.putInt(0, 0x4c435357).putInt(4, 1).putInt(8, width).putInt(12, height)
                .putInt(16, minX).putInt(20, minY).putInt(24, count).putInt(28, integer ? 1 : 0)
                .putInt(32, 56).putInt(36, 568).putInt(40, 1080).putInt(44, 1592)
                .putInt(48, TABLE).putInt(52, total);
        for (int i = 0; i < 128; i++) {
            buffer.putFloat(56 + i * 4, scratch.x[i]).putFloat(568 + i * 4, scratch.y[i])
                    .putInt(1080 + i * 4, scratch.nx[i]).putInt(1592 + i * 4, scratch.ny[i]);
        }
        int offset = TABLE + count * 32;
        for (int p = 0; p < count; p++) {
            final int bytes = p == 0 ? 4 : 1, descriptor = TABLE + p * 32;
            final Layer layer = p == 0 ? null : layers.get(p - 1);
            buffer.putInt(descriptor, p == 0 ? 0 : layer.discrete ? 2 : 1)
                    .putFloat(descriptor + 4, p == 0 ? target.getMinHeight() : 0)
                    .putFloat(descriptor + 8, p == 0 ? target.getMaxHeight() : layer.dataSize.maxValue)
                    .putInt(descriptor + 12, p == 0 ? 0 : layer.getDefaultValue())
                    .putInt(descriptor + 16, offset).putInt(descriptor + 20, offset + area * bytes)
                    .putLong(descriptor + 24, 0);
            offset += (area + 16384) * bytes;
        }
        // Copy terrain and fluid metadata from nearest source cells once. These
        // fields need no native computation and do not cross the JNI boundary.
        Arrays.fill(scratch.missing, 0);
        int neededX1 = Integer.MAX_VALUE, neededY1 = Integer.MAX_VALUE;
        int neededX2 = Integer.MIN_VALUE, neededY2 = Integer.MIN_VALUE;
        Tile cached = null; int cachedX = Integer.MIN_VALUE, cachedY = Integer.MIN_VALUE;
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                final int tx = scratch.nx[x] >> 7, ty = scratch.ny[y] >> 7, index = x + y * 128;
                if (tx != cachedX || ty != cachedY) { cached = sources.get(new Point(tx, ty)); cachedX = tx; cachedY = ty; }
                scratch.active[index] = (byte) (cached == null ? 0 : 1);
                if (cached == null) { scratch.missing[(x >> 4) * 8 + (y >> 4)]++; continue; }
                final int fx = floor(scratch.x[x], integer), fy = floor(scratch.y[y], integer);
                neededX1 = Math.min(neededX1, fx - (integer ? 0 : 1));
                neededY1 = Math.min(neededY1, fy - (integer ? 0 : 1));
                neededX2 = Math.max(neededX2, fx + (integer ? 0 : 2));
                neededY2 = Math.max(neededY2, fy + (integer ? 0 : 2));
                if (cached.getClass() != Tile.class) return false;
                synchronized (cached) {
                    final boolean tall = cached.getMaxHeight() - cached.getMinHeight() > 256;
                    cached.ensureReadable(tall ? TALL_WATERLEVEL : WATERLEVEL);
                    cached.ensureReadable(TERRAIN);
                    final int src = (scratch.nx[x] & 127) + (scratch.ny[y] & 127) * 128;
                    scratch.water[index] = (tall ? cached.tallWaterLevel[src] & 65535 : cached.waterLevel[src] & 255) + cached.getMinHeight();
                    scratch.terrain[index] = cached.terrain[src];
                }
            }
        }
        if (neededX1 == Integer.MAX_VALUE) {
            target.markScaledMissingChunks(scratch.missing);
            return true;
        }
        for (int tx = minX >> 7; tx <= maxX >> 7; tx++) {
            for (int ty = minY >> 7; ty <= maxY >> 7; ty++) {
                final Point key = new Point(tx, ty);
                final Tile actual = sources.get(key);
                final boolean needed = (tx << 7) <= neededX2 && (ty << 7) <= neededY2
                        && (tx << 7) + 127 >= neededX1 && (ty << 7) + 127 >= neededY1;
                final Tile heights = actual != null ? actual : needed
                        ? borders.computeIfAbsent(key, point -> factory.createTile(point.x, point.y)) : null;
                if (heights != null && heights.getClass() != Tile.class) return false;
                final int x1 = Math.max(minX, tx << 7), y1 = Math.max(minY, ty << 7);
                final int x2 = Math.min(maxX, (tx << 7) + 127), y2 = Math.min(maxY, (ty << 7) + 127);
                if (heights == null) {
                    final int start = buffer.getInt(TABLE + 16);
                    for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++)
                        buffer.putFloat(start + (x - minX + (y - minY) * width) * 4, 0);
                } else synchronized (heights) {
                    final boolean tall = heights.getMaxHeight() - heights.getMinHeight() > 256;
                    heights.ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
                    final int start = buffer.getInt(TABLE + 16);
                    for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) {
                        final int src = (x & 127) + (y & 127) * 128, dst = x - minX + (y - minY) * width;
                        buffer.putFloat(start + dst * 4, (tall ? heights.tallHeightMap[src] : heights.heightMap[src] & 65535) / 256f + heights.getMinHeight());
                    }
                }
                for (int p = 1; p < count; p++) {
                    final Layer layer = layers.get(p - 1);
                    final int start = buffer.getInt(TABLE + p * 32 + 16);
                    if (actual == null) {
                        for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++)
                            buffer.put(start + x - minX + (y - minY) * width, (byte) layer.getDefaultValue());
                    } else synchronized (actual) {
                        actual.ensureReadable(LAYER_DATA); actual.ensureReadable(BIT_LAYER_DATA);
                        final byte[] values = actual.layerData.get(layer);
                        final BitSet bits = actual.bitLayerData.get(layer);
                        for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) {
                            final int lx = x & 127, ly = y & 127, src = lx + ly * 128;
                            final int value = switch (layer.dataSize) {
                                case BIT -> bits != null && bits.get(src) ? 1 : 0;
                                case BIT_PER_CHUNK -> bits != null && bits.get(lx / 16 + ly / 16 * 8) ? 1 : 0;
                                case NIBBLE -> values == null ? layer.getDefaultValue() : (values[src / 2] >> ((src % 2) * 4)) & 15;
                                case BYTE -> values == null ? layer.getDefaultValue() : values[src] & 255;
                                default -> throw new AssertionError();
                            };
                            buffer.put(start + x - minX + (y - minY) * width, (byte) value);
                        }
                    }
                }
            }
        }
        buffer.position(0);
        if (!NativeSlices.resampleTile(buffer)) return false;
        target.applyScaledData(buffer, layers, scratch.active, scratch.water, scratch.terrain, scratch.missing);
        return true;
    }

    private static int floor(float coordinate, boolean integer) {
        return (int) Math.floor(integer ? coordinate : coordinate - Math.signum(coordinate) / 2);
    }

    /** Plugin samplers can have stateful queries; their border generation stays in Java order. */
    private static boolean isBuiltInMap(HeightMap map, IdentityHashMap<HeightMap, Boolean> seen) {
        if (seen.put(map, Boolean.TRUE) != null) return false;
        final Class<?> type = map.getClass();
        if (!type.getPackageName().equals("org.pepsoft.worldpainter.heightMaps")
                || type.getClassLoader() != HeightMap.class.getClassLoader()) return false;
        if (map instanceof DelegatingHeightMap delegated) {
            for (int i = 0; i < delegated.getHeightMapCount(); i++) {
                if (!isBuiltInMap(delegated.getHeightMap(i), seen)) return false;
            }
        }
        seen.remove(map);
        return true;
    }

    private static final class Scratch {
        ByteBuffer buffer;
        final float[] x = new float[128], y = new float[128];
        final int[] nx = new int[128], ny = new int[128], water = new int[16384], missing = new int[64];
        final byte[] active = new byte[16384], terrain = new byte[16384];
    }
}
