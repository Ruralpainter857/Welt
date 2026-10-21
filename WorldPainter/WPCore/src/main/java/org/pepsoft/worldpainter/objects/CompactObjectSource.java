package org.pepsoft.worldpainter.objects;

/** Optional bulk object reads; unsupported operations retain the original traversal. */
public interface CompactObjectSource {
    /** Null requests the ordinary WPObject snapshot traversal. */
    CompactMaterialVolume copyCompactVolume();
    /** Read-only classification in first-occurrence order; consumers tolerate duplicate materials.
     * False must emit no callbacks, allowing the caller to use the original visitor.
     */
    default boolean forEachClassificationMaterial(java.util.function.Consumer<org.pepsoft.minecraft.Material> consumer) {
        return false;
    }
}
