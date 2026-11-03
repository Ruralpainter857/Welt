package org.pepsoft.worldpainter;

import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import javax.imageio.ImageIO;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import static org.junit.Assert.*;

/** Verifies ownership of disk-backed images through the actual Swing dialog. */
public class ImportHeightMapDialogIntegrationTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @BeforeClass public static void start() throws Exception {
        assertFalse("This integration test requires the graphical test profile", GraphicsEnvironment.isHeadless());
        CustomMaterialPreviewIntegrationTest.start();
    }

    @AfterClass public static void stop() throws Exception {
        CustomMaterialPreviewIntegrationTest.stop();
    }

    @Test public void replacementFallbackAndWindowClosureReleaseOwnedCaptures() throws Exception {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) image.getRaster().setSample(x, y, 0, 1000 + x + 3 * y);
        }
        File tiff = files.newFile("height.tiff"), png = files.newFile("height.png");
        assertTrue(ImageIO.write(image, "TIFF", tiff));
        assertTrue(ImageIO.write(image, "PNG", png));
        String previous = System.getProperty("welt.import.boundedTiff");
        try {
            System.setProperty("welt.import.boundedTiff", "true");
            SwingUtilities.invokeAndWait(() -> {
                ImportHeightMapDialog dialog = new ImportHeightMapDialog(App.getInstance(), ColourScheme.DEFAULT,
                        false, 10, TileRenderer.LightOrigin.NORTHWEST, null);
                CachedImageHeightMap first = null, second = null;
                try {
                    JTextField filename = (JTextField) field(dialog, "fieldFilename");
                    filename.setText(tiff.getAbsolutePath());
                    first = (CachedImageHeightMap) field(dialog, "cachedHeightMap");
                    assertNotNull("TIFF must use the bounded source", first);
                    assertNull(field(dialog, "image"));
                    assertEquals(1011.0, first.getHeight(5, 2), 0.0);
                    filename.setText(png.getAbsolutePath());
                    assertTrue("Replacing the image closes its private capture", first.isClosed());
                    assertNull(field(dialog, "cachedHeightMap"));
                    assertNotNull("Unsupported formats retain the dense image path", field(dialog, "image"));
                    filename.setText(tiff.getAbsolutePath());
                    second = (CachedImageHeightMap) field(dialog, "cachedHeightMap");
                    assertNotNull(second);
                    assertFalse(second.isClosed());
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    assertTrue(dialog.isCancelled());
                    assertTrue("Closing the window releases the current capture", second.isClosed());
                    assertNull(field(dialog, "cachedHeightMap"));
                } finally {
                    dialog.dispose();
                    if (first != null) first.close();
                    if (second != null) second.close();
                }
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally {
            if (previous == null) System.clearProperty("welt.import.boundedTiff");
            else System.setProperty("welt.import.boundedTiff", previous);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
