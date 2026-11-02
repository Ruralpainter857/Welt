package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.heightMaps.*;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/** Conservative coordinate windows for bitmap leaves of one complete tile expression.
 * Pixels stay in their original precision; only a reusable raster row is expanded to doubles.
 * Unsupported or excessively large domains retain complete Java generation.
 */
final class GraphBitmapWindows {
    static final int MAX_CELLS = 262144, MAX_SIDE = 4096;
    private static final double MAX_COORDINATE = 8388600;
    private static final ThreadLocal<double[]> ROWS = ThreadLocal.withInitial(() -> new double[128]);

    record Domain(double minX, double maxX, double minY, double maxY, boolean integer) {
        Domain floating() { return new Domain((float) minX, (float) maxX, (float) minY, (float) maxY, false); }
        boolean finite() { return Double.isFinite(minX) && Double.isFinite(maxX) && Double.isFinite(minY) && Double.isFinite(maxY)
                && minX <= maxX && minY <= maxY && minX >= -MAX_COORDINATE && maxX <= MAX_COORDINATE
                && minY >= -MAX_COORDINATE && maxY <= MAX_COORDINATE; }
    }

    record Window(BitmapHeightMap bitmap, int x, int y, int width, int height) {
        // WTG bitmap kinds: u8, u16, i16, u32, f32, f64.
        int kind() {
            if (bitmap.isFloatingPoint()) return bitmap.getBitDepth() == 32 ? 5 : 6;
            if (bitmap.isSigned()) return 3;
            return bitmap.getBitDepth() <= 8 ? 1 : bitmap.getBitDepth() <= 16 ? 2 : 4;
        }
        int stride() { return switch (kind()) { case 1 -> 1; case 2, 3 -> 2; case 4, 5 -> 4; default -> 8; }; }
        int bytes() { return width * height * stride(); }
        void write(ByteBuffer destination, int offset) {
            double[] row = ROWS.get();
            if (row.length < width) { row = new double[width]; ROWS.set(row); }
            var raster = bitmap.getImage().getRaster();
            int at = offset, kind = kind();
            for (int dy = 0; dy < height; dy++) {
                int worldY = y + dy;
                int sourceY = bitmap.isRepeat() ? Math.floorMod(worldY, bitmap.getHeight()) : worldY;
                int column = 0;
                while (column < width) {
                    int worldX = x + column, sourceX, count;
                    boolean empty = !bitmap.isRepeat() && (sourceY < 0 || sourceY >= bitmap.getHeight());
                    if (bitmap.isRepeat()) {
                        sourceX = Math.floorMod(worldX, bitmap.getWidth());
                        count = Math.min(width - column, bitmap.getWidth() - sourceX);
                    } else if (worldX < 0) {
                        sourceX = 0; count = Math.min(width - column, -worldX); empty = true;
                    } else if (worldX >= bitmap.getWidth()) {
                        sourceX = 0; count = width - column; empty = true;
                    } else {
                        sourceX = worldX; count = Math.min(width - column, bitmap.getWidth() - sourceX);
                    }
                    if (!empty) raster.getSamples(sourceX, sourceY, count, 1, bitmap.getChannel(), row);
                    for (int i = 0; i < count; i++) {
                        double value = empty ? bitmap.getMinHeight() : row[i];
                        if (kind == 4 && value < 0) value += 4294967296.0;
                        switch (kind) {
                            case 1 -> destination.put(at++, (byte) (int) value);
                            case 2, 3 -> { destination.putShort(at, (short) (int) value); at += 2; }
                            case 4 -> { destination.putInt(at, (int) (long) value); at += 4; }
                            case 5 -> { destination.putFloat(at, (float) value); at += 4; }
                            default -> { destination.putDouble(at, value); at += 8; }
                        }
                    }
                    column += count;
                }
            }
        }
    }

    static Map<BitmapHeightMap, Window> prepare(HeightMap root, int x, int y, int width, int height) {
        long endX = (long) x + width - 1, endY = (long) y + height - 1;
        if (width < 1 || height < 1 || endX > Integer.MAX_VALUE || endY > Integer.MAX_VALUE) return null;
        var planner = new Planner();
        return planner.visit(root, new Domain(x, endX, y, endY, true), 0) ? planner.windows : null;
    }

