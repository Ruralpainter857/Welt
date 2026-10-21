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

/**
 * API native du fork Welt : interface Java avec la bibliothèque Rust {@code welt_core}.
 *
 * <p>Ce package contient la « glue » Java du portage Rust du cœur de WorldPainter :</p>
 * <ul>
 *   <li>{@link org.pepsoft.worldpainter.nativeapi.NativeLoader} : chargement paresseux et
 *       tolérant aux pannes de la bibliothèque native {@code welt_core} (via
 *       {@code System.loadLibrary} puis, en repli, extraction d'une ressource du classpath
 *       vers un fichier temporaire) ;</li>
 *   <li>{@link org.pepsoft.worldpainter.nativeapi.Native} : feature flags réversibles
 *       {@code wp.native.gen}, {@code wp.native.render} et {@code wp.native.export}
 *       permettant d'activer ou désactiver, par volet, l'accélération native.</li>
 * </ul>
 *
 * <p>Principes :</p>
 * <ul>
 *   <li>le chargement ne lève jamais d'exception : en cas d'échec, l'application
 *       retombe silencieusement sur le chemin Java existant ;</li>
 *   <li>les appels JNI sont concentrés dans les classes de liaison (voir
 *       {@code WpNative} et {@code TileView}, fournies par ailleurs) afin de limiter
 *       la surface native ;</li>
 *   <li>l'équivalence comportementale Java/Rust est garantie : le résultat produit
 *       par la bibliothèque native doit être identique à celui du code Java.</li>
 * </ul>
 */
package org.pepsoft.worldpainter.nativeapi;
