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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Chargeur de la bibliothèque native Rust {@code welt_core}.
 *
 * <p>Stratégie de chargement (dans l'ordre) :</p>
 * <ol>
 *   <li>{@link System#loadLibrary(String)} avec le nom {@code welt_core} (la
 *       bibliothèque doit alors être présente sur le chemin natif de la JVM,
 *       p. ex. via {@code java.library.path}) ;</li>
 *   <li>en cas d'échec ({@link UnsatisfiedLinkError}), extraction depuis le
 *       classpath de la ressource {@code natives/<os>-<arch>/<fichier>} vers un
 *       fichier temporaire sous {@code java.io.tmpdir}, puis
 *       {@link System#load(String)} de ce fichier.</li>
 * </ol>
 *
 * <p>Identifiants de plateforme supportés (dérivés de {@code os.name} et
 * {@code os.arch}) :</p>
 * <ul>
 *   <li>{@code windows-x86_64}, {@code windows-aarch64} :
 *       {@code welt_core.dll} ;</li>
 *   <li>{@code linux-x86_64}, {@code linux-aarch64} :
 *       {@code libwelt_core.so} ;</li>
 *   <li>{@code macos-x86_64}, {@code macos-aarch64} :
 *       {@code libwelt_core.dylib}.</li>
 * </ul>
 *
 * <p><strong>Tolérance aux pannes :</strong> aucune exception n'est jamais
 * propagée. En cas d'échec, l'erreur est journalisée via
 * {@link java.util.logging.Logger} (nom : {@code org.pepsoft.worldpainter.nativeapi})
 * et l'application retombe silencieusement sur le chemin Java pur. Le succès est
 * mémorisé dans un {@code boolean} statique {@code volatile} : les appels
 * ultérieurs à {@link #ensureLoaded()} retournent immédiatement.</p>
 *
 * <p>Cette classe ne fait pas partie d'un chemin critique (« hot path ») : la
 * synchronisation de {@link #ensureLoaded()} est sans impact sur les
 * performances.</p>
 */
public final class NativeLoader {
    /**
     * Logger du package, nom : {@code org.pepsoft.worldpainter.nativeapi}.
     */
    private static final Logger LOGGER = Logger.getLogger("org.pepsoft.worldpainter.nativeapi");

    /**
     * Nom de la bibliothèque native, sans préfixe ni extension.
     */
    private static final String LIB_NAME = "welt_core";

    /**
     * État de chargement : {@code true} une fois que la bibliothèque native a
     * été chargée avec succès. {@code volatile} car la lecture peut être faite
     * sans verrou par {@link #isNativeAvailable()} après initialisation.
     */
    private static volatile boolean loaded = false;

    private NativeLoader() {
        // Classe utilitaire : pas d'instanciation.
        throw new AssertionError("Non instanciable");
    }

    /**
     * Garantit que la bibliothèque native {@code welt_core} est chargée si
     * possible. Idempotente et thread-safe : si la bibliothèque est déjà
     * chargée, retour immédiat ; si un chargement a déjà échoué, une nouvelle
     * tentative a lieu (permet p. ex. de rattraper un classpath corrigé).
     *
     * <p>Ne lève jamais d'exception : toute erreur
     * ({@link UnsatisfiedLinkError}, {@link IOException}) est interceptée et
     * journalisée.</p>
     */
    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        try {
            System.loadLibrary(LIB_NAME);
            loaded = true;
            LOGGER.info(() -> "Bibliothèque native " + LIB_NAME + " chargée via java.library.path");
            return;
        } catch (final UnsatisfiedLinkError e) {
            LOGGER.log(Level.FINE, "System.loadLibrary(" + LIB_NAME + ") a échoué, tentative d'extraction depuis le classpath", e);
        }
        try {
            final String resourcePath = nativeResourcePath();
            if (resourcePath == null) {
                LOGGER.warning("Plateforme non supportée pour la bibliothèque native " + LIB_NAME + " ; chemin Java utilisé");
                return;
            }
            try (final InputStream in = NativeLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warning("Ressource native introuvable sur le classpath : " + resourcePath + " ; chemin Java utilisé");
                    return;
                }
                final Path tempFile = extractToTempFile(in);
                System.load(tempFile.toString());
                loaded = true;
                LOGGER.info(() -> "Bibliothèque native " + LIB_NAME + " chargée depuis " + tempFile);
            }
        } catch (final UnsatisfiedLinkError | IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Impossible de charger la bibliothèque native " + LIB_NAME + " ; chemin Java utilisé", e);
        }
    }

    /**
     * Indique si la bibliothèque native {@code welt_core} est disponible.
     *
     * <p>Appelle d'abord {@link #ensureLoaded()} puis retourne l'état
     * mémorisé. Ne lève jamais d'exception.</p>
     *
     * @return {@code true} si la bibliothèque native a été chargée avec succès,
     *         {@code false} sinon (dans ce cas le chemin Java pur doit être
     *         utilisé)
     */
    public static boolean isNativeAvailable() {
        ensureLoaded();
        return loaded;
    }

    /**
     * Calcule le chemin classpath de la bibliothèque native pour la plateforme
     * courante : {@code natives/<os>-<arch>/<fichier>}.
     *
     * @return le chemin de la ressource, ou {@code null} si la plateforme
     *         n'est pas supportée
     */
    private static String nativeResourcePath() {
        final String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        final String osArch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        final String os;
        final String fileName;
        if (osName.contains("windows")) {
            os = "windows";
            fileName = LIB_NAME + ".dll";
        } else if (osName.contains("linux")) {
            os = "linux";
            fileName = "lib" + LIB_NAME + ".so";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = "macos";
            fileName = "lib" + LIB_NAME + ".dylib";
        } else {
            return null;
        }
        final String arch;
        switch (osArch) {
            case "amd64":
            case "x86_64":
                arch = "x86_64";
                break;
            case "aarch64":
            case "arm64":
                arch = "aarch64";
                break;
            default:
                return null;
        }
        return "natives/" + os + "-" + arch + "/" + fileName;
    }

    /**
     * Copie le contenu de la ressource vers un fichier temporaire sous
     * {@code java.io.tmpdir}, avec le préfixe {@code welt_core-}.
     *
     * <p>Le fichier est marqué « supprimable à la sortie de la JVM »
     * ({@link File#deleteOnExit()}) ; le chargement via
     * {@link System#load(String)} verrouille le fichier sur certains OS
     * (Windows), ce qui interdirait une suppression immédiate.</p>
     *
     * @param in flux ouvert sur la ressource classpath ; non fermé par cette
     *           méthode (l'appelant gère le cycle de vie)
     * @return le chemin du fichier temporaire créé
     * @throws IOException en cas d'erreur d'écriture
     */
    private static Path extractToTempFile(final InputStream in) throws IOException {
        final Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path tempFile = Files.createTempFile(tempDir, LIB_NAME + "-", null);
        try (final OutputStream out = Files.newOutputStream(tempFile)) {
            in.transferTo(out);
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (final IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
