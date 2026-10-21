use crate::error::WeltError;

pub const MAX_BYTES: usize = 64 + 65536 + 32768 + 2048;

fn word(data: &[u8], offset: usize) -> usize {
    u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize
}

pub fn edit(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() < 64
        || data.len() > MAX_BYTES
        || word(data, 0) != 0x42464c57
        || word(data, 4) != 1
        || word(data, 52) != 0
        || word(data, 56) != 0
        || word(data, 60) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let (width, height, x, y, bits, mode) = (
        word(data, 8),
        word(data, 12),
        word(data, 16),
        word(data, 20),
        word(data, 24),
        word(data, 28),
    );
    if width == 0
        || height == 0
        || width > 128
        || height > 128
        || x > 128 - width
        || y > 128 - height
        || !matches!(bits, 8 | 16)
        || mode > 1
    {
        return Err(WeltError::IllegalArgument);
    }
    let area = width * height;
    let water = 64 + area * 4;
    let lava = water + area * bits / 8;
    if word(data, 44) != water || word(data, 48) != lava || data.len() != lava + 2048 {
        return Err(WeltError::IllegalArgument);
    }
    let value = (word(data, 32) as u16).to_le_bytes();
    let mut writes = 0u32;
    for dx in 0..width {
        for dy in 0..height {
            let s = 64 + (dx * height + dy) * 4;
            let strength = f32::from_le_bytes(data[s..s + 4].try_into().unwrap());
            // Java considère NaN comme différent de zéro, mais pas les deux zéros signés.
            if strength == 0.0 {
                continue;
            }
            let offset = water + (dy * width + dx) * bits / 8;
            data[offset..offset + bits / 8].copy_from_slice(&value[..bits / 8]);
            if mode == 1 {
                let cell = x + dx + (y + dy) * 128;
                data[lava + cell / 8] &= !(1 << (cell & 7));
            }
            writes += 1;
        }
    }
    data[36..40].copy_from_slice(&writes.to_le_bytes());
    data[40..44].copy_from_slice(&(if mode == 1 { writes } else { 0 }).to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(bits: u32, mode: u32) -> Vec<u8> {
        let water = 64 + 16;
        let lava = water + 4 * bits as usize / 8;
        let mut data = vec![0xff; lava + 2048];
        for (offset, value) in [
            (0, 0x42464c57),
            (4, 1),
            (8, 2),
            (12, 2),
            (16, 126),
            (20, 126),
            (24, bits),
            (28, mode),
            (32, 0xabcd),
            (44, water as u32),
            (48, lava as u32),
            (52, 0),
            (56, 0),
            (60, 0),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for (i, value) in [0.0f32, -0.0, f32::NAN, -1.0].into_iter().enumerate() {
            data[64 + i * 4..68 + i * 4].copy_from_slice(&value.to_le_bytes());
        }
        data
    }

    #[test]
    fn both_water_formats_preserve_unselected_cells_and_lava_border() {
        for bits in [8, 16] {
            for mode in [0, 1] {
                let mut data = fixture(bits, mode);
                edit(&mut data).unwrap();
                let water = word(&data, 44);
                let lava = word(&data, 48);
                assert_eq!(word(&data, 36), 2);
                assert_eq!(word(&data, 40), if mode == 1 { 2 } else { 0 });
                for cell in 0..4 {
                    let offset = water + cell * bits as usize / 8;
                    assert_eq!(data[offset], if cell & 1 == 0 { 255 } else { 0xcd });
                    if bits == 16 {
                        assert_eq!(data[offset + 1], if cell & 1 == 0 { 255 } else { 0xab });
                    }
                }
                assert_eq!(data[lava + 16383 / 8], if mode == 1 { 0x7f } else { 0xff });
                assert_eq!(data[lava], 0xff);
            }
        }
    }

    #[test]
    fn invalid_offsets_and_bounds_do_not_mutate_the_buffer() {
        for offset in [0, 4, 8, 12, 16, 20, 24, 28, 44, 48, 52, 56, 60] {
            let mut data = fixture(16, 1);
            data[offset..offset + 4].copy_from_slice(&u32::MAX.to_le_bytes());
            let before = data.clone();
            assert!(edit(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
}
