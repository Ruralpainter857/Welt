package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/**
 * Lissage sans thème ni filtre dépendant des mutations, avec un instantané de la bordure de cinq cellules.
 * Le demandeur sérialise les éditions de la dimension. Un appel JNI traite toute la zone ; les écritures
 * groupées par tuile respectent l'annulation. L'entrée reste intacte pendant tous les calculs de voisinage.
 */
public final class SmoothHeightAccess {
    // Activation explicite tant que les mesures de bout en bout ne montrent pas un gain reproductible.
    public static final String ENABLE_KEY = "wp.native.gen.smoothCompact";
    public static boolean isEnabled() { return Boolean.parseBoolean(System.getProperty(ENABLE_KEY, "false")); }
    private SmoothHeightAccess() { }
    private static final ThreadLocal<Scratch> BUFFER = new ThreadLocal<>();

    public static boolean tryApply(Dimension dimension, int ox, int oy, int width, int height, float[] strengths) {
        if (width <= 0 || height <= 0 || width > 246 || height > 246 || strengths.length != width * height
                || !TileRegionAccess.canBatch(dimension, ox - 5, oy - 5, width + 10, height + 10)) return false;
        int iw = width + 10, ih = height + 10, area = width * height;
        int forces = 48 + iw * ih * 4, output = forces + area * 4, mask = output + area * 4, length = mask + area;
        Scratch scratch = BUFFER.get();
        if (scratch == null || scratch.buffer.capacity() < length) { scratch = new Scratch(length); BUFFER.set(scratch); }
        ByteBuffer buffer = scratch.buffer; buffer.clear().limit(length);
        buffer.putInt(0, 0x4d534c57).putInt(4, 1).putInt(8, iw).putInt(12, ih).putInt(16, dimension.getMinHeight())
                .putInt(20, 48).putInt(24, forces).putInt(28, output).putInt(32, mask).putInt(36, 0).putInt(40, 0).putInt(44, 0);
        scratch.input.clear().limit(iw * ih);
        for (int x = 0; x < iw;) {
            int wx = ox - 5 + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(iw - x, TILE_SIZE - lx);
            for (int y = 0; y < ih;) {
                int wy = oy - 5 + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(ih - y, TILE_SIZE - ly);
                Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.copyHeightRegionDirect(lx, ly, rw, rh, scratch.input, x * ih + y, ih);
                else for (int dx = 0; dx < rw; dx++) for (int dy = 0; dy < rh; dy++) scratch.input.put((x + dx) * ih + y + dy, -Float.MAX_VALUE);
                y += rh;
            }
            x += rw;
        }
        scratch.forces.clear().position(forces / 4).limit(forces / 4 + area); scratch.forces.put(strengths);
        if (!NativeSlices.smoothCompactRegion(buffer)) smoothJava(buffer, width, height, ih, forces, output, mask);
        for (int x = 0; x < width;) {
            int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
            for (int y = 0; y < height;) {
                int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(height - y, TILE_SIZE - ly);
                Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.applyRawHeightRegion(lx, ly, rw, rh, buffer, x * height + y, height, output, mask, area);
                y += rh;
            }
            x += rw;
        }
        return true;
    }

    private static void smoothJava(ByteBuffer data, int width, int height, int inputHeight, int forces, int output, int mask) {
        int writes = 0;
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) {
            int i = x * height + y; data.put(mask + i, (byte) 0);
            float strength = data.getFloat(forces + i * 4), current = data.getFloat(48 + ((x + 5) * inputHeight + y + 5) * 4);
            if (!(strength > 0f) || current == -Float.MAX_VALUE) continue;
            float total = 0f; int count = 0;
            for (int sx = x; sx <= x + 10; sx++) for (int sy = y; sy <= y + 10; sy++) {
                float value = data.getFloat(48 + (sx * inputHeight + sy) * 4);
                if (value != -Float.MAX_VALUE) { total += value; count++; }
            }
            float edited = strength * (total / count) + (1f - strength) * current;
            data.putInt(output + i * 4, (int) ((edited - data.getInt(16)) * 256f)).put(mask + i, (byte) 1); writes++;
        }
        data.putInt(40, writes);
    }

    private static final class Scratch {
        final ByteBuffer buffer; final FloatBuffer input, forces;
        Scratch(int bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
            input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(48).asFloatBuffer(); forces = buffer.asFloatBuffer();
        }
    }
}
