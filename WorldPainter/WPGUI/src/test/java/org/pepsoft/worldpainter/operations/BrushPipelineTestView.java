package org.pepsoft.worldpainter.operations;

import org.pepsoft.worldpainter.BrushControl;
import org.pepsoft.worldpainter.ColourScheme;
import org.pepsoft.worldpainter.MapDragControl;
import org.pepsoft.worldpainter.WorldPainter;

/** Real Swing view for offline brush calls without creating the application singleton. */
final class BrushPipelineTestView extends WorldPainter {
    BrushPipelineTestView() {
        super(ColourScheme.DEFAULT, null);
    }

    // These tests invoke computation directly and never activate input handlers.
    @Override public BrushControl getBrushControl() { return null; }
    @Override public MapDragControl getMapDragControl() { return null; }
}
