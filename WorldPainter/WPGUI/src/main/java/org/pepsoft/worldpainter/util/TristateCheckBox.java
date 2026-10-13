package org.pepsoft.worldpainter.util;

import javax.swing.*;
import java.awt.*;

/** A Swing checkbox for single values and mixed values in multi-object selections. */
public final class TristateCheckBox extends JCheckBox {
    public TristateCheckBox() { this(null, null); }
    public TristateCheckBox(String text) { this(text, null); }
    public TristateCheckBox(String text, Icon icon) {
        super(text, icon);
        normalIcon = icon;
        setModel(new TristateButtonModel());
        getModel().addChangeListener(event -> refreshIcon());
    }

    public void setTristateMode(boolean value) { tristateModel().setTristateMode(value); }
    public boolean isTristateMode() { return tristateModel().isTristateMode(); }
    public boolean isMixed() { return getState() == STATE_MIXED; }
    public int getState() { return tristateModel().getState(); }
    public void setState(int value) { tristateModel().setState(value); refreshIcon(); }
    public void setMixed(boolean value) { setState(value ? STATE_MIXED : STATE_UNSELECTED); }

    @Override public void setSelected(boolean value) {
        // JCheckBox construction installs its ordinary model before ours exists.
        if (getModel() instanceof TristateButtonModel) setState(value ? STATE_SELECTED : STATE_UNSELECTED);
        else super.setSelected(value);
    }

    @Override public void setIcon(Icon icon) {
        if (getModel() instanceof TristateButtonModel) {
            normalIcon = icon;
            refreshIcon();
        } else super.setIcon(icon);
    }

    @Override public void updateUI() {
        super.updateUI();
        mixedIcon = null;
        if (getModel() instanceof TristateButtonModel) refreshIcon();
    }

    private TristateButtonModel tristateModel() {
        if (!(getModel() instanceof TristateButtonModel)) throw new IllegalStateException("TristateButtonModel is required");
        return (TristateButtonModel) getModel();
    }

    private void refreshIcon() {
        if (isMixed()) {
            if (mixedIcon == null) mixedIcon = new MixedIcon();
            super.setIcon(mixedIcon);
        } else super.setIcon(normalIcon);
    }

    private final class MixedIcon implements Icon {
        private final Icon border = UIManager.getIcon("CheckBox.icon");
        private final JCheckBox painter = new JCheckBox();
        public int getIconWidth() { return border == null ? 13 : border.getIconWidth(); }
        public int getIconHeight() { return border == null ? 13 : border.getIconHeight(); }

        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            painter.setEnabled(isEnabled());
            painter.setForeground(getForeground());
            painter.setBackground(getBackground());
            painter.getModel().setArmed(getModel().isArmed());
            painter.getModel().setPressed(getModel().isPressed());
            painter.getModel().setSelected(false);
            painter.getModel().setRollover(getModel().isRollover());
            if (border != null) border.paintIcon(painter, graphics, x, y);
            else {
                graphics.setColor(getBackground());
                graphics.fillRect(x, y, getIconWidth(), getIconHeight());
                graphics.setColor(getForeground());
                graphics.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
            }
            Color colour = isEnabled() ? getForeground() : UIManager.getColor("CheckBox.disabledText");
            graphics.setColor(colour == null ? getForeground() : colour);
            int width = Math.max(3, getIconWidth() - 6);
            graphics.fillRect(x + (getIconWidth() - width) / 2, y + getIconHeight() / 2 - 1, width, 3);
        }
    }

    private Icon normalIcon;
    private MixedIcon mixedIcon;
    public static final int STATE_UNSELECTED = 0, STATE_SELECTED = 1, STATE_MIXED = 2;
    private static final long serialVersionUID = 1L;
}
