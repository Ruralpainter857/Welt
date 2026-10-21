/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.brushes;

import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/**
 * An object which calculates brush strengths for simple mathematical brushes,
 * which must be symmetric in both the x and y axes (so that only one quadrant
 * of the brush needs to be calculated).
 *
 * @author pepijn
 */
public abstract class SymmetricBrush extends AbstractBrush {
    public SymmetricBrush(String name, BrushShape brushShape, boolean rotationallySymmetric) {
        this(name, brushShape, rotationallySymmetric, false);
    }

    /** Only opt in when calcStrength is independent of the current level. */
    protected SymmetricBrush(String name, BrushShape brushShape, boolean rotationallySymmetric,
                             boolean stableShape) {
        super(name);
        this.brushShape = brushShape;
        this.rotationallySymmetric = rotationallySymmetric;
        this.stableShape = stableShape;
        cacheStrengths();
    }

    @Override
    public BrushShape getBrushShape() {
        return brushShape;
    }

    @Override
    public int getRadius() {
        return radius;
    }

    @Override
    public void setRadius(int radius) {
        if (radius != this.radius) {
            this.radius = radius;
            cacheStrengths();
        }
    }

    @Override
    public float getLevel() {
        return level;
    }

    @Override
    public void setLevel(float level) {
        if (level != this.level) {
            this.level = level;
            cacheStrengths();
        }
    }

    public final boolean isRotationallySymmetric() {
        return rotationallySymmetric;
    }

    @Override
    public final float getStrength(int dx, int dy) {
        return strengthCache[index(dx, dy)];
    }
    
    @Override
    public final float getFullStrength(int dx, int dy) {
        return fullStrengthCache[index(dx, dy)];
    }
    
    @Override
    public String toString() {
        return getName() + " (radius=" + radius + ", brushShape=" + brushShape + ", level=" + level + ')';
    }

    @Override
    public SymmetricBrush clone() {
        SymmetricBrush clone = (SymmetricBrush) super.clone();
        // Freeze the shape plane when shared. Mutable intensity planes remain private.
        fullStrengthShared = true;
        clone.fullStrengthShared = true;
        clone.strengthCache = strengthCache == fullStrengthCache ? fullStrengthCache : strengthCache.clone();
        return clone;
    }

    protected abstract float calcStrength(int dx, int dy);

    /** Package-owned raster output; no mutable cache storage is exposed. */
    final boolean fillFullStrengthRaster(byte[] output) {
        return NativeSlices.expandBrushRaster(fullStrengthCache, cacheSide, radius, output);
    }
    private int index(int dx, int dy) {
        int x = Math.abs(dx), y = Math.abs(dy);
        if (x < 0 || y < 0 || x >= cacheSide || y >= cacheSide) {
            throw new ArrayIndexOutOfBoundsException("Brush coordinate outside cached quadrant");
        }
        return x * cacheSide + y;
    }

    private void cacheStrengths() {
        boolean radiusChanged = radius != cachedRadius;
        if (!radiusChanged && level == cachedLevel) return;
        if (radiusChanged) {
            int requiredSide = Math.addExact(radius, 1);
            if (requiredSide < 0) throw new NegativeArraySizeException();
            if (fullStrengthCache == null || fullStrengthShared || cacheSide < requiredSide) {
                cacheSide = requiredSide;
                fullStrengthCache = new float[Math.multiplyExact(cacheSide, cacheSide)];
            }
            // Reuse a private plane; detach before changing a plane shared with clones.
            fullStrengthShared = false;
            for (int dx = 0; dx <= radius; dx++) for (int dy = 0; dy <= radius; dy++) {
                fullStrengthCache[dx * cacheSide + dy] = calcStrength(dx, dy);
            }
        }
        if (level == 1.0f && stableShape) {
            strengthCache = fullStrengthCache;
        } else {
            if (strengthCache == null || strengthCache == fullStrengthCache || strengthCache.length != fullStrengthCache.length) {
                strengthCache = new float[fullStrengthCache.length];
            }
            if (radiusChanged || stableShape) {
                if (!NativeSlices.scaleBrushCache(fullStrengthCache, level, strengthCache)) {
                    for (int i = 0; i < strengthCache.length; i++) strengthCache[i] = fullStrengthCache[i] * level;
                }
            } else {
                // Custom brushes may consult the level while calculating their shape.
                for (int dx = 0; dx <= radius; dx++) for (int dy = 0; dy <= radius; dy++) {
                    strengthCache[dx * cacheSide + dy] = calcStrength(dx, dy) * level;
                }
            }
        }
        cachedRadius = radius;
        cachedLevel = level;
    }

