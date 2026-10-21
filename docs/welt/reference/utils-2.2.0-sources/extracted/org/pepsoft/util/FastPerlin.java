package org.pepsoft.util;

import java.io.Serial;
import java.io.Serializable;

/**
 * Fast implementation of Perlin noise.
 *
 * @author <a href="https://github.com/MCRcortex">MCRcortex</a>
 */
public class FastPerlin implements Serializable {
    public FastPerlin(long seed) {
        final UnsafeRandom r = new UnsafeRandom(seed);
        final byte[] permutation = new byte[256];
        for (int i = 0; i < 256; ++i) {
            permutation[i] = (byte) i;
        }

        for (int i = 0; i < 256; ++i) {
            final int j = r.nextInt(256 - i);
            final byte b = permutation[255 - i];
            permutation[255 - i] = permutation[j];
            permutation[j] = b;
        }

        for (int i = 0; i < 256; i++) {
            permPair[i] = (short) ((permutation[i] & 0xFF) | ((permutation[(i + 1) & 0xFF] & 0xFF) << 8));
        }
    }

    public float sampleResult(double X) {
        final double floorX = Math.floor(X);

        final float lx = (float) (X - floorX);

        final int x = getPair((int) floorX);

        return lerp(fade(lx),
                grad(getPair(getPair(x)), lx),
                grad(getPair(getPair(x >> 8)), lx - 1.0f));
    }

    public float sampleResult(double X, double Y) {
        final double floorX = Math.floor(X);
        final double floorY = Math.floor(Y);

        final int by = (int) floorY;

        final float lx = (float) (X - floorX);
        final float ly = (float) (Y - floorY);

        final int x = getPair((int) floorX);
        final int x0y = getPair(x + by);
        final int x1y = getPair((x >> 8) + by);

        final float py = fade(ly);

        return lerp(fade(lx),
                lerp(py,
                        grad(getPair(x0y), lx, ly),
                        grad(getPair(x0y >> 8), lx, ly - 1.0f)),
                lerp(py,
                        grad(getPair(x1y), lx - 1.0f, ly),
                        grad(getPair(x1y >> 8), lx - 1.0f, ly - 1.0f)));
    }

    public float sampleResult(double X, double Y, double Z) {
        final double floorX = Math.floor(X);
        final double floorY = Math.floor(Y);
        final double floorZ = Math.floor(Z);

        final int by = (int) floorY;
        final int bz = (int) floorZ;

        final float lx = (float) (X - floorX);
        final float ly = (float) (Y - floorY);
        final float lz = (float) (Z - floorZ);

        final int x = getPair((int) floorX);
        final int x0y = getPair(x + by);
        final int x1y = getPair((x >> 8) + by);
        final int x0y0z = getPair(x0y + bz);
        final int x0y1z = getPair((x0y >> 8) + bz);
        final int x1y0z = getPair(x1y + bz);
        final int x1y1z = getPair((x1y >> 8) + bz);

        final float py = fade(ly);
        final float pz = fade(lz);

        return lerp(fade(lx),
                lerp(py,
                        lerp(pz,
                                grad(x0y0z, lx, ly, lz),
                                grad(x0y0z >> 8, lx, ly, lz - 1.0f)),
                        lerp(pz,
                                grad(x0y1z, lx, ly - 1.0f, lz),
                                grad(x0y1z >> 8, lx, ly - 1.0f, lz - 1.0f))),
                lerp(py,
                        lerp(pz,
                                grad(x1y0z, lx - 1.0f, ly, lz),
                                grad(x1y0z >> 8, lx - 1.0f, ly, lz - 1.0f)),
                        lerp(pz,
                                grad(x1y1z, lx - 1.0f, ly - 1.0f, lz),
                                grad(x1y1z >> 8, lx - 1.0f, ly - 1.0f, lz - 1.0f))));
    }

    private int getPair(int idx) {
        return permPair[idx & 0xFF] & 0xFFFF;
    }

    private static float fade(float v) {
        return v * v * v * Math.fma(v, Math.fma(v, 6f, -15f), 10f);
    }

    private static float grad(int v, float x, float y, float z) {
        v = (v & 15) * 3;
        return Math.fma(x, LUT2[v], Math.fma(y, LUT2[v + 1], z * LUT2[v + 2]));
    }

    private static float grad(int v, float x) {
        v = (v & 15) * 3;
        return x * LUT2[v];
    }

    private static float grad(int v, float x, float y) {
        v = (v & 15) * 3;
        return Math.fma(x, LUT2[v], y * LUT2[v + 1]);
    }

    private static float lerp(float progress, float a, float b) {
        return Math.fma(b - a, progress, a);
    }

    private final short[] permPair = new short[256];

    private static final float[] LUT2 = {
        1, 1, 0,
        -1, 1, 0,
        1, -1, 0,
        -1, -1, 0,
        1, 0, 1,
        -1, 0, 1,
        1, 0, -1,
        -1, 0, -1,
        0, 1, 1,
        0, -1, 1,
        0, 1, -1,
        0, -1, -1,
        1, 1, 0,
        0, -1, 1,
        -1, 1, 0,
        0, -1, -1
    };
    @Serial
    private static final long serialVersionUID = 1L;
}