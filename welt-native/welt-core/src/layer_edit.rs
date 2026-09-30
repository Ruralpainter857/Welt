use crate::error::WeltError;

pub const MAX_BYTES: usize = 2 * 1024 * 1024;
pub const MASKED_MAX_BYTES: usize = 48 + 16384 + 16384;
fn word(data: &[u8], offset: usize) -> usize {
    u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize
}

pub fn edit_masked(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() < 48
        || data.len() > MASKED_MAX_BYTES
        || word(data, 0) != 0x4d424c57
        || word(data, 4) != 1
        || word(data, 40) != 0
        || word(data, 44) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let (x, y, width, height, bits, side, value) = (
        word(data, 8),
        word(data, 12),
        word(data, 16),
        word(data, 20),
        word(data, 24),
        word(data, 28),
        word(data, 32),
    );
    if width == 0
        || height == 0
        || width > 128
        || height > 128
        || x > 128 - width
        || y > 128 - height
        || !matches!(bits, 1 | 4 | 8)
        || !matches!(side, 8 | 128)
        || (side == 8 && bits != 1)
        || value >= 1 << bits
    {
        return Err(WeltError::IllegalArgument);
    }
    let mask = 48 + side * side * bits / 8;
    if data.len() != mask + width * height {
        return Err(WeltError::IllegalArgument);
    }
    let mut writes = 0u32;
    for dy in 0..height {
        for dx in 0..width {
            if data[mask + dy * width + dx] == 0 {
                continue;
            }
            let cell = if side == 8 {
                ((x + dx) >> 4) + ((y + dy) >> 4) * 8
            } else {
                x + dx + (y + dy) * 128
            };
            let offset = 48 + cell * bits / 8;
            let shift = cell * bits % 8;
            let packed_mask = ((1u16 << bits) - 1) as u8;
            data[offset] = (data[offset] & !(packed_mask << shift)) | ((value as u8) << shift);
            writes += 1;
        }
    }
    // Compter les setters demandés, même si la valeur stockée ne change pas.
    data[36..40].copy_from_slice(&writes.to_le_bytes());
    Ok(())
}