    protected final boolean rotationallySymmetric;
    protected final BrushShape brushShape;
    private final boolean stableShape;
    private int radius, cachedRadius = -1, cacheSide;
    private float[] strengthCache, fullStrengthCache;
    private boolean fullStrengthShared;
    private float level = 1.0f, cachedLevel = -1.0f;

    public static final SymmetricBrush CONSTANT_CIRCLE = new RadialBrush("Constant Circle", BrushShape.CIRCLE, true, true) {
        @Override
        protected float calcStrength(float dr) {
            return 1.0f;
        }
    };

    public static final SymmetricBrush CONSTANT_SQUARE = new RadialBrush("Constant Square", BrushShape.SQUARE, false, true) {
        @Override
        protected float calcStrength(float dr) {
            return 1.0f;
        }
    };
    
    public static final SymmetricBrush LINEAR_CIRCLE = new RadialBrush("Linear Circle", BrushShape.CIRCLE, true, true) {
        @Override
        protected float calcStrength(float dr) {
            return 1.0f - dr;
        }
    };

    public static final SymmetricBrush LINEAR_SQUARE = new RadialBrush("Linear Square", BrushShape.SQUARE, false, true) {
        @Override
        protected float calcStrength(float dr) {
            return 1.0f - dr;
        }
    };

    public static final SymmetricBrush COSINE_CIRCLE = new RadialBrush("Sine Circle", BrushShape.CIRCLE, true, true) {
        @Override
        protected float calcStrength(float dr) {
            return (float) Math.cos(dr * Math.PI) / 2 + 0.5f;
        }
    };
    
    public static final SymmetricBrush COSINE_SQUARE = new RadialBrush("Sine Square", BrushShape.SQUARE, false, true) {
        @Override
        protected float calcStrength(float dr) {
            return (float) Math.cos(dr * Math.PI) / 2 + 0.5f;
        }
    };

    public static final SymmetricBrush PLATEAU_CIRCLE = new RadialBrush("Plateau Circle", BrushShape.CIRCLE, true, true) {
        @Override
        protected float calcStrength(float dr) {
            if (dr <= 0.5f) {
                return 1.0f;
            } else {
                return (float) Math.cos((dr - 0.5f) * TWO_PI) / 2 + 0.5f;
            }
        }
    };
    
    public static final SymmetricBrush PLATEAU_SQUARE = new RadialBrush("Plateau Square", BrushShape.SQUARE, false, true) {
        @Override
        protected float calcStrength(float dr) {
            if (dr <= 0.5f) {
                return 1.0f;
            } else {
                return (float) Math.cos((dr - 0.5f) * TWO_PI) / 2 + 0.5f;
            }
        }
    };

    public static final SymmetricBrush SPIKE_CIRCLE = new RadialBrush("Spike Circle", BrushShape.CIRCLE, true, true) {
        @Override
        protected float calcStrength(float dr) {
            return (1.0f - dr) * (1.0f - dr);
        }
    };

    public static final SymmetricBrush SPIKE_SQUARE = new RadialBrush("Spike Square", BrushShape.SQUARE, false, true) {
        @Override
        protected float calcStrength(float dr) {
            return (1.0f - dr) * (1.0f - dr);
        }
    };

    public static final SymmetricBrush DOME_CIRCLE = new RadialBrush("Dome Circle", BrushShape.CIRCLE, true, true) {
        @Override
        protected float calcStrength(float dr) {
            return (float) Math.sqrt(1 - dr * dr);
        }
    };

    public static final SymmetricBrush DOME_SQUARE = new RadialBrush("Dome Square", BrushShape.SQUARE, false, true) {
        @Override
        protected float calcStrength(float dr) {
            return (float) Math.sqrt(1 - dr * dr);
        }
    };
    
    private static final float TWO_PI = (float) (Math.PI * 2);
}