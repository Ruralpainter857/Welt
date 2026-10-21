package org.pepsoft.worldpainter.panels;

import java.awt.Rectangle;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.operations.Filter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;

/** Generates the Rust golden fixture from actual Java filter calls, never a reimplementation. */
public final class EditorFilterOracle {
    public static void main(String[] args) throws Exception {
        System.setProperty(Native.GEN_KEY, "false");
        Dimension d = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) {
            d.setHeightAt(x, y, 58 + (x + y) / 2f);
            d.setWaterLevelAt(x, y, 61 + (x & 1));
            d.setTerrainAt(x, y, (x & 1) == 0 ? Terrain.GRASS : Terrain.SAND);
            d.setLayerValueAt(Biome.INSTANCE, x, y, (x & 1) == 0 ? 255 : 12);
            d.setLayerValueAt(Annotations.INSTANCE, x, y, (x + y) & 3);
            d.setLayerValueAt(Resources.INSTANCE, x, y, (x * 3 + y) & 15);
            d.setBitLayerValueAt(Frost.INSTANCE, x, y, (x & 2) == 0);
            d.setBitLayerValueAt(FloodWithLava.INSTANCE, x, y, (y & 1) != 0);
            d.setBitLayerValueAt(SelectionBlock.INSTANCE, x, y, (x + y) % 3 == 0);
        }
        d.setBitLayerValueAt(SelectionChunk.INSTANCE, 16, 0, true);
        List<Filter> filters = new ArrayList<>();
        Object[] items = { Terrain.GRASS, Frost.INSTANCE, SelectionChunk.INSTANCE, Resources.INSTANCE,
                new DefaultFilter.LayerValue(Resources.INSTANCE, 5),
                new DefaultFilter.LayerValue(Resources.INSTANCE, 5, DefaultFilter.Condition.HIGHER_THAN_OR_EQUAL),
                new DefaultFilter.LayerValue(Resources.INSTANCE, 5, DefaultFilter.Condition.LOWER_THAN_OR_EQUAL),
                new DefaultFilter.LayerValue(Biome.INSTANCE, 12), new DefaultFilter.LayerValue(Biome.INSTANCE, -12),
                TerrainOrLayerFilter.AUTO_BIOMES, TerrainOrLayerFilter.WATER, TerrainOrLayerFilter.LAVA,
                TerrainOrLayerFilter.LAND, new DefaultFilter.LayerValue(Annotations.INSTANCE),
                new DefaultFilter.LayerValue(Annotations.INSTANCE, 2) };
        for (Object item : items) {
            filters.add(OnlyOnTerrainOrLayerFilter.create(d, item));
            filters.add(ExceptOnTerrainOrLayerFilter.create(d, item));
        }
        int absent = Integer.MIN_VALUE;
        for (int[] levels : new int[][] {{absent, absent}, {62, absent}, {absent, 60}, {60, 62}, {62, 60}, {Integer.MAX_VALUE, absent}})
            for (boolean feather : new boolean[] {false, true})
                for (int selection : new int[] {-1, 0, 1})
                    filters.add(new DefaultFilter(d, selection == 1, selection == -1, levels[0], levels[1], feather,
                            true, List.of(Terrain.GRASS, Frost.INSTANCE), true, TerrainOrLayerFilter.LAVA, 20, false));
        filters.add(new CombinedFilter(List.of()));
        filters.add(new CombinedFilter(List.of(filters.get(0), filters.get(2))));
        filters.add(new DefaultFilter(d, false, false, 62, absent, true,
                false, null, false, null, 20, true).and(new DefaultFilter(d, false, false, absent, 60, true,
                false, null, false, null, -1, false)));
        float[] strengths = { 0f, -0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.MIN_VALUE, 0.8f, 1f };
        int[][] points = {{0, 0}, {1, 1}, {2, 3}, {7, 7}, {16, 0}, {-1, 0}, {127, 127}, {128, 0}};
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
            out.writeInt(0x57454631); out.writeInt(filters.size());
            for (Filter f : filters) {
                EditorFilterPlan p = EditorFilterPlan.compile(f, d);
                if (p == null) throw new AssertionError("Unsupported oracle filter");
                out.writeInt(p.nodes().size());
                out.writeInt(p.layers().size());
                for (EditorFilterPlan.Node node : p.nodes()) {
                    if (node instanceof EditorFilterPlan.PredicateNode n) {
                        out.writeInt(0); out.writeInt(n.type().ordinal()); out.writeInt(n.value());
                        out.writeInt(n.plane()); out.writeBoolean(n.except());
                    } else if (node instanceof EditorFilterPlan.CombinedNode n) {
                        out.writeInt(1); out.writeInt(n.children().size());
                        for (int child : n.children()) out.writeInt(child);
                    } else if (node instanceof EditorFilterPlan.DefaultNode n) {
                        out.writeInt(2); out.writeInt(n.selection()); out.writeInt(n.except()); out.writeInt(n.only());
                        out.writeInt(n.levels() == null ? -1 : n.levels().ordinal());
                        out.writeInt(n.above()); out.writeInt(n.below()); out.writeBoolean(n.feather());
                        out.writeBoolean(n.checkSlope()); out.writeFloat(n.slope()); out.writeBoolean(n.slopeIsAbove());
                    }
                }
                out.writeInt(points.length * strengths.length);
                for (int[] point : points) for (float strength : strengths) {
                    int x = point[0], y = point[1];
                    out.writeInt(d.getIntHeightAt(x, y)); out.writeInt(d.getWaterLevelAt(x, y));
                    out.writeFloat(d.getSlope(x, y));
                    Terrain terrain = d.getTerrainAt(x, y);
                    out.writeInt(terrain == null ? -1 : terrain.ordinal());
                    out.writeInt(d.getLayerValueAt(Biome.INSTANCE, x, y)); out.writeInt(d.getAutoBiome(x, y));
                    out.writeBoolean(d.getBitLayerValueAt(FloodWithLava.INSTANCE, x, y));
                    out.writeBoolean(d.getBitLayerValueAt(SelectionBlock.INSTANCE, x, y)
                            || d.getBitLayerValueAt(SelectionChunk.INSTANCE, x, y));
                    out.writeInt(d.getLayerValueAt(Annotations.INSTANCE, x, y));
                    for (Layer layer : p.layers()) out.writeInt(layer.dataSize == Layer.DataSize.BIT
                            || layer.dataSize == Layer.DataSize.BIT_PER_CHUNK
                            ? d.getBitLayerValueAt(layer, x, y) ? 1 : 0 : d.getLayerValueAt(layer, x, y));
                    out.writeInt(Float.floatToIntBits(strength));
                    out.writeInt(Float.floatToIntBits(f.modifyStrength(x, y, strength)));
                }
            }
        }
        System.out.println("Java oracle filters=" + filters.size() + " cases=" + filters.size() * points.length * strengths.length);
    }
}
