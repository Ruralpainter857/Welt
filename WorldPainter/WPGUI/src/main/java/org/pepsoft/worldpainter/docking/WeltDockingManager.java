package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.Dockable;
import io.github.andrewauclair.moderndocking.DockableStyle;
import io.github.andrewauclair.moderndocking.DockingRegion;
import io.github.andrewauclair.moderndocking.app.Docking;
import io.github.andrewauclair.moderndocking.app.RootDockingPanel;
import java.awt.*;
import java.io.*;
import java.util.*;
import javax.swing.*;
import javax.xml.stream.XMLStreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns tool registration and application layouts, including floating windows. */
public final class WeltDockingManager implements AutoCloseable {
    public WeltDockingManager(JFrame window, JPanel container, Component view) {
        requireEdt();
        this.window = window;
        Docking.initialize(window);
        RootDockingPanel root = new RootDockingPanel(window);
        container.add(root, BorderLayout.CENTER);
        workspace = new Workspace(view);
        Docking.registerDockable(workspace);
        Docking.dock(workspace, window);
    }

    public void addFrame(WeltDockPanel panel) {
        requireEdt();
        if (panels.containsKey(panel.getKey())) throw new IllegalArgumentException("Duplicate panel ID");
        Docking.registerDockable(panel);
        panels.put(panel.getKey(), panel);
        panel.attach(this);
        dockFrame(panel.getKey(), panel.getSide(), panel.getGroup());
    }

    public void removeFrame(String key) {
        requireEdt();
        WeltDockPanel panel = panels.remove(key);
        if (panel != null) {
            Docking.undock(panel);
            Docking.deregisterDockable(panel);
            panel.attach(null);
        }
    }

    public WeltDockPanel getFrame(String key) { return panels.get(key); }
    public void showFrame(String key) { activateFrame(key); }
    public void activateFrame(String key) {
        requireEdt();
        WeltDockPanel panel = Objects.requireNonNull(panels.get(key), "Unknown panel");
        if (!Docking.isDocked(panel) && !Docking.isHidden(panel)) dockFrame(key, panel.getSide(), panel.getGroup());
        Docking.bringToFront(panel);
    }

    public void dockFrame(String key, WeltDockPanel.Side side, int group) {
        requireEdt();
        WeltDockPanel panel = Objects.requireNonNull(panels.get(key), "Unknown panel");
        WeltDockPanel peer = null, preceding = null;
        for (WeltDockPanel candidate : panels.values()) {
            if (candidate == panel || candidate.getSide() != side || !Docking.isDocked(candidate)) continue;
            if (candidate.getGroup() == group) { peer = candidate; break; }
            if (candidate.getGroup() < group && (preceding == null || candidate.getGroup() > preceding.getGroup())) preceding = candidate;
        }
        if (peer != null) Docking.dock(panel, peer, DockingRegion.CENTER);
        else if (preceding != null) Docking.dock(panel, preceding, DockingRegion.SOUTH, 0.5);
        else Docking.dock(panel, window, side == WeltDockPanel.Side.WEST ? DockingRegion.WEST : DockingRegion.EAST, 0.2);
    }

    void renameFrame(WeltDockPanel panel, String key) {
        requireEdt();
        if (panel.getKey().equals(key)) return;
        if (panels.containsKey(key)) throw new IllegalArgumentException("Duplicate panel ID");
        final byte[] renamedLayout;
        try {
            renamedLayout = DockLayoutCodec.rename(getLayoutRawData(), panel.getKey(), key);
        } catch (Exception e) {
            throw new IllegalStateException("Could not preserve the renamed panel layout", e);
        }
        Docking.undock(panel);
        Docking.deregisterDockable(panel);
        panels.remove(panel.getKey());
        panel.changeKey(key);
        panels.put(key, panel);
        Docking.registerDockable(panel);
        loadLayoutFrom(new ByteArrayInputStream(renamedLayout));
    }

    public byte[] getLayoutRawData() {
        requireEdt();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Docking.getSingleInstance().getLayoutPersistence().saveLayoutToOutputStream(out, Docking.getSingleInstance().getDockingState().getApplicationLayout());
            return out.toByteArray();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not save docking layout", e);
        }
    }

    public void loadLayoutFrom(InputStream in) {
        requireEdt();
        try {
            byte[] bytes = in.readNBytes(DockLayoutCodec.MAX_BYTES + 1);
            DockLayoutCodec.parse(bytes);
            var layout = Docking.getSingleInstance().getLayoutPersistence()
                    .loadApplicationLayoutFromInputStream(new ByteArrayInputStream(bytes));
            Docking.getSingleInstance().getDockingState().restoreApplicationLayout(layout);
        } catch (Exception e) {
            LOGGER.warn("Could not restore docking layout; using the default layout", e);
            resetToDefault();
        }
    }

    public void resetToDefault() {
        requireEdt();
        for (WeltDockPanel panel : panels.values()) Docking.undock(panel);
        java.util.List<WeltDockPanel> ordered = new ArrayList<>(panels.values());
        ordered.sort(Comparator.comparing(WeltDockPanel::getSide).thenComparingInt(WeltDockPanel::getGroup));
        for (WeltDockPanel panel : ordered) dockFrame(panel.getKey(), panel.getSide(), panel.getGroup());
    }

    /** Release registered panels before their native windows lose the docking roots. */
    @Override public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        java.util.List<Window> windows = new ArrayList<>(Docking.getRootPanels().keySet());
        for (WeltDockPanel panel : new ArrayList<>(panels.values())) removeFrame(panel.getKey());
        Docking.deregisterDockable(workspace);
        Docking.uninitialize();
        for (Window dockWindow : windows) if (dockWindow != window) dockWindow.dispose();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Docking changes require the Swing event thread");
    }
    private static final class Workspace extends JPanel implements Dockable {
        Workspace(Component view) {
            super(new BorderLayout());
            setMinimumSize(new java.awt.Dimension(200, 120));
            add(view, BorderLayout.CENTER);
        }
        @Override public String getPersistentID() { return "welt.workspace"; }
        @Override public String getTabText() { return "World"; }
        @Override public boolean requestClose() { return false; }
        @Override public boolean isFloatingAllowed() { return false; }
        @Override public boolean isLimitedToWindow() { return true; }
        @Override public DockableStyle getStyle() { return DockableStyle.CENTER_ONLY; }
        @Override public io.github.andrewauclair.moderndocking.ui.DockingHeaderUI createHeaderUI(
                io.github.andrewauclair.moderndocking.ui.HeaderController controller,
                io.github.andrewauclair.moderndocking.ui.HeaderModel model) {
            WeltDockHeader header = new WeltDockHeader(controller, model);
            // The canvas has no draggable titlebar; only the surrounding tool panels move.
            header.setVisible(false);
            header.setPreferredSize(new java.awt.Dimension(0, 0));
            return header;
        }
    }
    private boolean closed;
    private final JFrame window;
    private final Workspace workspace;
    private final Map<String, WeltDockPanel> panels = new LinkedHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(WeltDockingManager.class);
}
