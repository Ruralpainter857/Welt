/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.themes;

import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Layer;

import java.util.Map;
import java.util.SortedMap;

/**
 * Seam "couleurs" de {@link SimpleTheme} — le côté « apparence » (volet 2, rendu)
 * du thème, extrait en Phase 0 (plan {@code docs/plan-decoupage-java-rust.md} §3,
 * seam « Blocs par thème » ; charte {@code docs/welt/CHARTE-ORCHESTRATION.md} §3/§4.5).
 *
 * <p><strong>Rôle de la seam.</strong> {@link SimpleTheme} est le seul vrai point de
 * contention inter-volets du portage Welt : le volet 2 (rendu / peinture interactive,
 * crate {@code welt-render}) et le volet 3 (export blocs, crate {@code welt-export})
 * portent chacun un côté de la classe. Cette interface fige le contrat « côté couleurs »
 * que le volet 2 consomme et remplace derrière son bridge natif, sans que le volet 3
 * n'ait à toucher au même fichier. Elle est purement additive : aucun consommateur
 * existant n'est modifié, {@link SimpleTheme} continue d'implémenter {@link Theme} et
 * d'être utilisé concrètement partout où il l'était.
 *
 * <p><strong>Réalité de ce codebase.</strong> Contrairement à une idée répandue,
 * {@link SimpleTheme} ne possède pas de surcharges {@code getColour(...)} : ce sont
 * {@code Terrain.getColour(long, int, int, float, int, Platform, ColourScheme)} et les
 * {@code LayerRenderer}s de {@code layers.renderers} qui calculent les couleurs RGB,
 * <em>à partir de l'état que le thème a peint dans la tuile</em>. La contribution du
 * thème au flux « couleurs » est donc :
 * <ol>
 * <li>la sélection du terrain par coordonnée — {@link #getTerrain(int, int, int)},
 * randomisée au {@code PerlinNoise} (c'est le calcul à haut trafic visé par le plan §1.3 :
 * « SimpleTheme calcule les couleurs avec du PerlinNoise !») ;</li>
 * <li>la peinture de cet état dans la tuile — {@link #apply(Tile, int, int)} écrit le
 * terrain (→ couleur du pixel via {@code TileRenderer.getPixelColour},
 * TileRenderer.java:374-384) et les niveaux de couches (→ rendu des
 * {@code LayerRenderer}s, TileRenderer.java:390 sqq.) ;</li>
 * <li>la configuration en lecture seule qui alimente ce calcul (plages de terrains,
 * drapeaux d'apparence, cartes de couches, valeurs discrètes, cadre de hauteurs,
 * graine).</li>
 * </ol>
 *
 * <p><strong>Consommateurs (volet 2).</strong> La population de tuiles
 * ({@code HeightMapTileFactory.createTile}:127 et {@code applyTheme}:151-152), le
 * re-apply interactif après chaque coup de pinceau/opération
 * ({@code Dimension.applyTheme}:817-820, consommé par {@code painting.DimensionPainter}:434,
 * {@code painting.TerrainPaint}:129/138/152, {@code operations.*}: CreateMountain:137,
 * Flatten:62/78/95, Height:67, RaiseMountain:62, Smooth:75), l'aperçu d'import
 * ({@code importing.HeightMapImporter}:180-191) et les éditeurs de thèmes WPGUI
 * ({@code themes.impl.simple.SimpleThemeEditor}:93/107/110/112 en lecture). Le volet 3
 * consomme le <em>même</em> état peint à l'export ({@code WorldPainterChunkFactory}:180/252
 * → {@code Terrain.getMaterial}:287/292/297/331) — d'où le partage documenté ci-dessous.
 *
 * <p><strong>Arbitrage des méthodes partagées.</strong> {@link #apply}, {@link #getTerrain},
 * les bornes de hauteurs, {@link #getSeed()}, {@link #getWaterHeight()} et la lecture de
 * configuration servent les deux flux (le terrain peint devient couleur côté volet 2 et
 * blocs/matériaux côté volet 3). Elles vivent dans cette interface — le seam principal —
 * car le consommateur majoritaire et le propriétaire du package {@code themes} est le
 * volet 2 (plan §4 : « themes + themes.impl.fancy — côté couleur seulement
 * ({@code ThemeColourer}) ») ; {@link ThemeBlockMapper} <em>étend</em> la présente
 * interface pour en hériter, ce qui reste additif et évite toute duplication de
 * signature.
 *
 * <p><strong>Contrat immuable (critique pour la parité bit-exact).</strong>
 * <ul>
 * <li>Les accesseurs de configuration ({@code get*}/{@code is*}) sont des lectures pures :
 * ils ne modifient jamais l'état de l'objet ni ne le remplacent ; les collections
 * retournées appartiennent à l'implémentation et ne doivent pas être mutées par
 * l'appelant sans passer par les mutateurs dédiés ;</li>
 * <li>{@link #getTerrain(int, int, int)} est une fonction de décision pure : mêmes
 * entrées (coordonnées, hauteur, graine, configuration) ⇒ même terrain, sans effet de
 * bord ni dépendance à un ordre d'appel. Le portage natif du volet 2 doit la reproduire
 * bit-exact (déterminisme du {@code PerlinNoise} G2, LCG {@code java.util.Random} G3) ;</li>
 * <li>{@link #apply(Tile, int, int)} est le point d'entrée « bulk-compatible » de la
 * peinture : il ne lit que la tuile et l'état du thème, et n'écrit que dans la tuile,
 * aux coordonnées demandées ;</li>
 * <li>les implémentations ne sont pas tenues d'être thread-safe (comme
 * {@link SimpleTheme}) : la coordination reste celle du modèle de threads existant ;</li>
 * <li>cette interface n'est PAS {@code Serializable}, par design : la forme sérialisée
 * de {@link SimpleTheme} reste gouvernée par {@link Theme} + son
 * {@code serialVersionUID} déclaré (1L) et n'est pas modifiée par l'extraction.</li>
 * </ul>
 *
 * @author G8 "theme-seams" (fork Welt — Phase 0)
 * @see ThemeBlockMapper
 * @see SimpleTheme
 */
