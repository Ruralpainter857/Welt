package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Emits reference values from the production WorldPainter NoiseHeightMap. */
public final class DumpGoldenNoiseHeightMap {
    private DumpGoldenNoiseHeightMap() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: DumpGoldenNoiseHeightMap <output-file>");
        }
        final Path output = Path.of(args[0]);
        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("# Generated from WorldPainter NoiseHeightMap.getValue(double,double), JDK 17\n");
            writer.write("# kind dHeight scale octaves effectiveSeed x y rawDoubleBits\n");
            count += dump(writer, 128.25, 1.75, 1, 16, -9, 5);
            count += dump(writer, 256.5, 2.25, 5, -14, -9, 5);
            writer.write("# samples " + count + "\n");
        }
        System.err.printf(Locale.ROOT, "DumpGoldenNoiseHeightMap: wrote %d samples to %s%n", count, output);
    }

    private static long dump(BufferedWriter writer, double dHeight, double scale, int octaves,
                             long effectiveSeed, int originX, int originY) throws IOException {
        final long seedOffset = octaves == 1 ? 11 : -23;
        final long seed = effectiveSeed - seedOffset;
        final NoiseHeightMap map = new NoiseHeightMap("golden", dHeight, scale, octaves, seedOffset);
        map.setSeed(seed);
        for (int y = originY; y < originY + 16; y++) {
            for (int x = originX; x < originX + 16; x++) {
                final long bits = Double.doubleToRawLongBits(map.getValue(x, y));
                writer.write(String.format(Locale.ROOT,
                        "noise_height_map %.17g %.17g %d %d %d %d %016x%n",
                        dHeight, scale, octaves, effectiveSeed, x, y, bits));
            }
        }
        return 16L * 16L;
    }
}
