package org.pepsoft.worldpainter.objects;

import org.pepsoft.minecraft.Material;
import java.util.Objects;
import java.util.function.Consumer;

/** Read-only classification; consumers tolerate repetitions and retain first-occurrence ordering. */
public final class CompactObjectMaterials {
    private CompactObjectMaterials() { }

    /** The consumer must not change voxel data. Native readers may collapse repeated source states. */
    public static void forEach(WPObject object, Consumer<Material> consumer) {
        Objects.requireNonNull(object); Objects.requireNonNull(consumer);
        if (object instanceof CompactObjectSource && ((CompactObjectSource) object).forEachClassificationMaterial(consumer)) return;
        // Preserve format-specific traversal and every random draw for all other sources.
        object.visitBlocks((source, x, y, z, material) -> { consumer.accept(material); return true; });
    }
}