    private static final class Planner {
        final IdentityHashMap<BitmapHeightMap, Window> windows = new IdentityHashMap<>();
        final IdentityHashMap<HeightMap, Boolean> visiting = new IdentityHashMap<>();
        int visits;

        boolean visit(HeightMap map, Domain domain, int depth) {
            if (map == null || depth >= 64 || ++visits > 64 || !domain.finite() || visiting.put(map, true) != null) return false;
            try {
                Class<?> type = map.getClass();
                if (type == BitmapHeightMap.class) return bitmap((BitmapHeightMap) map, domain);
                if (type == ConstantHeightMap.class || type == NoiseHeightMap.class || type == FastNoiseLiteHeightMap.class
                        || type == BandedHeightMap.class || type == MandelbrotHeightMap.class) return true;
                if (type == SumHeightMap.class || type == DifferenceHeightMap.class || type == ProductHeightMap.class
                        || type == MinimisingHeightMap.class || type == MaximisingHeightMap.class) {
                    if (map.isConstant()) return true;
                    CombiningHeightMap pair = (CombiningHeightMap) map;
                    return visit(pair.getHeightMap1(), domain, depth + 1) && visit(pair.getHeightMap2(), domain, depth + 1);
                }
                if (type == TransformingHeightMap.class) {
                    if (map.isConstant()) return true;
                    TransformingHeightMap transform = (TransformingHeightMap) map;
                    double[] matrix = new double[6];
                    Domain child;
                    if (!transform.copySamplingMatrix(matrix)) {
                        double dx = transform.getOffsetX(), dy = transform.getOffsetY();
                        child = domain.integer ? new Domain(domain.minX - dx, domain.maxX - dx, domain.minY - dy, domain.maxY - dy, true)
                                : new Domain((float) domain.minX - (float) dx, (float) domain.maxX - (float) dx,
                                        (float) domain.minY - (float) dy, (float) domain.maxY - (float) dy, false);
                    } else {
                        Domain input = domain.floating();
                        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
                        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                        var affine = new java.awt.geom.AffineTransform(matrix);
                        var source = new java.awt.geom.Point2D.Float(); var target = new java.awt.geom.Point2D.Float();
                        for (double x : new double[]{input.minX, input.maxX}) for (double y : new double[]{input.minY, input.maxY}) {
                            source.setLocation(x, y); affine.transform(source, target);
                            minX = Math.min(minX, target.x); maxX = Math.max(maxX, target.x);
                            minY = Math.min(minY, target.y); maxY = Math.max(maxY, target.y);
                        }
                        child = new Domain(minX, maxX, minY, maxY, false);
                    }
                    return visit(transform.getBaseHeightMap(), child, depth + 1);
                }
                if (type == BicubicHeightMap.class) {
                    if (map.isConstant()) return true;
                    BicubicHeightMap cubic = (BicubicHeightMap) map;
                    int[] bounds = new int[7]; cubic.copySamplingBounds(bounds);
                    Domain child = domain;
                    if (!domain.integer) {
                        double[] x = lattice(domain.minX, domain.maxX), y = lattice(domain.minY, domain.maxY);
                        child = new Domain(x[0], x[1], y[0], y[1], false);
                    }
                    if ((bounds[0] & 1) != 0) {
                        if (bounds[1] <= 0 || bounds[2] <= 0) return false;
                        double[] x = repeated(child.minX, child.maxX, bounds[1]), y = repeated(child.minY, child.maxY, bounds[2]);
                        child = new Domain(x[0], x[1], y[0], y[1], domain.integer);
                    } else if (!domain.integer && (bounds[0] & 2) != 0) {
                        if (bounds[1] <= 0 || bounds[2] <= 0 || bounds[3] != 0 || bounds[4] != 0
                                || bounds[5] != bounds[1] || bounds[6] != bounds[2]) return false;
                        child = new Domain(clamp(child.minX, bounds[1]), clamp(child.maxX, bounds[1]),
                                clamp(child.minY, bounds[2]), clamp(child.maxY, bounds[2]), false);
                    }
                    return visit(cubic.getHeightMap(0), child, depth + 1);
                }
                if (type == DisplacementHeightMap.class) {
                    if (map.isConstant()) return true;
                    DisplacementHeightMap displacement = (DisplacementHeightMap) map;
                    Domain input = domain.floating();
                    if (!visit(displacement.getAngleMap(), input, depth + 1) || !visit(displacement.getDistanceMap(), input, depth + 1)) return false;
                    double distance = magnitude(displacement.getDistanceMap(), 0);
                    if (!Double.isFinite(distance)) return false;
                    Domain child = new Domain(Math.nextDown((float) (input.minX - distance)), Math.nextUp((float) (input.maxX + distance)),
                            Math.nextDown((float) (input.minY - distance)), Math.nextUp((float) (input.maxY + distance)), false);
                    return visit(displacement.getBaseHeightMap(), child, depth + 1);
                }
                return false;
            } finally { visiting.remove(map); }
        }

