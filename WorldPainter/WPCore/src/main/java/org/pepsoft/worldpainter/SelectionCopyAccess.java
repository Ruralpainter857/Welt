package org.pepsoft.worldpainter;

import java.nio.*;
import java.util.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import org.pepsoft.worldpainter.nativeapi.*;

/** WLCP v1/v2: one JNI call for a source tile and its live destination planes. */
public final class SelectionCopyAccess {
    private static final Set<Layer> SKIP = Set.of(Biome.INSTANCE, SelectionChunk.INSTANCE, SelectionBlock.INSTANCE,
            NotPresent.INSTANCE, NotPresentBlock.INSTANCE, Annotations.INSTANCE, FloodWithLava.INSTANCE);
    private static final ThreadLocal<ByteBuffer> BUFFER = new ThreadLocal<>();
    private final Dimension dimension;
    private final Layer[] layers;
    private final int[] kinds, roles, operations, offsets;
    private final int bytes, chunks, blocks;
    private final boolean clear;
    private final Tile[] tiles = new Tile[5];
    private int calls;
    private SnapshotRandom random;
    private boolean copyLayers;
    private static final float[] BLEND_WEIGHTS = blendWeights();
    private static float[] blendWeights() {
        float[] values = new float[513]; Arrays.fill(values, 1f);
        for (int x = 0; x <= 16; x++) for (int y = 0; y <= 16; y++) {
            int square = x*x+y*y; float distance = org.pepsoft.util.MathUtils.getDistance(x, y);
            if (distance < 16) values[square] = (float) (-Math.cos(distance / (16.0 / Math.PI)) / 2 + 0.5);
        }
        return values;
    }

    private SelectionCopyAccess(Dimension d, List<Layer> ls, List<Integer> rs, List<Integer> ops, boolean clear) {
        dimension = d; this.clear = clear; layers = ls.toArray(new Layer[0]);
        int n = layers.length; kinds = new int[n]; roles = new int[n]; operations = new int[n]; offsets = new int[n];
        int size = 0, sc = -1, sb = -1;
        for (int p = 0; p < n; p++) {
            roles[p] = rs.get(p); operations[p] = ops.get(p); Layer l = layers[p];
            kinds[p] = roles[p] < 2 ? 0 : roles[p] == 2 ? 1 : l.dataSize == Layer.DataSize.BYTE ? 1
                    : l.dataSize == Layer.DataSize.NIBBLE ? 2 : l.dataSize == Layer.DataSize.BIT ? 3 : 4;
            offsets[p] = size; size += length(kinds[p]);
            if (l == SelectionChunk.INSTANCE) sc = p; if (l == SelectionBlock.INSTANCE) sb = p;
        }
        bytes = size; chunks = sc; blocks = sb;
    }
    static int length(int kind) { return kind == 0 ? 65536 : kind == 1 ? 16384 : kind == 2 ? 8192 : kind == 3 ? 2048 : 8; }
    /** Unsupported geometry or storage falls back before editing any tile. */
    public static SelectionCopyAccess prepare(Dimension d, int dx, int dy, boolean heights, boolean terrain, boolean fluids,
                                               boolean copyLayers, boolean biomes, boolean annotations, boolean clear) {
        if (d.getClass() != Dimension.class || !d.isEventsInhibited() || !Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) return null;
        List<Layer> ls = new ArrayList<>(); List<Integer> rs = new ArrayList<>(), ops = new ArrayList<>();
        if (heights) add(ls, rs, ops, null, 0, 3); if (terrain) add(ls, rs, ops, null, 2, 3);
        if (fluids) { add(ls, rs, ops, null, 1, 3); add(ls, rs, ops, FloodWithLava.INSTANCE, 3, 1); }
        add(ls, rs, ops, SelectionChunk.INSTANCE, 3, 0); add(ls, rs, ops, SelectionBlock.INSTANCE, 3, 0);
        Set<Layer> ordinary = new LinkedHashSet<>();
        for (Tile t : d.getTiles()) {
            long x = (long) t.getX() * 128, y = (long) t.getY() * 128;
            if (t.getClass() != Tile.class || t.getMinHeight() != d.getMinHeight() || t.getMaxHeight() != d.getMaxHeight()
                    || x < Integer.MIN_VALUE || y < Integer.MIN_VALUE || x + 127 > Integer.MAX_VALUE || y + 127 > Integer.MAX_VALUE
                    || x + dx < Integer.MIN_VALUE || y + dy < Integer.MIN_VALUE || x + dx + 127 > Integer.MAX_VALUE || y + dy + 127 > Integer.MAX_VALUE) return null;
            if (copyLayers) for (Layer l : t.getLayers()) if (!SKIP.contains(l)) ordinary.add(l);
        }
        for (Layer l : ordinary) {
            if (l.dataSize != Layer.DataSize.BIT && l.dataSize != Layer.DataSize.BIT_PER_CHUNK
                    && l.dataSize != Layer.DataSize.NIBBLE && l.dataSize != Layer.DataSize.BYTE
                    || l.getDefaultValue() < 0 || l.getDefaultValue() > l.dataSize.maxValue) return null;
            add(ls, rs, ops, l, 3, 2);
        }
        if (biomes) add(ls, rs, ops, Biome.INSTANCE, 3, 1); if (annotations) add(ls, rs, ops, Annotations.INSTANCE, 3, 1);
        return ls.size() > 64 ? null : new SelectionCopyAccess(d, ls, rs, ops, clear && copyLayers);
    }
    /** Include every layer that affects HashMap iteration, even when it is not copied. */
    public static SelectionCopyAccess prepareBlended(Dimension d, int dx, int dy, boolean heights, boolean terrain, boolean fluids,
                                                      boolean copyLayers, boolean biomes, boolean annotations, boolean clear, SnapshotRandom random) {
        if (random == null) return null;
        SelectionCopyAccess initial = prepare(d, dx, dy, heights, terrain, fluids, copyLayers, biomes, annotations, clear);
        if (initial == null) return null;
        List<Layer> ls = new ArrayList<>(Arrays.asList(initial.layers)); List<Integer> rs = new ArrayList<>(), ops = new ArrayList<>();
        for (int p = 0; p < initial.layers.length; p++) { rs.add(initial.roles[p]); ops.add(initial.operations[p]); }
        Set<Layer> all = new LinkedHashSet<>();
        for (Tile t : d.getTiles()) {
            long x = (long) t.getX()*128, y = (long) t.getY()*128;
            if (x-16 < Integer.MIN_VALUE || y-16 < Integer.MIN_VALUE || x+143 > Integer.MAX_VALUE || y+143 > Integer.MAX_VALUE) return null;
            if (copyLayers) all.addAll(t.getLayers());
        }
        for (Layer l : all) if (!ls.contains(l)) {
            if (l.dataSize != Layer.DataSize.BIT && l.dataSize != Layer.DataSize.BIT_PER_CHUNK
                    && l.dataSize != Layer.DataSize.NIBBLE && l.dataSize != Layer.DataSize.BYTE
                    || l.getDefaultValue() < 0 || l.getDefaultValue() > l.dataSize.maxValue) return null;
            add(ls, rs, ops, l, 3, 0);
        }
        if (ls.size() > 64) return null;
        if (copyLayers) {
            int[] bins = new int[16];
            for (Layer l : ls) if (l != null) { int hash = l.hashCode(); if (++bins[(hash ^ (hash >>> 16)) & 15] >= 9) return null; }
        }
        SelectionCopyAccess plan = new SelectionCopyAccess(d, ls, rs, ops, clear && copyLayers);
        plan.random = random; plan.copyLayers = copyLayers; return plan;
    }

