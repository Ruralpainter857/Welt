package org.pepsoft.util;

import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.lang.reflect.Array;
import java.util.*;

/**
 * A configurable-sized cube of values packed into a {@code long} array of indexes and a linear palette.
 */
public class PackedArrayCube<T> {
    /**
     * Create an empty packed array cube of the specified size.
     *
     * @param size            Length of one edge of the cube.
     * @param minimumWordSize The minimum word size for the packed data. In Minecraft this varies, from 4 for block
     *                        states (resulting in unnecessarily large arrays) to 1 for biomes.
     * @param straddleLongs   Whether palette indexes are allowed to straddle two longs in the packed data array. It
     *                        seems that Minecraft 1.15 supports this, whereas Minecraft 1.16+ does not.
     * @param type            The type of values to be stored in the packed array cube.
     */
    @SuppressWarnings("unchecked") // Guaranteed by Java library
    public PackedArrayCube(int size, int minimumWordSize, boolean straddleLongs, Class<T> type) {
        this.minimumWordSize = minimumWordSize;
        this.straddleLongs = straddleLongs;
        this.type = type;
        bitsPerCoordinate = (int) Math.ceil(Math.log(size) / Math.log(2));
        arraySize = size * size * size;
        paletteIndexStorage = Boolean.getBoolean("welt.packedArrayCube.compactPaletteStorage")
                || ((type == Material.class)
                    && Native.isResourcesExportEnabled());
        values = paletteIndexStorage ? null : (T[]) Array.newInstance(type, arraySize);
        if (paletteIndexStorage) {
            buildEmptyPaletteIndexView();
        }
    }

    /**
     * Create a packed array cube of the specified size by unpacking an existing data array and palette.
     *
     * @param size            Length of one edge of the cube.
     * @param data            The data array to unpack.
     * @param palette         The palette of values.
     * @param minimumWordSize The minimum word size for the packed data. In Minecraft this varies, from 4 for block
     *                        states (resulting in unnecessarily large arrays) to 1 for biomes.
     * @param straddleLongs   Whether palette indexes are allowed to straddle two longs in the packed data array. It
     *                        seems that Minecraft 1.15 supports this, whereas Minecraft 1.16+ does not.
     * @param type            The type of values to be stored in the packed array cube.
     */
    public PackedArrayCube(int size, long[] data, T[] palette, int minimumWordSize, boolean straddleLongs, Class<T> type) {
        this(size, minimumWordSize, straddleLongs, type);

        // Sanity check
        for (int i = 0; i < palette.length; i++) {
            if ((palette[i] != null) && (! type.isAssignableFrom(palette[i].getClass()))) {
                throw new IllegalArgumentException("Palette[" + i + "] is not a " + type.getSimpleName() + " (actual type: " + palette[i].getClass().getName() + "; value: " + palette[i] + ")");
            }
        }

        final int wordSize = Math.max(minimumWordSize, (int) Math.ceil(Math.log(palette.length) / Math.log(2)));
        final int expectedPackedDataArrayLengthInBytes = wordSize * arraySize / 8;
        final int dataArrayLengthInBytes = data.length * 8;
        if (paletteIndexStorage) {
            final int[] unpacked = unpackIndexes(data, wordSize, palette.length,
                    expectedPackedDataArrayLengthInBytes == dataArrayLengthInBytes);
            buildPaletteIndexView(palette, unpacked);
            return;
        }
        // The per-long Java loop is faster than JNI for non-straddling arrays on the measured fixture.
        if ((wordSize == 4 || dataArrayLengthInBytes == expectedPackedDataArrayLengthInBytes)
                && Native.isExportEnabled() && NativeLoader.areSlicesAvailable()) {
            final int[] nativeIndexes = NativeSlices.unpackArrayCube(
                    data, arraySize, wordSize, palette.length);
            if (nativeIndexes != null) {
                for (int i = 0; i < arraySize; i++) {
                    values[i] = palette[nativeIndexes[i]];
                }
                return;
            }
        }
        if (wordSize == 4) {
            // Optimised special case
            for (int w = 0; w < arraySize; w += 16) {
                final long arrayValue = data[w >> 4];
                values[w]      = palette[(int) (arrayValue  & 0x000000000000000fL)];
                values[w +  1] = palette[(int) ((arrayValue & 0x00000000000000f0L) >>   4)];
                values[w +  2] = palette[(int) ((arrayValue & 0x0000000000000f00L) >>   8)];
                values[w +  3] = palette[(int) ((arrayValue & 0x000000000000f000L) >>  12)];
                values[w +  4] = palette[(int) ((arrayValue & 0x00000000000f0000L) >>  16)];
                values[w +  5] = palette[(int) ((arrayValue & 0x0000000000f00000L) >>  20)];
                values[w +  6] = palette[(int) ((arrayValue & 0x000000000f000000L) >>  24)];
                values[w +  7] = palette[(int) ((arrayValue & 0x00000000f0000000L) >>  28)];
                values[w +  8] = palette[(int) ((arrayValue & 0x0000000f00000000L) >>  32)];
                values[w +  9] = palette[(int) ((arrayValue & 0x000000f000000000L) >>  36)];
                values[w + 10] = palette[(int) ((arrayValue & 0x00000f0000000000L) >>  40)];
                values[w + 11] = palette[(int) ((arrayValue & 0x0000f00000000000L) >>  44)];
                values[w + 12] = palette[(int) ((arrayValue & 0x000f000000000000L) >>  48)];
                values[w + 13] = palette[(int) ((arrayValue & 0x00f0000000000000L) >>  52)];
                values[w + 14] = palette[(int) ((arrayValue & 0x0f00000000000000L) >>  56)];
                values[w + 15] = palette[(int) ((arrayValue & 0xf000000000000000L) >>> 60)];
            }
        } else if (dataArrayLengthInBytes != expectedPackedDataArrayLengthInBytes) {
            // A weird format where the values are packed per long (leaving bits unused). Unpack each long individually
            final long mask = (long) (Math.pow(2, wordSize)) - 1;
            final int bitsInUse = (64 / wordSize) * wordSize;
            int materialIndex = 0;
            outer:
            for (long packedData: data) {
                for (int offset = 0; offset < bitsInUse; offset += wordSize) {
                    values[materialIndex++] = palette[(int) ((packedData & (mask << offset)) >>> offset)];
                    if (materialIndex >= arraySize) {
                        // The last long was not fully used
                        break outer;
                    }
                }
            }
        } else {
            final BitSet bitSet = BitSet.valueOf(data);
            for (int w = 0; w < arraySize; w++) {
                final int wordOffset = w * wordSize;
                int index = 0;
                for (int b = 0; b < wordSize; b++) {
                    index |= bitSet.get(wordOffset + b) ? 1 << b : 0;
                }
                values[w] = palette[index];
            }
        }
    }

