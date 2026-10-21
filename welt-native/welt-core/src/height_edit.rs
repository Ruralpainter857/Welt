//! Brush-based height edits shared by the native WorldPainter adapters.

const MAX_HEIGHT_CELLS: usize = 65_536;
pub const COMPACT_MAX_BYTES: usize = 80 + 65536 + 65536;

pub fn edit_compact_tile(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    if data.len() >= 80 && i32::from_le_bytes(data[4..8].try_into().unwrap()) == 3 {
        return crate::mountain::edit_compact_tile(data);
    }
    if data.len() >= 64 && i32::from_le_bytes(data[4..8].try_into().unwrap()) == 2 {
        return edit_compact_brush(data);
    }
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

// WHED v2 : même plan compact que v1, suivi des forces X-major de la sous-zone.
fn edit_compact_brush(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn get(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(get(data, offset) as u32)
    }
    let bits = get(data, 20);
    let mode = get(data, 24);
    let x = get(data, 40);
    let y = get(data, 44);
    let width = get(data, 48);
    let height = get(data, 52);
    if get(data, 0) != 0x44454857
        || !matches!(bits, 16 | 32)
        || !(0..=4).contains(&mode)
        || x < 0
        || y < 0
        || width <= 0
        || height <= 0
        || width > 128
        || height > 128
        || x > 128 - width
        || y > 128 - height
        || get(data, 36) != 0
        || get(data, 60) != 0
    {
        return Err(IllegalArgument);
    }
    let bytes = bits as usize / 8;
    let forces = 64 + 16384 * bytes;
    if get(data, 56) != forces as i32 || data.len() != forces + width as usize * height as usize * 4
    {
        return Err(IllegalArgument);
    }
    let min = get(data, 8) as f32;
    let min_clamp = float(data, 12);
    let max_clamp = float(data, 16);
    let value = float(data, 28);
    let mut writes = 0u32;
    for dx in 0..width as usize {
        for dy in 0..height as usize {
            let strength = float(data, forces + (dx * height as usize + dy) * 4);
            if strength.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
                continue;
            }
            let offset = 64 + ((x as usize + dx) + (y as usize + dy) * 128) * bytes;
            let raw = if bits == 16 {
                u16::from_le_bytes(data[offset..offset + 2].try_into().unwrap()) as i32
            } else {
                get(data, offset)
            };
            let current = raw as f32 / 256.0 + min;
            let target = match mode {
                0 => java_min(current + value, max_clamp),
                1 => java_max(current - value, min_clamp),
                _ => value,
            };
            let edited = strength * target + (1.0 - strength) * current;
            let write = match mode {
                2 => true,
                0 | 3 => edited > current,
                _ => edited < current,
            };
            if write {
                let raw = ((edited - min) * 256.0) as i32;
                if bits == 16 {
                    data[offset..offset + 2].copy_from_slice(&(raw as u16).to_le_bytes());
                } else {
                    data[offset..offset + 4].copy_from_slice(&raw.to_le_bytes());
                }
                writes += 1;
            }
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

    #[test]
    fn brush_v2_interpolates_and_preserves_cells_outside_the_subregion() {
        let mut data = vec![0; 64 + 32768 + 4];
        for (offset, value) in [
            (0, 0x44454857i32),
            (4, 2),
            (8, -64),
            (20, 16),
            (24, 2),
            (28, 12.5f32.to_bits() as i32),
            (40, 127),
            (44, 127),
            (48, 1),
            (52, 1),
            (56, 32832),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        data[32832..32836].copy_from_slice(&0.5f32.to_le_bytes());
        let original = data.clone();
        edit_compact_tile(&mut data).unwrap();
        assert_eq!(&data[64..32830], &original[64..32830]);
        assert_eq!(
            u16::from_le_bytes(data[32830..32832].try_into().unwrap()),
            38 * 256 + 64
        );
        assert_eq!(u32::from_le_bytes(data[32..36].try_into().unwrap()), 1);
        data[48..52].copy_from_slice(&129i32.to_le_bytes());
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