    private static void add(List<Layer> ls, List<Integer> rs, List<Integer> ops, Layer layer, int role, int op) {
        ls.add(layer); rs.add(role); ops.add(op);
    }
    public int getNativeCalls() { return calls; }
    /** The source remains in the same buffer when a destination overlaps it. */
    public boolean copyTile(Tile source, int dx, int dy) {
        if (random != null) synchronized (random) { return processTile(source, dx, dy); }
        return processTile(source, dx, dy);
    }
    private boolean processTile(Tile source, int dx, int dy) {
        if (!dimension.isEventsInhibited()) throw new IllegalStateException("Inhibited dimension required");
        if (source == null || source.getClass() != Tile.class || dimension.getTile(source.getX(), source.getY()) != source) return false;
        tiles[0] = source; int count = 1;
        int x1 = (int) (((long) source.getX() * 128 + dx) >> 7), x2 = (int) (((long) source.getX() * 128 + dx + 127) >> 7);
        int y1 = (int) (((long) source.getY() * 128 + dy) >> 7), y2 = (int) (((long) source.getY() * 128 + dy + 127) >> 7);
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) {
            Tile target = dimension.getTile(x, y); if (target != null && target != source) tiles[count++] = target;
        }
        int head = 64 + layers.length * 16, stride = 32 + bytes, extension = head + count * stride;
        int length = extension + (random == null ? 0 : 5708);
        ByteBuffer data = BUFFER.get();
        if (data == null || data.capacity() < length) { data = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN); BUFFER.set(data); }
        data.clear().limit(length);
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x50434c57).putInt(4, random == null ? 1 : 2).putInt(8, count).putInt(12, layers.length).putInt(16, dx).putInt(20, dy)
                .putInt(24, chunks).putInt(28, blocks).putInt(32, clear ? 1 : 0);
        for (int p = 0; p < layers.length; p++) data.putInt(64+p*16, kinds[p]).putInt(68+p*16, operations[p])
                .putInt(72+p*16, layers[p] == null ? 0 : layers[p].getDefaultValue()).putInt(76+p*16, offsets[p]);
        for (int t = 0; t < count; t++) tiles[t].copySelectionPlanes(data, head+t*stride, layers, roles, kinds, offsets);
        if (random != null) {
            data.putInt(36, copyLayers ? 1 : 0).putLong(40, random.snapshotState());
            if (!source.copySelectionBlendOrder(data, extension, layers, roles, operations, copyLayers)) return false;
            for (int i = 0; i < 3200; i += 8) data.putLong(extension+456+i, 0);
            for (int nx = -1; nx <= 1; nx++) for (int ny = -1; ny <= 1; ny++) {
                Tile neighbour = dimension.getTile(source.getX()+nx, source.getY()+ny);
                if (neighbour != null) neighbour.copySelectionHalo(data, extension+456, nx, ny);
            }
            for (int i = 0; i < 513; i++) data.putFloat(extension+3656+i*4, BLEND_WEIGHTS[i]);
        }
        data.position(0);
        if (!NativeSlices.copySelectionPlanes(data)) return false;
        if (random != null) random.restoreState(data.getLong(40));
        calls++;
        for (int t = 0; t < count; t++) if (data.getLong(head+t*stride+16) != 0) {
            Tile editing = dimension.getTileForEditing(tiles[t].getX(), tiles[t].getY());
            editing.applySelectionPlanes(data, head+t*stride, layers, roles, kinds, offsets);
        }
        return true;
    }
}
