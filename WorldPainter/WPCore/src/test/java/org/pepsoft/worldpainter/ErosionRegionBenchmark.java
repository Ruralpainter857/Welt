package org.pepsoft.worldpainter;

import java.awt.Rectangle;
import java.util.Random;
import java.lang.management.ManagementFactory;

public final class ErosionRegionBenchmark {
    static Dimension fixture() {
        Dimension d = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        d.removeTile(0, 0);
        for (int tx = -1; tx <= 1; tx++) for (int ty = -1; ty <= 1; ty++) {
            if (tx == 1 && ty == 1) continue;
            Tile t = new Tile(tx, ty, d.getMinHeight(), d.getMaxHeight());
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                t.setRawHeight(x, y, 12000 + ((x * 331 + y * 719 + tx * 53 + ty * 71) & 8191));
            d.addTile(t);
        }
        return d;
    }

    static byte[] controls(int radius, Random random) {
        int side = 2 * radius + 1;
        byte[] controls = new byte[side * side * 3];
        fillControls(controls, random);
        return controls;
    }

    static void fillControls(byte[] controls, Random random) {
        for (int i = 0; i < controls.length; i += 3) {
            controls[i] = (byte) (random.nextFloat() < 0.7f ? 1 : 0);
            if (controls[i] != 0) {
                controls[i + 1] = (byte) (random.nextBoolean() ? 1 : 0);
                controls[i + 2] = (byte) (random.nextBoolean() ? 1 : 0);
            } else controls[i + 1] = controls[i + 2] = 0;
        }
    }

    static void scalar(Dimension d, int cx, int cy, int radius, byte[] controls) {
        int side = radius * 2 + 1;
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            int c = (x * side + y) * 3;
            if (controls[c] == 0) continue;
            int wx = cx - radius + x, wy = cy - radius + y;
            int lx = 0, ly = 0, low = Integer.MAX_VALUE;
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
                int dx = controls[c + 1] != 0 ? 2 - a : a;
                int dy = controls[c + 2] != 0 ? 2 - b : b;
                int value = d.getRawHeightAt(wx + dx - 1, wy + dy - 1);
                if (value < low) { low = value; lx = dx; ly = dy; }
            }
            if (lx == 1 && ly == 1) continue;
            int current = d.getRawHeightAt(wx, wy);
            int amount = Math.min((int) ((current - low) / 2 / ((lx != 1 && ly != 1) ? (float) Math.sqrt(2) : 1)), 64);
            amount = (int) ((amount / 64f) * (amount / 64f) * 64);
            if (amount > 0) {
                d.setRawHeightAt(wx, wy, current - amount);
                d.setRawHeightAt(wx + lx - 1, wy + ly - 1, low + amount);
            }
        }
    }

    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[] times = new double[7]; long allocation = 0;
        for (int trial = -5; trial < 7; trial++) {
            Dimension d = fixture(); Random random = new Random(781);
            byte[] controls = new byte[127 * 127 * 3];
            d.setEventsInhibited(true);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 16; stroke++) {
                fillControls(controls, random);
                if (rust) {
                    if (!ErosionAccess.apply(d, 64, 64, 63, controls)) throw new AssertionError("Native erosion unavailable");
                } else scalar(d, 64, 64, 63, controls);
            }
            double elapsed = (System.nanoTime() - start) / 1e6;
            allocation = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            d.setEventsInhibited(false);
            if (trial >= 0) times[trial] = elapsed;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s full_16_erosion_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocation);
    }
}
