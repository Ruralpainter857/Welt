import org.pepsoft.util.PerlinNoise;
import org.pepsoft.util.RandomField;
import org.pepsoft.util.FastPerlin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Emits G2 bit-pattern references from the production Utils 2.2.0 classes. */
public final class DumpGoldenNoise {
    private static final long[] SEEDS = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
    private static final double[] COORDS = {-1024.5, -256.0, -17.25, -1.0, -0.125,
            0.0, 0.125, 0.5, 1.0, 17.25, 255.875, 256.0, 1024.5};

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0 ? null : Path.of(args[0]);
        Writer writer = output == null
                ? new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
                : Files.newBufferedWriter(output, StandardCharsets.UTF_8);
        long count = 0;
        try (writer) {
            writer.write("# kind seed coords... value\n");
            writer.write("# generated-by: DumpGoldenNoise.java; Utils-2.2.0.jar; float values are raw IEEE-754 bits\n");
            for (long seed : SEEDS) {
                PerlinNoise noise = new PerlinNoise(seed);
                FastPerlin fast = new FastPerlin(seed);
                for (double x : COORDS) {
                    count += emit(writer, "perlin1d", seed, x, noise.getPerlinNoise(x));
                    count += emit(writer, "fastperlin1d", seed, x, fast.sampleResult(x));
                }
                for (int i = 0; i < COORDS.length; i++) {
                    double x = COORDS[i];
                    double y = COORDS[(i * 7 + 3) % COORDS.length];
                    count += emit(writer, "perlin2d", seed, x, y, noise.getPerlinNoise(x, y));
                    count += emit(writer, "fastperlin2d", seed, x, y, fast.sampleResult(x, y));
                    double z = COORDS[(i * 11 + 5) % COORDS.length];
                    count += emit(writer, "perlin3d", seed, x, y, z, noise.getPerlinNoise(x, y, z));
                    count += emit(writer, "fastperlin3d", seed, x, y, z, fast.sampleResult(x, y, z));
                }
                for (int bits : new int[] {1, 2, 4, 8, 12}) {
                    for (double scale : new double[] {17.5, 256.0}) {
                        RandomField field = new RandomField(bits, scale, seed);
                        for (int i = 0; i < COORDS.length; i++) {
                            int x = (int) COORDS[i];
                            int y = (int) COORDS[(i * 7 + 3) % COORDS.length];
                            int z = (int) COORDS[(i * 11 + 5) % COORDS.length];
                            count += emit(writer, "randomfield2d", seed, bits, scale, x, y, field.getValue(x, y));
                            count += emit(writer, "randomfield3d", seed, bits, scale, x, y, z, field.getValue(x, y, z));
                        }
                    }
                }
            }
            for (int p = 0; p < 1000; p++) {
                count += emit(writer, "promillage", 0L, p, PerlinNoise.getLevelForPromillage(p));
                count += emit(writer, "promillage", 0L, p + 0.05f, PerlinNoise.getLevelForPromillage(p + 0.05f));
            }
            count += emitException(writer, "promillage", -1.0f);
            count += emitException(writer, "promillage", 1000.1f);
            writer.write("# samples " + count + "\n");
        }
        System.err.printf(Locale.ROOT, "DumpGoldenNoise: wrote %d samples%s%n", count,
                output == null ? " to stdout" : " to " + output);
    }

    private static long emit(Writer w, String kind, long seed, double x, float value) throws IOException {
        w.write(String.format(Locale.ROOT, "%s %d %.17g %08x%n", kind, seed, x, Float.floatToRawIntBits(value)));
        return 1;
    }

    private static long emit(Writer w, String kind, long seed, double x, double y, float value) throws IOException {
        w.write(String.format(Locale.ROOT, "%s %d %.17g %.17g %08x%n", kind, seed, x, y, Float.floatToRawIntBits(value)));
        return 1;
    }

    private static long emit(Writer w, String kind, long seed, double x, double y, double z, float value) throws IOException {
        w.write(String.format(Locale.ROOT, "%s %d %.17g %.17g %.17g %08x%n", kind, seed, x, y, z,
                Float.floatToRawIntBits(value)));
        return 1;
    }

    private static long emit(Writer w, String kind, long seed, int bits, double scale, int x, int y, int value)
            throws IOException {
        w.write(String.format(Locale.ROOT, "%s %d %d %.17g %d %d %08x%n", kind, seed, bits, scale, x, y, value));
        return 1;
    }

    private static long emit(Writer w, String kind, long seed, int bits, double scale, int x, int y, int z, int value)
            throws IOException {
        w.write(String.format(Locale.ROOT, "%s %d %d %.17g %d %d %d %08x%n", kind, seed, bits, scale, x, y, z, value));
        return 1;
    }

    private static long emit(Writer w, String kind, long seed, float p, float value) throws IOException {
        w.write(String.format(Locale.ROOT, "%s %d %.9g %08x%n", kind, seed, p, Float.floatToRawIntBits(value)));
        return 1;
    }

    private static long emitException(Writer w, String kind, float p) throws IOException {
        try {
            PerlinNoise.getLevelForPromillage(p);
            throw new AssertionError("expected IllegalArgumentException for promillage " + p);
        } catch (IllegalArgumentException expected) {
            w.write(String.format(Locale.ROOT, "%s 0 %.9g EX:IllegalArgumentException%n", kind, p));
            return 1;
        }
    }
}
