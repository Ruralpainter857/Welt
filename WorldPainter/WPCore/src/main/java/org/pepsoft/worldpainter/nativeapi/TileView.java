package org.pepsoft.worldpainter.nativeapi;

import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Layer;

import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Facade Java <strong>read-only</strong> du contrat C-compatible
 * {@code WpTileView} (Rust : {@code welt-native/welt-core/src/abi/mod.rs},
 * plan §0.3). Une instance capture l'état d'<em>une</em> tuile 128×128 sous la
 * forme exacte que les exports JNI marshalleront vers la structure native :
 * hauteurs brutes en point fixe /256, ordinaux {@link
 * org.pepsoft.worldpainter.Terrain}, niveaux d'eau bruts, couches bit en mots
 * 64 bits (layout {@link java.util.BitSet}) et couches byte (nibbles packées
 * comme dans {@code Tile}).
 *
 * <p><strong>Chemin canonique de construction</strong> (plan §1.2) : à partir
 * d'un snapshot de dimension — {@code dimension.getSnapshot()} puis les tuiles
 * du {@code DimensionSnapshot} — ou directement d'une {@link Tile} via
 * {@link #of(Tile)}. Les tuiles « tall » (gamme de hauteurs > 256, buffers
 * {@code int[]}/{@code short[]} Java) ne sont PAS représentables en v1 :
 * {@link #of(Tile)} les rejette ; l'extension passera par une incrément de
 * version du contrat ABI.</p>
 *
 * <p><strong>Immutabilité</strong> : toutes les données sont copiées à la
 * construction (jamais de rétention des buffers copy-on-write de l'UndoManager,
 * plan §0.3) ; les buffers internes ne fuient jamais par référence. Cette
 * classe est thread-safe par construction (état entièrement {@code final}).</p>
 *
 * <p><strong>La facade n'appelle PAS la lib native</strong> : la validation est
 * dupliquée en pur Java (miroir de {@code WpNative.nativeTileViewCheck}) pour
 * rester utilisable quand le flag {@code wp.native.*} est éteint (charte §5 :
 * fallback silencieux). Le marshalling effectif tableaux Java → {@code
 * WpTileView} appartient aux exports (jni.rs), pas à cette classe.</p>
 *
 * <p>La construction générique {@link #of(Tile)} passe par les getters publics
 * de {@code Tile}. Pour le chemin optimisé depuis un snapshot immuable, utiliser
 * {@link org.pepsoft.worldpainter.TileViewSnapshotAdapter#of} : il assemble la
 * vue depuis les buffers en une passe, puis {@link #ofArrays} en fait une copie
 * défensive.</p>
 */
public final class TileView {
    /** Côté d'une tuile, en pixels (miroir de {@code Constants.TILE_SIZE}). */
    public static final int TILE_SIZE = 128;

    /** Décalage d'indexation intra-tuile (miroir de {@code Constants.TILE_SIZE_BITS}). */
    public static final int TILE_SIZE_BITS = 7;

    /** Masque intra-tuile (miroir de {@code Constants.TILE_SIZE_MASK}). */
    public static final int TILE_SIZE_MASK = TILE_SIZE - 1;

    /** Nombre de pixels par tuile : 128 × 128 = 16 384. */
    public static final int TILE_PIXELS = TILE_SIZE * TILE_SIZE;

    /** Mots 64 bits d'une couche bit par pixel : 16 384 bits / 64 = 256. */
    public static final int BIT_LAYER_WORDS = TILE_PIXELS / Long.SIZE;

    /** Cellules d'une couche bit par chunk 16×16 : 8 × 8 = 64. */
    public static final int BIT_PER_CHUNK_LAYER_CELLS = (TILE_SIZE / 16) * (TILE_SIZE / 16);

    /** Mots 64 bits d'une couche bit par chunk : 64 bits / 64 = 1. */
    public static final int BIT_PER_CHUNK_LAYER_WORDS = BIT_PER_CHUNK_LAYER_CELLS / Long.SIZE;

    /** Octets d'une couche nibble : 16 384 pixels / 2 = 8 192. */
    public static final int NIBBLE_LAYER_BYTES = TILE_PIXELS / 2;

    private final int x;
    private final int y;
    private final int minHeight;
    private final int maxHeight;
    private final short[] heights;   // null => absentes
    private final byte[] terrain;    // null => absent
    private final byte[] water;      // null => absent
    private final Map<Layer, long[]> bitLayerWords;
    private final Map<Layer, byte[]> byteLayerData;

    private TileView(int x, int y, int minHeight, int maxHeight,
                     short[] heights, byte[] terrain, byte[] water,
                     Map<Layer, long[]> bitLayerWords, Map<Layer, byte[]> byteLayerData) {
        this.x = x;
        this.y = y;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.heights = heights;
        this.terrain = terrain;
        this.water = water;
        this.bitLayerWords = Collections.unmodifiableMap(bitLayerWords);
        this.byteLayerData = Collections.unmodifiableMap(byteLayerData);
    }

    /**
     * Construit une vue read-only d'une tuile en copiant ses données via les
     * getters publics pixel par pixel de {@link Tile}. C'est le chemin Phase 0,
     * <em>sûr mais non optimisé</em> (≈ 16 384 × 3 appels) : il respecte la
     * frontière de lecture existante (synchronisation par tuile, buffers
     * copy-on-write jamais exposés). Le chemin bulk passera par
     * {@link #ofArrays} une fois la seam d'accès aux tableaux arbitrée par
     * l'orchestrateur (les zones {@code Tile} sont gelées en Phase 0).
     *
     * @param tile La tuile à capturer (ses données doivent être lisibles ;
     *             typiquement issue d'un {@code Dimension.getSnapshot()}).
     * @return Une vue read-only indépendante de l'état futur de la tuile.
     * @throws IllegalArgumentException Si la tuile est « tall » (gamme de
     *     hauteurs > 256 — même règle que {@code Tile}), non représentable
     *     en v1.
     * @throws NullPointerException Si {@code tile} est {@code null}.
     */
    public static TileView of(Tile tile) {
        Objects.requireNonNull(tile, "tile");
        if (tile instanceof org.pepsoft.worldpainter.TileSnapshot) {
            return org.pepsoft.worldpainter.TileViewSnapshotAdapter.of(
                (org.pepsoft.worldpainter.TileSnapshot) tile);
        }
        final int minHeight = tile.getMinHeight();
        final int maxHeight = tile.getMaxHeight();
        // Même règle que le constructeur de Tile : (maxHeight - minHeight) > 256 => tall.
        if (maxHeight - minHeight > 256) {
            throw new IllegalArgumentException("Tall tiles are not representable by TileView v1 (height range "
                + (maxHeight - minHeight) + " > 256, tile " + tile.getX() + "," + tile.getY() + ")");
        }
        final short[] heights = new short[TILE_PIXELS];
        final byte[] terrain = new byte[TILE_PIXELS];
        final byte[] water = new byte[TILE_PIXELS];
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                final int index = px | (py << TILE_SIZE_BITS);
                heights[index] = (short) tile.getRawHeight(px, py);
                terrain[index] = (byte) tile.getTerrain(px, py).ordinal();
                water[index] = (byte) (tile.getWaterLevel(px, py) - minHeight);
            }
        }
        final Map<Layer, long[]> bitLayers = new LinkedHashMap<>();
        final Map<Layer, byte[]> byteLayers = new LinkedHashMap<>();
        for (final Layer layer: tile.getLayers()) {
            switch (layer.getDataSize()) {
                case BIT:
                {
                    final long[] words = new long[BIT_LAYER_WORDS];
                    for (int py = 0; py < TILE_SIZE; py++) {
                        for (int px = 0; px < TILE_SIZE; px++) {
                            if (tile.getBitLayerValue(layer, px, py)) {
                                final int index = px | (py << TILE_SIZE_BITS);
                                words[index >>> 6] |= 1L << index;
                            }
                        }
                    }
                    bitLayers.put(layer, words);
                    break;
                }
                case BIT_PER_CHUNK:
                {
                    final long[] words = new long[BIT_PER_CHUNK_LAYER_WORDS];
                    for (int cy = 0; cy < TILE_SIZE / 16; cy++) {
                        for (int cx = 0; cx < TILE_SIZE / 16; cx++) {
                            // getBitLayerValue interprète (x, y) en coordonnées pixel :
                            // (cx << 4, cy << 4) désigne le chunk (cx, cy).
                            if (tile.getBitLayerValue(layer, cx << 4, cy << 4)) {
                                final int cell = cx + cy * (TILE_SIZE / 16);
                                words[cell >>> 6] |= 1L << cell;
                            }
                        }
                    }
                    bitLayers.put(layer, words);
                    break;
                }
                case NIBBLE:
                {
                    final byte[] data = new byte[NIBBLE_LAYER_BYTES];
                    for (int py = 0; py < TILE_SIZE; py++) {
                        for (int px = 0; px < TILE_SIZE; px++) {
                            final int index = px | (py << TILE_SIZE_BITS);
                            final int value = tile.getLayerValue(layer, px, py) & 0x0F;
                            if ((index & 1) == 0) {
                                data[index >>> 1] = (byte) value;
                            } else {
                                data[index >>> 1] |= (byte) (value << 4);
                            }
                        }
                    }
                    byteLayers.put(layer, data);
                    break;
                }
                case BYTE:
                {
                    final byte[] data = new byte[TILE_PIXELS];
                    for (int py = 0; py < TILE_SIZE; py++) {
                        for (int px = 0; px < TILE_SIZE; px++) {
                            data[px | (py << TILE_SIZE_BITS)] = (byte) tile.getLayerValue(layer, px, py);
                        }
                    }
                    byteLayers.put(layer, data);
                    break;
                }
                default:
                    // DataSize.NONE : pas de buffer de données, ignoré.
                    break;
            }
        }
        return new TileView(tile.getX(), tile.getY(), minHeight, maxHeight, heights, terrain, water, bitLayers, byteLayers);
    }

    /**
     * Construit une vue read-only depuis les tableaux bruts d'une tuile
     * (chemin bulk, pour l'intégration snapshot : code du package
     * {@code org.pepsoft.worldpainter} ayant accès aux buffers
     * {@code protected} de {@code Tile} — {@code Dimension.getSnapshot()},
     * plan §1.2). Tous les buffers sont <strong>copiés défensivement</strong> :
     * la vue ne retient jamais les buffers copy-on-write de l'UndoManager.
     *
     * @param x Coordonnée X de la tuile ({@code Tile.getX()}).
     * @param y Coordonnée Y de la tuile ({@code Tile.getY()}).
     * @param minHeight Hauteur minimale ({@code Tile.getMinHeight()}).
     * @param maxHeight Hauteur maximale ({@code Tile.getMaxHeight()}).
     * @param heights Hauteurs brutes en point fixe /256, zéro-based —
     *                exactement {@link #TILE_PIXELS} entrées, ou {@code null}
     *                (absentes).
     * @param terrain Ordinaux {@code Terrain.values()} — exactement
     *                {@link #TILE_PIXELS} entrées, ou {@code null}.
     * @param water Niveaux d'eau bruts, zéro-based — exactement
     *              {@link #TILE_PIXELS} entrées, ou {@code null}.
     * @param bitLayerData Couches bit ({@code Tile.bitLayerData}) —
     *                     {@code null} autorisé (aucune couche).
     * @param layerData Couches byte ({@code Tile.layerData}) — {@code null}
     *                  autorisé (aucune couche).
     * @return Une vue read-only validée.
     * @throws IllegalArgumentException Si une longueur ou un {@code DataSize}
     *     ne respecte pas le contrat {@code WpTileView}, ou si
     *     {@code maxHeight < minHeight}.
     */
    public static TileView ofArrays(int x, int y, int minHeight, int maxHeight,
                                    short[] heights, byte[] terrain, byte[] water,
                                    Map<Layer, BitSet> bitLayerData, Map<Layer, byte[]> layerData) {
        if (maxHeight < minHeight) {
            throw new IllegalArgumentException("maxHeight " + maxHeight + " < minHeight " + minHeight);
        }
        if ((heights != null) && (heights.length != TILE_PIXELS)) {
            throw new IllegalArgumentException("heights length " + heights.length + " != " + TILE_PIXELS);
        }
        if ((terrain != null) && (terrain.length != TILE_PIXELS)) {
            throw new IllegalArgumentException("terrain length " + terrain.length + " != " + TILE_PIXELS);
        }
        if ((water != null) && (water.length != TILE_PIXELS)) {
            throw new IllegalArgumentException("water length " + water.length + " != " + TILE_PIXELS);
        }
        final Map<Layer, long[]> bitLayers = new LinkedHashMap<>();
        if (bitLayerData != null) {
            for (final Map.Entry<Layer, BitSet> entry: bitLayerData.entrySet()) {
                final Layer layer = Objects.requireNonNull(entry.getKey(), "bit layer");
                final int wordCount;
                switch (layer.getDataSize()) {
                    case BIT: wordCount = BIT_LAYER_WORDS; break;
                    case BIT_PER_CHUNK: wordCount = BIT_PER_CHUNK_LAYER_WORDS; break;
                    default: throw new IllegalArgumentException(layer + ": not a bit sized layer");
                }
                bitLayers.put(layer, toPaddedWords(Objects.requireNonNull(entry.getValue(), "bitSet"), wordCount));
            }
        }
        final Map<Layer, byte[]> byteLayers = new LinkedHashMap<>();
        if (layerData != null) {
            for (final Map.Entry<Layer, byte[]> entry: layerData.entrySet()) {
                final Layer layer = Objects.requireNonNull(entry.getKey(), "byte layer");
                final byte[] buffer = Objects.requireNonNull(entry.getValue(), "layer buffer");
                final int expectedLength;
                switch (layer.getDataSize()) {
                    case BYTE: expectedLength = TILE_PIXELS; break;
                    case NIBBLE: expectedLength = NIBBLE_LAYER_BYTES; break;
                    default: throw new IllegalArgumentException(layer + ": not a byte sized layer");
                }
                if (buffer.length != expectedLength) {
                    throw new IllegalArgumentException(layer + ": buffer length " + buffer.length + " != " + expectedLength);
                }
                byteLayers.put(layer, buffer.clone());
            }
        }
        return new TileView(x, y, minHeight, maxHeight,
            (heights == null) ? null : heights.clone(),
            (terrain == null) ? null : terrain.clone(),
            (water == null) ? null : water.clone(),
            bitLayers, byteLayers);
    }

    /** Convertit un {@link BitSet} en mots 64 bits paddés au nombre exact requis. */
    private static long[] toPaddedWords(BitSet bitSet, int wordCount) {
        final long[] raw = bitSet.toLongArray(); // layout exact : bit n = bit (n % 64) du mot (n / 64)
        if (raw.length > wordCount) {
            throw new IllegalArgumentException("BitSet too large: " + raw.length + " words > " + wordCount);
        }
        final long[] words = new long[wordCount];
        System.arraycopy(raw, 0, words, 0, raw.length);
        return words;
    }

    /** Coordonnée X de la tuile dans la grille ({@code Tile.getX()}). */
    public int getX() {
        return x;
    }

    /** Coordonnée Y de la tuile dans la grille ({@code Tile.getY()}). */
    public int getY() {
        return y;
    }

    /** Hauteur minimale ({@code Tile.getMinHeight()}). */
    public int getMinHeight() {
        return minHeight;
    }

    /** Hauteur maximale ({@code Tile.getMaxHeight()}). */
    public int getMaxHeight() {
        return maxHeight;
    }

    /**
     * Longueur du buffer de hauteurs de la vue, en entrées : 0 (absent) ou
     * {@link #TILE_PIXELS}.
     */
    public int heightsLen() {
        return (heights == null) ? 0 : heights.length;
    }

    /** Longueur du buffer de terrains : 0 (absent) ou {@link #TILE_PIXELS}. */
    public int terrainLen() {
        return (terrain == null) ? 0 : terrain.length;
    }

    /** Longueur du buffer de niveaux d'eau : 0 (absent) ou {@link #TILE_PIXELS}. */
    public int waterLen() {
        return (water == null) ? 0 : water.length;
    }

    /**
     * Couches bit de la tuile, en mots 64 bits prêts pour l'ABI (non
     * modifiable ; les tableaux eux-mêmes ne sont jamais exposés).
     *
     * @return Map immuable layer → mots ({@link #BIT_LAYER_WORDS} par couche
     *     bit, {@link #BIT_PER_CHUNK_LAYER_WORDS} par couche bit par chunk).
     */
    public Map<Layer, long[]> getBitLayerWords() {
        return bitLayerWords;
    }

    /**
     * Couches byte de la tuile (non modifiable ; buffers copiés).
     *
     * @return Map immuable layer → données ({@link #TILE_PIXELS} octets par
     *     couche BYTE, {@link #NIBBLE_LAYER_BYTES} par couche NIBBLE).
     */
    public Map<Layer, byte[]> getByteLayerData() {
        return byteLayerData;
    }

    /** Hauteur brute en point fixe /256 (zéro-based) du pixel local {@code (x, y)}. */
    public int getRawHeight(int x, int y) {
        return heights[tileIndex(x, y)] & 0xFFFF;
    }

    /** Ordinal {@code Terrain.values()} du pixel local {@code (x, y)}. */
    public int getTerrainOrdinal(int x, int y) {
        return terrain[tileIndex(x, y)] & 0xFF;
    }

    /** Niveau d'eau brut (zéro-based) du pixel local {@code (x, y)}. */
    public int getRawWaterLevel(int x, int y) {
        return water[tileIndex(x, y)] & 0xFF;
    }

    /**
     * Miroir Java de {@code WpNative.nativeTileViewCheck} : une longueur de
     * buffer est valide si elle vaut {@code 0} (donnée absente) ou un multiple
     * positif de {@link #TILE_PIXELS}. Les longueurs négatives sont rejetées.
     */
    public static boolean isValidLength(int len) {
        return (len >= 0) && (len % TILE_PIXELS == 0);
    }

    /**
     * Vérifie que les trois longueurs principales respectent le contrat natif
     * (miroir direct de {@code WpNative.nativeTileViewCheck(heightsLen(),
     * terrainLen(), waterLen()) == WpNative.WELT_ERROR_OK}). Toujours vrai sur
     * une instance construite — existe pour la défense en profondeur aux
     * frontières JNI et pour les tests de parité (G7).
     */
    public boolean isValid() {
        return isValidLength(heightsLen()) && isValidLength(terrainLen()) && isValidLength(waterLen());
    }

    private static int tileIndex(int x, int y) {
        if (((x & ~TILE_SIZE_MASK) != 0) || ((y & ~TILE_SIZE_MASK) != 0)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") not within tile [0, " + TILE_SIZE + ")");
        }
        return x | (y << TILE_SIZE_BITS);
    }

    @Override
    public String toString() {
        return "TileView[x=" + x + ",y=" + y + ",minHeight=" + minHeight + ",maxHeight=" + maxHeight
            + ",heights=" + heightsLen() + ",terrain=" + terrainLen() + ",water=" + waterLen()
            + ",bitLayers=" + bitLayerWords.size() + ",byteLayers=" + byteLayerData.size() + "]";
    }
}
