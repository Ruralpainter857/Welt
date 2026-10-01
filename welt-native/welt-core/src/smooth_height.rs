//! Brush-sized height smoothing using WorldPainter's 11 by 11 neighborhood.

const MAX_SMOOTH_CELLS: usize = 65_536;
const EMPTY_HEIGHT: f32 = -f32::MAX;
const NEIGHBORHOOD_RADIUS: usize = 5;
pub const COMPACT_MAX_BYTES: usize = 48 + 4 * 65536 + 9 * 246 * 246;

// WLSM v1 : en-tête LE, instantané f32 avec bordure, forces f32, sortie brute i32 et masque u8.
pub fn edit_compact(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn get(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(get(data, offset) as u32)
    }
    if data.len() < 48 || get(data, 0) != 0x4d534c57 || get(data, 4) != 1 {
        return Err(IllegalArgument);
    }
    let width = get(data, 8);
    let height = get(data, 12);
    if !(11..=256).contains(&width)
        || !(11..=256).contains(&height)
        || get(data, 36) != 0
        || get(data, 44) != 0
    {
        return Err(IllegalArgument);
    }
    let width = width as usize;
    let height = height as usize;
    let out_width = width - 10;
    let out_height = height - 10;
    let area = out_width * out_height;
    let forces = 48 + width * height * 4;
    let output = forces + area * 4;
    let mask = output + area * 4;
    if get(data, 20) != 48
        || get(data, 24) != forces as i32
        || get(data, 28) != output as i32
        || get(data, 32) != mask as i32
        || data.len() != mask + area
    {
        return Err(IllegalArgument);
    }
    data[mask..].fill(0);
    let mut writes = 0u32;
    let min_z = get(data, 16) as f32;
    for x in 0..out_width {
        for y in 0..out_height {
            let i = x * out_height + y;
            let strength = float(data, forces + i * 4);
            if strength.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
                continue;
            }
            let current = float(data, 48 + ((x + 5) * height + y + 5) * 4);
            if current == EMPTY_HEIGHT {
                continue;
            }
            let mut total = 0.0f32;
            let mut count = 0u32;
            for sx in x..=x + 10 {
                for sy in y..=y + 10 {
                    let value = float(data, 48 + (sx * height + sy) * 4);
                    if value != EMPTY_HEIGHT {
                        total += value;
                        count += 1;
                    }
                }
            }
            let edited = strength * (total / count as f32) + (1.0 - strength) * current;
            let raw = ((edited - min_z) * 256.0) as i32;
            data[output + i * 4..output + i * 4 + 4].copy_from_slice(&raw.to_le_bytes());
            data[mask + i] = 1;
            writes += 1;
        }
    }
    data[40..44].copy_from_slice(&writes.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod compact_tests {
    #[test]
    fn rectangular_windows_preserve_empty_centers_and_validate_before_mutation() {
        let mut data = vec![0; 594];
        for (offset, value) in [
            (0, 0x4d534c57i32),
            (4, 1),
            (8, 11),
            (12, 12),
            (16, -64),
            (20, 48),
            (24, 576),
            (28, 584),
            (32, 592),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for i in 0..132 {
            data[48 + i * 4..52 + i * 4].copy_from_slice(&10.25f32.to_le_bytes());
        }
        data[48 + 66 * 4..52 + 66 * 4].copy_from_slice(&(-f32::MAX).to_le_bytes());
        data[576..580].copy_from_slice(&1f32.to_le_bytes());
        data[580..584].copy_from_slice(&1f32.to_le_bytes());
        super::edit_compact(&mut data).unwrap();
        assert_eq!(
            i32::from_le_bytes(data[584..588].try_into().unwrap()),
            74 * 256 + 64
        );
        assert_eq!(&data[592..594], &[1, 0]);
        assert_eq!(i32::from_le_bytes(data[40..44].try_into().unwrap()), 1);
        data[28..32].copy_from_slice(&588i32.to_le_bytes());
        let before = data.clone();
        assert!(super::edit_compact(&mut data).is_err());
        assert_eq!(data, before);
    }
}

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
