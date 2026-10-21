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
        let target = target(mode, strength);
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

pub(crate) fn target(mode: NibblePaintMode, strength: f32) -> i32 {
    match mode {
        NibblePaintMode::Apply => 1_i32.wrapping_add(java_round_f32(strength * 14.0_f32)),
        NibblePaintMode::RemoveRounded => 14_i32.wrapping_sub(java_round_f32(strength * 14.0_f32)),
        NibblePaintMode::RemoveTruncated => {
            14_i32.wrapping_sub((strength * 14.0_f32 + 0.0_f32) as i32)
        }
    }
}

fn java_round_f32(value: f32) -> i32 {
    (f64::from(value) + 0.5).floor() as i32
}

pub const COMPACT_MAX_BYTES: usize = 64 + 8192 + 32768 + 65536 + 16384;

fn edit_cell(
    data: &mut [u8],
    plane: usize,
    cell: usize,
    mode: NibblePaintMode,
    strength: f32,
) -> bool {
    if strength == 0.0 {
        return false;
    }
    let offset = plane + cell / 2;
    let shift = (cell & 1) * 4;
    let current = (data[offset] >> shift) & 15;
    let value = target(mode, strength);
    let write = if mode == NibblePaintMode::Apply {
        value > current as i32
    } else {
        value < current as i32
    };
    if write {
        data[offset] = (data[offset] & !(15 << shift)) | ((value as u8) << shift);
    }
    write
}

fn edit_combined(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError;
    fn word(d: &[u8], o: usize) -> usize {
        u32::from_le_bytes(d[o..o + 4].try_into().unwrap()) as usize
    }
    if data.len() < 64
        || data.len() > COMPACT_MAX_BYTES
        || word(data, 0) != 0x50424c57
        || word(data, 56) != 0
        || word(data, 60) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let (w, h, x, y, flags) = (
        word(data, 8),
        word(data, 12),
        word(data, 16),
        word(data, 20),
        word(data, 32),
    );
    if w == 0
        || h == 0
        || w > 128
        || h > 128
        || x > 128 - w
        || y > 128 - h
        || !(1..=3).contains(&flags)
        || word(data, 36) > 255
        || word(data, 40) > 255
    {
        return Err(WeltError::IllegalArgument);
    }
    let mode = match word(data, 24) {
        0 => NibblePaintMode::Apply,
        1 => NibblePaintMode::RemoveRounded,
        2 => NibblePaintMode::RemoveTruncated,
        _ => return Err(WeltError::IllegalArgument),
    };
    if flags & 1 != 0 && mode != NibblePaintMode::Apply {
        return Err(WeltError::IllegalArgument);
    }
    let biome = 8256 + if flags & 1 != 0 { 16384 } else { 0 };
    let strengths = 8256 + flags.count_ones() as usize * 16384;
    let mask = strengths + w * h * 4;
    if word(data, 48) != strengths || word(data, 52) != mask || data.len() != mask + w * h {
        return Err(WeltError::IllegalArgument);
    }
    for s in data[strengths..mask].as_chunks::<4>().0 {
        let v = f32::from_le_bytes(*s);
        if v < 0.0 || v > 1.0 {
            return Err(WeltError::IllegalArgument);
        }
    }
    let terrain_value = word(data, 36) as u8;
    let biome_value = word(data, 40) as u8;
    let mut changed = 0u32;
    let mut auxiliary = 0u32;
    for dy in 0..h {
        for dx in 0..w {
            let index = dy * w + dx;
            let s = strengths + index * 4;
            let strength = f32::from_le_bytes(data[s..s + 4].try_into().unwrap());
            let cell = x + dx + (y + dy) * 128;
            changed += u32::from(edit_cell(data, 64, cell, mode, strength));
            if data[mask + index] != 0 {
                if flags & 1 != 0 {
                    data[8256 + cell] = terrain_value;
                }
                if flags & 2 != 0 {
                    data[biome + cell] = biome_value;
                }
                auxiliary += 1;
            }
        }
    }
    data[28..32].copy_from_slice(&changed.to_le_bytes());
    data[44..48].copy_from_slice(&auxiliary.to_le_bytes());
    Ok(())
}

