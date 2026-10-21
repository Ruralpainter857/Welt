package org.pepsoft.worldpainter.objects;
import org.pepsoft.worldpainter.Platform;

/** Indexed sources may prepare a complete transformed snapshot without an intermediate object. */
public interface CompactTransformedObjectSource {
    /** Parameters use the stable v1 transform layout; null requests Java traversal. */
    CompactMaterialVolume copyTransformedCompactVolume(int[] parameters, Platform[] platforms);
}