public interface ThemeColourer {
    /**
     * Peint l'état du thème aux coordonnées données de la tuile : le terrain sélectionné
     * (celui que le rendu convertira en couleur via {@code Terrain.getColour}) et les
     * niveaux des couches dérivés des filtres (celui que les {@code LayerRenderer}s
     * rendront). Les coordonnées sont relatives à la tuile, pas absolues.
     *
     * <p>Contrat identique à {@link Theme#apply(Tile, int, int)} (même méthode, seam
     * volet 2). Consommateurs : {@code HeightMapTileFactory}:127/152,
     * {@code HeightMapImporter}:181/191, {@code Dimension.applyTheme} (opérations
     * interactives du volet 2).
     *
     * @param tile la tuile à peindre
     * @param x l'abscisse relative à la tuile
     * @param y l'ordonnée relative à la tuile
     */
    void apply(Tile tile, int x, int y);

    /**
     * Sélectionne le terrain pour une coordonnée et une hauteur — la fonction de
     * décision « couleur/bloc » du thème : bandes de plages ({@code terrainRangesTable}),
     * plages ({@link #isBeaches()}) autour de {@link #getWaterHeight()}, et
     * randomisation {@code PerlinNoise} ({@link #isRandomise()}) quand elle est
     * active. C'est le seul calcul à bruit du thème ; le portage Rust du volet 2 doit
     * le reproduire bit-exact.
     *
     * <p>L'appelant doit fournir une hauteur déjà ramenée dans
     * [{@link #getMinHeight()}, {@link #getMaxHeight()} - 1], comme le fait
     * {@link #apply(Tile, int, int)} (clamp avant l'appel). Historiquement
     * {@code protected} dans {@link SimpleTheme} ; élargie à {@code public} pour
     * implémenter cette seam (changement purement additif, sans effet sur le
     * comportement ni la sérialisation).
     *
     * @param x abscisse absolue du monde
     * @param y ordonnée absolue du monde
     * @param height la hauteur, déjà ramenée dans [{@link #getMinHeight()}, {@link #getMaxHeight()} - 1]
     * @return le terrain sélectionné (jamais {@code null} si les plages sont intactes ;
     *         {@link #apply} vérifie ce retour comme il le faisait avant)
     */
    Terrain getTerrain(int x, int y, int height);

