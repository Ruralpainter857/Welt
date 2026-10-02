package org.pepsoft.worldpainter.panels;

import java.awt.Rectangle;
import java.util.List;
import org.junit.Test;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.TestData;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.operations.Filter;
import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.panels.EditorFilterPlan.*;

public class EditorFilterPlanTest {
    private Dimension dimension() { return TestData.createDimension(new Rectangle(0, 0, 128, 128), 62); }

    @Test public void snapshotsDefaultOrderLevelsSlopeAndDependencies() {
        Dimension d = dimension();
        DefaultFilter f = new DefaultFilter(d, true, false, 62, 60, true,
                true, List.of(Terrain.GRASS, Frost.INSTANCE), true,
                TerrainOrLayerFilter.WATER, 35, false);
        EditorFilterPlan p = compile(f, d);
        assertNotNull(p);
        DefaultNode root = (DefaultNode) p.nodes().get(p.nodes().size() - 1);
        assertTrue(root.except() < root.only());
        assertEquals(DefaultFilter.LevelType.OUTSIDE, root.levels());
        assertEquals(Float.floatToIntBits(f.slope), Float.floatToIntBits(root.slope()));
        assertTrue(root.feather());
        assertEquals(1, root.selection());
        assertEquals(HEIGHT | WATER | LAVA | SLOPE | TERRAIN | SELECTION, p.dependencies());
        assertEquals(List.of(Frost.INSTANCE), p.layers());
    }

    @Test public void deduplicatesPlanesAndRetainsPredicateDirection() {
        Dimension d = dimension();
        Filter f = new CombinedFilter(List.of(OnlyOnTerrainOrLayerFilter.create(d, Frost.INSTANCE),
                ExceptOnTerrainOrLayerFilter.create(d, Frost.INSTANCE)));
        EditorFilterPlan p = compile(f, d);
        assertEquals(1, p.layers().size());
        PredicateNode only = (PredicateNode) p.nodes().get(0), except = (PredicateNode) p.nodes().get(1);
        assertEquals(0, only.plane()); assertEquals(0, except.plane());
        assertFalse(only.except()); assertTrue(except.except());
        assertEquals(List.of(0, 1), ((CombinedNode) p.nodes().get(2)).children());
    }

    @Test public void capturesAutoBiomeAsymmetryAndRejectsOtherDimension() {
        Dimension d = dimension();
        Filter f = ExceptOnTerrainOrLayerFilter.create(d, new DefaultFilter.LayerValue(Biome.INSTANCE, -12));
        EditorFilterPlan p = compile(f, d);
        assertEquals(BIOME | AUTO_BIOME, p.dependencies());
        assertEquals(12, ((PredicateNode) p.nodes().get(0)).value());
        assertNull(compile(f, dimension()));
    }

    @Test public void customAndExcessivelyDeepFiltersFallBack() {
        Dimension d = dimension();
        assertNull(compile((x, y, strength) -> strength, d));
        Filter f = new CombinedFilter(List.of());
        for (int i = 0; i < 128; i++) f = new CombinedFilter(List.of(f));
        assertNull(compile(f, d));
        assertNull(compile(new DefaultFilter(d, false, false, 0, 64, false,
                true, null, false, null, -1, false), d));
    }

    @Test public void planCollectionsAreImmutable() {
        Dimension d = dimension();
        EditorFilterPlan p = compile(new CombinedFilter(List.of()), d);
        assertThrows(UnsupportedOperationException.class, () -> p.nodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((CombinedNode) p.nodes().get(0)).children().add(0));
    }
}
