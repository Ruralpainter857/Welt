//! Brush-based height edits shared by the native WorldPainter adapters.

const MAX_HEIGHT_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeightEditError {
    TooManyCells,
    HeightLength { expected: usize, actual: usize },
    StrengthLength { expected: usize, actual: usize },
    ModifiedLength { expected: usize, actual: usize },
}

/// Applies WorldPainter's raise/lower height brush to one row-major brush area.
///
/// The caller prepares current heights and brush strengths in Java order. The
/// modified plane lets Java preserve its normal undo-aware writes and theme updates.
pub fn apply_height_brush(
    inverse: bool,
    min_height: f32,
    max_height: f32,
    adjustment: f32,
    heights: &mut [f32],
    strengths: &[f32],
    modified: &mut [i8],
) -> Result<(), HeightEditError> {
    if heights.len() > MAX_HEIGHT_CELLS {
        return Err(HeightEditError::TooManyCells);
    }
    if strengths.len() != heights.len() {
        return Err(HeightEditError::StrengthLength {
            expected: heights.len(),
            actual: strengths.len(),
        });
    }
    if modified.len() != heights.len() {
        return Err(HeightEditError::ModifiedLength {
            expected: heights.len(),
            actual: modified.len(),
        });
    }

    modified.fill(0);
    for index in 0..heights.len() {
        let current_height = heights[index];
        let target_height = if inverse {
            java_max(current_height - adjustment, min_height)
        } else {
            java_min(current_height + adjustment, max_height)
        };
        let strength = strengths[index];
        if strength > 0.0_f32 {
            let new_height = strength * target_height + (1.0_f32 - strength) * current_height;
            if if inverse {
                new_height < current_height
            } else {
                new_height > current_height
            } {
                heights[index] = new_height;
                modified[index] = 1;
            }
        }
    }
    Ok(())
}

fn java_max(left: f32, right: f32) -> f32 {
    if left.is_nan() || right.is_nan() {
        f32::NAN
    } else if left == 0.0_f32 && right == 0.0_f32 {
        if left.is_sign_positive() || right.is_sign_positive() {
            0.0_f32
        } else {
            -0.0_f32
        }
    } else if left > right {
        left
    } else {
        right
    }
}

fn java_min(left: f32, right: f32) -> f32 {
    if left.is_nan() || right.is_nan() {
        f32::NAN
    } else if left == 0.0_f32 && right == 0.0_f32 {
        if left.is_sign_negative() || right.is_sign_negative() {
            -0.0_f32
        } else {
            0.0_f32
        }
    } else if left < right {
        left
    } else {
        right
    }
}
