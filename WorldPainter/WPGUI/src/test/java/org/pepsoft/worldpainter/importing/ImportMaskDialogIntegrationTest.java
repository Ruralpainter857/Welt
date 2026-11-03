package org.pepsoft.worldpainter.importing;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.worldpainter.App;
import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.CustomMaterialPreviewIntegrationTest;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.biomeschemes.CustomBiomeManager;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.importing.DialogTestSupport.Hold;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Resources;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static java.awt.image.DataBuffer.TYPE_USHORT;
import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.importing.DialogTestSupport.*;

/**
 * Ownership of the bounded mask capture through the actual Swing dialog: replacing a TIFF by a PNG (dense fallback) and
 * back, closing the window, and a complete import through {@code ok()}, which releases the capture and leaves no
 * reference to it. Loading off the event dispatch thread: the dialog stays usable, only the last request is shown,
 * stale results and errors are discarded and a capture cancelled by closing the dialog is not followed by a complete
 * load. Requires the graphical test profile (isolated configuration directory).
 */
public class ImportMaskDialogIntegrationTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @BeforeClass public static void start() throws Exception {
        assertFalse("This integration test requires the graphical test profile", GraphicsEnvironment.isHeadless());
        CustomMaterialPreviewIntegrationTest.start();
    }

    @AfterClass public static void stop() throws Exception {
        CustomMaterialPreviewIntegrationTest.stop();
    }

    @After public void restore() throws Exception {
        awaitQuiet();
        assertTrue("no load waiting, running or undelivered", allIdle());
        System.clearProperty("welt.import.boundedMaskTiff");
        System.clearProperty("welt.import.asyncImages");
    }

    @Test public void replacementClosureAndCompleteImportReleaseTheCapture() throws Exception {
        final File tiff = files.newFile("mask.tif"), png = files.newFile("mask.png");
        writeMask(tiff, png, 0);
        System.setProperty("welt.import.boundedMaskTiff", "true");
        final ImportMaskDialog dialog = edt(ImportMaskDialogIntegrationTest::dialog);
        CachedImageHeightMap first = null, second = null;
        try {
            setFile(dialog, tiff);
            awaitQuiet();
            first = (CachedImageHeightMap) edt(() -> field(dialog, "capture"));
            assertNotNull("The TIFF mask must use the bounded capture", first);
            assertNull("No dense image beside the capture", edt(() -> field(dialog, "image")));
            assertNotNull(edt(() -> field(dialog, "maskImporter")));
            setFile(dialog, png);
            assertTrue("Replacing the mask closes its capture", first.isClosed());
            awaitQuiet();
            assertNull(edt(() -> field(dialog, "capture")));
            assertNotNull("A PNG keeps the dense image path", edt(() -> field(dialog, "image")));
            setFile(dialog, tiff);
            awaitQuiet();
            second = (CachedImageHeightMap) edt(() -> field(dialog, "capture"));
            assertNotNull(second);
            assertFalse(second.isClosed());
            edtRun(() -> dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
            assertTrue(edt(dialog::isCancelled));
            assertTrue("Closing the window releases the capture", second.isClosed());
            assertNull(edt(() -> field(dialog, "capture")));
            assertNull("A closed dialog keeps no image", edt(() -> field(dialog, "image")));
        } finally {
            edtRun(dialog::dispose);
            if (first != null) first.close();
            if (second != null) second.close();
        }
        // A complete import through the dialog: the Resources layer with the first proposed mapping
        final Dimension dimension = edt(() -> App.getInstance().getDimension());
        final long before = edt(() -> resources(dimension));
        final CachedImageHeightMap imported = importThroughDialog(tiff);
        assertTrue("The completed import released the capture", imported.isClosed());
        assertNotEquals("The mask was imported into the dimension", before, (long) edt(() -> resources(dimension)));
    }

    @Test public void theDialogStaysUsableWhileAMaskLoads() throws Exception {
        final File tiff = files.newFile("mask.tif"), png = files.newFile("mask.png");
        writeMask(tiff, png, 0);
        System.setProperty("welt.import.boundedMaskTiff", "true");
        final ImportMaskDialog dialog = edt(ImportMaskDialogIntegrationTest::dialog);
        try (Hold hold = hold(1)) {
            setFile(dialog, tiff);
            hold.awaitHeld();
            // The loading thread is held: the event dispatch thread handles other actions meanwhile
            edtRun(() -> {
                assertTrue(((BackgroundImageLoader<?>) field(dialog, "loader")).isLoading());
                assertEquals("Loading image...", ((JLabel) field(dialog, "labelImageDimensions")).getText());
                for (String name: new String[] {"buttonOk", "radioButtonLayer", "radioButtonTerrain", "comboBoxLayer", "comboBoxMapping", "comboBoxApplyToTerrain", "comboBoxApplyToLayerValue"}) {
                    assertFalse(name + " disabled while loading", ((JComponent) field(dialog, name)).isEnabled());
                }
                ((JSpinner) field(dialog, "spinnerOffsetX")).setValue(5);
                ((JSpinner) field(dialog, "spinnerOffsetX")).setValue(0);
            });
            hold.release();
            awaitQuiet();
            edtRun(() -> {
                assertNotNull(field(dialog, "capture"));
                assertNotNull(field(dialog, "maskImporter"));
                assertTrue(((JRadioButton) field(dialog, "radioButtonLayer")).isEnabled());
                assertTrue(((JLabel) field(dialog, "labelImageDimensions")).getText().startsWith("<html>Image size: 300 x 200, grey scale, 16 bits integer"));
            });
        } finally {
            edtRun(() -> closeWindow(dialog));
        }
    }

    @Test public void onlyTheLastRequestIsShownAndStaleResultsAndErrorsAreDiscarded() throws Exception {
        final File a = files.newFile("a.tif"), aPng = files.newFile("a.png"), damaged = files.newFile("damaged.png"), c = files.newFile("c.tif"), cPng = files.newFile("c.png");
        writeMask(a, aPng, 0);
        writeMask(c, cPng, 7);
        Files.writeString(damaged.toPath(), "not an image");
        System.setProperty("welt.import.boundedMaskTiff", "true");
        final int cacheFilesBefore = cacheFiles();
        final ImportMaskDialog dialog = edt(ImportMaskDialogIntegrationTest::dialog);
        try (Hold hold = holdResults(1)) {
            setFile(dialog, a);
            hold.awaitHeld();
            setFile(dialog, damaged);
            setFile(dialog, c);
            hold.release();
            awaitQuiet();
            assertEquals("A and C ran; the damaged file was replaced before it started", 2, hold.started());
            assertArrayEquals("C delivered, A discarded", new int[] {1, 1, 0, 0}, loaderCounters(dialog));
            edtRun(() -> {
                assertEquals(c, ((CachedImageHeightMap) field(dialog, "capture")).getImageFile());
                assertEquals(c, field(dialog, "selectedFile"));
                assertNotEquals(Color.RED, ((JLabel) field(dialog, "labelImageDimensions")).getForeground());
            });
            assertEquals("Only the capture of C remains", cacheFilesBefore + 1, cacheFiles());
        }
        // A damaged file loaded on its own is reported, and its stale error does not hide a later mask
        try (Hold hold = holdResults(1)) {
            setFile(dialog, damaged);
            hold.awaitHeld();
            setFile(dialog, a);
            hold.release();
            awaitQuiet();
            edtRun(() -> {
                assertEquals(a, field(dialog, "selectedFile"));
                assertNotNull(field(dialog, "capture"));
            });
        }
        setFile(dialog, damaged);
        awaitQuiet();
        edtRun(() -> {
            final JLabel label = (JLabel) field(dialog, "labelImageDimensions");
            assertEquals("Not an image file, or damaged file!", label.getText());
            assertEquals(Color.RED, label.getForeground());
            assertNull(field(dialog, "selectedFile"));
            assertFalse(((JButton) field(dialog, "buttonOk")).isEnabled());
        });
        edtRun(() -> closeWindow(dialog));
        assertEquals(cacheFilesBefore, cacheFiles());
    }

    @Test public void closingDuringACaptureCancelsItWithoutACompleteLoadAndTheNextImportWorks() throws Exception {
        final File tiff = files.newFile("mask.tif"), png = files.newFile("mask.png");
        writeMask(tiff, png, 0);
        System.setProperty("welt.import.boundedMaskTiff", "true");
        final int cacheFilesBefore = cacheFiles();
        final ImportMaskDialog closed = edt(ImportMaskDialogIntegrationTest::dialog);
        try (Hold hold = holdInsideTask()) {
            setFile(closed, tiff);
            hold.awaitHeld();
            edtRun(() -> closeWindow(closed));
            hold.release();
            awaitQuiet();
            assertArrayEquals("cancelled capture: nothing delivered, nothing loaded completely to discard", new int[] {0, 0, 1, 0}, loaderCounters(closed));
            assertNull(edt(() -> field(closed, "image")));
            assertEquals(cacheFilesBefore, cacheFiles());
        }
        final CachedImageHeightMap imported = importThroughDialog(tiff);
        assertTrue(imported.isClosed());
        assertEquals(cacheFilesBefore, cacheFiles());
    }

    @Test public void backgroundAndSynchronousLoadingShowTheSameProperties() throws Exception {
        final File tiff = files.newFile("mask.tif"), png = files.newFile("mask.png");
        writeMask(tiff, png, 0);
        for (String bounded: new String[] {"true", "false"}) {
            System.setProperty("welt.import.boundedMaskTiff", bounded);
            for (File file: new File[] {tiff, png}) {
                assertEquals(file.getName() + " bounded=" + bounded, properties(file, false), properties(file, true));
            }
        }
    }

    /** What the dialog shows after loading a file and selecting the Resources layer. */
    private List<String> properties(File file, boolean async) throws Exception {
        System.setProperty("welt.import.asyncImages", Boolean.toString(async));
        final ImportMaskDialog dialog = edt(ImportMaskDialogIntegrationTest::dialog);
        try {
            setFile(dialog, file);
            awaitQuiet();
            edtRun(() -> {
                ((JRadioButton) field(dialog, "radioButtonLayer")).doClick();
                ((JComboBox<?>) field(dialog, "comboBoxLayer")).setSelectedItem(Resources.INSTANCE);
            });
            return edt(() -> {
                final List<String> properties = new ArrayList<>();
                for (String name: new String[] {"labelImageDimensions", "labelMaskRange", "labelWorldDimensions", "labelReason"}) {
                    properties.add(name + "=" + ((JLabel) field(dialog, name)).getText());
                }
                final SpinnerNumberModel threshold = (SpinnerNumberModel) ((JSpinner) field(dialog, "spinnerThreshold")).getModel();
                properties.add("threshold=" + threshold.getMinimum() + ".." + threshold.getMaximum() + " " + threshold.getValue() + " step " + threshold.getStepSize());
                final JComboBox<?> mappings = (JComboBox<?>) field(dialog, "comboBoxMapping");
                for (int i = 0; i < mappings.getItemCount(); i++) {
                    properties.add("mapping=" + ((Mapping) mappings.getItemAt(i)).getDescription());
                }
                properties.add("scale=" + ((JSpinner) field(dialog, "spinnerScale")).isEnabled());
                return properties;
            });
        } finally {
            edtRun(() -> closeWindow(dialog));
            System.clearProperty("welt.import.asyncImages");
        }
    }

    /** Load a mask in a new dialog and import it into the Resources layer with the first mapping; returns its capture. */
    private static CachedImageHeightMap importThroughDialog(File tiff) throws Exception {
        final ImportMaskDialog dialog = edt(ImportMaskDialogIntegrationTest::dialog);
        try {
            setFile(dialog, tiff);
            awaitQuiet();
            final CachedImageHeightMap capture = (CachedImageHeightMap) edt(() -> field(dialog, "capture"));
            assertNotNull(capture);
            edtRun(() -> {
                ((JRadioButton) field(dialog, "radioButtonLayer")).doClick();
                ((JComboBox<?>) field(dialog, "comboBoxLayer")).setSelectedItem(Resources.INSTANCE);
                final JComboBox<?> mappings = (JComboBox<?>) field(dialog, "comboBoxMapping");
                assertTrue("mappings proposed", mappings.getItemCount() > 0);
                mappings.setSelectedIndex(0);
                assertTrue(((JButton) field(dialog, "buttonOk")).isEnabled());
                call(dialog, "ok");
                assertFalse("The import completed", dialog.isCancelled());
                assertNull("No reference to the capture after the import", field(dialog, "capture"));
            });
            return capture;
        } finally {
            edtRun(dialog::dispose);
        }
    }

    private static ImportMaskDialog dialog() {
        final App app = App.getInstance();
        final List<Layer> layers = new ArrayList<>(app.getAllLayers());
        if (! layers.contains(Resources.INSTANCE)) {
            layers.add(Resources.INSTANCE);
        }
        return new ImportMaskDialog(app, app.getDimension(), ColourScheme.DEFAULT, layers, new CustomBiomeManager(), null);
    }

    private static void closeWindow(JDialog dialog) {
        dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
        dialog.dispose();
    }

    /** A 16 bit greyscale mask of 300 x 200 as TIFF, and the same as PNG. */
    private static void writeMask(File tiff, File png, int variant) throws Exception {
        BoundedMaskImportParityTest.writeTiff(tiff, TYPE_USHORT, 300, 200, (x, y) -> (x * 977 + y * 131 + variant * 4099) & 0xffff);
        final BufferedImage image = ImageIO.read(tiff);
        assertTrue(ImageIO.write(image, "PNG", png));
    }

    /** A digest of the Resources layer of the dimension. */
    private static long resources(Dimension dimension) {
        long hash = 17;
        for (Tile tile: dimension.getTiles()) {
            for (int y = 0; y < 128; y += 2) {
                for (int x = 0; x < 128; x += 2) {
                    hash = hash * 31 + tile.getLayerValue(Resources.INSTANCE, x, y);
                }
            }
        }
        return hash;
    }
}
