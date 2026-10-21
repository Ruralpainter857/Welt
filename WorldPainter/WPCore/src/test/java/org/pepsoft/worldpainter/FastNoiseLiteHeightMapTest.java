package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.FastNoiseLiteHeightMap;
import org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public final class FastNoiseLiteHeightMapTest {
    @Test
    public void settingsCloneAndSerializationPreserveTheMap() throws Exception {
        final FastNoiseLiteHeightMap original = new FastNoiseLiteHeightMap("FNL", 320.0, 1.75, 4, 0x1234_5678L);
        original.setSeed(0x7654_3210L);
        original.setNoiseType(FastNoiseLite.NoiseType.Cellular);
        original.setFractalType(FastNoiseLite.FractalType.Ridged);
        final FastNoiseLiteHeightMap clone = original.clone();

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        final FastNoiseLiteHeightMap restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (FastNoiseLiteHeightMap) input.readObject();
        }

        for (int[] point : new int[][] {{-129, 73}, {0, 0}, {8123, -9012}}) {
            final long expected = Double.doubleToRawLongBits(original.getHeight(point[0], point[1]));
            assertEquals(expected, Double.doubleToRawLongBits(clone.getHeight(point[0], point[1])));
            assertEquals(expected, Double.doubleToRawLongBits(restored.getHeight(point[0], point[1])));
        }
        assertEquals("FNL", restored.getName());
        assertEquals(320.0, restored.getHeight(), 0.0);
        assertEquals(1.75, restored.getScale(), 0.0);
        assertEquals(4, restored.getOctaves());
        assertEquals(FastNoiseLite.NoiseType.Cellular, restored.getNoiseType());
        assertEquals(FastNoiseLite.FractalType.Ridged, restored.getFractalType());
    }

    @Test
    public void missingNoiseSettingsRetainTheOriginalDefaults() throws Exception {
        final FastNoiseLiteHeightMap oldFormat = new FastNoiseLiteHeightMap(320.0, 1.75, 4, 0x1234_5678L);
        final Field noiseType = FastNoiseLiteHeightMap.class.getDeclaredField("noiseType");
        final Field fractalType = FastNoiseLiteHeightMap.class.getDeclaredField("fractalType");
        noiseType.setAccessible(true);
        fractalType.setAccessible(true);
        noiseType.set(oldFormat, null);
        fractalType.set(oldFormat, null);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(oldFormat);
        }
        final FastNoiseLiteHeightMap restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (FastNoiseLiteHeightMap) input.readObject();
        }

        assertEquals(FastNoiseLite.NoiseType.OpenSimplex2, restored.getNoiseType());
        assertEquals(FastNoiseLite.FractalType.FBm, restored.getFractalType());
    }

    @Test
    public void nativeTreeMatchesJavaHeightMap() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        Native.setGenEnabled(true);
        try {
            final double scale = 1.375;
            final int originX = -1024;
            final int originY = 537;
            final int width = 19;
            final int height = 13;
            final float[] xCoordinates = {-31.25f, -0.5f, 12.75f, 300.125f};
            final float[] yCoordinates = {47.5f, -9.25f, 0.125f, -512.75f};
            final FastNoiseLite.FractalType[] fractalTypes = {
                    FastNoiseLite.FractalType.None,
                    FastNoiseLite.FractalType.FBm,
                    FastNoiseLite.FractalType.Ridged,
                    FastNoiseLite.FractalType.PingPong
            };
            for (FastNoiseLite.NoiseType noiseType : FastNoiseLite.NoiseType.values()) {
                for (FastNoiseLite.FractalType fractalType : fractalTypes) {
                    final FastNoiseLiteHeightMap map = new FastNoiseLiteHeightMap(
                            384.0, scale, 5, 0x1234_5678_9abc_def0L);
                    map.setSeed(0x0fed_cba9_8765_4321L);
                    map.setNoiseType(noiseType);
                    map.setFractalType(fractalType);
                    final int[] opcodes = {13};
                    final double[] values = {map.getHeight()};
                    final double[] scales = {1.0 / (Constants.LARGE_BLOBS * map.getScale())};
                    final int[] octaves = {0x4000_0000 | map.getOctaves()
                            | (map.getNoiseType().ordinal() << 8)
                            | (map.getFractalType().ordinal() << 16)};
                    final long[] seeds = {map.getSeed() + map.getSeedOffset()};
                    final double[] output = new double[width * height];

                    assertTrue("FastNoiseLite opcode should accept " + noiseType + "/" + fractalType,
                            NativeSlices.fillHeightMapTree(originX, originY, width, height,
                                    1, opcodes, values, scales, octaves, seeds, output));
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            final double javaValue = map.getHeight(originX + x, originY + y);
                            assertEquals(noiseType + "/" + fractalType + " x=" + (originX + x)
                                            + " y=" + (originY + y),
                                    Double.doubleToRawLongBits(javaValue),
                                    Double.doubleToRawLongBits(output[y * width + x]));
                        }
                    }

                    final double[] pointOutput = new double[xCoordinates.length];
                    assertTrue("FastNoiseLite points should accept " + noiseType + "/" + fractalType,
                            NativeSlices.fillHeightMapTreePoints(1, opcodes, values, scales,
                                    octaves, seeds, xCoordinates, yCoordinates, pointOutput));
                    for (int index = 0; index < xCoordinates.length; index++) {
                        assertEquals(noiseType + "/" + fractalType + " point=" + index,
                                Double.doubleToRawLongBits(map.getHeight(xCoordinates[index], yCoordinates[index])),
                                Double.doubleToRawLongBits(pointOutput[index]));
                    }
                }
            }

            final FastNoiseLiteHeightMap legacyDefaultMap =
                    new FastNoiseLiteHeightMap(384.0, scale, 5, 0x1234_5678_9abc_def0L);
            legacyDefaultMap.setSeed(0x0fed_cba9_8765_4321L);
            final double[] legacyOutput = new double[width * height];
            assertTrue("Legacy FastNoiseLite settings should retain OpenSimplex2 FBm defaults",
                    NativeSlices.fillHeightMapTree(originX, originY, width, height, 1,
                            new int[] {13}, new double[] {legacyDefaultMap.getHeight()},
                            new double[] {1.0 / (Constants.LARGE_BLOBS * legacyDefaultMap.getScale())},
                            new int[] {legacyDefaultMap.getOctaves()},
                            new long[] {legacyDefaultMap.getSeed() + legacyDefaultMap.getSeedOffset()},
                            legacyOutput));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    assertEquals("legacy default x=" + (originX + x) + " y=" + (originY + y),
                            Double.doubleToRawLongBits(legacyDefaultMap.getHeight(originX + x, originY + y)),
                            Double.doubleToRawLongBits(legacyOutput[y * width + x]));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }
}
