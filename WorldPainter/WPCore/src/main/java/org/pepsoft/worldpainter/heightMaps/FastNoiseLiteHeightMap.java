package org.pepsoft.worldpainter.heightMaps;

import org.pepsoft.util.IconUtils;
import org.pepsoft.worldpainter.Constants;
import org.pepsoft.worldpainter.heightMaps.noise.FastNoiseLite;

import javax.swing.Icon;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Random;

/** A selectable OpenSimplex2 fractal height map backed by FastNoiseLite. */
public final class FastNoiseLiteHeightMap extends AbstractHeightMap {
    public FastNoiseLiteHeightMap(double height, double scale, int octaves) {
        this(null, height, scale, octaves, new Random().nextLong());
    }

    public FastNoiseLiteHeightMap(double height, double scale, int octaves, long seedOffset) {
        this(null, height, scale, octaves, seedOffset);
    }

    public FastNoiseLiteHeightMap(String name, double height, double scale, int octaves) {
        this(name, height, scale, octaves, new Random().nextLong());
    }

    public FastNoiseLiteHeightMap(String name, double height, double scale, int octaves, long seedOffset) {
        setName(name);
        this.height = height;
        this.scale = scale;
        this.octaves = octaves;
        this.seedOffset = seedOffset;
        validateSettings();
        rebuildNoise();
    }

    public double getHeight() {
        return height;
    }

    public void setHeight(double height) {
        if (!Double.isFinite(height)) {
            throw new IllegalArgumentException("Height must be finite");
        }
        this.height = height;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        if (!Double.isFinite(scale) || (scale <= 0.0)) {
            throw new IllegalArgumentException("Scale must be finite and greater than zero");
        }
        this.scale = scale;
        rebuildNoise();
    }

    public int getOctaves() {
        return octaves;
    }

    public void setOctaves(int octaves) {
        if ((octaves < 1) || (octaves > MAX_OCTAVES)) {
            throw new IllegalArgumentException("Octaves must be between 1 and " + MAX_OCTAVES);
        }
        this.octaves = octaves;
        rebuildNoise();
    }

    public FastNoiseLite.NoiseType getNoiseType() {
        return noiseType;
    }

    public void setNoiseType(FastNoiseLite.NoiseType noiseType) {
        if (noiseType == null) {
            throw new IllegalArgumentException("Noise type must not be null");
        }
        this.noiseType = noiseType;
        rebuildNoise();
    }

    public FastNoiseLite.FractalType getFractalType() {
        return fractalType;
    }

    public void setFractalType(FastNoiseLite.FractalType fractalType) {
        if ((fractalType == null)
                || (fractalType == FastNoiseLite.FractalType.DomainWarpProgressive)
                || (fractalType == FastNoiseLite.FractalType.DomainWarpIndependent)) {
            throw new IllegalArgumentException("Unsupported 2D height map fractal type: " + fractalType);
        }
        this.fractalType = fractalType;
        rebuildNoise();
    }

    public long getSeedOffset() {
        return seedOffset;
    }

    @Override
    public void setSeed(long seed) {
        if (seed != this.seed) {
            super.setSeed(seed);
            rebuildNoise();
        }
    }

    @Override
    public double getHeight(int x, int y) {
        return getHeight((float) x, (float) y);
    }

    @Override
    public double getHeight(float x, float y) {
        final float normalized = (noise.GetNoise(x, y) + 1.0f) * 0.5f;
        return normalized * height;
    }

    @Override
    public FastNoiseLiteHeightMap clone() {
        FastNoiseLiteHeightMap clone = new FastNoiseLiteHeightMap(name, height, scale, octaves, seedOffset);
        clone.noiseType = noiseType;
        clone.fractalType = fractalType;
        clone.setSeed(getSeed());
        clone.rebuildNoise();
        return clone;
    }

    @Override
    public Icon getIcon() {
        return ICON;
    }

    @Override
    public double[] getRange() {
        return new double[] {0.0, height};
    }

    private void validateSettings() {
        if (!Double.isFinite(height)) {
            throw new IllegalArgumentException("Height must be finite");
        }
        if (!Double.isFinite(scale) || (scale <= 0.0)) {
            throw new IllegalArgumentException("Scale must be finite and greater than zero");
        }
        if ((octaves < 1) || (octaves > MAX_OCTAVES)) {
            throw new IllegalArgumentException("Octaves must be between 1 and " + MAX_OCTAVES);
        }
    }

    private void rebuildNoise() {
        noise = new FastNoiseLite((int) (seed + seedOffset));
        noise.SetNoiseType(noiseType);
        noise.SetFractalType(fractalType);
        noise.SetFractalOctaves(octaves);
        noise.SetFractalLacunarity(2.0f);
        noise.SetFractalGain(0.5f);
        noise.SetFrequency((float) (1.0 / (Constants.LARGE_BLOBS * scale)));
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (noiseType == null) {
            noiseType = FastNoiseLite.NoiseType.OpenSimplex2;
        }
        if (fractalType == null) {
            fractalType = FastNoiseLite.FractalType.FBm;
        }
        validateSettings();
        rebuildNoise();
    }

    private double height;
    private double scale;
    private int octaves;
    private final long seedOffset;
    private FastNoiseLite.NoiseType noiseType = FastNoiseLite.NoiseType.OpenSimplex2;
    private FastNoiseLite.FractalType fractalType = FastNoiseLite.FractalType.FBm;
    private transient FastNoiseLite noise;

    private static final int MAX_OCTAVES = 10;
    private static final long serialVersionUID = 1L;
    private static final Icon ICON = IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/noise.png");
}
