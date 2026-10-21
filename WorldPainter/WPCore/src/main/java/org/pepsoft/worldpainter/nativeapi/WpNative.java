package org.pepsoft.worldpainter.nativeapi;

/**
 * Déclarations natives du pont Welt vers le cœur Rust {@code welt_core}
 * ({@code welt-native/welt-core/src/jni.rs}, Phase 0 — charte §5, plan §0.5).
 *
 * <p><strong>Convention de retour</strong> (charte §5) :</p>
 * <ul>
 *     <li>Les fonctions natives sont <em>pures, synchrones et bulk par
 *     tuile/zone</em> — jamais d'appel pixel par pixel à travers JNI.</li>
 *     <li>Sur panic interne du Rust : {@code java.lang.RuntimeException} levée
 *     côté JVM et retour {@link #JNI_ERROR} ({@code -1}).</li>
 *     <li>Sinon, les « checks » retournent un code {@link #WELT_ERROR_OK}…
 *     {@code WELT_ERROR_INTERNAL} (sans exception) : l'appelant décide.</li>
 * </ul>
 *
 * <p><strong>Chargement</strong> : le bloc statique délègue à
 * {@code NativeLoader.ensureLoaded()} (extraction depuis le classpath
 * {@code natives/<os>-<arch>/} vers un répertoire temporaire, puis
 * {@code System.load}) — l'unique point de {@code System.load} autorisé
 * (charte §7). Ne toucher à cette classe QUE si le flag correspondant de
 * {@code Native} ({@code wp.native.gen}/{@code wp.native.render}/
 * {@code wp.native.export}) a confirmé que la lib est disponible : l'échec du
 * chargement déclenche {@code NoClassDefFoundError} à l'initialisation.</p>
 *
 * <p>Phase 0 (G1) : les méthodes sont {@code static} — l'ABI JNI reçoit
 * {@code jclass} comme second paramètre, conformément aux signatures Rust
 * {@code (env, jclass, ...)} de {@code jni.rs}.</p>
 */
public final class WpNative {
    /**
     * Version attendue du pont natif. Doit correspondre à la valeur retournée
     * par {@link #nativeVersion()} (miroir Rust : {@code jni::NATIVE_VERSION}).
     */
    public static final int EXPECTED_NATIVE_VERSION = 1;

    /**
     * Version du contrat de layout {@code WpTileView}
     * (miroir Rust : {@code abi::WP_TILE_VIEW_ABI_VERSION}).
     */
    public static final int WP_TILE_VIEW_ABI_VERSION = 1;

    /**
     * Code {@code WeltError::Ok} (miroir Rust : {@code welt-core/src/error.rs}).
     * Aucune erreur.
     */
    public static final int WELT_ERROR_OK = 0;

    /** Code {@code WeltError::NullPointer} : un pointeur obligatoire était {@code null}. */
    public static final int WELT_ERROR_NULL_POINTER = 1;

    /** Code {@code WeltError::IllegalArgument} : un argument viole un invariant documenté. */
    public static final int WELT_ERROR_ILLEGAL_ARGUMENT = 2;

    /** Code {@code WeltError::Internal} : erreur interne inattendue. */
    public static final int WELT_ERROR_INTERNAL = 3;

    /**
     * Retour d'un export après qu'une exception a été levée vers la JVM
     * (charte §5 : panic Rust → {@code RuntimeException}, retour {@code -1}).
     */
    public static final int JNI_ERROR = -1;

    static {
        // NativeLoader (livré par l'intégration A2) charge « welt_core » :
        // c'est l'unique point de System.load du fork (charte §7).
        NativeLoader.ensureLoaded();
    }

    /**
     * Version du pont natif Welt ({@code 1} en Phase 0).
     *
     * @return La version du pont natif, ≥ 1.
     * @throws java.lang.RuntimeException Sur panic interne du Rust (retour
     *     {@link #JNI_ERROR}).
     */
    public static native int nativeVersion();

    /**
     * Valide les invariants de tailles du contrat {@code WpTileView}
     * (plan §0.3) : chaque longueur doit être {@code 0} (donnée absente) ou un
     * multiple positif de {@code TILE_SIZE * TILE_SIZE} (16 384 — une tuile,
     * ou plusieurs tuiles pour un buffer plat de zone). Les longueurs
     * négatives sont rejetées.
     *
     * @param heightsLen Longueur du buffer de hauteurs (en entrées).
     * @param terrainLen Longueur du buffer de terrains (en entrées).
     * @param waterLen Longueur du buffer de niveaux d'eau (en entrées).
     * @return {@link #WELT_ERROR_OK} si les invariants tiennent, sinon
     *     {@link #WELT_ERROR_ILLEGAL_ARGUMENT} — sans exception.
     * @throws java.lang.RuntimeException Sur panic interne du Rust (retour
     *     {@link #JNI_ERROR}).
     */
    public static native int nativeTileViewCheck(int heightsLen, int terrainLen, int waterLen);

    private WpNative() {
        // Classe utilitaire, non instanciable.
    }
}
