package org.pepsoft.worldpainter.util;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;

/** Reuses one checkbox and delegates item icons/text to the application's renderer. */
public final class CheckBoxTreeCellRenderer extends JPanel implements TreeCellRenderer {
    public CheckBoxTreeCellRenderer() {
        super(new BorderLayout());
        setOpaque(false);
        checkbox.setOpaque(false);
        checkbox.setFocusable(false);
        add(checkbox, BorderLayout.LINE_START);
    }

    public void setActualTreeRenderer(TreeCellRenderer value) {
        if (value == null) throw new IllegalArgumentException("A tree renderer is required");
        actual = value;
    }
    public int getCheckBoxWidth() { return checkbox.getPreferredSize().width; }
    void refreshLookAndFeel() {
        checkbox.updateUI();
        if (actual instanceof JComponent) ((JComponent) actual).updateUI();
    }

    @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                           boolean expanded, boolean leaf, int row, boolean focus) {
        CheckBoxTreeSelectionModel model = ((CheckBoxTree) tree).getCheckBoxTreeSelectionModel();
        TreePath path = tree.getPathForRow(row);
        checkbox.setState(model.isPathSelected(path, true) ? TristateCheckBox.STATE_SELECTED
                : model.isPartiallySelected(path) ? TristateCheckBox.STATE_MIXED : TristateCheckBox.STATE_UNSELECTED);
        checkbox.setEnabled(tree.isEnabled());
        checkbox.setFont(tree.getFont());
        checkbox.setForeground(tree.getForeground());
        checkbox.setBackground(tree.getBackground());
        setComponentOrientation(tree.getComponentOrientation());
        Component label = actual.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
        if (label != lastLabel) {
            if (lastLabel != null) remove(lastLabel);
            add(label, BorderLayout.CENTER);
            lastLabel = label;
        }
        return this;
    }

    private final TristateCheckBox checkbox = new TristateCheckBox();
    private TreeCellRenderer actual = new DefaultTreeCellRenderer();
    private Component lastLabel;
    private static final long serialVersionUID = 1L;
}
