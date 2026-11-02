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
    /**
     * A linked surface intersection (WVR versions 7..10), filled by the renderer for each tile and reused. The linked
     * heights are copied into the frame; nothing here is retained by native code.
     */
    static final class Intersection {
        void ceiling(int reflection) {
            mode = 1;
            this.reflection = reflection;
        }

        void tunnelRoof(int roofMode, int level, int min, int max, int minZ, int maxZ) {
            mode = 2;
            this.roofMode = roofMode;
            this.level = level;
            this.min = min;
            this.max = max;
            this.minZ = minZ;
            this.maxZ = maxZ;
            noise = false;
        }

        void noise(double dHeight, double scale, int octaves, long seed, int offset) {
            noise = true;
            this.dHeight = dHeight;
            this.scale = scale;
            this.octaves = octaves;
            this.seed = seed;
            noiseOffset = offset;
        }

        /** Full resolution linked heights, one per cell, in Java's {@code x + y * 128} order. */
        final int[] linkedHeights = new int[AREA];
        private int mode, reflection, roofMode, level, min, max, minZ, maxZ, octaves, noiseOffset;
        private boolean noise;
        private double dHeight, scale;
        private long seed;
    }

    private static final Terrain[] TERRAINS = Terrain.values();
    private static final int AREA = 16384, HALO = 130 * 130, MAX_BYTES = 4194304;
    private final int[] colours = new int[TERRAINS.length];
    private final byte[] planned = new byte[TERRAINS.length];
    private final MixedMaterial[] mixtures = new MixedMaterial[TERRAINS.length];
    private byte[] sampledTerrains = new byte[0];
    private final Platform platform;
    private ByteBuffer data;
    private final boolean[] usedBiomes = new boolean[256];
    long completed;
    private boolean boundedSurfaceHeights;

    ViewportRenderAccess(Platform platform) {
        this.platform = platform;
    }
    private boolean planTerrain(int i) {
        if (planned[i] == 0 && TERRAINS[i].isCustom()) {
            MixedMaterial material = Terrain.getCustomMaterial(TERRAINS[i].getCustomTerrainIndex());
            // Custom slots can be replaced between redraws. Their colour
            // metadata and deterministic selectors are captured here; random mixtures retain Java.
            Integer colour = material == null ? null : material.getColour();
            org.pepsoft.minecraft.Material single = material == null ? null : material.getSingleMaterial();
            if (colour != null) {
                colours[i] = colour;
                planned[i] = 1;
            } else if (material != null && material.getMode() == MixedMaterial.Mode.SIMPLE && single != null && boundedSurfaceHeights) {
                // Rounded surface heights cannot trigger the helper's AIR branch
                // in this bounded range, so the single material has a fixed colour.
                colours[i] = ColourScheme.DEFAULT.getColour(single);
                planned[i] = 1;
            } else if (material != null && boundedSurfaceHeights && material.viewportModelBytes() > 0) {
                mixtures[i] = material; colours[i] = 0; planned[i] = 1;
            } else {
                planned[i] = 2;
            }
        }
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
        if (bits <= 4 && renderer.getClass() == PaintRenderer.class) {
            PaintRenderer paint = (PaintRenderer) renderer;
            if (paint.getRed() != null && (paint.getW() <= 0 || paint.getH() <= 0 || paint.getW() > paint.getH()
                    || (long) paint.getW() * paint.getH() > 65536)) return null;
            return new Model(layer, bits, 7, paint.getColour(), 0L, 0, renderer);
        }
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
    private static int bytes(int bits, int area) { return bits == 0 ? 8 : (area * bits + 7) / 8; }

    /** A tile without linked surface intersection (WVR versions 3..6). */
    boolean render(Tile tile, TileProvider provider, Layer[] layers, LayerRenderer[] renderers,
                   float[] surfaces, int[] heights, int[] water, byte[] terrains, boolean contours, int separation, int light,
                   boolean hideFluids, boolean bottomless, boolean voidPresent, boolean missingPresent, boolean lavaPresent,
                   int waterColour, int lavaColour, int bedrockColour, int voidColour, int missingColour, int[] output, int shift) {
        return render(tile, provider, layers, renderers, surfaces, heights, water, terrains, contours, separation, light, hideFluids,
                bottomless, voidPresent, missingPresent, lavaPresent, waterColour, lavaColour, bedrockColour, voidColour, missingColour,
                output, shift, null);
    }

    /** @param intersection The tile's linked surface intersection (WVR versions 7..10), or {@code null} for none. */
    boolean render(Tile tile, TileProvider provider, Layer[] layers, LayerRenderer[] renderers,
                   float[] surfaces, int[] heights, int[] water, byte[] terrains, boolean contours, int separation, int light,
                   boolean hideFluids, boolean bottomless, boolean voidPresent, boolean missingPresent, boolean lavaPresent,
                   int waterColour, int lavaColour, int bedrockColour, int voidColour, int missingColour, int[] output, int shift,
                   Intersection intersection) {
        if (tile.getClass() != Tile.class || provider.getClass() != Dimension.class
                || TERRAINS.length > 256 || contours && separation == 0) return false;
        if (shift < 0 || shift > 7) return false;
        // The stored raw height is at most a signed int divided by 256.
        // This min-height bound therefore keeps rounded surfaces away from int saturation.
        boundedSurfaceHeights = tile.getMinHeight() >= -(1 << 24) && tile.getMinHeight() <= (1 << 24);
        Arrays.fill(mixtures, null);
        try {
            for (int i = 0; i < TERRAINS.length; i++) if (TERRAINS[i].isCustom()) planned[i] = 0;
            int width = 128 >> shift, area = width * width;
            boolean compact = shift != 0;
            if (compact) {
                if (sampledTerrains.length < area) sampledTerrains = new byte[area];
                tile.copySampledTerrainOrdinals(shift, sampledTerrains);
            }
            byte[] planningTerrains = compact ? sampledTerrains : terrains;
            for (int cell = 0; cell < area; cell++) if ((planningTerrains[cell] & 255) >= TERRAINS.length
                    || !planTerrain(planningTerrains[cell] & 255)) return false;
            int mixtureCount = 0, mixtureBytes = 0;
            for (MixedMaterial mixture : mixtures) if (mixture != null) { mixtureCount++; mixtureBytes += 12 + mixture.viewportModelBytes(); }
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
            // The intersection variants extend the 128 byte header to 208 bytes
            int header = (intersection != null) ? 208 : 128;
            int palette = header, table = palette + colours.length * 4, terrainOffset = table + models.size() * 32;
            int heightOffset = terrainOffset + area, heightBytes = compact ? area * 20 : HALO * 4;
            int wetOffset = heightOffset + heightBytes, planes = wetOffset + heightBytes;
            int end = planes; for (Model model : models) end += bytes(model.bits, area);
            int required = end + area * 4;
            for (Model model : models) required += model.kind == 5 ? 64 : model.kind == 6 ? 256 * 1024 + 1024 : 0;
            for (Model model : models) if (model.kind == 7) {
                PaintRenderer paint = (PaintRenderer) model.renderer;
                required += 16 + (paint.getRed() == null ? 0 : paint.getW() * paint.getH() * 16);
            }
            if (mixtureCount != 0) required += mixtureBytes + area * 4;
            if (intersection != null) required += AREA * 4;
            if (required > MAX_BYTES) return false;
            if (data == null || data.capacity() < required) data = ByteBuffer.allocateDirect(required).order(ByteOrder.LITTLE_ENDIAN);
            data.clear().limit(required);
            for (int i = 0; i < header; i += 8) data.putLong(i, 0L);
            int version = (mixtureCount == 0 ? (compact ? 4 : 3) : (compact ? 6 : 5)) + ((intersection != null) ? 4 : 0);
            data.putInt(0, 0x31525657).putInt(4, version).putInt(8, models.size()).putInt(12, colours.length)
                    .putInt(16, tile.getMinHeight()).putInt(20, (contours ? 1 : 0) | (hideFluids ? 2 : 0) | (bottomless ? 4 : 0))
                    .putInt(24, separation).putInt(28, light).putInt(32, waterColour).putInt(36, lavaColour)
                    .putInt(40, bedrockColour).putInt(44, voidColour).putInt(48, missingColour)
                    .putInt(52, terrainOffset).putInt(56, heightOffset).putInt(60, wetOffset)
                    .putInt(68, palette).putInt(72, table).putInt(76, visible)
                    .putInt(80, vp).putInt(84, np).putInt(88, nb).putInt(92, lp)
                    .putInt(96, tile.getX() << 7).putInt(100, tile.getY() << 7).putInt(104, shift);
            data.position(palette); data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(colours);
            if (compact) {
                tile.copySampledRenderPoints(shift, data, terrainOffset, heightOffset, wetOffset);
                for (int cell = 0; cell < area; cell++) {
                    int terrain = data.get(terrainOffset + cell) & 255;
                    if (terrain >= TERRAINS.length || !planTerrain(terrain)) return false;
                }
                for (int i = 0; i < width; i++) {
                    edge(edges[0], i << shift, 127, heightOffset + (i * 5 + 1) * 4, wetOffset + (i * 5 + 1) * 4);
                    edge(edges[2], 127, i << shift, heightOffset + (i * width * 5 + 2) * 4, wetOffset + (i * width * 5 + 2) * 4);
                }
                // Planning sampled terrains updated the reusable palette after the first palette write.
                data.position(palette); data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(colours);
            } else {
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
            }
            int offset = planes;
            for (int i = 0; i < models.size(); i++) {
                Model m = models.get(i); int record = table + i * 32;
                data.putInt(record, m.bits).putInt(record + 4, m.kind).putInt(record + 8, m.colour).putInt(record + 12, offset)
                        .putLong(record + 16, m.pattern).putInt(record + 24, m.frost).putInt(record + 28, 0);
                if (compact) tile.copySampledLayerPlane(m.layer, m.bits, shift, data, offset);
                else tile.copyCombinedLayerPlane(m.layer, m.bits, data, offset);
                offset += bytes(m.bits, area);
            }
            for (int i = 0; i < models.size(); i++) {
                Model m = models.get(i);
                if (m.kind == 7) {
                    PaintRenderer paint = (PaintRenderer) m.renderer;
                    data.putInt(table + i * 32 + 28, offset);
                    boolean textured = paint.getRed() != null;
                    int cells = textured ? paint.getW() * paint.getH() : 0;
                    data.putInt(offset, textured ? paint.getW() : 0).putInt(offset + 4, textured ? paint.getH() : 0)
                            .putInt(offset + 8, paint.getColour()).putFloat(offset + 12, paint.getOpacity());
                    offset += 16;
                    // Copy the renderer snapshot, not the live image from which it was constructed.
                    for (int cell = 0; cell < cells; cell++) {
                        data.putInt(offset, paint.getRed()[cell]).putInt(offset + 4, paint.getGreen()[cell])
                                .putInt(offset + 8, paint.getBlue()[cell]).putFloat(offset + 12, paint.getAlpha()[cell]);
                        offset += 16;
                    }
                } else if (m.kind == 5) {
                    data.putInt(table + i * 32 + 28, offset);
                    AnnotationsRenderer renderer = (AnnotationsRenderer) m.renderer;
                    for (int value = 0; value < 16; value++) data.putInt(offset + value * 4, renderer.getPixelColour(0, 0, 0, value));
                    offset += 64;
                } else if (m.kind == 6) {
                    Arrays.fill(usedBiomes, false);
                    int plane = data.getInt(table + i * 32 + 12);
                    for (int cell = 0; cell < area; cell++) usedBiomes[data.get(plane + cell) & 255] = true;
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
            if (mixtureCount != 0) {
                data.putInt(108, offset).putInt(124, mixtureCount).putLong(112, ((Dimension) provider).getSeed());
                if (((Dimension) provider).getTopLayerAnchor() == Dimension.LayerAnchor.TERRAIN)
                    data.putInt(20, data.getInt(20) | 8);
                int entries = offset; offset += mixtureCount * 12; int modelIndex = 0;
                for (int terrain = 0; terrain < mixtures.length; terrain++) if (mixtures[terrain] != null) {
                    MixedMaterial mixture = mixtures[terrain]; int size = mixture.viewportModelBytes();
                    if (size == 0 || !mixture.writeViewportModel(data, offset)) return false;
                    data.putInt(entries + modelIndex * 12, terrain).putInt(entries + modelIndex * 12 + 4, offset)
                            .putInt(entries + modelIndex * 12 + 8, size);
                    offset += size; modelIndex++;
                }
                data.putInt(120, offset); data.position(offset);
                java.nio.FloatBuffer floats = data.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                if (compact) tile.copySampledRenderHeights(shift, floats); else floats.put(surfaces, 0, area);
                offset += area * 4;
            }
            if (intersection != null) {
                data.putInt(128, intersection.mode).putInt(132, offset);
                if (intersection.mode == 1) {
                    data.putInt(136, intersection.reflection);
                } else {
                    data.putInt(136, intersection.roofMode).putInt(140, intersection.level).putInt(144, intersection.min)
                            .putInt(148, intersection.max).putInt(152, intersection.minZ).putInt(156, intersection.maxZ);
                    if (intersection.noise) {
                        data.putInt(160, 1).putInt(164, intersection.octaves).putDouble(168, intersection.dHeight)
                                .putDouble(176, intersection.scale).putLong(184, intersection.seed).putInt(192, intersection.noiseOffset);
                    }
                }
                data.position(offset);
                data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(intersection.linkedHeights);
                offset += AREA * 4;
            }
            data.putInt(64, offset); data.limit(offset + area * 4);
            data.position(0);
            if (!NativeSlices.renderViewportTile(data)) return false;
            data.position(offset);
            IntBuffer pixels = data.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            if (compact) for (int row = 0; row < width; row++) pixels.get(output, row * 128, width);
            else pixels.get(output);
            data.position(0);
            completed++; return true;
        } finally {
            Arrays.fill(mixtures, null);
        }
    }
    private void edge(Tile tile, int x, int y, int heightOffset, int wetOffset) {
        int height = tile == null ? DEFAULT_WATER_LEVEL : tile.getIntHeight(x, y);
        int water = tile == null ? Integer.MIN_VALUE : tile.getWaterLevel(x, y);
        data.putInt(heightOffset, height).putInt(wetOffset, water > height ? water : Integer.MIN_VALUE);
    }
}