        private boolean bitmap(BitmapHeightMap bitmap, Domain domain) {
            var raster = bitmap.getImage().getRaster();
            if (!raster.getClass().getName().startsWith("sun.awt.image.")
                    || !raster.getSampleModel().getClass().getName().startsWith("java.awt.image.")
                    || !raster.getDataBuffer().getClass().getName().startsWith("java.awt.image.DataBuffer")
                    || bitmap.getChannel() < 0 || bitmap.getChannel() >= raster.getNumBands()) return false;
            Rectangle extent = bitmap.getExtent();
            if (!bitmap.isRepeat() && (extent == null || extent.x != 0 || extent.y != 0
                    || extent.width != bitmap.getWidth() || extent.height != bitmap.getHeight())) return false;
            int x = (int) domain.minX, maxX = (int) domain.maxX, y = (int) domain.minY, maxY = (int) domain.maxY;
            Window old = windows.get(bitmap);
            if (old != null) { x = Math.min(x, old.x); y = Math.min(y, old.y); maxX = Math.max(maxX, old.x + old.width - 1); maxY = Math.max(maxY, old.y + old.height - 1); }
            long width = (long) maxX - x + 1, height = (long) maxY - y + 1;
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || width * height > MAX_CELLS) return false;
            windows.put(bitmap, new Window(bitmap, x, y, (int) width, (int) height)); return true;
        }
    }
    /** Use metadata, never an image-wide range scan, when bounding a displacement control. */
    private static double magnitude(HeightMap map, int depth) {
        if (depth >= 64) return Double.POSITIVE_INFINITY;
        if (map.isConstant()) return Math.abs(map.getConstantValue());
        if (map.getClass() == BitmapHeightMap.class) {
            BitmapHeightMap image = (BitmapHeightMap) map;
            return Math.max(Math.abs(image.getMinHeight()), Math.abs(image.getMaxHeight()));
        }
        if (map.getClass() == TransformingHeightMap.class) return magnitude(((TransformingHeightMap) map).getBaseHeightMap(), depth + 1);
        if (map.getClass() == BicubicHeightMap.class) return magnitude(((BicubicHeightMap) map).getHeightMap(0), depth + 1);
        if (map.getClass() == DisplacementHeightMap.class) return magnitude(((DisplacementHeightMap) map).getBaseHeightMap(), depth + 1);
        if (map instanceof CombiningHeightMap pair) {
            double a = magnitude(pair.getHeightMap1(), depth + 1), b = magnitude(pair.getHeightMap2(), depth + 1);
            if (map.getClass() == ProductHeightMap.class) return Math.nextUp(a * b);
            if (map.getClass() == MinimisingHeightMap.class || map.getClass() == MaximisingHeightMap.class) return Math.max(a, b);
            return Math.nextUp(a + b);
        }
        double[] range = map.getRange();
        return range.length == 1 ? Math.abs(range[0]) : Math.max(Math.abs(range[0]), Math.abs(range[1]));
    }
    private static double[] lattice(double min, double max) {
        float a = (float) min, b = (float) max;
        float x = a - Math.signum(a) / 2, y = b - Math.signum(b) / 2;
        double lo = Math.min(x, y), hi = Math.max(x, y);
        if (a <= 0 && b >= 0) { lo = Math.min(lo, -.5); hi = Math.max(hi, .5); }
        return new double[]{Math.floor(lo) - 1, Math.floor(hi) + 2};
    }
    private static double[] repeated(double min, double max, int period) {
        if (Math.floor(min / period) != Math.floor(max / period)) return new double[]{0, period - 1};
        double first = min - Math.floor(min / period) * period, last = max - Math.floor(max / period) * period;
        return new double[]{Math.max(0, first - 1), Math.min(period - 1, last + 1)};
    }
    private static double clamp(double value, int size) { return Math.max(0, Math.min(size - 1, value)); }
}
