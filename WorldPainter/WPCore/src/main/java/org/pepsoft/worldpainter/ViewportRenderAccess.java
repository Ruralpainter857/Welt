package org.pepsoft.worldpainter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.renderers.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.pepsoft.minecraft.Constants.DEFAULT_WATER_LEVEL;

/** One renderer-owned compact WVR frame, borrowed by Rust only during JNI. */
final class ViewportRenderAccess {
    private static final Terrain[] TERRAINS = Terrain.values();
    private static final int HEADER = 128, AREA = 16384, HALO = 130 * 130, MAX_BYTES = 4194304;
    private final int[] colours = new int[TERRAINS.length];
    private final byte[] planned = new byte[TERRAINS.length];
    private final Platform platform;
    private ByteBuffer data;
    private final boolean[] usedBiomes = new boolean[256];
    long completed;

    ViewportRenderAccess(Platform platform) {
        this.platform = platform;
    }
    private boolean planTerrain(int i) {
        if (planned[i] == 0) {
            Class<?> type = TERRAINS[i].getClass();
            boolean supported;
            try {
                supported = type.getMethod("getColour", long.class, int.class, int.class, float.class, int.class, Platform.class, ColourScheme.class).getDeclaringClass() == Terrain.class
                        && type.getMethod("getMaterial", Platform.class, long.class, int.class, int.class, float.class, int.class).getDeclaringClass() == Terrain.class
                        && type.getMethod("getMaterial", Platform.class, long.class, int.class, int.class, int.class, int.class).getDeclaringClass() == Terrain.class;
            } catch (ReflectiveOperationException | SecurityException e) { supported = false; }
            // The stock float material method rounds the same height as the render
            // snapshot, so the displayed surface uses its constant top material.
            if (supported) colours[i] = TERRAINS[i].getColour(0L, 0, 0, 0f, 0, platform, ColourScheme.DEFAULT);
            planned[i] = (byte) (supported ? 1 : 2);
        }
        return planned[i] == 1;
    }
    private record Model(Layer layer, int bits, int kind, int colour, long pattern, int frost, LayerRenderer renderer) { }
    private static Model model(Layer layer, LayerRenderer renderer) {
        int bits = switch (layer.dataSize) { case BIT_PER_CHUNK -> 0; case BIT -> 1; case NIBBLE -> 4; case BYTE -> 8; default -> -1; };
        if (bits < 0) return null;
        if (bits == 8) return renderer.getClass() == BiomeRenderer.class
                ? new Model(layer, bits, 6, 0, 0L, 0, renderer) : null;
        if (bits == 4 && renderer.getClass() == AnnotationsRenderer.class)
            return new Model(layer, bits, 5, 0, 0L, 0, renderer);
        try {
            Class<?> owner = renderer.getClass().getMethod("getPixelColour", int.class, int.class, int.class,
                    bits <= 1 ? boolean.class : int.class).getDeclaringClass();
            if (owner == TransparentColourRenderer.class)
                return new Model(layer, bits, 1, ((TransparentColourRenderer) renderer).getColour(), -1L, 0, renderer);
            if (owner == ColouredPatternRenderer.class && bits == 4) {
                ColouredPatternRenderer r = (ColouredPatternRenderer) renderer;
                return new Model(layer, bits, 4, r.getColour(), r.getPatternMask(), 0, renderer);
            }
            if (renderer.getClass() == FrostRenderer.class && bits <= 1)
                return new Model(layer, bits, 2, 0, 0L, layer instanceof Frost ? 1 : 0, renderer);
            if (renderer.getClass() == ReadOnlyRenderer.class && bits <= 1)
                return new Model(layer, bits, 3, 0, 0L, 0, renderer);
        } catch (ReflectiveOperationException | SecurityException e) { return null; }
        return null;
    }
    private static int helper(List<Model> models, Layer layer, boolean present) {
        if (!present) return -1;
        for (int i = 0; i < models.size(); i++) if (models.get(i).layer.equals(layer)) return i;
        int bits = layer.dataSize == Layer.DataSize.BIT ? 1 : 0;
        models.add(new Model(layer, bits, 0, 0, 0L, 0, null)); return models.size() - 1;
    }
    private static int bytes(int bits) { return bits == 0 ? 8 : AREA * bits / 8; }

