//! Threshold decisions for layer brush painting.

const MAX_PAINT_MASK_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PaintMaskError {
    TooManyCells,
    ModifiedLength,
}

/// Marks the cells painted by a non-dithered layer brush.
///
/// Java still performs the layer writes so its undo and copy-on-write paths
/// remain in effect.
pub fn paint_threshold_mask(strengths: &[f32], modified: &mut [i8]) -> Result<(), PaintMaskError> {
    if strengths.len() > MAX_PAINT_MASK_CELLS {
        return Err(PaintMaskError::TooManyCells);
    }
    if modified.len() != strengths.len() {
        return Err(PaintMaskError::ModifiedLength);
    }

    for (strength, changed) in strengths.iter().zip(modified.iter_mut()) {
        *changed = i8::from(*strength > 0.75_f32);
    }
    Ok(())
}
