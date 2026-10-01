/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter;

import org.pepsoft.util.MathUtils;
import org.pepsoft.util.undo.BufferKey;
import org.pepsoft.util.undo.UndoListener;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.gardenofeden.Seed;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Layer.DataSize;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;

import java.awt.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.stream.Collectors.toSet;
import static org.pepsoft.util.CollectionUtils.unsignedMax;
import static org.pepsoft.util.ObjectUtils.copyObject;
import static org.pepsoft.worldpainter.Constants.*;
import static org.pepsoft.worldpainter.Tile.TileBuffer.*;
import static org.pepsoft.worldpainter.layers.Layer.DataSize.BYTE;
import static org.pepsoft.worldpainter.layers.Layer.DataSize.NIBBLE;

/**
 *
 * @author pepijn
 */
public class Tile extends InstanceKeeper implements Serializable, UndoListener, Cloneable {
    public Tile(int x, int y, int minHeight, int maxHeight) {
        this(x, y, minHeight, maxHeight, true);
    }

    protected Tile(int x, int y, int minHeight, int maxHeight, boolean init) {
        this.x = x;
        this.y = y;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        if ((maxHeight - minHeight) > 256) {
            tall = true;
        }
        if (init) {
            if (tall) {
                tallHeightMap = DEFAULT_TALL_HEIGHTMAP_BUFFER;
                tallWaterLevel = DEFAULT_TALL_WATERLEVEL_BUFFER;
            } else {
                heightMap = DEFAULT_HEIGHTMAP_BUFFER;
                waterLevel = DEFAULT_WATERLEVEL_BUFFER;
            }
            terrain = DEFAULT_TERRAIN_BUFFER;
            layerData = DEFAULT_LAYER_DATA_BUFFER;
            bitLayerData = DEFAULT_BIT_LAYER_DATA_BUFFER;
            init();
        }
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public synchronized int getMinHeight() {
        return minHeight;
    }

    public synchronized int getMaxHeight() {
        return maxHeight;
    }
    
    public void setMinMaxHeight(int minHeight, int maxHeight, HeightTransform heightTransform) {
        inhibitEvents();
        try {
            synchronized (this) {
                if (resizeVerticalNative(minHeight, maxHeight, heightTransform)) return;
                if ((maxHeight != this.maxHeight) || (minHeight != this.minHeight)) {
                    final int oldMinHeight = this.minHeight, minHeightDelta = oldMinHeight - minHeight;
                    this.minHeight = minHeight;
                    this.maxHeight = maxHeight;
                    maxY = maxHeight - 1;
                    boolean newTall = (maxHeight - minHeight) > 256;
                    if (newTall == tall) {
                        // Tallness is not changing
                        if (! heightTransform.isIdentity()) {
                            for (int x = 0; x < TILE_SIZE; x++) {
                                for (int y = 0; y < TILE_SIZE; y++) {
                                    setHeight(x, y, clamp(heightTransform.transformHeight(getHeight(x, y) + minHeightDelta)));
                                    setWaterLevel(x, y, clamp(heightTransform.transformHeight(getWaterLevel(x, y) + minHeightDelta)));
                                }
                            }
                        } else {
                            // TODO why would this ever be necessary?
                            for (int x = 0; x < TILE_SIZE; x++) {
                                for (int y = 0; y < TILE_SIZE; y++) {
                                    setHeight(x, y, clamp(getHeight(x, y) + minHeightDelta));
                                    setWaterLevel(x, y, clamp(getWaterLevel(x, y) + minHeightDelta));
                                }
                            }
                        }
                    } else if (tall) {
                        // Going from tall to not tall
                        heightMap = new short[TILE_SIZE * TILE_SIZE];
                        waterLevel = new byte[TILE_SIZE * TILE_SIZE];
                        if (undoManager != null) {
                            undoManager.addBuffer(HEIGHTMAP_BUFFER_KEY, heightMap, this);
                            undoManager.addBuffer(WATERLEVEL_BUFFER_KEY, waterLevel, this);
                            readableBuffers.add(HEIGHTMAP);
                            readableBuffers.add(WATERLEVEL);
                            writeableBuffers.add(HEIGHTMAP);
                            writeableBuffers.add(WATERLEVEL);
                        }
                        tall = false;
                        for (int x = 0; x < TILE_SIZE; x++) {
                            for (int y = 0; y < TILE_SIZE; y++) {
                                setHeight(x, y, clamp(heightTransform.transformHeight(tallHeightMap[x | (y << TILE_SIZE_BITS)] / 256f + oldMinHeight)));
                                setWaterLevel(x, y, clamp(heightTransform.transformHeight(tallWaterLevel[x | (y << TILE_SIZE_BITS)] + oldMinHeight)));
                            }
                        }
                        if (undoManager != null) {
                            undoManager.removeBuffer(TALL_HEIGHTMAP_BUFFER_KEY);
                            undoManager.removeBuffer(TALL_WATERLEVEL_BUFFER_KEY);
                            readableBuffers.remove(TALL_HEIGHTMAP);
                            readableBuffers.remove(TALL_WATERLEVEL);
                            writeableBuffers.remove(TALL_HEIGHTMAP);
                            writeableBuffers.remove(TALL_WATERLEVEL);
                        }
                        tallHeightMap = null;
                        tallWaterLevel = null;
                    } else {
                        // Going from not tall to tall
                        tallHeightMap = new int[TILE_SIZE * TILE_SIZE];
                        tallWaterLevel = new short[TILE_SIZE * TILE_SIZE];
                        if (undoManager != null) {
                            undoManager.addBuffer(TALL_HEIGHTMAP_BUFFER_KEY, tallHeightMap, this);
                            undoManager.addBuffer(TALL_WATERLEVEL_BUFFER_KEY, tallWaterLevel, this);
                            readableBuffers.add(TALL_HEIGHTMAP);
                            readableBuffers.add(TALL_WATERLEVEL);
                            writeableBuffers.add(TALL_HEIGHTMAP);
                            writeableBuffers.add(TALL_WATERLEVEL);
                        }
                        tall = true;
                        for (int x = 0; x < TILE_SIZE; x++) {
                            for (int y = 0; y < TILE_SIZE; y++) {
                                setHeight(x, y, clamp(heightTransform.transformHeight((heightMap[x | (y << TILE_SIZE_BITS)] & 0xFFFF) / 256f + oldMinHeight)));
                                setWaterLevel(x, y, clamp(heightTransform.transformHeight((waterLevel[x | (y << TILE_SIZE_BITS)] & 0xFF) + oldMinHeight)));
                            }
                        }
                        if (undoManager != null) {
                            undoManager.removeBuffer(HEIGHTMAP_BUFFER_KEY);
                            undoManager.removeBuffer(WATERLEVEL_BUFFER_KEY);
                            readableBuffers.remove(HEIGHTMAP);
                            readableBuffers.remove(WATERLEVEL);
                            writeableBuffers.remove(HEIGHTMAP);
                            writeableBuffers.remove(WATERLEVEL);
                        }
                        heightMap = null;
                        waterLevel = null;
                    }
                } else if (! heightTransform.isIdentity()) {
                    for (int x = 0; x < TILE_SIZE; x++) {
                        for (int y = 0; y < TILE_SIZE; y++) {
                            setHeight(x, y, clamp(heightTransform.transformHeight(getHeight(x, y))));
                            setWaterLevel(x, y, clamp(heightTransform.transformHeight(getWaterLevel(x, y))));
                        }
                    }
                }
            }
        } finally {
            releaseEvents();
        }
    }

    public int getIntHeight(int x, int y) {
        return Math.round(getHeight(x, y));
    }

    public int getLowestIntHeight() {
        return Math.round(getLowestRawHeight() / 256f + minHeight);
    }

    public int getHighestIntHeight() {
        return Math.round(getHighestRawHeight() / 256f + minHeight);
    }

    public synchronized float getHeight(int x, int y) {
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            return tallHeightMap[x | (y << TILE_SIZE_BITS)] / 256f + minHeight;
        } else {
            ensureReadable(HEIGHTMAP);
            return (heightMap[x | (y << TILE_SIZE_BITS)] & 0xFFFF) / 256f + minHeight;
        }
    }

    /** Copie les hauteurs arrondies dans l'ordre de stockage, sous un seul verrou. */
    public synchronized void copyQuantisedHeights(int[] destination) {
        if (destination.length != TILE_SIZE * TILE_SIZE) throw new IllegalArgumentException("Expected one height per cell");
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        for (int i = 0; i < destination.length; i++) {
            destination[i] = Math.round((tall ? tallHeightMap[i] : heightMap[i] & 0xffff) / 256f + minHeight);
        }
    }

    /** Applique un thème préparé sans contourner les buffers d'annulation ni les événements différés. */
    public synchronized void applyPreparedTheme(int[] terrains, Layer[] layers, byte[][] values) {
        final int area = TILE_SIZE * TILE_SIZE;
        if (getClass() != Tile.class || eventInhibitionCounter == 0) throw new IllegalStateException("Inhibited plain tile required");
        if (terrains.length != area || layers.length != values.length) throw new IllegalArgumentException("Invalid theme planes");
        for (int ordinal : terrains) if (ordinal < 0 || ordinal >= TERRAIN_VALUES.length) throw new IllegalArgumentException("Invalid terrain");
        for (int l = 0; l < layers.length; l++) {
            DataSize size = layers[l].getDataSize();
            if (size != DataSize.BIT && size != DataSize.BIT_PER_CHUNK && size != NIBBLE && size != BYTE
                    || values[l].length != area) throw new IllegalArgumentException("Invalid layer plane");
            for (byte value : values[l]) if ((value & 255) > size.maxValue) throw new IllegalArgumentException("Invalid layer value");
        }
        ensureReadable(TERRAIN);
        boolean changed = false;
        for (int i = 0; i < area; i++) if ((terrain[i] & 255) != terrains[i]) {
            if (!changed) { ensureWriteable(TERRAIN); changed = true; }
            terrain[i] = (byte) terrains[i];
        }
        if (changed) terrainChanged();
        for (int l = 0; l < layers.length; l++) {
            Layer layer = layers[l]; DataSize size = layer.getDataSize(); byte[] plane = values[l];
            boolean bit = size == DataSize.BIT || size == DataSize.BIT_PER_CHUNK;
            ensureReadable(bit ? BIT_LAYER_DATA : LAYER_DATA);
            BitSet bits = bit ? bitLayerData.get(layer) : null;
            byte[] data = bit ? null : layerData.get(layer);
            changed = false;
            // L'ordre X/Y conserve les mutations successives des couches par chunk.
            for (int x = 0; x < TILE_SIZE; x++) for (int y = 0; y < TILE_SIZE; y++) {
                int index = x | y << TILE_SIZE_BITS;
                int target = plane[index] & 255;
                int offset = size == DataSize.BIT_PER_CHUNK ? x / 16 + (y / 16) * 8 : index;
                int current = bit ? bits != null && bits.get(offset) ? 1 : 0
                        : data == null ? layer.getDefaultValue()
                        : size == NIBBLE ? (data[index / 2] >>> ((index & 1) * 4)) & 15 : data[index] & 255;
                if (current == target) continue;
                if (!changed) {
                    ensureWriteable(bit ? BIT_LAYER_DATA : LAYER_DATA);
                    if (bit) {
                        bits = bitLayerData.get(layer);
                        if (bits == null) { bits = new BitSet(size == DataSize.BIT ? area : 64); bitLayerData.put(layer, bits); cachedLayers = null; }
                    } else {
                        data = layerData.get(layer);
                        if (data == null) {
                            data = new byte[size == NIBBLE ? area / 2 : area];
                            int d = layer.getDefaultValue();
                            if (d != 0) Arrays.fill(data, (byte) (size == NIBBLE ? d | d << 4 : d));
                            layerData.put(layer, data); cachedLayers = null;
                        }
                        data = detachSharedLayerDataBuffer(layer, data);
                    }
                    changed = true;
                }
                if (bit) bits.set(offset, target != 0);
                else if (size == NIBBLE) {
                    int shift = (index & 1) * 4;
                    data[index / 2] = (byte) ((data[index / 2] & ~(15 << shift)) | target << shift);
                } else data[index] = (byte) target;
            }
            if (changed) layerDataChanged(layer);
        }
    }

