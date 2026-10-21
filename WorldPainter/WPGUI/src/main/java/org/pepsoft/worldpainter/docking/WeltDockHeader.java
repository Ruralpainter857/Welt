package org.pepsoft.worldpainter.docking;

import io.github.andrewauclair.moderndocking.ui.DockingHeaderUI;
import io.github.andrewauclair.moderndocking.ui.HeaderController;
import io.github.andrewauclair.moderndocking.ui.HeaderModel;
import java.awt.*;
import javax.swing.*;

/** Tool panels can float or auto-hide; their header deliberately offers no Close action. */
final class WeltDockHeader extends JPanel implements DockingHeaderUI {
    WeltDockHeader(HeaderController controller, HeaderModel model) {
        super(new BorderLayout(4, 0));
        this.controller = controller;
        this.model = model;
        controller.setUI(this);
        setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 2));
        add(title, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        for (JButton button : new JButton[] {floating, pin}) {
            button.setFocusable(false);
            button.setMargin(new Insets(0, 3, 0, 3));
            actions.add(button);
        }
        floating.setToolTipText("Float panel");
        floating.getAccessibleContext().setAccessibleName("Float panel");
        floating.addActionListener(e -> controller.newWindow());
        pin.setToolTipText("Automatically hide panel");
        pin.getAccessibleContext().setAccessibleName("Automatically hide panel");
        pin.addActionListener(e -> controller.toggleAutoHide());
        add(actions, BorderLayout.EAST);
        title.setText(model.titleText());
        title.setIcon(model.icon());
    }
    @Override public void update() {
        title.setText(model.titleText());
        title.setIcon(model.icon());
        floating.setVisible(model.isFloatingAllowed());
        pin.setVisible(model.isAutoHideAllowed());
        pin.setText(model.isAutoHideEnabled() ? "●" : "○");
        revalidate(); repaint();
    }
    @Override public void displaySettingsMenu(JButton button) {
        JPopupMenu menu = new JPopupMenu();
        if (model.isFloatingAllowed()) {
            JMenuItem item = new JMenuItem("Float panel");
            item.addActionListener(e -> controller.newWindow()); menu.add(item);
        }
        if (model.isAutoHideAllowed()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem("Automatically hide panel", model.isAutoHideEnabled());
            item.addActionListener(e -> controller.toggleAutoHide()); menu.add(item);
        }
        model.addMoreOptions(menu);
        menu.show(button, 0, button.getHeight());
    }
    @Override public void setBackgroundOverride(Color color) {
        setBackground(color == null ? UIManager.getColor("Panel.background") : color);
    }
    @Override public void setForegroundOverride(Color color) {
        title.setForeground(color == null ? UIManager.getColor("Label.foreground") : color);
    }
    private final HeaderController controller;
    private final HeaderModel model;
    private final JLabel title = new JLabel();
    private final JButton floating = new JButton("↗"), pin = new JButton("○");
}