pub fn edit(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() < 16
        || data.len() > MAX_BYTES
        || word(data, 0) != 0x44454c57
        || word(data, 4) != 1
        || word(data, 12) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let count = word(data, 8);
    if !(1..=128).contains(&count) || 16 + count * 24 > data.len() {
        return Err(WeltError::IllegalArgument);
    }
    let mut end = 16 + count * 24;
    for plane in 0..count {
        let d = 16 + plane * 24;
        let side = word(data, d);
        let bits = word(data, d + 4);
        if !matches!(side, 8 | 128)
            || !matches!(bits, 1 | 4 | 8 | 16)
            || (side == 8 && bits != 1)
            || word(data, d + 8) > 2
            || (bits == 16 && word(data, d + 8) != 2)
            || word(data, d + 12) >= (1 << bits)
            || word(data, d + 16) != end
            || side * side * bits / 8 > data.len() - end
        {
            return Err(WeltError::IllegalArgument);
        }
        end += side * side * bits / 8;
    }
    if end != data.len() {
        return Err(WeltError::IllegalArgument);
    }
    for plane in 0..count {
        let d = 16 + plane * 24;
        let side = word(data, d);
        let bits = word(data, d + 4);
        let action = word(data, d + 8);
        let value = word(data, d + 12) as u16;
        let offset = word(data, d + 16);
        let mut changed = false;
        if bits == 16 {
            let packed = value.to_le_bytes();
            for cell in data[offset..offset + side * side * 2]
                .as_chunks_mut::<2>()
                .0
            {
                changed |= *cell != packed;
                *cell = packed;
            }
            data[d + 20..d + 24].copy_from_slice(&(u32::from(changed)).to_le_bytes());
            continue;
        }
        let value = value as u8;
        for byte in &mut data[offset..offset + side * side * bits / 8] {
            let edited = if action == 0 {
                !*byte
            } else if action == 2 {
                match bits {
                    1 => {
                        if value == 0 {
                            0
                        } else {
                            255
                        }
                    }
                    4 => value | (value << 4),
                    _ => value,
                }
            } else if bits == 1 {
                if value != 0 {
                    255
                } else {
                    *byte
                }
            } else if bits == 4 {
                ((*byte & 15).max(value)) | (((*byte >> 4).max(value)) << 4)
            } else {
                (*byte).max(value)
            };
            changed |= edited != *byte;
            *byte = edited;
        }
        data[d + 20..d + 24].copy_from_slice(&(u32::from(changed)).to_le_bytes());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_masked_headers_are_rejected_before_writing() {
        let mut valid = vec![0; 48 + 16384 + 1];
        for (o, v) in [
            (0, 0x4d424c57u32),
            (4, 1),
            (16, 1),
            (20, 1),
            (24, 8),
            (28, 128),
            (32, 42),
        ] {
            valid[o..o + 4].copy_from_slice(&v.to_le_bytes());
        }
        valid[48 + 16384] = 1;
        for offset in [0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 44] {
            let mut data = valid.clone();
            data[offset..offset + 4].copy_from_slice(&u32::MAX.to_le_bytes());
            let before = data.clone();
            assert!(edit_masked(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
    #[test]
    fn masked_planes_keep_unselected_values_and_chunk_bit_addressing() {
        for (bits, side, value) in [(1, 8, 0u32), (1, 128, 0), (4, 128, 7), (8, 128, 42)] {
            let mask = 48 + side * side * bits / 8;
            let mut data = vec![255; mask + 2];
            for (o, v) in [
                (0, 0x4d424c57),
                (4, 1),
                (8, 127),
                (12, 126),
                (16, 1),
                (20, 2),
                (24, bits as u32),
                (28, side as u32),
                (32, value),
                (40, 0),
                (44, 0),
            ] {
                data[o..o + 4].copy_from_slice(&v.to_le_bytes());
            }
            data[mask] = 1;
            data[mask + 1] = 0;
            edit_masked(&mut data).unwrap();
            assert_eq!(word(&data, 36), 1);
            assert_eq!(data[48], 255);
            let cell = if side == 8 { 63 } else { 127 + 126 * 128 };
            assert_eq!(
                (data[48 + cell * bits / 8] >> (cell * bits % 8)) & ((1u16 << bits) - 1) as u8,
                value as u8
            );
        }
    }
    fn fixture() -> Vec<u8> {
        let mut data = vec![0; 16 + 48 + 8 + 8192];
        for (offset, value) in [
            (0, 0x44454c57u32),
            (4, 1),
            (8, 2),
            (16, 8),
            (20, 1),
            (24, 0),
            (32, 64),
            (40, 128),
            (44, 4),
            (48, 1),
            (52, 7),
            (56, 72),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        data[72..].fill(0xa3);
        data
    }
    #[test]
    fn multiple_packed_planes_are_processed_together() {
        let mut data = fixture();
        edit(&mut data).unwrap();
        assert!(data[64..72].iter().all(|b| *b == 255));
        assert!(data[72..].iter().all(|b| *b == 0xa7));
        assert_eq!(word(&data, 36), 1);
        assert_eq!(word(&data, 60), 1);
    }
    #[test]
    fn all_descriptors_are_checked_before_any_mutation() {
        let mut data = fixture();
        data[56..60].copy_from_slice(&0u32.to_le_bytes());
        let before = data.clone();
        assert!(edit(&mut data).is_err());
        assert_eq!(data, before);
    }

    #[test]
    fn constant_short_and_bit_planes_keep_little_endian_layout() {
        let mut data = vec![0; 64 + 32768 + 2048];
        for (offset, value) in [
            (0, 0x44454c57u32),
            (4, 1),
            (8, 2),
            (16, 128),
            (20, 16),
            (24, 2),
            (28, 0xabcd),
            (32, 64),
            (40, 128),
            (44, 1),
            (48, 2),
            (52, 1),
            (56, 32832),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        edit(&mut data).unwrap();
        assert!(data[64..32832]
            .as_chunks::<2>()
            .0
            .iter()
            .all(|v| *v == [0xcd, 0xab]));
        assert!(data[32832..].iter().all(|v| *v == 255));
    }
}
