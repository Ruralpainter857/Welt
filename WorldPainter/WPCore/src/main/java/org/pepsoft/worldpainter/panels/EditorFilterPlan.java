package org.pepsoft.worldpainter.panels;

import java.util.ArrayList;
import java.util.List;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.operations.Filter;

/**
 * Immutable filter snapshot for fused editor transactions. Dependencies describe
 * the planes to prepare once; this class never evaluates cells or mutates tiles.
 * Unsupported filters must retain their original Java path.
 */
public record EditorFilterPlan(List<Node> nodes, List<Layer> layers, int dependencies) {
    public static final int HEIGHT = 1, WATER = 2, SLOPE = 4, TERRAIN = 8,
            BIOME = 16, AUTO_BIOME = 32, LAVA = 64, SELECTION = 128, ANNOTATIONS = 256;

    public EditorFilterPlan {
        nodes = List.copyOf(nodes);
        layers = List.copyOf(layers);
    }

    public sealed interface Node permits PredicateNode, CombinedNode, DefaultNode { }
    public record PredicateNode(TerrainOrLayerFilter.ObjectType type, int value,
                                int plane, boolean except) implements Node { }
    public record CombinedNode(List<Integer> children) implements Node {
        public CombinedNode { children = List.copyOf(children); }
    }
    public record DefaultNode(int selection, int except, int only,
                              DefaultFilter.LevelType levels, int above, int below,
                              boolean feather, boolean checkSlope, float slope,
                              boolean slopeIsAbove) implements Node { }

    /** Returns null before any mutation when a custom filter cannot be represented. */
    public static EditorFilterPlan compile(Filter filter, Dimension dimension) {
        if (filter == null || dimension == null || dimension.getClass() != Dimension.class) return null;
        Compiler compiler = new Compiler(dimension);
        return compiler.append(filter, 0) < 0 ? null
                : new EditorFilterPlan(compiler.nodes, compiler.layers, compiler.dependencies);
    }

    private static final class Compiler {
        private final Dimension dimension;
        private final List<Node> nodes = new ArrayList<>();
        private final List<Layer> layers = new ArrayList<>();
        private int dependencies;
        private Compiler(Dimension dimension) { this.dimension = dimension; }

        private int append(Filter filter, int depth) {
            if (filter == null || depth >= 128 || nodes.size() >= 128) return -1;
            Node node;
            if (filter.getClass() == CombinedFilter.class) {
                List<Integer> children = new ArrayList<>();
                for (Filter child : ((CombinedFilter) filter).getFilters()) {
                    int index = append(child, depth + 1);
                    if (index < 0) return -1;
                    children.add(index);
                }
                node = new CombinedNode(children);
            } else if (filter.getClass() == DefaultFilter.class) {
                DefaultFilter source = (DefaultFilter) filter;
                if (source.dimension != dimension) return -1;
                int except = source.exceptOn ? append(source.exceptOnFilter, depth + 1) : -1;
                if (source.exceptOn && except < 0) return -1;
                int only = source.onlyOn ? append(source.onlyOnFilter, depth + 1) : -1;
                if (source.onlyOn && only < 0) return -1;
                int selection = source.inSelection ? 1 : source.outsideSelection ? -1 : 0;
                if (selection != 0) dependencies |= SELECTION;
                if (source.checkLevel) dependencies |= HEIGHT;
                if (source.checkSlope) dependencies |= SLOPE;
                node = new DefaultNode(selection, except, only, source.levelType,
                        source.aboveLevel, source.belowLevel, source.feather,
                        source.checkSlope, source.slope, source.slopeIsAbove);
            } else if (filter.getClass() == OnlyOnTerrainOrLayerFilter.class
                    || filter.getClass() == ExceptOnTerrainOrLayerFilter.class) {
                TerrainOrLayerFilter source = (TerrainOrLayerFilter) filter;
                if (source.dimension != dimension) return -1;
                int plane = -1;
                switch (source.objectType) {
                    case BIOME -> dependencies |= BIOME;
                    case AUTO_BIOME -> dependencies |= BIOME | AUTO_BIOME;
                    case TERRAIN -> dependencies |= TERRAIN;
                    case WATER, LAVA -> dependencies |= HEIGHT | WATER | LAVA;
                    case LAND -> dependencies |= HEIGHT | WATER;
                    case ANNOTATION, ANNOTATION_ANY -> dependencies |= ANNOTATIONS;
                    default -> {
                        Layer layer = source.layer;
                        if (layer == null) return -1;
                        boolean bit = source.objectType == TerrainOrLayerFilter.ObjectType.BIT_LAYER;
                        if (bit ? layer.dataSize != Layer.DataSize.BIT && layer.dataSize != Layer.DataSize.BIT_PER_CHUNK
                                : layer.dataSize != Layer.DataSize.NIBBLE && layer.dataSize != Layer.DataSize.BYTE) return -1;
                        // Default values outside the encoded domain need the original getters.
                        if (!bit && (layer.getDefaultValue() < 0 || layer.getDefaultValue() > layer.dataSize.maxValue)) return -1;
                        plane = layers.indexOf(layer);
                        if (plane < 0) { plane = layers.size(); layers.add(layer); }
                    }
                }
                node = new PredicateNode(source.objectType,
                        source.terrain != null ? source.terrain.ordinal() : source.value,
                        plane, filter.getClass() == ExceptOnTerrainOrLayerFilter.class);
            } else {
                return -1;
            }
            if (nodes.size() >= 128) return -1;
            nodes.add(node);
            return nodes.size() - 1;
        }
    }
}
