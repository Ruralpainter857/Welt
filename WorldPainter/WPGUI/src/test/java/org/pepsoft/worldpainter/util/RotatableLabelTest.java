package org.pepsoft.worldpainter.util;

import com.jidesoft.plaf.basic.BasicJideLabelUI;
import com.jidesoft.swing.JideLabel;
import org.junit.Test;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class RotatableLabelTest {
    private static void onEdt(Runnable check) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { check.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test public void matchesExistingVerticalLabels() throws Exception {
        onEdt(() -> {
            for (String text : new String[] {"Show", "Solo", "Opacity"}) {
                for (boolean clockwise : new boolean[] {false, true}) {
                    for (boolean enabled : new boolean[] {false, true}) {
                        compare(text, clockwise, enabled, false);
                    }
                }
            }
        });
    }

    @Test public void preservesHtmlIconsAndClippedText() throws Exception {
        onEdt(() -> {
            for (String text : new String[] {"<html><b>Show</b><br>layers</html>", "A longer clipped label"}) {
                for (boolean clockwise : new boolean[] {false, true}) compare(text, clockwise, true, true);
            }
        });
    }

    private static void compare(String text, boolean clockwise, boolean enabled, boolean clipped) {
        JideLabel reference = new JideLabel(text);
        reference.setUI(new BasicJideLabelUI());
        RotatableLabel replacement = new RotatableLabel(text);
        reference.setOrientation(SwingConstants.VERTICAL);
        replacement.setOrientation(SwingConstants.VERTICAL);
        reference.setClockwise(clockwise);
        replacement.setClockwise(clockwise);
        for (JLabel label : new JLabel[] {reference, replacement}) {
            label.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
            label.setForeground(Color.BLACK);
            label.setBackground(Color.WHITE);
            label.setOpaque(true);
            label.setEnabled(enabled);
            label.setBorder(new EmptyBorder(2, 3, 4, 5));
            label.setDisplayedMnemonic('o');
            if (clipped) {
                label.setIcon(new Icon() {
                    public int getIconWidth() { return 6; }
                    public int getIconHeight() { return 8; }
                    public void paintIcon(Component c, Graphics g, int x, int y) {
                        g.setColor(Color.RED); g.fillRect(x, y, 6, 8);
                    }
                });
            }
        }
        assertEquals(reference.getPreferredSize(), replacement.getPreferredSize());
        Dimension size = reference.getPreferredSize();
        size.width += 9;
        size.height = clipped ? 28 : size.height + 11;
        BufferedImage expected = paint(reference, size), actual = paint(replacement, size);
        for (int y = 0; y < size.height; y++) {
            for (int x = 0; x < size.width; x++) {
                assertEquals(text + " clockwise=" + clockwise + " enabled=" + enabled + " pixel=" + x + "," + y,
                        expected.getRGB(x, y), actual.getRGB(x, y));
            }
        }
    }

    private static BufferedImage paint(JLabel label, Dimension size) {
        label.setSize(size);
        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { label.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    @Test public void retainsStockHorizontalUiAfterLookAndFeelUpdates() throws Exception {
        onEdt(() -> {
            RotatableLabel label = new RotatableLabel("Show");
            JLabel stock = new JLabel("Show");
            assertEquals(stock.getUI().getClass(), label.getUI().getClass());
            Dimension size = label.getPreferredSize();
            label.setOrientation(SwingConstants.VERTICAL);
            label.updateUI();
            assertEquals(new Dimension(size.height, size.width), label.getPreferredSize());
            assertEquals(-1, label.getBaseline(30, 70));
            label.setOrientation(SwingConstants.HORIZONTAL);
            assertEquals(stock.getUI().getClass(), label.getUI().getClass());
            assertEquals(size, label.getPreferredSize());
            try { label.setOrientation(9); fail("Invalid orientation accepted"); }
            catch (IllegalArgumentException expected) { }
        });
    }
}
