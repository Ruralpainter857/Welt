package org.pepsoft.worldpainter;

import java.nio.*;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** WLFD v1 : snapshot et application par tuile, mémoire bornée et un appel JNI par zone. */
public final class FluidFloodAccess {
    private FluidFloodAccess() { }
    private static final ThreadLocal<ByteBuffer> BUFFERS = new ThreadLocal<>();
    public static boolean tryFill(Dimension dimension, int sx, int sy, boolean inverse, boolean lava) {
        long widthLong = (long) dimension.getWidth() * 128, heightLong = (long) dimension.getHeight() * 128;
        long oxLong = (long) dimension.getLowestX() * 128, oyLong = (long) dimension.getLowestY() * 128;
        if (widthLong <= 0 || heightLong <= 0 || widthLong > 65536 || heightLong > 65536 || widthLong * heightLong > 65536
                || oxLong < Integer.MIN_VALUE || oyLong < Integer.MIN_VALUE
                || oxLong + widthLong - 1 > Integer.MAX_VALUE || oyLong + heightLong - 1 > Integer.MAX_VALUE
                || sx < oxLong || sy < oyLong || sx >= oxLong + widthLong || sy >= oyLong + heightLong) return false;
        int width = (int) widthLong, height = (int) heightLong, ox = (int) oxLong, oy = (int) oyLong;
        if (!TileRegionAccess.canBatch(dimension, ox, oy, width, height)) return false;
        int terrain = dimension.getIntHeightAt(sx, sy), water = dimension.getWaterLevelAt(sx, sy);
        if (terrain == Integer.MIN_VALUE || inverse && water <= terrain) return true;
        boolean convert = water > terrain && lava != dimension.getBitLayerValueAt(FloodWithLava.INSTANCE, sx, sy);
        int base = Math.max(terrain, water);
        if (!convert && (inverse ? base <= dimension.getMinHeight() : base >= dimension.getMaxHeight() - 1)) return true;
        int mode = convert ? 2 : inverse ? 1 : 0, level = inverse ? base - 1 : base + 1;
        int area = width * height, length = 64 + area * 10;
        ByteBuffer data = BUFFERS.get();
        if (data == null || data.capacity() < length) { data = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN); BUFFERS.set(data); }
        data.clear().limit(length);
        for (int i = 0; i < 64; i += 8) data.putLong(i, 0);
        data.putInt(0, 0x44464c57).putInt(4, 1).putInt(8, width).putInt(12, height)
                .putInt(16, sx - ox).putInt(20, sy - oy).putInt(24, mode).putInt(28, level).putInt(32, lava ? 1 : 0);
        for (int i = 0; i < area; i++) data.putInt(64 + i * 4, Integer.MIN_VALUE).putInt(64 + area * 4 + i * 4, Integer.MIN_VALUE).put(64 + area * 8 + i, (byte) 0);
        for (Tile tile : dimension.getTiles()) tile.copyFluidFlood(data, (tile.getX() * 128 - ox) + (tile.getY() * 128 - oy) * width, width, area);
        if (!NativeSlices.floodFluidRegion(data)) return false;
        for (Tile tile : dimension.getTiles()) {
            int offset = (tile.getX() * 128 - ox) + (tile.getY() * 128 - oy) * width;
            boolean touched = false;
            for (int y = 0; y < 128 && !touched; y++) for (int x = 0; x < 128; x++) {
                if (data.get(64 + area * 9 + offset + x + y * width) != 0) { touched = true; break; }
            }
            if (!touched) continue;
            Tile editing = dimension.getTileForEditing(tile.getX(), tile.getY());
            editing.applyFluidFlood(data, offset, width, area, mode);
        }
        return true;
    }
}
