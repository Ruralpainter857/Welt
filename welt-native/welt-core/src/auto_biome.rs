use crate::error::WeltError;

pub const BYTES: usize = 64 + 16384 * 8;
fn word(data: &[u8], offset: usize) -> i32 {
    i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}

pub fn bake(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() != BYTES
        || word(data, 0) != 0x4d424157
        || word(data, 4) != 1
        || word(data, 56) != 0
        || word(data, 60) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let constant = word(data, 8);
    if !(-1..=254).contains(&constant) {
        return Err(WeltError::IllegalArgument);
    }
    let mut biomes = [0u8; 10];
    for (i, biome) in biomes.iter_mut().enumerate() {
        let value = word(data, 16 + i * 4);
        if !(0..=254).contains(&value) {
            return Err(WeltError::IllegalArgument);
        }
        *biome = value as u8;
    }
    let mut changed = 0i32;
    for i in 0..16384 {
        let offset = 64 + i * 8;
        if data[offset + 5] != 255 {
            continue;
        }
        let depth = word(data, offset);
        let flags = data[offset + 4];
        let forest = flags & 16 != 0;
        let swamp = flags & 4 != 0;
        let jungle = flags & 8 != 0;
        let flooded = depth > 0 && flags & 32 == 0;
        let biome = if constant >= 0 {
            constant as u8
        } else if flags & 1 != 0 {
            if flags & 2 != 0 {
                biomes[0]
            } else if forest || swamp || jungle {
                biomes[1]
            } else if flags & 64 != 0 || (flooded && depth <= 5) {
                biomes[0]
            } else if flooded {
                biomes[2]
            } else {
                biomes[3]
            }
        } else if flags & 2 != 0 {
            biomes[4]
        } else if swamp {
            biomes[5]
        } else if jungle {
            biomes[6]
        } else if flooded {
            if depth <= 5 {
                biomes[4]
            } else if depth <= 20 {
                biomes[7]
            } else {
                biomes[8]
            }
        } else if forest && flags & 128 != 0 {
            biomes[9]
        } else {
            data[offset + 6]
        };
        data[offset + 5] = biome;
        changed += 1;
    }
    data[12..16].copy_from_slice(&changed.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fixed_biomes_survive_and_automatic_cells_are_classified() {
        let mut data = vec![0; BYTES];
        for (offset, value) in [(0, 0x4d424157i32), (4, 1), (8, -1)] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for i in 0..10 {
            data[16 + i * 4..20 + i * 4].copy_from_slice(&(i as i32 + 10).to_le_bytes());
        }
        data[69] = 255;
        data[68] = 1 | 2;
        data[77] = 255;
        data[76] = 1 | 16;
        data[85] = 7;
        bake(&mut data).unwrap();
        assert_eq!(data[69], 10);
        assert_eq!(data[77], 11);
        assert_eq!(data[85], 7);
        assert_eq!(word(&data, 12), 2);
    }
    #[test]
    fn invalid_palette_is_rejected_without_mutation() {
        let mut data = vec![0; BYTES];
        data[0..4].copy_from_slice(&0x4d424157i32.to_le_bytes());
        data[4..8].copy_from_slice(&1i32.to_le_bytes());
        data[16..20].copy_from_slice(&999i32.to_le_bytes());
        let before = data.clone();
        assert!(bake(&mut data).is_err());
        assert_eq!(data, before);
    }
}
