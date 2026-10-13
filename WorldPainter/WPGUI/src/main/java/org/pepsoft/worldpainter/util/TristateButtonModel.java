package org.pepsoft.worldpainter.util;

import javax.swing.JToggleButton;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;

/** Swing toggle model retaining the editor's three-state cycle and event order. */
public class TristateButtonModel extends JToggleButton.ToggleButtonModel {
    public void setTristateMode(boolean value) {
        tristateMode = value;
        if (!value && isMixed()) setState(TristateCheckBox.STATE_UNSELECTED);
    }

    public boolean isTristateMode() { return tristateMode; }
    public boolean isMixed() { return (stateMask & MIXED) != 0; }

    public int getState() {
        return isMixed() ? TristateCheckBox.STATE_MIXED
                : isSelected() ? TristateCheckBox.STATE_SELECTED : TristateCheckBox.STATE_UNSELECTED;
    }

    public void setState(int value) {
        switch (value) {
            case TristateCheckBox.STATE_UNSELECTED -> setSelected(false);
            case TristateCheckBox.STATE_SELECTED -> setSelected(true);
            case TristateCheckBox.STATE_MIXED -> setMixed(true);
            default -> { /* Retain the historical no-op for unknown states. */ }
        }
    }

    public void setMixed(boolean value) {
        if (isMixed() == value) return;
        if (value) stateMask |= MIXED | SELECTED;
        else stateMask &= ~MIXED;
        fireStateChanged();
    }

    @Override public void setSelected(boolean value) {
        if (isMixed()) {
            stateMask &= ~MIXED;
            // Leaving mixed must emit the same item event even when selected stays true.
            stateMask ^= SELECTED;
        }
        super.setSelected(value);
    }

    protected int getNextState(int current) {
        if (!tristateMode && isSelected()) return TristateCheckBox.STATE_UNSELECTED;
        return current == TristateCheckBox.STATE_UNSELECTED ? TristateCheckBox.STATE_SELECTED
                : current == TristateCheckBox.STATE_SELECTED ? TristateCheckBox.STATE_MIXED
                : TristateCheckBox.STATE_UNSELECTED;
    }

    @Override @SuppressWarnings("deprecation") public void setPressed(boolean value) {
        if (isPressed() == value || !isEnabled()) return;
        if (!value && isArmed()) setState(getNextState(getState()));
        if (value) stateMask |= PRESSED;
        else stateMask &= ~PRESSED;
        fireStateChanged();
        if (!isPressed() && isArmed()) {
            AWTEvent event = EventQueue.getCurrentEvent();
            int modifiers = event instanceof InputEvent ? ((InputEvent) event).getModifiers()
                    : event instanceof ActionEvent ? ((ActionEvent) event).getModifiers() : 0;
            fireActionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED,
                    getActionCommand(), EventQueue.getMostRecentEventTime(), modifiers));
        }
    }

    private boolean tristateMode = true;
    public static final int MIXED = 128;
    private static final long serialVersionUID = 1L;
}
