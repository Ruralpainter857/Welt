package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.MultiPixelPackedSampleModel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Packed font crops and paint planes share one JNI transaction per destination tile. */
public final class GlyphPaintAccess {
    private GlyphPaintAccess() { }
    private static final ThreadLocal<ByteBuffer> BUFFER = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN));

    /** Returns successful JNI calls, or -1 when the original pixel path is required. */
    public static int paint(Dimension dimension, BufferedImage image, int width, int height, int x, int y,
                            int angle, Layer[] layers, int[] roles, int[] operations, int[] targets) {
        if (angle < 0 || angle > 3 || width <= 0 || height <= 0 || width > image.getWidth() || height > image.getHeight()
                || layers.length < 1 || layers.length > 3 || roles.length != layers.length
                || operations.length != layers.length || targets.length != layers.length
                || image.getType() != BufferedImage.TYPE_BYTE_BINARY
                || !(image.getRaster().getSampleModel() instanceof MultiPixelPackedSampleModel)
                || !(image.getRaster().getDataBuffer() instanceof DataBufferByte)) return -1;
        MultiPixelPackedSampleModel model = (MultiPixelPackedSampleModel) image.getRaster().getSampleModel();
        DataBufferByte bank = (DataBufferByte) image.getRaster().getDataBuffer();
        if (model.getPixelBitStride() != 1 || model.getDataBitOffset() != 0 || bank.getNumBanks() != 1
                || image.getRaster().getSampleModelTranslateX() != 0 || image.getRaster().getSampleModelTranslateY() != 0
                || (image.getColorModel().getRGB(0) & 1) != 0 || (image.getColorModel().getRGB(1) & 1) == 0) return -1;
        int n = layers.length, bytes = 0;
        int[] kinds = new int[n], offsets = new int[n], defaults = new int[n];
        for (int p = 0; p < n; p++) {
            Layer layer = layers[p];
            if (roles[p] == 2) {if (layer != null) return -1;kinds[p] = 1;}
            else if (roles[p] == 3 && layer != null) {
                Layer.DataSize size = layer.getDataSize();
                kinds[p] = size == Layer.DataSize.BYTE ? 1 : size == Layer.DataSize.NIBBLE ? 2
                        : size == Layer.DataSize.BIT ? 3 : size == Layer.DataSize.BIT_PER_CHUNK ? 4 : -1;
                if (kinds[p] < 0) return -1;
                defaults[p] = kinds[p] >= 3 ? 0 : layer.getDefaultValue();
            } else return -1;
            int max = kinds[p] == 1 ? 255 : kinds[p] == 2 ? 15 : 1;
            if (targets[p] < 0 || targets[p] > max || defaults[p] < 0 || defaults[p] > max
                    || operations[p] < 0 || operations[p] > 1 || operations[p] == 1 && kinds[p] >= 3) return -1;
            offsets[p] = bytes;bytes += SelectionCopyAccess.length(kinds[p]);
        }
        long ox = angle == 0 || angle == 1 ? x : (long)x - ((angle == 2 ? width : height) - 1L);
        long oy = angle == 0 || angle == 3 ? y : (long)y - ((angle == 1 ? width : height) - 1L);
        int rw = (angle & 1) == 0 ? width : height, rh = (angle & 1) == 0 ? height : width;
        if (ox < Integer.MIN_VALUE || oy < Integer.MIN_VALUE || ox + rw - 1 > Integer.MAX_VALUE
                || oy + rh - 1 > Integer.MAX_VALUE || !TileRegionAccess.canBatch(dimension, (int)ox, (int)oy, rw, rh)) return -1;
        byte[] source = bank.getData();int sourceStride = model.getScanlineStride(), sourceOffset = bank.getOffset();
        if (sourceOffset < 0 || sourceStride < (width + 7L) / 8
                || sourceOffset + (height - 1L) * sourceStride + (width + 7L) / 8 > source.length) return -1;
        ByteBuffer d = BUFFER.get();int calls = 0;
        for (int dy = 0; dy < rh;) {
            int wy = (int)oy + dy, ly = wy & 127, th = Math.min(rh - dy, 128 - ly);
            for (int dx = 0; dx < rw;) {
                int wx = (int)ox + dx, lx = wx & 127, tw = Math.min(rw - dx, 128 - lx);
                Tile tile = dimension.getTileForEditing(wx >> 7, wy >> 7);
                if (tile != null) {
                    int sx = angle == 0 ? wx - x : angle == 1 ? y - (wy + th - 1)
                            : angle == 2 ? x - (wx + tw - 1) : wy - y;
                    int sy = angle == 0 ? wy - y : angle == 1 ? wx - x
                            : angle == 2 ? y - (wy + th - 1) : x - (wx + tw - 1);
                    int sw = (angle & 1) == 0 ? tw : th, sh = (angle & 1) == 0 ? th : tw;
                    int bit = sx & 7, stride = (sw + bit + 7) / 8, bitmap = 256 + n * 16;
                    int meta = bitmap + stride * sh, size = meta + 32 + bytes;
                    d.clear().limit(size);for (int p = 0; p < 256; p += 8) d.putLong(p, 0);
                    d.putInt(0,0x594c4757).putInt(4,1).putInt(8,size).putInt(12,n).putInt(16,lx).putInt(20,ly)
                            .putInt(24,tw).putInt(28,th).putInt(32,angle).putInt(36,stride).putInt(40,bit)
                            .putInt(44,sw).putInt(48,sh).putInt(52,bitmap).putInt(56,meta);
                    for (int p = 0; p < n; p++) d.putInt(64+p*4,defaults[p]).putInt(80+p*4,roles[p])
                            .putInt(256+p*16,kinds[p]).putInt(260+p*16,operations[p]).putInt(264+p*16,targets[p]).putInt(268+p*16,offsets[p]);
                    for (int row = 0; row < sh; row++) {
                        d.position(bitmap+row*stride);d.put(source,sourceOffset+(sy+row)*sourceStride+sx/8,stride);
                    }
                    tile.copySelectionPlanes(d,meta,layers,roles,kinds,offsets);d.position(0);
                    // Plain paints are idempotent; a missing or rejected JNI entry can retry the original glyph.
                    if (!NativeSlices.paintGlyphTile(d)) return -1;
                    calls++;tile.applyOrderedPlanes(d,meta,layers,roles,kinds,offsets);
                }
                dx += tw;
            }
            dy += th;
        }
        return calls;
    }
}
