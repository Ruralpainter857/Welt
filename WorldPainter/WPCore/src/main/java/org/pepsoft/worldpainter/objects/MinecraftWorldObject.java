/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.objects;

import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.Entity;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;
import org.pepsoft.util.AttributeKey;
import org.pepsoft.util.Box;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.exporting.MinecraftWorld;
import org.pepsoft.worldpainter.exporting.VirtualChunk;

import javax.vecmath.Point3i;
import java.io.Serializable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.pepsoft.minecraft.Constants.BLK_AIR;
import static org.pepsoft.minecraft.Material.AIR;

/**
 * A memory only combination of {@link MinecraftWorld} and {@link WPObject}, allowing to render worlds and layers to it
 * and then treat it as an object, for instance for generating previews. As such it does not support entities, tile
 * entities, lighting information, etc., just basic block info. Trying to use the unsupported features will fail
 * silently.
 * 
 * <p>For the {@code MinecraftWorld} interface the supported coordinates are those specified by the {@code volume}
 * parameter. For the {@code WPObject} interface, the coordinates are translated so that the lower north west corner is
 * at 0,0,0. In other words, the {@code WPObject} has no offset and all the coordinates are positive.
 *
 * <p>An offset may in fact be specified but it has no effect on the coordinates used by this object; it is purely meant
 * to communicate to a consumer of the {@code WPObject} that the object should be shifted when placed.
 * 
 * @author SchmitzP
 */
public final class MinecraftWorldObject implements MinecraftWorld, WPObject {
    /**
     * Create a new {@code MinecraftWorldObject} which is initialised with
     * all air and has no offset.
     *
     * @param name The name of the world/object.
     * @param volume The volume of blocks in world coordinates which the object should encompass. Blocks outside this
     *               volume are returned as air for read operations and silently ignored for write operations.
     * @param maxHeight The height to return from {@link MinecraftWorld#getMaxHeight()}. Must be a power of two and may
     *                  be higher than the volume; that just means the blocks between the top of the volume and
     *                  maxHeight won't be stored.
     */
    public MinecraftWorldObject(String name, Box volume, int maxHeight, int waterLevel) {
        this(name, volume, maxHeight, waterLevel, null, new Point3i(0, 0, 0));
    }

    /**
     * Create a new {@code MinecraftWorldObject}.
     *
     * @param name The name of the world/object.
     * @param volume The volume of blocks in world coordinates which the object should encompass. Blocks outside this
     *               volume are returned as air for read operations and silently ignored for write operations.
     * @param maxHeight The height to return from {@link MinecraftWorld#getMaxHeight()}. Must be a power of two and may
     *                  be higher than the volume; that just means the blocks between the top of the volume and
     *                  maxHeight won't be stored.
     * @param lowestBlocks An optional column of materials with which the bottom of the volume should be filled. All
     *                     other blocks will be initialised as air. May be {@code null}, in which case all blocks
     *                     will be initialised as air.
     * @param offset The offset to return from {@link WPObject#getOffset()}.
     */
    public MinecraftWorldObject(String name, Box volume, int maxHeight, int waterLevel, Material[] lowestBlocks, Point3i offset) {
        this.name = name;
        this.volume = volume;
        this.maxHeight = maxHeight;
        this.waterLevel = waterLevel;
        this.lowestBlocks = lowestBlocks;
        this.offset = offset;
        dx = volume.getX1();
        dy = volume.getY1();
        dz = volume.getZ1();
        dimensions = new Point3i(volume.getWidth(), volume.getLength(), volume.getHeight());
        if (compactEnabled(volume.getWidth(), volume.getLength(), volume.getHeight())) {
            compactBlocks = new MutableMaterialVolume(volume.getWidth(), volume.getLength(), volume.getHeight(), lowestBlocks);
        } else {
            blocks = new Material[volume.getWidth()][volume.getLength()][volume.getHeight()];
            for (Material[][] slice: blocks) {
                for (Material[] column: slice) {
                    Arrays.fill(column, AIR);
                    if (lowestBlocks != null) System.arraycopy(lowestBlocks, 0, column, 0, lowestBlocks.length);
                }
            }
        }
    }
    private MinecraftWorldObject(MinecraftWorldObject source) {
        name = source.name; volume = source.volume.clone(); maxHeight = source.maxHeight; waterLevel = source.waterLevel;
        dx = source.dx; dy = source.dy; dz = source.dz;
        dimensions = (Point3i) source.dimensions.clone(); offset = (Point3i) source.offset.clone();
        lowestBlocks = source.lowestBlocks == null ? null : source.lowestBlocks.clone();
        blocks = source.blocks == null ? null : copyLegacy(source.blocks);
        compactBlocks = source.compactBlocks == null ? null : source.compactBlocks.copy();
    }
    public void reset() {
        if (compactBlocks != null) { compactBlocks.reset(lowestBlocks); return; }
        for (Material[][] slice: blocks) {
            for (Material[] row: slice) {
                Arrays.fill(row, AIR);
                if (lowestBlocks != null) {
                    System.arraycopy(lowestBlocks, 0, row, 0, lowestBlocks.length);
                }
            }
        }
    }

