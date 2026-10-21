import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Welt G3 (rng-port) — dumper golden du LCG {@link java.util.Random} (48 bits, sémantique JDK 17+).
 *
 * <p>Sortie : une ligne par échantillon « kind seed index value », écrite dans le fichier
 * donné en args[0] (UTF-8/ASCII, fins de ligne LF), ou sur stdout si aucun argument.
 * Consommé par les tests bit-à-bit de {@code welt-core/src/rng/java_random.rs}.
 *
 * <p><b>Déterminisme nextGaussian</b> : {@code Random.nextGaussian()} (JDK 17+) appelle
 * {@code StrictMath.log(s)} et {@code StrictMath.sqrt} (bytecode vérifié sur Temurin
 * 21.0.9 : {@code mult = StrictMath.sqrt(-2 * StrictMath.log(s) / s)}) — sémantique
 * fdlibm ({@code java.lang.FdLibm$Log}), identique en JIT et interprété. Une sonde
 * séparée a observé des différences entre {@code Math.log} et {@code StrictMath.log};
 * elles ne concernent pas {@code Random.nextGaussian()}, qui appelle explicitement
 * {@code StrictMath}. Ce dumper AUTO-VÉRIFIE au démarrage que {@code nextGaussian()}
 * est redérivable depuis {@code nextDouble()} + {@code StrictMath} (exit 2 sinon), plutôt
 * que de produire un golden non reproductible.
 *
 * <p>Contenu (contrat G3) : seeds 0, 12345, -987654321 :
 * <ul>
 *   <li>1000× {@code nextInt()}</li>
 *   <li>{@code nextInt(bound)} ×100 pour bound ∈ {2, 3, 16, 256, 1000, 2147483647, 1073741824}</li>
 *   <li>500× {@code nextLong()}, 500× {@code nextDouble()}, 500× {@code nextGaussian()}</li>
 *   <li>200× {@code nextFloat()}, 200× {@code nextBoolean()}</li>
 *   <li>pattern WorldPainter : 100 couples (x,y) de la grille [0..9]² :
 *       {@code new Random(42 + x*65537L + y*4099L).nextInt(256)}
 *       (cf. {@code Terrain.getSurfaceObject}), index = 10*x+y</li>
 * </ul>
 * Total : 3×3600 + 100 = 10900 échantillons.
 */
public class DumpGoldenRandom {
    private static final long[] SEEDS = {0L, 12345L, -987654321L};
    private static final int[] BOUNDS = {2, 3, 16, 256, 1000, 2147483647, 1073741824};
    private static final int NEXT_INT_COUNT = 1000;
    private static final int BOUND_COUNT = 100;
    private static final int LONG_COUNT = 500;
    private static final int DOUBLE_COUNT = 500;
    private static final int GAUSSIAN_COUNT = 500;
    private static final int FLOAT_COUNT = 200;
    private static final int BOOLEAN_COUNT = 200;
    private static final int WP_GRID = 10; // 10×10 = 100 couples (x,y)

