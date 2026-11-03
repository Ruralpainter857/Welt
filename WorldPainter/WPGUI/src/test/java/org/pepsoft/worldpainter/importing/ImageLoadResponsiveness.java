package org.pepsoft.worldpainter.importing;

import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Resources;

import javax.swing.*;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Responsiveness of opening an image in one of the actual import dialogs: the time from setting the file name until the
 * image is ready (properties shown, OK possible), the time spent in the event dispatch thread during that, measured by
 * a probe thread that keeps posting empty events and records how late each one runs, and the retained heap with the
 * image loaded and after closing the dialog. Works with synchronous and background loading alike. Not a test; requires
 * the graphical test profile (isolated configuration directory).
 *
 * <p>Arguments: {@code heightmap|mask file bounded(true|false) repetitions [rapid files...]}. The first repetition is a
 * warm-up. With rapid files, each of them is selected in turn without waiting, then the file: the counters of the
 * loader show which loads ran (background loading only).
 */
public final class ImageLoadResponsiveness {
    public static void main(String[] args) throws Exception {
        final boolean heightMap = args[0].equals("heightmap");
        final File file = new File(args[1]);
        final String bounded = args[2];
        final int repetitions = Integer.parseInt(args[3]);
        final String[] rapid = java.util.Arrays.copyOfRange(args, 4, args.length);
        System.setProperty(heightMap ? "welt.import.boundedTiff" : "welt.import.boundedMaskTiff", bounded);
        CustomMaterialPreviewIntegrationTest.start();
        int failures = 0;
        try {
            for (int repetition = 0; repetition < repetitions; repetition++) {
                final JDialog[] dialog = new JDialog[1];
                SwingUtilities.invokeAndWait(() -> dialog[0] = heightMap ? heightMapDialog() : maskDialog());
                final Probe probe = new Probe();
                probe.start();
                final long[] setText = new long[1];
                final long start = System.nanoTime();
                for (String other: rapid) {
                    SwingUtilities.invokeAndWait(() -> ((JTextField) field(dialog[0], "fieldFilename")).setText(new File(other).getAbsolutePath()));
                }
                SwingUtilities.invokeAndWait(() -> {
                    final long t = System.nanoTime();
                    ((JTextField) field(dialog[0], "fieldFilename")).setText(file.getAbsolutePath());
                    setText[0] = System.nanoTime() - t;
                });
                final long deadline = start + 120_000_000_000L;
                boolean ready = false;
                while ((! ready) && (System.nanoTime() < deadline)) {
                    final boolean[] state = new boolean[1];
                    SwingUtilities.invokeAndWait(() -> state[0] = ready(dialog[0]));
                    ready = state[0];
                    if (! ready) {
                        Thread.sleep(1);
                    }
                }
                final double totalMs = (System.nanoTime() - start) / 1e6;
                // Let the delayed preview update run too, still probed
                Thread.sleep(600);
                probe.stop = true;
                probe.join();
                final long retainedLoaded = retainedHeap();
                final String counters = counters(dialog[0]);
                SwingUtilities.invokeAndWait(() -> {
                    dialog[0].dispatchEvent(new WindowEvent(dialog[0], WindowEvent.WINDOW_CLOSING));
                    dialog[0].dispose();
                });
                SwingUtilities.invokeAndWait(() -> {});
                final long retainedClosed = retainedHeap();
                if (! ready) {
                    failures++;
                }
                System.out.printf(Locale.ROOT, "rep=%d dialog=%s bounded=%s ready=%b total_ms=%.1f settext_edt_ms=%.1f max_edt_stall_ms=%.1f edt_stall_over_20ms_total_ms=%.1f probes=%d"
                                + " retained_heap_loaded=%d retained_heap_closed=%d cache_files_left=%d rapid=%d loader=%s%n", repetition, args[0], bounded, ready, totalMs, setText[0] / 1e6,
                        probe.maxStall / 1e6, probe.stallOver / 1e6, probe.probes, retainedLoaded, retainedClosed, cacheFiles(), rapid.length, counters);
            }
        } finally {
            CustomMaterialPreviewIntegrationTest.stop();
        }
        System.exit((failures == 0) ? 0 : 1);
    }

    /** Whether the image is loaded and no load is in progress, through the private state of either dialog. */
    private static boolean ready(JDialog dialog) {
        try {
            final Method hasImage = dialog.getClass().getDeclaredMethod("hasImage");
            hasImage.setAccessible(true);
            if (! (Boolean) hasImage.invoke(dialog)) {
                return false;
            }
            try {
                final Object loader = field(dialog, "loader");
                final Method loading = loader.getClass().getDeclaredMethod("isLoading");
                loading.setAccessible(true);
                return ! (Boolean) loading.invoke(loader);
            } catch (AssertionError e) {
                // Synchronous dialog: loaded when the image is there
                return true;
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The delivered, discarded and cancelled counters of the loader of the dialog, or "none" for a synchronous one. */
    private static String counters(JDialog dialog) throws Exception {
        final String[] result = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                final Object loader = field(dialog, "loader");
                result[0] = field(loader, "deliveredResults") + "/" + field(loader, "discardedResults") + "/" + field(loader, "cancelledTasks");
            } catch (AssertionError e) {
                result[0] = "none";
            }
        });
        return result[0];
    }

    private static JDialog heightMapDialog() {
        return new ImportHeightMapDialog(App.getInstance(), ColourScheme.DEFAULT, false, 10, TileRenderer.LightOrigin.NORTHWEST, null);
    }

    private static JDialog maskDialog() {
        final App app = App.getInstance();
        final List<Layer> layers = new ArrayList<>(app.getAllLayers());
        if (! layers.contains(Resources.INSTANCE)) {
            layers.add(Resources.INSTANCE);
        }
        return new ImportMaskDialog(app, app.getDimension(), ColourScheme.DEFAULT, layers, new CustomBiomeManager(), null);
    }

    /** Posts empty events one at a time and records how long each takes to run: the event dispatch thread stalls. */
    private static final class Probe extends Thread {
        Probe() {
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                while (! stop) {
                    final long posted = System.nanoTime();
                    SwingUtilities.invokeAndWait(() -> {});
                    final long delay = System.nanoTime() - posted;
                    probes++;
                    maxStall = Math.max(maxStall, delay);
                    if (delay > 20_000_000L) {
                        stallOver += delay;
                    }
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        volatile boolean stop;
        volatile long maxStall, stallOver;
        volatile int probes;
    }

    private static int cacheFiles() {
        final String[] names = new File(System.getProperty("java.io.tmpdir")).list();
        int count = 0;
        if (names != null) {
            for (String name: names) {
                if (name.startsWith("welt-heightmap-")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long retainedHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static Object field(Object target, String name) {
        try {
            final Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
