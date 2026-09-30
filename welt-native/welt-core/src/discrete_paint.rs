//! Threshold decisions for discrete layer brush painting.

const MAX_DISCRETE_PAINT_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiscretePaintError {
    TooManyCells,
    ModifiedLength,
}

/// Marks the cells painted by the non-dithered discrete layer brush.
///
/// Java still performs the layer writes so its undo and copy-on-write paths
/// remain in effect.
pub fn discrete_layer_paint_mask(
    strengths: &[f32],
    modified: &mut [i8],
) -> Result<(), DiscretePaintError> {
    if strengths.len() > MAX_DISCRETE_PAINT_CELLS {
        return Err(DiscretePaintError::TooManyCells);
    }
    if modified.len() != strengths.len() {
        return Err(DiscretePaintError::ModifiedLength);
    }

    for (strength, changed) in strengths.iter().zip(modified.iter_mut()) {
        *changed = i8::from(*strength > 0.75_f32);
    }
    Ok(())
}