    public T getValue(int x, int y, int z) {
        return getValueAtIndex(offset(x, y, z));
    }

    /** Reads the x-fast linear storage without creating a coordinate tuple. */
    public T getValueAtIndex(int index) {
        if ((index < 0) || (index >= arraySize)) {
            throw new IndexOutOfBoundsException("index " + index);
        }
        return (values != null) ? values[index] : indexedPalette[paletteIndexes[index]];
    }

    /** Whether this cube stores palette indexes as its primary value representation. */
    public boolean hasPaletteIndexStorage() {
        return paletteIndexes != null;
    }

    /**
     * Return the canonical palette-index storage for a bulk updater, or
     * {@code null} when this cube uses object storage. Mutations through the
     * returned array are immediately visible to reads and serialization when
     * palette-index storage is enabled. Callers must reserve every output
     * value first and must not retain the array beyond the owning chunk's
     * lifetime.
     */
    public int[] getPaletteIndexesForBulkUpdate() {
        return paletteIndexes;
    }

    /**
     * Add or find a value in the canonical palette for a bulk updater.
     * Returns {@code -1} when this cube does not use palette-index storage.
     */
    public int ensurePaletteIndexForBulkUpdate(T value) {
        return (paletteIndexes != null) ? paletteIndexFor(value) : -1;
    }

    /** Copies the cube's current palette indices into caller-owned storage. */
    public void copyPaletteIndexesTo(int[] target, int targetOffset) {
        if (paletteIndexes == null) {
            throw new IllegalStateException("This cube has no palette-index view");
        }
        if ((targetOffset < 0) || (targetOffset > target.length - paletteIndexes.length)) {
            throw new IndexOutOfBoundsException("targetOffset " + targetOffset);
        }
        System.arraycopy(paletteIndexes, 0, target, targetOffset, paletteIndexes.length);
    }

    public int getPaletteIndexCount() {
        return (indexedPalette != null) ? indexedPalette.length : 0;
    }

