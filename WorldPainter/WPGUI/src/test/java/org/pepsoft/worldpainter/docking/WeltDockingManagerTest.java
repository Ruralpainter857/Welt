package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.app.Docking;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

public class WeltDockingManagerTest {
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static final AtomicReference<Check> CLEANUP = new AtomicReference<>();
    private static void onEdt(Check check) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { check.run(); } catch (Throwable e) { error.set(e); } });
        // Let floating-window notifications run before deregistering their panels.
        SwingUtilities.invokeAndWait(() -> {
            Check cleanup = CLEANUP.getAndSet(null);
            if (cleanup != null) try { cleanup.run(); } catch (Throwable e) {
                if (error.get() == null) error.set(e); else error.get().addSuppressed(e);
            }
        });
        SwingUtilities.invokeAndWait(() -> { });
        if (error.get() != null) throw new AssertionError("Docking check failed", error.get());
    }
    private static WeltDockPanel panel(String id, int group) {
        WeltDockPanel panel = new WeltDockPanel(id, id, WeltDockPanel.Side.WEST, group);
        panel.add(new JLabel(id));
        return panel;
    }
    @Test public void registersGroupsRestoresLayoutsAndPreservesRename() throws Exception {
        onEdt(() -> {
            JFrame frame = new JFrame("Welt docking checks");
            frame.setSize(1000, 800);
            JPanel container = new JPanel(new BorderLayout());
            frame.add(container);
            WeltDockingManager manager = new WeltDockingManager(frame, container, new JLabel("World"));
            try {
                WeltDockPanel tools = panel("tools", 1), layers = panel("layers", 3), terrain = panel("terrain", 3);
                manager.addFrame(tools); manager.addFrame(layers); manager.addFrame(terrain);
                assertTrue(Docking.isDocked(tools));
                assertTrue(Docking.isDocked(layers));
                assertTrue(Docking.isDocked(terrain));
                String snapshot = System.getProperty("welt.docking.snapshot");
                if (snapshot != null) {
                    frame.addNotify(); frame.validate();
                    var image = new java.awt.image.BufferedImage(1000, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try { frame.getContentPane().printAll(graphics); } finally { graphics.dispose(); }
                    javax.imageio.ImageIO.write(image, "png", new java.io.File(snapshot));
                }
                byte[] layout = manager.getLayoutRawData();
                assertEquals("app-layout", DockLayoutCodec.parse(layout).getDocumentElement().getTagName());
                assertTrue(new String(layout, StandardCharsets.UTF_8).contains("welt.workspace"));
                manager.resetToDefault();
                manager.loadLayoutFrom(new ByteArrayInputStream(layout));
                assertTrue(Docking.isDocked(layers));
                layers.setTitle("Renamed & <palette>");
                layers.setKey("palette & <renamed>");
                assertFalse(Docking.isDockableRegistered("layers"));
                assertTrue(Docking.isDockableRegistered("palette & <renamed>"));
                assertSame(layers, manager.getFrame("palette & <renamed>"));
                assertTrue(Docking.isDocked(layers));
                manager.activateFrame(layers.getKey());
                manager.removeFrame(layers.getKey());
                assertFalse(Docking.isDockableRegistered(layers.getKey()));
                assertNull(manager.getFrame(layers.getKey()));
                manager.addFrame(layers);
                assertTrue(Docking.isDocked(layers));
            } finally {
                CLEANUP.set(() -> { manager.close(); frame.dispose(); });
            }
        });
    }
    @Test public void floatingWindowRoundTripKeepsPanelAndRestoresMainLayout() throws Exception {
        onEdt(() -> {
            JFrame frame = new JFrame("Welt floating checks");
            frame.setSize(1000, 800);
            JPanel container = new JPanel(new BorderLayout()); frame.add(container);
            WeltDockingManager manager = new WeltDockingManager(frame, container, new JLabel("World"));
            try {
                WeltDockPanel tools = panel("tools", 1); manager.addFrame(tools);
                byte[] docked = manager.getLayoutRawData();
                frame.setVisible(true);
                JButton floatingButton = button(container, "Float panel");
                assertNotNull(floatingButton);
                floatingButton.doClick();
                assertEquals(1, Docking.getSingleInstance().getDockingState().getApplicationLayout().getFloatingFrameLayouts().size());
                byte[] floating = manager.getLayoutRawData();
                manager.loadLayoutFrom(new ByteArrayInputStream(docked));
                assertTrue(Docking.isDocked(tools));
                manager.loadLayoutFrom(new ByteArrayInputStream(floating));
                assertEquals(1, Docking.getSingleInstance().getDockingState().getApplicationLayout().getFloatingFrameLayouts().size());
                assertTrue(Docking.isDocked(tools));
            } finally {
                CLEANUP.set(() -> { manager.close(); frame.dispose(); });
            }
        });
    }
    private static JButton button(Container container, String tooltip) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton b && tooltip.equals(b.getToolTipText())) return b;
            if (child instanceof Container nested) {
                JButton result = button(nested, tooltip);
                if (result != null) return result;
            }
        }
        return null;
    }
    @Test public void autoHideHeaderActionAndDynamicRemovalPreserveRegistry() throws Exception {
        onEdt(() -> {
            JFrame frame = new JFrame("Welt pin checks"); frame.setSize(1000, 800);
            JPanel container = new JPanel(new BorderLayout()); frame.add(container);
            WeltDockingManager manager = new WeltDockingManager(frame, container, new JLabel("World"));
            try {
                WeltDockPanel tools = panel("tools", 1); manager.addFrame(tools);
                JButton pin = button(container, "Automatically hide panel");
                assertNotNull(pin);
                assertFalse(tools.requestClose());
                pin.doClick();
                assertTrue(Docking.isHidden(tools));
                byte[] pinned = manager.getLayoutRawData();
                manager.loadLayoutFrom(new ByteArrayInputStream(pinned));
                assertTrue(Docking.isHidden(tools));
                manager.activateFrame("tools");
                assertTrue(Docking.isHidden(tools));
                manager.removeFrame("tools");
                assertFalse(Docking.isDockableRegistered("tools"));
            } finally {
                CLEANUP.set(() -> { manager.close(); frame.dispose(); });
            }
        });
    }
    @Test public void legacyAndModernLayoutsRemainIndependentAcrossSerialization() throws Exception {
        var config = new org.pepsoft.worldpainter.Configuration();
        byte[] legacy = {1, 2, 3}, modern = {4, 5, 6};
        config.setDefaultJideLayoutData(legacy);
        config.setJideLayoutData(java.util.Map.of("dimension", legacy));
        assertNull(config.getDefaultModernDockingLayoutData());
        assertNull(config.getModernDockingLayoutData());
        config.setDefaultModernDockingLayoutData(modern);
        config.setModernDockingLayoutData(java.util.Map.of("dimension", modern));
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.ObjectOutputStream(bytes)) { out.writeObject(config); }
        org.pepsoft.worldpainter.Configuration copy;
        try (var in = new java.io.ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (org.pepsoft.worldpainter.Configuration) in.readObject();
        }
        assertArrayEquals(legacy, copy.getDefaultJideLayoutData());
        assertArrayEquals(legacy, copy.getJideLayoutData().get("dimension"));
        assertArrayEquals(modern, copy.getDefaultModernDockingLayoutData());
        assertArrayEquals(modern, copy.getModernDockingLayoutData().get("dimension"));
        copy.setDefaultModernDockingLayoutData(null); copy.setModernDockingLayoutData(null);
        assertArrayEquals(legacy, copy.getDefaultJideLayoutData());
        assertArrayEquals(legacy, copy.getJideLayoutData().get("dimension"));
    }
    @Test public void openSourceSwingWidgetsRetainSelectionAndTristateBehaviour() throws Exception {
        onEdt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            com.jidesoft.plaf.LookAndFeelFactory.installJideExtension(
                    com.jidesoft.plaf.LookAndFeelFactory.VSNET_STYLE_WITHOUT_MENU);
            var root = new javax.swing.tree.DefaultMutableTreeNode("Root");
            var first = new javax.swing.tree.DefaultMutableTreeNode("First");
            var second = new javax.swing.tree.DefaultMutableTreeNode("Second");
            root.add(first); root.add(second);
            var tree = new com.jidesoft.swing.CheckBoxTree(new javax.swing.tree.DefaultTreeModel(root));
            var path = new javax.swing.tree.TreePath(new Object[] {root, first});
            tree.getCheckBoxTreeSelectionModel().addSelectionPath(path);
            assertTrue(tree.getCheckBoxTreeSelectionModel().isPathSelected(path));
            assertFalse(tree.getCheckBoxTreeSelectionModel().isPathSelected(
                    new javax.swing.tree.TreePath(new Object[] {root, second})));
            var checkbox = new org.pepsoft.worldpainter.util.TristateCheckBox("Layer");
            checkbox.setState(com.jidesoft.swing.TristateCheckBox.STATE_MIXED);
            assertTrue(checkbox.isMixed());
            checkbox.setTristateMode(false);
            assertFalse(checkbox.isMixed());
            assertFalse(checkbox.isSelected());
            checkbox.doClick(); assertTrue(checkbox.isSelected());
            checkbox.doClick(); assertFalse(checkbox.isSelected());
            var label = new com.jidesoft.swing.JideLabel("Show");
            label.setOrientation(SwingConstants.VERTICAL);
            assertEquals(SwingConstants.VERTICAL, label.getOrientation());
        });
    }
    @Test public void codecRejectsExternalEntitiesAndRenamesOnlyDockingReferences() throws Exception {
        String xml = "<app-layout><simple persistentID='old' title-text='old'/><dockable id='old'/></app-layout>";
        byte[] renamed = DockLayoutCodec.rename(xml.getBytes(StandardCharsets.UTF_8), "old", "new & <id>");
        var doc = DockLayoutCodec.parse(renamed);
        var simple = (org.w3c.dom.Element) doc.getElementsByTagName("simple").item(0);
        assertEquals("new & <id>", simple.getAttribute("persistentID"));
        assertEquals("old", simple.getAttribute("title-text"));
        assertThrows(Exception.class, () -> DockLayoutCodec.parse(new byte[0]));
        assertThrows(Exception.class, () -> DockLayoutCodec.parse(new byte[DockLayoutCodec.MAX_BYTES + 1]));
        assertThrows(Exception.class, () -> DockLayoutCodec.parse("<other/>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> DockLayoutCodec.parse(
                "<!DOCTYPE app-layout [<!ENTITY x SYSTEM 'file:/never-read'>]><app-layout>&x;</app-layout>".getBytes(StandardCharsets.UTF_8)));
    }
}
