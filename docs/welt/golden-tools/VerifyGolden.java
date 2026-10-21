import org.pepsoft.util.FastPerlin;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.util.RandomField;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * VerifyGolden — vérificateur standalone des fichiers d'or Welt (G7 « parity-harness »,
 * plan §0.6). Voir le protocole : {@code welt-native/golden/README.md}.
 *
 * <p>Relit un (ou plusieurs) fichier golden, re-exécute la même séquence d'appels via
 * les classes Java réelles, puis compare les <b>bits bruts</b> — tolérance AUCUNE
 * (charte §5). Classes de vérité :</p>
 * <ul>
 *   <li>{@code org.pepsoft.util.PerlinNoise} / {@code FastPerlin} / {@code RandomField},
 *       chargés depuis {@code docs/welt/reference/Utils-2.2.0.jar} (le jar embarque
 *       {@code noiselevels.txt}, requis par l'initialiseur statique de PerlinNoise) ;</li>
 *   <li>{@code java.util.Random} du JDK (LCG 48 bits).</li>
 * </ul>
 *
 * <p>Note : {@code UnsafeRandom} est package-private dans Utils 2.2.0 — non rejouable
 * depuis ce vérificateur (défaut de package). Son comportement est couvert
 * indirectement par les kinds {@code fastperlin*} (la permutation de FastPerlin est
 * initialisée par UnsafeRandom). Un golden dédié exigerait un dumper/verificateur
 * déclarant {@code package org.pepsoft.util;}</p>
 *
 * <p>Format tolérant : lignes vides et lignes '{@code #}' ignorées ; colonnes séparées
 * par tout blanc ; hex casse libre, préfixe {@code 0x} accepté, padding variable ;
 * encodage UTF-8, LF ou CRLF. Une donnée par ligne :
 * {@code kind seed coords... value} — {@code value} = bits bruts hexadécimaux
 * ({@code Float.floatToRawIntBits} = 8 chiffres, {@code Double.doubleToRawDoubleBits}
 * = 16 chiffres, int = 8 chiffres, long = 16 chiffres, booléen = 0/1) ou
 * {@code EX:<NomSimple>} si l'appel doit lever.</p>
 *
 * <p>Usage :</p>
 * <pre>
 *   java VerifyGolden <golden.txt> [<golden2.txt> ...]   exit 0 = tout concorde
 *                                                        exit 1 = divergences (détails affichés)
 *                                                        exit 2 = erreur d'usage / IO
 *   java VerifyGolden --emit-example [<out.txt>]          génère l'exemple de référence
 * </pre>
 *
 * <p>Compilation/exécution standalone (indépendant de Maven, charte §2) :</p>
 * <pre>
 *   javac --release 17 -cp docs/welt/reference/Utils-2.2.0.jar -d <build> docs/welt/golden-tools/VerifyGolden.java
 *   java -cp "<build>;docs/welt/reference/Utils-2.2.0.jar" VerifyGolden <golden.txt>
 * </pre>
 * <p>({@code ;} = séparateur de classpath Windows. Le stderr de javac/java sous pwsh
 * n'est pas une erreur : juger sur {@code $LASTEXITCODE}. Ne jamais commiter les
 * {@code .class} — compiler vers un répertoire hors du dépôt.)</p>
 */
public final class VerifyGolden {

    /** Compteur de lignes de données vérifiées avec succès. */
    private int checked;
    /** Compteur de divergences (bits ≠, exception inattendue, exception attendue absente, format invalide). */
    private int divergences;
    /** Nombre de données OK par kind. */
    private final Map<String, Integer> perKind = new TreeMap<>();
    /** Nombre de divergences par kind. */
    private final Map<String, Integer> kindFailures = new TreeMap<>();

    /** Cache des instances de bruit par seed (les appels sont indépendants). */
    private final Map<Long, PerlinNoise> perlinBySeed = new HashMap<>();
    private final Map<Long, FastPerlin> fastPerlinBySeed = new HashMap<>();
    private final Map<String, RandomField> randomFields = new HashMap<>();

    /** État du « run » RNG courant (suite contiguë de même kind/seed, index 0,1,2,...). */
    private Random randomRun;
    private String runKind = "";
    private long runSeed;
    private long runLastIndex = -1L;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        if ("--emit-example".equals(args[0])) {
            Path out = (args.length > 1)
                    ? Path.of(args[1])
                    : Path.of("docs", "welt", "golden-tools", "example-golden.txt");
            emitExample(out);
            System.out.println("Exemple de référence écrit : " + out.toAbsolutePath());
            return;
        }
        int exit = 0;
        final java.util.List<Path> files = new java.util.ArrayList<>();
        for (String arg : args) {
            files.add(Path.of(arg));
        }
        if (files.isEmpty()) {
            usage();
            System.exit(2);
        }
        for (Path file : files) {
            exit = Math.max(exit, new VerifyGolden().verifyFile(file));
        }
        System.exit(exit);
    }

    private static void usage() {
        System.err.println("Usage : java VerifyGolden <golden.txt> [<golden2.txt> ...]");
        System.err.println("        java VerifyGolden --emit-example [<out.txt>]");
        System.err.println("Les séquences java.util.Random, dont nextGaussian(), sont vérifiables avec ou sans JIT.");
        System.err.println("Protocole : welt-native/golden/README.md (tolérance AUCUNE — bit-exact).");
    }

    // ================================================================================
    // Vérification d'un fichier
    // ================================================================================

    private int verifyFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            System.err.println("ERREUR : fichier introuvable : " + path.toAbsolutePath());
            return 2;
        }
        final java.util.List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("ERREUR : lecture impossible de " + path + " : " + e);
            return 2;
        }
        System.out.println("Vérification Java de " + path.getFileName());
        for (int i = 0; i < lines.size(); i++) {
            final long lineNo = i + 1L;
            final String raw = lines.get(i).trim();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue; // en-têtes / commentaires — tolérés (protocole §3)
            }
            final String[] tok = raw.split("\\s+");
            try {
                checkLine(lineNo, tok);
            } catch (RuntimeException e) {
                diverge(lineNo, String.join(" ", tok), e.getMessage());
            }
        }
        System.out.println();
        System.out.printf(Locale.ROOT, "== %s : %d données vérifiées, %d divergence(s) ==%n",
                path.getFileName(), checked, divergences);
        for (Map.Entry<String, Integer> e : perKind.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-24s %6d OK   %3d KO%n",
                    e.getKey(), e.getValue(), kindFailures.getOrDefault(e.getKey(), 0));
        }
        System.out.println(divergences == 0
                ? "RÉSULTAT : OK (bit-exact)"
                : "RÉSULTAT : ÉCHEC — " + divergences + " divergence(s)");
        return divergences == 0 ? 0 : 1;
    }

    // ================================================================================
    // Rejeu d'une ligne (registre des kinds — protocole §4)
    // ================================================================================

    private void checkLine(long lineNo, String[] tok) {
        final String kind = tok[0].toLowerCase(Locale.ROOT);
        final long seed = Long.parseLong(tok[1]); // NumberFormatException → divergence
        // Legacy names emitted by G3's first dumpers. Kept readable so existing
        // checked-in goldens remain useful while the format is consolidated.
        if (kind.startsWith("nextint-bound-")) {
            need(tok, 4, kind);
            final long idx = Long.parseLong(tok[2]);
            final int bound = Integer.parseInt(kind.substring("nextint-bound-".length()));
            checkInt(lineNo, kind, ctx(tok, "i=" + idx + " bound=" + bound), tok[3],
                    () -> rngFor(kind, seed, idx).nextInt(bound));
            return;
        }
        if (kind.equals("wppattern")) {
            need(tok, 4, kind);
            checkInt(lineNo, kind, ctx(tok, "index=" + tok[2]), tok[3],
                    () -> new Random(seed).nextInt(256));
            return;
        }
        switch (kind) {
            case "perlin1d" -> {
                need(tok, 4, kind);
                final double x = Double.parseDouble(tok[2]);
                checkFloat(lineNo, kind, ctx(tok, "x=" + x), tok[3], () -> perlin(seed).getPerlinNoise(x));
            }
            case "perlin2d" -> {
                need(tok, 5, kind);
                final double x = Double.parseDouble(tok[2]);
                final double y = Double.parseDouble(tok[3]);
                checkFloat(lineNo, kind, ctx(tok, "x=" + x + " y=" + y), tok[4],
                        () -> perlin(seed).getPerlinNoise(x, y));
            }
            case "perlin3d" -> {
                need(tok, 6, kind);
                final double x = Double.parseDouble(tok[2]);
                final double y = Double.parseDouble(tok[3]);
                final double z = Double.parseDouble(tok[4]);
                checkFloat(lineNo, kind, ctx(tok, "x=" + x + " y=" + y + " z=" + z), tok[5],
                        () -> perlin(seed).getPerlinNoise(x, y, z));
            }
            case "promillage" -> {
                need(tok, 4, kind);
                if (seed != 0L) {
                    throw new IllegalArgumentException(
                            "promillage : la colonne seed doit valoir 0 (méthode statique)");
                }
                final float p = Float.parseFloat(tok[2]);
                checkFloat(lineNo, kind, ctx(tok, "p=" + p), tok[3],
                        () -> PerlinNoise.getLevelForPromillage(p));
            }
            case "fastperlin1d" -> {
                need(tok, 4, kind);
                final double x = Double.parseDouble(tok[2]);
                checkFloat(lineNo, kind, ctx(tok, "x=" + x), tok[3],
                        () -> fastPerlin(seed).sampleResult(x));
            }
            case "fastperlin2d" -> {
                need(tok, 5, kind);
                final double x = Double.parseDouble(tok[2]);
                final double y = Double.parseDouble(tok[3]);
                checkFloat(lineNo, kind, ctx(tok, "x=" + x + " y=" + y), tok[4],
                        () -> fastPerlin(seed).sampleResult(x, y));
            }
            case "fastperlin3d" -> {
                need(tok, 6, kind);
                final double x = Double.parseDouble(tok[2]);
                final double y = Double.parseDouble(tok[3]);
                final double z = Double.parseDouble(tok[4]);
                checkFloat(lineNo, kind, ctx(tok, "x=" + x + " y=" + y + " z=" + z), tok[5],
                        () -> fastPerlin(seed).sampleResult(x, y, z));
            }
            case "randomfield2d", "randomfield3d" -> {
                final boolean is3d = kind.endsWith("3d");
                need(tok, is3d ? 8 : 7, kind); // kind, seed, bits, scale, x, y[, z], value
                final int bits = Integer.parseInt(tok[2]);
                final double scale = Double.parseDouble(tok[3]);
                final int x = Integer.parseInt(tok[4]);
                final int y = Integer.parseInt(tok[5]);
                final int z = is3d ? Integer.parseInt(tok[6]) : 0;
                final String valueTok = tok[tok.length - 1];
                final String desc = ctx(tok, "bits=" + bits + " scale=" + scale + " x=" + x + " y=" + y
                        + (is3d ? " z=" + z : ""));
                checkInt(lineNo, kind, desc, valueTok,
                        () -> is3d ? randomField(bits, scale, seed).getValue(x, y, z)
                                   : randomField(bits, scale, seed).getValue(x, y));
            }
            case "rand_next_int", "nextint" -> {
                need(tok, 4, kind);
                final long idx = Long.parseLong(tok[2]);
                checkInt(lineNo, kind, ctx(tok, "i=" + idx), tok[3],
                        () -> rngFor(kind, seed, idx).nextInt());
            }
            case "rand_next_int_bound" -> {
                need(tok, 5, kind);
                final long idx = Long.parseLong(tok[2]);
                final int bound = Integer.parseInt(tok[3]);
                checkInt(lineNo, kind, ctx(tok, "i=" + idx + " bound=" + bound), tok[4],
                        () -> rngFor(kind, seed, idx).nextInt(bound));
            }
            case "rand_next_double", "nextdouble" -> {
                need(tok, 4, kind);
                final long idx = Long.parseLong(tok[2]);
                checkDouble(lineNo, kind, ctx(tok, "i=" + idx), tok[3],
                        () -> rngFor(kind, seed, idx).nextDouble());
            }
            case "rand_next_gaussian", "nextgaussian" -> {
                need(tok, 4, kind);
                final long idx = Long.parseLong(tok[2]);
                checkDouble(lineNo, kind, ctx(tok, "i=" + idx), tok[3],
                        () -> rngFor(kind, seed, idx).nextGaussian());
            }
            case "rand_next_long", "nextlong" -> {
                need(tok, 4, kind);
                final long idx = Long.parseLong(tok[2]);
                checkLong(lineNo, kind, ctx(tok, "i=" + idx), tok[3],
                        () -> rngFor(kind, seed, idx).nextLong());
            }
            case "rand_next_boolean", "nextboolean" -> {
                need(tok, 4, kind);
                final long idx = Long.parseLong(tok[2]);
                final String valueTok = tok[3];
                final String desc = ctx(tok, "i=" + idx);
                if (valueTok.startsWith("EX:")) {
                    checkException(lineNo, kind, desc, valueTok.substring(3),
                            () -> rngFor(kind, seed, idx).nextBoolean() ? 1 : 0);
                } else {
                    boolToken(valueTok); // validation stricte : 0 ou 1 uniquement
                    checkInt(lineNo, kind, desc, valueTok,
                            () -> rngFor(kind, seed, idx).nextBoolean() ? 1 : 0);
                }
            }
            case "rand_next_float", "nextfloat" -> {
                need(tok, 4, kind);
                final long idx = Long.parseLong(tok[2]);
                checkFloat(lineNo, kind, ctx(tok, "i=" + idx), tok[3],
                        () -> rngFor(kind, seed, idx).nextFloat());
            }
            default -> throw new IllegalArgumentException(
                    "kind inconnu (registre protocole §4 : " + KNOWN_KINDS + ") : " + kind);
        }
    }

    private static final String KNOWN_KINDS =
            "perlin1d/2d/3d, promillage, fastperlin1d/2d/3d, randomfield2d/3d, "
            + "rand_next_int, rand_next_int_bound, rand_next_double, rand_next_gaussian, "
            + "rand_next_long, rand_next_boolean, rand_next_float, legacy G3 names nextInt/nextInt-bound-*/wpPattern";

    /** Le fichier contient-il au moins une ligne de kind {@code rand_next_gaussian} ? */
    private static boolean containsNextGaussian(java.util.List<String> lines) {
        for (String raw : lines) {
            final String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.toLowerCase(Locale.ROOT).startsWith("rand_next_gaussian")) {
                return true;
            }
        }
        return false;
    }

    /** Nombre de colonnes exact attendu pour ce kind (sinon divergence de format). */
    private static void need(String[] tok, int n, String kind) {
        if (tok.length != n) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s : %d colonnes attendues, %d trouvées : [%s]", kind, n, tok.length,
                    String.join(" ", tok)));
        }
    }

    /** Contexte lisible pour les rapports de divergence. */
    private static String ctx(String[] tok, String coords) {
        return tok[0] + " seed=" + tok[1] + " " + coords;
    }

    // ================================================================================
    // Comparaisons bit-exact
    // ================================================================================

    private void checkFloat(long lineNo, String kind, String desc, String valueTok,
            Supplier<Float> call) {
        if (valueTok.startsWith("EX:")) {
            checkException(lineNo, kind, desc, valueTok.substring(3), call);
            return;
        }
        final int expected = hex32(valueTok);
        try {
            final float actual = call.get();
            final int actualBits = Float.floatToRawIntBits(actual);
            if (actualBits == expected) {
                ok(kind);
            } else {
                fail(lineNo, kind, desc, String.format(Locale.ROOT,
                        "attendu %08x (%s), obtenu %08x (%s), |delta|=%s",
                        expected, Float.intBitsToFloat(expected),
                        actualBits, actual,
                        Math.abs(actual - Float.intBitsToFloat(expected))));
            }
        } catch (RuntimeException e) {
            fail(lineNo, kind, desc, "exception inattendue : " + e);
        }
    }

    private void checkDouble(long lineNo, String kind, String desc, String valueTok,
            Supplier<Double> call) {
        if (valueTok.startsWith("EX:")) {
            checkException(lineNo, kind, desc, valueTok.substring(3), call);
            return;
        }
        final long expected = hex64(valueTok);
        try {
            final double actual = call.get();
            final long actualBits = Double.doubleToRawLongBits(actual);
            if (actualBits == expected) {
                ok(kind);
            } else {
                fail(lineNo, kind, desc, String.format(Locale.ROOT,
                        "attendu %016x (%s), obtenu %016x (%s), |delta|=%s",
                        expected, Double.longBitsToDouble(expected),
                        actualBits, actual,
                        Math.abs(actual - Double.longBitsToDouble(expected))));
            }
        } catch (RuntimeException e) {
            fail(lineNo, kind, desc, "exception inattendue : " + e);
        }
    }

    private void checkInt(long lineNo, String kind, String desc, String valueTok,
            Supplier<Integer> call) {
        if (valueTok.startsWith("EX:")) {
            checkException(lineNo, kind, desc, valueTok.substring(3), call);
            return;
        }
        final int expected = hex32(valueTok);
        try {
            final int actual = call.get();
            if (actual == expected) {
                ok(kind);
            } else {
                fail(lineNo, kind, desc, String.format(Locale.ROOT,
                        "attendu %08x (%d), obtenu %08x (%d)", expected, expected, actual, actual));
            }
        } catch (RuntimeException e) {
            fail(lineNo, kind, desc, "exception inattendue : " + e);
        }
    }

    private void checkLong(long lineNo, String kind, String desc, String valueTok,
            Supplier<Long> call) {
        if (valueTok.startsWith("EX:")) {
            checkException(lineNo, kind, desc, valueTok.substring(3), call);
            return;
        }
        final long expected = hex64(valueTok);
        try {
            final long actual = call.get();
            if (actual == expected) {
                ok(kind);
            } else {
                fail(lineNo, kind, desc, String.format(Locale.ROOT,
                        "attendu %016x (%d), obtenu %016x (%d)", expected, expected, actual, actual));
            }
        } catch (RuntimeException e) {
            fail(lineNo, kind, desc, "exception inattendue : " + e);
        }
    }

    /** {@code EX:<NomSimple>} : l'appel DOIT lever une exception portant ce nom simple. */
    private void checkException(long lineNo, String kind, String desc, String expectedSimpleName,
            Supplier<?> call) {
        try {
            final Object got = call.get();
            fail(lineNo, kind, desc, "exception " + expectedSimpleName
                    + " attendue, valeur obtenue : " + got);
        } catch (RuntimeException e) {
            if (e.getClass().getSimpleName().equals(expectedSimpleName)) {
                ok(kind);
            } else {
                fail(lineNo, kind, desc, "exception " + expectedSimpleName
                        + " attendue, obtenue : " + e.getClass().getSimpleName());
            }
        }
    }

    // ================================================================================
    // Instances de référence (caches)
    // ================================================================================

    private PerlinNoise perlin(long seed) {
        return perlinBySeed.computeIfAbsent(seed, PerlinNoise::new);
    }

    private FastPerlin fastPerlin(long seed) {
        return fastPerlinBySeed.computeIfAbsent(seed, FastPerlin::new);
    }

    private RandomField randomField(int bits, double scale, long seed) {
        return randomFields.computeIfAbsent(bits + "/" + scale + "/" + seed,
                k -> new RandomField(bits, scale, seed));
    }

    /**
     * Sémantique de rejeu RNG (protocole §4) : un « run » est une suite contiguë de
     * lignes de même kind et même seed avec index 0, 1, 2, ... Tout changement de
     * kind/seed ou discontinuité d'index démarre un run frais (l'index doit alors
     * repartir de 0) — les runs doivent être contigus dans le fichier.
     */
    private Random rngFor(String kind, long seed, long index) {
        final boolean contigu = kind.equals(runKind) && seed == runSeed && index == runLastIndex + 1L;
        if (randomRun == null || !contigu) {
            if (index != 0L) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "run RNG non contigu : index=%d (attendu 0 pour un nouveau run, ou %d pour continuer [%s seed=%d])",
                        index, runLastIndex + 1L, runKind, runSeed));
            }
            randomRun = new Random(seed);
            runKind = kind;
            runSeed = seed;
        }
        runLastIndex = index;
        return randomRun;
    }

    // ================================================================================
    // Analyse des tokens
    // ================================================================================

    /** Hex 32 bits → bits bruts (tolère 0x, casse, padding 1..8 chiffres). */
    private static int hex32(String tok) {
        final String h = strip0x(tok);
        if (h.length() < 1 || h.length() > 8) {
            throw new NumberFormatException("hex 32 bits invalide (1 à 8 chiffres) : " + tok);
        }
        return (int) Long.parseLong(h, 16);
    }

    /** Hex 64 bits → bits bruts (tolère 0x, casse, padding 1..16 chiffres). */
    private static long hex64(String tok) {
        final String h = strip0x(tok);
        if (h.length() < 1 || h.length() > 16) {
            throw new NumberFormatException("hex 64 bits invalide (1 à 16 chiffres) : " + tok);
        }
        return Long.parseUnsignedLong(h, 16);
    }

    /** Valeur booléenne encodée 0/1 (les autres tokens sont invalides). */
    private static int boolToken(String tok) {
        if ("0".equals(tok) || "1".equals(tok)) {
            return Integer.parseInt(tok);
        }
        throw new NumberFormatException("valeur booléenne attendue : 0 ou 1, obtenu " + tok);
    }

    private static String strip0x(String tok) {
        final String t = tok.toLowerCase(Locale.ROOT);
        return t.startsWith("0x") ? t.substring(2) : t;
    }

    // ================================================================================
    // Comptabilité / rapport
    // ================================================================================

    private void ok(String kind) {
        checked++;
        perKind.merge(kind, 1, Integer::sum);
    }

    private void fail(long lineNo, String kind, String desc, String detail) {
        divergences++;
        kindFailures.merge(kind, 1, Integer::sum);
        System.out.printf(Locale.ROOT, "DIVERGENCE ligne %d [%s] : %s%n", lineNo, desc, detail);
    }

    private void diverge(long lineNo, String rawLine, String message) {
        divergences++;
        System.out.printf(Locale.ROOT, "DIVERGENCE ligne %d [%s] : %s%n", lineNo, rawLine, message);
    }

    // ================================================================================
    // Génération de l'exemple de référence
    // ================================================================================

    /**
     * Écrit l'exemple de référence (mission G7) : PerlinNoise 2D seed 0 sur grille 5×5
     * (x, y ∈ {0, 0.5, 1, 1.5, 2} — demi-pas : le bruit s'annule aux nœuds entiers, une
     * grille entière ne serait pas discriminante) + java.util.Random seed 0,
     * 20 × nextInt(). Le fichier produit DOIT passer la vérification de ce même outil
     * (aller-retour complet).
     */
    private static void emitExample(Path out) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("# golden Welt v1 — exemple de référence (G7, docs/welt/golden-tools/example-golden.txt)\n");
        sb.append("# Format : kind seed coords... value — value = bits bruts hexadécimaux\n");
        sb.append("# (Float.floatToRawIntBits / Double.doubleToRawDoubleBits / Integer.toHexString)\n");
        sb.append("# Protocole : welt-native/golden/README.md — tolérance AUCUNE (charte §5)\n");
        sb.append("# Contenu : PerlinNoise 2D seed 0, grille 5x5 (x,y dans {0.0, 0.5, 1.0, 1.5, 2.0})\n");
        sb.append("#           + java.util.Random seed 0, 20 x nextInt()\n");
        sb.append("# Classes de vérité : org.pepsoft.util.PerlinNoise (Utils-2.2.0.jar), java.util.Random (JDK)\n");
        sb.append("# Revérifier : java -cp \"<build>;docs/welt/reference/Utils-2.2.0.jar\" VerifyGolden <ce-fichier>\n");
        final PerlinNoise noise = new PerlinNoise(0L);
        for (int j = 0; j < 5; j++) {
            for (int i = 0; i < 5; i++) {
                final double x = i * 0.5;
                final double y = j * 0.5;
                final float v = noise.getPerlinNoise(x, y);
                sb.append(String.format(Locale.ROOT, "perlin2d 0 %s %s %08x%n",
                        Double.toString(x), Double.toString(y),
                        Float.floatToRawIntBits(v)));
            }
        }
        final Random r = new Random(0L);
        for (int i = 0; i < 20; i++) {
            sb.append(String.format(Locale.ROOT, "rand_next_int 0 %d %08x%n", i, r.nextInt()));
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
