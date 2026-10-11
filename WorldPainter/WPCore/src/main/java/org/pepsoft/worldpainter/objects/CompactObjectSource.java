package org.pepsoft.worldpainter.objects;

/** Supplies an owned immutable snapshot without per-block material lookups. */
public interface CompactObjectSource {
    /** Null requests the ordinary WPObject snapshot traversal. */
    CompactMaterialVolume copyCompactVolume();
}