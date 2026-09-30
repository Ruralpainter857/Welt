//! Nibble-layer brush calculations for the Java painting adapter.

const MAX_NIBBLE_PAINT_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NibblePaintMode {
    Apply,
    RemoveRounded,
    RemoveTruncated,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NibblePaintError {
    TooManyCells,
    StrengthLength,
    ModifiedLength,
}

/// Computes the same target nibble values as `NibbleLayerPaint`, leaving the
/// actual tile writes to Java so undo and copy-on-write behavior stay intact.
pub fn apply_nibble_layer_brush(
    mode: NibblePaintMode,
    values: &mut [i32],
    strengths: &[f32],
    modified: &mut [i8],
) -> Result<(), NibblePaintError> {
    if values.len() > MAX_NIBBLE_PAINT_CELLS {
        return Err(NibblePaintError::TooManyCells);
    }
    if strengths.len() != values.len() {
        return Err(NibblePaintError::StrengthLength);
    }
    if modified.len() != values.len() {
        return Err(NibblePaintError::ModifiedLength);
    }

    modified.fill(0);
    for index in 0..values.len() {
        let strength = strengths[index];
        if strength == 0.0_f32 {
            continue;
        }
        let target = match mode {
            NibblePaintMode::Apply => 1_i32.wrapping_add(java_round_f32(strength * 14.0_f32)),
            NibblePaintMode::RemoveRounded => {
                14_i32.wrapping_sub(java_round_f32(strength * 14.0_f32))
            }
            NibblePaintMode::RemoveTruncated => {
                14_i32.wrapping_sub((strength * 14.0_f32 + 0.0_f32) as i32)
            }
        };
        let should_write = match mode {
            NibblePaintMode::Apply => target > values[index],
            NibblePaintMode::RemoveRounded | NibblePaintMode::RemoveTruncated => {
                target < values[index]
            }
        };
        if should_write {
            values[index] = target;
            modified[index] = 1;
        }
    }
    Ok(())
}

fn java_round_f32(value: f32) -> i32 {
    (value + 0.5_f32).floor() as i32
}
