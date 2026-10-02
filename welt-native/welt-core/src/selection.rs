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

/// Read-only WSEL v3 bounds. Four local byte coordinates and bit 32 for a nonempty selection.
/// Whole chunks and individual blocks form a union; absent planes remain explicitly absent.
pub fn bounds(data: &[u8]) -> Option<i64> {
    if data.len() != LENGTH
        || word(data, 0) != MAGIC
        || word(data, 4) != 3
        || word(data, 16) > 1
        || word(data, 20) > 1
    {
        return None;
    }
    let mut low_x = 128u32;
    let mut high_x = 0u32;
    let mut low_y = 128u32;
    let mut high_y = 0u32;
    if word(data, 16) != 0 {
        let mut chunks = u64::from_le_bytes(data[CHUNKS..CHUNKS + 8].try_into().unwrap());
        while chunks != 0 {
            let chunk = chunks.trailing_zeros();
            chunks &= chunks - 1;
            let x = (chunk % 8) * 16;
            let y = (chunk / 8) * 16;
            low_x = low_x.min(x);
            high_x = high_x.max(x + 15);
            low_y = low_y.min(y);
            high_y = high_y.max(y + 15);
        }
    }
    if word(data, 20) != 0 && !(low_x == 0 && high_x == 127 && low_y == 0 && high_y == 127) {
        // Each block row is two words. Bounds depend on the first and last set bits, not its density.
        for y in 0..128u32 {
            for half in 0..2u32 {
                let offset = BLOCKS + y as usize * 16 + half as usize * 8;
                let row = u64::from_le_bytes(data[offset..offset + 8].try_into().unwrap());
                if row != 0 {
                    low_x = low_x.min(half * 64 + row.trailing_zeros());
                    high_x = high_x.max(half * 64 + 63 - row.leading_zeros());
                    low_y = low_y.min(y);
                    high_y = high_y.max(y);
                }
            }
        }
    }
    Some(if low_x == 128 {
        0
    } else {
        (1i64 << 32)
            | i64::from(low_x)
            | (i64::from(high_x) << 8)
            | (i64::from(low_y) << 16)
            | (i64::from(high_y) << 24)
    })
}
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

#[cfg(test)]
mod bounds_tests {
    use super::*;
    fn oracle(data: &[u8]) -> i64 {
        let (mut lx, mut ly, mut hx, mut hy) = (128, 128, 0, 0);
        for y in 0..128 {
            for x in 0..128 {
                let c = x / 16 + y / 16 * 8;
                let b = x + y * 128;
                if (word(data, 16) != 0 && data[CHUNKS + c / 8] & (1 << (c % 8)) != 0)
                    || (word(data, 20) != 0 && data[BLOCKS + b / 8] & (1 << (b % 8)) != 0)
                {
                    lx = lx.min(x);
                    hx = hx.max(x);
                    ly = ly.min(y);
                    hy = hy.max(y);
                }
            }
        }
        if lx == 128 {
            0
        } else {
            (1i64 << 32)
                | lx as i64
                | ((hx as i64) << 8)
                | ((ly as i64) << 16)
                | ((hy as i64) << 24)
        }
    }
    #[test]
    fn bounds_match_cell_oracle_for_sparse_dense_mixed_and_absent_planes() {
        let mut seed = 19u32;
        for variant in 0..20 {
            for chunks in 0..=1u32 {
                for blocks in 0..=1u32 {
                    let mut data = vec![0; LENGTH];
                    data[..4].copy_from_slice(&MAGIC.to_le_bytes());
                    data[4..8].copy_from_slice(&3u32.to_le_bytes());
                    data[16..20].copy_from_slice(&chunks.to_le_bytes());
                    data[20..24].copy_from_slice(&blocks.to_le_bytes());
                    for value in &mut data[CHUNKS..MASK] {
                        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                        *value = if variant == 0 {
                            0
                        } else if variant == 1 {
                            255
                        } else if variant % 3 == 0 {
                            u8::from(seed.is_multiple_of(101))
                        } else {
                            seed as u8
                        };
                    }
                    assert_eq!(bounds(&data), Some(oracle(&data)));
                }
            }
        }
        for bit in [0, 63, 64, 127, 128, 8191, 8192, 16383] {
            let mut data = vec![0; LENGTH];
            data[..4].copy_from_slice(&MAGIC.to_le_bytes());
            data[4..8].copy_from_slice(&3u32.to_le_bytes());
            data[20..24].copy_from_slice(&1u32.to_le_bytes());
            data[BLOCKS + bit / 8] = 1 << (bit % 8);
            assert_eq!(bounds(&data), Some(oracle(&data)));
        }
    }
    #[test]
    fn invalid_bounds_frames_are_rejected() {
        assert_eq!(bounds(&[]), None);
        assert_eq!(bounds(&vec![0; LENGTH]), None);
    }
}
