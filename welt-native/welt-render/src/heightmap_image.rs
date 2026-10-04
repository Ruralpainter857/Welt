//! WHIE v1: one raw-height plane converted in place into image samples.

pub const BYTES: usize = 32 + 128 * 128 * 4;

pub fn convert(data: &mut [u8]) -> Result<(), &'static str> {
    if data.len() != BYTES {
        return Err("Invalid image plane length");
    }
    let word = |offset: usize| u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap());
    if word(0) != 0x45494857 || word(4) != 1 || word(8) != 16384 || word(12) > 3 {
        return Err("Invalid image plane header");
    }
    let mode = word(12);
    let tile_min = word(16) as i32;
    let dimension_min = word(20) as i32;
    let offset = f32::from_bits(word(24));
    let scale = f32::from_bits(word(28));
    for cell in data[32..].chunks_exact_mut(4) {
        let raw = i32::from_le_bytes(cell.try_into().unwrap());
        let value = raw as f32 / 256.0 + tile_min as f32;
        let output = match mode {
            0 => raw as u32,
            1 => ((f64::from(value) + 0.5).floor() as i32).wrapping_sub(dimension_min) as u32,
            2 => value.to_bits(),
            3 => ((value - offset) / scale).to_bits(),
            _ => unreachable!(),
        };
        cell.copy_from_slice(&output.to_le_bytes());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame(mode: u32, min: i32) -> Vec<u8> {
        let mut data = vec![0; BYTES];
        for (i, word) in [
            0x45494857,
            1,
            16384,
            mode,
            min as u32,
            min as u32,
            (-64.0f32).to_bits(),
            256.0f32.to_bits(),
        ]
        .into_iter()
        .enumerate()
        {
            data[i * 4..i * 4 + 4].copy_from_slice(&word.to_le_bytes());
        }
        data
    }
    #[test]
    fn preserves_raw_signed_and_unsigned_storage_words() {
        let mut data = frame(0, -64);
        let values = [0i32, 127, 128, 65535, 65536, i32::MAX, i32::MIN];
        for (i, v) in values.into_iter().enumerate() {
            data[32 + i * 4..36 + i * 4].copy_from_slice(&v.to_le_bytes());
        }
        let before = data.clone();
        convert(&mut data).unwrap();
        assert_eq!(data, before);
    }
    #[test]
    fn keeps_float_order_and_rounding_at_negative_half() {
        let mut data = frame(1, -64);
        for (i, raw) in [127i32, 128, 129, 255].into_iter().enumerate() {
            data[32 + i * 4..36 + i * 4].copy_from_slice(&raw.to_le_bytes());
        }
        convert(&mut data).unwrap();
        for (i, expected) in [0i32, 1, 1, 1].into_iter().enumerate() {
            assert_eq!(
                i32::from_le_bytes(data[32 + i * 4..36 + i * 4].try_into().unwrap()),
                expected
            );
        }
    }
    #[test]
    fn converts_direct_and_normalised_floats() {
        for mode in [2, 3] {
            let mut data = frame(mode, -64);
            data[32..36].copy_from_slice(&65535i32.to_le_bytes());
            convert(&mut data).unwrap();
            let height = 65535f32 / 256.0 - 64.0;
            let expected = if mode == 2 {
                height
            } else {
                (height - -64.0) / 256.0
            };
            assert_eq!(
                u32::from_le_bytes(data[32..36].try_into().unwrap()),
                expected.to_bits()
            );
        }
    }
    #[test]
    fn rejects_invalid_headers_without_mutation() {
        for (offset, word) in [(0, 0u32), (4, 2), (8, 1), (12, 4)] {
            let mut data = frame(1, -64);
            data[offset..offset + 4].copy_from_slice(&word.to_le_bytes());
            let before = data.clone();
            assert!(convert(&mut data).is_err());
            assert_eq!(data, before);
        }
        assert!(convert(&mut vec![0; BYTES - 1]).is_err());
    }
}