pub fn edit_compact(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    if data.len() >= 64 && u32::from_le_bytes(data[4..8].try_into().unwrap()) == 2 {
        return edit_combined(data);
    }
    use crate::error::WeltError;
    fn word(data: &[u8], offset: usize) -> usize {
        u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize
    }
    if data.len() < 48 + 8192
        || data.len() > COMPACT_MAX_BYTES
        || word(data, 0) != 0x50424c57
        || word(data, 4) != 1
        || [32, 36, 40, 44].iter().any(|&o| word(data, o) != 0)
    {
        return Err(WeltError::IllegalArgument);
    }
    let (width, height, x, y) = (
        word(data, 8),
        word(data, 12),
        word(data, 16),
        word(data, 20),
    );
    if width == 0
        || height == 0
        || width > 128
        || height > 128
        || x > 128 - width
        || y > 128 - height
        || data.len() != 48 + 8192 + width * height * 4
    {
        return Err(WeltError::IllegalArgument);
    }
    let mode = match word(data, 24) {
        0 => NibblePaintMode::Apply,
        1 => NibblePaintMode::RemoveRounded,
        2 => NibblePaintMode::RemoveTruncated,
        _ => return Err(WeltError::IllegalArgument),
    };
    // Tout vérifier avant les mutations ; les forces hors plage gardent le chemin Java et ses exceptions.
    for s in data[8240..].as_chunks::<4>().0 {
        let v = f32::from_le_bytes(*s);
        if v < 0.0 || v > 1.0 {
            return Err(WeltError::IllegalArgument);
        }
    }
    let mut changed = 0u32;
    for dy in 0..height {
        for dx in 0..width {
            let s = 8240 + (dy * width + dx) * 4;
            let strength = f32::from_le_bytes(data[s..s + 4].try_into().unwrap());
            if strength == 0.0 {
                continue;
            }
            let cell = x + dx + (y + dy) * 128;
            changed += u32::from(edit_cell(data, 48, cell, mode, strength));
        }
    }
    data[28..32].copy_from_slice(&changed.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn combined_planes_share_one_mask_and_reject_invalid_offsets() {
        for flags in 1u32..=3 {
            let strength = 8256 + flags.count_ones() as usize * 16384;
            let mask = strength + 8;
            let mut data = vec![0; mask + 2];
            for (o, v) in [
                (0, 0x50424c57u32),
                (4, 2),
                (8, 2),
                (12, 1),
                (16, 126),
                (20, 127),
                (32, flags),
                (36, 7),
                (40, 42),
                (48, strength as u32),
                (52, mask as u32),
            ] {
                data[o..o + 4].copy_from_slice(&v.to_le_bytes());
            }
            data[strength..strength + 4].copy_from_slice(&1.0f32.to_le_bytes());
            data[mask + 1] = 1;
            edit_compact(&mut data).unwrap();
            let first = 126 + 127 * 128;
            assert_eq!(data[64 + first / 2], 15);
            assert_eq!(u32::from_le_bytes(data[28..32].try_into().unwrap()), 1);
            assert_eq!(u32::from_le_bytes(data[44..48].try_into().unwrap()), 1);
            if flags & 1 != 0 {
                assert_eq!(data[8256 + first], 0);
                assert_eq!(data[8256 + first + 1], 7);
            }
            if flags & 2 != 0 {
                let b = 8256 + if flags & 1 != 0 { 16384 } else { 0 };
                assert_eq!(data[b + first + 1], 42);
            }
            data[48..52].copy_from_slice(&0u32.to_le_bytes());
            let before = data.clone();
            assert!(edit_compact(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
    #[test]
    fn round_does_not_round_up_the_float_just_below_a_half() {
        assert_eq!(java_round_f32(f32::from_bits(0x3effffff)), 0);
        assert_eq!(java_round_f32(0.5), 1);
        assert_eq!(java_round_f32(-0.5), 0);
        assert_eq!(java_round_f32(f32::from_bits(0xbf000001)), -1);
    }
    #[test]
    fn compact_brush_keeps_the_other_half_of_each_packed_byte() {
        let mut data = vec![0; 8240 + 8];
        for (o, v) in [
            (0, 0x50424c57u32),
            (4, 1),
            (8, 1),
            (12, 2),
            (16, 127),
            (20, 126),
        ] {
            data[o..o + 4].copy_from_slice(&v.to_le_bytes());
        }
        data[48..8240].fill(0x32);
        data[8240..8244].copy_from_slice(&1.0f32.to_le_bytes());
        edit_compact(&mut data).unwrap();
        assert_eq!(data[48 + (127 + 126 * 128) / 2], 0xf2);
        assert_eq!(data[48 + 16383 / 2], 0x32);
        let mut invalid = data.clone();
        invalid[8244..].copy_from_slice(&2.0f32.to_le_bytes());
        let before = invalid.clone();
        assert!(edit_compact(&mut invalid).is_err());
        assert_eq!(invalid, before);
    }
}
