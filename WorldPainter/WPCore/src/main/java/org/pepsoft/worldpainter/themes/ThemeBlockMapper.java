/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.themes;

import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.layers.Layer;

import java.util.Map;
import java.util.SortedMap;

/**
 * Seam « blocs » de {@link SimpleTheme} — le côté « export » (volet 3) du thème, extrait
 * en Phase 0 (plan {@code docs/plan-decoupage-java-rust.md} §3, seam « Blocs par
 * thème » ; charte {@code docs/welt/CHARTE-ORCHESTRATION.md} §3/§4.5).
 *
 * <p><strong>Rôle de la seam.</strong> Second volet de la résolution du point de
 * contention {@link SimpleTheme} : le volet 3 (export & I/O Minecraft, crate
 * {@code welt-export}) porte ce contrat, le volet 2 porte
 * {@link ThemeColourer}. L'interface <em>étend</em> {@link ThemeColourer} parce que la
 * contribution du thème à l'export est le même état peint (terrain sélectionné +
 * niveaux de couches) que celui qui devient couleur côté rendu : le terrain peint dans
 * la tuile est ce que l'export convertit en blocs. Hériter plutôt que dupliquer les
 * signatures reste additif et évite toute dérive de contrat.
 *
 * <p><strong>Réalité de ce codebase.</strong> {@link SimpleTheme} ne possède pas de
 * méthode {@code getBlock(...)} : à l'export, le thème n'est pas interrogé bloc par
 * bloc. {@code exporting.WorldPainterChunkFactory} lit le terrain que le thème a peint
 * dans la tuile (WorldPainterChunkFactory.java:180 et :252 —
 * {@code tile.getTerrain(xInTile, yInTile)}) et le convertit en matériaux via
 * {@code Terrain.getMaterial(platform, seed, ...)} (lignes :287, :292, :297 et :331
 * pour la sous-surface) ; les valeurs de couches peintes par le thème deviennent des
 * blocs via les {@code LayerExporter}s de {@code layers.*.exporters}. Le « côté blocs »
 * du thème se résume donc à :
 * <ol>
 * <li>la sélection de terrain partagée ({@link #getTerrain(int, int, int)},
 * {@link #apply(Tile, int, int)}) — héritée de {@link ThemeColourer} ;</li>
 * <li>la configuration qui décide quels blocs produisent ces terrains/couches :
 * {@link #getTerrainRanges()} (quelle plage → quel terrain → quels blocs),
 * {@link #getLayerMap()} (quelles couches seront exportées) et
 * {@link #getDiscreteValues()} (intensités/biomes écrits dans le NBT) — en lecture
 * héritée ;</li>
 * <li>les mutateurs de reconfiguration utilisés par l'importeur du volet 3 pour
 * préparer les thèmes des dimensions exportées (Nether/End) :
 * {@code importing.JavaMapImporter}.java:132-137 et :161-166 appellent
 * {@code getTerrainRanges()}, {@link #setTerrainRanges(SortedMap)} puis
 * {@link #setLayerMap(Map)} sur le thème du tile factory.</li>
 * </ol>
 *
 * <p><strong>Consommateurs (volet 3).</strong> Le pipeline d'export
 * ({@code exporting.WorldPainterChunkFactory}, {@code AbstractWorldExporter}, les
 * {@code layers.*.exporters}) consomme l'état peint ; l'import de cartes Minecraft
 * ({@code importing.JavaMapImporter}) reconfigure le thème via les deux mutateurs.
 * Le plan §4 rattache explicitement « le côté blocs de {@code SimpleTheme}
 * ({@code ThemeBlockMapper}) » au volet 3.
 *
 * <p><strong>Arbitrage des méthodes partagées.</strong> Les deux mutateurs exposés ici
 * sont aussi appelés par l'éditeur de thèmes Swing ({@code SimpleThemeEditor}:55/60,
 * {@code WorldFactory}:135) — mais ce scaffolding WPGUI reste Java sur le type concret
 * {@link SimpleTheme} (plan §1.4 : l'UI n'est pas portée). Le consommateur <em>porté</em>
 * majoritaire des mutateurs est l'importeur du volet 3 ; ils vivent donc sur cette
 * seam. Les méthodes de calcul et de lecture communes aux deux flux restent sur le seam
 * principal {@link ThemeColourer} (voir son arbitrage documenté).
 *
 * <p><strong>Contrat immuable (critique pour la parité bit-exact).</strong>
 * <ul>
 * <li>les mutateurs reconfigurent le mappage terrain/couches→blocs et reconstruisent
 * les caches internes ({@code initCaches()}/{@code updateTerrainRangesTable()}) : ils
 * ne doivent jamais changer la forme sérialisée (aucun champ d'instance ajouté ni
 * renommé par l'extraction) ;</li>
 * <li>après reconfiguration, les sorties de {@link #apply} / {@link #getTerrain} doivent
 * être reproductibles bit-exact — le portage natif du volet 3 réutilise le bruit
 * partagé (PerlinNoise/LCG) du socle ;</li>
 * <li>les implémentations ne sont pas tenues d'être thread-safe (comme
 * {@link SimpleTheme}) ;</li>
 * <li>cette interface n'est PAS {@code Serializable}, par design : la forme sérialisée
 * de {@link SimpleTheme} reste gouvernée par {@link Theme} + son
 * {@code serialVersionUID} déclaré (1L) et n'est pas modifiée par l'extraction.</li>
 * </ul>
 *
 * @author G8 "theme-seams" (fork Welt — Phase 0)
 * @see ThemeColourer
 * @see SimpleTheme
 */
public interface ThemeBlockMapper extends ThemeColourer {
    /**
     * Reconfigure les plages de terrains par hauteur — c'est-à-dire quels terrains
     * (donc quels blocs à l'export) le thème sélectionnera pour chaque coordonnée.
     * Remplace la carte existante et reconstruit les tables internes.
     *
     * <p>Contrat identique à {@code SimpleTheme.setTerrainRanges} (validations :
     * pas de {@code null}, pas de carte vide, pas de valeurs {@code null} ; la plage
     * la plus basse est ramenée sous {@link #getMinHeight()}). Consommateur porté :
     * {@code JavaMapImporter}:136/165 (thèmes Nether/End de l'export).
     *
     * @param terrainRanges la carte triée hauteur→terrain
     */
    void setTerrainRanges(SortedMap<Integer, Terrain> terrainRanges);

    /**
     * Reconfigure la carte filtre→couche du thème — c'est-à-dire quelles couches
     * seront peintes puis exportées en blocs par les {@code LayerExporter}s.
     * {@code null} ou une carte vide désactive les couches du thème.
     * Reconstruit les caches de niveaux.
     *
     * <p>Contrat identique à {@code SimpleTheme.setLayerMap}. Consommateur porté :
     * {@code JavaMapImporter}:137/166 (désactivation des couches pour les dimensions
     * Nether/End importées).
     *
     * @param layerMap la carte filtre→couche ({@code null} pour aucune couche)
     */
    void setLayerMap(Map<Filter, Layer> layerMap);
}
