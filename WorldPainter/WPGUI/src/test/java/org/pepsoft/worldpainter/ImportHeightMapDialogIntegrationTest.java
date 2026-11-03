package org.pepsoft.worldpainter;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.importing.BackgroundImageLoader;
import org.pepsoft.worldpainter.importing.DialogTestSupport.Hold;
import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.importing.DialogTestSupport.*;

/**
 * Verifies ownership of disk-backed images through the actual Swing dialog, and loading them off the event dispatch
 * thread: the dialog stays usable, only the last of several requests is shown, stale results and errors are discarded
 * and a capture cancelled by closing the dialog is not followed by a complete load.
 */
public class ImportHeightMapDialogIntegrationTest {
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
        System.clearProperty("welt.import.boundedTiff");
        System.clearProperty("welt.import.asyncImages");
    }

    @Test public void replacementFallbackAndWindowClosureReleaseOwnedCaptures() throws Exception {
        File tiff = files.newFile("height.tiff"), png = files.newFile("height.png");
        writeImage(tiff, png, 1000);
        System.setProperty("welt.import.boundedTiff", "true");
        ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
        CachedImageHeightMap first = null, second = null;
        try {
            setFile(dialog, tiff);
            awaitQuiet();
            first = (CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"));
            assertNotNull("TIFF must use the bounded source", first);
            assertNull(edt(() -> field(dialog, "image")));
            assertEquals(1011.0, first.getHeight(5, 2), 0.0);
            setFile(dialog, png);
            assertTrue("Replacing the image closes its private capture", first.isClosed());
            awaitQuiet();
            assertNull(edt(() -> field(dialog, "cachedHeightMap")));
            assertNotNull("Unsupported formats retain the dense image path", edt(() -> field(dialog, "image")));
            setFile(dialog, tiff);
            awaitQuiet();
            second = (CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"));
            assertNotNull(second);
            assertFalse(second.isClosed());
            edtRun(() -> dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING)));
            assertTrue(edt(dialog::isCancelled));
            assertTrue("Closing the window releases the current capture", second.isClosed());
            assertNull(edt(() -> field(dialog, "cachedHeightMap")));
            assertNull("A cancelled dialog keeps no image", edt(() -> field(dialog, "image")));
        } finally {
            edtRun(dialog::dispose);
            if (first != null) first.close();
            if (second != null) second.close();
        }
    }

    @Test public void theDialogStaysUsableWhileAnImageLoads() throws Exception {
        File tiff = files.newFile("height.tiff"), png = files.newFile("height.png");
        writeImage(tiff, png, 1000);
        System.setProperty("welt.import.boundedTiff", "true");
        ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
        try (Hold hold = hold(1)) {
            setFile(dialog, tiff);
            hold.awaitHeld();
            // The loading thread is held: the event dispatch thread handles other actions meanwhile
            edtRun(() -> {
                assertTrue(((BackgroundImageLoader<?>) field(dialog, "loader")).isLoading());
                assertFalse("No import while loading", ((JButton) field(dialog, "buttonOk")).isEnabled());
                assertFalse((Boolean) call(dialog, "hasImage"));
                assertEquals("Loading image...", ((JLabel) field(dialog, "labelImageDimensions")).getText());
                ((JSpinner) field(dialog, "spinnerScale")).setValue(50.0f);
                ((JCheckBox) field(dialog, "checkBoxInvert")).doClick();
                ((JCheckBox) field(dialog, "checkBoxInvert")).doClick();
                ((JSpinner) field(dialog, "spinnerScale")).setValue(100.0f);
            });
            hold.release();
            awaitQuiet();
            edtRun(() -> {
                assertNotNull(field(dialog, "cachedHeightMap"));
                assertTrue(((JButton) field(dialog, "buttonOk")).isEnabled());
                assertTrue(((JLabel) field(dialog, "labelImageDimensions")).getText().startsWith("Image size: 128 x 128, 16 bits"));
            });
        } finally {
            edtRun(() -> closeWindow(dialog));
        }
    }

    @Test public void onlyTheLastOfRapidReplacementsIsShownAndStaleResultsAreReleased() throws Exception {
        File a = files.newFile("a.tiff"), b = files.newFile("b.png"), c = files.newFile("c.tiff"), aPng = files.newFile("a.png"), cPng = files.newFile("c.png");
        writeImage(a, aPng, 1000);
        writeImage(c, cPng, 3000);
        writeImage(files.newFile("b.tiff"), b, 2000);
        System.setProperty("welt.import.boundedTiff", "true");
        final int cacheFilesBefore = cacheFiles();
        ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
        try (Hold hold = holdResults(1)) {
            setFile(dialog, a);
            hold.awaitHeld();
            setFile(dialog, b);
            setFile(dialog, c);
            hold.release();
            awaitQuiet();
            assertEquals("A and C ran; B was replaced before it started", 2, hold.started());
            assertArrayEquals("C delivered, A discarded", new int[] {1, 1, 0, 0}, loaderCounters(dialog));
            final CachedImageHeightMap shown = (CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"));
            assertEquals(c, shown.getImageFile());
            assertEquals(3011.0, shown.getHeight(5, 2), 0.0);
            assertEquals("Only the capture of C remains", cacheFilesBefore + 1, cacheFiles());
        } finally {
            edtRun(() -> closeWindow(dialog));
        }
        assertEquals(cacheFilesBefore, cacheFiles());
    }

    @Test public void aStaleErrorDoesNotReplaceTheCurrentImage() throws Exception {
        File damaged = files.newFile("damaged.png"), tiff = files.newFile("height.tiff"), png = files.newFile("height.png");
        Files.writeString(damaged.toPath(), "not an image");
        writeImage(tiff, png, 1000);
        System.setProperty("welt.import.boundedTiff", "true");
        ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
        try (Hold hold = holdResults(1)) {
            setFile(dialog, damaged);
            hold.awaitHeld();
            setFile(dialog, tiff);
            hold.release();
            awaitQuiet();
            edtRun(() -> {
                final JLabel label = (JLabel) field(dialog, "labelImageDimensions");
                assertTrue(label.getText(), label.getText().startsWith("Image size: "));
                assertNotEquals(Color.RED, label.getForeground());
                assertEquals(tiff, field(dialog, "selectedFile"));
                assertNotNull(field(dialog, "cachedHeightMap"));
            });
            // The damaged file on its own is reported
            setFile(dialog, damaged);
            awaitQuiet();
            edtRun(() -> {
                final JLabel label = (JLabel) field(dialog, "labelImageDimensions");
                assertEquals("Not an image file of a supported type, or damaged file!", label.getText());
                assertEquals(Color.RED, label.getForeground());
                assertNull(field(dialog, "selectedFile"));
                assertFalse(((JButton) field(dialog, "buttonOk")).isEnabled());
            });
        } finally {
            edtRun(() -> closeWindow(dialog));
        }
    }

    @Test public void closingDuringACaptureCancelsItWithoutACompleteLoadAndTheNextImportWorks() throws Exception {
        File tiff = files.newFile("height.tiff"), png = files.newFile("height.png");
        writeImage(tiff, png, 1000);
        System.setProperty("welt.import.boundedTiff", "true");
        final int cacheFilesBefore = cacheFiles();
        ImportHeightMapDialog closed = edt(ImportHeightMapDialogIntegrationTest::dialog);
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
        // A new dialog loads and imports the same image into a new world
        ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
        setFile(dialog, tiff);
        awaitQuiet();
        final CachedImageHeightMap capture = (CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"));
        assertNotNull(capture);
        edtRun(() -> call(dialog, "ok"));
        assertFalse(edt(dialog::isCancelled));
        final World2 world = edt(dialog::getImportedWorld);
        assertNotNull(world);
        assertTrue("The import released the capture", capture.isClosed());
        assertEquals(cacheFilesBefore, cacheFiles());
    }

    @Test public void anInvalidOrEmptyPathUnloadsTheAcceptedImageInBothModes() throws Exception {
        File a = files.newFile("a.tiff"), aPng = files.newFile("a.png"), c = files.newFile("c.tiff"), cPng = files.newFile("c.png");
        writeImage(a, aPng, 1000);
        writeImage(c, cPng, 3000);
        File missing = new File(files.getRoot(), "missing.tiff");
        System.setProperty("welt.import.boundedTiff", "true");
        final int cacheFilesBefore = cacheFiles();
        for (boolean async: new boolean[] {true, false}) {
            System.setProperty("welt.import.asyncImages", Boolean.toString(async));
            for (String invalid: new String[] {missing.getAbsolutePath(), ""}) {
                ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
                try {
                    setFile(dialog, a);
                    awaitQuiet();
                    final CachedImageHeightMap accepted = (CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"));
                    assertNotNull(accepted);
                    edtRun(() -> ((JTextField) field(dialog, "fieldFilename")).setText(invalid));
                    awaitQuiet();
                    assertTrue("async=" + async + " invalid=" + invalid + ": the accepted capture is released", accepted.isClosed());
                    assertUnloaded(dialog);
                    assertEquals(cacheFilesBefore, cacheFiles());
                    // A valid file afterwards loads and imports normally
                    setFile(dialog, c);
                    awaitQuiet();
                    final CachedImageHeightMap reloaded = (CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"));
                    assertEquals(c, reloaded.getImageFile());
                    assertTrue(edt(() -> ((JButton) field(dialog, "buttonOk")).isEnabled()));
                    edtRun(() -> call(dialog, "ok"));
                    assertNotNull(edt(dialog::getImportedWorld));
                    assertTrue(reloaded.isClosed());
                } finally {
                    edtRun(() -> closeWindow(dialog));
                }
            }
        }
        assertEquals(cacheFilesBefore, cacheFiles());
    }

    @Test public void anInvalidOrEmptyPathDuringALoadDiscardsItsLateResult() throws Exception {
        File a = files.newFile("a.tiff"), aPng = files.newFile("a.png"), c = files.newFile("c.tiff"), cPng = files.newFile("c.png");
        writeImage(a, aPng, 1000);
        writeImage(c, cPng, 3000);
        File missing = new File(files.getRoot(), "missing.tiff");
        System.setProperty("welt.import.boundedTiff", "true");
        final int cacheFilesBefore = cacheFiles();
        for (String invalid: new String[] {missing.getAbsolutePath(), ""}) {
            ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
            try {
                try (Hold hold = holdResults(1)) {
                    setFile(dialog, a);
                    hold.awaitHeld();
                    edtRun(() -> ((JTextField) field(dialog, "fieldFilename")).setText(invalid));
                    assertFalse("invalid=" + invalid + ": nothing is loading anymore", isLoading(dialog));
                    assertUnloaded(dialog);
                    hold.release();
                    awaitQuiet();
                }
                assertArrayEquals("invalid=" + invalid + ": the late capture of A is discarded", new int[] {0, 1, 0, 0}, loaderCounters(dialog));
                assertUnloaded(dialog);
                assertEquals("the late capture is closed", cacheFilesBefore, cacheFiles());
                setFile(dialog, c);
                awaitQuiet();
                assertEquals(c, ((CachedImageHeightMap) edt(() -> field(dialog, "cachedHeightMap"))).getImageFile());
                assertTrue(edt(() -> ((JButton) field(dialog, "buttonOk")).isEnabled()));
            } finally {
                edtRun(() -> closeWindow(dialog));
            }
        }
        assertEquals(cacheFilesBefore, cacheFiles());
    }

    /** The dialog shows and holds no image, and nothing that needs one can be used. */
    private static void assertUnloaded(ImportHeightMapDialog dialog) throws Exception {
        edtRun(() -> {
            assertNull(field(dialog, "selectedFile"));
            assertNull(field(dialog, "cachedHeightMap"));
            assertNull(field(dialog, "image"));
            assertEquals("Image size: ? x ?, bit depth: ?, lowest value: ?, highest value: ?", ((JLabel) field(dialog, "labelImageDimensions")).getText());
            for (String name: new String[] {"buttonOk", "spinnerImageLow", "spinnerImageHigh", "comboBoxPreset"}) {
                assertFalse(name + " disabled", ((JComponent) field(dialog, name)).isEnabled());
            }
            assertEquals("no preview", 0, ((org.pepsoft.util.swing.TiledImageViewer) field(dialog, "tiledImageViewer2")).getTileProviderCount());
        });
    }

    @Test public void backgroundAndSynchronousLoadingShowTheSameProperties() throws Exception {
        File tiff = files.newFile("height.tiff"), png = files.newFile("height.png");
        writeImage(tiff, png, 1000);
        for (String bounded: new String[] {"true", "false"}) {
            System.setProperty("welt.import.boundedTiff", bounded);
            for (File file: new File[] {tiff, png}) {
                for (boolean invertFirst: new boolean[] {false, true}) {
                    final List<String> synchronous = properties(file, false, invertFirst), background = properties(file, true, invertFirst);
                    assertEquals(file.getName() + " bounded=" + bounded + " invertFirst=" + invertFirst, synchronous, background);
                }
                // Inverting a loaded image shows what loading it inverted shows
                assertEquals(file.getName() + " bounded=" + bounded, properties(file, true, true), properties(file, true, false, true));
            }
        }
    }

    private List<String> properties(File file, boolean async, boolean invertFirst) throws Exception {
        return properties(file, async, invertFirst, false);
    }

    /** What the dialog shows after loading a file, optionally inverted before or after loading it. */
    private List<String> properties(File file, boolean async, boolean invertFirst, boolean invertAfter) throws Exception {
        System.setProperty("welt.import.asyncImages", Boolean.toString(async));
        ImportHeightMapDialog dialog = edt(ImportHeightMapDialogIntegrationTest::dialog);
        try {
            if (invertFirst) {
                edtRun(() -> ((JCheckBox) field(dialog, "checkBoxInvert")).doClick());
            }
            setFile(dialog, file);
            awaitQuiet();
            if (invertAfter) {
                edtRun(() -> ((JCheckBox) field(dialog, "checkBoxInvert")).doClick());
            }
            return edt(() -> {
                final List<String> properties = new ArrayList<>();
                for (String name: new String[] {"labelImageDimensions", "labelImageLowestLevel", "labelImageHighestLevel", "labelWorldDimensions"}) {
                    properties.add(name + "=" + ((JLabel) field(dialog, name)).getText());
                }
                for (String name: new String[] {"spinnerImageLow", "spinnerImageHigh", "spinnerWorldLow", "spinnerWorldHigh", "spinnerVoidBelow"}) {
                    properties.add(name + "=" + ((JSpinner) field(dialog, name)).getValue());
                }
                properties.add("preset=" + ((JComboBox<?>) field(dialog, "comboBoxPreset")).getSelectedItem());
                properties.add("ok=" + ((JButton) field(dialog, "buttonOk")).isEnabled());
                return properties;
            });
        } finally {
            edtRun(() -> closeWindow(dialog));
            System.clearProperty("welt.import.asyncImages");
        }
    }

    private static ImportHeightMapDialog dialog() {
        return new ImportHeightMapDialog(App.getInstance(), ColourScheme.DEFAULT, false, 10, TileRenderer.LightOrigin.NORTHWEST, null);
    }

    private static void closeWindow(JDialog dialog) {
        dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
        dialog.dispose();
    }

    /** A 16 bit greyscale image of 128 x 128 with samples base + x + 3y, as TIFF and PNG. */
    private static void writeImage(File tiff, File png, int base) throws Exception {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) image.getRaster().setSample(x, y, 0, base + x + 3 * y);
        }
        assertTrue(ImageIO.write(image, "TIFF", tiff));
        assertTrue(ImageIO.write(image, "PNG", png));
    }
}