    public static void main(String[] args) throws IOException {
        checkGaussianSemantics();
        final Path outPath = (args.length > 0) ? Path.of(args[0]) : null;
        final Writer w = (outPath != null)
                ? Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)
                : new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        long total = 0;
        try {
            emitHeaders(w);
            for (long seed : SEEDS) {
                Random r = new Random(seed);
                for (int i = 0; i < NEXT_INT_COUNT; i++) {
                    write(w, "nextInt", seed, i, Integer.toHexString(r.nextInt()));
                    total++;
                }
                for (int bound : BOUNDS) {
                    r = new Random(seed);
                    for (int i = 0; i < BOUND_COUNT; i++) {
                        write(w, "nextInt-bound-" + bound, seed, i, Integer.toHexString(r.nextInt(bound)));
                        total++;
                    }
                }
                r = new Random(seed);
                for (int i = 0; i < LONG_COUNT; i++) {
                    write(w, "nextLong", seed, i, Long.toHexString(r.nextLong()));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < DOUBLE_COUNT; i++) {
                    write(w, "nextDouble", seed, i, Long.toHexString(Double.doubleToRawLongBits(r.nextDouble())));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < GAUSSIAN_COUNT; i++) {
                    write(w, "nextGaussian", seed, i, Long.toHexString(Double.doubleToRawLongBits(r.nextGaussian())));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < FLOAT_COUNT; i++) {
                    write(w, "nextFloat", seed, i, Integer.toHexString(Float.floatToRawIntBits(r.nextFloat())));
                    total++;
                }
                r = new Random(seed);
                for (int i = 0; i < BOOLEAN_COUNT; i++) {
                    write(w, "nextBoolean", seed, i, r.nextBoolean() ? "1" : "0");
                    total++;
                }
            }
            // Pattern WorldPainter (Terrain.getSurfaceObject) : un tirage par couple (x,y).
            for (int y = 0; y < WP_GRID; y++) {
                for (int x = 0; x < WP_GRID; x++) {
                    final long wpSeed = 42L + x * 65537L + y * 4099L;
                    final int value = new Random(wpSeed).nextInt(256);
                    write(w, "wpPattern", wpSeed, 10 * x + y, Integer.toHexString(value));
                    total++;
                }
            }
            w.write("# samples " + total + "\n");
        } finally {
            w.flush();
            w.close();
        }
        System.err.println("DumpGoldenRandom: wrote " + total + " samples"
                + (outPath != null ? " to " + outPath : " to stdout"));
    }

    private static void emitHeaders(Writer w) throws IOException {
        w.write("# kind seed index value\n");
        w.write("# generated-by: DumpGoldenRandom.java (Welt G3 rng-port) - java.util.Random golden dump\n");
        w.write("# java.version=" + System.getProperty("java.version")
                + " java.vm.name=" + System.getProperty("java.vm.name")
                + " os.arch=" + System.getProperty("os.arch") + "\n");
        w.write("# run-with: java DumpGoldenRandom <outfile> (no -Xint needed: nextGaussian uses StrictMath.log/sqrt = fdlibm, deterministic in all modes; self-checked at startup)\n");
        w.write("# value-formats: seed=signed-decimal; int=unsigned-hex Integer.toHexString; long=unsigned-hex Long.toHexString;"
                + " double=hex of Double.doubleToRawLongBits; float=hex of Float.floatToRawIntBits; boolean=0|1\n");
        w.write("# kinds: nextInt; nextInt-bound-<b> with b in {2,3,16,256,1000,2147483647,1073741824};"
                + " nextLong; nextDouble; nextGaussian; nextFloat; nextBoolean; wpPattern\n");
        w.write("# wpPattern: index=10*x+y with x,y in 0..9, seed=42+x*65537+y*4099 (Terrain.getSurfaceObject pattern), value=nextInt(256)\n");
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

    /**
     * Garde-fou : Random.nextGaussian() doit être bit-à-bit redrivable depuis la
     * séquence nextDouble() du même seed + StrictMath.log/StrictMath.sqrt (sémantique
     * JDK 17+ = fdlibm, ce que le portage Rust reproduit). Abort exit 2 sinon.
     */
    private static void checkGaussianSemantics() {
        final int n = 100;
        for (int i = 0; i < n; i++) {
            final long seed = 0x5EED0000L + i * 7919L;
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
            final double expect1 = v1 * mult;
            final double expect2 = v2 * mult;
            if (Double.doubleToRawLongBits(g1) != Double.doubleToRawLongBits(expect1)
                    || Double.doubleToRawLongBits(g2) != Double.doubleToRawLongBits(expect2)) {
                System.err.println("FATAL: nextGaussian() != derivation nextDouble()+StrictMath pour seed " + seed);
                System.err.println("FATAL: golden nextGaussian non reproductible sur cette JVM — voir docs/welt/rapports/G3-rng-port.md");
                System.exit(2);
            }
        }
        System.err.println("DumpGoldenRandom: nextGaussian == nextDouble()+StrictMath(fdlibm) on " + n + " seeds (2 valeurs/seed)");
    }
}
