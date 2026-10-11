package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.pepsoft.minecraft.Material.*;

/** Per-object material lookup and scalar results; the compact block arrays remain authoritative. */
final class LegacySchematicAccess {
    private static final int BYTES = 524352;
    private static final class Worker {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final BitSet seen = new BitSet(65536);
        final IdentityHashMap<String,Integer> names = new IdentityHashMap<>();
        Worker() { for (int state = 0; state < 4096; state++) prepare(this, state, Material.getByCombinedIndex(state)); }
    }
    private static final ThreadLocal<Worker> WORKER = ThreadLocal.withInitial(Worker::new);
    private static final AtomicLong COMPLETED = new AtomicLong();
    private final int[] keys;
    private final Material[] materials;
    final Point3i offset;
    final boolean connect;
    private LegacySchematicAccess(int[] keys, Material[] materials, Point3i offset, boolean connect) {
        this.keys = keys; this.materials = materials; this.offset = offset; this.connect = connect;
    }
    private static void prepare(Worker worker, int state, Material material) {
        int flags = material == null ? 16 : (material.connectingBlock ? 1 : 0)
                | (material.is(WEST) || material.is(NORTH) || material.is(EAST) || material.is(SOUTH) ? 2 : 0)
                | (material.veryInsubstantial ? 4 : 0) | (material.solid && material.opaque ? 8 : 0);
        Integer name = material == null ? Integer.valueOf(0) : worker.names.get(material.name);
        if (name == null) { name = worker.names.size(); worker.names.put(material.name,name); }
        worker.buffer.putInt(64 + state * 8,flags);worker.buffer.putInt(68 + state * 8,name);
    }
    static long completedAnalyses() { return COMPLETED.get(); }
    Material material(int state) {
        // Ordinary legacy states already have a direct, allocation-free Java lookup.
        if (state < 4096) return Material.getByCombinedIndex(state);
        int at = Arrays.binarySearch(keys, state);
        return at >= 0 ? materials[at] : Material.getByCombinedIndex(state);
    }
    static LegacySchematicAccess create(byte[] blocks, byte[] data, byte[] add, int width, int length, int height) {
        if (add == null || !NativeSlices.isLegacySchematicAvailable() || width <= 0 || length <= 0 || height <= 0) return null;
        long count = (long) width * length * height;
        if (count > 16 * 1024 * 1024 || blocks == null || data == null || blocks.length < count || data.length < count || add != null && add.length < (count + 1) / 2) return null;
        boolean extended = false;
        for (int pair = 0; pair < (count + 1) / 2; pair++) {
            int high = add[pair] & 255;
            if ((high & 15) != 0 || (high & 240) != 0 && pair * 2 + 1 < count) { extended = true; break; }
        }
        // The measured ordinary-ID path is already cheap; preserve Java inference for it.
        if (!extended) return null;
        Worker worker = WORKER.get(); BitSet seen = worker.seen; seen.clear();
        // Only AddBlocks can introduce an extended ID; zero nibbles need no material preparation.
        if (add != null) for (int pair = 0; pair < (count + 1) / 2; pair++) {
            int high = add[pair] & 255;
            if (high == 0) continue;
            int at = pair * 2;
            if ((high & 15) != 0) seen.set((((blocks[at] & 255) | ((high & 15) << 8)) << 4) | (data[at] & 15));
            if ((high & 240) != 0 && at + 1 < count) seen.set((((blocks[at+1] & 255) | ((high & 240) << 4)) << 4) | (data[at+1] & 15));
        }
        int[] keys = seen.stream().toArray(); Material[] materials = new Material[keys.length];
        ByteBuffer buffer = worker.buffer; buffer.clear();
        buffer.putInt(0,0x574c5343);buffer.putInt(4,1);buffer.putInt(8,width);buffer.putInt(12,length);buffer.putInt(16,height);buffer.putInt(44,0);
        for (int i = 0; i < keys.length; i++) {
            Material material = materials[i] = Material.getByCombinedIndex(keys[i]);
            if (material == null) return null;
            prepare(worker, keys[i], material);
        }
        if (!NativeSlices.analyzeLegacySchematic(blocks,data,add,buffer) || buffer.getInt(44) != 1) return null;
        Point3i offset = buffer.getInt(24) == 0 ? null : new Point3i(buffer.getInt(28),buffer.getInt(32),buffer.getInt(36));
        COMPLETED.incrementAndGet();
        return new LegacySchematicAccess(keys,materials,offset,buffer.getInt(40) != 0);
    }
}