    public T getPaletteValue(int index) {
        if ((indexedPalette == null) || (index < 0) || (index >= indexedPalette.length)) {
            throw new IndexOutOfBoundsException("palette index " + index);
        }
        return indexedPalette[index];
    }

    private void buildPaletteIndexView(T[] sourcePalette, int[] sourceIndexes) {
        indexedPalette = Arrays.copyOf(sourcePalette, sourcePalette.length);
        indexedPaletteLookup = new IdentityHashMap<>(sourcePalette.length * 2);
        for (int i = 0; i < sourcePalette.length; i++) {
            indexedPaletteLookup.putIfAbsent(sourcePalette[i], i);
        }
        paletteIndexes = sourceIndexes;
    }

    @SuppressWarnings("unchecked")
    private void buildEmptyPaletteIndexView() {
        indexedPalette = (T[]) Array.newInstance(type, 1);
        indexedPaletteLookup = new IdentityHashMap<>();
        indexedPaletteLookup.put(null, 0);
        paletteIndexes = new int[arraySize];
    }

    private int paletteIndexFor(T value) {
        Integer index = indexedPaletteLookup.get(value);
        if (index == null) {
            index = indexedPalette.length;
            indexedPalette = Arrays.copyOf(indexedPalette, index + 1);
            indexedPalette[index] = value;
            indexedPaletteLookup.put(value, index);
        }
        return index;
    }

    public void setValue(int x, int y, int z, T value) {
        final int index = offset(x, y, z);
        if (values != null) {
            values[index] = value;
        }
        if (paletteIndexes != null) {
            paletteIndexes[index] = paletteIndexFor(value);
        }
    }

    /** Copies the cube's linear x-fast storage to caller-owned scratch space. */
    public void copyValuesTo(T[] target, int targetOffset) {
        if ((targetOffset < 0) || (targetOffset > target.length - arraySize)) {
            throw new IndexOutOfBoundsException("targetOffset " + targetOffset);
        }
        if (values != null) {
            System.arraycopy(values, 0, target, targetOffset, arraySize);
        } else {
            for (int i = 0; i < arraySize; i++) {
                target[targetOffset + i] = indexedPalette[paletteIndexes[i]];
            }
        }
    }

    public void fill(T value) {
        if (values != null) {
            Arrays.fill(values, value);
        }
        if (paletteIndexes != null) {
            Arrays.fill(paletteIndexes, paletteIndexFor(value));
        }
    }

