package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

/** Protects weighted material selection and project persistence before native BO3 loading. */
public class Bo3RandomBlockRegressionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void ordinarySpecsStillRejectMissingMaterialsAndCoordinates() {
        assertThrows(NullPointerException.class, () -> new Bo2BlockSpec(new Point3i(), null, null));
        assertThrows(NullPointerException.class, () -> new Bo2BlockSpec(null, Material.STONE, null));
    }

    @Test public void weightedSelectionKeepsTheJavaDrawOrderAndLastAlternativeFallback() throws Exception {
        Material[] materials = {Material.STONE, Material.get(17, 0), Material.get(35, 4)};
        int[] chances = {15, 70, -3};
        Bo3BlockSpec.RandomBlock[] variants = new Bo3BlockSpec.RandomBlock[3];
        for (int i = 0; i < 3; i++) variants[i] = new Bo3BlockSpec.RandomBlock(materials[i], null, chances[i]);
        Bo3BlockSpec spec = new Bo3BlockSpec(new Point3i(-2, 3, 7), variants);
        var field = Bo3BlockSpec.class.getDeclaredField("RANDOM");
        field.setAccessible(true);
        Random actual = (Random) field.get(null), expected = new Random(93841);
        synchronized (actual) {
            actual.setSeed(93841);
            for (int sample = 0; sample < 1000; sample++) {
                Material selected = materials[2];
                for (int i = 0; i < 3; i++) {
                    if (chances[i] >= 100 || expected.nextInt(100) < chances[i]) { selected = materials[i]; break; }
                }
                assertSame(selected, spec.getMaterial());
            }
            assertEquals(expected.nextLong(), actual.nextLong());
        }
    }

    @Test public void certainAlternativesDoNotConsumeRandomDraws() throws Exception {
        var field = Bo3BlockSpec.class.getDeclaredField("RANDOM");
        field.setAccessible(true);
        Random actual = (Random) field.get(null);
        synchronized (actual) {
            actual.setSeed(7331);
            Bo3BlockSpec spec = new Bo3BlockSpec(new Point3i(), new Bo3BlockSpec.RandomBlock[]{
                    new Bo3BlockSpec.RandomBlock(Material.STONE, null, 100),
                    new Bo3BlockSpec.RandomBlock(Material.get(35, 4), null, 100)});
            assertSame(Material.STONE, spec.getMaterial());
            assertEquals(new Random(7331).nextLong(), actual.nextLong());
        }
    }

    @Test public void randomVariantsAndTheirTileEntitiesSurviveProjectSerialization() throws Exception {
        TileEntity tile = TileEntity.fromNBT(new CompoundTag("", new HashMap<>(Map.of(
                "id", new StringTag("id", "welt:container"), "x", new IntTag("x", 0),
                "y", new IntTag("y", 0), "z", new IntTag("z", 0), "custom", new StringTag("custom", "preserved")))));
        Point3i coords = new Point3i(-2, 3, 7);
        Bo3BlockSpec original = new Bo3BlockSpec(coords, new Bo3BlockSpec.RandomBlock[]{
                new Bo3BlockSpec.RandomBlock(Material.STONE, null, 0),
                new Bo3BlockSpec.RandomBlock(Material.get(35, 4), tile, 100)});
        Bo3BlockSpec copy = roundTrip(original);
        assertEquals(coords, copy.getCoords());
        assertSame(Material.get(35, 4), copy.getMaterial());
        TileEntity copiedTile = copy.getTileEntities().iterator().next();
        assertEquals(-2, copiedTile.getX()); assertEquals(7, copiedTile.getY()); assertEquals(3, copiedTile.getZ());
        assertNotSame(tile, copiedTile);
    }

    @Test public void completeFileLoadingPreservesRandomBlocksNbtOffsetsCloneAndSave() throws Exception {
        File nbt = temporary.newFile("container.nbt");
        Map<String, Tag> values = new HashMap<>(Map.of("id", new StringTag("id", "welt:container"),
                "x", new IntTag("x", 0), "y", new IntTag("y", 0), "z", new IntTag("z", 0)));
        try (NBTOutputStream out = new NBTOutputStream(new GZIPOutputStream(new FileOutputStream(nbt)))) {
            out.writeTag(new CompoundTag("", new HashMap<>(Map.of("wrapped", new CompoundTag("wrapped", values)))));
        }
        File source = temporary.newFile("mixed.bo3");
        Files.writeString(source.toPath(), "RotateRandomly: false\nBlock(-2,7,3,STONE)\nRandomBlock(1,5,-4,1,0,35:4,container.nbt,100)\n", StandardCharsets.US_ASCII);
        Bo3Object object = Bo3Object.load("Mixed", source);
        for (Bo3Object candidate : List.of(object, object.clone(), roundTrip(object))) {
            assertEquals(new Point3i(4, 8, 3), candidate.getDimensions());
            assertEquals(new Point3i(-2, -4, 5), candidate.getOffset());
            assertSame(Material.STONE, candidate.getMaterial(0, 7, 2));
            assertSame(Material.get(35, 4), candidate.getMaterial(3, 0, 0));
            assertEquals(1, candidate.getTileEntities().size());
            TileEntity tile = candidate.getTileEntities().get(0);
            assertEquals(3, tile.getX()); assertEquals(0, tile.getY()); assertEquals(0, tile.getZ());
            assertEquals(source, candidate.getAttribute(Bo3Object.ATTRIBUTE_FILE));
        }
    }

    @SuppressWarnings("unchecked") private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(value); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (T) in.readObject(); }
    }
}
