package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.Dockable;
import io.github.andrewauclair.moderndocking.app.Docking;
import javax.swing.*;
import java.awt.*;

/** Application-owned panel metadata; docking implementation stays outside the tool panels. */
public final class WeltDockPanel extends JPanel implements Dockable {
    public enum Side { WEST, EAST }

    public WeltDockPanel(String key, String title, Side side, int group) {
        super(new BorderLayout());
        this.key = key;
        this.title = title;
        this.side = side;
        this.group = group;
        // Child widget minimum widths must not squeeze the central canvas on small screens.
        setMinimumSize(new Dimension(160, 60));
    }

    @Override public String getPersistentID() { return key; }
    @Override public String getTabText() { return title; }
    @Override public String getTabTooltip() { return getToolTipText(); }
    @Override public Icon getIcon() { return icon; }
    // Modern Docking must be able to detach panels internally during layout restoration.
    // User closure is rejected separately and omitted from the application header.
    @Override public boolean requestClose() { return false; }
    @Override public io.github.andrewauclair.moderndocking.ui.DockingHeaderUI createHeaderUI(
            io.github.andrewauclair.moderndocking.ui.HeaderController controller,
            io.github.andrewauclair.moderndocking.ui.HeaderModel model) {
        return new WeltDockHeader(controller, model);
    }
    @Override public boolean isAutoHideAllowed() { return true; }
    public String getKey() { return key; }
    public Side getSide() { return side; }
    public int getGroup() { return group; }

    public void setTitle(String title) {
        this.title = title;
        if (Docking.isDockableRegistered(key)) Docking.updateTabInfo(this);
    }
    public void setFrameIcon(Icon icon) {
        this.icon = icon;
        if (Docking.isDockableRegistered(key)) Docking.updateTabInfo(this);
    }
    public void setKey(String key) {
        if (manager == null) this.key = key;
        else manager.renameFrame(this, key);
    }
    void changeKey(String key) { this.key = key; }
    void attach(WeltDockingManager manager) { this.manager = manager; }

    private String key, title;
    private Icon icon;
    private final Side side;
    private final int group;
    private WeltDockingManager manager;
}