    boolean render(Tile tile, TileProvider provider, Layer[] layers, LayerRenderer[] renderers,
                   int[] heights, int[] water, byte[] terrains, boolean contours, int separation, int light,
                   boolean hideFluids, boolean bottomless, boolean voidPresent, boolean missingPresent, boolean lavaPresent,
                   int waterColour, int lavaColour, int bedrockColour, int voidColour, int missingColour, int[] output) {
        if (tile.getClass() != Tile.class || provider.getClass() != Dimension.class
                || TERRAINS.length > 256 || contours && separation == 0) return false;
        for (byte terrain : terrains) if ((terrain & 255) >= TERRAINS.length || !planTerrain(terrain & 255)) return false;
        List<Model> models = new ArrayList<>();
        for (int i = 0; i < layers.length; i++) {
            if (renderers[i] == null) return false;
            Model model = model(layers[i], renderers[i]); if (model == null) return false;
            if (model.bits == 4 && (layers[i].getDefaultValue() < 0 || layers[i].getDefaultValue() > 15)) return false;
            if (model.bits == 8 && (layers[i].getDefaultValue() < 0 || layers[i].getDefaultValue() > 255)) return false;
            models.add(model);
        }
        int visible = models.size();
        int vp = helper(models, org.pepsoft.worldpainter.layers.Void.INSTANCE, voidPresent);
        int np = helper(models, NotPresent.INSTANCE, missingPresent);
        int nb = helper(models, NotPresentBlock.INSTANCE, missingPresent);
        int lp = helper(models, FloodWithLava.INSTANCE, lavaPresent);
        if (models.size() > 128) return false;
        Tile[] edges = {provider.getTile(tile.getX(), tile.getY() - 1), provider.getTile(tile.getX(), tile.getY() + 1),
                provider.getTile(tile.getX() - 1, tile.getY()), provider.getTile(tile.getX() + 1, tile.getY())};
        for (Tile edge : edges) if (edge != null && edge.getClass() != Tile.class) return false;
        int palette = HEADER, table = palette + colours.length * 4, terrainOffset = table + models.size() * 32;
        int heightOffset = terrainOffset + AREA, wetOffset = heightOffset + HALO * 4, planes = wetOffset + HALO * 4;
        int end = planes; for (Model model : models) end += bytes(model.bits);
        int required = end + AREA * 4;
        for (Model model : models) required += model.kind == 5 ? 64 : model.kind == 6 ? 256 * 1024 + 1024 : 0;
        if (required > MAX_BYTES) return false;
        if (data == null || data.capacity() < required) data = ByteBuffer.allocateDirect(required).order(ByteOrder.LITTLE_ENDIAN);
        data.clear().limit(required);
        for (int i = 0; i < HEADER; i += 8) data.putLong(i, 0L);
        data.putInt(0, 0x31525657).putInt(4, 2).putInt(8, models.size()).putInt(12, colours.length)
                .putInt(16, tile.getMinHeight()).putInt(20, (contours ? 1 : 0) | (hideFluids ? 2 : 0) | (bottomless ? 4 : 0))
                .putInt(24, separation).putInt(28, light).putInt(32, waterColour).putInt(36, lavaColour)
                .putInt(40, bedrockColour).putInt(44, voidColour).putInt(48, missingColour)
                .putInt(52, terrainOffset).putInt(56, heightOffset).putInt(60, wetOffset)
                .putInt(68, palette).putInt(72, table).putInt(76, visible)
                .putInt(80, vp).putInt(84, np).putInt(88, nb).putInt(92, lp);
        data.position(palette); data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(colours);
        data.position(terrainOffset); data.put(terrains);
        data.position(heightOffset);
        IntBuffer heightHalo = data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int y = 0; y < 128; y++) {
            heightHalo.position((y + 1) * 130 + 1); heightHalo.put(heights, y * 128, 128);
            for (int x = 0; x < 128; x++) {
                int cell = x + y * 128;
                data.putInt(wetOffset + ((y + 1) * 130 + x + 1) * 4, water[cell] > heights[cell] ? water[cell] : Integer.MIN_VALUE);
            }
        }
        for (int i = 0; i < 128; i++) {
            edge(edges[0], i, 127, heightOffset + (i + 1) * 4, wetOffset + (i + 1) * 4);
            edge(edges[1], i, 0, heightOffset + (129 * 130 + i + 1) * 4, wetOffset + (129 * 130 + i + 1) * 4);
            edge(edges[2], 127, i, heightOffset + ((i + 1) * 130) * 4, wetOffset + ((i + 1) * 130) * 4);
            edge(edges[3], 0, i, heightOffset + ((i + 1) * 130 + 129) * 4, wetOffset + ((i + 1) * 130 + 129) * 4);
        }
        int offset = planes;
        for (int i = 0; i < models.size(); i++) {
            Model m = models.get(i); int record = table + i * 32;
            data.putInt(record, m.bits).putInt(record + 4, m.kind).putInt(record + 8, m.colour).putInt(record + 12, offset)
                    .putLong(record + 16, m.pattern).putInt(record + 24, m.frost).putInt(record + 28, 0);
            tile.copyCombinedLayerPlane(m.layer, m.bits, data, offset); offset += bytes(m.bits);
        }
        for (int i = 0; i < models.size(); i++) {
            Model m = models.get(i);
            if (m.kind == 5) {
                data.putInt(table + i * 32 + 28, offset);
                AnnotationsRenderer renderer = (AnnotationsRenderer) m.renderer;
                for (int value = 0; value < 16; value++) data.putInt(offset + value * 4, renderer.getPixelColour(0, 0, 0, value));
                offset += 64;
            } else if (m.kind == 6) {
                Arrays.fill(usedBiomes, false);
                int plane = data.getInt(table + i * 32 + 12);
                for (int cell = 0; cell < AREA; cell++) usedBiomes[data.get(plane + cell) & 255] = true;
                int lookup = offset; offset += 1024;
                data.putInt(table + i * 32 + 28, lookup);
                BiomeRenderer renderer = (BiomeRenderer) m.renderer;
                for (int value = 0; value < 256; value++) {
                    int length = usedBiomes[value] ? renderer.copyViewportPattern(value, data, offset) : 0;
                    if (length < 0) return false;
                    data.putInt(lookup + value * 4, length == 0 ? 0 : offset); offset += length;
                }
            }
        }
        data.putInt(64, offset); data.limit(offset + AREA * 4);
        data.position(0);
        if (!NativeSlices.renderViewportTile(data)) return false;
        data.position(offset); data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(output); data.position(0);
        completed++; return true;
    }
    private void edge(Tile tile, int x, int y, int heightOffset, int wetOffset) {
        int height = tile == null ? DEFAULT_WATER_LEVEL : tile.getIntHeight(x, y);
        int water = tile == null ? Integer.MIN_VALUE : tile.getWaterLevel(x, y);
        data.putInt(heightOffset, height).putInt(wetOffset, water > height ? water : Integer.MIN_VALUE);
    }
}
