package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.Layer;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Undo history storage for the grid buffers and the byte layer map of a {@link Tile}.
 *
 * <p>The {@link org.pepsoft.util.undo.UndoManager} copies a buffer once per history frame, the first time it is
 * edited after a save point, and keeps the previous version in the older frame. For raw arrays and maps that means a
 * complete copy of every touched buffer per frame, and a copy of <em>all</em> byte layers of a tile when only one of
 * them is painted. The versions in this class change what is retained, not how the tile reads or writes its data:
 *
 * <ul>
 *     <li>{@link GridVersion}: while editable it holds the tile's array. When it becomes history (the next version is
 *     copied from it) it is converted to 64 immutable 16x16 pages, and pages equal to those of the previous history
 *     version of the same buffer are shared rather than stored again. Each version decodes on its own; there is no
 *     diff chain. Reading a history version (undo, redo, snapshots) assembles the array once and caches it weakly.
 *     <li>{@link LayerDataVersion}: copying it copies the map only; the layer arrays are shared with the previous
 *     version until the tile writes one of them, at which point only that layer is copied (see
 *     {@link #detachLayer(Object, Layer, byte[], boolean)}). History versions that no longer share an array with the
 *     editable version store it as pages, like grids.
 * </ul>
 *
 * <p>The versions are owned by the undo manager's frames and by snapshots only, and are released with them. No global
 * cache is involved. Setting the system property {@code welt.tileHistory.compact} to {@code false} makes the
 * {@code wrap} methods return the raw buffers, restoring the original history behaviour.
 */
final class TileHistoryStorage {
    private TileHistoryStorage() {
        // Utility class
    }

    /** Wrap a grid array (short[], int[] or byte[] of one value per cell) for the undo manager. */
    static Object wrapGrid(Object array, boolean shared) {
        return ENABLED ? new GridVersion(array, ! shared, null) : array;
    }

    /** Wrap the byte layer map of a tile for the undo manager. The map remains the tile's live map. */
    static Object wrapLayers(Map<Layer, byte[]> layerData) {
        return ENABLED ? new LayerDataVersion(layerData, null) : layerData;
    }

    static boolean isLayerDataVersion(Object buffer) {
        return buffer instanceof LayerDataVersion;
    }

    /** The data of an undo buffer, for reading only. */
    @SuppressWarnings("unchecked")
    static <T> T read(Object buffer) {
        if (buffer instanceof GridVersion version) {
            return (T) version.read();
        } else if (buffer instanceof LayerDataVersion version) {
            return (T) version.read();
        } else {
            return (T) buffer;
        }
    }

    /**
     * The data of an undo buffer obtained from {@code getBufferForEditing()}, for writing. Grid arrays returned by this
     * method are private to the version; for layer maps, arrays may still be shared and must be passed through
     * {@link #detachLayer(Object, Layer, byte[], boolean)} before being modified in place.
     */
    @SuppressWarnings("unchecked")
    static <T> T edit(Object buffer) {
        if (buffer instanceof GridVersion version) {
            return (T) version.edit();
        } else if (buffer instanceof LayerDataVersion version) {
            return (T) version.edit();
        } else {
            return (T) buffer;
        }
    }

    /**
     * Get a layer array of the editable layer map that may be modified in place, copying it (and replacing it in the
     * map) if it is shared with a history version or is a globally shared uniform buffer.
     */
    static byte[] detachLayer(Object version, Layer layer, byte[] values, boolean globallyShared) {
        return ((LayerDataVersion) version).detach(layer, values, globallyShared);
    }

    /**
     * Record that layers were removed in place from the readable map of a layer data buffer (as
     * {@link Tile#prepareForSaving()} does for layers containing only default values), so that the removal persists
     * in the history version and is not undone when its map is assembled again.
     */
    static void layersRemoved(Object buffer, Collection<Layer> layers) {
        if (buffer instanceof LayerDataVersion version) {
            version.layersRemoved(layers);
        }
    }

    /** Diagnostics: the configuration. */
    static String describe() {
        return ENABLED ? "compact" : "compact-disabled";
    }

    /** Diagnostics: the readable content of an undo buffer. */
    static Object content(Object buffer) {
        return read(buffer);
    }

    static final boolean ENABLED = ! "false".equalsIgnoreCase(System.getProperty("welt.tileHistory.compact"));

    static final class GridVersion implements org.pepsoft.util.undo.Cloneable<GridVersion> {
        GridVersion(Object array, boolean owned, GridVersion parent) {
            this.array = array;
            this.owned = owned;
            this.parent = (parent != null) ? new WeakReference<>(parent) : null;
        }

        synchronized Object read() {
            if (array != null) {
                return array;
            }
            Object result = (inflated != null) ? inflated.get() : null;
            if (result == null) {
                result = Pages.assemble(pages, length);
                inflated = new WeakReference<>(result);
            }
            return result;
        }

        synchronized Object edit() {
            if (array == null) {
                // A history version made editable again (an edit after an undo without a new save point). The undo
                // manager hands out the same object in that case, and readers of this frame see the edits, as before
                array = read();
                owned = true;
                pages = null;
                inflated = null;
            } else if (! owned) {
                array = Pages.copy(array);
                owned = true;
            }
            return array;
        }

        @Override
        public synchronized GridVersion clone() {
            final Object source = (array != null) ? array : ((inflated != null) ? inflated.get() : null);
            // A history version nobody is reading is assembled straight into the copy
            final GridVersion copy = new GridVersion((source != null) ? Pages.copy(source) : Pages.assemble(pages, length), true, this);
            if ((array != null) && Pages.pageable(array)) {
                // This version becomes history
                final GridVersion previous = (parent != null) ? parent.get() : null;
                length = Pages.length(array);
                pages = Pages.paginate(array, (previous != null) ? previous.pages() : null);
                inflated = new WeakReference<>(array);
                array = null;
                owned = false;
            }
            return copy;
        }

        private synchronized Object[] pages() {
            return pages;
        }

        private Object array;
        private boolean owned;
        private Object[] pages;
        private int length;
        private WeakReference<Object> inflated;
        private final WeakReference<GridVersion> parent;
    }

    static final class LayerDataVersion implements org.pepsoft.util.undo.Cloneable<LayerDataVersion> {
        LayerDataVersion(Map<Layer, byte[]> map, LayerDataVersion parent) {
            this.map = map;
            this.parent = (parent != null) ? new WeakReference<>(parent) : null;
        }

        synchronized Map<Layer, byte[]> read() {
            if (map != null) {
                return map;
            }
            Map<Layer, byte[]> result = (inflated != null) ? inflated.get() : null;
            if (result == null) {
                result = new HashMap<>(cold.size() * 2);
                for (Map.Entry<Layer, Object> entry: cold.entrySet()) {
                    final Object value = entry.getValue();
                    result.put(entry.getKey(), (value instanceof byte[] values) ? values : (byte[]) Pages.assemble((Object[]) value, -1));
                }
                inflated = new WeakReference<>(result);
            }
            return result;
        }

        Map<Layer, byte[]> edit() {
            final Map<Layer, byte[]> result;
            final List<Map.Entry<Layer, byte[]>> released = new ArrayList<>();
            synchronized (this) {
                if (map == null) {
                    // A history version made editable again; arrays it still has directly may be shared with older
                    // versions, so treat them as borrowed
                    final Map<Layer, byte[]> current = read();
                    borrowed = new HashMap<>();
                    for (Map.Entry<Layer, Object> entry: cold.entrySet()) {
                        if (entry.getValue() instanceof byte[] values) {
                            borrowed.put(entry.getKey(), values);
                        }
                    }
                    map = current;
                    cold = null;
                    inflated = null;
                } else if (borrowed != null) {
                    // Forget arrays the tile replaced or removed since the last edit, so that they are not retained
                    for (Iterator<Map.Entry<Layer, byte[]>> i = borrowed.entrySet().iterator(); i.hasNext(); ) {
                        final Map.Entry<Layer, byte[]> entry = i.next();
                        if (map.get(entry.getKey()) != entry.getValue()) {
                            released.add(entry);
                            i.remove();
                        }
                    }
                }
                result = map;
            }
            released.forEach(entry -> retire(entry.getKey(), entry.getValue()));
            return result;
        }

        byte[] detach(Layer layer, byte[] values, boolean globallyShared) {
            final boolean wasBorrowed;
            final byte[] copy;
            synchronized (this) {
                wasBorrowed = (borrowed != null) && (borrowed.get(layer) == values);
                if (! (wasBorrowed || globallyShared)) {
                    return values;
                }
                if (wasBorrowed) {
                    borrowed.remove(layer);
                }
                copy = values.clone();
                map.put(layer, copy);
            }
            if (wasBorrowed && ! globallyShared) {
                retire(layer, values);
            }
            return copy;
        }

        synchronized void layersRemoved(Collection<Layer> layers) {
            if (map != null) {
                map.keySet().removeAll(layers);
            } else {
                cold.keySet().removeAll(layers);
                final Map<Layer, byte[]> current = (inflated != null) ? inflated.get() : null;
                if (current != null) {
                    current.keySet().removeAll(layers);
                }
            }
        }

        @Override
        public synchronized LayerDataVersion clone() {
            final Map<Layer, byte[]> source = read();
            final LayerDataVersion copy = new LayerDataVersion(new HashMap<>(source), this);
            copy.borrowed = new HashMap<>(source);
            if (map != null) {
                // This version becomes history. Its arrays are all shared with the copy for now
                cold = new HashMap<>(map);
                inflated = new WeakReference<>(map);
                map = null;
                borrowed = null;
            }
            return copy;
        }

        /**
         * The editable descendant of this version no longer uses {@code values} for {@code layer}: store it as pages in
         * every history version that still has it directly, sharing pages with the next older version.
         */
        private void retire(Layer layer, byte[] values) {
            final List<LayerDataVersion> holders = new ArrayList<>();
            Object older = null;
            for (LayerDataVersion version = (parent != null) ? parent.get() : null; version != null; version = version.parent()) {
                synchronized (version) {
                    if (version.cold == null) {
                        break;
                    }
                    final Object value = version.cold.get(layer);
                    if (value != values) {
                        older = value;
                        break;
                    }
                    holders.add(version);
                }
            }
            if (holders.isEmpty() || ! Pages.pageable(values)) {
                return;
            }
            final Object[] pages = Pages.paginate(values, (older instanceof Object[] olderPages) ? olderPages : null);
            for (LayerDataVersion holder: holders) {
                synchronized (holder) {
                    if ((holder.cold != null) && (holder.cold.get(layer) == values)) {
                        holder.cold.put(layer, pages);
                    }
                }
            }
        }

        private LayerDataVersion parent() {
            return (parent != null) ? parent.get() : null;
        }

        private Map<Layer, byte[]> map;
        private Map<Layer, byte[]> borrowed;
        private Map<Layer, Object> cold;
        private WeakReference<Map<Layer, byte[]>> inflated;
        private final WeakReference<LayerDataVersion> parent;
    }

    /**
     * Immutable 16x16 pages of a 128x128 grid stored in a byte[], short[] or int[] in row-major order, with one element
     * per cell or (for byte[]s of half the size) two nibbles per byte. A page is an array of the same type holding 16
     * rows of the page, and may be shared between versions.
     */
    static final class Pages {
        private Pages() {
            // Utility class
        }

        /** Whether the array is a full grid (one element per cell) or a nibble grid (two cells per byte). */
        static boolean pageable(Object array) {
            final int length = length(array);
            return (length == ROWS * ROWS) || ((length == ROWS * ROWS / 2) && (array instanceof byte[]));
        }

        static Object[] paginate(Object array, Object[] previous) {
            final int length = length(array);
            final int rowLength = length / ROWS, pageRowLength = rowLength / PAGES_PER_ROW, pageLength = pageRowLength * PAGE_ROWS;
            final Object[] pages = new Object[PAGES_PER_ROW * PAGES_PER_ROW];
            final boolean comparable = (previous != null) && (previous.length == pages.length) && (previous[0] != null)
                    && (previous[0].getClass() == array.getClass()) && (length(previous[0]) == pageLength);
            Map<Long, Object> uniformPages = null;
            for (int page = 0; page < pages.length; page++) {
                final int start = (page / PAGES_PER_ROW) * PAGE_ROWS * rowLength + (page % PAGES_PER_ROW) * pageRowLength;
                if (comparable && equalRows(array, start, rowLength, previous[page], pageRowLength)) {
                    pages[page] = previous[page];
                    continue;
                }
                final long uniformValue = uniformValue(array, start, rowLength, pageRowLength);
                if (uniformValue != NOT_UNIFORM) {
                    if (uniformPages == null) {
                        uniformPages = new HashMap<>();
                    }
                    Object uniformPage = uniformPages.get(uniformValue);
                    if (uniformPage == null) {
                        uniformPage = extract(array, start, rowLength, pageRowLength, pageLength);
                        uniformPages.put(uniformValue, uniformPage);
                    }
                    pages[page] = uniformPage;
                } else {
                    pages[page] = extract(array, start, rowLength, pageRowLength, pageLength);
                }
            }
            return pages;
        }

        static Object assemble(Object[] pages, int length) {
            final int pageLength = length(pages[0]);
            if (length < 0) {
                length = pageLength * pages.length;
            }
            final int rowLength = length / ROWS, pageRowLength = rowLength / PAGES_PER_ROW;
            final Object array = newArray(pages[0], length);
            for (int page = 0; page < pages.length; page++) {
                final int start = (page / PAGES_PER_ROW) * PAGE_ROWS * rowLength + (page % PAGES_PER_ROW) * pageRowLength;
                for (int row = 0; row < PAGE_ROWS; row++) {
                    System.arraycopy(pages[page], row * pageRowLength, array, start + row * rowLength, pageRowLength);
                }
            }
            return array;
        }

        static Object copy(Object array) {
            if (array instanceof short[] values) {
                return values.clone();
            } else if (array instanceof byte[] values) {
                return values.clone();
            } else if (array instanceof int[] values) {
                return values.clone();
            } else {
                throw new IllegalArgumentException("Unsupported grid type " + array.getClass());
            }
        }

        static int length(Object array) {
            if (array instanceof short[] values) {
                return values.length;
            } else if (array instanceof byte[] values) {
                return values.length;
            } else if (array instanceof int[] values) {
                return values.length;
            } else {
                throw new IllegalArgumentException("Unsupported grid type " + array.getClass());
            }
        }

        private static Object newArray(Object type, int length) {
            if (type instanceof short[]) {
                return new short[length];
            } else if (type instanceof byte[]) {
                return new byte[length];
            } else {
                return new int[length];
            }
        }

        private static Object extract(Object array, int start, int rowLength, int pageRowLength, int pageLength) {
            final Object page = newArray(array, pageLength);
            for (int row = 0; row < PAGE_ROWS; row++) {
                System.arraycopy(array, start + row * rowLength, page, row * pageRowLength, pageRowLength);
            }
            return page;
        }

        // Plain loops: page rows are only 8 to 16 elements long, too short for Arrays.equals() to pay off
        private static boolean equalRows(Object array, int start, int rowLength, Object page, int pageRowLength) {
            if (array instanceof short[] values) {
                final short[] pageValues = (short[]) page;
                for (int row = 0, index = 0; row < PAGE_ROWS; row++) {
                    for (int from = start + row * rowLength, end = from + pageRowLength; from < end; from++, index++) {
                        if (values[from] != pageValues[index]) {
                            return false;
                        }
                    }
                }
            } else if (array instanceof byte[] values) {
                final byte[] pageValues = (byte[]) page;
                for (int row = 0, index = 0; row < PAGE_ROWS; row++) {
                    for (int from = start + row * rowLength, end = from + pageRowLength; from < end; from++, index++) {
                        if (values[from] != pageValues[index]) {
                            return false;
                        }
                    }
                }
            } else {
                final int[] values = (int[]) array, pageValues = (int[]) page;
                for (int row = 0, index = 0; row < PAGE_ROWS; row++) {
                    for (int from = start + row * rowLength, end = from + pageRowLength; from < end; from++, index++) {
                        if (values[from] != pageValues[index]) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private static long uniformValue(Object array, int start, int rowLength, int pageRowLength) {
            if (array instanceof short[] values) {
                final short value = values[start];
                for (int row = 0; row < PAGE_ROWS; row++) {
                    for (int index = start + row * rowLength, end = index + pageRowLength; index < end; index++) {
                        if (values[index] != value) {
                            return NOT_UNIFORM;
                        }
                    }
                }
                return value;
            } else if (array instanceof byte[] values) {
                final byte value = values[start];
                for (int row = 0; row < PAGE_ROWS; row++) {
                    for (int index = start + row * rowLength, end = index + pageRowLength; index < end; index++) {
                        if (values[index] != value) {
                            return NOT_UNIFORM;
                        }
                    }
                }
                return value;
            } else {
                final int[] values = (int[]) array;
                final int value = values[start];
                for (int row = 0; row < PAGE_ROWS; row++) {
                    for (int index = start + row * rowLength, end = index + pageRowLength; index < end; index++) {
                        if (values[index] != value) {
                            return NOT_UNIFORM;
                        }
                    }
                }
                return value;
            }
        }

        private static final int ROWS = 128, PAGE_ROWS = 16, PAGES_PER_ROW = ROWS / PAGE_ROWS;
        private static final long NOT_UNIFORM = Long.MIN_VALUE;
    }
}
