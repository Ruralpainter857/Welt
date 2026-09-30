//! Brush-sized height smoothing using WorldPainter's 11 by 11 neighborhood.

const MAX_SMOOTH_CELLS: usize = 65_536;
const EMPTY_HEIGHT: f32 = -f32::MAX;
const NEIGHBORHOOD_RADIUS: usize = 5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SmoothHeightError {
    InvalidDimensions,
    AreaOverflow,
    TooManyCells,
    InputLength { expected: usize, actual: usize },
    StrengthLength { expected: usize, actual: usize },
    OutputLength { expected: usize, actual: usize },
    ModifiedLength { expected: usize, actual: usize },
}

/// Computes one Java-compatible smoothing pass over a row-major height view.
///
/// The input view includes a five-cell halo on every side of the output area.
/// Java applies output heights through its normal setters after this calculation.
pub fn smooth_height_region(
    input_width: usize,
    input_height: usize,
    heights: &[f32],
    strengths: &[f32],
    output: &mut [f32],
    modified: &mut [i8],
) -> Result<(), SmoothHeightError> {
    if input_width < 11 || input_height < 11 {
        return Err(SmoothHeightError::InvalidDimensions);
    }
    let input_area = input_width
        .checked_mul(input_height)
        .ok_or(SmoothHeightError::AreaOverflow)?;
    let output_width = input_width - 10;
    let output_height = input_height - 10;
    let output_area = output_width
        .checked_mul(output_height)
        .ok_or(SmoothHeightError::AreaOverflow)?;
    if input_area > MAX_SMOOTH_CELLS || output_area > MAX_SMOOTH_CELLS {
        return Err(SmoothHeightError::TooManyCells);
    }
    if heights.len() != input_area {
        return Err(SmoothHeightError::InputLength {
            expected: input_area,
            actual: heights.len(),
        });
    }
    if strengths.len() != output_area {
        return Err(SmoothHeightError::StrengthLength {
            expected: output_area,
            actual: strengths.len(),
        });
    }
    if output.len() != output_area {
        return Err(SmoothHeightError::OutputLength {
            expected: output_area,
            actual: output.len(),
        });
    }
    if modified.len() != output_area {
        return Err(SmoothHeightError::ModifiedLength {
            expected: output_area,
            actual: modified.len(),
        });
    }

    modified.fill(0);
    for x in 0..output_width {
        for y in 0..output_height {
            let strength = strengths[x * output_height + y];
            if strength.is_nan() || strength <= 0.0_f32 {
                continue;
            }
            let mut total = 0.0_f32;
            let mut sample_count = 0_u32;
            for sample_x in x..=x + NEIGHBORHOOD_RADIUS * 2 {
                for sample_y in y..=y + NEIGHBORHOOD_RADIUS * 2 {
                    let height = heights[sample_x * input_width + sample_y];
                    if height != EMPTY_HEIGHT {
                        total += height;
                        sample_count += 1;
                    }
                }
            }
            let center = heights[(x + NEIGHBORHOOD_RADIUS) * input_width + y + NEIGHBORHOOD_RADIUS];
            let current_height = if center == EMPTY_HEIGHT {
                0.0_f32
            } else {
                center
            };
            let average = total / sample_count as f32;
            output[x * output_height + y] =
                strength * average + (1.0_f32 - strength) * current_height;
            modified[x * output_height + y] = 1;
        }
    }
    Ok(())
}