    /**
     * La graine du bruit {@code PerlinNoise} qui randomise la sélection de terrain.
     * Déterminisme des deux flux (couleurs et blocs). Même signature que
     * {@link Theme#getSeed()}.
     *
     * @return la graine du thème
     */
    long getSeed();

    /**
     * Le niveau de l'eau : ancre des bandes de plage dans {@link #getTerrain} et
     * repère des couleurs d'eau/ressaut de blocs dans les deux flux. Même signature
     * que {@link Theme#getWaterHeight()}.
     *
     * @return la hauteur d'eau du thème
     */
    int getWaterHeight();

    /**
     * La borne inférieure valide du cadre de hauteurs du thème. Même signature que
     * {@link Theme#getMinHeight()}.
     *
     * @return la hauteur minimale
     */
    int getMinHeight();

    /**
     * La borne supérieure (exclusive) valide du cadre de hauteurs du thème. Même
     * signature que {@link Theme#getMaxHeight()}.
     *
     * @return la hauteur maximale
     */
    int getMaxHeight();

    /**
     * Indique si la sélection de terrain est randomisée au {@code PerlinNoise}
     * (drapeau d'apparence lu par {@link #getTerrain} et les éditeurs de thèmes du
     * volet 2).
     *
     * @return {@code true} si la randomisation est active
     */
    boolean isRandomise();

    /**
     * Indique si les plages de sable sont peintes autour du niveau de l'eau (drapeau
     * d'apparence lu par {@link #getTerrain} et les éditeurs de thèmes du volet 2).
     *
     * @return {@code true} si les plages sont actives
     */
    boolean isBeaches();

    /**
     * Les plages de terrains par hauteur : la configuration d'apparence qui décide
     * quel terrain — donc quelle couleur côté rendu, quels blocs côté export — reçoit
     * chaque coordonnée. Consommée en lecture par les éditeurs de thèmes (volet 2 :
     * {@code SimpleThemeEditor}:93) et par l'importeur d'export (volet 3 :
     * {@code JavaMapImporter}:133/162).
     *
     * @return la carte triée hauteur→terrain (propriété de l'implémentation ; ne pas
     *         muter sans passer par {@link ThemeBlockMapper#setTerrainRanges(SortedMap)})
     */
    SortedMap<Integer, Terrain> getTerrainRanges();

    /**
     * La carte filtre→couche : chaque filtre sélectionné par hauteur fournit le niveau
     * de couche peint par {@link #apply} — niveau ensuite rendu par les
     * {@code LayerRenderer}s (volet 2) et transformé en blocs par les
     * {@code LayerExporter}s (volet 3, p.ex. Frost→glace). Lue par les éditeurs de
     * thèmes ({@code SimpleThemeEditor}:112).
     *
     * @return la carte filtre→couche (peut être {@code null} ou vide ; propriété de
     *         l'implémentation)
     */
    Map<Filter, Layer> getLayerMap();

    /**
     * Les valeurs discrètes par couche : quand une couche y figure, son niveau peint
     * devient la valeur discrète si le filtre passe le seuil (sinon la valeur par
     * défaut de la couche) — calculé dans les caches de {@link SimpleTheme}
     * (initCaches). Ex. : la valeur {@code Biome} saisie dans les dialogues du volet 2
     * ({@code FloatingLayerDialog}:348, {@code TunnelLayerDialog}:640) devient le biome
     * écrit à l'export.
     *
     * @return la carte couche→valeur discrète (peut être {@code null} ; propriété de
     *         l'implémentation)
     */
    Map<Layer, Integer> getDiscreteValues();
}
