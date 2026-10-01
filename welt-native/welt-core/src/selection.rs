//! Whole-tile selection edits over compact chunk and block bit planes.
//! Fixed LE v1 ABI: magic/version/add/result flags, presence flags, 64 chunk
//! actions (0 untouched, 1 covered, 2 partial), 8 chunk bytes, 2048 block bytes,
//! and 2048 shape-mask bytes. Java computes Shape predicates; Rust merges both
//! selection levels together without expanding them into per-cell objects.

pub const MAGIC: u32 = 0x4c45_5357; // WSEL
pub const LENGTH: usize = 4192;
const ACTIONS: usize = 24;
const CHUNKS: usize = 88;
const BLOCKS: usize = 96;
const MASK: usize = 2144;

fn word(data: &[u8], index: usize) -> u32 {
    u32::from_le_bytes(data[index..index + 4].try_into().unwrap())
}

pub fn edit(data: &mut [u8]) -> Result<(), &'static str> {
    if data.len() != LENGTH
        || word(data, 0) != MAGIC
        || !matches!(word(data, 4), 1 | 2)
        || word(data, 8) > 1
        || word(data, 16) > 1
        || word(data, 20) > 1
        || data[ACTIONS..CHUNKS].iter().any(|&action| action > 2)
    {
        return Err("invalid selection program");
    }
    let add = word(data, 8) != 0;
    let brush = word(data, 4) == 2;
    let chunks_present = word(data, 16) != 0;
    let blocks_present = word(data, 20) != 0;
    let mut flags = 0_u32;
    for chunk in 0..64 {
        let action = if brush {
            let mut any = false;
            let mut all = true;
            for row in 0..16 {
                let offset = (chunk / 8 * 16 + row) * 16 + chunk % 8 * 2;
                let mask = u16::from_le_bytes([data[MASK + offset], data[MASK + offset + 1]]);
                any |= mask != 0;
                all &= mask == u16::MAX;
            }
            if all {
                1
            } else if any {
                2
            } else {
                0
            }
        } else {
            data[ACTIONS + chunk]
        };
        if action == 0 {
            continue;
        }
        let bit = 1_u8 << (chunk % 8);
        let whole = data[CHUNKS + chunk / 8] & bit != 0;
        if add && whole && (brush || action == 2) {
            continue;
        }
        let mut any_mask = false;
        let mut any_output = false;
        for row in 0..16 {
            let offset = (chunk / 8 * 16 + row) * 16 + chunk % 8 * 2;
            let mask = u16::from_le_bytes([data[MASK + offset], data[MASK + offset + 1]]);
            let old = u16::from_le_bytes([data[BLOCKS + offset], data[BLOCKS + offset + 1]]);
            let output = if action == 1 {
                0
            } else if add {
                old | mask
            } else if whole {
                // Au pinceau, Java conserve les bits déjà présents sous une sélection de chunk.
                if brush {
                    old | !mask
                } else {
                    !mask
                }
            } else {
                old & !mask
            };
            any_mask |= mask != 0;
            any_output |= output != 0;
            data[BLOCKS + offset..BLOCKS + offset + 2].copy_from_slice(&output.to_le_bytes());
        }
        if action == 1 {
            if add {
                data[CHUNKS + chunk / 8] |= bit;
            } else {
                data[CHUNKS + chunk / 8] &= !bit;
            }
            if add || chunks_present {
                flags |= 1;
            }
            if blocks_present {
                flags |= 2;
            }
        } else if !add && whole {
            data[CHUNKS + chunk / 8] &= !bit;
            flags |= 1;
            if blocks_present || any_output {
                flags |= 2;
            }
        } else if any_mask && (add || blocks_present) {
            flags |= 2;
        }
    }
    data[12..16].copy_from_slice(&flags.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn brush_keeps_existing_bits_during_demotion_and_skips_selected_chunks() {
        let mut data = vec![0; super::LENGTH];
        for (offset, value) in [(0, super::MAGIC), (4, 2), (16, 1), (20, 1)] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        data[super::CHUNKS] = 1;
        data[super::BLOCKS] = 1;
        data[super::MASK] = 1;
        super::edit(&mut data).unwrap();
        assert_eq!(data[super::CHUNKS] & 1, 0);
        assert_eq!(data[super::BLOCKS], 255);
        data[8..12].copy_from_slice(&1u32.to_le_bytes());
        data[super::CHUNKS] = 1;
        let blocks = data[super::BLOCKS..super::MASK].to_vec();
        data[super::MASK..].fill(255);
        super::edit(&mut data).unwrap();
        assert_eq!(&data[super::BLOCKS..super::BLOCKS + 2], &blocks[..2]);
        let before = data.clone();
        data[4..8].copy_from_slice(&3u32.to_le_bytes());
        assert!(super::edit(&mut data).is_err());
        assert_eq!(&data[super::CHUNKS..], &before[super::CHUNKS..]);
    }
}
