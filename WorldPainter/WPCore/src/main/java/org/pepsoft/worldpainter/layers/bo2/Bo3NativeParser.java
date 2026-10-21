package org.pepsoft.worldpainter.layers.bo2;

import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.slf4j.LoggerFactory;
import javax.vecmath.Point3i;
import java.io.*;
import java.lang.invoke.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** One source-order BO3 transaction; Java retains material, RNG and tile-entity semantics. */
final class Bo3NativeParser {
    private static final VarHandle WORD = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final AtomicLong COMPLETED = new AtomicLong();
    record Decoded(Map<String,String> properties, Map<Point3i,Bo3BlockSpec> blocks, Point3i origin, Point3i dimensions) { }
    static long completedObjects() { return COMPLETED.get(); }
    static void completed() { COMPLETED.incrementAndGet(); }
    private static int word(byte[] bytes, int at) { return (int) WORD.get(bytes, at); }
    private static String span(byte[] bytes, int start, int length) {
        if (start < 0 || length < 0 || (long) start + length > bytes.length) throw new IllegalArgumentException("Invalid native BO3 span");
        return new String(bytes, start, length, StandardCharsets.US_ASCII);
    }

    static Decoded decode(byte[] source, File file) throws IOException {
        byte[] frame = NativeSlices.parseBo3(source);
        if (frame == null) return null;
        if (frame.length < 64 || word(frame,0) != 0x57424f33 || word(frame,4) != 1 || word(frame,56) != frame.length || word(frame,60) != source.length) throw new IllegalArgumentException("Invalid native BO3 header");
        int events = word(frame,8), choices = word(frame,12), materials = word(frame,16);
        int choicesAt = word(frame,48), materialsAt = word(frame,52);
        if (events < 1 || events > 300000 || choices < 1 || choices > 524288 || materials < 1 || materials > 65536 || word(frame,44) != 64
                || choicesAt != 64L + events * 40L || materialsAt != choicesAt + choices * 20L || frame.length != materialsAt + materials * 8L) throw new IllegalArgumentException("Invalid native BO3 layout");
        Material[] palette = new Material[materials];
        Map<String,String> properties = new HashMap<>();
        Map<Point3i,Bo3BlockSpec> blocks = new HashMap<>();
        Map<String,TileEntity> templates = new HashMap<>();
        for (int event = 0; event < events; event++) {
            int at = 64 + event * 40, type = word(frame,at);
            if (type == 0) {
                properties.put(span(source,word(frame,at+24),word(frame,at+28)), span(source,word(frame,at+32),word(frame,at+36)));
            } else if (type == 1 || type == 2) {
                LoggerFactory.getLogger(Bo3Object.class).warn((type == 1 ? "Ignoring unsupported bo3 feature " : "Ignoring unrecognised line: ") + span(source,word(frame,at+24),word(frame,at+28)));
            } else if (type == 3 || type == 4) {
                int first = word(frame,at+16), count = word(frame,at+20);
                if (first < 0 || count < 1 || (long) first + count > choices || type == 3 && count != 1) throw new IllegalArgumentException("Invalid native BO3 alternatives");
                Bo3BlockSpec.RandomBlock[] variants = type == 4 ? new Bo3BlockSpec.RandomBlock[count] : null;
                Material ordinary = null; TileEntity ordinaryTile = null;
                for (int i = 0; i < count; i++) {
                    int choice = choicesAt + (first + i) * 20, index = word(frame,choice);
                    if (index < 0 || index >= materials) throw new IllegalArgumentException("Invalid native BO3 material index");
                    // Resolve lazily so earlier NBT errors and warnings keep their source ordering.
                    if (palette[index] == null) {
                        int entry = materialsAt + index * 8;
                        palette[index] = Bo3Object.decodeMaterial(span(source,word(frame,entry),word(frame,entry+4)));
                    }
                    TileEntity tile = null;
                    if (word(frame,choice+4) != -1) {
                        String filename = span(source,word(frame,choice+4),word(frame,choice+8));
                        TileEntity template = templates.get(filename);
                        if (template == null) {
                            template = Bo3Object.loadTileEntity(file, filename);
                            // Bound transaction-local prototypes; every placed entity is a deep clone.
                            if (templates.size() < 64 && new File(file.getParentFile(), filename).length() <= 1024 * 1024) templates.put(filename, template);
                        }
                        tile = (TileEntity) template.clone();
                    }
                    if (variants != null) variants[i] = new Bo3BlockSpec.RandomBlock(palette[index],tile,word(frame,choice+12));
                    else { ordinary = palette[index]; ordinaryTile = tile; }
                }
                Point3i coords = new Point3i(word(frame,at+4),word(frame,at+8),word(frame,at+12));
                blocks.put(coords, variants == null ? new Bo3BlockSpec(coords,ordinary,ordinaryTile) : new Bo3BlockSpec(coords,variants));
            } else throw new IllegalArgumentException("Invalid native BO3 event type");
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("Empty native BO3 blocks");
        int x = word(frame,20), y = word(frame,24), z = word(frame,28);
        return new Decoded(properties,blocks,new Point3i(-x,-y,-z),new Point3i(word(frame,32)-x+1,word(frame,36)-y+1,word(frame,40)-z+1));
    }
}
