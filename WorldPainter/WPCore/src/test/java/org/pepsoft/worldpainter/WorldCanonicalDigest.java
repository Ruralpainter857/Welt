package org.pepsoft.worldpainter;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Order-independent digest of everything Java serialisation would persist from an object graph: all non-transient,
 * non-static fields, recursively. Maps and sets are digested in the order of their canonical entry digests, so that
 * identity hash codes (layers have no value equality) do not matter. Everything is digested by value: an object reached
 * again gets the digest computed the first time (memoised by identity), and an object reached again while it is being
 * digested (a cycle, such as dimension and world) gets a fixed marker. Sharing itself is not compared; it is a property
 * of Java serialisation, which the stream format checks cover. Used to compare a world before saving and after loading.
 *
 * <p>{@link #explain} returns a readable path list of the first difference between two graphs.
 */
final class WorldCanonicalDigest {
    private WorldCanonicalDigest() {
        // Utility class
    }

    static long of(Object root) {
        return new Walker().digest(root, "root");
    }

    /** Lines "path = digest" for every object field down to {@code depth}; diff two of these to locate a mismatch. */
    static List<String> explain(Object root, int depth) {
        final Walker walker = new Walker();
        walker.trace = new ArrayList<>();
        walker.traceDepth = depth;
        walker.digest(root, "root");
        return walker.trace;
    }

    private static final class Walker {
        long digest(Object object, String path) {
            depth++;
            try {
                final long value = compute(object, path);
                if ((trace != null) && (depth <= traceDepth)) {
                    trace.add(path + " = " + Long.toHexString(value));
                }
                return value;
            } finally {
                depth--;
            }
        }

        private long compute(Object object, String path) {
            if (object == null) {
                return 0x9E3779B97F4A7C15L;
            }
            final Class<?> type = object.getClass();
            if ((object instanceof String) || (object instanceof Number) || (object instanceof Boolean) || (object instanceof Character)) {
                return mix(type.getName().hashCode(), Objects.hashCode(valueOf(object)));
            } else if (object instanceof Enum<?> e) {
                return mix(e.getDeclaringClass().getName().hashCode(), e.name().hashCode());
            } else if (object instanceof Class<?> c) {
                return mix(17, c.getName().hashCode());
            } else if (type.isArray() && type.getComponentType().isPrimitive()) {
                return mix(type.getName().hashCode(), primitiveArrayHash(object));
            }
            final Long memoised = memo.get(object);
            if (memoised != null) {
                return memoised;
            }
            if (onStack.put(object, Boolean.TRUE) != null) {
                return mix(0xC1C1E, type.getName().hashCode());
            }
            try {
                final long hash = digestObject(object, type, path);
                memo.put(object, hash);
                return hash;
            } finally {
                onStack.remove(object);
            }
        }

        private long digestObject(Object object, Class<?> type, String path) {
            if (object instanceof java.util.BitSet bits) {
                return mix(type.getName().hashCode(), Arrays.hashCode(bits.toLongArray()));
            } else if (object instanceof java.util.Date date) {
                return mix(type.getName().hashCode(), Long.hashCode(date.getTime()));
            }
            long hash = type.getName().hashCode();
            if (type.isArray()) {
                for (int i = 0; i < Array.getLength(object); i++) {
                    hash = mix(hash, digest(Array.get(object, i), path + "[" + i + "]"));
                }
            } else if (object instanceof Map<?, ?> map) {
                // Canonical order: by key digest computed in an isolated walker, so ordering does not depend on visits
                final List<Object[]> entries = new ArrayList<>();
                for (Map.Entry<?, ?> entry: map.entrySet()) {
                    entries.add(new Object[] {isolated(entry.getKey()), entry.getKey(), entry.getValue()});
                }
                entries.sort(Comparator.comparingLong(entry -> (Long) entry[0]));
                for (Object[] entry: entries) {
                    final String key = String.valueOf(entry[1]);
                    hash = mix(hash, digest(entry[1], path + "{key " + key + "}"));
                    hash = mix(hash, digest(entry[2], path + "{" + key + "}"));
                }
            } else if (object instanceof Collection<?> collection) {
                final List<Object> elements = new ArrayList<>(collection);
                if (object instanceof Set) {
                    elements.sort(Comparator.comparingLong(this::isolated));
                }
                int i = 0;
                for (Object element: elements) {
                    hash = mix(hash, digest(element, path + "[" + i++ + "]"));
                }
            } else {
                for (Class<?> c = type; (c != null) && (c != Object.class); c = c.getSuperclass()) {
                    for (Field field: fields(c)) {
                        Object value;
                        try {
                            value = field.get(object);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                        hash = mix(hash, field.getName().hashCode());
                        hash = mix(hash, digest(value, path + "." + field.getName()));
                    }
                }
            }
            return hash;
        }

        private long isolated(Object object) {
            final Walker walker = new Walker();
            return walker.digest(object, "key");
        }

        private static Object valueOf(Object object) {
            if (object instanceof Float f) {
                return Float.floatToRawIntBits(f);
            } else if (object instanceof Double d) {
                return Double.doubleToRawLongBits(d);
            } else {
                return object;
            }
        }

        private static List<Field> fields(Class<?> type) {
            return FIELDS.computeIfAbsent(type, c -> {
                final List<Field> result = new ArrayList<>();
                for (Field field: c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        result.add(field);
                    } catch (RuntimeException e) {
                        // JDK internals that cannot be opened: covered by the object's own type name only
                    }
                }
                result.sort(Comparator.comparing(Field::getName));
                return result;
            });
        }

        private static long primitiveArrayHash(Object array) {
            if (array instanceof byte[] a) return Arrays.hashCode(a);
            if (array instanceof short[] a) return Arrays.hashCode(a);
            if (array instanceof int[] a) return Arrays.hashCode(a);
            if (array instanceof long[] a) return Arrays.hashCode(a);
            if (array instanceof float[] a) return Arrays.hashCode(a);
            if (array instanceof double[] a) return Arrays.hashCode(a);
            if (array instanceof char[] a) return Arrays.hashCode(a);
            return Arrays.hashCode((boolean[]) array);
        }

        private static long mix(long hash, long value) {
            long h = hash * 0x9E3779B97F4A7C15L + value;
            return h ^ (h >>> 31);
        }

        private final Map<Object, Long> memo = new IdentityHashMap<>();
        private final Map<Object, Boolean> onStack = new IdentityHashMap<>();
        private List<String> trace;
        private int traceDepth, depth;

        private static final Map<Class<?>, List<Field>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    }
}
