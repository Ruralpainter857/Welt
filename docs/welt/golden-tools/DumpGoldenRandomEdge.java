import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Welt G3 (rng-port) — dumper golden EDGE du LCG {@link java.util.Random}.
 *
 * <p>Complète {@link DumpGoldenRandom} (contrat principal) au-delà de son contenu imposé :
 * <ol>
 *   <li><b>Seeds extrêmes</b> {Long.MIN_VALUE, Long.MAX_VALUE, -1, 1} — séries complètes
 *       (nextInt, nextInt(1), nextInt(1000), nextLong, nextDouble, nextFloat, nextBoolean)
 *       + 5100 nextGaussian par seed (validation massive du log fdlibm du portage Rust).</li>
 *   <li><b>Chemin de rejet de nextInt(bound)</b> : la boucle
 *       {@code u - (r = u % bound) + m < 0} (avec wraparound i32) ne s'exécute que pour
 *       u ≥ 2^31 - bound + 1 — probabilité ~3e-7 pour bound=1000, ~9e-8 pour bound=3 :
 *       le golden principal ne la couvre DONC quasi certainement jamais. Ce dumper
 *       CONSTRUIT algébriquement (O(1), inversion du LCG mod 2^48, M impair donc inversible)
 *       des seeds dont le PREMIER next(31) vaut exactement une valeur rejetante,
 *       VALIDE le rejet par comptage des next() consommés (CountingRandom : >= 2),
 *       puis dump 100 nextInt(bound) pour ces seeds.
 *       (Un scan séquentiel de seeds serait inefficace : les seeds consécutifs
 *       produisent des next(31) très corrélés, pente ~288k/seed.)</li>
 *   <li><b>Vérifications vivantes</b> sur stderr : nextInt(0)/nextInt(-5) →
 *       IllegalArgumentException (le portage Rust paniquera de façon équivalente).</li>
 * </ol>
 *
 * <p>Lancer normalement ({@code java DumpGoldenRandomEdge <outfile>}) : contrairement à
 * une première hypothèse G3, aucun {@code -Xint} n'est nécessaire — le bytecode JDK 17+
 * de {@code Random.nextGaussian()} utilise {@code StrictMath.log}/{@code StrictMath.sqrt}
 * (fdlibm), déterministe en JIT comme en interprété ; seul {@code Math.log} est concerné
 * par l'intrinsic Intel LIBM (sans effet sur Random). Auto-vérifié au démarrage.
 *
 * <p>Format identique au golden principal : « kind seed index value » (ASCII).
 */
