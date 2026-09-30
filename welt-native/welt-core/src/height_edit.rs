//! Brush-based height edits shared by the native WorldPainter adapters.

const MAX_HEIGHT_CELLS: usize = 65_536;

pub fn edit_compact_tile(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError;
    fn read(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    if data.len() < 40 || read(data, 0) != 0x44454857 || read(data, 4) != 1 || read(data, 36) != 0 {
        return Err(WeltError::IllegalArgument);
    }
    let bits = read(data, 20);
    let mode = read(data, 24);
    if !matches!(bits, 16 | 32)
        || !(0..=4).contains(&mode)
        || data.len() != 40 + 16384 * bits as usize / 8
        || read(data, 12) > read(data, 16)
    {
        return Err(WeltError::IllegalArgument);
    }
    let min_height = read(data, 8) as f32;
    let min = read(data, 12) as f32;
    let max = read(data, 16) as f32;
    let value = f32::from_bits(read(data, 28) as u32);
    let mut writes = 0u32;
    for i in 0..16384 {
        let offset = 40 + i * bits as usize / 8;
        let raw = if bits == 16 {
            u16::from_le_bytes(data[offset..offset + 2].try_into().unwrap()) as i32
        } else {
            read(data, offset)
        };
        let current = raw as f32 / 256.0 + min_height;
        let target = match mode {
            2 => java_min(current + value, max),
            4 => java_max(current - value, min),
            _ => value,
        };
        let write = match mode {
            0 => true,
            1 | 2 => current < target,
            _ => current > target,
        };
        if write {
            let edited = ((target - min_height) * 256.0) as i32;
            if bits == 16 {
                data[offset..offset + 2].copy_from_slice(&(edited as u16).to_le_bytes());
            } else {
                data[offset..offset + 4].copy_from_slice(&edited.to_le_bytes());
            }
            writes += 1;
        }
    }
    data[32..36].copy_from_slice(&writes.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod compact_tests {
    use super::edit_compact_tile;
    fn fixture() -> Vec<u8> {
        let mut data = vec![0; 40 + 32768];
        for (offset, value) in [
            (0, 0x44454857i32),
            (4, 1),
            (12, 0),
            (16, 255),
            (20, 16),
            (24, 2),
            (28, 3f32.to_bits() as i32),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for cell in data[40..].as_chunks_mut::<2>().0 {
            *cell = (252 * 256 + 128u16).to_le_bytes();
        }
        data
    }
    #[test]
    fn unsigned_heights_are_clamped_and_quantised() {
        let mut data = fixture();
        edit_compact_tile(&mut data).unwrap();
        assert!(data[40..]
            .as_chunks::<2>()
            .0
            .iter()
            .all(|cell| *cell == (255 * 256u16).to_le_bytes()));
        assert_eq!(u32::from_le_bytes(data[32..36].try_into().unwrap()), 16384);
    }
    #[test]
    fn invalid_header_does_not_change_the_height_plane() {
        let mut data = fixture();
        data[24..28].copy_from_slice(&99i32.to_le_bytes());
        let before = data.clone();
        assert!(edit_compact_tile(&mut data).is_err());
        assert_eq!(data, before);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeightEditError {
    TooManyCells,
    HeightLength { expected: usize, actual: usize },
    StrengthLength { expected: usize, actual: usize },
    ModifiedLength { expected: usize, actual: usize },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FlattenMode {
    Flatten,
    Raise,
    Lower,
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

/// Applies WorldPainter's flatten, raise-only, or lower-only brush pass.
pub fn apply_flatten_brush(
    mode: FlattenMode,
    target_height: f32,
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
        let strength = strengths[index];
        if strength > 0.0_f32 {
            let new_height = strength * target_height + (1.0_f32 - strength) * current_height;
            let should_write = match mode {
                FlattenMode::Flatten => true,
                FlattenMode::Raise => new_height > current_height,
                FlattenMode::Lower => new_height < current_height,
            };
            if should_write {
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
