package org.pepsoft.worldpainter.util;

import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TristateCheckBoxTest {
    private static final class ReferenceModel extends com.jidesoft.swing.TristateButtonModel {
        boolean tristate = true;
        void mode(boolean value) {
            tristate = value;
            if (!value && isMixed()) setState(0);
        }
        @Override protected int getNextState(int current) {
            return !tristate && isSelected() ? 0 : super.getNextState(current);
        }
    }

    @Test public void preservesModelStatesAndEventOrder() {
        ReferenceModel old = new ReferenceModel();
        TristateButtonModel model = new TristateButtonModel();
        List<String> expected = events(old), actual = events(model);
        Random random = new Random(72815);
        for (int i = 0; i < 10000; i++) {
            int operation = random.nextInt(9), state = random.nextInt(5) - 1;
            boolean value = random.nextBoolean();
            switch (operation) {
                case 0 -> { old.setState(state); model.setState(state); }
                case 1 -> { old.setSelected(value); model.setSelected(value); }
                case 2 -> { old.setMixed(value); model.setMixed(value); }
                case 3 -> { old.setArmed(value); model.setArmed(value); }
                case 4 -> { old.setPressed(value); model.setPressed(value); }
                case 5 -> { old.setEnabled(value); model.setEnabled(value); }
                case 6 -> { old.mode(value); model.setTristateMode(value); }
                case 7 -> { old.setRollover(value); model.setRollover(value); }
                case 8 -> { old.setActionCommand("edit"); model.setActionCommand("edit"); }
            }
            assertEquals("operation " + i, old.getState(), model.getState());
            assertEquals(old.isSelected(), model.isSelected());
            assertEquals(old.isMixed(), model.isMixed());
            assertEquals("events after operation " + i, expected, actual);
            expected.clear(); actual.clear();
        }
    }

    private static List<String> events(JToggleButton.ToggleButtonModel model) {
        List<String> result = new ArrayList<>();
        model.addChangeListener(e -> result.add("change:" + model.isSelected() + ":" + model.isArmed() + ":" + model.isPressed() + ":" + model.isEnabled()));
        model.addItemListener(e -> result.add("item:" + e.getStateChange()));
        model.addActionListener(e -> result.add("action:" + e.getActionCommand() + ":" + e.getModifiers()));
        return result;
    }

    private static void onEdt(Runnable check) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { check.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test public void retainsMouseKeyboardAndBinaryCycles() throws Exception {
        onEdt(() -> {
            TristateCheckBox box = new TristateCheckBox("Layer");
            assertEquals(0, box.getState());
            for (int state : new int[] {1, 2, 0, 1, 2, 0}) {
                box.doClick(0); assertEquals(state, box.getState());
            }
            box.setMixed(true); assertTrue(box.isSelected());
            box.setTristateMode(false); assertEquals(0, box.getState());
            for (int state : new int[] {1, 0, 1, 0}) {
                box.doClick(0); assertEquals(state, box.getState());
            }
            box.setEnabled(false); box.doClick(0); assertEquals(0, box.getState());
            box.setEnabled(true);
            box.getActionMap().get("pressed").actionPerformed(new java.awt.event.ActionEvent(box, 0, ""));
            box.getActionMap().get("released").actionPerformed(new java.awt.event.ActionEvent(box, 0, ""));
            assertEquals(1, box.getState());
            box.setMixed(true); box.setSelected(true); assertFalse(box.isMixed());
            box.setMixed(false); assertFalse(box.isSelected());
        });
    }

    @Test public void paintsMixedStateAndRetainsSwingDimensionsAcrossUiUpdates() throws Exception {
        onEdt(() -> {
            for (String name : new String[] {"javax.swing.plaf.metal.MetalLookAndFeel", "javax.swing.plaf.nimbus.NimbusLookAndFeel"}) {
                LookAndFeel original = UIManager.getLookAndFeel();
                try {
                    UIManager.setLookAndFeel(name);
                    TristateCheckBox box = new TristateCheckBox("Layer");
                    JCheckBox plain = new JCheckBox("Layer");
                    assertEquals(plain.getPreferredSize(), box.getPreferredSize());
                    box.setMixed(true);
                    Icon icon = box.getIcon(); assertNotNull(icon);
                    for (boolean enabled : new boolean[] {false, true}) {
                        box.setEnabled(enabled);
                        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g = image.createGraphics();
                        try { icon.paintIcon(box, g, 0, 0); } finally { g.dispose(); }
                        int painted = 0;
                        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                            if ((image.getRGB(x, y) >>> 24) != 0) painted++;
                        }
                        assertTrue("Missing mixed indicator", painted > 8);                        Color colour = enabled ? box.getForeground() : UIManager.getColor("CheckBox.disabledText");
                        if (colour == null) colour = box.getForeground();
                        assertEquals("Missing centre dash", colour.getRGB(), image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
                    }
                    box.updateUI(); assertTrue(box.isMixed()); assertNotNull(box.getIcon());
                    box.setSelected(false); assertNull(box.getIcon());
                    assertEquals(plain.getPreferredSize(), box.getPreferredSize());
                    Icon custom = new ImageIcon(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB));
                    box.setIcon(custom); box.setMixed(true); box.setSelected(true);
                    assertSame(custom, box.getIcon());
                } catch (Exception e) { throw new AssertionError(e); }
                finally { try { UIManager.setLookAndFeel(original); } catch (Exception e) { throw new AssertionError(e); } }
            }
        });
    }
}