public class DumpGoldenRandomEdge {
    private static final long[] EDGE_SEEDS = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L};
    private static final int SMALL_COUNT = 100;
    private static final int FLOAT_BOOL_COUNT = 200;
    private static final int GAUSSIAN_COUNT = 5100;
    private static final int REJECT_SEEDS_PER_BOUND = 4;
    private static final int REJECT_SERIES_COUNT = 100;

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long SCRAMBLE = 0x5DEECE66DL;
    private static final long MASK48 = (1L << 48) - 1;

    /** Sous-classe exposant next() (protected dans java.util.Random). */
    static class ExposedRandom extends Random {
        ExposedRandom(long seed) { super(seed); }
        int nextBits(int bits) { return next(bits); }
    }

    /** Compte les appels next() du vrai nextInt(bound) → ≥2 ⟺ boucle de rejet exercée. */
    static class CountingRandom extends Random {
        int calls;
        CountingRandom(long seed) { super(seed); }
        @Override protected int next(int bits) { calls++; return super.next(bits); }
    }

    public static void main(String[] args) throws IOException {
        checkGaussianSemantics();
        sanityInlineLcg();
        checkIllegalBounds();

        // Zones de rejet (déduites de l'algo JDK, u = premier next(31) ∈ [0, 2^31)) :
        //  bound=1000 (m=999) : rejet ⟺ u - u%1000 + 999 ≥ 2^31 ⟺ u ≥ 2147483000 ;
        //  bound=3    (m=2)   : rejet ⟺ u ∈ {2147483646, 2147483647}.
        final int[] targetsB1000 = {2147483000, 2147483137, 2147483333, 2147483647};
        final int[] targetsB3 = {2147483646, 2147483647, 2147483646, 2147483647};
        final int[] offsetsB3 = {0x00000, 0x03333, 0x05555, 0x07B7B};
        final long[] rejectSeeds1000 = new long[REJECT_SEEDS_PER_BOUND];
        final long[] rejectSeeds3 = new long[REJECT_SEEDS_PER_BOUND];
        for (int i = 0; i < REJECT_SEEDS_PER_BOUND; i++) {
            rejectSeeds1000[i] = constructValidatedRejectionSeed(targetsB1000[i], 0x01234, 1000);
            rejectSeeds3[i] = constructValidatedRejectionSeed(targetsB3[i], offsetsB3[i], 3);
            System.err.println("DumpGoldenRandomEdge: rejection seed bound=1000 #" + i + ": " + rejectSeeds1000[i]);
            System.err.println("DumpGoldenRandomEdge: rejection seed bound=3 #" + i + ": " + rejectSeeds3[i]);
        }

        final Path outPath = (args.length > 0) ? Path.of(args[0]) : null;
        final Writer w = (outPath != null)
                ? Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)
                : new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        long total = 0;
        try {
            emitHeaders(w);
            for (long seed : EDGE_SEEDS) {
                Random r = new Random(seed);
                for (int i = 0; i < SMALL_COUNT; i++) {
                    write(w, "nextInt", seed, i, Integer.toHexString(r.nextInt()));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < SMALL_COUNT; i++) {
                    write(w, "nextInt-bound-1", seed, i, Integer.toHexString(r.nextInt(1)));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < SMALL_COUNT; i++) {
                    write(w, "nextInt-bound-1000", seed, i, Integer.toHexString(r.nextInt(1000)));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < SMALL_COUNT; i++) {
                    write(w, "nextLong", seed, i, Long.toHexString(r.nextLong()));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < SMALL_COUNT; i++) {
                    write(w, "nextDouble", seed, i, Long.toHexString(Double.doubleToRawLongBits(r.nextDouble())));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < GAUSSIAN_COUNT; i++) {
                    write(w, "nextGaussian", seed, i, Long.toHexString(Double.doubleToRawLongBits(r.nextGaussian())));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < FLOAT_BOOL_COUNT; i++) {
                    write(w, "nextFloat", seed, i, Integer.toHexString(Float.floatToRawIntBits(r.nextFloat())));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < FLOAT_BOOL_COUNT; i++) {
                    write(w, "nextBoolean", seed, i, r.nextBoolean() ? "1" : "0");
                    total++;
                }
            }
            for (long seed : rejectSeeds3) {
                final Random r = new Random(seed);
                for (int i = 0; i < REJECT_SERIES_COUNT; i++) {
                    write(w, "nextInt-bound-3", seed, i, Integer.toHexString(r.nextInt(3)));
                    total++;
                }
            }
            for (long seed : rejectSeeds1000) {
                final Random r = new Random(seed);
                for (int i = 0; i < REJECT_SERIES_COUNT; i++) {
                    write(w, "nextInt-bound-1000", seed, i, Integer.toHexString(r.nextInt(1000)));
                    total++;
                }
            }
            w.write("# samples " + total + "\n");
        } finally {
            w.flush();
            w.close();
        }
        System.err.println("DumpGoldenRandomEdge: wrote " + total + " samples"
                + (outPath != null ? " to " + outPath : " to stdout"));
    }

    /**
     * Premier next(31) d'un seed, sans allouer de Random (utile aussi pour la validation).
     * Reproduction directe de la spec java.util.Random :
     * seed48 = (seed ^ 0x5DEECE66D) & ((1<<48)-1) ; s = (s*0x5DEECE66D + 0xB) & mask ; u = s >>> (48-31).
     */
    static int firstNext31(long seed) {
        long s = (seed ^ SCRAMBLE) & MASK48;
        s = (s * MULTIPLIER + ADDEND) & MASK48;
        return (int) (s >>> 17);
    }

    /**
     * Construit un seed dont le PREMIER next(31) vaut exactement {@code target} (O(1), déterministe).
     * next(31) = ((seed48 * M + B) mod 2^48) >>> 17 avec seed48 = (seed ^ K) & mask48.
     * M est impair donc inversible mod 2^48 : pour tout s1 ∈ [target<<17, (target<<17)+2^17),
     * seed48 = (s1 - B) * M^{-1} mod 2^48, puis seed = seed48 ^ (K & mask48)
     * (les bits >= 48 de seed sont ignorés par setSeed). {@code offset} ∈ [0, 2^17) fixe s1.
     */
    private static long constructSeedForFirstNext31(int target, int offset) {
        // M^{-1} mod 2^48 par Newton-Hensel (convergence quadratique : 1,2,4,8,16,32,>=48 bits).
        long inv = MULTIPLIER;
        for (int i = 0; i < 6; i++) {
            final long r = (MULTIPLIER * inv) & MASK48;
            inv = (inv * ((2 - r) & MASK48)) & MASK48;
        }
        if (((MULTIPLIER * inv) & MASK48) != 1L) {
            throw new IllegalStateException("inversion mod 2^48 échouée");
        }
        final long s1 = ((long) target << 17) + offset;
        final long seed48 = ((s1 - ADDEND) * inv) & MASK48;
        return seed48 ^ (SCRAMBLE & MASK48);
    }

    /** Construit, vérifie (LCG inline + rejet réel constaté par CountingRandom) et retourne le seed. */
    private static long constructValidatedRejectionSeed(int target, int offset, int bound) {
        final long seed = constructSeedForFirstNext31(target, offset);
        final int u = firstNext31(seed);
        if (u != target) {
            System.err.println("FATAL: construction seed pour u=" + target + " a produit u=" + u);
            System.exit(6);
        }
        final CountingRandom c = new CountingRandom(seed);
        c.nextInt(bound);
        if (c.calls < 2) {
            System.err.println("FATAL: seed construit " + seed + " ne declenche pas le rejet pour bound=" + bound);
            System.exit(6);
        }
        return seed;
    }

    private static void sanityInlineLcg() {
        final Random probe = new Random(0x5EEDCA11L);
        for (int i = 0; i < 1000; i++) {
            final long s = probe.nextLong();
            final int inline = firstNext31(s);
            final int real = new ExposedRandom(s).nextBits(31);
            if (inline != real) {
                System.err.println("FATAL: LCG inline != java.util.Random.next(31) pour seed " + s);
                System.exit(4);
            }
        }
        System.err.println("DumpGoldenRandomEdge: inline LCG == Random.next(31) on 1000 seeds");
    }

    private static void checkIllegalBounds() {
        try {
            new Random(0L).nextInt(0);
            System.err.println("FATAL: nextInt(0) n'a pas levé IllegalArgumentException");
            System.exit(5);
        } catch (IllegalArgumentException e) {
            System.err.println("DumpGoldenRandomEdge: nextInt(0) -> IllegalArgumentException (le portage Rust panique de facon equivalente)");
        }
        try {
            new Random(0L).nextInt(-5);
            System.err.println("FATAL: nextInt(-5) n'a pas levé IllegalArgumentException");
            System.exit(5);
        } catch (IllegalArgumentException e) {
            System.err.println("DumpGoldenRandomEdge: nextInt(-5) -> IllegalArgumentException (le portage Rust panique de facon equivalente)");
        }
    }

    private static void emitHeaders(Writer w) throws IOException {
        w.write("# kind seed index value\n");
        w.write("# generated-by: DumpGoldenRandomEdge.java (Welt G3 rng-port) - cas limites au-dela du contrat principal\n");
        w.write("# run-with: java DumpGoldenRandomEdge <outfile> (no -Xint needed, cf. DumpGoldenRandom.java)\n");
        w.write("# contenu: seeds extremes {Long.MIN_VALUE, Long.MAX_VALUE, -1, 1}: 100 nextInt; 100 nextInt-bound-1;"
                + " 100 nextInt-bound-1000; 100 nextLong; 100 nextDouble; " + GAUSSIAN_COUNT + " nextGaussian;"
                + " 200 nextFloat; 200 nextBoolean\n");
        w.write("#          + " + REJECT_SEEDS_PER_BOUND + " seeds construits (inversion LCG mod 2^48) dont le PREMIER next(31) declenche"
                + " la boucle de rejet de nextInt(3), + " + REJECT_SEEDS_PER_BOUND + " pour nextInt(1000) -"
                + " " + REJECT_SERIES_COUNT + " tirages chacun (le golden principal ne couvre jamais ce chemin: proba ~3e-7/tirage)\n");
        w.write("# value-formats: identiques a java-random-golden.txt\n");
    }

    private static void write(Writer w, String kind, long seed, long index, String value) throws IOException {
        w.write(kind);
        w.write(' ');
        w.write(Long.toString(seed));
        w.write(' ');
        w.write(Long.toString(index));
        w.write(' ');
        w.write(value);
        w.write('\n');
    }

    /** Garde-fou identique à DumpGoldenRandom : nextGaussian redrivable via nextDouble+StrictMath (fdlibm). */
    private static void checkGaussianSemantics() {
        final int n = 50;
        for (int i = 0; i < n; i++) {
            final long seed = 0x5EEDF00DL + i * 104729L;
            final Random r = new Random(seed);
            final double g1 = r.nextGaussian();
            final double g2 = r.nextGaussian();
            final Random r2 = new Random(seed);
            double v1, v2, s;
            do {
                v1 = 2 * r2.nextDouble() - 1;
                v2 = 2 * r2.nextDouble() - 1;
                s = v1 * v1 + v2 * v2;
            } while (s >= 1 || s == 0);
            final double mult = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
            if (Double.doubleToRawLongBits(g1) != Double.doubleToRawLongBits(v1 * mult)
                    || Double.doubleToRawLongBits(g2) != Double.doubleToRawLongBits(v2 * mult)) {
                System.err.println("FATAL: nextGaussian() != derivation nextDouble()+StrictMath pour seed " + seed);
                System.exit(2);
            }
        }
        System.err.println("DumpGoldenRandomEdge: nextGaussian == nextDouble()+StrictMath(fdlibm) on " + n + " seeds");
    }
}
