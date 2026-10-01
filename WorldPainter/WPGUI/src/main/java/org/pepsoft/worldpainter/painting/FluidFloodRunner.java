package org.pepsoft.worldpainter.painting;

import java.awt.Window;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.FluidFloodAccess;
import org.pepsoft.worldpainter.FluidFloodSession;
import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.util.swing.ProgressDialog;
import org.pepsoft.util.swing.ProgressTask;

/** Pilotage du remplissage fluide et reprise du parcours dans le dialogue de progression. */
public final class FluidFloodRunner {
    private FluidFloodRunner() { }
    /** null signifie repli Java ; false déclenche l'annulation des modifications existante. */
    public static Boolean tryFill(Dimension dimension, int x, int y, boolean inverse, boolean floodWithLava, String description, Window parent) {
        long started = System.nanoTime();
        if (FluidFloodAccess.tryFill(dimension, x, y, inverse, floodWithLava)) return true;
        FluidFloodSession session = FluidFloodSession.tryStart(dimension, x, y, inverse, floodWithLava);
        if (session == null) return null;
        while (!session.isComplete()) {
            session.advance();
            if (!session.isComplete() && System.nanoTime() - started > 2_000_000_000L) {
                Boolean result = ProgressDialog.executeTask(parent, new ProgressTask<Boolean>() {
                    @Override public String getName() { return description; }
                    @Override public Boolean execute(ProgressReceiver progress) throws ProgressReceiver.OperationCancelled {
                        while (!session.isComplete()) { progress.checkForCancellation(); session.advance(); }
                        return true;
                    }
                });
                return Boolean.TRUE.equals(result);
            }
        }
        return true;
    }

}
