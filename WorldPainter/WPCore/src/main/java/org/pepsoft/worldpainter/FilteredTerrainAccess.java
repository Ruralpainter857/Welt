package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.panels.EditorFilterPlan;

/** Compatibility entry point; terrain and layer painting share one worker buffer. */
public final class FilteredTerrainAccess {
    public static final int MAX_BYTES = FilteredPaintAccess.MAX_BYTES;
    private FilteredTerrainAccess() { }
    public static long completedTransactions() { return FilteredPaintAccess.completedTransactions(); }
    public static boolean apply(Dimension dimension, Terrain target, EditorFilterPlan plan,
                                int ox, int oy, int width, int height, float dynamic, float[] strengths) {
        return FilteredPaintAccess.apply(dimension, target, plan, ox, oy, width, height, dynamic, strengths);
    }
}
