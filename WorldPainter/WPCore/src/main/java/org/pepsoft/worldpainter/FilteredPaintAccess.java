package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.panels.EditorFilterPlan;
import org.pepsoft.worldpainter.panels.EditorFilterPlan.*;
import static org.pepsoft.worldpainter.panels.EditorFilterPlan.*;
import static org.pepsoft.worldpainter.biomeschemes.Minecraft1_21Biomes.*;

/**
 * Complete filtering and packed painting in one JNI call, with one shared worker buffer.
 * WFPT v1/v2/v3/v4 is little-endian: a 160-byte header, 48-byte program nodes with appended
 * combined-child indices, terrain biome palette, 8-byte packed-plane descriptors,
 * then 32-byte tile descriptors and contiguous tile payloads. Each payload contains
 * a full terrain plane, a 130x130 X-major float height halo, absolute water levels,
 * compact input layers and row-major brush strengths for its painted rectangle.
 * Version 2 adds output bits at byte 60, output plane at byte 152 and numeric mode
 * at byte 156 (apply, rounded removal, truncated removal); version 1 keeps terrain output.
 * Version 3 uses output bits 0/1 for chunk/block bits and mode 0/1 for apply/remove.
 * Version 4 writes constant nibble/byte values from byte 20, with mode 0.
 * Version 5 adds a 192-byte header with height mode/value/clamps/minimum/storage,
 * and per-tile X-major raw heights plus a mutation mask. Global X/Y edits update every
 * intersecting halo so later slope/auto-biome predicates observe quantised writes.
 * Descriptor state is -1 for a missing tile, otherwise the number of requested
 * paint setters, including unchanged values. Application preserves Java's COW
 * and coalesced events. Painting touches each cell once; predicates only read that
 * cell and immutable height neighbors, allowing the tile grouping used here.
 * Chunk bits alias multiple cells; row order inside each tile preserves those reads.
 */
public final class FilteredPaintAccess {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int HEADER = 160, TERRAIN_BYTES = 16384, HEIGHT_BYTES = 130 * 130 * 4;
    private static final Terrain[] TERRAINS = Terrain.values();
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch { ByteBuffer buffer; long completed; }
    /** Diagnostic count for parity and complete-operation benchmarks on the current worker. */
    public static long completedTransactions() { return SCRATCH.get().completed; }
    private static final Layer[] HELPERS = {Biome.INSTANCE, FloodWithLava.INSTANCE, SelectionBlock.INSTANCE,
            SelectionChunk.INSTANCE, Annotations.INSTANCE, Frost.INSTANCE, River.INSTANCE,
            DeciduousForest.INSTANCE, PineForest.INSTANCE, SwampLand.INSTANCE, Jungle.INSTANCE};
    private static final int[] BIOMES = {BIOME_FROZEN_RIVER, BIOME_COLD_TAIGA, BIOME_FROZEN_OCEAN,
            BIOME_ICE_PLAINS, BIOME_RIVER, BIOME_SWAMPLAND, BIOME_JUNGLE, BIOME_OCEAN, BIOME_DEEP_OCEAN, BIOME_FOREST};
    private FilteredPaintAccess() { }

    public static boolean apply(Dimension dimension, Terrain target, EditorFilterPlan plan,
                                int ox, int oy, int width, int height, float dynamic, float[] strengths) {
        return target != null && apply(dimension, target, null, plan, ox, oy, width, height, dynamic, strengths, 0);
    }

    public static boolean applyNibble(Dimension dimension, Layer layer, EditorFilterPlan plan,
                                      int ox, int oy, int width, int height, float dynamic, float[] strengths, int mode) {
        if (layer == null || layer.dataSize != Layer.DataSize.NIBBLE || layer.getDefaultValue() < 0
                || layer.getDefaultValue() > 15 || mode < 0 || mode > 2) return false;
        return apply(dimension, null, layer, plan, ox, oy, width, height, dynamic, strengths, mode);
    }

    public static boolean applyBit(Dimension dimension, Layer layer, EditorFilterPlan plan,
                                   int ox, int oy, int width, int height, float dynamic, float[] strengths, boolean value) {
        if (layer == null || (layer.dataSize != Layer.DataSize.BIT && layer.dataSize != Layer.DataSize.BIT_PER_CHUNK)) return false;
        return apply(dimension, null, layer, plan, ox, oy, width, height, dynamic, strengths, value ? 0 : 1);
    }

