use crate::error::WeltError;

pub const BYTES: usize = 48 + 16384 * 8;

fn word(data: &[u8], offset: usize) -> i32 {
    i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}

pub fn resize(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() != BYTES || word(data, 0) != 0x56525357 || word(data, 4) != 1 {
        return Err(WeltError::IllegalArgument);
    }
    let old_min = word(data, 8);
    let new_min = word(data, 12);
    let new_max = word(data, 16);
    let old_tall = word(data, 20);
    let new_tall = word(data, 24);
    let factor = f32::from_bits(word(data, 28) as u32);
    let translate = word(data, 32);
    let identity = word(data, 36);
    if new_min >= new_max
        || !factor.is_finite()
        || ![0, 1].contains(&old_tall)
        || ![0, 1].contains(&new_tall)
        || ![0, 1].contains(&identity)
        || word(data, 40) != 0
        || word(data, 44) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let max = new_max - 1;
    let delta = old_min.wrapping_sub(new_min);
    for i in 0..16384 {
        let offset = 48 + i * 8;
        let raw = word(data, offset);
        let water = word(data, offset + 4);
        let mut height = if old_tall == new_tall {
            (raw as f32 / 256.0 + new_min as f32) + delta as f32
        } else {
            raw as f32 / 256.0 + old_min as f32
        };
        let water = water.wrapping_add(old_min);
        if identity == 0 {
            height = height * factor + translate as f32;
        }
        height = if height < new_min as f32 {
            new_min as f32
        } else if height > max as f32 {
            max as f32
        } else {
            height
        };
        let water = if identity != 0 {
            water
        } else {
            ((water as f32 * factor + translate as f32) as f64 + 0.5).floor() as i32
        };
        let new_raw = ((height - new_min as f32) * 256.0) as i32;
        let new_water = water.clamp(new_min, max).wrapping_sub(new_min);
        data[offset..offset + 4].copy_from_slice(&new_raw.to_le_bytes());
        data[offset + 4..offset + 8].copy_from_slice(&new_water.to_le_bytes());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_header_does_not_mutate_payload() {
        let mut data = vec![7; BYTES];
        let before = data.clone();
        assert!(resize(&mut data).is_err());
        assert_eq!(data, before);
    }

    #[test]
    fn height_and_water_are_transformed_together() {
        let mut data = vec![0; BYTES];
        for (offset, value) in [
            (0, 0x56525357i32),
            (4, 1),
            (8, -64),
            (12, -128),
            (16, 512),
            (20, 1),
            (24, 1),
            (28, 1.25f32.to_bits() as i32),
            (32, 10),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for i in 0..16384 {
            data[48 + i * 8..52 + i * 8].copy_from_slice(&(126 * 256i32).to_le_bytes());
            data[52 + i * 8..56 + i * 8].copy_from_slice(&126i32.to_le_bytes());
        }
        resize(&mut data).unwrap();
        assert_eq!(word(&data, 48), (215.5 * 256.0) as i32);
        assert_eq!(word(&data, 52), 216);
    }
}