    /** Copy a rectangle into a caller-owned, X-major region buffer under one lock. */
    synchronized void copyHeightRegion(int x, int y, int width, int height,
                                     float[] destination, int offset, int columnStride) {
        checkHeightRegion(x, y, width, height, destination.length, offset, columnStride);
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                final int index = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                destination[offset + dx * columnStride + dy] = tall
                        ? tallHeightMap[index] / 256f + minHeight
                        : (heightMap[index] & 0xffff) / 256f + minHeight;
            }
        }
    }

    private boolean resizeVerticalNative(int newMin, int newMax, HeightTransform transform) {
        if (getClass() != Tile.class || !transform.isBuiltIn() || newMin >= newMax
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        if (newMin == minHeight && newMax == maxHeight && transform.isIdentity()) return true;
        boolean newTall = (newMax - newMin) > 256;
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        ensureReadable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        ByteBuffer buffer = VerticalResizeAccess.prepare(minHeight, newMin, newMax, tall, newTall, transform);
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            buffer.putInt(48 + i * 8, tall ? tallHeightMap[i] : heightMap[i] & 0xffff);
            buffer.putInt(52 + i * 8, tall
                    ? (newTall ? tallWaterLevel[i] & 0xffff : tallWaterLevel[i]) : waterLevel[i] & 0xff);
        }
        if (!VerticalResizeAccess.resize(buffer)) return false;
        if (newTall == tall) {
            ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
            ensureWriteable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        } else {
            TileBuffer oldHeight = tall ? TALL_HEIGHTMAP : HEIGHTMAP;
            TileBuffer oldWater = tall ? TALL_WATERLEVEL : WATERLEVEL;
            BufferKey<?> oldHeightKey = tall ? TALL_HEIGHTMAP_BUFFER_KEY : HEIGHTMAP_BUFFER_KEY;
            BufferKey<?> oldWaterKey = tall ? TALL_WATERLEVEL_BUFFER_KEY : WATERLEVEL_BUFFER_KEY;
            if (newTall) {
                tallHeightMap = new int[TILE_SIZE * TILE_SIZE];
                tallWaterLevel = new short[TILE_SIZE * TILE_SIZE];
                if (undoManager != null) {
                    undoManager.addBuffer(TALL_HEIGHTMAP_BUFFER_KEY, tallHeightMap, this);
                    undoManager.addBuffer(TALL_WATERLEVEL_BUFFER_KEY, tallWaterLevel, this);
                }
            } else {
                heightMap = new short[TILE_SIZE * TILE_SIZE];
                waterLevel = new byte[TILE_SIZE * TILE_SIZE];
                if (undoManager != null) {
                    undoManager.addBuffer(HEIGHTMAP_BUFFER_KEY, heightMap, this);
                    undoManager.addBuffer(WATERLEVEL_BUFFER_KEY, waterLevel, this);
                }
            }
            if (undoManager != null) {
                undoManager.removeBuffer(oldHeightKey);
                undoManager.removeBuffer(oldWaterKey);
                readableBuffers.remove(oldHeight); readableBuffers.remove(oldWater);
                writeableBuffers.remove(oldHeight); writeableBuffers.remove(oldWater);
                readableBuffers.add(newTall ? TALL_HEIGHTMAP : HEIGHTMAP);
                readableBuffers.add(newTall ? TALL_WATERLEVEL : WATERLEVEL);
                writeableBuffers.add(newTall ? TALL_HEIGHTMAP : HEIGHTMAP);
                writeableBuffers.add(newTall ? TALL_WATERLEVEL : WATERLEVEL);
            }
            if (newTall) { heightMap = null; waterLevel = null; }
            else { tallHeightMap = null; tallWaterLevel = null; }
            tall = newTall;
        }
        minHeight = newMin; maxHeight = newMax; maxY = newMax - 1;
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            if (tall) {
                tallHeightMap[i] = buffer.getInt(48 + i * 8);
                tallWaterLevel[i] = (short) buffer.getInt(52 + i * 8);
            } else {
                heightMap[i] = (short) buffer.getInt(48 + i * 8);
                waterLevel[i] = (byte) buffer.getInt(52 + i * 8);
            }
        }
        heightMapChanged(); waterLevelChanged();
        return true;
    }

    synchronized boolean bakeAutoBiomes(int constantBiome, int defaultBiome) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        ensureReadable(LAYER_DATA); ensureReadable(BIT_LAYER_DATA); ensureReadable(TERRAIN);
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        ensureReadable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        AutoBiomeAccess.Scratch scratch = AutoBiomeAccess.prepare(constantBiome, defaultBiome);
        ByteBuffer buffer = scratch.buffer;
        byte[] biomes = layerData.get(Biome.INSTANCE);
        byte[] deciduous = layerData.get(org.pepsoft.worldpainter.layers.DeciduousForest.INSTANCE);
        byte[] pine = layerData.get(org.pepsoft.worldpainter.layers.PineForest.INSTANCE);
        byte[] swamp = layerData.get(org.pepsoft.worldpainter.layers.SwampLand.INSTANCE);
        byte[] jungle = layerData.get(org.pepsoft.worldpainter.layers.Jungle.INSTANCE);
        BitSet frost = bitLayerData.get(org.pepsoft.worldpainter.layers.Frost.INSTANCE);
        BitSet river = bitLayerData.get(org.pepsoft.worldpainter.layers.River.INSTANCE);
        BitSet lava = bitLayerData.get(FloodWithLava.INSTANCE);
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            int ordinal = terrain[i] & 0xff;
            if (ordinal >= scratch.biomes.length || scratch.biomes[ordinal] < 0 || scratch.biomes[ordinal] > 255) return false;
            int raw = tall ? tallHeightMap[i] : heightMap[i] & 0xffff;
            int water = (tall ? tallWaterLevel[i] & 0xffff : waterLevel[i] & 0xff) + minHeight;
            int flags = (frost != null && frost.get(i) ? 1 : 0) | (river != null && river.get(i) ? 2 : 0)
                    | (nibblePresent(swamp, i) ? 4 : 0) | (nibblePresent(jungle, i) ? 8 : 0)
                    | (nibblePresent(deciduous, i) || nibblePresent(pine, i) ? 16 : 0)
                    | (lava != null && lava.get(i) ? 32 : 0) | (ordinal == Terrain.WATER.ordinal() ? 64 : 0)
                    | (scratch.forest[ordinal] ? 128 : 0);
            int offset = 64 + i * 8;
            buffer.putInt(offset, water - Math.round(raw / 256f + minHeight));
            buffer.put(offset + 4, (byte) flags).put(offset + 5, biomes == null ? (byte) 255 : biomes[i]);
            buffer.put(offset + 6, (byte) scratch.biomes[ordinal]).put(offset + 7, (byte) 0);
        }
        if (!AutoBiomeAccess.bake(scratch)) return false;
        if (buffer.getInt(12) == 0) return true;
        if (biomes == null) {
            boolean nonDefault = false;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                if (buffer.get(64 + i * 8 + 5) != (byte) 255) { nonDefault = true; break; }
            }
            if (!nonDefault) return true;
        }
        ensureWriteable(LAYER_DATA);
        biomes = layerData.get(Biome.INSTANCE);
        if (biomes == null) {
            biomes = new byte[TILE_SIZE * TILE_SIZE];
            layerData.put(Biome.INSTANCE, biomes); cachedLayers = null;
        } else { biomes = detachSharedLayerDataBuffer(Biome.INSTANCE, biomes); }
        for (int i = 0; i < biomes.length; i++) biomes[i] = buffer.get(64 + i * 8 + 5);
        layerDataChanged(Biome.INSTANCE);
        return true;
    }

    private static boolean nibblePresent(byte[] data, int i) {
        return data != null && ((data[i >>> 1] >>> ((i & 1) * 4)) & 15) > 0;
    }

    /** Apply independent height edits, preserving COW and one deferred notification. */
    void applyHeightRegion(int x, int y, int width, int height,
                           float[] source, byte[] modified, int offset, int columnStride) {
        checkHeightRegion(x, y, width, height, source.length, offset, columnStride);
        checkHeightRegion(x, y, width, height, modified.length, offset, columnStride);
        boolean changed = false;
        synchronized (this) {
            if (eventInhibitionCounter == 0) {
                throw new IllegalStateException("Bulk height editing requires inhibited events");
            }
            for (int dx = 0; dx < width; dx++) {
                for (int dy = 0; dy < height; dy++) {
                    final int input = offset + dx * columnStride + dy;
                    if (modified[input] != 0) {
                        if (!changed) {
                            ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
                            changed = true;
                        }
                        final int index = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                        if (tall) {
                            tallHeightMap[index] = (int) ((source[input] - minHeight) * 256);
                        } else {
                            heightMap[index] = (short) ((source[input] - minHeight) * 256);
                        }
                    }
                }
            }
        }
        if (changed) {
            heightMapChanged();
        }
    }

    synchronized void editHeightBrushRegion(int x, int y, int width, int height, float[] strengths, int offset, int stride,
                                            int mode, float value, float minClamp, float maxClamp) {
        checkHeightRegion(x, y, width, height, strengths.length, offset, stride);
        if (eventInhibitionCounter == 0) throw new IllegalStateException("Bulk height brushing requires inhibited events");
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        HeightBrushAccess.Scratch result = HeightBrushAccess.edit(minHeight, x, y, width, height, strengths, offset, stride,
                mode, value, minClamp, maxClamp, tall ? null : heightMap, tall ? tallHeightMap : null);
        if (result != null) {
            if (result.buffer.getInt(32) != 0) {
                ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
                if (tall) result.copy(tallHeightMap); else result.copy(heightMap);
                heightMapChanged();
            }
            return;
        }
        // Le repli utilise les mêmes paramètres et les setters habituels, sans reprendre le geste.
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            float strength = strengths[offset + dx * stride + dy];
            if (!(strength > 0f)) continue;
            float current = getHeight(x + dx, y + dy);
            float target = mode == 0 ? Math.min(current + value, maxClamp) : mode == 1 ? Math.max(current - value, minClamp) : value;
            float edited = strength * target + (1f - strength) * current;
            if (mode == 2 || ((mode == 0 || mode == 3) ? edited > current : edited < current)) setHeight(x + dx, y + dy, edited);
        }
    }

    synchronized void editMountainRegion(int x, int y, int width, int height, float[] forces, int offset, int stride,
                                          float peak, float factor, boolean inverse, int min, int range) {
        checkHeightRegion(x, y, width, height, forces.length, offset, stride);
        if (eventInhibitionCounter == 0) throw new IllegalStateException("Mountain editing requires inhibited events");
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        MountainAccess.Scratch result = minHeight != min ? null : MountainAccess.edit(this.x, this.y, min, range, x, y, width, height,
                forces, offset, stride, peak, factor, inverse, tall ? null : heightMap, tall ? tallHeightMap : null);
        if (result != null) {
            if (result.buffer.getInt(32) != 0) {
                ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
                if (tall) result.copy(tallHeightMap); else result.copy(heightMap);
                heightMapChanged();
            }
            return;
        }
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            float current = getHeight(x + dx, y + dy);
            float target = MountainAccess.target((this.x << TILE_SIZE_BITS) + x + dx, (this.y << TILE_SIZE_BITS) + y + dy,
                    forces[offset + dx * stride + dy], min, range, peak, factor, inverse);
            if (inverse ? target < current : target > current) setHeight(x + dx, y + dy, target);
        }
    }

    synchronized void copyRiverRegion(int x, int y, int width, int height, ByteBuffer data, int offset, int stride, int area) {
        checkHeightRegion(x, y, width, height, area, offset, stride);
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP); ensureReadable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            int cell = (x + dx) | ((y + dy) << TILE_SIZE_BITS), i = offset + dx * stride + dy;
            float heightValue = (tall ? tallHeightMap[cell] : heightMap[cell] & 0xffff) / 256f + minHeight;
            data.putFloat(64 + i * 4, heightValue).putInt(64 + area * 4 + i * 4, Math.round(heightValue))
                    .putInt(64 + area * 8 + i * 4, (tall ? tallWaterLevel[cell] & 0xffff : waterLevel[cell] & 255) + minHeight);
        }
    }

    synchronized void applyRiverRegion(int x, int y, int width, int height, ByteBuffer data, int offset, int stride,
                                       int area, int level, boolean lava) {
        checkHeightRegion(x, y, width, height, area, offset, stride);
        if (eventInhibitionCounter == 0) throw new IllegalStateException("River editing requires inhibited events");
        boolean heights = false, waters = false, terrains = false, lavaChanged = false;
        ensureReadable(BIT_LAYER_DATA);
        BitSet bits = bitLayerData.get(FloodWithLava.INSTANCE);
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            int input = offset + dx * stride + dy, flags = data.get(64 + area * 20 + input);
            int cell = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
            if ((flags & 1) != 0) {
                if (!heights) { ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP); heights = true; }
                int raw = (int) ((data.getFloat(64 + input * 4) - minHeight) * 256);
                if (tall) tallHeightMap[cell] = raw; else heightMap[cell] = (short) raw;
            }
            if ((flags & 2) != 0) {
                if (!waters) { ensureWriteable(tall ? TALL_WATERLEVEL : WATERLEVEL); waters = true; }
                if (tall) tallWaterLevel[cell] = (short) (level - minHeight); else waterLevel[cell] = (byte) (level - minHeight);
                if (lava || bits != null) {
                    if (!lavaChanged) {
                        ensureWriteable(BIT_LAYER_DATA); bits = bitLayerData.get(FloodWithLava.INSTANCE);
                        if (bits == null) { bits = new BitSet(16384); bitLayerData.put(FloodWithLava.INSTANCE, bits); cachedLayers = null; }
                        lavaChanged = true;
                    }
                    bits.set(cell, lava);
                }
            }
            if ((flags & 4) != 0) {
                if (!terrains) { ensureWriteable(TERRAIN); terrains = true; }
                terrain[cell] = (byte) Terrain.BEACHES.ordinal();
            }
        }
        if (heights) heightMapChanged(); if (waters) waterLevelChanged(); if (terrains) terrainChanged();
        if (lavaChanged) layerDataChanged(FloodWithLava.INSTANCE);
    }

    synchronized void copyErosionRegion(int x, int y, int width, int height, ByteBuffer buffer, int offset, int stride, int types) {
        checkHeightRegion(x, y, width, height, (types - 32) / 4, offset, stride);
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            int cell = (x + dx) | ((y + dy) << TILE_SIZE_BITS), i = offset + dx * stride + dy;
            buffer.putInt(32 + i * 4, tall ? tallHeightMap[cell] : heightMap[cell] & 0xffff);
            buffer.put(types + i, (byte) (tall ? 2 : 1));
        }
    }

    void applyErosionRegion(int x, int y, int width, int height, ByteBuffer buffer, int offset, int stride, int mask) {
        int area = (mask - 32) / 5;
        applyRawHeightRegion(x, y, width, height, buffer, offset, stride, 32, mask, area);
    }

    // Masque : bit 0 pour la hauteur, bit 1 pour le terrain. Une seule transaction COW par tuile.
    void applyShapingRegion(int x, int y, int width, int height, ByteBuffer data, int offset, int stride,
                            int mask, int area, Terrain material) {
        checkHeightRegion(x, y, width, height, area, offset, stride);
        boolean heightsChanged = false, terrainsChanged = false;
        synchronized (this) {
            if (eventInhibitionCounter == 0) throw new IllegalStateException("Shaping requires inhibited events");
            for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
                int input = offset + dx * stride + dy, flags = data.get(mask + input);
                int cell = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                if ((flags & 1) != 0) {
                    if (!heightsChanged) { ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP); heightsChanged = true; }
                    int raw = (int) ((data.getFloat(32 + input * 4) - minHeight) * 256);
                    if (tall) tallHeightMap[cell] = raw; else heightMap[cell] = (short) raw;
                }
                if ((flags & 2) != 0) {
                    if (!terrainsChanged) { ensureWriteable(TERRAIN); terrainsChanged = true; }
                    terrain[cell] = (byte) material.ordinal();
                }
            }
        }
        if (heightsChanged) heightMapChanged();
        if (terrainsChanged) terrainChanged();
    }

    synchronized void copyHeightRegionDirect(int x, int y, int width, int height, java.nio.FloatBuffer output, int offset, int stride) {
        checkHeightRegion(x, y, width, height, output.limit(), offset, stride);
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            int cell = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
            output.put(offset + dx * stride + dy, (tall ? tallHeightMap[cell] : heightMap[cell] & 0xffff) / 256f + minHeight);
        }
    }

    // Application commune aux traitements avec voisins ; aucune lecture d'objet par cellule après le JNI.
    void applyRawHeightRegion(int x, int y, int width, int height, ByteBuffer buffer, int offset, int stride,
                              int dataOffset, int mask, int area) {
        checkHeightRegion(x, y, width, height, area, offset, stride);
        if (dataOffset < 0 || dataOffset + (long) area * 4 > buffer.limit() || mask < 0 || mask + (long) area > buffer.limit())
            throw new IndexOutOfBoundsException("Invalid raw height region");
        boolean changed = false;
        synchronized (this) {
            if (eventInhibitionCounter == 0) throw new IllegalStateException("Bulk erosion requires inhibited events");
            for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
                int i = offset + dx * stride + dy;
                if (buffer.get(mask + i) == 0) continue;
                if (!changed) { ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP); changed = true; }
                int cell = (x + dx) | ((y + dy) << TILE_SIZE_BITS), value = buffer.getInt(dataOffset + i * 4);
                if (tall) tallHeightMap[cell] = value; else heightMap[cell] = (short) value;
            }
        }
        if (changed) heightMapChanged();
    }

    private static void checkHeightRegion(int x, int y, int width, int height,
                                          int length, int offset, int columnStride) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) x + width > TILE_SIZE || (long) y + height > TILE_SIZE
                || offset < 0 || columnStride < height
                || (long) offset + (long) (width - 1) * columnStride + height > length) {
            throw new IndexOutOfBoundsException("Invalid tile height region");
        }
    }

    /** Copies the height and water snapshot for callers that do not need terrain ordinals. */
    void copyRenderHeightDataTo(float[] heights, int[] intHeights, int[] waterLevels) {
        copyRenderDataTo(heights, intHeights, waterLevels, null);
    }

    /**
     * Copies the data needed by the tile renderer under one read lock. This
     * avoids repeated tile monitor acquisition and copy-on-write buffer lookup
     * for each rendered pixel while reusing the renderer's scratch arrays.
     * A null terrain array requests only heights and water levels.
     */
    synchronized void copyRenderDataTo(float[] heights, int[] intHeights,
                                       int[] waterLevels, byte[] terrainOrdinals) {
        final int area = TILE_SIZE * TILE_SIZE;
        if ((heights == null) || (heights.length != area)
                || (intHeights == null) || (intHeights.length != area)
                || (waterLevels == null) || (waterLevels.length != area)
                || ((terrainOrdinals != null) && (terrainOrdinals.length != area))) {
            throw new IllegalArgumentException("Expected render data for every tile cell");
        }
        if (terrainOrdinals != null) {
            ensureReadable(TERRAIN);
        }
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            ensureReadable(TALL_WATERLEVEL);
            for (int index = 0; index < area; index++) {
                final float height = tallHeightMap[index] / 256f + minHeight;
                heights[index] = height;
                intHeights[index] = Math.round(height);
                waterLevels[index] = (tallWaterLevel[index] & 0xFFFF) + minHeight;
            }
        } else {
            ensureReadable(HEIGHTMAP);
            ensureReadable(WATERLEVEL);
            for (int index = 0; index < area; index++) {
                final float height = (heightMap[index] & 0xFFFF) / 256f + minHeight;
                heights[index] = height;
                intHeights[index] = Math.round(height);
                waterLevels[index] = (waterLevel[index] & 0xFF) + minHeight;
            }
        }
        if (terrainOrdinals != null) {
            System.arraycopy(terrain, 0, terrainOrdinals, 0, area);
        }
    }

    public float getLowestHeight() {
        return getLowestRawHeight() / 256f + minHeight;
    }

    public float getHighestHeight() {
        return getHighestRawHeight() / 256f + minHeight;
    }

    public void setHeight(int x, int y, float height) {
        synchronized (this) {
            if (tall) {
                ensureWriteable(TALL_HEIGHTMAP);
                tallHeightMap[x | (y << TILE_SIZE_BITS)] = (int) ((height - minHeight) * 256);
            } else {
                ensureWriteable(HEIGHTMAP);
                heightMap[x | (y << TILE_SIZE_BITS)] = (short) ((height - minHeight) * 256);
            }
        }
        heightMapChanged();
    }

    /** Only freshly generated, ordinary tiles with no observers use grouped scaling writes. */
    synchronized boolean canApplyScaledData() {
        return getClass() == Tile.class && undoManager == null && listeners.isEmpty() && eventInhibitionCounter == 0;
    }

    /** Applies complete resampling output to normal COW buffers; no backing array escapes. */
    synchronized void applyScaledData(ByteBuffer buffer, List<Layer> layers, byte[] active,
                                      int[] water, byte[] terrains, int[] missing) {
        if (!canApplyScaledData()) throw new IllegalStateException("Scaling target is no longer fresh");
        final int heightOutput = buffer.getInt(ScalingTileAccess.TABLE + 20);
        ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        ensureWriteable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        ensureWriteable(TERRAIN);
        for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
            if (active[i] == 0) continue;
            final int raw = (int) ((buffer.getFloat(heightOutput + i * 4) - minHeight) * 256);
            if (tall) { tallHeightMap[i] = raw; tallWaterLevel[i] = (short) (water[i] - minHeight); }
            else { heightMap[i] = (short) raw; waterLevel[i] = (byte) (water[i] - minHeight); }
            terrain[i] = terrains[i];
        }
        for (int p = 0; p < layers.size(); p++) {
            final Layer layer = layers.get(p);
            final int start = buffer.getInt(ScalingTileAccess.TABLE + (p + 1) * 32 + 20);
            final boolean bit = layer.dataSize == DataSize.BIT || layer.dataSize == DataSize.BIT_PER_CHUNK;
            byte[] values = null; BitSet bits = null; boolean touched = false;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                if (active[i] == 0) continue;
                final int value = buffer.get(start + i) & 255;
                if (bit && layer.discrete ? value == 0 : value == layer.getDefaultValue()) continue;
                if (!touched) {
                    ensureWriteable(bit ? BIT_LAYER_DATA : LAYER_DATA);
                    if (bit) {
                        bits = bitLayerData.get(layer);
                        if (bits == null) { bits = new BitSet(layer.dataSize == DataSize.BIT ? 16384 : 64); bitLayerData.put(layer, bits); }
                    } else {
                        values = layerData.get(layer);
                        if (values == null) {
                            values = new byte[layer.dataSize == DataSize.NIBBLE ? 8192 : 16384];
                            Arrays.fill(values, (byte) (layer.dataSize == DataSize.NIBBLE
                                    ? layer.getDefaultValue() | layer.getDefaultValue() << 4 : layer.getDefaultValue()));
                            layerData.put(layer, values);
                        }
                        values = detachSharedLayerDataBuffer(layer, values);
                    }
                    touched = true;
                }
                if (bit) bits.set(layer.dataSize == DataSize.BIT ? i : (i % 128) / 16 + (i / 128) / 16 * 8);
                else if (layer.dataSize == DataSize.BYTE) values[i] = (byte) value;
                else {
                    final int shift = (i % 2) * 4;
                    values[i / 2] = (byte) ((values[i / 2] & ~(15 << shift)) | value << shift);
                }
            }
            if (touched) cachedLayers = null;
        }
        markScaledMissingChunks(missing);
    }

    void markScaledMissingChunks(int[] missing) {
        for (int i = 0; i < 64; i++) if (missing[i] == 256)
            setBitLayerValue(org.pepsoft.worldpainter.layers.NotPresent.INSTANCE, (i >> 3) << 4, (i & 7) << 4, true);
    }

    /**
     * Initialise all heights and water levels in one write transaction while
     * a newly generated tile has its events inhibited. This preserves the
     * normal copy-on-write buffers and coalesces the same deferred change
     * notifications that individual setters would produce.
     */
    public int[] initializeHeightAndWaterLevels(float[] heights, int waterLevel) {
        return initializeHeightAndWaterLevels(heights, waterLevel,
                new int[TILE_SIZE * TILE_SIZE]);
    }

    /** Batch height initialisation with caller-owned scratch for the quantised heights. */
    public int[] initializeHeightAndWaterLevels(float[] heights, int waterLevel, int[] intHeights) {
        if (eventInhibitionCounter == 0) {
            throw new IllegalStateException("Bulk tile initialisation requires inhibited events");
        }
        if ((heights == null) || (heights.length != TILE_SIZE * TILE_SIZE)) {
            throw new IllegalArgumentException("Expected one height for every tile cell");
        }
        if ((intHeights == null) || (intHeights.length != heights.length)) {
            throw new IllegalArgumentException("Expected one integer height for every tile cell");
        }
        synchronized (this) {
            if (tall) {
                final int firstRawHeight = (int) ((heights[0] - minHeight) * 256);
                boolean uniformHeight = true;
                for (int i = 1; i < heights.length; i++) {
                    if ((int) ((heights[i] - minHeight) * 256) != firstRawHeight) {
                        uniformHeight = false;
                        break;
                    }
                }
                if (uniformHeight) {
                    setSharedTallHeightMapBuffer(uniformTallHeightMapBuffer(firstRawHeight));
                } else {
                    ensureWriteable(TALL_HEIGHTMAP);
                }
                final short rawWaterLevel = (short) (waterLevel - minHeight);
                if (rawWaterLevel == 0) {
                    setSharedTallWaterLevelBuffer(DEFAULT_TALL_WATERLEVEL_BUFFER);
                } else {
                    ensureWriteable(TALL_WATERLEVEL);
                }
                if (uniformHeight) {
                    Arrays.fill(intHeights, Math.round(firstRawHeight / 256f + minHeight));
                    if (rawWaterLevel != 0) {
                        Arrays.fill(tallWaterLevel, rawWaterLevel);
                    }
                } else {
                    for (int i = 0; i < heights.length; i++) {
                        final int rawHeight = (int) ((heights[i] - minHeight) * 256);
                        tallHeightMap[i] = rawHeight;
                        if (rawWaterLevel != 0) {
                            tallWaterLevel[i] = rawWaterLevel;
                        }
                        intHeights[i] = Math.round(rawHeight / 256f + minHeight);
                    }
                }
            } else {
                final short firstRawHeight = (short) ((heights[0] - minHeight) * 256);
                boolean uniformHeight = true;
                for (int i = 1; i < heights.length; i++) {
                    if ((short) ((heights[i] - minHeight) * 256) != firstRawHeight) {
                        uniformHeight = false;
                        break;
                    }
                }
                if (uniformHeight) {
                    setSharedHeightMapBuffer(uniformHeightMapBuffer(firstRawHeight));
                } else {
                    ensureWriteable(HEIGHTMAP);
                }
                final byte rawWaterLevel = (byte) (waterLevel - minHeight);
                setSharedWaterLevelBuffer(uniformWaterLevelBuffer(rawWaterLevel));
                if (uniformHeight) {
                    Arrays.fill(intHeights, Math.round((firstRawHeight & 0xFFFF) / 256f + minHeight));
                } else {
                    for (int i = 0; i < heights.length; i++) {
                        final short rawHeight = (short) ((heights[i] - minHeight) * 256);
                        heightMap[i] = rawHeight;
                        intHeights[i] = Math.round((rawHeight & 0xFFFF) / 256f + minHeight);
                    }
                }
            }
        }
        heightMapChanged();
        waterLevelChanged();
        return intHeights;
    }

    /** Batch terrain writes for a fresh, event-inhibited tile. */
    void initializeTerrainOrdinals(byte[] terrainOrdinals) {
        if (eventInhibitionCounter == 0) {
            throw new IllegalStateException("Bulk terrain initialisation requires inhibited events");
        }
        if ((terrainOrdinals == null) || (terrainOrdinals.length != TILE_SIZE * TILE_SIZE)) {
            throw new IllegalArgumentException("Expected one terrain ordinal for every tile cell");
        }
        boolean hasNonDefaultTerrain = false;
        for (byte ordinal : terrainOrdinals) {
            hasNonDefaultTerrain |= ordinal != 0;
        }
        if (!hasNonDefaultTerrain) {
            return;
        }
        synchronized (this) {
            ensureWriteable(TERRAIN);
            System.arraycopy(terrainOrdinals, 0, terrain, 0, terrainOrdinals.length);
        }
        terrainChanged();
    }

    /**
     * Initializes one layer from caller-owned values on a fresh tile. The tile
     * must have events inhibited; its copy-on-write buffers and deferred layer
     * notification are still handled through the normal Tile mechanisms.
     */
    public void initializeLayerValues(Layer layer, byte[] values) {
        initializeLayerValues(layer, values, 0);
    }

    /** Initializes one layer from a plane within a larger caller-owned scratch buffer. */
    public void initializeLayerValues(Layer layer, byte[] values, int offset) {
        if (layer == null) {
            throw new NullPointerException("layer");
        }
        final int area = TILE_SIZE * TILE_SIZE;
        if ((values == null) || (offset < 0) || (offset > values.length - area)) {
            throw new IllegalArgumentException("Expected one layer value for every tile cell at the given offset");
        }
        final DataSize dataSize = layer.getDataSize();
        if ((dataSize != Layer.DataSize.BIT) && (dataSize != Layer.DataSize.BIT_PER_CHUNK)
                && (dataSize != Layer.DataSize.NIBBLE) && (dataSize != Layer.DataSize.BYTE)) {
            throw new IllegalArgumentException("Unsupported layer data size " + dataSize);
        }
        final int maxValue = dataSize.maxValue;
        final int defaultValue = layer.getDefaultValue();
        final int firstValue = values[offset] & 0xFF;
        boolean uniformValues = true;
        boolean hasNonDefaultValue = false;
        for (int index = 0; index < area; index++) {
            final byte rawValue = values[offset + index];
            final int value = rawValue & 0xFF;
            if (value > maxValue) {
                throw new IllegalArgumentException("Illegal value " + value + " for " + dataSize + " layer " + layer);
            }
            uniformValues &= value == firstValue;
            hasNonDefaultValue |= ((dataSize == Layer.DataSize.BIT) || (dataSize == Layer.DataSize.BIT_PER_CHUNK))
                    ? value != 0 : value != defaultValue;
        }

        synchronized (this) {
            if (eventInhibitionCounter == 0) {
                throw new IllegalStateException("Bulk layer initialisation requires inhibited events");
            }
            if ((dataSize == Layer.DataSize.BIT) || (dataSize == Layer.DataSize.BIT_PER_CHUNK)) {
                ensureReadable(BIT_LAYER_DATA);
                if (bitLayerData.containsKey(layer)) {
                    throw new IllegalStateException("Layer is already initialized on this tile: " + layer);
                }
                if (!hasNonDefaultValue) {
                    return;
                }
                ensureWriteable(BIT_LAYER_DATA);
                final int bitCount = (dataSize == Layer.DataSize.BIT) ? area : area / 256;
                final BitSet bitSet = new BitSet(bitCount);
                if (dataSize == Layer.DataSize.BIT) {
                    for (int index = 0; index < area; index++) {
                        if (values[offset + index] != 0) {
                            bitSet.set(index);
                        }
                    }
                } else {
                    for (int index = 0; index < area; index++) {
                        if (values[offset + index] != 0) {
                            final int x = index & TILE_SIZE_MASK;
                            final int y = index >> TILE_SIZE_BITS;
                            bitSet.set((x / 16) + (y / 16) * (TILE_SIZE / 16));
                        }
                    }
                }
                bitLayerData.put(layer, bitSet);
            } else {
                ensureReadable(LAYER_DATA);
                if (layerData.containsKey(layer)) {
                    throw new IllegalStateException("Layer is already initialized on this tile: " + layer);
                }
                if (!hasNonDefaultValue) {
                    return;
                }
                ensureWriteable(LAYER_DATA);
                final byte[] layerValues;
                if (dataSize == Layer.DataSize.NIBBLE) {
                    if (uniformValues) {
                        final byte packedValue = (byte) (firstValue << 4 | firstValue);
                        layerValues = uniformLayerDataBuffer(area / 2, packedValue, null);
                    } else {
                        layerValues = new byte[area / 2];
                        if (defaultValue != 0) {
                            Arrays.fill(layerValues, (byte) (defaultValue << 4 | defaultValue));
                        }
                        for (int index = 0; index < area; index++) {
                            final int byteOffset = index / 2;
                            final int value = values[offset + index] & 0xFF;
                            if ((index & 1) == 0) {
                                layerValues[byteOffset] = (byte) ((layerValues[byteOffset] & 0xF0) | value);
                            } else {
                                layerValues[byteOffset] = (byte) ((layerValues[byteOffset] & 0x0F) | (value << 4));
                            }
                        }
                    }
                } else {
                    if (uniformValues) {
                        layerValues = uniformLayerDataBuffer(area, (byte) firstValue, null);
                    } else {
                        layerValues = new byte[area];
                        if (defaultValue != 0) {
                            Arrays.fill(layerValues, (byte) defaultValue);
                        }
                        System.arraycopy(values, offset, layerValues, 0, area);
                    }
                }
                layerData.put(layer, layerValues);
            }
            cachedLayers = null;
        }
        layerDataChanged(layer);
    }

    /**
     * Get the raw height value. This is the height times 256 (for added precision) and zero-based rather than adjusted
     * for {@code minHeight}.
     */
    public synchronized int getRawHeight(int x, int y) {
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            return tallHeightMap[x | (y << TILE_SIZE_BITS)];
        } else {
            ensureReadable(HEIGHTMAP);
            return (heightMap[x | (y << TILE_SIZE_BITS)] & 0xFFFF);
        }
    }

    public synchronized int getLowestRawHeight() {
        int lowestRawHeight = Integer.MAX_VALUE;
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            for (int height: tallHeightMap) {
                if (height < lowestRawHeight) {
                    lowestRawHeight = height;
                }
                if (lowestRawHeight <= 0) {
                    return 0;
                }
            }
        } else {
            ensureReadable(HEIGHTMAP);
            for (short height: heightMap) {
                if ((height & 0xFFFF) < lowestRawHeight) {
                    lowestRawHeight = (height & 0xFFFF);
                }
                if (lowestRawHeight <= 0) {
                    return 0;
                }
            }
        }
        return lowestRawHeight;
    }

    public synchronized int getHighestRawHeight() {
        int highestRawHeight = Integer.MIN_VALUE;
        final int maxRawHeight = (maxHeight - 1 - minHeight) * 256;
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            for (int height: tallHeightMap) {
                if (height > highestRawHeight) {
                    highestRawHeight = height;
                    if (highestRawHeight >= maxRawHeight) {
                        return maxRawHeight;
                    }
                }
            }
        } else {
            ensureReadable(HEIGHTMAP);
            for (short height: heightMap) {
                if ((height & 0xFFFF) > highestRawHeight) {
                    highestRawHeight = (height & 0xFFFF);
                    if (highestRawHeight >= maxRawHeight) {
                        return maxRawHeight;
                    }
                }
            }
        }
        return highestRawHeight;
    }

    public synchronized int[] getRawHeightRange() {
        int lowestRawHeight = Integer.MAX_VALUE;
        int highestRawHeight = Integer.MIN_VALUE;
        final int maxRawHeight = (maxHeight - 1 - minHeight) * 256;
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            for (int height: tallHeightMap) {
                if (height < lowestRawHeight) {
                    lowestRawHeight = height;
                }
                if (height > highestRawHeight) {
                    highestRawHeight = height;
                }
                if ((lowestRawHeight <= 0) && (highestRawHeight >= maxRawHeight)) {
                    return new int[] { 0, maxRawHeight };
                }
            }
        } else {
            ensureReadable(HEIGHTMAP);
            for (short height: heightMap) {
                if ((height & 0xFFFF) < lowestRawHeight) {
                    lowestRawHeight = (height & 0xFFFF);
                }
                if ((height & 0xFFFF) > highestRawHeight) {
                    highestRawHeight = (height & 0xFFFF);
                }
                if ((lowestRawHeight <= 0) && (highestRawHeight >= maxRawHeight)) {
                    return new int[] { 0, maxRawHeight };
                }
            }
        }
        return new int[] { lowestRawHeight, highestRawHeight };
    }

    /**
     * Set the raw height value. This is the height times 256 (for added precision) and zero-based rather than adjusted
     * for {@code minHeight}.
     */
    public void setRawHeight(int x, int y, int rawHeight) {
        synchronized (this) {
            if (tall) {
                ensureWriteable(TALL_HEIGHTMAP);
                tallHeightMap[x | (y << TILE_SIZE_BITS)] = rawHeight;
            } else {
                ensureWriteable(HEIGHTMAP);
                heightMap[x | (y << TILE_SIZE_BITS)] = (short) rawHeight;
            }
        }
        heightMapChanged();
    }

    public float getSlope(int x, int y) {
        return doGetSlope(x, y);
    }

    protected final synchronized float doGetSlope(int x, int y) {
        return Math.max(Math.max(Math.abs(getHeight(x + 1, y) - getHeight(x - 1, y)) / 2,
            Math.abs(getHeight(x + 1, y + 1) - getHeight(x - 1, y - 1)) / SQRT_OF_EIGHT),
            Math.max(Math.abs(getHeight(x, y + 1) - getHeight(x, y - 1)) / 2,
            Math.abs(getHeight(x - 1, y + 1) - getHeight(x + 1, y - 1)) / SQRT_OF_EIGHT));
    }

    public synchronized Terrain getTerrain(int x, int y) {
        ensureReadable(TERRAIN);
        return TERRAIN_VALUES[terrain[x | (y << TILE_SIZE_BITS)] & 0xFF];
    }

    public void setTerrain(int x, int y, Terrain terrain) {
        synchronized (this) {
            ensureWriteable(TERRAIN);
            // Sanity checks because of NPE's observed in the wild from this method
            if (this.terrain == null) {
                throw new NullPointerException("setTerrain(" + x + ", " + y + ", " + terrain + "): this.terrain is null for tile @ " + this.x + "," + this.y);
            } else if (terrain == null) {
                throw new NullPointerException("setTerrain(" + x + ", " + y + ", null): terrain parameter is null for tile @ " + this.x + "," + this.y);
            }
            this.terrain[x | (y << TILE_SIZE_BITS)] = (byte) terrain.ordinal();
        }
        terrainChanged();
    }

    public synchronized Set<Terrain> getAllTerrains() {
        ensureReadable(TERRAIN);
        BitSet terrainIndices = new BitSet(256);
        for (byte terrainIndex: terrain) {
            terrainIndices.set(terrainIndex & 0xff);
        }
        return terrainIndices.stream().mapToObj(i -> TERRAIN_VALUES[i]).collect(toSet());
    }

    public synchronized int getWaterLevel(int x, int y) {
        if (tall) {
            ensureReadable(TALL_WATERLEVEL);
            return (tallWaterLevel[x | (y << TILE_SIZE_BITS)] & 0xFFFF) + minHeight;
        } else {
            ensureReadable(WATERLEVEL);
            return (waterLevel[x | (y << TILE_SIZE_BITS)] & 0xFF) + minHeight;
        }
    }

    public synchronized int getHighestWaterLevel() {
        if (tall) {
            ensureReadable(TALL_WATERLEVEL);
            return unsignedMax(tallWaterLevel) + minHeight;
        } else {
            ensureReadable(WATERLEVEL);
            return unsignedMax(waterLevel) + minHeight;
        }
    }

    public void setWaterLevel(int x, int y, int waterLevel) {
        synchronized (this) {
            if (tall) {
                ensureWriteable(TALL_WATERLEVEL);
                this.tallWaterLevel[x | (y << TILE_SIZE_BITS)] = (short) (waterLevel - minHeight);
            } else {
                ensureWriteable(WATERLEVEL);
                this.waterLevel[x | (y << TILE_SIZE_BITS)] = (byte) (waterLevel - minHeight);
            }
        }
        waterLevelChanged();
    }

    public synchronized List<Layer> getLayers() {
        if (cachedLayers == null) {
            ensureReadable(LAYER_DATA);
            ensureReadable(BIT_LAYER_DATA);
            List<Layer> layers = new ArrayList<>();
            layers.addAll(layerData.keySet());
            layers.addAll(bitLayerData.keySet());
            Collections.sort(layers);
            cachedLayers = Collections.unmodifiableList(layers);
        }
        return cachedLayers;
    }

    public synchronized boolean hasLayer(Layer layer) {
        DataSize dataSize = layer.getDataSize();
        if ((dataSize == DataSize.BIT) || (dataSize == DataSize.BIT_PER_CHUNK)) {
            ensureReadable(BIT_LAYER_DATA);
            return bitLayerData.containsKey(layer);
        } else {
            ensureReadable(LAYER_DATA);
            return layerData.containsKey(layer);
        }
    }

    public synchronized List<Layer> getActiveLayers(int x, int y) {
        ensureReadable(BIT_LAYER_DATA);
        ensureReadable(LAYER_DATA);
        List<Layer> activeLayers = new ArrayList<>(bitLayerData.size() + layerData.size());
        for (Map.Entry<Layer, BitSet> entry: bitLayerData.entrySet()) {
            final Layer layer = entry.getKey();
            final DataSize dataSize = layer.getDataSize();
            if (((dataSize == DataSize.BIT) && getBitPerBlockLayerValue(entry.getValue(), x, y))
                || ((dataSize == DataSize.BIT_PER_CHUNK) && getBitPerChunkLayerValue(entry.getValue(), x, y))) {
                activeLayers.add(layer);
            }
        }
        for (Map.Entry<Layer, byte[]> entry: layerData.entrySet()) {
            final Layer layer = entry.getKey();
            final DataSize dataSize = layer.getDataSize();
            final int defaultValue = layer.getDefaultValue();
            if (dataSize == DataSize.NIBBLE) {
                final int byteOffset = x | (y << TILE_SIZE_BITS);
                final byte _byte = entry.getValue()[byteOffset / 2];
                if ((byteOffset % 2 == 0) ? ((_byte & 0x0F) != defaultValue) : (((_byte & 0xF0) >> 4) != defaultValue)) {
                    activeLayers.add(layer);
                }
            } else if ((entry.getValue()[x | (y << TILE_SIZE_BITS)] & 0xFF) != defaultValue) {
                activeLayers.add(layer);
            }
        }
        return activeLayers;
    }

    /**
     * Get a list of all layers in use in the tile, as well as the set of
     * additional layers provided, the total sorted by layer priority.
     *
     * @param additionalLayers The additional layers to include in the list.
     * @return The list of all layers provided or in use on the tile, sorted by
     *     layer priority.
     */
    public List<Layer> getLayers(Set<Layer> additionalLayers) {
        return doGetLayers(additionalLayers);
    }

    protected final synchronized List<Layer> doGetLayers(Set<Layer> additionalLayers) {
        SortedSet<Layer> layers = new TreeSet<>(additionalLayers);
        layers.addAll(getLayers());
        return new ArrayList<>(layers);
    }

    public synchronized boolean getBitLayerValue(Layer layer, int x, int y) {
        if ((layer.getDataSize() != Layer.DataSize.BIT) && (layer.getDataSize() != Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Layer is not bit sized");
        }
        ensureReadable(BIT_LAYER_DATA);
        BitSet bitSet = bitLayerData.get(layer);
        if (bitSet == null) {
            return false;
        } else {
            if (layer.getDataSize() == Layer.DataSize.BIT) {
                return getBitPerBlockLayerValue(bitSet, x, y);
            } else {
                return getBitPerChunkLayerValue(bitSet, x, y);
            }
        }
    }

    /**
     * Copies a rectangular bit layer into caller-owned x-major storage. The
     * destination index is {@code offset + x * height + y}; bit-per-chunk
     * layers are expanded to one value per cell in the rectangle.
     */
    public synchronized void copyBitLayerValues(Layer layer, int x, int y,
                                                int width, int height,
                                                byte[] destination, int offset) {
        if ((layer.getDataSize() != Layer.DataSize.BIT)
                && (layer.getDataSize() != Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Layer is not bit sized");
        }
        final int area = checkedLayerCopyArea(x, y, width, height, destination.length, offset);
        ensureReadable(BIT_LAYER_DATA);
        final BitSet bitSet = bitLayerData.get(layer);
        for (int dx = 0; dx < width; dx++) {
            Arrays.fill(destination, offset + dx * height, offset + (dx + 1) * height, (byte) 0);
        }
        if (bitSet == null) {
            return;
        }
        final boolean bitPerChunk = layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK;
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                if (bitPerChunk
                        ? getBitPerChunkLayerValue(bitSet, x + dx, y + dy)
                        : getBitPerBlockLayerValue(bitSet, x + dx, y + dy)) {
                    destination[offset + dx * height + dy] = 1;
                }
            }
        }
    }

    /**
     * Copies a rectangular numeric layer into caller-owned x-major storage.
     * The destination index is {@code offset + x * height + y}.
     */
    public synchronized void copyLayerValues(Layer layer, int x, int y,
                                             int width, int height,
                                             int[] destination, int offset) {
        if ((layer.getDataSize() == Layer.DataSize.BIT)
                || (layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Can't get bits using this method");
        }
        final int area = checkedLayerCopyArea(x, y, width, height, destination.length, offset);
        ensureReadable(LAYER_DATA);
        final byte[] layerValues = layerData.get(layer);
        if (layerValues == null) {
            Arrays.fill(destination, offset, offset + area, layer.getDefaultValue());
            return;
        }
        switch (layer.getDataSize()) {
            case NIBBLE -> {
                for (int dx = 0; dx < width; dx++) {
                    for (int dy = 0; dy < height; dy++) {
                        final int byteOffset = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                        final byte value = layerValues[byteOffset >> 1];
                        destination[offset + dx * height + dy] = ((byteOffset & 1) == 0)
                                ? value & 0x0f : (value & 0xf0) >> 4;
                    }
                }
            }
            case BYTE -> {
                for (int dx = 0; dx < width; dx++) {
                    for (int dy = 0; dy < height; dy++) {
                        final int byteOffset = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                        destination[offset + dx * height + dy] = layerValues[byteOffset] & 0xff;
                    }
                }
            }
            case BIT, BIT_PER_CHUNK -> throw new IllegalArgumentException("Can't get bits using this method");
            default -> throw new InternalError();
        }
    }

    /**
     * Copies a rectangular numeric layer into caller-owned byte storage and
     * returns whether the tile has stored values for the layer. When no layer
     * buffer exists, the destination is cleared and callers should use the
     * layer default value.
     */
    public synchronized boolean copyLayerValues(Layer layer, int x, int y,
                                                int width, int height,
                                                byte[] destination, int offset) {
        if ((layer.getDataSize() == Layer.DataSize.BIT)
                || (layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Can't get bits using this method");
        }
        final int area = checkedLayerCopyArea(x, y, width, height, destination.length, offset);
        ensureReadable(LAYER_DATA);
        final byte[] layerValues = layerData.get(layer);
        if (layerValues == null) {
            Arrays.fill(destination, offset, offset + area, (byte) 0);
            return false;
        }
        switch (layer.getDataSize()) {
            case NIBBLE -> {
                for (int dx = 0; dx < width; dx++) {
                    for (int dy = 0; dy < height; dy++) {
                        final int byteOffset = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                        final byte value = layerValues[byteOffset >> 1];
                        destination[offset + dx * height + dy] = (byte) (((byteOffset & 1) == 0)
                                ? value & 0x0f : (value & 0xf0) >> 4);
                    }
                }
            }
            case BYTE -> {
                for (int dx = 0; dx < width; dx++) {
                    for (int dy = 0; dy < height; dy++) {
                        final int byteOffset = (x + dx) | ((y + dy) << TILE_SIZE_BITS);
                        destination[offset + dx * height + dy] = layerValues[byteOffset];
                    }
                }
            }
            case BIT, BIT_PER_CHUNK -> throw new IllegalArgumentException("Can't get bits using this method");
            default -> throw new InternalError();
        }
        return true;
    }

    private static int checkedLayerCopyArea(int x, int y, int width, int height,
                                            int targetLength, int offset) {
        if ((width < 0) || (height < 0) || (x < 0) || (y < 0)
                || (x > TILE_SIZE - width) || (y > TILE_SIZE - height)) {
            throw new IndexOutOfBoundsException("rectangle " + x + "," + y + " " + width + "x" + height);
        }
        final long area = (long) width * height;
        if ((offset < 0) || (area > Integer.MAX_VALUE)
                || (offset > targetLength - (int) area)) {
            throw new IndexOutOfBoundsException("destination offset " + offset + " for " + area + " values");
        }
        return (int) area;
    }

    /**
     * Count the number of blocks where the specified bit layer is set in a
     * square around a particular location
     *
     * @param layer The bit layer to count.
     * @param x The X coordinate (local to the tile) of the location around
     *     which to count the layer.
     * @param y The Y coordinate (local to the tile) of the location around
     *     which to count the layer.
     * @param r The radius of the square.
     * @return The number of blocks in the specified square where the specified
     *     bit layer is set.
     */
    public synchronized int getBitLayerCount(Layer layer, int x, int y, int r) {
        if ((layer.getDataSize() != Layer.DataSize.BIT) && (layer.getDataSize() != Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Layer is not bit sized");
        }
        if (((x - r) < 0) || ((x + r) >= TILE_SIZE) || ((y - r) < 0) || ((y + r) >= TILE_SIZE)) {
            throw new IllegalArgumentException("Requested area not contained entirely on tile");
        }
        ensureReadable(BIT_LAYER_DATA);
        BitSet bitSet = bitLayerData.get(layer);
        if (bitSet == null) {
            return 0;
        } else {
            boolean bitPerChunk = layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK;
            int count = 0, bitOffset;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (bitPerChunk) {
                        bitOffset = ((x + dx) / 16) + ((y + dy) / 16) * (TILE_SIZE / 16);
                    } else {
                        bitOffset = x + dx + (y + dy) * TILE_SIZE;
                    }
                    if (bitSet.get(bitOffset)) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    /**
     * Gets all layers that are set at the specified location, along with their intensities or values. For bit-valued
     * layers the intensity is zero for off, one for on.
     *
     * @param x The X location for which to retrieve all layers.
     * @param y The Y location for which to retrieve all layers.
     * @return A map with all layers set at the specified location, mapped to their intensities or values at that
     * location. May either be {@code null} or an empty map if no layers are present.
     */
    public synchronized Map<Layer, Integer> getLayersAt(int x, int y) {
        Map<Layer, Integer> layers = null;
        ensureReadable(LAYER_DATA);
        for (Map.Entry<Layer, byte[]> entry: layerData.entrySet()) {
            Layer layer = entry.getKey();
            byte[] layerValues = entry.getValue();
            int value;
            if (layer.getDataSize() == DataSize.NIBBLE) {
                int byteOffset = x | (y << TILE_SIZE_BITS);
                byte _byte = layerValues[byteOffset / 2];
                if (byteOffset % 2 == 0) {
                    value = _byte & 0x0F;
                } else {
                    value = (_byte & 0xF0) >> 4;
                }
            } else {
                value = layerValues[x | (y << TILE_SIZE_BITS)] & 0xFF;
            }
            if (value != layer.getDefaultValue()) {
                if (layers == null) {
                    layers = new HashMap<>();
                }
                layers.put(layer, value);
            }
        }
        ensureReadable(BIT_LAYER_DATA);
        for (Map.Entry<Layer, BitSet> entry: bitLayerData.entrySet()) {
            Layer layer = entry.getKey();
            BitSet layerValues = entry.getValue();
            int value;
            if (layer.getDataSize() == Layer.DataSize.BIT) {
                value = layerValues.get(x | (y << TILE_SIZE_BITS)) ? 1 : 0;
            } else {
                value = layerValues.get((x >> 4) + (y >> 4) * (TILE_SIZE >> 4)) ? 1 : 0;
            }
            if (value != layer.getDefaultValue()) {
                if (layers == null) {
                    layers = new HashMap<>();
                }
                layers.put(layer, value);
            }
        }
        return layers;
    }

    /**
     * Count the number of blocks that are flooded in a square around a
     * particular location
     *
     * @param x The X coordinate (local to the tile) of the location around
     *     which to count flooded blocks.
     * @param y The Y coordinate (local to the tile) of the location around
     *     which to count flooded blocks.
     * @param r The radius of the square.
     * @param lava Whether to check for lava (when {@code true}) or water
     *     (when {@code false}).
     * @return The number of blocks in the specified square that are flooded.
     */
    public synchronized int getFloodedCount(final int x, final int y, final int r, final boolean lava) {
        if (((x - r) < 0) || ((x + r) >= TILE_SIZE) || ((y - r) < 0) || ((y + r) >= TILE_SIZE)) {
            throw new IllegalArgumentException("Requested area not contained entirely on tile");
        }
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            ensureReadable(TALL_WATERLEVEL);
            ensureReadable(BIT_LAYER_DATA);
            final BitSet floodWithLava = bitLayerData.get(FloodWithLava.INSTANCE);
            int count = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    final int xx = x + dx, yy = y + dy;
                    if (((tallWaterLevel[xx + yy * TILE_SIZE]) > (Math.round(tallHeightMap[xx + yy * TILE_SIZE] / 256f)))
                            && (lava ? ((floodWithLava != null) && getBitPerBlockLayerValue(floodWithLava, xx, yy))
                                : ((floodWithLava == null) || (! getBitPerBlockLayerValue(floodWithLava, xx, yy))))) {
                        count++;
                    }
                }
            }
            return count;
        } else {
            ensureReadable(HEIGHTMAP);
            ensureReadable(WATERLEVEL);
            ensureReadable(BIT_LAYER_DATA);
            final BitSet floodWithLava = bitLayerData.get(FloodWithLava.INSTANCE);
            int count = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    final int xx = x + dx, yy = y + dy;
                    if (((waterLevel[xx + yy * TILE_SIZE] & 0xFF) > (Math.round((heightMap[xx + yy * TILE_SIZE] & 0xFFFF) / 256f)))
                            && (lava ? ((floodWithLava != null) && getBitPerBlockLayerValue(floodWithLava, xx, yy))
                                : ((floodWithLava == null) || (! getBitPerBlockLayerValue(floodWithLava, xx, yy))))) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    public synchronized float getDistanceToEdge(final Layer layer, final int x, final int y, final float maxDistance) {
        if ((layer.getDataSize() != Layer.DataSize.BIT) && (layer.getDataSize() != Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Layer is not bit sized");
        }
        int r = (int) Math.ceil(maxDistance);
        if (((x - r) < 0) || ((x + r) >= TILE_SIZE) || ((y - r) < 0) || ((y + r) >= TILE_SIZE)) {
            throw new IllegalArgumentException("Requested area not contained entirely on tile");
        }
        ensureReadable(BIT_LAYER_DATA);
        BitSet bitSet = bitLayerData.get(layer);
        if (bitSet == null) {
            return 0;
        } else {
            float distance = maxDistance;
            if (layer.getDataSize() == DataSize.BIT) {
                if (! getBitPerBlockLayerValue(bitSet, x, y)) {
                    return 0;
                }
                for (int i = 1; i <= r; i++) {
                    if (((! getBitPerBlockLayerValue(bitSet, x - i, y))
                                || (! getBitPerBlockLayerValue(bitSet, x + i, y))
                                || (! getBitPerBlockLayerValue(bitSet, x, y - i))
                                || (! getBitPerBlockLayerValue(bitSet, x, y + i)))
                            && (i < distance)) {
                        // If we get here there's no possible way a shorter
                        // distance could be found later, so return immediately
                        return i;
                    }
                    for (int d = 1; d <= i; d++) {
                        if ((! getBitPerBlockLayerValue(bitSet, x - i, y - d))
                                || (! getBitPerBlockLayerValue(bitSet, x + d, y - i))
                                || (! getBitPerBlockLayerValue(bitSet, x + i, y + d))
                                || (! getBitPerBlockLayerValue(bitSet, x - d, y + i))
                                || ((d < i) && ((! getBitPerBlockLayerValue(bitSet, x - i, y + d))
                                    || (! getBitPerBlockLayerValue(bitSet, x - d, y - i))
                                    || (! getBitPerBlockLayerValue(bitSet, x + i, y - d))
                                    || (! getBitPerBlockLayerValue(bitSet, x + d, y + i))))) {
                            float tDistance = MathUtils.getDistance(i, d);
                            if (tDistance < distance) {
                                distance = tDistance;
                            }
                            // We won't find a shorter distance this round, so
                            // skip to the next round
                            break;
                        }
                    }
                }
            } else {
                if (! getBitPerChunkLayerValue(bitSet, x, y)) {
                    return 0;
                }
                for (int i = 1; i <= r; i++) {
                    if (((! getBitPerChunkLayerValue(bitSet, x - i, y))
                                || (! getBitPerChunkLayerValue(bitSet, x + i, y))
                                || (! getBitPerChunkLayerValue(bitSet, x, y - i))
                                || (! getBitPerChunkLayerValue(bitSet, x, y + i)))
                            && (i < distance)) {
                        // If we get here there's no possible way a shorter
                        // distance could be found later, so return immediately
                        return i;
                    }
                    for (int d = 1; d <= i; d++) {
                        if ((! getBitPerChunkLayerValue(bitSet, x - i, y - d))
                                || (! getBitPerChunkLayerValue(bitSet, x + d, y - i))
                                || (! getBitPerChunkLayerValue(bitSet, x + i, y + d))
                                || (! getBitPerChunkLayerValue(bitSet, x - d, y + i))
                                || ((d < i) && ((! getBitPerChunkLayerValue(bitSet, x - i, y + d))
                                    || (! getBitPerChunkLayerValue(bitSet, x - d, y - i))
                                    || (! getBitPerChunkLayerValue(bitSet, x + i, y - d))
                                    || (! getBitPerChunkLayerValue(bitSet, x + d, y + i))))) {
                            float tDistance = MathUtils.getDistance(i, d);
                            if (tDistance < distance) {
                                distance = tDistance;
                            }
                            // We won't find a shorter distance this round, so
                            // skip to the next round
                            break;
                        }
                    }
                }
            }
            return distance;
        }
    }

    public void setBitLayerValue(Layer layer, int x, int y, boolean value) {
        if ((layer.getDataSize() != Layer.DataSize.BIT) && (layer.getDataSize() != Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Layer is not bit sized");
        }
        synchronized (this) {
            ensureReadable(BIT_LAYER_DATA);
            BitSet bitSet = bitLayerData.get(layer);
            if ((bitSet == null) && !value) {
                return;
            }
            ensureWriteable(BIT_LAYER_DATA);
            bitSet = bitLayerData.get(layer);
            if (bitSet == null) {
                if (value) {
                    cachedLayers = null;
                    if (layer.getDataSize() == Layer.DataSize.BIT) {
                        bitSet = new BitSet(TILE_SIZE * TILE_SIZE);
                    } else {
                        bitSet = new BitSet(TILE_SIZE * TILE_SIZE / 256);
                    }
                    bitLayerData.put(layer, bitSet);
                } else {
                    // If there is no bitset the default value is false, so if we're
                    // setting to false anyway there's no point in creating the
                    // bitset
                    return;
                }
            }
            int bitOffset;
            if (layer.getDataSize() == Layer.DataSize.BIT) {
                bitOffset = x | (y << TILE_SIZE_BITS);
            } else {
                bitOffset = (x / 16) + (y / 16) * (TILE_SIZE / 16);
            }
            bitSet.set(bitOffset, value);
        }
        layerDataChanged(layer);
    }

    /**
     * Edit the two compact selection planes together. False requests the normal
     * Java shape path. Deferred events are required to preserve notification
     * semantics while replacing the undo-aware bit-layer map in one transaction.
     */
    public boolean editSelectionShape(Shape shape, boolean add) {
        final int flags;
        synchronized (this) {
            if (getClass() != Tile.class || eventInhibitionCounter == 0
                    || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
                return false;
            }
            ensureReadable(BIT_LAYER_DATA);
            final var chunkLayer = SelectionChunk.INSTANCE;
            final var blockLayer = SelectionBlock.INSTANCE;
            final ByteBuffer result = SelectionTileAccess.edit(this, shape, add,
                    bitLayerData.get(chunkLayer), bitLayerData.get(blockLayer));
            if (result == null) return false;
            flags = result.getInt(12);
            if (flags == 0) return true;
            ensureWriteable(BIT_LAYER_DATA);
            if ((flags & 1) != 0) {
                bitLayerData.put(chunkLayer, SelectionTileAccess.applyBits(result, 88, 8, bitLayerData.get(chunkLayer)));
            }
            if ((flags & 2) != 0) {
                bitLayerData.put(blockLayer, SelectionTileAccess.applyBits(result, 96, 2048, bitLayerData.get(blockLayer)));
            }
            cachedLayers = null;
        }
        if ((flags & 1) != 0) layerDataChanged(SelectionChunk.INSTANCE);
        if ((flags & 2) != 0) layerDataChanged(SelectionBlock.INSTANCE);
        return true;
    }

    public synchronized boolean canEditSelectionMask() {
        return Native.isGenEnabled() && NativeLoader.areSlicesAvailable() && selectionMaskCompatible();
    }

    private boolean selectionMaskCompatible() {
        if (getClass() != Tile.class || eventInhibitionCounter == 0) return false;
        ensureReadable(BIT_LAYER_DATA);
        BitSet chunks = bitLayerData.get(SelectionChunk.INSTANCE), blocks = bitLayerData.get(SelectionBlock.INSTANCE);
        return (chunks == null || chunks.length() <= 64) && (blocks == null || blocks.length() <= 16384);
    }

    /** Masque de 2 048 octets, bit x + y * 128 ; aucun tirage aléatoire n'est refait lors du repli. */
    public boolean editSelectionMask(byte[] mask, boolean add) {
        final int flags;
        synchronized (this) {
            if (mask.length != 2048 || !selectionMaskCompatible()) return false;
            ByteBuffer result = SelectionTileAccess.editMask(add, bitLayerData.get(SelectionChunk.INSTANCE), bitLayerData.get(SelectionBlock.INSTANCE), mask);
            if (result == null) { editSelectionMaskJava(mask, add); return true; }
            flags = result.getInt(12);
            if (flags == 0) return true;
            ensureWriteable(BIT_LAYER_DATA);
            if ((flags & 1) != 0) bitLayerData.put(SelectionChunk.INSTANCE,
                    SelectionTileAccess.applyBits(result, 88, 8, bitLayerData.get(SelectionChunk.INSTANCE)));
            if ((flags & 2) != 0) bitLayerData.put(SelectionBlock.INSTANCE,
                    SelectionTileAccess.applyBits(result, 96, 2048, bitLayerData.get(SelectionBlock.INSTANCE)));
            cachedLayers = null;
        }
        if ((flags & 1) != 0) layerDataChanged(SelectionChunk.INSTANCE);
        if ((flags & 2) != 0) layerDataChanged(SelectionBlock.INSTANCE);
        return true;
    }

    private void editSelectionMaskJava(byte[] mask, boolean add) {
        boolean chunksPresent = hasLayer(SelectionChunk.INSTANCE), blocksPresent = hasLayer(SelectionBlock.INSTANCE);
        for (int cx = 0; cx < 128; cx += 16) for (int cy = 0; cy < 128; cy += 16) {
            boolean whole = chunksPresent && getBitLayerValue(SelectionChunk.INSTANCE, cx, cy);
            if (add && whole) continue;
            boolean any = false, all = true;
            for (int dx = 0; dx < 16; dx++) for (int dy = 0; dy < 16; dy++) {
                int bit = cx + dx + (cy + dy) * 128; boolean selected = (mask[bit / 8] & (1 << (bit % 8))) != 0;
                any |= selected; all &= selected;
            }
            if (!any) continue;
            if (all) {
                if (add || chunksPresent) setBitLayerValue(SelectionChunk.INSTANCE, cx, cy, add);
                if (blocksPresent) for (int dx = 0; dx < 16; dx++) for (int dy = 0; dy < 16; dy++)
                    setBitLayerValue(SelectionBlock.INSTANCE, cx + dx, cy + dy, false);
            } else {
                if (!add && whole) setBitLayerValue(SelectionChunk.INSTANCE, cx, cy, false);
                for (int dx = 0; dx < 16; dx++) for (int dy = 0; dy < 16; dy++) {
                    int bit = cx + dx + (cy + dy) * 128; boolean selected = (mask[bit / 8] & (1 << (bit % 8))) != 0;
                    if (add ? selected : whole ? !selected : selected)
                        setBitLayerValue(SelectionBlock.INSTANCE, cx + dx, cy + dy, add || whole);
                }
            }
        }
    }

    /**
     * Set a bit layer on the entire tile.
     *
     * @param layer The layer for which to set the layer.
     */
    public void setBitLayerValue(Layer layer) {
        if ((layer.getDataSize() != Layer.DataSize.BIT) && (layer.getDataSize() != Layer.DataSize.BIT_PER_CHUNK)) {
            throw new IllegalArgumentException("Layer is not bit sized");
        }
        synchronized (this) {
            ensureWriteable(BIT_LAYER_DATA);
            BitSet bitSet = bitLayerData.get(layer);
            if (bitSet == null) {
                cachedLayers = null;
                if (layer.getDataSize() == Layer.DataSize.BIT) {
                    bitSet = new BitSet(TILE_SIZE * TILE_SIZE);
                    bitSet.set(0, TILE_SIZE * TILE_SIZE);
                } else {
                    bitSet = new BitSet(TILE_SIZE * TILE_SIZE / 256);
                    bitSet.set(0, TILE_SIZE * TILE_SIZE / 256);
                }
                bitLayerData.put(layer, bitSet);
            }
            if (layer.getDataSize() == Layer.DataSize.BIT) {
                bitSet.set(0, TILE_SIZE * TILE_SIZE);
            } else {
                bitSet.set(0, TILE_SIZE * TILE_SIZE / 256);
            }
        }
        layerDataChanged(layer);
    }

    public synchronized int getLayerValue(Layer layer, int x, int y) {
        ensureReadable(LAYER_DATA);
        byte[] layerValues = layerData.get(layer);
        if (layerValues == null) {
            return layer.getDefaultValue();
        } else {
            switch (layer.getDataSize()) {
                case BIT:
                case BIT_PER_CHUNK:
                    throw new IllegalArgumentException("Can't get bits using this method");
                case NIBBLE:
                    int byteOffset = x | (y << TILE_SIZE_BITS);
                    byte _byte = layerValues[byteOffset / 2];
                    if (byteOffset % 2 == 0) {
                        return _byte & 0x0F;
                    } else {
                        return (_byte & 0xF0) >> 4;
                    }
                case BYTE:
                    byteOffset = x | (y << TILE_SIZE_BITS);
                    return layerValues[byteOffset] & 0xFF;
                default:
                    throw new InternalError();
            }
        }
    }

    public void setLayerValue(Layer layer, int x, int y, int value) {
        synchronized (this) {
            ensureReadable(LAYER_DATA);
            byte[] layerValues = layerData.get(layer);
            if ((layerValues == null) && (value == layer.getDefaultValue())) {
                return;
            }
            ensureWriteable(LAYER_DATA);
            layerValues = layerData.get(layer);
            if (layerValues == null) {
                if (value == layer.getDefaultValue()) {
                    // There is no data buffer and we're setting the value to the
                    // default, so we don't need to create it
                    return;
                }
                cachedLayers = null;
                switch (layer.getDataSize()) {
                    case BIT:
                    case BIT_PER_CHUNK:
                        throw new IllegalArgumentException("Can't set bits using this method");
                    case NIBBLE:
                        layerValues = new byte[TILE_SIZE * TILE_SIZE / 2];
                        if (layer.getDefaultValue() != 0) {
                            byte defaultValue = (byte) (layer.getDefaultValue() << 4 | layer.getDefaultValue());
                            Arrays.fill(layerValues, defaultValue);
                        }
                        break;
                    case BYTE:
                        layerValues = new byte[TILE_SIZE * TILE_SIZE];
                        if (layer.getDefaultValue() != 0) {
                            byte defaultValue = (byte) layer.getDefaultValue();
                            Arrays.fill(layerValues, defaultValue);
                        }
                        break;
                    default:
                        throw new InternalError();
                }
                layerData.put(layer, layerValues);
            }
            layerValues = detachSharedLayerDataBuffer(layer, layerValues);
            switch (layer.getDataSize()) {
                case BIT:
                case BIT_PER_CHUNK:
                    throw new IllegalArgumentException("Can't set bits using this method");
                case NIBBLE:
                    if ((value < 0) || (value > 15)) {
                        throw new IllegalArgumentException("Illegal value " + value + " for nibble sized layer " + layer);
                    }
                    int byteOffset = x | (y << TILE_SIZE_BITS);
                    byte _byte = layerValues[byteOffset / 2];
                    if (byteOffset % 2 == 0) {
                        _byte &= 0xF0;
                        _byte |= value;
                    } else {
                        _byte &= 0x0F;
                        _byte |= (value << 4);
                    }
                    layerValues[byteOffset / 2] = _byte;
                    break;
                case BYTE:
                    if ((value < 0) || (value > 255)) {
                        throw new IllegalArgumentException("Illegal value " + value + " for byte sized layer " + layer);
                    }
                    byteOffset = x | (y << TILE_SIZE_BITS);
                    layerValues[byteOffset] = (byte) value;
                    break;
                default:
                    throw new InternalError();
            }
        }
        layerDataChanged(layer);
    }

    public void clearLayerData(Layer layer) {
        final Set<Layer> changedLayers = new HashSet<>();
        synchronized (this) {
            if ((layer.getDataSize() == Layer.DataSize.BIT) || (layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK)) {
                ensureReadable(BIT_LAYER_DATA);
                if (bitLayerData.containsKey(layer)) {
                    ensureWriteable(BIT_LAYER_DATA);
                    bitLayerData.remove(layer);
                    changedLayers.add(layer);
                    cachedLayers = null;
                }
            } else {
                ensureReadable(LAYER_DATA);
                if (layerData.containsKey(layer)) {
                    ensureWriteable(LAYER_DATA);
                    layerData.remove(layer);
                    changedLayers.add(layer);
                    cachedLayers = null;
                }
            }
        }
        if (! changedLayers.isEmpty()) {
            changedLayers.forEach(this::layerDataChanged);
        }
    }

    /**
     * Clear all layer data at a particular location (by resetting to the
     * layer's default value), possibly with the exception of certain layers.
     *
     * @param x The X coordinate of the location to clear of layer data.
     * @param y The Y coordinate of the location to clear of layer data.
     * @param excludedLayers The layers to exclude, if any. May be
     *                       {@code null}.
     */
    public void clearLayerData(int x, int y, Set<Layer> excludedLayers) {
        final Set<Layer> changedLayers = new HashSet<>();
        synchronized (this) {
            ensureReadable(BIT_LAYER_DATA);
            if (!bitLayerData.isEmpty()) {
                ensureWriteable(BIT_LAYER_DATA);
                for (Map.Entry<Layer, BitSet> entry: bitLayerData.entrySet()) {
                    Layer layer = entry.getKey();
                    if ((excludedLayers != null) && excludedLayers.contains(layer)) {
                        continue;
                    }
                    int bitOffset;
                    if (layer.getDataSize() == Layer.DataSize.BIT) {
                        bitOffset = x | (y << TILE_SIZE_BITS);
                    } else {
                        bitOffset = (x / 16) + (y / 16) * (TILE_SIZE / 16);
                    }
                    entry.getValue().set(bitOffset, layer.getDefaultValue() != 0);
                    changedLayers.add(layer);
                }
            }
            ensureReadable(LAYER_DATA);
            if (!layerData.isEmpty()) {
                ensureWriteable(LAYER_DATA);
                for (Map.Entry<Layer, byte[]> entry: layerData.entrySet()) {
                    Layer layer = entry.getKey();
                    if ((excludedLayers != null) && excludedLayers.contains(layer)) {
                        continue;
                    }
                    byte[] layerValues = detachSharedLayerDataBuffer(layer, entry.getValue());
                    switch (layer.getDataSize()) {
                        case NIBBLE:
                            int byteOffset = x | (y << TILE_SIZE_BITS);
                            byte _byte = layerValues[byteOffset / 2];
                            if (byteOffset % 2 == 0) {
                                _byte &= 0xF0;
                                _byte |= layer.getDefaultValue();
                            } else {
                                _byte &= 0x0F;
                                _byte |= (layer.getDefaultValue() << 4);
                            }
                            layerValues[byteOffset / 2] = _byte;
                            break;
                        case BYTE:
                            byteOffset = x | (y << TILE_SIZE_BITS);
                            layerValues[byteOffset] = (byte) layer.getDefaultValue();
                            break;
                        default:
                            throw new InternalError();
                    }
                    changedLayers.add(layer);
                }
            }
        }
        if (! changedLayers.isEmpty()) {
            changedLayers.forEach(this::layerDataChanged);
        }
    }

    public synchronized HashSet<Seed> getSeeds() {
        if (seeds != null) {
            ensureReadable(SEEDS);
            return seeds;
        } else {
            return null;
        }
    }

    public boolean plantSeed(Seed seed) {
        synchronized (this) {
            if (seeds == null) {
                seeds = new HashSet<>();
                if (undoManager != null) {
                    undoManager.addBuffer(SEEDS_BUFFER_KEY, seeds, this);
                    readableBuffers.add(SEEDS);
                    writeableBuffers.add(SEEDS);
                }
            } else {
                ensureWriteable(SEEDS);
            }
            seeds.add(seed);
        }
        seedsChanged();
        return true;
    }

    public void removeSeed(Seed seed) {
        synchronized (this) {
            if (seeds == null) {
                seeds = new HashSet<>();
                if (undoManager != null) {
                    undoManager.addBuffer(SEEDS_BUFFER_KEY, seeds, this);
                    readableBuffers.add(SEEDS);
                    writeableBuffers.add(SEEDS);
                }
            } else {
                ensureWriteable(SEEDS);
            }
            seeds.remove(seed);
        }
        seedsChanged();
    }

    public synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isEventsInhibited() {
        return eventInhibitionCounter != 0;
    }

    /**
     * Stop firing events when the tile is modified, until {@link #releaseEvents()} is invoked. Make sure that
     * {@code releaseEvents()} is always invoked, even if an exception is thrown, by using a try-finally statement:
     *
     * <p><code>tile.inhibitEvents();<br>
     * try {<br>
     * &nbsp;&nbsp;&nbsp;&nbsp;// modify the tile<br>
     * } finally {<br>
     * &nbsp;&nbsp;&nbsp;&nbsp;tile.releaseEvents();<br>
     * }</code>
     *
     * <p><strong>Note</strong> that calls to these methods may be nested, and if so, events will only be released after
     * the final invocation of {@code releaseEvents()}.
     */
    public void inhibitEvents() {
        eventInhibitionCounter++;
    }

    /**
     * Release an inhibition on firing events. Will fire all appropriate events at this time, if the tile was modified
     * since the first invocation of {@link #inhibitEvents()}, but only if this is the last invocation of
     * {@code releaseEvents()} in a nested set.
     */
    public void releaseEvents() {
        if (eventInhibitionCounter > 0) {
            eventInhibitionCounter--;
            if (eventInhibitionCounter == 0) {
                if (heightMapDirty) {
                    heightMapChanged();
                    heightMapDirty = false;
                }
                if (terrainDirty) {
                    terrainChanged();
                    terrainDirty = false;
                }
                if (waterLevelDirty) {
                    waterLevelChanged();
                    waterLevelDirty = false;
                }
                if (bitLayersDirty) {
                    allBitLayerDataChanged();
                    bitLayersDirty = false;
                    for (Iterator<Layer> i = dirtyLayers.iterator(); i.hasNext(); ) {
                        DataSize dataSize = i.next().getDataSize();
                        if ((dataSize == DataSize.BIT) || (dataSize == DataSize.BIT_PER_CHUNK)) {
                            i.remove();
                        }
                    }
                }
                if (nonBitLayersDirty) {
                    allNonBitLayerDataChanged();
                    nonBitLayersDirty = false;
                    for (Iterator<Layer> i = dirtyLayers.iterator(); i.hasNext(); ) {
                        DataSize dataSize = i.next().getDataSize();
                        if ((dataSize != DataSize.BIT) && (dataSize != DataSize.BIT_PER_CHUNK)) {
                            i.remove();
                        }
                    }
                }
                if (! dirtyLayers.isEmpty()) {
                    Set<Layer> changedLayers = Collections.unmodifiableSet(dirtyLayers);
                    for (Listener listener: listeners) {
                        listener.layerDataChanged(this, changedLayers);
                    }
                    dirtyLayers.clear();
                }
                if (seedsDirty) {
                    seedsChanged();
                    seedsDirty = false;
                }
            }
        } else {
            throw new IllegalStateException("Events not inhibited");
        }
    }

    public synchronized void register(UndoManager undoManager) {
        this.undoManager = undoManager;
        registerUndoBuffers();
        undoManager.addListener(this);
    }

    public synchronized void unregister() {
        if (undoManager != null) {
            undoManager.removeListener(this);
            unregisterUndoBuffers();
            undoManager = null;
        }
    }

    /**
     * Create a new tile based on this one but horizontally transformed according to some transformation. Scaling is not
     * supported, and shifting must be by multiples of {@link Constants#TILE_SIZE}.
     *
     * @param transform The transform to apply.
     * @return A new tile with the same contents, except transformed according to the specified transform (including the
     * X and Y coordinates).
     * @throws IllegalArgumentException If the specified transform is not supported.
     */
    public synchronized Tile transform(CoordinateTransform transform) {
        if (transform.isScaling()) {
            throw new IllegalArgumentException("Scaling tiles not supported");
        }
        final Point transformedCoords = transform.transform(x << TILE_SIZE_BITS, y << TILE_SIZE_BITS);
        final Tile transformedTile;
        final Tile nativeRotated = TileRotationAccess.rotate(this, transform, transformedCoords);
        if (nativeRotated != null) {
            transformedTile = nativeRotated;
            transformedTile.init();
        } else if (transform.isRotating()) {
            transformedTile = new Tile(transformedCoords.x >> TILE_SIZE_BITS, transformedCoords.y >> TILE_SIZE_BITS, minHeight, maxHeight);
            ensureReadable(TERRAIN);
            if (tall) {
                ensureReadable(TALL_HEIGHTMAP);
                ensureReadable(TALL_WATERLEVEL);
            } else {
                ensureReadable(HEIGHTMAP);
                ensureReadable(WATERLEVEL);
            }
            for (int x = 0; x < TILE_SIZE; x++) {
                for (int y = 0; y < TILE_SIZE; y++) {
                    transformedCoords.x = x;
                    transformedCoords.y = y;
                    transform.transformInPlace(transformedCoords);
                    transformedCoords.x &= TILE_SIZE_MASK;
                    transformedCoords.y &= TILE_SIZE_MASK;
                    transformedTile.setTerrain(transformedCoords.x, transformedCoords.y, TERRAIN_VALUES[terrain[x | (y << TILE_SIZE_BITS)] & 0xFF]);
                    transformedTile.setRawHeight(transformedCoords.x, transformedCoords.y, tall ? tallHeightMap[x | (y << TILE_SIZE_BITS)] : (heightMap[x | (y << TILE_SIZE_BITS)] & 0xFFFF));
                    transformedTile.setWaterLevel(transformedCoords.x, transformedCoords.y, (tall ? (tallWaterLevel[x | y << TILE_SIZE_BITS] & 0xFFFF) : (waterLevel[x | y << TILE_SIZE_BITS] & 0xFF)) + minHeight);
                }
            }
            for (Layer layer: getLayers()) {
                switch (layer.getDataSize()) {
                    case BIT -> {
                        ensureReadable(BIT_LAYER_DATA);
                        final BitSet bitSet = bitLayerData.get(layer);
                        if (bitSet != null) {
                            for (int x = 0; x < TILE_SIZE; x++) {
                                for (int y = 0; y < TILE_SIZE; y++) {
                                    if (getBitPerBlockLayerValue(bitSet, x, y)) {
                                        transformedCoords.x = x;
                                        transformedCoords.y = y;
                                        transform.transformInPlace(transformedCoords);
                                        transformedCoords.x &= TILE_SIZE_MASK;
                                        transformedCoords.y &= TILE_SIZE_MASK;
                                        transformedTile.setBitLayerValue(layer, transformedCoords.x, transformedCoords.y, true);
                                    }
                                }
                            }
                        }
                    }
                    case BIT_PER_CHUNK -> {
                        ensureReadable(BIT_LAYER_DATA);
                        final BitSet bitSet = bitLayerData.get(layer);
                        if (bitSet != null) {
                            for (int x = 0; x < TILE_SIZE; x += 16) {
                                for (int y = 0; y < TILE_SIZE; y += 16) {
                                    if (getBitPerChunkLayerValue(bitSet, x, y)) {
                                        transformedCoords.x = x;
                                        transformedCoords.y = y;
                                        transform.transformInPlace(transformedCoords);
                                        transformedCoords.x &= TILE_SIZE_MASK;
                                        transformedCoords.y &= TILE_SIZE_MASK;
                                        transformedTile.setBitLayerValue(layer, transformedCoords.x, transformedCoords.y, true);
                                    }
                                }
                            }
                        }
                    }
                    case NIBBLE -> {
                        ensureReadable(LAYER_DATA);
                        final byte[] layerValues = layerData.get(layer);
                        if (layerValues != null) {
                            final int defaultValue = layer.getDefaultValue();
                            for (int x = 0; x < TILE_SIZE; x++) {
                                for (int y = 0; y < TILE_SIZE; y++) {
                                    final int value;
                                    final int byteOffset = x | (y << TILE_SIZE_BITS);
                                    final byte _byte = layerValues[byteOffset / 2];
                                    if (byteOffset % 2 == 0) {
                                        value = _byte & 0x0F;
                                    } else {
                                        value = (_byte & 0xF0) >> 4;
                                    }
                                    if (value != defaultValue) {
                                        transformedCoords.x = x;
                                        transformedCoords.y = y;
                                        transform.transformInPlace(transformedCoords);
                                        transformedCoords.x &= TILE_SIZE_MASK;
                                        transformedCoords.y &= TILE_SIZE_MASK;
                                        transformedTile.setLayerValue(layer, transformedCoords.x, transformedCoords.y, value);
                                    }
                                }
                            }
                        }
                    }
                    case BYTE -> {
                        ensureReadable(LAYER_DATA);
                        final byte[] layerValues = layerData.get(layer);
                        if (layerValues != null) {
                            final int defaultValue = layer.getDefaultValue();
                            for (int x = 0; x < TILE_SIZE; x++) {
                                for (int y = 0; y < TILE_SIZE; y++) {
                                    final int value = layerValues[x | (y << TILE_SIZE_BITS)] & 0xFF;
                                    if (value != defaultValue) {
                                        transformedCoords.x = x;
                                        transformedCoords.y = y;
                                        transform.transformInPlace(transformedCoords);
                                        transformedCoords.x &= TILE_SIZE_MASK;
                                        transformedCoords.y &= TILE_SIZE_MASK;
                                        transformedTile.setLayerValue(layer, transformedCoords.x, transformedCoords.y, value);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // The transformation does not affect intra-tile coordinates, so just copy the buffers without transforming them
            transformedTile = new Tile(transformedCoords.x >> TILE_SIZE_BITS, transformedCoords.y >> TILE_SIZE_BITS, minHeight, maxHeight, false);
            transformedTile.heightMap = isSharedUniformHeightMapBuffer(heightMap) ? heightMap : copyObject(heightMap);
            transformedTile.tallHeightMap = isSharedUniformTallHeightMapBuffer(tallHeightMap) ? tallHeightMap : copyObject(tallHeightMap);
            transformedTile.terrain = (terrain == DEFAULT_TERRAIN_BUFFER) ? DEFAULT_TERRAIN_BUFFER : terrain.clone();
            transformedTile.waterLevel = isSharedUniformWaterLevelBuffer(waterLevel) ? waterLevel : copyObject(waterLevel);
            transformedTile.tallWaterLevel = (tallWaterLevel == DEFAULT_TALL_WATERLEVEL_BUFFER) ? DEFAULT_TALL_WATERLEVEL_BUFFER : copyObject(tallWaterLevel);
            transformedTile.layerData = layerData.isEmpty() ? DEFAULT_LAYER_DATA_BUFFER : copyObject(layerData);
            transformedTile.bitLayerData = bitLayerData.isEmpty() ? DEFAULT_BIT_LAYER_DATA_BUFFER : copyObject(bitLayerData);
            transformedTile.init();
        }
        if (seeds != null) {
            transformedTile.seeds = new HashSet<>();
            transformedTile.seeds.addAll(seeds);
            for (Seed seed: transformedTile.seeds) {
                seed.transform(transform);
            }
        }
        return transformedTile;
    }

    public synchronized boolean repair(int minHeight, int maxHeight, PrintStream out) {
        // Repair as much as possible if the tile was not read in completely
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        maxY = maxHeight - 1;
        if (maxHeight > 256) {
            tall = true;
            if (tallHeightMap == null) {
                out.println("Height map for tile " + x + "," + y + " lost");
                tallHeightMap = DEFAULT_TALL_HEIGHTMAP_BUFFER;
            }
            if (tallWaterLevel == null) {
                out.println("Water level map for tile " + x + "," + y + " lost");
                tallWaterLevel = DEFAULT_TALL_WATERLEVEL_BUFFER;
            }
            heightMap = null;
            waterLevel = null;
        } else {
            tall = false;
            if (heightMap == null) {
                out.println("Height map for tile " + x + "," + y + " lost");
                heightMap = DEFAULT_HEIGHTMAP_BUFFER;
            }
            if (waterLevel == null) {
                out.println("Water level map for tile " + x + "," + y + " lost");
                waterLevel = DEFAULT_WATERLEVEL_BUFFER;
            }
            tallHeightMap = null;
            tallWaterLevel = null;
        }
        if (terrain == null) {
            out.println("Terrain type map for tile " + x + "," + y + " lost");
            terrain = DEFAULT_TERRAIN_BUFFER;
        }
        if (layerData == null) {
            out.println("Non-bit valued layer data for tile " + x + "," + y + " lost");
            layerData = DEFAULT_LAYER_DATA_BUFFER;
        }
        if (bitLayerData == null) {
            out.println("Bit valued layer data for tile " + x + "," + y + " lost");
            bitLayerData = DEFAULT_BIT_LAYER_DATA_BUFFER;
        }
        init();
        return true;
    }

    public synchronized boolean containsOneOf(Layer... layers) {
        boolean bitLayersAvailable = false, nonBitLayersAvailable = false;
        for (Layer layer: layers) {
            switch (layer.getDataSize()) {
                case BIT:
                case BIT_PER_CHUNK:
                    if (! bitLayersAvailable) {
                        ensureReadable(BIT_LAYER_DATA);
                        bitLayersAvailable = true;
                    }
                    if (bitLayerData.containsKey(layer)) {
                        return true;
                    }
                    break;
                case BYTE:
                case NIBBLE:
                    if (! nonBitLayersAvailable) {
                        ensureReadable(LAYER_DATA);
                        nonBitLayersAvailable = true;
                    }
                    if (layerData.containsKey(layer)) {
                        return true;
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Data size " + layer.getDataSize() + " not supported");
            }
        }
        return false;
    }

    // UndoListener

    @Override
    public synchronized void savePointArmed() {
        if (logger.isTraceEnabled()) {
            logger.trace("Save point armed; clearing writable buffers");
        }
        writeableBuffers.clear();
    }

    @Override
    public synchronized void savePointCreated() {
        if (logger.isTraceEnabled()) {
            logger.trace("Save point created; clearing writable buffers");
        }
        writeableBuffers.clear();
    }

    @Override
    public void undoPerformed() {
        // Do nothing
    }

    @Override
    public void redoPerformed() {
        // Do nothing
    }

    @Override
    public void bufferChanged(BufferKey<?> key) {
        TileUndoBufferKey<?> tileKey = (TileUndoBufferKey<?>) key;
        if (logger.isTraceEnabled()) {
            logger.trace("Buffer " + key + " changed; clearing buffer cache for type " + tileKey.buffer + " and notifying listeners");
        }
        synchronized (this) {
            switch (tileKey.buffer) {
                case BIT_LAYER_DATA:
                    readableBuffers.remove(BIT_LAYER_DATA);
                    writeableBuffers.remove(BIT_LAYER_DATA);
                    cachedLayers = null;
                    break;
                case HEIGHTMAP:
                    readableBuffers.remove(HEIGHTMAP);
                    writeableBuffers.remove(HEIGHTMAP);
                    break;
                case TALL_HEIGHTMAP:
                    readableBuffers.remove(TALL_HEIGHTMAP);
                    writeableBuffers.remove(TALL_HEIGHTMAP);
                    break;
                case LAYER_DATA:
                    readableBuffers.remove(LAYER_DATA);
                    writeableBuffers.remove(LAYER_DATA);
                    cachedLayers = null;
                    break;
                case TERRAIN:
                    readableBuffers.remove(TERRAIN);
                    writeableBuffers.remove(TERRAIN);
                    break;
                case WATERLEVEL:
                    readableBuffers.remove(WATERLEVEL);
                    writeableBuffers.remove(WATERLEVEL);
                    break;
                case TALL_WATERLEVEL:
                    readableBuffers.remove(TALL_WATERLEVEL);
                    writeableBuffers.remove(TALL_WATERLEVEL);
                    break;
                case SEEDS:
                    readableBuffers.remove(SEEDS);
                    writeableBuffers.remove(SEEDS);
                    break;
            }
        }
        switch (tileKey.buffer) {
            case BIT_LAYER_DATA:
                allBitLayerDataChanged();
                break;
            case HEIGHTMAP:
            case TALL_HEIGHTMAP:
                heightMapChanged();
                break;
            case LAYER_DATA:
                allNonBitLayerDataChanged();
                break;
            case TERRAIN:
                terrainChanged();
                break;
            case WATERLEVEL:
            case TALL_WATERLEVEL:
                waterLevelChanged();
                break;
            case SEEDS:
                seedsChanged();
                break;
        }
    }

    // Object

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Tile other = (Tile) obj;
        if (this.x != other.x) {
            return false;
        }
        if (this.y != other.y) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 17 * hash + this.x;
        hash = 17 * hash + this.y;
        return hash;
    }

    @Override
    public String toString() {
        return "Tile[x=" + x + ",y=" + y + "]";
    }

    synchronized void ensureAllReadable() {
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            ensureReadable(TALL_WATERLEVEL);
        } else {
            ensureReadable(HEIGHTMAP);
            ensureReadable(WATERLEVEL);
        }
        ensureReadable(TERRAIN);
        ensureReadable(LAYER_DATA);
        ensureReadable(BIT_LAYER_DATA);
        ensureReadable(SEEDS);
    }

    synchronized void convertBiomeData() {
        byte[] biomeData = layerData.get(Biome.INSTANCE);
        if (biomeData != null) {
            layerData.remove(Biome.INSTANCE);
            byte[] newBiomeData = new byte[biomeData.length * 2];
            for (int i = 0; i < biomeData.length; i++) {
                newBiomeData[i * 2] = (byte) (biomeData[i] & 0x0f);
                newBiomeData[i * 2 + 1] = (byte) ((biomeData[i] & 0xf0) >> 4);
            }
            layerData.put(Biome.INSTANCE, newBiomeData);
        }
    }

    synchronized void prepareForSaving() {
        // Make sure all buffers are current, otherwise we may save out of date
        // data to disk
        ensureAllReadable();

        // Take the opportunity to save memory and disk space by throwing away "empty" layer buffers. Since this is
        // functionally a null operation there is no need to notify listeners, make the buffer writable or otherwise
        // notify the undo manager
        for (Iterator<Map.Entry<Layer, BitSet>> i = bitLayerData.entrySet().iterator(); i.hasNext(); ) {
            final Map.Entry<Layer, BitSet> entry = i.next();
            if (entry.getValue().isEmpty()) {
                i.remove();
                cachedLayers = null;
            }
        }
        layerLoop:
        for (Iterator<Map.Entry<Layer, byte[]>> i = layerData.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<Layer, byte[]> entry = i.next();
            final Layer layer = entry.getKey();
            final byte[] buffer = entry.getValue();
            if (layer.getDataSize() == NIBBLE) {
                final byte defaultByte = (byte) (layer.getDefaultValue() << 4 | layer.getDefaultValue());
                for (byte bufferByte: buffer) {
                    if (bufferByte != defaultByte) {
                        continue layerLoop;
                    }
                }
                // If we reach here all bytes were default bytes
                i.remove();
                cachedLayers = null;
            } else if (layer.getDataSize() == BYTE) {
                final byte defaultByte = (byte) layer.getDefaultValue();
                for (byte bufferByte: buffer) {
                    if (bufferByte != defaultByte) {
                        continue layerLoop;
                    }
                }
                // If we reach here all bytes were default bytes
                i.remove();
                cachedLayers = null;
            }
        }
    }

    private boolean getBitPerBlockLayerValue(BitSet bitSet, int x, int y) {
        return bitSet.get(x | (y << TILE_SIZE_BITS));
    }

    private boolean getBitPerChunkLayerValue(BitSet bitSet, int x, int y) {
        return bitSet.get((x >> 4) + (y >> 4) * (TILE_SIZE >> 4));
    }

    private void registerUndoBuffers() {
        if (tall) {
            undoManager.addBuffer(TALL_HEIGHTMAP_BUFFER_KEY,  tallHeightMap,  this);
            undoManager.addBuffer(TALL_WATERLEVEL_BUFFER_KEY, tallWaterLevel, this);
            readableBuffers = EnumSet.of(TALL_HEIGHTMAP, TALL_WATERLEVEL, TERRAIN, LAYER_DATA, BIT_LAYER_DATA);
            writeableBuffers = EnumSet.of(TALL_HEIGHTMAP, TALL_WATERLEVEL, TERRAIN, LAYER_DATA, BIT_LAYER_DATA);
        } else {
            undoManager.addBuffer(HEIGHTMAP_BUFFER_KEY,  heightMap,  this);
            undoManager.addBuffer(WATERLEVEL_BUFFER_KEY, waterLevel, this);
            readableBuffers = EnumSet.of(HEIGHTMAP, WATERLEVEL, TERRAIN, LAYER_DATA, BIT_LAYER_DATA);
            writeableBuffers = EnumSet.of(HEIGHTMAP, WATERLEVEL, TERRAIN, LAYER_DATA, BIT_LAYER_DATA);
        }
        undoManager.addBuffer(TERRAIN_BUFFER_KEY,        terrain,      this);
        undoManager.addBuffer(LAYER_DATA_BUFFER_KEY,     layerData,    this);
        undoManager.addBuffer(BIT_LAYER_DATA_BUFFER_KEY, bitLayerData, this);
        if (seeds != null) {
            undoManager.addBuffer(SEEDS_BUFFER_KEY, seeds, this);
            readableBuffers.add(SEEDS);
            writeableBuffers.add(SEEDS);
        }
    }

    private void unregisterUndoBuffers() {
        // Also make sure that we have all the data from the current undo level
        // references, because after this we can't get at it any more
        if (tall) {
            ensureReadable(TALL_HEIGHTMAP);
            undoManager.removeBuffer(TALL_HEIGHTMAP_BUFFER_KEY);
            ensureReadable(TALL_WATERLEVEL);
            undoManager.removeBuffer(TALL_WATERLEVEL_BUFFER_KEY);
        } else {
            ensureReadable(HEIGHTMAP);
            undoManager.removeBuffer(HEIGHTMAP_BUFFER_KEY);
            ensureReadable(WATERLEVEL);
            undoManager.removeBuffer(WATERLEVEL_BUFFER_KEY);
        }
        ensureReadable(TERRAIN);
        undoManager.removeBuffer(TERRAIN_BUFFER_KEY);
        ensureReadable(LAYER_DATA);
        undoManager.removeBuffer(LAYER_DATA_BUFFER_KEY);
        ensureReadable(BIT_LAYER_DATA);
        undoManager.removeBuffer(BIT_LAYER_DATA_BUFFER_KEY);
        if (seeds != null) {
            ensureReadable(SEEDS);
            undoManager.removeBuffer(SEEDS_BUFFER_KEY);
        }
        readableBuffers = writeableBuffers = null;
    }

    protected synchronized void ensureReadable(TileBuffer buffer) {
        if ((undoManager != null) && (! readableBuffers.contains(buffer))) {
            switch (buffer) {
                case HEIGHTMAP:
                    heightMap = undoManager.getBuffer(HEIGHTMAP_BUFFER_KEY);
                    break;
                case TALL_HEIGHTMAP:
                    tallHeightMap = undoManager.getBuffer(TALL_HEIGHTMAP_BUFFER_KEY);
                    break;
                case TERRAIN:
                    terrain = undoManager.getBuffer(TERRAIN_BUFFER_KEY);
                    break;
                case WATERLEVEL:
                    waterLevel = undoManager.getBuffer(WATERLEVEL_BUFFER_KEY);
                    break;
                case TALL_WATERLEVEL:
                    tallWaterLevel = undoManager.getBuffer(TALL_WATERLEVEL_BUFFER_KEY);
                    break;
                case LAYER_DATA:
                    layerData = undoManager.getBuffer(LAYER_DATA_BUFFER_KEY);
                    break;
                case BIT_LAYER_DATA:
                    bitLayerData = undoManager.getBuffer(BIT_LAYER_DATA_BUFFER_KEY);
                    break;
                case SEEDS:
                    seeds = undoManager.getBuffer(SEEDS_BUFFER_KEY);
                    break;
            }
            readableBuffers.add(buffer);
        }
    }

    private void ensureWriteable(TileBuffer buffer) {
        if (isSharedBuffer(buffer)) {
            if (undoManager == null) {
                copySharedBuffer(buffer);
            } else {
                switch (buffer) {
                    case HEIGHTMAP:
                        heightMap = undoManager.getBufferForEditing(HEIGHTMAP_BUFFER_KEY);
                        if (isSharedUniformHeightMapBuffer(heightMap)) {
                            heightMap = heightMap.clone();
                            undoManager.addBuffer(HEIGHTMAP_BUFFER_KEY, heightMap, this);
                        }
                        break;
                    case TALL_HEIGHTMAP:
                        tallHeightMap = undoManager.getBufferForEditing(TALL_HEIGHTMAP_BUFFER_KEY);
                        if (isSharedUniformTallHeightMapBuffer(tallHeightMap)) {
                            tallHeightMap = tallHeightMap.clone();
                            undoManager.addBuffer(TALL_HEIGHTMAP_BUFFER_KEY, tallHeightMap, this);
                        }
                        break;
                    case TERRAIN:
                        terrain = undoManager.getBufferForEditing(TERRAIN_BUFFER_KEY);
                        if (terrain == DEFAULT_TERRAIN_BUFFER) {
                            terrain = DEFAULT_TERRAIN_BUFFER.clone();
                            undoManager.addBuffer(TERRAIN_BUFFER_KEY, terrain, this);
                        }
                        break;
                    case WATERLEVEL:
                        waterLevel = undoManager.getBufferForEditing(WATERLEVEL_BUFFER_KEY);
                        if (isSharedUniformWaterLevelBuffer(waterLevel)) {
                            waterLevel = waterLevel.clone();
                            undoManager.addBuffer(WATERLEVEL_BUFFER_KEY, waterLevel, this);
                        }
                        break;
                    case TALL_WATERLEVEL:
                        tallWaterLevel = undoManager.getBufferForEditing(TALL_WATERLEVEL_BUFFER_KEY);
                        if (tallWaterLevel == DEFAULT_TALL_WATERLEVEL_BUFFER) {
                            tallWaterLevel = DEFAULT_TALL_WATERLEVEL_BUFFER.clone();
                            undoManager.addBuffer(TALL_WATERLEVEL_BUFFER_KEY, tallWaterLevel, this);
                        }
                        break;
                    case LAYER_DATA:
                        layerData = undoManager.getBufferForEditing(LAYER_DATA_BUFFER_KEY);
                        if (layerData == DEFAULT_LAYER_DATA_BUFFER) {
                            layerData = new HashMap<>();
                            undoManager.addBuffer(LAYER_DATA_BUFFER_KEY, layerData, this);
                        }
                        break;
                    case BIT_LAYER_DATA:
                        bitLayerData = undoManager.getBufferForEditing(BIT_LAYER_DATA_BUFFER_KEY);
                        if (bitLayerData == DEFAULT_BIT_LAYER_DATA_BUFFER) {
                            bitLayerData = new HashMap<>();
                            undoManager.addBuffer(BIT_LAYER_DATA_BUFFER_KEY, bitLayerData, this);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Not a shareable tile buffer: " + buffer);
                }
                readableBuffers.add(buffer);
                writeableBuffers.add(buffer);
            }
            return;
        }
        if ((undoManager != null) && (! writeableBuffers.contains(buffer))) {
            switch (buffer) {
                case HEIGHTMAP:
                    heightMap = undoManager.getBufferForEditing(HEIGHTMAP_BUFFER_KEY);
                    break;
                case TALL_HEIGHTMAP:
                    tallHeightMap = undoManager.getBufferForEditing(TALL_HEIGHTMAP_BUFFER_KEY);
                    break;
                case TERRAIN:
                    terrain = undoManager.getBufferForEditing(TERRAIN_BUFFER_KEY);
                    break;
                case WATERLEVEL:
                    waterLevel = undoManager.getBufferForEditing(WATERLEVEL_BUFFER_KEY);
                    break;
                case TALL_WATERLEVEL:
                    tallWaterLevel = undoManager.getBufferForEditing(TALL_WATERLEVEL_BUFFER_KEY);
                    break;
                case LAYER_DATA:
                    layerData = undoManager.getBufferForEditing(LAYER_DATA_BUFFER_KEY);
                    break;
                case BIT_LAYER_DATA:
                    bitLayerData = undoManager.getBufferForEditing(BIT_LAYER_DATA_BUFFER_KEY);
                    break;
                case SEEDS:
                    seeds = undoManager.getBufferForEditing(SEEDS_BUFFER_KEY);
                    break;
            }
            readableBuffers.add(buffer);
            writeableBuffers.add(buffer);
        }
    }

    public void invertLayer(Layer layer) { editWholeLayer(layer, true, 0); }

    public void fillTerrain(Terrain value) {
        Objects.requireNonNull(value);
        if (fillTerrainNative(value)) return;
        for (int x = 0; x < TILE_SIZE; x++) for (int y = 0; y < TILE_SIZE; y++) {
            if (getTerrain(x, y) != value) setTerrain(x, y, value);
        }
    }

    synchronized boolean fillTerrainNative(Terrain value) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        ensureReadable(TERRAIN);
        byte ordinal = (byte) value.ordinal();
        boolean changed = false;
        for (byte cell : terrain) if (cell != ordinal) { changed = true; break; }
        if (!changed) return true;
        ByteBuffer buffer = LayerEditAccess.constant(128, 8, value.ordinal());
        if (buffer == null) return false;
        ensureWriteable(TERRAIN);
        buffer.position(40); buffer.get(terrain);
        terrainChanged();
        return true;
    }

    public void assignLayerValue(Layer layer, int value) {
        DataSize size = layer.dataSize;
        if (size != DataSize.BIT && size != DataSize.BIT_PER_CHUNK && size != NIBBLE && size != BYTE)
            throw new UnsupportedOperationException("Unsupported layer storage: " + size);
        if (value < 0 || value > size.maxValue) throw new IllegalArgumentException("Invalid layer value");
        if (assignLayerNative(layer, value)) return;
        int step = size == DataSize.BIT_PER_CHUNK ? 16 : 1;
        for (int x = 0; x < TILE_SIZE; x += step) for (int y = 0; y < TILE_SIZE; y += step) {
            if (size == DataSize.BIT || size == DataSize.BIT_PER_CHUNK) setBitLayerValue(layer, x, y, value != 0);
            else setLayerValue(layer, x, y, value);
        }
    }

    synchronized void editMaskedRegion(Layer layer, Terrain terrainValue, int value, int x, int y, int width, int height,
                                       byte[] mask, int offset, int stride) {
        checkHeightRegion(y, x, height, width, mask.length, offset, stride);
        if (editMaskedRegionNative(layer, terrainValue, value, x, y, width, height, mask, offset, stride)) return;
        boolean bit = layer != null && (layer.dataSize == DataSize.BIT || layer.dataSize == DataSize.BIT_PER_CHUNK);
        for (int dy = 0; dy < height; dy++) for (int dx = 0; dx < width; dx++) if (mask[offset + dy * stride + dx] != 0) {
            if (layer == null) setTerrain(x + dx, y + dy, terrainValue);
            else if (bit) setBitLayerValue(layer, x + dx, y + dy, value != 0);
            else setLayerValue(layer, x + dx, y + dy, value);
        }
    }

    synchronized boolean editMaskedRegionNative(Layer layer, Terrain terrainValue, int value, int x, int y, int width, int height,
                                                byte[] mask, int offset, int stride) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        checkHeightRegion(y, x, height, width, mask.length, offset, stride);
        boolean bit = layer != null && (layer.dataSize == DataSize.BIT || layer.dataSize == DataSize.BIT_PER_CHUNK);
        int bits = layer == null || layer.dataSize == BYTE ? 8 : bit ? 1 : layer.dataSize == NIBBLE ? 4 : 0;
        if (bits == 0 || layer != null && (value < 0 || value > layer.dataSize.maxValue
                || !bit && (layer.getDefaultValue() < 0 || layer.getDefaultValue() > layer.dataSize.maxValue))) return false;
        ensureReadable(layer == null ? TERRAIN : bit ? BIT_LAYER_DATA : LAYER_DATA);
        if (layer != null && (bit ? !bitLayerData.containsKey(layer) && value == 0
                : !layerData.containsKey(layer) && value == layer.getDefaultValue())) return true;
        int side = layer != null && layer.dataSize == DataSize.BIT_PER_CHUNK ? 8 : 128;
        ByteBuffer buffer = MaskedPlaneAccess.edit(x, y, width, height, bits, side,
                layer == null ? terrainValue.ordinal() : value,
                layer == null ? terrain : bit ? null : layerData.get(layer), bit ? bitLayerData.get(layer) : null,
                layer == null || bit ? 0 : layer.getDefaultValue(), mask, offset, stride);
        if (buffer == null) return false;
        if (buffer.getInt(36) == 0) return true;
        ensureWriteable(layer == null ? TERRAIN : bit ? BIT_LAYER_DATA : LAYER_DATA);
        if (layer == null) {
            buffer.position(48); buffer.get(terrain); terrainChanged();
        } else {
            if (bit) bitLayerData.put(layer, SelectionTileAccess.applyBits(buffer, 48, side * side / 8, bitLayerData.get(layer)));
            else applyNumericLayerPlane(layer, buffer, 48, 16384 * bits / 8);
            cachedLayers = null; layerDataChanged(layer);
        }
        return true;
    }

    synchronized boolean assignLayerNative(Layer layer, int value) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        DataSize size = layer.dataSize;
        boolean bit = size == DataSize.BIT || size == DataSize.BIT_PER_CHUNK;
        if ((!bit && size != NIBBLE && size != BYTE) || value < 0 || value > size.maxValue) return false;
        ensureReadable(bit ? BIT_LAYER_DATA : LAYER_DATA);
        // Les setters ne créent pas de couche absente quand la valeur est celle par défaut.
        if (bit ? !bitLayerData.containsKey(layer) && value == 0
                : !layerData.containsKey(layer) && value == layer.getDefaultValue()) return true;
        ByteBuffer buffer = LayerEditAccess.constant(size == DataSize.BIT_PER_CHUNK ? 8 : 128,
                bit ? 1 : size == NIBBLE ? 4 : 8, value);
        if (buffer == null) return false;
        applyLayerEdit(layer, bit, buffer);
        return true;
    }

    public void resetFluids(int level, boolean lava) {
        if (resetFluidsNative(level, lava)) return;
        if (!lava) clearLayerData(FloodWithLava.INSTANCE);
        for (int x = 0; x < TILE_SIZE; x++) for (int y = 0; y < TILE_SIZE; y++) {
            setWaterLevel(x, y, level);
            if (lava) setBitLayerValue(FloodWithLava.INSTANCE, x, y, true);
        }
    }

    synchronized void editFluidRegion(int x, int y, int width, int height, float[] strengths,
                                      int offset, int stride, boolean reset, int level) {
        checkHeightRegion(x, y, width, height, strengths.length, offset, stride);
        if (!editFluidRegionNative(x, y, width, height, strengths, offset, stride, reset, level)) {
            for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
                if (strengths[offset + dx * stride + dy] == 0f) continue;
                setWaterLevel(x + dx, y + dy, level);
                if (reset) setBitLayerValue(FloodWithLava.INSTANCE, x + dx, y + dy, false);
            }
        }
    }

    synchronized void editNibbleRegion(Layer layer, int x, int y, int width, int height,
                                       float[] strengths, int offset, int stride, int mode) {
        checkHeightRegion(y, x, height, width, strengths.length, offset, stride);
        if (editNibbleRegionNative(layer, x, y, width, height, strengths, offset, stride, mode)) return;
        for (int dy = 0; dy < height; dy++) for (int dx = 0; dx < width; dx++) {
            int current = getLayerValue(layer, x + dx, y + dy);
            float strength = strengths[offset + dy * stride + dx];
            if (strength == 0f) continue;
            int target = NibbleBrushAccess.target(mode, strength);
            if (mode == 0 ? target > current : target < current) setLayerValue(layer, x + dx, y + dy, target);
        }
    }

    synchronized void editCombinedNibbleRegion(Layer layer, Terrain terrainValue, int biomeValue, int x, int y, int width, int height,
                                               float[] strengths, byte[] mask, int offset, int stride, int mode) {
        checkHeightRegion(y, x, height, width, strengths.length, offset, stride);
        checkHeightRegion(y, x, height, width, mask.length, offset, stride);
        ensureReadable(LAYER_DATA); if (terrainValue != null) ensureReadable(TERRAIN);
        boolean biomePresent = layerData.containsKey(Biome.INSTANCE);
        ByteBuffer buffer = NibbleBrushAccess.editCombined(x, y, width, height, strengths, mask, offset, stride, mode,
                layerData.get(layer), layer.getDefaultValue(), terrainValue == null ? null : terrain,
                biomeValue < 0 ? null : layerData.get(Biome.INSTANCE), terrainValue == null ? -1 : terrainValue.ordinal(), biomeValue);
        if (buffer == null) {
            editNibbleRegion(layer, x, y, width, height, strengths, offset, stride, mode);
            for (int dy = 0; dy < height; dy++) for (int dx = 0; dx < width; dx++) if (mask[offset + dy * stride + dx] != 0) {
                if (terrainValue != null) setTerrain(x + dx, y + dy, terrainValue);
                if (biomeValue >= 0) setLayerValue(Biome.INSTANCE, x + dx, y + dy, biomeValue);
            }
            return;
        }
        if (buffer.getInt(28) != 0) {
            ensureWriteable(LAYER_DATA); applyNumericLayerPlane(layer, buffer, 64, 8192);
            cachedLayers = null; layerDataChanged(layer);
        }
        if (buffer.getInt(44) != 0) {
            if (terrainValue != null) {
                ensureWriteable(TERRAIN); buffer.position(8256); buffer.get(terrain); terrainChanged();
            }
            if (biomeValue >= 0 && (biomePresent || biomeValue != Biome.INSTANCE.getDefaultValue())) {
                ensureWriteable(LAYER_DATA); applyNumericLayerPlane(Biome.INSTANCE, buffer, 8256 + (terrainValue != null ? 16384 : 0), 16384);
                cachedLayers = null; layerDataChanged(Biome.INSTANCE);
            }
        }
    }

    synchronized boolean editNibbleRegionNative(Layer layer, int x, int y, int width, int height,
                                                float[] strengths, int offset, int stride, int mode) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0 || layer.dataSize != NIBBLE
                || layer.getDefaultValue() < 0 || layer.getDefaultValue() > 15
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        checkHeightRegion(y, x, height, width, strengths.length, offset, stride);
        ensureReadable(LAYER_DATA);
        ByteBuffer buffer = NibbleBrushAccess.edit(x, y, width, height, strengths, offset, stride,
                mode, layerData.get(layer), layer.getDefaultValue());
        if (buffer == null) return false;
        if (buffer.getInt(28) == 0) return true;
        ensureWriteable(LAYER_DATA);
        applyNumericLayerPlane(layer, buffer, 48, 8192);
        cachedLayers = null;
        layerDataChanged(layer);
        return true;
    }

    synchronized boolean editFluidRegionNative(int x, int y, int width, int height, float[] strengths,
                                               int offset, int stride, boolean reset, int level) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        checkHeightRegion(x, y, width, height, strengths.length, offset, stride);
        ensureReadable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        if (reset) ensureReadable(BIT_LAYER_DATA);
        boolean hasLava = reset && bitLayerData.containsKey(FloodWithLava.INSTANCE);
        FluidBrushAccess.Scratch result = FluidBrushAccess.edit(x, y, width, height, strengths, offset, stride,
                level - minHeight, reset, tall ? null : waterLevel, tall ? tallWaterLevel : null,
                hasLava ? bitLayerData.get(FloodWithLava.INSTANCE) : null);
        if (result == null) return false;
        if (result.buffer.getInt(36) == 0) return true;
        ensureWriteable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        result.copyWater(x, y, width, height, tall ? null : waterLevel, tall ? tallWaterLevel : null);
        waterLevelChanged();
        if (hasLava) {
            ensureWriteable(BIT_LAYER_DATA);
            bitLayerData.put(FloodWithLava.INSTANCE, SelectionTileAccess.applyBits(result.buffer,
                    result.buffer.getInt(48), 2048, bitLayerData.get(FloodWithLava.INSTANCE)));
            layerDataChanged(FloodWithLava.INSTANCE);
        }
        return true;
    }

    public void editTerrainHeight(TerrainHeightOperation operation, float value, int minClamp, int maxClamp) {
        Objects.requireNonNull(operation);
        if (editTerrainHeightNative(operation, value, minClamp, maxClamp)) return;
        for (int x = 0; x < TILE_SIZE; x++) for (int y = 0; y < TILE_SIZE; y++) {
            if (operation == TerrainHeightOperation.SET) { setHeight(x, y, value); continue; }
            float current = getHeight(x, y);
            float target = switch (operation) {
                case SET, RAISE_TO, LOWER_TO -> value;
                case RAISE_BY -> Math.min(current + value, maxClamp);
                case LOWER_BY -> Math.max(current - value, minClamp);
            };
            boolean write = switch (operation) {
                case SET -> true;
                case RAISE_TO, RAISE_BY -> current < target;
                case LOWER_TO, LOWER_BY -> current > target;
            };
            if (write) setHeight(x, y, target);
        }
    }

    synchronized boolean editTerrainHeightNative(TerrainHeightOperation operation, float value, int minClamp, int maxClamp) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0 || minClamp > maxClamp
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        ensureReadable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        HeightPlaneAccess.Scratch result = HeightPlaneAccess.edit(minHeight, minClamp, maxClamp, operation, value,
                tall ? null : heightMap, tall ? tallHeightMap : null);
        if (result == null) return false;
        if (result.buffer.getInt(32) == 0) return true;
        ensureWriteable(tall ? TALL_HEIGHTMAP : HEIGHTMAP);
        if (tall) result.copy(tallHeightMap); else result.copy(heightMap);
        heightMapChanged();
        return true;
    }

    synchronized boolean resetFluidsNative(int level, boolean lava) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        ByteBuffer buffer = LayerEditAccess.resetFluids(tall, level - minHeight, lava);
        if (buffer == null) return false;
        if (!lava) clearLayerData(FloodWithLava.INSTANCE);
        ensureWriteable(tall ? TALL_WATERLEVEL : WATERLEVEL);
        buffer.position(64);
        if (tall) LayerEditAccess.copyFluidWater(buffer, tallWaterLevel);
        else buffer.get(waterLevel);
        if (lava) {
            ensureWriteable(BIT_LAYER_DATA);
            bitLayerData.put(FloodWithLava.INSTANCE, SelectionTileAccess.applyBits(buffer,
                    64 + (tall ? 32768 : 16384), 2048, bitLayerData.get(FloodWithLava.INSTANCE)));
            cachedLayers = null;
            layerDataChanged(FloodWithLava.INSTANCE);
        }
        waterLevelChanged();
        return true;
    }

    public void raiseLayerTo(Layer layer, int minimum) { editWholeLayer(layer, false, minimum); }

    private void editWholeLayer(Layer layer, boolean invert, int minimum) {
        DataSize size = layer.dataSize;
        if (size != DataSize.BIT && size != DataSize.BIT_PER_CHUNK && size != NIBBLE && size != BYTE)
            throw new UnsupportedOperationException("Unsupported layer storage: " + size);
        if (minimum < 0 || minimum > size.maxValue) throw new IllegalArgumentException("Invalid minimum layer value");
        if (editLayerNative(layer, invert, minimum)) return;
        int step = size == DataSize.BIT_PER_CHUNK ? 16 : 1;
        for (int x = 0; x < TILE_SIZE; x += step) for (int y = 0; y < TILE_SIZE; y += step) {
            if (size == DataSize.BIT || size == DataSize.BIT_PER_CHUNK) {
                boolean previous = getBitLayerValue(layer, x, y);
                if (invert || (!previous && minimum != 0)) setBitLayerValue(layer, x, y, invert ? !previous : true);
            } else {
                int previous = getLayerValue(layer, x, y);
                if (invert || previous < minimum) setLayerValue(layer, x, y, invert ? size.maxValue - previous : minimum);
            }
        }
    }

    synchronized boolean editLayerNative(Layer layer, boolean invert, int minimum) {
        if (getClass() != Tile.class || eventInhibitionCounter == 0
                || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return false;
        boolean bit = layer.dataSize == DataSize.BIT || layer.dataSize == DataSize.BIT_PER_CHUNK;
        if (!bit && layer.dataSize != NIBBLE && layer.dataSize != BYTE) return false;
        if (!bit && (layer.getDefaultValue() < 0 || layer.getDefaultValue() > layer.dataSize.maxValue)) return false;
        ensureReadable(bit ? BIT_LAYER_DATA : LAYER_DATA);
        ByteBuffer buffer = LayerEditAccess.edit(layer, invert, minimum,
                bit ? null : layerData.get(layer), bit ? bitLayerData.get(layer) : null);
        if (buffer == null) return false;
        if (buffer.getInt(36) == 0) return true;
        applyLayerEdit(layer, bit, buffer);
        return true;
    }

    private void applyLayerEdit(Layer layer, boolean bit, ByteBuffer buffer) {
        ensureWriteable(bit ? BIT_LAYER_DATA : LAYER_DATA);
        int bytes = buffer.limit() - 40;
        if (bit) {
            bitLayerData.put(layer, SelectionTileAccess.applyBits(buffer, 40, bytes, bitLayerData.get(layer)));
        } else {
            applyNumericLayerPlane(layer, buffer, 40, bytes);
        }
        cachedLayers = null;
        layerDataChanged(layer);
    }

    private void applyNumericLayerPlane(Layer layer, ByteBuffer buffer, int offset, int bytes) {
        byte[] values = layerData.get(layer);
        if (values == null) { values = new byte[bytes]; layerData.put(layer, values); }
        else { values = detachSharedLayerDataBuffer(layer, values); }
        buffer.position(offset); buffer.get(values);
    }

    private boolean isSharedBuffer(TileBuffer buffer) {
        switch (buffer) {
            case HEIGHTMAP:
                return isSharedUniformHeightMapBuffer(heightMap);
            case TALL_HEIGHTMAP:
                return isSharedUniformTallHeightMapBuffer(tallHeightMap);
            case TERRAIN:
                return terrain == DEFAULT_TERRAIN_BUFFER;
            case WATERLEVEL:
                return isSharedUniformWaterLevelBuffer(waterLevel);
            case TALL_WATERLEVEL:
                return tallWaterLevel == DEFAULT_TALL_WATERLEVEL_BUFFER;
            case LAYER_DATA:
                return layerData == DEFAULT_LAYER_DATA_BUFFER;
            case BIT_LAYER_DATA:
                return bitLayerData == DEFAULT_BIT_LAYER_DATA_BUFFER;
            default:
                return false;
        }
    }

    private void copySharedBuffer(TileBuffer buffer) {
        switch (buffer) {
            case HEIGHTMAP:
                heightMap = heightMap.clone();
                break;
            case TALL_HEIGHTMAP:
                tallHeightMap = tallHeightMap.clone();
                break;
            case TERRAIN:
                terrain = DEFAULT_TERRAIN_BUFFER.clone();
                break;
            case WATERLEVEL:
                waterLevel = waterLevel.clone();
                break;
            case TALL_WATERLEVEL:
                tallWaterLevel = DEFAULT_TALL_WATERLEVEL_BUFFER.clone();
                break;
            case LAYER_DATA:
                layerData = new HashMap<>();
                break;
            case BIT_LAYER_DATA:
                bitLayerData = new HashMap<>();
                break;
            default:
                throw new IllegalArgumentException("Not a shareable tile buffer: " + buffer);
        }
    }

    private void setSharedHeightMapBuffer(short[] sharedBuffer) {
        if (undoManager == null) {
            heightMap = sharedBuffer;
        } else {
            undoManager.getBufferForEditing(HEIGHTMAP_BUFFER_KEY);
            heightMap = sharedBuffer;
            undoManager.addBuffer(HEIGHTMAP_BUFFER_KEY, sharedBuffer, this);
            readableBuffers.add(HEIGHTMAP);
            writeableBuffers.add(HEIGHTMAP);
        }
    }

    private void setSharedTallHeightMapBuffer(int[] sharedBuffer) {
        if (undoManager == null) {
            tallHeightMap = sharedBuffer;
        } else {
            undoManager.getBufferForEditing(TALL_HEIGHTMAP_BUFFER_KEY);
            tallHeightMap = sharedBuffer;
            undoManager.addBuffer(TALL_HEIGHTMAP_BUFFER_KEY, sharedBuffer, this);
            readableBuffers.add(TALL_HEIGHTMAP);
            writeableBuffers.add(TALL_HEIGHTMAP);
        }
    }

    private void setSharedWaterLevelBuffer(byte[] sharedBuffer) {
        if (undoManager == null) {
            waterLevel = sharedBuffer;
        } else {
            undoManager.getBufferForEditing(WATERLEVEL_BUFFER_KEY);
            waterLevel = sharedBuffer;
            undoManager.addBuffer(WATERLEVEL_BUFFER_KEY, sharedBuffer, this);
            readableBuffers.add(WATERLEVEL);
            writeableBuffers.add(WATERLEVEL);
        }
    }

    private void setSharedTallWaterLevelBuffer(short[] sharedBuffer) {
        if (undoManager == null) {
            tallWaterLevel = sharedBuffer;
        } else {
            undoManager.getBufferForEditing(TALL_WATERLEVEL_BUFFER_KEY);
            tallWaterLevel = sharedBuffer;
            undoManager.addBuffer(TALL_WATERLEVEL_BUFFER_KEY, sharedBuffer, this);
            readableBuffers.add(TALL_WATERLEVEL);
            writeableBuffers.add(TALL_WATERLEVEL);
        }
    }

    private byte[] detachSharedLayerDataBuffer(Layer layer, byte[] buffer) {
        if (isSharedLayerDataBuffer(buffer)) {
            buffer = buffer.clone();
            layerData.put(layer, buffer);
            if (undoManager != null) {
                undoManager.addBuffer(LAYER_DATA_BUFFER_KEY, layerData, this);
            }
        }
        return buffer;
    }

    private static short[] uniformHeightMapBuffer(short value) {
        synchronized (UNIFORM_HEIGHTMAP_BUFFER_LOCK) {
            final int key = value & 0xFFFF;
            if (cachedUniformHeightMapValue != key) {
                if (value == 0) {
                    cachedUniformHeightMapBuffer = DEFAULT_HEIGHTMAP_BUFFER;
                } else {
                    final short[] buffer = new short[TILE_SIZE * TILE_SIZE];
                    Arrays.fill(buffer, value);
                    synchronized (SHARED_HEIGHTMAP_BUFFERS) {
                        SHARED_HEIGHTMAP_BUFFERS.add(buffer);
                    }
                    cachedUniformHeightMapBuffer = buffer;
                }
                cachedUniformHeightMapValue = key;
            }
            return cachedUniformHeightMapBuffer;
        }
    }

    private static int[] uniformTallHeightMapBuffer(int value) {
        synchronized (UNIFORM_HEIGHTMAP_BUFFER_LOCK) {
            if (cachedUniformTallHeightMapValue != value) {
                if (value == 0) {
                    cachedUniformTallHeightMapBuffer = DEFAULT_TALL_HEIGHTMAP_BUFFER;
                } else {
                    final int[] buffer = new int[TILE_SIZE * TILE_SIZE];
                    Arrays.fill(buffer, value);
                    synchronized (SHARED_TALL_HEIGHTMAP_BUFFERS) {
                        SHARED_TALL_HEIGHTMAP_BUFFERS.add(buffer);
                    }
                    cachedUniformTallHeightMapBuffer = buffer;
                }
                cachedUniformTallHeightMapValue = value;
            }
            return cachedUniformTallHeightMapBuffer;
        }
    }

    private static short[] internUniformHeightMapBuffer(short[] values) {
        if (isSharedUniformHeightMapBuffer(values) || (values.length != TILE_SIZE * TILE_SIZE)) {
            return values;
        }
        final short value = values[0];
        for (int index = 1; index < values.length; index++) {
            if (values[index] != value) {
                return values;
            }
        }
        return uniformHeightMapBuffer(value);
    }

    private static int[] internUniformTallHeightMapBuffer(int[] values) {
        if (isSharedUniformTallHeightMapBuffer(values) || (values.length != TILE_SIZE * TILE_SIZE)) {
            return values;
        }
        final int value = values[0];
        for (int index = 1; index < values.length; index++) {
            if (values[index] != value) {
                return values;
            }
        }
        return uniformTallHeightMapBuffer(value);
    }

    private static boolean isSharedUniformHeightMapBuffer(short[] buffer) {
        if (buffer == null) {
            return false;
        }
        synchronized (SHARED_HEIGHTMAP_BUFFERS) {
            return SHARED_HEIGHTMAP_BUFFERS.contains(buffer);
        }
    }

    private static boolean isSharedUniformTallHeightMapBuffer(int[] buffer) {
        if (buffer == null) {
            return false;
        }
        synchronized (SHARED_TALL_HEIGHTMAP_BUFFERS) {
            return SHARED_TALL_HEIGHTMAP_BUFFERS.contains(buffer);
        }
    }

    private static byte[] uniformWaterLevelBuffer(byte value) {
        return UNIFORM_WATERLEVEL_BUFFER_CACHE.computeIfAbsent(value & 0xFF, rawValue -> {
            final byte[] buffer = new byte[TILE_SIZE * TILE_SIZE];
            Arrays.fill(buffer, (byte) rawValue.intValue());
            SHARED_WATERLEVEL_BUFFERS.add(buffer);
            return buffer;
        });
    }

    private static byte[] internUniformWaterLevelBuffer(byte[] values) {
        if (isSharedUniformWaterLevelBuffer(values)) {
            return values;
        }
        if (values.length != TILE_SIZE * TILE_SIZE) {
            return values;
        }
        final byte value = values[0];
        for (int index = 1; index < values.length; index++) {
            if (values[index] != value) {
                return values;
            }
        }
        return uniformWaterLevelBuffer(value);
    }

    private static boolean isSharedUniformWaterLevelBuffer(byte[] buffer) {
        return (buffer != null) && SHARED_WATERLEVEL_BUFFERS.contains(buffer);
    }

    private static byte[] uniformLayerDataBuffer(int length, byte value, byte[] candidate) {
        final long key = (((long) length) << 8) | (value & 0xFFL);
        synchronized (UNIFORM_LAYER_DATA_BUFFER_LOCK) {
            final WeakReference<byte[]> reference = UNIFORM_LAYER_DATA_BUFFER_CACHE.get(key);
            byte[] buffer = (reference != null) ? reference.get() : null;
            if (buffer == null) {
                buffer = (candidate != null) ? candidate : new byte[length];
                if (candidate == null) {
                    Arrays.fill(buffer, value);
                }
                UNIFORM_LAYER_DATA_BUFFER_CACHE.put(key, new WeakReference<>(buffer));
                SHARED_LAYER_DATA_BUFFERS.add(buffer);
            }
            return buffer;
        }
    }

    private static byte[] internUniformLayerDataBuffer(byte[] values) {
        if ((values == null) || isSharedLayerDataBuffer(values)
                || ((values.length != TILE_SIZE * TILE_SIZE)
                && (values.length != TILE_SIZE * TILE_SIZE / 2))) {
            return values;
        }
        final byte value = values[0];
        for (int index = 1; index < values.length; index++) {
            if (values[index] != value) {
                return values;
            }
        }
        return uniformLayerDataBuffer(values.length, value, values);
    }

    private static boolean isSharedLayerDataBuffer(byte[] buffer) {
        return (buffer != null) && SHARED_LAYER_DATA_BUFFERS.contains(buffer);
    }

    private static boolean isAllZero(byte[] values) {
        if (values.length != TILE_SIZE * TILE_SIZE) {
            return false;
        }
        for (byte value: values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(short[] values) {
        if (values.length != TILE_SIZE * TILE_SIZE) {
            return false;
        }
        for (short value: values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private void heightMapChanged() {
        if (eventInhibitionCounter != 0) {
            heightMapDirty = true;
        } else {
            for (Listener listener: listeners) {
                listener.heightMapChanged(this);
            }
        }
    }

    private void terrainChanged() {
        if (eventInhibitionCounter != 0) {
            terrainDirty = true;
        } else {
            for (Listener listener: listeners) {
                listener.terrainChanged(this);
            }
        }
    }

    private void waterLevelChanged() {
        if (eventInhibitionCounter != 0) {
            waterLevelDirty = true;
        } else {
            for (Listener listener: listeners) {
                listener.waterLevelChanged(this);
            }
        }
    }

    private void layerDataChanged(Layer layer) {
        if (eventInhibitionCounter != 0) {
            dirtyLayers.add(layer);
        } else {
            Set<Layer> changedLayers = Collections.singleton(layer);
            for (Listener listener: listeners) {
                listener.layerDataChanged(this, changedLayers);
            }
        }
    }

    private void allBitLayerDataChanged() {
        if (eventInhibitionCounter != 0) {
            bitLayersDirty = true;
        } else {
            for (Listener listener: listeners) {
                listener.allBitLayerDataChanged(this);
            }
        }
    }

    private void allNonBitLayerDataChanged() {
        if (eventInhibitionCounter != 0) {
            nonBitLayersDirty = true;
        } else {
            for (Listener listener: listeners) {
                listener.allNonBitlayerDataChanged(this);
            }
        }
    }
    
    private void seedsChanged() {
        if (eventInhibitionCounter != 0) {
            seedsDirty = true;
        } else {
            for (Listener listener: listeners) {
                listener.seedsChanged(this);
            }
        }
    }
    
    private float clamp(float level) {
        if (level < minHeight) {
            return minHeight;
        } else if (level > maxY) {
            return maxY;
        } else {
            return level;
        }
    }
    
    private int clamp(int level) {
        if (level < minHeight) {
            return minHeight;
        } else if (level > maxY) {
            return maxY;
        } else {
            return level;
        }
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        init();
    }
    
    @Serial
    private synchronized void writeObject(ObjectOutputStream out) throws IOException {
        prepareForSaving();
        final short[] currentHeightMap = heightMap;
        final int[] currentTallHeightMap = tallHeightMap;
        final byte[] currentTerrain = terrain;
        final byte[] currentWaterLevel = waterLevel;
        final short[] currentTallWaterLevel = tallWaterLevel;
        final Map<Layer, byte[]> currentLayerData = layerData;
        try {
            if (isSharedUniformHeightMapBuffer(currentHeightMap)) {
                heightMap = currentHeightMap.clone();
            }
            if (isSharedUniformTallHeightMapBuffer(currentTallHeightMap)) {
                tallHeightMap = currentTallHeightMap.clone();
            }
            if (currentTerrain == DEFAULT_TERRAIN_BUFFER) {
                // Keep serialized tiles independent for readers that do not implement this buffer sharing.
                terrain = currentTerrain.clone();
            }
            if (isSharedUniformWaterLevelBuffer(currentWaterLevel)) {
                waterLevel = currentWaterLevel.clone();
            }
            if (currentTallWaterLevel == DEFAULT_TALL_WATERLEVEL_BUFFER) {
                tallWaterLevel = currentTallWaterLevel.clone();
            }
            if ((currentLayerData != null) && !currentLayerData.isEmpty()) {
                Map<Layer, byte[]> serializedLayerData = null;
                for (Map.Entry<Layer, byte[]> entry : currentLayerData.entrySet()) {
                    if (isSharedLayerDataBuffer(entry.getValue())) {
                        if (serializedLayerData == null) {
                            serializedLayerData = new HashMap<>(currentLayerData);
                        }
                        serializedLayerData.put(entry.getKey(), entry.getValue().clone());
                    }
                }
                if (serializedLayerData != null) {
                    layerData = serializedLayerData;
                }
            }
            out.defaultWriteObject();
        } finally {
            heightMap = currentHeightMap;
            tallHeightMap = currentTallHeightMap;
            terrain = currentTerrain;
            waterLevel = currentWaterLevel;
            tallWaterLevel = currentTallWaterLevel;
            layerData = currentLayerData;
        }
    }

    private void init() {
        listeners = new ArrayList<>();
        HEIGHTMAP_BUFFER_KEY = new TileUndoBufferKey<>(this, HEIGHTMAP);
        TALL_HEIGHTMAP_BUFFER_KEY = new TileUndoBufferKey<>(this, TALL_HEIGHTMAP);
        TERRAIN_BUFFER_KEY = new TileUndoBufferKey<>(this, TERRAIN);
        WATERLEVEL_BUFFER_KEY = new TileUndoBufferKey<>(this, WATERLEVEL);
        TALL_WATERLEVEL_BUFFER_KEY = new TileUndoBufferKey<>(this, TALL_WATERLEVEL);
        LAYER_DATA_BUFFER_KEY = new TileUndoBufferKey<>(this, LAYER_DATA);
        BIT_LAYER_DATA_BUFFER_KEY = new TileUndoBufferKey<>(this, BIT_LAYER_DATA);
        SEEDS_BUFFER_KEY = new TileUndoBufferKey<>(this, SEEDS);
        dirtyLayers = new HashSet<>();
        maxY = maxHeight - 1;
        
        // Legacy map support
        if (maxHeight == 0) {
            maxHeight = 128;
            tall = false;
        }
        if (heightMap != null) {
            heightMap = internUniformHeightMapBuffer(heightMap);
        }
        if (tallHeightMap != null) {
            tallHeightMap = internUniformTallHeightMapBuffer(tallHeightMap);
        }
        if ((terrain != null) && (terrain != DEFAULT_TERRAIN_BUFFER) && isAllZero(terrain)) {
            terrain = DEFAULT_TERRAIN_BUFFER;
        }
        if (waterLevel != null) {
            waterLevel = internUniformWaterLevelBuffer(waterLevel);
        }
        if ((tallWaterLevel != null) && (tallWaterLevel != DEFAULT_TALL_WATERLEVEL_BUFFER) && isAllZero(tallWaterLevel)) {
            tallWaterLevel = DEFAULT_TALL_WATERLEVEL_BUFFER;
        }
        if ((layerData == null) || layerData.isEmpty()) {
            layerData = DEFAULT_LAYER_DATA_BUFFER;
        } else {
            Map<Layer, byte[]> sharedLayerData = null;
            for (Map.Entry<Layer, byte[]> entry : layerData.entrySet()) {
                final byte[] values = entry.getValue();
                final byte[] sharedValues = internUniformLayerDataBuffer(values);
                if (sharedValues != values) {
                    if (sharedLayerData == null) {
                        sharedLayerData = new HashMap<>(layerData);
                    }
                    sharedLayerData.put(entry.getKey(), sharedValues);
                }
            }
            if (sharedLayerData != null) {
                layerData = sharedLayerData;
            }
        }
        if ((bitLayerData == null) || bitLayerData.isEmpty()) {
            bitLayerData = DEFAULT_BIT_LAYER_DATA_BUFFER;
        }
        if ((seeds != null) && seeds.isEmpty()) {
            seeds = null;
        }
    }

    private final int x, y;
    private int minHeight, maxHeight;
    private boolean tall;
    protected short[] heightMap;
    protected int[] tallHeightMap;
    protected byte[] terrain;
    protected byte[] waterLevel;
    protected short[] tallWaterLevel; // TODO this is too small, no?
    protected Map<Layer, byte[]> layerData;
    protected Map<Layer, BitSet> bitLayerData;
    private HashSet<Seed> seeds;
    private transient List<Listener> listeners;
    private transient volatile boolean heightMapDirty, terrainDirty, waterLevelDirty, seedsDirty, bitLayersDirty, nonBitLayersDirty;
    private transient Set<TileBuffer> readableBuffers;
    private transient Set<TileBuffer> writeableBuffers;
    private transient UndoManager undoManager;
    private transient List<Layer> cachedLayers;
    private transient volatile Set<Layer> dirtyLayers;
    private transient int maxY;
    private transient volatile int eventInhibitionCounter;

    private transient BufferKey<short[]>            HEIGHTMAP_BUFFER_KEY;
    private transient BufferKey<int[]>              TALL_HEIGHTMAP_BUFFER_KEY;
    private transient BufferKey<byte[]>             TERRAIN_BUFFER_KEY;
    private transient BufferKey<byte[]>             WATERLEVEL_BUFFER_KEY;
    private transient BufferKey<short[]>            TALL_WATERLEVEL_BUFFER_KEY;
    private transient BufferKey<Map<Layer, byte[]>> LAYER_DATA_BUFFER_KEY;
    private transient BufferKey<Map<Layer, BitSet>> BIT_LAYER_DATA_BUFFER_KEY;
    private transient BufferKey<HashSet<Seed>>      SEEDS_BUFFER_KEY;
    
    private static final Terrain[] TERRAIN_VALUES = Terrain.values();
    private static final short[] DEFAULT_HEIGHTMAP_BUFFER = new short[TILE_SIZE * TILE_SIZE];
    private static final int[] DEFAULT_TALL_HEIGHTMAP_BUFFER = new int[TILE_SIZE * TILE_SIZE];
    private static final byte[] DEFAULT_TERRAIN_BUFFER = new byte[TILE_SIZE * TILE_SIZE];
    private static final Map<Layer, byte[]> DEFAULT_LAYER_DATA_BUFFER = Collections.emptyMap();
    private static final Map<Layer, BitSet> DEFAULT_BIT_LAYER_DATA_BUFFER = Collections.emptyMap();
    private static final byte[] DEFAULT_WATERLEVEL_BUFFER = new byte[TILE_SIZE * TILE_SIZE];
    private static final short[] DEFAULT_TALL_WATERLEVEL_BUFFER = new short[TILE_SIZE * TILE_SIZE];
    // Height-map sharing retains at most one non-zero buffer per tile height format.
    private static final Object UNIFORM_HEIGHTMAP_BUFFER_LOCK = new Object();
    private static final Set<short[]> SHARED_HEIGHTMAP_BUFFERS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<int[]> SHARED_TALL_HEIGHTMAP_BUFFERS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static short[] cachedUniformHeightMapBuffer = DEFAULT_HEIGHTMAP_BUFFER;
    private static int cachedUniformHeightMapValue;
    private static int[] cachedUniformTallHeightMapBuffer = DEFAULT_TALL_HEIGHTMAP_BUFFER;
    private static int cachedUniformTallHeightMapValue;
    // Normal water levels have only 256 raw values, so this cache has a fixed 4 MiB maximum.
    private static final ConcurrentMap<Integer, byte[]> UNIFORM_WATERLEVEL_BUFFER_CACHE = new ConcurrentHashMap<>();
    private static final Set<byte[]> SHARED_WATERLEVEL_BUFFERS = ConcurrentHashMap.newKeySet();
    private static final Object UNIFORM_LAYER_DATA_BUFFER_LOCK = new Object();
    private static final ConcurrentMap<Long, WeakReference<byte[]>> UNIFORM_LAYER_DATA_BUFFER_CACHE = new ConcurrentHashMap<>();
    private static final Set<byte[]> SHARED_LAYER_DATA_BUFFERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    static {
        SHARED_HEIGHTMAP_BUFFERS.add(DEFAULT_HEIGHTMAP_BUFFER);
        SHARED_TALL_HEIGHTMAP_BUFFERS.add(DEFAULT_TALL_HEIGHTMAP_BUFFER);
        UNIFORM_WATERLEVEL_BUFFER_CACHE.put(0, DEFAULT_WATERLEVEL_BUFFER);
        SHARED_WATERLEVEL_BUFFERS.add(DEFAULT_WATERLEVEL_BUFFER);
    }

    private static final float SQRT_OF_EIGHT = (float) Math.sqrt(8.0);
    
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Tile.class);
    
    @Serial
    private static final long serialVersionUID = 2011040101L;

    public interface Listener {
        void heightMapChanged(Tile tile);
        void terrainChanged(Tile tile);
        void waterLevelChanged(Tile tile);
        void layerDataChanged(Tile tile, Set<Layer> changedLayers);
        void allBitLayerDataChanged(Tile tile);
        void allNonBitlayerDataChanged(Tile tile);
        void seedsChanged(Tile tile);
    }

    static class TileUndoBufferKey<T> implements BufferKey<T> {
        public TileUndoBufferKey(Tile tile, TileBuffer buffer) {
            this.tile = tile;
            this.buffer = buffer;
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof TileUndoBufferKey)
                && (tile == ((TileUndoBufferKey<?>) obj).tile)
                && (buffer == ((TileUndoBufferKey<?>) obj).buffer);
        }

        @Override
        public int hashCode() {
            return (31 + System.identityHashCode(tile)) * 31 + buffer.hashCode();
        }

        @Override
        public String toString() {
            return "[" + tile.x + ", " + tile.y + ", " + buffer + "]";
        }

        final Tile tile;
        final TileBuffer buffer;
    }

    public enum TileBuffer {
        HEIGHTMAP, TERRAIN, WATERLEVEL, LAYER_DATA, BIT_LAYER_DATA, TALL_HEIGHTMAP, TALL_WATERLEVEL, SEEDS
    }
}
