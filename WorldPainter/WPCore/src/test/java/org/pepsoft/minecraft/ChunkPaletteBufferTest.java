package org.pepsoft.minecraft;

import org.junit.Test;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.pepsoft.minecraft.Material.*;

public final class ChunkPaletteBufferTest {
    @Test
    public void capturesChunkInVersionedLittleEndianPaletteLayout() {
        final MC12AnvilChunk chunk = new MC12AnvilChunk(-2, 7, 32);
        chunk.setMaterial(3, 17, 5, STONE);

        final ChunkPaletteBuffer.View view = ChunkPaletteBuffer.capture(chunk, 3465, ICE, AIR);

        assertEquals(-2, view.chunkX());
        assertEquals(7, view.chunkZ());
        assertEquals(0, view.minY());
        assertEquals(32, view.maxY());
        assertEquals(STONE, view.material(view.indexAt(3, 17, 5)));
        assertEquals(AIR, view.material(view.indexAt(0, 0, 0)));
        assertTrue(view.paletteSize() >= 3);

        final ByteBuffer buffer = view.buffer();
        assertEquals(ChunkPaletteBuffer.MAGIC, buffer.getInt(0));
        assertEquals(ChunkPaletteBuffer.ABI_VERSION, buffer.getInt(4));
        assertEquals(ChunkPaletteBuffer.HEADER_BYTES, buffer.getInt(8));
        assertEquals(2, buffer.getInt(28));
        assertEquals(ChunkPaletteBuffer.HEADER_BYTES, buffer.getInt(36));
        assertEquals(ChunkPaletteBuffer.HEADER_BYTES + 2 * ChunkPaletteBuffer.SECTION_DESCRIPTOR_BYTES,
                buffer.getInt(40));
        final int expectedPayload = ChunkPaletteBuffer.HEADER_BYTES
                + 2 * ChunkPaletteBuffer.SECTION_DESCRIPTOR_BYTES + view.paletteSize() * Integer.BYTES;
        assertEquals(expectedPayload, buffer.getInt(44));
        assertEquals(1, buffer.getInt(48));
        assertEquals(3465, buffer.getInt(52));
        assertEquals(0, buffer.getInt(56));
        assertEquals(expectedPayload, buffer.getInt(60));
        assertEquals(0, view.mutationSequence());
        assertEquals(1, view.completeMutationBatch());
        assertEquals(1, view.buffer().getInt(64));
    }

    @Test
    public void writesOnlyPreviouslyReservedPaletteMaterials() {
        final MC12AnvilChunk chunk = new MC12AnvilChunk(0, 0, 16);
        final ChunkPaletteBuffer.View view = ChunkPaletteBuffer.capture(chunk, ICE);
        view.setIndexAt(1, 4, 1, 0);

        assertEquals(ICE, view.material(view.indexAt(1, 4, 1)));
    }

    @Test
    public void capturesLegacyStorageInChunkOrder() {
        final MC12AnvilChunk chunk = new MC12AnvilChunk(2, -3, 32);
        chunk.setMaterial(3, 17, 5, Material.get(35, 14));
        chunk.setMaterial(8, 7, 12, Material.get(300, 5));
        chunk.setMaterial(15, 31, 15, STONE);

        assertMatchesChunk(chunk);
    }

    @Test
    public void capturesPaletteStorageFor115AndNegativeHeight118() {
        final String previousView = System.getProperty("welt.packedArrayCube.compactPaletteStorage");
        try {
            System.setProperty("welt.packedArrayCube.compactPaletteStorage", "true");
            final Material leaves = Material.get("minecraft:oak_leaves");
            final MC115AnvilChunk chunk115 = new MC115AnvilChunk(1, 2, 32);
            chunk115.setMaterial(4, 19, 7, leaves);
            chunk115.setMaterial(2, 2, 1, STONE);
            assertTrue(chunk115.getSections()[0].materials.hasPaletteIndexStorage());
            assertMatchesChunk(chunk115);

            final MC118AnvilChunk chunk118 = new MC118AnvilChunk(-4, 8, -64, 64);
            chunk118.setMaterial(4, -55, 7, leaves);
            chunk118.setMaterial(2, 10, 1, STONE);
            assertTrue(chunk118.getSections()[0].materials == null
                    || chunk118.getSections()[0].materials.hasPaletteIndexStorage());
            assertMatchesChunk(chunk118);
        } finally {
            if (previousView == null) {
                System.clearProperty("welt.packedArrayCube.compactPaletteStorage");
            } else {
                System.setProperty("welt.packedArrayCube.compactPaletteStorage", previousView);
            }
        }
    }

    @Test
    public void encodesPaletteIndexesWiderThanOneByte() {
        final MC115AnvilChunk chunk = new MC115AnvilChunk(0, 0, 16);
        final List<Material> reserved = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            reserved.add(Material.get("welt:buffer_test_" + i));
        }
        final ChunkPaletteBuffer.View view = ChunkPaletteBuffer.capture(chunk,
                reserved.toArray(new Material[0]));

        assertEquals(2, view.indexWidthBytes());
        view.setIndexAt(15, 0, 15, 259);
        assertEquals(259, view.indexAt(15, 0, 15));
        assertEquals(reserved.get(259), view.material(259));
    }

    @Test
    public void validatesChunkBufferWithRustWhenNativeLibraryIsAvailable() {
        assumeTrue("welt_slices is loaded through java.library.path", NativeLoader.areSlicesAvailable());

        final MC12AnvilChunk chunk = new MC12AnvilChunk(-1, 3, 16);
        chunk.setMaterial(2, 4, 9, Material.STONE);
        final ChunkPaletteBuffer.View view = ChunkPaletteBuffer.capture(chunk);
        assertTrue(NativeSlices.validateChunkPaletteBuffer(view.buffer()));

        final ByteBuffer malformed = view.buffer();
        malformed.putInt(40, 0);
        assertFalse(NativeSlices.validateChunkPaletteBuffer(malformed));
    }

    private static void assertMatchesChunk(Chunk chunk) {
        final ChunkPaletteBuffer.View view = ChunkPaletteBuffer.capture(chunk, ICE, AIR);
        assertEquals(chunk.getMinHeight(), view.minY());
        assertEquals(chunk.getMaxHeight(), view.maxY());
        for (int y = chunk.getMinHeight(); y < chunk.getMaxHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals("at " + x + "," + y + "," + z,
                            chunk.getMaterial(x, y, z), view.material(view.indexAt(x, y, z)));
                }
            }
        }
    }
}
