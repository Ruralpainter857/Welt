import java.util.Random;

/**
 * Probe G3 (éphémère) : vérifie empiriquement sur CETTE JVM si Math.log (potentiellement
 * compilée JIT avec l'intrinsic Intel LIBM "fast_log") est bit-identique à StrictMath.log
 * (fdlibm native) sur la distribution des s consommés par java.util.Random.nextGaussian().
 *
 * Contexte : java.util.Random.nextGaussian() appelle Math.sqrt(-2 * Math.log(s) / s).
 * Le bit-exact du portage Rust (fdlibm) n'est garanti que si Math.log == StrictMath.log
 * bit-à-bit pour ces s. Affiche le résultat sur stderr (jamais dans les golden).
 */
public class ProbeMathLog {
    public static void main(String[] args) {
        final long iterations = (args.length > 0) ? Long.parseLong(args[0]) : 10_000_000L;
        final Random r = new Random(0xC0FFEEL);
        long mismatch = 0, total = 0;
        long firstMismatchS = 0;
        for (long i = 0; i < iterations; i++) {
            // Reproduit exactement la boucle de Random.nextGaussian() pour générer des s réalistes.
            double s;
            do {
                double v1 = 2 * r.nextDouble() - 1;
                double v2 = 2 * r.nextDouble() - 1;
                s = v1 * v1 + v2 * v2;
            } while (s >= 1 || s == 0);
            final double a = Math.log(s);
            final double b = StrictMath.log(s);
            total++;
            if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) {
                mismatch++;
                if (mismatch <= 5) {
                    System.err.println("MISMATCH s=" + s + " bits=0x"
                            + Long.toHexString(Double.doubleToRawLongBits(s)));
                    if (firstMismatchS == 0) firstMismatchS = Double.doubleToRawLongBits(s);
                }
            }
        }
        System.err.println("ProbeMathLog: total=" + total + " mismatch=" + mismatch);
        System.err.println("java.version=" + System.getProperty("java.version")
                + " vm=" + System.getProperty("java.vm.name"));
        // Résultat attendu pour le portage : mismatch == 0 (fdlibm Rust == Math.log JVM).
        System.exit(mismatch == 0 ? 0 : 1);
    }
}
