package org.pepsoft.worldpainter.nativeapi;

/**
 * Smoke test autonome Java → Rust du pont Welt (G1, Phase 0 — validation
 * bout-en-bout de la chaîne JNI, plan §0.3/§0.5).
 *
 * <p><strong>Indépendance</strong> : ce fichier ne dépend NI de l'arbre Maven,
 * NI de {@code WpNative.java} — il déclare SA PROPRE copie des méthodes
 * natives. Le résolveur JNI de la JVM construit le symbole à partir du nom
 * (package + classe + méthode) de la classe déclarante : la classe ci-dessous
 * s'appelle donc exactement {@code org.pepsoft.worldpainter.nativeapi.WpNative}
 * pour que {@code WpNative.nativeVersion()} résolve le symbole canonique
 * {@code Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeVersion} — celui
 * exporté par {@code welt-native/welt-core/src/jni.rs}. {@code System.load}
 * charge {@code welt_core.dll} par chemin absolu (l'unique point de load hors
 * {@code NativeLoader}, toléré car ce test est un outil autonome, pas du code
 * applicatif).</p>
 *
 * <p><strong>Compilation</strong> (JDK 21, cible 17) :</p>
 * <pre>  javac --release 17 -encoding UTF-8 -d <out> WpNativeSmokeTest.java</pre>
 * <p><strong>Exécution</strong> (classpath limité au répertoire de sortie) :</p>
 * <pre>  java -Dwelt.native.path=<chemin-vers-welt_core.dll> -cp <out> org.pepsoft.worldpainter.nativeapi.WpNativeSmokeTest</pre>
 * <p>Sortie attendue : {@code nativeVersion() = 1} puis {@code SMOKE TEST OK}.
 * Code retour 0 si tout est conforme, 1 sinon.</p>
 */
public class WpNativeSmokeTest {

    public static void main(String[] args) {
        int failures = 0;

        final int version = WpNative.nativeVersion();
        System.out.println("nativeVersion()                        = " + version + "   (attendu : 1)");
        if (version != 1) {
            failures++;
        }

        final int tilePixels = 128 * 128;
        int result = WpNative.nativeTileViewCheck(tilePixels, tilePixels, tilePixels);
        System.out.println("nativeTileViewCheck(16384,16384,16384) = " + result + "   (attendu : 0 = WELT_ERROR_OK)");
        if (result != 0) {
            failures++;
        }

        result = WpNative.nativeTileViewCheck(0, 0, 0);
        System.out.println("nativeTileViewCheck(0,0,0)             = " + result + "   (attendu : 0, donnees absentes)");
        if (result != 0) {
            failures++;
        }

        result = WpNative.nativeTileViewCheck(2 * tilePixels, tilePixels, 0);
        System.out.println("nativeTileViewCheck(32768,16384,0)     = " + result + "   (attendu : 0, buffer de zone)");
        if (result != 0) {
            failures++;
        }

        result = WpNative.nativeTileViewCheck(tilePixels, tilePixels + 1, 0);
        System.out.println("nativeTileViewCheck(16384,16385,0)     = " + result + "   (attendu : 2 = WELT_ERROR_ILLEGAL_ARGUMENT)");
        if (result != 2) {
            failures++;
        }

        result = WpNative.nativeTileViewCheck(-tilePixels, 0, 0);
        System.out.println("nativeTileViewCheck(-16384,0,0)        = " + result + "   (attendu : 2, longueur negative)");
        if (result != 2) {
            failures++;
        }

        if (failures == 0) {
            System.out.println("SMOKE TEST OK : pont Java->Rust operationnel (WpNativeSmokeTest)");
        } else {
            System.out.println("SMOKE TEST ECHEC : " + failures + " verification(s) en echec");
            System.exit(1);
        }
    }
}

/**
 * Copie autonome (dans ce fichier uniquement) de la déclaration native du pont :
 * le nom pleinement qualifié de cette classe détermine le symbole JNI résolu —
 * {@code Java_org_pepsoft_worldpainter_nativeapi_WpNative_nativeVersion} et
 * {@code ..._nativeTileViewCheck} — exactement les exports de
 * {@code welt-native/welt-core/src/jni.rs}.
 */
class WpNative {
    public static native int nativeVersion();

    public static native int nativeTileViewCheck(int heightsLen, int terrainLen, int waterLen);

    static {
        final String nativePath = System.getProperty("welt.native.path");
        if ((nativePath == null) || nativePath.isBlank()) {
            System.loadLibrary("welt_core");
        } else {
            System.load(java.nio.file.Path.of(nativePath).toAbsolutePath().toString());
        }
    }
}