    public Box getVolume() {
        return volume.clone();
    }

    public int getWaterLevel() {
        return waterLevel;
    }

    // MinecraftWorld
    
    @Override
    public int getBlockTypeAt(int x, int y, int height) {
        if (volume.contains(x, y, height)) {
            return materialAtRelative(x - dx, y - dy, height - dz).blockType;
        } else {
            return BLK_AIR;
        }
    }

    @Override
    public int getDataAt(int x, int y, int height) {
        if (volume.contains(x, y, height)) {
            return materialAtRelative(x - dx, y - dy, height - dz).data;
        } else {
            return 0;
        }
    }

    @Override
    public Material getMaterialAt(int x, int y, int height) {
        if (volume.contains(x, y, height)) {
            return materialAtRelative(x - dx, y - dy, height - dz);
        } else {
            return AIR;
        }
    }

    @Override
    public void setBlockTypeAt(int x, int y, int height, int blockType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDataAt(int x, int y, int height, int data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMaterialAt(int x, int y, int height, Material material) {
        if (volume.contains(x, y, height)) {
            putRelative(x - dx, y - dy, height - dz, material);
        }
    }

    @Override
    public void markForUpdateWorld(int x, int y, int height) {
        // Do nothing
    }

    @Override
    public int getMinHeight() {
        return 0;
    }

    @Override
    public int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public void addEntity(double x, double y, double height, Entity entity) {
        // Do nothing
    }

    @Override
    public void addTileEntity(int x, int y, int height, TileEntity tileEntity) {
        // Do nothing
    }

    @Override
    public int getBlockLightLevel(int x, int y, int height) {
        return 0;
    }

    @Override
    public void setBlockLightLevel(int x, int y, int height, int blockLightLevel) {
        // Do nothing
    }

    @Override
    public int getSkyLightLevel(int x, int y, int height) {
        return 15;
    }

    @Override
    public void setSkyLightLevel(int x, int y, int height, int skyLightLevel) {
        // Do nothing
    }

    @Override
    public boolean isChunkPresent(int x, int y) {
        return volume.containsXY(x << 4, y << 4);
    }

    /**
     * Copies the block IDs and data values from the specified chunk to this
     * object, insofar as it intersects the object bounds.
     *
     * @param chunk The chunk to copy.
     */
    public void addChunk(Chunk chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = (chunk.getxPos() << 4) | x, blockY = (chunk.getzPos() << 4) | z;
                if (volume.containsXY(blockX, blockY)) {
                    for (int y = Math.min(chunk.getHighestNonAirBlock(x, z), dz + volume.getHeight() - 1); y >= dz; y--) {
                        putRelative(blockX - dx, blockY - dy, y - dz, chunk.getMaterial(x, y, z));
                    }
                }
            }
        }
    }