    private static boolean apply(Dimension dimension, Terrain target, Layer outputLayer, EditorFilterPlan plan,
                                  int ox, int oy, int width, int height, float dynamic, float[] strengths, int mode) {
        return apply(dimension, target, outputLayer, plan, ox, oy, width, height, dynamic, strengths, mode, null);
    }

    public static boolean applyDiscrete(Dimension dimension, Layer layer, int value, EditorFilterPlan plan,
                                        int ox, int oy, int width, int height, float dynamic, float[] strengths) {
        if (layer == null || (layer.dataSize != Layer.DataSize.NIBBLE && layer.dataSize != Layer.DataSize.BYTE)
                || value < 0 || value >= (1 << bits(layer))) return false;
        return apply(dimension, null, layer, plan, ox, oy, width, height, dynamic, strengths, 0, value);
    }

    /** Complete unthemed height edits use row-major unfiltered strengths and evolving global X/Y reads. */
    public static boolean applyHeight(Dimension dimension, EditorFilterPlan plan, int ox,int oy,int width,int height,
                                      float[] strengths,int mode,float value,float low,float high){
        return applyHeight(dimension,plan,ox,oy,width,height,strengths,mode,value,low,high,1f);
    }
    public static boolean applyHeight(Dimension dimension, EditorFilterPlan plan, int ox,int oy,int width,int height,
                                      float[] strengths,int mode,float value,float low,float high,float dynamic){
        if(mode<0||mode>4)return false;
        return apply(dimension,null,null,plan,ox,oy,width,height,dynamic,strengths,0,null,new HeightEdit(mode,value,low,high));
    }
    private record HeightEdit(int mode,float value,float low,float high) { }
    private static boolean apply(Dimension dimension, Terrain target, Layer outputLayer, EditorFilterPlan plan,
                                  int ox, int oy, int width, int height, float dynamic, float[] strengths, int mode, Integer fixed) {
        return apply(dimension,target,outputLayer,plan,ox,oy,width,height,dynamic,strengths,mode,fixed,null);
    }
    private static boolean apply(Dimension dimension, Terrain target, Layer outputLayer, EditorFilterPlan plan,
                                  int ox, int oy, int width, int height, float dynamic, float[] strengths, int mode, Integer fixed,HeightEdit edit) {        if (plan == null || width <= 0 || height <= 0 || width > 256 || height > 256
                || (long) width * height != strengths.length || TERRAINS.length > 256
                || ox < Integer.MIN_VALUE + 256 || oy < Integer.MIN_VALUE + 256
                || (long) ox + width > Integer.MAX_VALUE - 256 || (long) oy + height > Integer.MAX_VALUE - 256
                || !TileRegionAccess.canBatch(dimension, ox, oy, width, height)) return false;
        int tx1 = ox >> 7, ty1 = oy >> 7, tx2 = (ox + width - 1) >> 7, ty2 = (oy + height - 1) >> 7;
        // Height halos must also respect overrides in neighboring tile subclasses.
        for (int ty = ty1 - 1; ty <= ty2 + 1; ty++) for (int tx = tx1 - 1; tx <= tx2 + 1; tx++) {
            Tile tile = dimension.getTile(tx, ty);
            if (tile != null && tile.getClass() != Tile.class) return false;
        }
        List<Layer> layers = new ArrayList<>(plan.layers());
        int outputPlane = -1;
        if (outputLayer != null) {
            outputPlane = layers.indexOf(outputLayer);
            if (outputPlane < 0) { outputPlane = layers.size(); layers.add(outputLayer); }
        }
        int dependencies = plan.dependencies();
        boolean auto = (dependencies & AUTO_BIOME) != 0;
        int[] helpers = new int[HELPERS.length];
        for (int i = 0; i < helpers.length; i++) {
            boolean needed = switch (i) {
                case 0 -> (dependencies & BIOME) != 0;
                case 1 -> auto || (dependencies & LAVA) != 0;
                case 2, 3 -> (dependencies & SELECTION) != 0;
                case 4 -> (dependencies & ANNOTATIONS) != 0;
                default -> auto;
            };
            helpers[i] = -1;
            if (needed) {
                int index = layers.indexOf(HELPERS[i]);
                if (index < 0) { index = layers.size(); layers.add(HELPERS[i]); }
                helpers[i] = index;
            }
        }
        int header=edit==null?HEADER:192;
        if(edit!=null&&(long)dimension.getMaxHeight()-dimension.getMinHeight()>65536)return false;
        int programBytes = plan.encodedBytes();
        int planeDefinitions = header + programBytes + TERRAINS.length * 8;
        int tileDefinitions = planeDefinitions + layers.size() * 8;
        int tileCount = (tx2 - tx1 + 1) * (ty2 - ty1 + 1);
        int payload = tileDefinitions + tileCount * 32;
        int planeEnd = TERRAIN_BYTES + HEIGHT_BYTES + 16384 * 4;
        for (Layer layer : layers) planeEnd += bytes(bits(layer));
        int heightOutput=planeEnd;
        if(edit!=null)planeEnd+=16384*5;
        long required = (long) payload + (long) tileCount * planeEnd + (long) width * height * 4;
        if (layers.size() > 128 || tileCount > 9 || required > MAX_BYTES) return false;
        Scratch scratch = SCRATCH.get();
        ByteBuffer data = scratch.buffer;
        if (data == null || data.capacity() < required) {
            data = ByteBuffer.allocateDirect((int) required).order(ByteOrder.LITTLE_ENDIAN);
            scratch.buffer = data;
        }
        data.clear().limit((int) required);
        for (int i = 0; i < header; i += 8) data.putLong(i, 0);
        int outputBits = outputLayer == null ? 8 : bits(outputLayer);
        data.putInt(0, 0x54504657).putInt(4, edit!=null?5:fixed != null ? 4 : outputLayer == null ? 1 : outputBits == 4 ? 2 : 3).putInt(8, plan.nodes().size()).putInt(12, layers.size())
                .putInt(16, tileCount).putInt(20, fixed != null ? fixed : target == null ? 0 : target.ordinal()).putFloat(24, dynamic)
                .putInt(28, header + programBytes).putInt(32, TERRAINS.length)
                .putInt(36, dimension.getAnchor().dim == -1 ? BIOME_HELL : dimension.getAnchor().dim == 1 ? BIOME_SKY : -1)
                .putInt(40, planeDefinitions).putInt(44, tileDefinitions).putInt(48, payload)
                .putInt(52, dependencies).putInt(56, Terrain.WATER.ordinal());
        if (outputLayer != null) data.putInt(60, outputBits).putInt(152, outputPlane).putInt(156, mode);
        for (int i = 0; i < helpers.length; i++) data.putInt(64 + i * 4, helpers[i]);
        data.putFloat(108, (float) Math.sqrt(8.0));
        for (int i = 0; i < BIOMES.length; i++) data.putInt(112 + i * 4, BIOMES[i]);
        if(edit!=null)data.putInt(160,edit.mode).putFloat(164,edit.value).putFloat(168,edit.low).putFloat(172,edit.high)
                .putInt(176,dimension.getMinHeight()).putInt(180,dimension.getMaxHeight()-dimension.getMinHeight()>256?1:0);
        plan.writeTo(data, header);
        int palette = header + programBytes;
        for (Terrain terrain : TERRAINS) {
            int biome = terrain.isConfigured() ? terrain.getDefaultBiome() : -1;
            boolean forest = biome != BIOME_DESERT && biome != BIOME_DESERT_HILLS && biome != BIOME_DESERT_M
                    && biome != BIOME_MESA && biome != BIOME_MESA_BRYCE && biome != BIOME_MESA_PLATEAU
                    && biome != BIOME_MESA_PLATEAU_F && biome != BIOME_MESA_PLATEAU_F_M && biome != BIOME_MESA_PLATEAU_M;
            data.putInt(palette + terrain.ordinal() * 8, biome).putInt(palette + terrain.ordinal() * 8 + 4, forest ? 1 : 0);
        }
        int offset = TERRAIN_BYTES + HEIGHT_BYTES + 16384 * 4;
        for (int i = 0; i < layers.size(); i++) {
            int bits = bits(layers.get(i));
            data.putInt(planeDefinitions + i * 8, bits).putInt(planeDefinitions + i * 8 + 4, offset);
            offset += bytes(bits);
        }
        int frame = 0, cursor = payload;
        for (int ty = ty1; ty <= ty2; ty++) for (int tx = tx1; tx <= tx2; tx++) {
            int wx = tx << 7, wy = ty << 7;
            int x = Math.max(ox - wx, 0), y = Math.max(oy - wy, 0);
            int w = Math.min(ox + width - wx, 128) - x, h = Math.min(oy + height - wy, 128) - y;
            int record = tileDefinitions + frame++ * 32;
            Tile tile = dimension.getTile(tx, ty);
            data.putInt(record, tx).putInt(record + 4, ty).putInt(record + 8, x).putInt(record + 12, y)
                    .putInt(record + 16, w).putInt(record + 20, h).putInt(record + 24, cursor)
                    .putInt(record + 28, tile == null ? -1 : 0);
            if (tile != null) {
                if(edit!=null&&(tile.getMinHeight()!=dimension.getMinHeight()||tile.getMaxHeight()!=dimension.getMaxHeight()))return false;
                copyHalo(dimension, wx - 1, wy - 1, data, cursor + TERRAIN_BYTES);
                synchronized (tile) {
                    tile.copyCombinedLayerPlane(null, 8, data, cursor);
                    tile.copyFilteredWater(data, cursor + TERRAIN_BYTES + HEIGHT_BYTES);
                    for (int i = 0; i < layers.size(); i++) tile.copyCombinedLayerPlane(layers.get(i),
                            data.getInt(planeDefinitions + i * 8), data, cursor + data.getInt(planeDefinitions + i * 8 + 4));
                }
                if(edit!=null)for(int i=0;i<16384;i++)data.put(cursor+heightOutput+65536+i,(byte)0);
                data.position(cursor + planeEnd);
                for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++)
                    data.putFloat(strengths[(wy + y + dy - oy) * width + wx + x + dx - ox]);
            }
            cursor += planeEnd + w * h * 4;
        }
        data.position(0);
        if (!NativeSlices.paintFilteredTerrain(data)) return false;
        scratch.completed++;
        for (int i = 0; i < tileCount; i++) {
            int record = tileDefinitions + i * 32;
            int writes = data.getInt(record + 28);
            if(edit!=null){
                if(writes>0){Tile tile=dimension.getTileForEditing(data.getInt(record),data.getInt(record+4));int base=data.getInt(record+24)+heightOutput;
                    tile.applyRawHeightRegion(0,0,128,128,data,0,128,base,base+65536,16384);}
                continue;
            }
            // The original one-tile path requests editing even when every strength is rejected.
            if (writes > 0 || tileCount == 1 && writes == 0) {
                Tile tile = dimension.getTileForEditing(data.getInt(record), data.getInt(record + 4));
                // Default setters on absent layer storage neither allocate nor notify in Java.
                boolean absentDefault = outputLayer != null && !tile.hasLayer(outputLayer)
                        && (outputBits <= 1 && mode == 1 || fixed != null && fixed == outputLayer.getDefaultValue());
                if (writes > 0 && !absentDefault)
                    tile.applyCombinedLayerPlane(outputLayer, outputBits, data,
                        data.getInt(record + 24) + (outputLayer == null ? 0 : data.getInt(planeDefinitions + outputPlane * 8 + 4)));
            }
        }
        return true;
    }

    private static void copyHalo(Dimension dimension, int ox, int oy, ByteBuffer data, int offset) {
        int limit = data.limit();
        data.position(offset).limit(offset + HEIGHT_BYTES);
        FloatBuffer heights = data.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        data.limit(limit);
        for (int i = 0; i < 130 * 130; i++) heights.put(i, -Float.MAX_VALUE);
        for (int x = 0; x < 130;) {
            int wx = ox + x, w = Math.min(130 - x, 128 - (wx & 127));
            for (int y = 0; y < 130;) {
                int wy = oy + y, h = Math.min(130 - y, 128 - (wy & 127));
                Tile tile = dimension.getTile(wx >> 7, wy >> 7);
                if (tile != null) tile.copyHeightRegionDirect(wx & 127, wy & 127, w, h, heights, x * 130 + y, 130);
                y += h;
            }
            x += w;
        }
    }

    private static int bits(Layer layer) { return switch (layer.dataSize) {
        case BIT_PER_CHUNK -> 0; case BIT -> 1; case NIBBLE -> 4; case BYTE -> 8; default -> throw new IllegalArgumentException();
    }; }
    private static int bytes(int bits) { return bits == 0 ? 8 : 16384 * bits / 8; }
}
