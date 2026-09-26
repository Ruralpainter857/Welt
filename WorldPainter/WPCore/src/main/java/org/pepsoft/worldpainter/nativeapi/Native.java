/*
 * WorldPainter - une application de peinture de cartes pour Minecraft.
 * Copyright (C) 2025 le projet Welt et contributeurs de WorldPainter.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.pepsoft.worldpainter.nativeapi;

/**
 * Feature flags d'activation de l'accélération native Rust ({@code welt_core}),
 * par volet : génération, rendu et export.
 *
 * <p>Chaque volet est contrôlé par une propriété système distincte, lue à la
 * volée (pas de mise en cache) afin de permettre une <strong>bascule
 * réversible</strong> à l'exécution, y compris en cours de test :</p>
 * <ul>
 *   <li>{@code wp.native.gen} : génération de terrain ;</li>
 *   <li>{@code wp.native.render} : rendu 2D ;</li>
 *   <li>{@code wp.native.export} : export de monde.</li>
 * </ul>
 *
 * <p>La valeur par défaut de chaque flag est {@code false} : sans configuration
 * explicite, WorldPainter utilise le chemin Java historique, garantissant
 * l'équivalence comportementale par défaut. Si un flag est activé mais que la
 * bibliothèque native est absente (voir
 * {@link NativeLoader#isNativeAvailable()}), le repli est <em>silencieux</em>
 * vers le chemin Java : aucun flag ne provoque d'erreur.</p>
 *
 * <p>Les valeurs reconnues comme vraies sont {@code "true"} (insensible à la
 * casse) ; toute autre valeur, y compris une propriété absente, vaut
 * {@code false}.</p>
 */
public final class Native {
    /**
     * Clé de propriété système du volet « génération ».
     */
    public static final String GEN_KEY = "wp.native.gen";

    /**
     * Clé de propriété système du volet « rendu ».
     */
    public static final String RENDER_KEY = "wp.native.render";

    /**
     * Clé de propriété système du volet « export ».
     */
    public static final String EXPORT_KEY = "wp.native.export";

    private static final String DEFAULT_VALUE = "false";

    private Native() {
        // Classe utilitaire : pas d'instanciation.
        throw new AssertionError("Non instanciable");
    }

    /**
     * Indique si le volet « génération » doit utiliser l'accélération native.
     *
     * <p>Lit la propriété système {@code wp.native.gen} (défaut :
     * {@code false}). L'appelant doit également vérifier
     * {@link NativeLoader#isNativeAvailable()} : en cas de bibliothèque
     * absente, le repli est silencieux vers le chemin Java.</p>
     *
     * @return {@code true} si la génération native est activée
     */
    public static boolean isGenEnabled() {
        return Boolean.parseBoolean(System.getProperty(GEN_KEY, DEFAULT_VALUE));
    }

    /**
     * Indique si le volet « rendu » doit utiliser l'accélération native.
     *
     * <p>Lit la propriété système {@code wp.native.render} (défaut :
     * {@code false}). L'appelant doit également vérifier
     * {@link NativeLoader#isNativeAvailable()} : en cas de bibliothèque
     * absente, le repli est silencieux vers le chemin Java.</p>
     *
     * @return {@code true} si le rendu natif est activé
     */
    public static boolean isRenderEnabled() {
        return Boolean.parseBoolean(System.getProperty(RENDER_KEY, DEFAULT_VALUE));
    }

    /**
     * Indique si le volet « export » doit utiliser l'accélération native.
     *
     * <p>Lit la propriété système {@code wp.native.export} (défaut :
     * {@code false}). L'appelant doit également vérifier
     * {@link NativeLoader#isNativeAvailable()} : en cas de bibliothèque
     * absente, le repli est silencieux vers le chemin Java.</p>
     *
     * @return {@code true} si l'export natif est activé
     */
    public static boolean isExportEnabled() {
        return Boolean.parseBoolean(System.getProperty(EXPORT_KEY, DEFAULT_VALUE));
    }

    /**
     * Active ou désactive le volet « génération » (usage : tests et
     * diagnostics). Bascule réversible : positionne la propriété système
     * {@code wp.native.gen}.
     *
     * @param enabled {@code true} pour activer la génération native
     */
    public static void setGenEnabled(final boolean enabled) {
        System.setProperty(GEN_KEY, Boolean.toString(enabled));
    }

    /**
     * Active ou désactive le volet « rendu » (usage : tests et diagnostics).
     * Bascule réversible : positionne la propriété système
     * {@code wp.native.render}.
     *
     * @param enabled {@code true} pour activer le rendu natif
     */
    public static void setRenderEnabled(final boolean enabled) {
        System.setProperty(RENDER_KEY, Boolean.toString(enabled));
    }

    /**
     * Active ou désactive le volet « export » (usage : tests et diagnostics).
     * Bascule réversible : positionne la propriété système
     * {@code wp.native.export}.
     *
     * @param enabled {@code true} pour activer l'export natif
     */
    public static void setExportEnabled(final boolean enabled) {
        System.setProperty(EXPORT_KEY, Boolean.toString(enabled));
    }
}
