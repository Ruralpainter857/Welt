package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.worldpainter.Constants.*;

/**
 * Transaction d'érosion sur une zone et sa bordure, avec un appel JNI et un tampon réutilisé par worker.
 * Le demandeur sérialise les éditions de la dimension et inhibe les événements pendant l'appel.
 * Les contrôles suivent le parcours X puis Y : sélection, inversion X, inversion Y par cellule.
 * Les filtres qui observent les mutations intermédiaires doivent conserver le parcours Java.
 */
public final class ErosionAccess {
    private ErosionAccess() { }
    private static final ThreadLocal<ByteBuffer> BUFFER = new ThreadLocal<>();

    public static boolean canApply(Dimension dimension, int cx, int cy, int radius) {
        return radius >= 0 && radius <= 255 && TileRegionAccess.canBatch(dimension,
                cx - radius - 1, cy - radius - 1, radius * 2 + 3, radius * 2 + 3);
    }

    // WLER v1 : toute la zone et sa bordure restent dans un seul tampon pendant la propagation.
    public static boolean apply(Dimension dimension, int cx, int cy, int radius, byte[] controls) {
        if (!canApply(dimension, cx, cy, radius)) return false;
        int side = radius * 2 + 1, width = side + 2, area = width * width;
        if (controls.length != side * side * 3) return false;
        int types = 32 + area * 4, mask = types + area, controlOffset = mask + area;
        int length = controlOffset + controls.length;
        ByteBuffer buffer = BUFFER.get();
        if (buffer == null || buffer.capacity() < length) {
            buffer = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN); BUFFER.set(buffer);
        }
        buffer.clear().limit(length);
        buffer.putInt(0, 0x52454c57).putInt(4, 1).putInt(8, radius).putInt(12, width)
                .putInt(16, 32).putInt(20, types).putInt(24, mask).putInt(28, controlOffset);
        int ox = cx - radius - 1, oy = cy - radius - 1;
        for (int x = 0; x < width;) {
            int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
            for (int y = 0; y < width;) {
                int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(width - y, TILE_SIZE - ly);
                Tile tile = dimension.getTile(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.copyErosionRegion(lx, ly, rw, rh, buffer, x * width + y, width, types);
                else for (int dx = 0; dx < rw; dx++) for (int dy = 0; dy < rh; dy++) {
                    int i = (x + dx) * width + y + dy;
                    buffer.putInt(32 + i * 4, Integer.MIN_VALUE).put(types + i, (byte) 0);
                }
                y += rh;
            }
            x += rw;
        }
        buffer.position(controlOffset); buffer.put(controls); buffer.position(0);
        if (!NativeSlices.erodeCompactRegion(buffer)) erodeJava(buffer, side, width, types, mask, controlOffset);
        for (int x = 0; x < width;) {
            int wx = ox + x, lx = wx & TILE_SIZE_MASK, rw = Math.min(width - x, TILE_SIZE - lx);
            for (int y = 0; y < width;) {
                int wy = oy + y, ly = wy & TILE_SIZE_MASK, rh = Math.min(width - y, TILE_SIZE - ly);
                Tile tile = dimension.getTileForEditing(wx >> TILE_SIZE_BITS, wy >> TILE_SIZE_BITS);
                if (tile != null) tile.applyErosionRegion(lx, ly, rw, rh, buffer, x * width + y, width, mask);
                y += rh;
            }
            x += rw;
        }
        return true;
    }

    private static void erodeJava(ByteBuffer data, int side, int width, int types, int mask, int controls) {
        for (int i = mask; i < controls; i++) data.put(i, (byte) 0);
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            int c = controls + (x * side + y) * 3;
            if (data.get(c) == 0) continue;
            int lx = 0, ly = 0, low = Integer.MAX_VALUE;
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
                int dx = data.get(c + 1) != 0 ? 2 - a : a, dy = data.get(c + 2) != 0 ? 2 - b : b;
                int i = (x + dx) * width + y + dy;
                int value = data.get(types + i) == 0 ? Integer.MIN_VALUE : data.getInt(32 + i * 4);
                if (value < low) { low = value; lx = dx; ly = dy; }
            }
            if (lx == 1 && ly == 1) continue;
            int centre = (x + 1) * width + y + 1, lowest = (x + lx) * width + y + ly;
            int current = data.get(types + centre) == 0 ? Integer.MIN_VALUE : data.getInt(32 + centre * 4);
            int amount = Math.min((int) ((current - low) / 2 / ((lx != 1 && ly != 1) ? (float) Math.sqrt(2) : 1)), 64);
            amount = (int) ((amount / 64f) * (amount / 64f) * 64);
            if (amount > 0) {
                write(data, types, mask, centre, current - amount);
                write(data, types, mask, lowest, low + amount);
            }
        }
    }

    private static void write(ByteBuffer data, int types, int mask, int index, int value) {
        int format = data.get(types + index);
        if (format == 0) return;
        data.putInt(32 + index * 4, format == 1 ? value & 0xffff : value).put(mask + index, (byte) 1);
    }
}
