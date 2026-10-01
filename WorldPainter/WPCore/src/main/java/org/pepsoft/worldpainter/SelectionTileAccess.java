package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.*;
import java.awt.geom.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;

/** Shape preparation and a single native edit of both packed selection levels. */
final class SelectionTileAccess {
    private static final ThreadLocal<ByteBuffer> BUFFERS = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(4192).order(ByteOrder.LITTLE_ENDIAN));

    private SelectionTileAccess() { }

    static ByteBuffer edit(Tile tile, Shape shape, boolean add, BitSet chunks, BitSet blocks) {
        // Custom Shape implementations may have stateful predicates. Keep their
        // exact predicate order and callbacks in the existing Java implementation.
        final Class<?> type = shape.getClass();
        if (type != Rectangle.class && type != Rectangle2D.Double.class && type != Rectangle2D.Float.class
                && type != Ellipse2D.Double.class && type != Ellipse2D.Float.class
                && type != RoundRectangle2D.Double.class && type != RoundRectangle2D.Float.class
                && type != Polygon.class && type != Area.class
                && type != Path2D.Double.class && type != Path2D.Float.class) {
            return null;
        }
        if ((chunks != null && chunks.length() > 64) || (blocks != null && blocks.length() > 16384)) {
            return null;
        }
        // Entire-tile edits retain the existing clear-layer path, including its
        // removal of empty layer entries. This kernel handles partial tiles.
        if (shape.contains(new Rectangle(tile.getX() << 7, tile.getY() << 7, 128, 128))) {
            return null;
        }
        final ByteBuffer buffer = BUFFERS.get();
        buffer.clear();
        for (int i = 0; i < buffer.capacity(); i += 8) buffer.putLong(i, 0);
        buffer.putInt(0, 0x4c455357).putInt(4, 1).putInt(8, add ? 1 : 0)
                .putInt(16, chunks != null ? 1 : 0).putInt(20, blocks != null ? 1 : 0);
        putBits(buffer, 88, chunks);
        putBits(buffer, 96, blocks);
        final Rectangle bounds = new Rectangle();
        for (int chunkX = 0; chunkX < 8; chunkX++) {
            for (int chunkY = 0; chunkY < 8; chunkY++) {
                final int chunk = chunkX + chunkY * 8;
                bounds.setBounds((tile.getX() << 7) + chunkX * 16,
                        (tile.getY() << 7) + chunkY * 16, 16, 16);
                if (shape.contains(bounds)) {
                    buffer.put(24 + chunk, (byte) 1);
                } else if (shape.intersects(bounds)) {
                    buffer.put(24 + chunk, (byte) 2);
                    if (add && chunks != null && chunks.get(chunk)) continue;
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dy = 0; dy < 16; dy++) {
                            if (shape.contains(bounds.x + dx, bounds.y + dy)) {
                                final int block = chunkX * 16 + dx + (chunkY * 16 + dy) * 128;
                                final int offset = 2144 + block / 8;
                                buffer.put(offset, (byte) (buffer.get(offset) | (1 << (block % 8))));
                            }
                        }
                    }
                }
            }
        }
        buffer.position(0);
        return NativeSlices.editSelection(buffer) ? buffer : null;
    }

    private static void putBits(ByteBuffer buffer, int offset, BitSet values) {
        if (values == null) return;
        for (int bit = values.nextSetBit(0); bit >= 0; bit = values.nextSetBit(bit + 1)) {
            final int index = offset + bit / 8;
            buffer.put(index, (byte) (buffer.get(index) | (1 << (bit % 8))));
        }
    }

    // WSEL v2 : le masque indique les tirages retenus ; Rust classe et modifie les deux plans ensemble.
    static ByteBuffer editMask(boolean add, BitSet chunks, BitSet blocks, byte[] mask) {
        ByteBuffer buffer = BUFFERS.get(); buffer.clear();
        for (int i = 0; i < buffer.capacity(); i += 8) buffer.putLong(i, 0);
        buffer.putInt(0, 0x4c455357).putInt(4, 2).putInt(8, add ? 1 : 0)
                .putInt(16, chunks != null ? 1 : 0).putInt(20, blocks != null ? 1 : 0);
        putBits(buffer, 88, chunks); putBits(buffer, 96, blocks);
        buffer.position(2144); buffer.put(mask); buffer.position(0);
        return NativeSlices.editSelection(buffer) ? buffer : null;
    }

    /** The caller already acquired the undo-aware writable bit-layer map. */
    static BitSet applyBits(ByteBuffer buffer, int offset, int length, BitSet destination) {
        if (destination == null) destination = new BitSet(length * 8);
        destination.clear();
        long first = buffer.getLong(offset);
        if (first == 0 || first == -1L) {
            boolean uniform = true;
            for (int word = 1; word < length / 8; word++) {
                if (buffer.getLong(offset + word * 8) != first) { uniform = false; break; }
            }
            if (uniform) {
                if (first != 0) destination.set(0, length * 8);
                return destination;
            }
        }
        for (int word = 0; word < length / 8; word++) {
            long value = buffer.getLong(offset + word * 8);
            while (value != 0) {
                destination.set(word * 64 + Long.numberOfTrailingZeros(value));
                value &= value - 1;
            }
        }
        return destination;
    }
}
