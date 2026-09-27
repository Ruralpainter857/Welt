package org.pepsoft.util;

import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Checks the native Minecraft long-array packer against the original Java path. */
public final class PackedArrayCubeNativeParityTest {
    @Test
    public void nativePackingMatchesJavaForAllCubeLayouts() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            assertMatchesJava(4, false, null, 16, false);
            assertMatchesJava(1, false, "fallback", 25, true);
            assertMatchesJava(1, true, null, 25, true);
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.EXPORT_KEY);
            } else {
                System.setProperty(Native.EXPORT_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeUnpackingMatchesJavaForAllCubeLayouts() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.EXPORT_KEY);
        try {
            assertUnpackMatchesJava(4, false, 16);
            assertUnpackMatchesJava(1, false, 31);
            assertUnpackMatchesJava(1, true, 31);
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.EXPORT_KEY);
            } else {
                System.setProperty(Native.EXPORT_KEY, previousFlag);
            }
        }
    }

    private static void assertUnpackMatchesJava(int minimumWordSize, boolean straddleLongs,
                                                int paletteSize) {
        final PackedArrayCube<String> source = new PackedArrayCube<>(16, minimumWordSize,
                straddleLongs, String.class);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    final int index = x + y * 16 + z * 256;
                    source.setValue(x, y, z, "state-" + ((index * 37 + index / 11) % paletteSize));
                }
            }
        }

        Native.setExportEnabled(false);
        final PackedArrayCube<String>.PackedData packed = source.pack();
        final PackedArrayCube<String> javaDecoded = new PackedArrayCube<>(16, packed.data,
                packed.palette, minimumWordSize, straddleLongs, String.class);
        Native.setExportEnabled(true);
        final int wordSize = Math.max(minimumWordSize,
                (int) Math.ceil(Math.log(packed.palette.length) / Math.log(2)));
        final int[] indexes = NativeSlices.unpackArrayCube(
                packed.data, 4096, wordSize, packed.palette.length);
        assertNotNull(indexes);
        for (int i = 0; i < indexes.length; i++) {
            final String expected = javaDecoded.getValue(i & 15, (i >>> 4) & 15, (i >>> 8) & 15);
            assertEquals(expected, packed.palette[indexes[i]]);
        }
        final PackedArrayCube<String> nativeDecoded = new PackedArrayCube<>(16, packed.data,
                packed.palette, minimumWordSize, straddleLongs, String.class);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(javaDecoded.getValue(x, y, z), nativeDecoded.getValue(x, y, z));
                }
            }
        }
    }

    private static void assertMatchesJava(int minimumWordSize, boolean straddleLongs,
                                          String nullSubstitute, int paletteSize, boolean includeNull) {
        final PackedArrayCube<String> cube = new PackedArrayCube<>(16, minimumWordSize,
                straddleLongs, String.class);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    final int index = x + y * 16 + z * 256;
                    final String value = includeNull && ((index % 97) == 0)
                            ? null : "state-" + ((index * 37 + index / 11) % paletteSize);
                    cube.setValue(x, y, z, value);
                }
            }
        }

        Native.setExportEnabled(false);
        final PackedArrayCube<String>.PackedData javaPacked = cube.pack(nullSubstitute);
        Native.setExportEnabled(true);
        final PackedArrayCube<String>.PackedData nativePacked = cube.pack(nullSubstitute);
        assertArrayEquals(javaPacked.palette, nativePacked.palette);
        assertArrayEquals(javaPacked.data, nativePacked.data);
    }
}