    public boolean isEmpty() {
        for (int i = 0; i < arraySize; i++) {
            if (getValueAtIndex(i) != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pack the data into a palette and a {@code long} array. {@code null} values are not replaced and if any of the
     * values are {@code null}, the palette will contain a {@code null} entry.
     *
     * @return The packed data.
     */
    public PackedData pack() {
        return pack(null);
    }

    /**
     * Pack the data into a palette and a {@code long} array.
     *
     * @param nullSubstitute The value to replace {@code null} values with, if any. May be {@code null}, in which case
     *                       one of the palette entries may be {@code null}.
     * @return The packed data.
     */
    @SuppressWarnings("unchecked") // Guaranteed by Java library
    public PackedData pack(T nullSubstitute) {
        if (paletteIndexStorage) {
            return packFromPaletteIndexes(nullSubstitute);
        }
        // Create the palette. We have to do this first, because otherwise we don't know how many bits the indices will
        // be and therefore how big to make the data array
        final Map<T, Integer> reversePalette = new HashMap<>();
        final List<T> palette = new LinkedList<>();
        final int[] paletteIndices = Native.isExportEnabled() && NativeLoader.areSlicesAvailable()
                ? new int[arraySize] : null;
        for (int i = 0; i < values.length; i++) {
            T value = values[i];
            if (value == null) {
                value = nullSubstitute;
            }
            Integer paletteIndex = reversePalette.get(value);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                reversePalette.put(value, paletteIndex);
                palette.add(value);
            }
            if (paletteIndices != null) {
                paletteIndices[i] = paletteIndex;
            }
        }

        // Create the data array and fill it, using the appropriate length palette indices so that it just fits
        final int paletteIndexSize = Math.max((int) Math.ceil(Math.log(palette.size()) / Math.log(2)), minimumWordSize);
        if (paletteIndices != null) {
            final long[] nativeData = NativeSlices.packArrayCube(paletteIndices, paletteIndexSize, straddleLongs);
            if (nativeData != null) {
                return new PackedData(nativeData, palette.toArray((T[]) Array.newInstance(type, palette.size())));
            }
        }
        final long[] data;
        if ((paletteIndexSize == 4) && ((values.length % 16) == 0)) {
            // Optimised special case
            data = new long[values.length >> 4];
            for (int i = 0; i < values.length; i += 16) {
                data[i >> 4] =
                               reversePalette.get(substituteNull(values[i],      nullSubstitute))
                    |         (reversePalette.get(substituteNull(values[i +  1], nullSubstitute))  <<  4)
                    |         (reversePalette.get(substituteNull(values[i +  2], nullSubstitute))  <<  8)
                    |         (reversePalette.get(substituteNull(values[i +  3], nullSubstitute))  << 12)
                    |         (reversePalette.get(substituteNull(values[i +  4], nullSubstitute))  << 16)
                    |         (reversePalette.get(substituteNull(values[i +  5], nullSubstitute))  << 20)
                    |         (reversePalette.get(substituteNull(values[i +  6], nullSubstitute))  << 24)
                    | ((long) (reversePalette.get(substituteNull(values[i +  7], nullSubstitute))) << 28)
                    | ((long) (reversePalette.get(substituteNull(values[i +  8], nullSubstitute))) << 32)
                    | ((long) (reversePalette.get(substituteNull(values[i +  9], nullSubstitute))) << 36)
                    | ((long) (reversePalette.get(substituteNull(values[i + 10], nullSubstitute))) << 40)
                    | ((long) (reversePalette.get(substituteNull(values[i + 11], nullSubstitute))) << 44)
                    | ((long) (reversePalette.get(substituteNull(values[i + 12], nullSubstitute))) << 48)
                    | ((long) (reversePalette.get(substituteNull(values[i + 13], nullSubstitute))) << 52)
                    | ((long) (reversePalette.get(substituteNull(values[i + 14], nullSubstitute))) << 56)
                    | ((long) (reversePalette.get(substituteNull(values[i + 15], nullSubstitute))) << 60);
            }
        } else {
            if (straddleLongs) {
                final BitSet dataBits = new BitSet(arraySize * paletteIndexSize);
                for (int i = 0; i < arraySize; i++) {
                    final int offset = i * paletteIndexSize;
                    final int index = reversePalette.get(substituteNull(values[i], nullSubstitute));
                    for (int j = 0; j < paletteIndexSize; j++) {
                        if ((index & (1 << j)) != 0) {
                            dataBits.set(offset + j);
                        }
                    }
                }
                final long[] dataArray = dataBits.toLongArray();
                // Pad with zeros if necessary TODOMC118 why?
                final int requiredLength = 64 * paletteIndexSize; // TODOMC118 where does this 64 come from?
                if (dataArray.length != requiredLength) {
                    final long[] expandedArray = new long[requiredLength];
                    System.arraycopy(dataArray, 0, expandedArray, 0, dataArray.length);
                    data = expandedArray;
                } else {
                    data = dataArray;
                }
            } else {
                final int wordsPerLong = 64 / paletteIndexSize;
                final int dataSize = arraySize / wordsPerLong + (((arraySize % wordsPerLong) == 0) ? 0 : 1); // Round up
                final BitSet dataBits = new BitSet(dataSize * 64);
                for (int i = 0; i < arraySize; i++) {
                    final int offset = (i / wordsPerLong) * 64 + (i % wordsPerLong) * paletteIndexSize;
                    final int index = reversePalette.get(substituteNull(values[i], nullSubstitute));
                    for (int j = 0; j < paletteIndexSize; j++) {
                        if ((index & (1 << j)) != 0) {
                            dataBits.set(offset + j);
                        }
                    }
                }
                final long[] dataArray = dataBits.toLongArray();
                if (dataArray.length == dataSize) {
                    data = dataArray;
                } else {
                    // If the last bits of the BitSet are zero, toLongArray() does not return those longs, but
                    // Minecraft can't handle that
                    data = Arrays.copyOf(dataArray, dataSize);
                }
            }
        }
        return new PackedData(data, palette.toArray((T[]) Array.newInstance(type, palette.size())));
    }

    private int offset(int x, int y, int z) {
        return x | ((y | (z << bitsPerCoordinate)) << bitsPerCoordinate);
    }

    @SuppressWarnings("unchecked") // The palette uses this cube's declared runtime component type.
    private PackedData packFromPaletteIndexes(T nullSubstitute) {
        final Map<T, Integer> reverse = new HashMap<>(indexedPalette.length * 2);
        final List<T> palette = new ArrayList<>(indexedPalette.length);
        final int[] remap = new int[indexedPalette.length];
        Arrays.fill(remap, -1);
        final int[] packedIndexes = new int[arraySize];
        for (int i = 0; i < arraySize; i++) {
            final int sourceIndex = paletteIndexes[i];
            int packedIndex = remap[sourceIndex];
            if (packedIndex < 0) {
                T value = indexedPalette[sourceIndex];
                if (value == null) {
                    value = nullSubstitute;
                }
                Integer existing = reverse.get(value);
                if (existing == null) {
                    existing = palette.size();
                    reverse.put(value, existing);
                    palette.add(value);
                }
                packedIndex = existing;
                remap[sourceIndex] = packedIndex;
            }
            packedIndexes[i] = packedIndex;
        }
        final int bits = Math.max((int) Math.ceil(Math.log(palette.size()) / Math.log(2)), minimumWordSize);
        final T[] packedPalette = palette.toArray((T[]) Array.newInstance(type, palette.size()));
        if (Native.isExportEnabled() && NativeLoader.areSlicesAvailable()) {
            final long[] nativeData = NativeSlices.packArrayCube(packedIndexes, bits, straddleLongs);
            if (nativeData != null) {
                return new PackedData(nativeData, packedPalette);
            }
        }
        final long[] data;
        if ((bits == 4) && ((arraySize % 16) == 0)) {
            data = new long[arraySize >> 4];
            for (int i = 0; i < arraySize; i += 16) {
                long word = 0;
                for (int j = 0; j < 16; j++) {
                    word |= (long) packedIndexes[i + j] << (j * 4);
                }
                data[i >> 4] = word;
            }
        } else {
            final BitSet bitsOut = new BitSet(arraySize * bits);
            for (int i = 0; i < arraySize; i++) {
                final int offset = straddleLongs ? i * bits : (i / (64 / bits)) * 64 + (i % (64 / bits)) * bits;
                for (int b = 0; b < bits; b++) {
                    if ((packedIndexes[i] & (1 << b)) != 0) {
                        bitsOut.set(offset + b);
                    }
                }
            }
            final long[] raw = bitsOut.toLongArray();
            final int expected;
            if (straddleLongs) {
                expected = 64 * bits;
            } else {
                final int wordsPerLong = 64 / bits;
                expected = arraySize / wordsPerLong + ((arraySize % wordsPerLong == 0) ? 0 : 1);
            }
            data = Arrays.copyOf(raw, expected);
        }
        return new PackedData(data, packedPalette);
    }

    private int[] unpackIndexes(long[] data, int bits, int paletteLength, boolean straddles) {
        if ((bits == 4 || straddles) && Native.isExportEnabled() && NativeLoader.areSlicesAvailable()) {
            final int[] nativeIndexes = NativeSlices.unpackArrayCube(data, arraySize, bits, paletteLength);
            if (nativeIndexes != null) {
                return nativeIndexes;
            }
        }
        final int[] result = new int[arraySize];
        if (bits == 4) {
            for (int i = 0; i < arraySize; i += 16) {
                final long word = data[i >> 4];
                for (int j = 0; j < 16; j++) {
                    result[i + j] = (int) ((word >>> (j * 4)) & 0xf);
                }
            }
        } else if (!straddles) {
            final int perLong = 64 / bits;
            final long mask = (1L << bits) - 1L;
            for (int i = 0; i < arraySize; i++) {
                result[i] = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            }
        } else {
            final BitSet source = BitSet.valueOf(data);
            for (int i = 0; i < arraySize; i++) {
                final int bitOffset = i * bits;
                int index = 0;
                for (int b = 0; b < bits; b++) {
                    if (source.get(bitOffset + b)) {
                        index |= 1 << b;
                    }
                }
                result[i] = index;
            }
        }
        return result;
    }

    private T substituteNull(T value, T nullSubstitute) {
        return (value == null) ? nullSubstitute : value;
    }

    private final Class<T> type;
    private final int arraySize, minimumWordSize, bitsPerCoordinate;
    private final boolean straddleLongs;
    private final T[] values;
    private final boolean paletteIndexStorage;
    private int[] paletteIndexes;
    private T[] indexedPalette;
    private Map<T, Integer> indexedPaletteLookup;

    public class PackedData {
        public PackedData(long[] data, T[] palette) {
            this.data = data;
            this.palette = palette;
        }

        public final long[] data;
        public final T[] palette;
    }
}
