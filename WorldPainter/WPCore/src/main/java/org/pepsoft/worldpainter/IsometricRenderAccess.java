package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

/** Worker-owned compact columns and final pixels for a complete isometric tile. */
public final class IsometricRenderAccess {
    private static final int AREA = 16384, HEADER = 64, RECORD = 32, TOPS = HEADER + AREA * RECORD;
    private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_COLOURS = 2097152;
    private static final Terrain[] TERRAINS = Terrain.values();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private final float[] floats = new float[AREA];
    private final int[] heights = new int[AREA], water = new int[AREA], subsurface = new int[AREA], surface = new int[AREA];
    private final int[] neighbour = new int[AREA], edges = new int[512];
    private final byte[] terrains = new byte[AREA], absent = new byte[AREA], missing = new byte[AREA],
            missingBlocks = new byte[AREA], frost = new byte[AREA], lava = new byte[AREA];
    private ByteBuffer frame;

    public static boolean isEnabled() {
        return Boolean.getBoolean("welt.native.isometric") && NativeSlices.isIsometricAvailable();
    }

    public static long completedTiles() { return COMPLETED.get(); }

    /** Unsupported sources retain the original renderer, without consuming custom random material state. */
    public boolean render(Dimension dimension, Tile tile, ColourScheme colours, Platform platform,
                          BufferedImage surfaceImage, BufferedImage target, int rotation,
                          boolean hideFrost, boolean hideFluids, int minimum, int maximum,
                          int stoneColour, int waterColour, int lavaColour, int iceColour) {
        if (!isEnabled() || dimension.getClass() != Dimension.class || tile.getClass() != Tile.class
                || dimension.getTile(tile.getX(), tile.getY()) != tile
                || colours != ColourScheme.DEFAULT || rotation < 0 || rotation > 3
                || target.getType() != BufferedImage.TYPE_INT_ARGB || target.getWidth() != 256
                || target.getRaster().getDataBuffer().getOffset() != 0
                || target.getRaster().getDataBuffer().getSize() != (long) target.getWidth() * target.getHeight()
                || target.getRaster().getSampleModelTranslateX() != 0 || target.getRaster().getSampleModelTranslateY() != 0
                || target.getHeight() < 128 || target.getHeight() > 8192 || minimum < -1000000 || maximum > 1000000
                || tile.getX() < -(1 << 24) || tile.getX() >= (1 << 24) || tile.getY() < -(1 << 24) || tile.getY() >= (1 << 24)
                || target.getHeight() != 128L + maximum - minimum) return false;
        tile.copyRenderDataTo(floats, heights, water, terrains);
        tile.copyBitLayerValues(org.pepsoft.worldpainter.layers.Void.INSTANCE, 0, 0, 128, 128, absent, 0);
        tile.copyBitLayerValues(NotPresent.INSTANCE, 0, 0, 128, 128, missing, 0);
        tile.copyBitLayerValues(NotPresentBlock.INSTANCE, 0, 0, 128, 128, missingBlocks, 0);
        tile.copyBitLayerValues(Frost.INSTANCE, 0, 0, 128, 128, frost, 0);
        tile.copyBitLayerValues(FloodWithLava.INSTANCE, 0, 0, 128, 128, lava, 0);
        long colourCount = 0;
        int worldX = tile.getX() * 128, worldY = tile.getY() * 128;
        boolean coverSteep = dimension.isCoverSteepTerrain();
        if (coverSteep && (!edge(dimension, (worldX - 1) >> 7, tile.getY(), 0)
                || !edge(dimension, (worldX + 128) >> 7, tile.getY(), 1)
                || !edge(dimension, tile.getX(), (worldY - 1) >> 7, 2)
                || !edge(dimension, tile.getX(), (worldY + 128) >> 7, 3))) return false;
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            int at = x + y * 128, bits = x * 128 + y;
            if (absent[bits] != 0 || missing[bits] != 0 || missingBlocks[bits] != 0) continue;
            Terrain terrain = TERRAINS[terrains[at] & 255];
            MixedMaterial mixture = terrain.isCustom() ? Terrain.getCustomMaterial(terrain.getCustomTerrainIndex()) : null;
            if (terrain.isCustom() && (mixture == null || (mixture.getColour() == null && mixture.getMode() == MixedMaterial.Mode.NOISE))) return false;
            int height = heights[at];
            int depth = Math.max(height - minimum - dimension.getTopLayerDepth(worldX + x, worldY + y, height), 0);
            if (coverSteep) depth = Math.min(depth, Math.min(Math.min(
                    x == 0 ? edges[y] : heights[at - 1], x == 127 ? edges[128 + y] : heights[at + 1]), Math.min(
                    y == 0 ? edges[256 + x] : heights[at - 128], y == 127 ? edges[384 + x] : heights[at + 128])) - minimum);
            if (height < minimum || height > maximum || water[at] < minimum || water[at] > maximum || depth < -8192
                    || depth > maximum - minimum) return false;
            subsurface[at] = depth;
            if (mixture == null || mixture.getColour() == null) colourCount += Math.max(height - minimum - depth - 1, 0);
        }
        long output = TOPS + colourCount * 4, length = output + (long) target.getWidth() * target.getHeight() * 4;
        if (colourCount > MAX_COLOURS || length > MAX_BYTES) return false;
        if (frame == null || frame.capacity() < length) frame = ByteBuffer.allocateDirect((int) length).order(ByteOrder.LITTLE_ENDIAN);
        frame.clear().limit((int) length);
        for (int p = 0; p < HEADER; p += 4) frame.putInt(p, 0);
        frame.putInt(0, 0x44334957).putInt(4, 1).putInt(8, AREA).putInt(12, 256).putInt(16, target.getHeight())
                .putInt(20, rotation).putInt(24, minimum).putInt(28, maximum).putInt(32, stoneColour)
                .putInt(36, waterColour).putInt(40, lavaColour).putInt(44, iceColour).putInt(48, HEADER)
                .putInt(52, (int) output).putInt(56, (int) colourCount);
        surfaceImage.getRGB(0, 0, 128, 128, surface, 0, 128);
        int cursor = TOPS;
        long seed = dimension.getSeed();
        boolean relative = dimension.getTopLayerAnchor() == Dimension.LayerAnchor.TERRAIN;
        // Preserve the Java renderer's rotation-dependent material lookup order.
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            int tx = rotation == 0 ? x : rotation == 1 ? y : rotation == 2 ? 127-x : 127-y;
            int ty = rotation == 0 ? y : rotation == 1 ? 127-x : rotation == 2 ? 127-y : x;
            int at = tx + ty * 128, bits = tx * 128 + ty, p = HEADER + at * RECORD;
            frame.putInt(p + 12, 0).putInt(p + 28, 0);
            if (absent[bits] != 0 || missing[bits] != 0 || missingBlocks[bits] != 0) continue;
            int height = heights[at], depth = subsurface[at], fluid = hideFluids ? height : water[at];
            boolean isLava = water[at] > height && lava[bits] != 0;
            int flags = 1 | (isLava ? 2 : 0) | (!isLava && !hideFrost && frost[bits] != 0 ? 4 : 0);
            Terrain terrain = TERRAINS[terrains[at] & 255];
            MixedMaterial mixture = terrain.isCustom() ? Terrain.getCustomMaterial(terrain.getCustomTerrainIndex()) : null;
            if (mixture != null && mixture.getColour() != null) {
                flags |= 8;
                frame.putInt(p + 24, mixture.getColour());
            } else {
                int topOffset = mixture != null && relative && mixture.getMode() == MixedMaterial.Mode.LAYERED
                        ? -(height - mixture.getPatternHeight() + 1) : 0;
                int bx = worldX + tx, by = worldY + ty;
                Material next = terrain.getMaterial(platform, seed, bx, by, depth + minimum + 1, height);
                for (int z = depth + minimum + 1; z <= height - 1; z++) {
                    Material material = next;
                    if (z < maximum) {
                        next = terrain.getMaterial(platform, seed, bx, by, z + 1, height);
                        if (!next.veryInsubstantial && (material == Material.GRASS_BLOCK || material == Material.MYCELIUM || material == Material.FARMLAND)) material = Material.DIRT;
                    }
                    int colour = colours.getColour(topOffset != 0 ? mixture.getMaterial(seed, bx, by, z + topOffset) : material);
                    frame.putInt(cursor, colour); cursor += 4;
                }
            }
            int count = (flags & 8) != 0 ? 0 : Math.max(height - minimum - depth - 1, 0);
            frame.putInt(p, height).putInt(p + 4, fluid).putInt(p + 8, depth).putInt(p + 12, flags)
                    .putInt(p + 16, cursor - count * 4).putInt(p + 20, surface[at]);
        }
        if (cursor != output) return false;
        if (!NativeSlices.renderIsometricTile(frame)) return false;
        int[] pixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        frame.position((int) output); frame.asIntBuffer().get(pixels, 0, target.getWidth() * target.getHeight()); frame.position(0);
        COMPLETED.incrementAndGet();
        return true;
    }

    private boolean edge(Dimension dimension, int x, int y, int side) {
        Tile tile = dimension.getTile(x, y);
        if (tile == null) { Arrays.fill(edges, side * 128, (side + 1) * 128, Integer.MAX_VALUE); return true; }
        if (tile.getClass() != Tile.class) return false;
        tile.copyQuantisedHeights(neighbour);
        for (int cell = 0; cell < 128; cell++) edges[side * 128 + cell] = neighbour[switch (side) {
            case 0 -> 127 + cell * 128;
            case 1 -> cell * 128;
            case 2 -> cell + 127 * 128;
            default -> cell;
        }];
        return true;
    }
}
