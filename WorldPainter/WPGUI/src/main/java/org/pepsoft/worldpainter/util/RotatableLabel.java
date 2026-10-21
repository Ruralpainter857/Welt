package org.pepsoft.worldpainter.util;

import javax.swing.*;
import javax.swing.plaf.basic.BasicLabelUI;
import javax.swing.text.View;
import java.awt.*;

/** A Swing label with optional quarter-turn text and icon orientation. */
public final class RotatableLabel extends JLabel {
    public RotatableLabel() { super(); }
    public RotatableLabel(String text) { super(text); }

    public int getOrientation() { return orientation; }
    public boolean isClockwise() { return clockwise; }

    public void setOrientation(int value) {
        if (value != SwingConstants.HORIZONTAL && value != SwingConstants.VERTICAL) {
            throw new IllegalArgumentException("Invalid label orientation");
        }
        if (orientation != value) {
            int old = orientation;
            orientation = value;
            updateUI();
            firePropertyChange("orientation", old, value);
            revalidate();
            repaint();
        }
    }

    public void setClockwise(boolean value) {
        if (clockwise != value) {
            boolean old = clockwise;
            clockwise = value;
            firePropertyChange("clockwise", old, value);
            repaint();
        }
    }

    @Override public void updateUI() {
        if (orientation == SwingConstants.VERTICAL) setUI(new VerticalLabelUI());
        else super.updateUI();
    }

    private int orientation = SwingConstants.HORIZONTAL;
    private boolean clockwise = true;
    private static final long serialVersionUID = 1L;

    private static final class VerticalLabelUI extends BasicLabelUI {
        private final Rectangle view = new Rectangle(), iconBounds = new Rectangle(), textBounds = new Rectangle();
        private final Insets insets = new Insets(0, 0, 0, 0);

        @Override public Dimension getPreferredSize(JComponent component) {
            Dimension size = super.getPreferredSize(component);
            return new Dimension(size.height, size.width);
        }

        @Override public Dimension getMinimumSize(JComponent component) {
            return htmlSize(component, true);
        }

        @Override public Dimension getMaximumSize(JComponent component) {
            return htmlSize(component, false);
        }

        private Dimension htmlSize(JComponent component, boolean minimum) {
            Dimension size = getPreferredSize(component);
            View html = (View) component.getClientProperty("html");
            if (html != null) {
                float width = minimum ? html.getMinimumSpan(View.X_AXIS) : html.getMaximumSpan(View.X_AXIS);
                size.height += (int) (width - html.getPreferredSpan(View.X_AXIS));
            }
            return size;
        }

        @Override public int getBaseline(JComponent component, int width, int height) {
            super.getBaseline(component, width, height);
            return -1;
        }

        @Override public Component.BaselineResizeBehavior getBaselineResizeBehavior(JComponent component) {
            return Component.BaselineResizeBehavior.OTHER;
        }

        @Override public void paint(Graphics graphics, JComponent component) {
            RotatableLabel label = (RotatableLabel) component;
            String text = label.getText();
            Icon icon = label.isEnabled() ? label.getIcon() : label.getDisabledIcon();
            if (text == null && icon == null) return;
            FontMetrics metrics = label.getFontMetrics(label.getFont());
            label.getInsets(insets);
            // Keep the horizontal layout convention used by existing vertical labels.
            view.setBounds(insets.left, insets.top,
                    label.getHeight() - insets.top - insets.bottom,
                    label.getWidth() - insets.left - insets.right);
            iconBounds.setBounds(0, 0, 0, 0);
            textBounds.setBounds(0, 0, 0, 0);
            String clipped = layoutCL(label, metrics, text, icon, view, iconBounds, textBounds);
            Graphics2D rotated = (Graphics2D) graphics.create();
            try {
                if (label.isClockwise()) {
                    rotated.rotate(Math.PI / 2);
                    rotated.translate(0, -label.getWidth());
                } else {
                    rotated.rotate(-Math.PI / 2);
                    rotated.translate(-label.getHeight(), 0);
                }
                if (icon != null) icon.paintIcon(label, rotated, iconBounds.x, iconBounds.y);
                if (text != null) {
                    View html = (View) label.getClientProperty("html");
                    if (html != null) html.paint(rotated, textBounds);
                    else if (label.isEnabled()) paintEnabledText(label, rotated, clipped, textBounds.x, textBounds.y + metrics.getAscent());
                    else paintDisabledText(label, rotated, clipped, textBounds.x, textBounds.y + metrics.getAscent());
                }
            } finally {
                rotated.dispose();
            }
        }
    }
}
