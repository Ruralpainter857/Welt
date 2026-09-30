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
        || word(data, 4) != 1
        || word(data, 8) > 1
        || word(data, 16) > 1
        || word(data, 20) > 1
        || data[ACTIONS..CHUNKS].iter().any(|&action| action > 2)
    {
        return Err("invalid selection program");
    }
    let add = word(data, 8) != 0;
    let chunks_present = word(data, 16) != 0;
    let blocks_present = word(data, 20) != 0;
    let mut flags = 0_u32;
    for chunk in 0..64 {
        let action = data[ACTIONS + chunk];
        if action == 0 {
            continue;
        }
        let bit = 1_u8 << (chunk % 8);
        let whole = data[CHUNKS + chunk / 8] & bit != 0;
        if action == 2 && add && whole {
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
                !mask
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
