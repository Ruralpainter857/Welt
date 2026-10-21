//! Sponge operation decisions for one brush stroke.

const MAX_SPONGE_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpongeError {
    TooManyCells,
    ActionLength { expected: usize, actual: usize },
}

pub const NO_CHANGE: i8 = 0;
pub const DRY_CELL: i8 = 1;
pub const RESET_FLUID: i8 = 2;

/// Produces the water/lava action for every Java-prepared brush strength.
/// Java applies the resulting actions through its normal undo-aware setters.
pub fn apply_sponge_brush(
    inverse: bool,
    water_height: i32,
    strengths: &[f32],
    actions: &mut [i8],
) -> Result<(), SpongeError> {
    if strengths.len() > MAX_SPONGE_CELLS {
        return Err(SpongeError::TooManyCells);
    }
    if actions.len() != strengths.len() {
        return Err(SpongeError::ActionLength {
            expected: strengths.len(),
            actual: actions.len(),
        });
    }

    actions.fill(NO_CHANGE);
    for (action, &strength) in actions.iter_mut().zip(strengths) {
        if strength != 0.0 {
            if inverse {
                if water_height != -1 {
                    *action = RESET_FLUID;
                }
            } else {
                *action = DRY_CELL;
            }
        }
    }
    Ok(())
}
