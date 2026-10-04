package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.app.Docking;
import java.awt.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import org.pepsoft.worldpainter.App;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.WorldFactory;
import org.pepsoft.worldpainter.WPContext;
import org.pepsoft.worldpainter.plugins.WPPluginManager;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Exercises the real application in the profile's isolated configuration directory. */
public class AppDockingIntegrationTest {
    @Test public void applicationOpensWorldAndRestoresItsToolPanels() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        String root = System.getProperty("welt.docking.configRoot");
        assumeNotNull(root);
        assertTrue("Application configuration must remain inside the test directory",
                Configuration.getConfigDir().toPath().toAbsolutePath().normalize()
                        .startsWith(new File(root).toPath().toAbsolutePath().normalize()));
        Configuration config = new Configuration();
        config.setDefaultWidth(2); config.setDefaultHeight(2);
        config.setAutosaveEnabled(false);
        Configuration.setInstance(config);
        WPPluginManager.initialise(config.getUuid(), WPContext.INSTANCE);
        var world = WorldFactory.createDefaultWorld(config, 1234L);
        AtomicReference<App> window = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    com.jidesoft.plaf.LookAndFeelFactory.installJideExtension(
                            com.jidesoft.plaf.LookAndFeelFactory.VSNET_STYLE_WITHOUT_MENU);
                    App app = App.getInstance(); window.set(app);
                    app.setWorld(world, true);
                    app.setSize(1200, 900);
                    app.setVisible(true);
                } catch (Exception e) { throw new IllegalStateException(e); }
            });
            SwingUtilities.invokeAndWait(() -> {
                var manager = window.get().getDockingManager();
                byte[] layout = manager.getLayoutRawData();
                manager.resetToDefault();
                manager.loadLayoutFrom(new ByteArrayInputStream(layout));
            });
            SwingUtilities.invokeAndWait(() -> {
                App app = window.get(); app.validate();
                var manager = app.getDockingManager();
                for (String id : new String[] {"tools", "toolSettings", "layers", "terrain", "biomes",
                        "annotations", "brushes", "brushSettings", "infoPanel"}) {
                    assertNotNull("Missing application panel " + id, manager.getFrame(id));
                    assertTrue("Undocked application panel " + id, Docking.isDocked(manager.getFrame(id)));
                }
                assertSame(world, app.getWorld());
                assertTrue(manager.getFrame("tools").getWidth() > 0);
                assertTrue(manager.getFrame("brushes").getHeight() > 0);
                String snapshot = System.getProperty("welt.docking.appSnapshot");
                if (snapshot != null) {
                    var image = new java.awt.image.BufferedImage(app.getWidth(), app.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try { app.getRootPane().printAll(graphics); } finally { graphics.dispose(); }
                    try { javax.imageio.ImageIO.write(image, "png", new File(snapshot)); }
                    catch (IOException e) { throw new IllegalStateException(e); }
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                App app = window.get();
                if (app != null) { app.clearWorld(); app.getDockingManager().close(); app.dispose(); }
            });
            SwingUtilities.invokeAndWait(() -> { });
        }
    }
}
