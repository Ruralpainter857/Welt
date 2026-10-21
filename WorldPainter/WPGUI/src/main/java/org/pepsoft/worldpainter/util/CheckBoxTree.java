package org.pepsoft.worldpainter.util;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;

/** Swing tree with checked-branch selection independent of keyboard row navigation. */
public final class CheckBoxTree extends JTree {
    public CheckBoxTree() { this(new DefaultTreeModel(new DefaultMutableTreeNode("Root"))); }
    public CheckBoxTree(TreeModel model) {
        super(model);
        checked = new CheckBoxTreeSelectionModel(model);
        checked.addTreeSelectionListener(event -> repaint());
        getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        setCellRenderer(new CheckBoxTreeCellRenderer());
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "toggle-check");
        getActionMap().put("toggle-check", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (CheckBoxTree.this.isEnabled()) toggle(getLeadSelectionPath());
            }
        });
    }

    public CheckBoxTreeSelectionModel getCheckBoxTreeSelectionModel() { return checked; }
    @Override public void setModel(TreeModel model) {
        super.setModel(model);
        if (checked != null) checked.setModel(model);
    }
    @Override public void updateUI() {
        super.updateUI();
        if (getCellRenderer() instanceof CheckBoxTreeCellRenderer) {
            ((CheckBoxTreeCellRenderer) getCellRenderer()).refreshLookAndFeel();
        }
    }

    private void toggle(TreePath path) {
        if (path == null) return;
        if (checked.isPathSelected(path, true)) checked.removeSelectionPath(path);
        else checked.addSelectionPath(path);
    }

    @Override protected void processMouseEvent(MouseEvent event) {
        if (checked != null && isEnabled() && SwingUtilities.isLeftMouseButton(event)) {
            TreePath path = getPathForLocation(event.getX(), event.getY());
            Rectangle bounds = path == null ? null : getPathBounds(path);
            int width = ((CheckBoxTreeCellRenderer) getCellRenderer()).getCheckBoxWidth();
            boolean inCheckbox = bounds != null && (getComponentOrientation().isLeftToRight()
                    ? event.getX() < bounds.x + width : event.getX() >= bounds.x + bounds.width - width);
            if (inCheckbox) {
                if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                    requestFocusInWindow();
                    setSelectionPath(path);
                    toggle(path);
                }
                event.consume();
                return;
            }
        }
        super.processMouseEvent(event);
    }

    private final CheckBoxTreeSelectionModel checked;
    private static final long serialVersionUID = 1L;
}
