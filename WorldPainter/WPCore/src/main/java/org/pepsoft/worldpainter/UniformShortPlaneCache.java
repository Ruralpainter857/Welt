package org.pepsoft.worldpainter;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Bounded weak interning of uniform short planes. Accepted arrays are immutable until detached by their owner. */
final class UniformShortPlaneCache {
    UniformShortPlaneCache(short[] zero, int capacity) {
        if (zero.length == 0 || capacity < 1) throw new IllegalArgumentException("Invalid plane cache bounds");
        this.zero = zero;
        this.capacity = capacity;
        shared.add(zero);
    }

    short[] uniform(short value) { return intern(value, null); }

    short[] intern(short[] values) {
        if (values == null || values.length != zero.length || isShared(values)) return values;
        short value = values[0];
        for (int i = 1; i < values.length; i++) if (values[i] != value) return values;
        return intern(value, values);
    }

    boolean isShared(short[] values) { return values != null && shared.contains(values); }

    private synchronized short[] intern(short value, short[] candidate) {
        if (value == 0) return zero;
        int key = value & 0xffff;
        WeakReference<short[]> reference = cache.get(key);
        short[] values = reference == null ? null : reference.get();
        if (values == null) {
            values = candidate == null ? new short[zero.length] : candidate;
            if (candidate == null) Arrays.fill(values, value);
            shared.add(values);
            cache.put(key, new WeakReference<>(values));
            if (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
        }
        return values;
    }

    private final short[] zero;
    private final int capacity;
    private final Map<Integer, WeakReference<short[]>> cache = new LinkedHashMap<>(16, .75f, true);
    // Eviction must not make an array still held by live tiles writable. This registry retains no plane strongly.
    private final Set<short[]> shared = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
}