    @Override
    public int getHighestNonAirBlock(int x, int y) {
        if (volume.containsXY(x, y)) {
            for (int z = volume.getHeight() - 1; z >= 0; z--) {
                if (materialAtRelative(x - dx, y - dy, z) != null) {
                    return z + dz;
                }
            }
            return Integer.MIN_VALUE;
        } else {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public Chunk getChunk(int x, int z) {
        return new VirtualChunk(this, x, z);
    }

    @Override
    public Chunk getChunkForEditing(int x, int z) {
        return getChunk(x, z);
    }

    @Override
    public void close() {
        // Do nothing
    }

    // WPObject
    
    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Point3i getDimensions() {
        return dimensions;
    }

    @Override
    public Material getMaterial(int x, int y, int z) {
        return materialAtRelative(x, y, z);
    }

    @Override
    public boolean getMask(int x, int y, int z) {
        return materialAtRelative(x, y, z) != null;
    }

    @Override
    public List<Entity> getEntities() {
        return null;
    }

    @Override
    public List<TileEntity> getTileEntities() {
        return null;
    }

    @Override
    public void prepareForExport(Dimension dimension) {
        // Do nothing
    }

    @Override
    public Map<String, Serializable> getAttributes() {
        return null;
    }

    @Override
    public <T extends Serializable> T getAttribute(AttributeKey<T> key) {
        if (key.equals(ATTRIBUTE_OFFSET)) {
            //noinspection unchecked // Responsibility of caller
            return (T) offset;
        } else {
            return key.defaultValue;
        }
    }

    @Override
    public void setAttributes(Map<String, Serializable> attributes) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public <T extends Serializable> void setAttribute(AttributeKey<T> key, T value) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Point3i getOffset() {
        return getAttribute(ATTRIBUTE_OFFSET);
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public MinecraftWorldObject clone() {
        return new MinecraftWorldObject(this);
    }

    private Material materialAtRelative(int x, int y, int z) {
        return compactBlocks == null ? blocks[x][y][z] : compactBlocks.get(x, y, z);
    }
    private void putRelative(int x, int y, int z, Material material) {
        if (compactBlocks == null) blocks[x][y][z] = material; else compactBlocks.set(x, y, z, material);
    }
    private static boolean compactEnabled(int width, int length, int height) {
        return Boolean.parseBoolean(System.getProperty("welt.compact.previewWorld", "true")) && width >= 0 && length >= 0 && height >= 0
                && (height == 0 || (long) width * length <= Integer.MAX_VALUE / height);
    }
    private static Material[][][] copyLegacy(Material[][][] source) {
        Material[][][] copy = source.clone();
        for (int x = 0; x < copy.length; x++) if (source[x] != null) {
            copy[x] = source[x].clone();
            for (int y = 0; y < copy[x].length; y++) if (source[x][y] != null) copy[x][y] = source[x][y].clone();
        }
        return copy;
    }
    // Retain the historical field layout; Box currently prevents Java serialization.
    private void writeObject(ObjectOutputStream out) throws IOException {
        ObjectOutputStream.PutField fields = out.putFields();
        fields.put("name", name); fields.put("volume", volume); fields.put("dx", dx); fields.put("dy", dy); fields.put("dz", dz);
        fields.put("maxHeight", maxHeight); fields.put("waterLevel", waterLevel); fields.put("dimensions", dimensions);
        fields.put("offset", offset); fields.put("lowestBlocks", lowestBlocks);
        fields.put("blocks", compactBlocks == null ? blocks : compactBlocks.toLegacy());
        out.writeFields();
    }
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (compactEnabled(volume.getWidth(), volume.getLength(), volume.getHeight())) {
            compactBlocks = MutableMaterialVolume.fromLegacy(blocks, volume.getWidth(), volume.getLength(), volume.getHeight());
            if (compactBlocks != null) blocks = null;
        }
    }
    private static final long serialVersionUID = 5054283000984305244L;

    private final String name;
    private final Box volume;
    private final int dx, dy, dz, maxHeight, waterLevel;
    private final Point3i dimensions, offset;
    private Material[][][] blocks;
    private transient MutableMaterialVolume compactBlocks;
    private final Material[] lowestBlocks;
}