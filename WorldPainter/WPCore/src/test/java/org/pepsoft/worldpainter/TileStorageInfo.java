package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.Layer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;

/**
 * Test-side diagnostics shared by the reference and the new storage: deep retained size of undo buffers and the
 * share of 16x16 pages left unchanged between successive versions. Works on raw arrays, maps and (by reflection)
 * on history wrappers, so that the same benchmark binary measures both implementations.
 */
final class TileStorageInfo {
    private TileStorageInfo() {
        // Utility class
    }

    static String describe() {
        try {
            final Class<?> storage = Class.forName("org.pepsoft.worldpainter.TileHistoryStorage");
            final Method method = storage.getDeclaredMethod("describe");
            method.setAccessible(true);
            return (String) method.invoke(null);
        } catch (ClassNotFoundException e) {
            return "reference";
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Payload bytes reachable from a buffer and not yet counted (identity based); keys and layers excluded. */
    static long retainedBytes(Object object, Map<Object, Boolean> seen) {
        if ((object == null) || (object instanceof Layer) || (object instanceof Tile) || (object instanceof Class)
                || (object instanceof Enum) || (object instanceof Number) || (object instanceof String)
                || (object instanceof java.lang.ref.Reference) || (seen.put(object, Boolean.TRUE) != null)) {
            return 0;
        }
        final Class<?> type = object.getClass();
        if (type.isArray()) {
            if (type.getComponentType().isPrimitive()) {
                return 16 + (long) Array.getLength(object) * elementSize(type.getComponentType());
            }
            long total = 16 + 4L * Array.getLength(object);
            for (int i = 0; i < Array.getLength(object); i++) {
                total += retainedBytes(Array.get(object, i), seen);
            }
            return total;
        } else if (object instanceof BitSet bitSet) {
            return 40 + (bitSet.size() / 8);
        } else if (object instanceof Map<?, ?> map) {
            long total = 48 + 32L * map.size();
            for (Object value: map.values()) {
                total += retainedBytes(value, seen);
            }
            return total;
        } else if (object instanceof Collection<?> collection) {
            long total = 48 + 24L * collection.size();
            for (Object value: collection) {
                total += retainedBytes(value, seen);
            }
            return total;
        } else {
            long total = 16;
            for (Class<?> c = type; (c != null) && (c != Object.class); c = c.getSuperclass()) {
                for (Field field: c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getType().isPrimitive()) {
                        total += elementSize(field.getType());
                        continue;
                    }
                    total += 4;
                    try {
                        field.setAccessible(true);
                        total += retainedBytes(field.get(object), seen);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // Inaccessible JDK internals: count the reference only
                    }
                }
            }
            return total;
        }
    }

    /** Returns {pages compared, pages identical} between an older and a newer version of the same buffer. */
    static long[] comparePages(Object older, Object newer) {
        older = content(older);
        newer = content(newer);
        final long[] result = new long[2];
        if ((older instanceof Map<?, ?> olderMap) && (newer instanceof Map<?, ?> newerMap)) {
            for (Map.Entry<?, ?> entry: olderMap.entrySet()) {
                final long[] layer = comparePages(entry.getValue(), newerMap.get(entry.getKey()));
                result[0] += layer[0];
                result[1] += layer[1];
            }
            return result;
        }
        if ((older == null) || !older.getClass().isArray() || !older.getClass().getComponentType().isPrimitive()) {
            return result;
        }
        final int length = Array.getLength(older);
        if ((length != TILE_SIZE * TILE_SIZE) && (length != TILE_SIZE * TILE_SIZE / 2)) {
            return result;
        }
        final boolean comparable = (newer != null) && (newer.getClass() == older.getClass()) && (Array.getLength(newer) == length);
        final int shift = (length == TILE_SIZE * TILE_SIZE) ? 0 : 1;
        for (int py = 0; py < TILE_SIZE / 16; py++) {
            for (int px = 0; px < TILE_SIZE / 16; px++) {
                result[0]++;
                if (!comparable) {
                    continue;
                }
                boolean same = true;
                for (int y = py * 16; same && (y < py * 16 + 16); y++) {
                    for (int x = px * 16; x < px * 16 + 16; x += 1 << shift) {
                        final int index = (x + y * TILE_SIZE) >> shift;
                        if (!Objects.equals(Array.get(older, index), Array.get(newer, index))) {
                            same = false;
                            break;
                        }
                    }
                }
                if (same) {
                    result[1]++;
                }
            }
        }
        return result;
    }

    private static Object content(Object buffer) {
        try {
            final Class<?> storage = Class.forName("org.pepsoft.worldpainter.TileHistoryStorage");
            final Method method = storage.getDeclaredMethod("content", Object.class);
            method.setAccessible(true);
            return method.invoke(null, buffer);
        } catch (ClassNotFoundException e) {
            return buffer;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int elementSize(Class<?> type) {
        if ((type == long.class) || (type == double.class)) {
            return 8;
        } else if ((type == int.class) || (type == float.class)) {
            return 4;
        } else if ((type == short.class) || (type == char.class)) {
            return 2;
        } else {
            return 1;
        }
    }
}
