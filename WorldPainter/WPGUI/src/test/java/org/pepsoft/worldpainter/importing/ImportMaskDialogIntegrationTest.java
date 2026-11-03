package org.pepsoft.worldpainter.importing;

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
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Resources;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static java.awt.image.DataBuffer.TYPE_USHORT;
import static org.junit.Assert.*;

/**
 * Ownership of the bounded mask capture through the actual Swing dialog: replacing a TIFF by a PNG (dense fallback) and
 * back, closing the window, and a complete import through {@code ok()}, which releases the capture and leaves no
 * reference to it. Requires the graphical test profile (isolated configuration directory).
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

    @Test public void replacementClosureAndCompleteImportReleaseTheCapture() throws Exception {
        final File tiff = files.newFile("mask.tif"), png = files.newFile("mask.png");
        BoundedMaskImportParityTest.writeTiff(tiff, TYPE_USHORT, 300, 200, (x, y) -> (x * 977 + y * 131) & 0xffff);
        final BufferedImage image = ImageIO.read(tiff);
        assertTrue(ImageIO.write(image, "PNG", png));
        final String previous = System.getProperty("welt.import.boundedMaskTiff");
        try {
            System.setProperty("welt.import.boundedMaskTiff", "true");
            SwingUtilities.invokeAndWait(() -> {
                final ImportMaskDialog dialog = dialog();
                CachedImageHeightMap first = null, second = null;
                try {
                    final JTextField filename = (JTextField) field(dialog, "fieldFilename");
                    filename.setText(tiff.getAbsolutePath());
                    first = (CachedImageHeightMap) field(dialog, "capture");
                    assertNotNull("The TIFF mask must use the bounded capture", first);
                    assertNull("No dense image beside the capture", field(dialog, "image"));
                    assertNotNull(field(dialog, "maskImporter"));
                    filename.setText(png.getAbsolutePath());
                    assertTrue("Replacing the mask closes its capture", first.isClosed());
                    assertNull(field(dialog, "capture"));
                    assertNotNull("A PNG keeps the dense image path", field(dialog, "image"));
                    filename.setText(tiff.getAbsolutePath());
                    second = (CachedImageHeightMap) field(dialog, "capture");
                    assertNotNull(second);
                    assertFalse(second.isClosed());
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    assertTrue(dialog.isCancelled());
                    assertTrue("Closing the window releases the capture", second.isClosed());
                    assertNull(field(dialog, "capture"));
                } finally {
                    dialog.dispose();
                    if (first != null) first.close();
                    if (second != null) second.close();
                }
            });
            // A complete import through the dialog: the Resources layer with the first proposed mapping
            final CachedImageHeightMap[] imported = new CachedImageHeightMap[1];
            final Dimension dimension = App.getInstance().getDimension();
            final long before = resources(dimension);
            SwingUtilities.invokeAndWait(() -> {
                final ImportMaskDialog dialog = dialog();
                try {
                    ((JTextField) field(dialog, "fieldFilename")).setText(tiff.getAbsolutePath());
                    imported[0] = (CachedImageHeightMap) field(dialog, "capture");
                    assertNotNull(imported[0]);
                    ((JRadioButton) field(dialog, "radioButtonLayer")).doClick();
                    ((JComboBox<?>) field(dialog, "comboBoxLayer")).setSelectedItem(Resources.INSTANCE);
                    final JComboBox<?> mappings = (JComboBox<?>) field(dialog, "comboBoxMapping");
                    assertTrue("mappings proposed", mappings.getItemCount() > 0);
                    mappings.setSelectedIndex(0);
                    final Method ok = ImportMaskDialog.class.getDeclaredMethod("ok");
                    ok.setAccessible(true);
                    ok.invoke(dialog);
                    assertFalse("The import completed", dialog.isCancelled());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                } finally {
                    dialog.dispose();
                }
            });
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue("The completed import released the capture", imported[0].isClosed());
            assertNotEquals("The mask was imported into the dimension", before, resources(dimension));
        } finally {
            if (previous == null) System.clearProperty("welt.import.boundedMaskTiff");
            else System.setProperty("welt.import.boundedMaskTiff", previous);
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
