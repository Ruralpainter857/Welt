//! In-place rotation of all packed tile planes in one versioned buffer.
//! v1: LE u32 magic, version, quarter turns, plane count; then descriptors
//! (side, bits per cell, byte offset, byte length), followed by contiguous planes.
//! Cells are row-major, low bit/nibble first. Validation precedes all mutation.

use std::cell::RefCell;

pub const MAGIC: u32 = 0x544f_5257; // WROT
pub const MAX_BYTES: usize = 2 * 1024 * 1024;
thread_local! { static SCRATCH: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) }; }

fn word(data: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}

pub fn rotate(data: &mut [u8]) -> Result<(), &'static str> {
    if data.len() < 16 || data.len() > MAX_BYTES || word(data, 0) != MAGIC || word(data, 4) != 1 {
        return Err("invalid header");
    }
    let turns = word(data, 8);
    let count = word(data, 12) as usize;
    if !(1..=3).contains(&turns) || !(3..=128).contains(&count) || 16 + count * 16 > data.len() {
        return Err("invalid program");
    }
    let mut end = 16 + count * 16;
    for plane in 0..count {
        let descriptor = 16 + plane * 16;
        let side = word(data, descriptor) as usize;
        let bits = word(data, descriptor + 4) as usize;
        let offset = word(data, descriptor + 8) as usize;
        let length = word(data, descriptor + 12) as usize;
        if !matches!(side, 8 | 128)
            || !matches!(bits, 1 | 4 | 8 | 16 | 32)
            || length != side * side * bits / 8
            || offset != end
            || length > data.len() - end
        {
            return Err("invalid plane");
        }
        end += length;
    }
    if end != data.len() {
        return Err("trailing data");
    }
    SCRATCH.with(|scratch| {
        let mut scratch = scratch.borrow_mut();
        for plane in 0..count {
            let descriptor = 16 + plane * 16;
            let side = word(data, descriptor) as usize;
            let bits = word(data, descriptor + 4) as usize;
            let offset = word(data, descriptor + 8) as usize;
            let length = word(data, descriptor + 12) as usize;
            let target = &mut data[offset..offset + length];
            scratch.resize(length, 0);
            scratch.copy_from_slice(target);
            for y in 0..side {
                for x in 0..side {
                    let (tx, ty) = match turns {
                        1 => (side - 1 - y, x),
                        2 => (side - 1 - x, side - 1 - y),
                        _ => (y, side - 1 - x),
                    };
                    let source_bit = (x + y * side) * bits;
                    let target_bit = (tx + ty * side) * bits;
                    if bits < 8 {
                        let mask = (1_u8 << bits) - 1;
                        let value = (scratch[source_bit / 8] >> (source_bit % 8)) & mask;
                        let shift = target_bit % 8;
                        target[target_bit / 8] =
                            (target[target_bit / 8] & !(mask << shift)) | (value << shift);
                    } else {
                        let bytes = bits / 8;
                        target[target_bit / 8..target_bit / 8 + bytes]
                            .copy_from_slice(&scratch[source_bit / 8..source_bit / 8 + bytes]);
                    }
                }
            }
        }
    });
    Ok(())
}
