package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.heightMaps.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;

/** WTGP v3: post-order coordinate-aware expressions inside the existing fresh-tile transaction. */
final class CoordinateHeightMapSource {
    private static final int HEADER = 128, STRIDE = 96, LIMIT = 64;
    private static final ThreadLocal<Compiler> COMPILERS = ThreadLocal.withInitial(Compiler::new);

    static ProceduralTileSource prepare(HeightMap map, int x, int y, int width, int height) {
        if (map == null || width < 1 || height < 1 || width > 256 || height > 256
                || !Boolean.getBoolean("welt.native.coordinateGraphGeneration")) {
            return null;
        }
        Compiler compiler = COMPILERS.get();
        compiler.count = 0;
        compiler.nested = false;
        compiler.visiting.clear();
        // Leave simpler roots to the existing specialised compiler.
        if (compiler.append(map, 0) < 0 || !compiler.nested) {
            return null;
        }
        int length = HEADER + compiler.count * STRIDE;
        for (int p = 0; p < HEADER; p += 8) {
            compiler.data.putLong(p, 0);
        }
        compiler.data.putInt(0, 0x50475457).putInt(4, 3).putInt(8, length)
                .putInt(12, compiler.count).putInt(16, 6).putInt(20, x).putInt(24, y)
                .putInt(28, width).putInt(32, height);
        return new ProceduralTileSource(Arrays.copyOf(compiler.bytes, length));
    }

    private static final class Compiler {
        final byte[] bytes = new byte[HEADER + LIMIT * STRIDE];
        final ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final IdentityHashMap<HeightMap, Boolean> visiting = new IdentityHashMap<>();
        final double[] matrix = new double[6];
        int count;
        boolean nested;

        int append(HeightMap map, int depth) {
            if (map == null || depth >= LIMIT || count >= LIMIT
                    || visiting.put(map, Boolean.TRUE) != null) {
                return -1;
            }
            try {
                Class<?> type = map.getClass();
                int opcode = type == ConstantHeightMap.class ? 0 : type == NoiseHeightMap.class ? 1
                        : type == SumHeightMap.class ? 2 : type == DifferenceHeightMap.class ? 3
                        : type == ProductHeightMap.class ? 4 : type == MinimisingHeightMap.class ? 5
                        : type == MaximisingHeightMap.class ? 6 : type == TransformingHeightMap.class ? 8
                        : type == DisplacementHeightMap.class ? 9 : type == FastNoiseLiteHeightMap.class ? 11 : -1;
                if (opcode < 0) {
                    return -1;
                }
                // Delegating maps cache constant values; preserve that observable Java behavior.
                if (map.isConstant()) {
                    return constant(map.getConstantValue());
                }
                if (opcode == 0) {
                    return constant(((ConstantHeightMap) map).getHeight());
                }
                int left = 0, right = 0, distance = 0;
                if (opcode >= 2 && opcode <= 6) {
                    CombiningHeightMap combine = (CombiningHeightMap) map;
                    left = append(combine.getHeightMap1(), depth + 1);
                    if (left < 0) return -1;
                    right = append(combine.getHeightMap2(), depth + 1);
                    if (right < 0) return -1;
                } else if (opcode == 8) {
                    left = append(((TransformingHeightMap) map).getBaseHeightMap(), depth + 1);
                    if (left < 0) return -1;
                }
                if (opcode == 9) {
                    DisplacementHeightMap displacement = (DisplacementHeightMap) map;
                    left = append(displacement.getBaseHeightMap(), depth + 1);
                    if (left < 0) return -1;
                    right = append(displacement.getAngleMap(), depth + 1);
                    if (right < 0) return -1;
                    distance = append(displacement.getDistanceMap(), depth + 1);
                    if (distance < 0) return -1;
                }
                if (count >= LIMIT) return -1;
                int index = count++, p = HEADER + index * STRIDE;
                clear(p);
                data.putInt(p, opcode).putInt(p + 4, left).putInt(p + 8, right);
                if (opcode == 1) {
                    NoiseHeightMap noise = (NoiseHeightMap) map;
                    if (!Double.isFinite(noise.getHeight()) || !Double.isFinite(noise.getScale())
                            || noise.getScale() <= 0 || noise.getOctaves() > 10) return -1;
                    data.putDouble(p + 16, noise.getHeight()).putDouble(p + 24, noise.getScale())
                            .putLong(p + 32, noise.getSeed() + noise.getSeedOffset()).putInt(p + 40, noise.getOctaves());
                } else if (opcode == 11) {
                    FastNoiseLiteHeightMap noise = (FastNoiseLiteHeightMap) map;
                    double frequency = 1.0 / (Constants.LARGE_BLOBS * noise.getScale());
                    if (!Double.isFinite(noise.getHeight()) || !Double.isFinite(frequency)
                            || !Float.isFinite((float) frequency) || frequency < 0 || noise.getOctaves() < 1 || noise.getOctaves() > 10) return -1;
                    data.putDouble(p + 16, noise.getHeight()).putDouble(p + 24, frequency)
                            .putLong(p + 32, noise.getSeed() + noise.getSeedOffset()).putInt(p + 40, noise.getOctaves())
                            .putInt(p + 44, noise.getNoiseType().ordinal()).putInt(p + 48, noise.getFractalType().ordinal());
                } else if (opcode == 8) {
                    TransformingHeightMap transform = (TransformingHeightMap) map;
                    if (depth > 0) nested = true;
                    if (transform.copySamplingMatrix(matrix)) {
                        for (int i = 0; i < 6; i++) {
                            if (!Double.isFinite(matrix[i])) return -1;
                            data.putDouble(p + 48 + i * 8, matrix[i]);
                        }
                    } else {
                        data.putInt(p, 7).putInt(p + 40, transform.getOffsetX()).putInt(p + 44, transform.getOffsetY());
                    }
                }
                if (opcode == 9) {
                    if (depth > 0) nested = true;
                    data.putInt(p + 40, distance);
                    HeightMap angle = ((DisplacementHeightMap) map).getAngleMap();
                    if (angle.isConstant()) {
                        // Compute constant-angle trig once with the actual JVM implementation.
                        double value = angle.getConstantValue();
                        data.putInt(p, 10).putDouble(p + 48, Math.sin(value)).putDouble(p + 56, Math.cos(value));
                    }
                }
                return index;
            } finally {
                visiting.remove(map);
            }
        }

        int constant(double value) {
            if (count >= LIMIT) return -1;
            int index = count++, p = HEADER + index * STRIDE;
            clear(p);
            data.putDouble(p + 16, value);
            return index;
        }

        void clear(int p) {
            for (int i = 0; i < STRIDE; i += 8) {
                data.putLong(p + i, 0);
            }
        }
    }
}
