use crate::error::WeltError;

pub const MAX_BYTES: usize = 2 * 1024 * 1024;
fn word(data: &[u8], offset: usize) -> usize {
    u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize
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
