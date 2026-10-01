//! WLCP v1: packed plane copies across one source tile and up to four destinations.
//! Reads stay live during mutations, including overlapping chunk-sized bits.
//! Header: magic/version, tile/plane counts, signed displacement, selection plane
//! indices, clear flag, then zero reserved bytes (64 bytes total).
//! Plane descriptors: kind, operation, default value, offset in each tile payload.
//! Kinds: LE u32, byte, packed nibble, block bits, chunk bits (low bit first).
//! Operations: read only, layer setter, clear/copy ordinary layer, mandatory copy.
//! Tile records: signed coordinates, presence/change u64 masks, eight reserved
//! zero bytes, followed by the packed planes. All coordinates are world tile indices.

use crate::error::WeltError;
mod blend;

pub const MAX_BYTES: usize = 6 * 1024 * 1024;
const AREA: usize = 16384;
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn mask(d: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn put_mask(d: &mut [u8], p: usize, v: u64) {
    d[p..p + 8].copy_from_slice(&v.to_le_bytes());
}
fn length(kind: u32) -> usize {
    match kind {
        0 => AREA * 4,
        1 => AREA,
        2 => AREA / 2,
        3 => AREA / 8,
        4 => 8,
        _ => 0,
    }
}
fn index(kind: u32, x: usize, y: usize) -> usize {
    if kind == 4 {
        x / 16 + y / 16 * 8
    } else {
        x + y * 128
    }
}
fn get(d: &[u8], base: usize, kind: u32, i: usize) -> u32 {
    match kind {
        0 => word(d, base + i * 4),
        1 => d[base + i] as u32,
        2 => ((d[base + i / 2] >> ((i % 2) * 4)) & 15) as u32,
        _ => ((d[base + i / 8] >> (i % 8)) & 1) as u32,
    }
}
fn set(d: &mut [u8], base: usize, kind: u32, i: usize, value: u32) {
    match kind {
        0 => d[base + i * 4..base + i * 4 + 4].copy_from_slice(&value.to_le_bytes()),
        1 => d[base + i] = value as u8,
        2 => {
            let shift = (i % 2) * 4;
            d[base + i / 2] = (d[base + i / 2] & !(15 << shift)) | ((value as u8) << shift);
        }
        _ => {
            let bit = 1 << (i % 8);
            d[base + i / 8] = (d[base + i / 8] & !bit) | ((value as u8) << (i % 8));
        }
    }
}

/// Validate the complete frame before writing data or change masks.
pub fn copy(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() >= 8 && word(data, 4) == 2 {
        return blend::copy_blended(data);
    }
    let bad = WeltError::IllegalArgument;
    if data.len() < 64
        || data.len() > MAX_BYTES
        || word(data, 0) != 0x5043_4c57
        || word(data, 4) != 1
    {
        return Err(bad);
    }
    let tiles = word(data, 8) as usize;
    let planes = word(data, 12) as usize;
    let dx = word(data, 16) as i32;
    let dy = word(data, 20) as i32;
    let chunks = word(data, 24) as usize;
    let blocks = word(data, 28) as usize;
    if !(1..=5).contains(&tiles)
        || !(2..=64).contains(&planes)
        || chunks >= planes
        || blocks >= planes
        || chunks == blocks
        || word(data, 32) > 1
        || data[36..64].iter().any(|&v| v != 0)
        || 64 + planes * 16 > data.len()
    {
        return Err(bad);
    }
    let mut bytes = 0;
    for p in 0..planes {
        let desc = 64 + p * 16;
        let kind = word(data, desc);
        let op = word(data, desc + 4);
        let def = word(data, desc + 8);
        if length(kind) == 0
            || op > 3
            || kind == 0 && op != 3
            || op < 3
                && def
                    > match kind {
                        1 => 255,
                        2 => 15,
                        _ => 1,
                    }
            || word(data, desc + 12) as usize != bytes
        {
            return Err(bad);
        }
        bytes += length(kind);
    }
    if word(data, 64 + chunks * 16) != 4
        || word(data, 68 + chunks * 16) != 0
        || word(data, 64 + blocks * 16) != 3
        || word(data, 68 + blocks * 16) != 0
    {
        return Err(bad);
    }
    let head = 64 + planes * 16;
    let stride = 32 + bytes;
    if head + tiles * stride != data.len() {
        return Err(bad);
    }
    for t in 0..tiles {
        let meta = head + t * stride;
        if mask(data, meta + 16) != 0
            || data[meta + 24..meta + 32].iter().any(|&v| v != 0)
            || planes < 64 && mask(data, meta + 8) >> planes != 0
        {
            return Err(bad);
        }
        for previous in 0..t {
            let other = head + previous * stride;
            if word(data, meta) == word(data, other)
                && word(data, meta + 4) == word(data, other + 4)
            {
                return Err(bad);
            }
        }
    }
    let source = head + 32;
    let sx = word(data, head) as i32 as i64 * 128;
    let sy = word(data, head + 4) as i32 as i64 * 128;
    let chunk_base = source + word(data, 64 + chunks * 16 + 12) as usize;
    let block_base = source + word(data, 64 + blocks * 16 + 12) as usize;
    for xi in 0..128 {
        for yi in 0..128 {
            let x = if dx > 0 { 127 - xi } else { xi };
            let y = if dy > 0 { 127 - yi } else { yi };
            if get(data, chunk_base, 4, index(4, x, y)) == 0
                && get(data, block_base, 3, index(3, x, y)) == 0
            {
                continue;
            }
            let wx = sx + x as i64 + dx as i64;
            let wy = sy + y as i64 + dy as i64;
            let target = (0..tiles).find(|&t| {
                let m = head + t * stride;
                word(data, m) as i32 as i64 == wx.div_euclid(128)
                    && word(data, m + 4) as i32 as i64 == wy.div_euclid(128)
            });
            let Some(t) = target else { continue };
            let meta = head + t * stride;
            let dest = meta + 32;
            let tx = wx.rem_euclid(128) as usize;
            let ty = wy.rem_euclid(128) as usize;
            // Java clears destination layers before reading ordinary source layers.
            if word(data, 32) == 1 {
                for p in 0..planes {
                    let desc = 64 + p * 16;
                    if word(data, desc + 4) != 2 || mask(data, meta + 8) & (1u64 << p) == 0 {
                        continue;
                    }
                    let kind = word(data, desc);
                    let base = dest + word(data, desc + 12) as usize;
                    set(data, base, kind, index(kind, tx, ty), word(data, desc + 8));
                    put_mask(data, meta + 16, mask(data, meta + 16) | (1u64 << p));
                }
            }
            for p in 0..planes {
                let desc = 64 + p * 16;
                let op = word(data, desc + 4);
                if op == 0 {
                    continue;
                }
                let kind = word(data, desc);
                let offset = word(data, desc + 12) as usize;
                let value = get(data, source + offset, kind, index(kind, x, y));
                let def = word(data, desc + 8);
                if op == 2 && (mask(data, head + 8) & (1u64 << p) == 0 || value == def) {
                    continue;
                }
                // Setters do not create absent layer planes for their default value.
                let empty = if kind >= 3 { value == 0 } else { value == def };
                if op != 3 && mask(data, meta + 8) & (1u64 << p) == 0 && empty {
                    continue;
                }
                set(data, dest + offset, kind, index(kind, tx, ty), value);
                put_mask(data, meta + 8, mask(data, meta + 8) | (1u64 << p));
                put_mask(data, meta + 16, mask(data, meta + 16) | (1u64 << p));
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let kinds = [(4, 0), (3, 0), (4, 2), (1, 3)];
        let bytes = 8 + 2048 + 8 + AREA;
        let mut d = vec![0; 64 + 64 + 32 + bytes];
        for (p, v) in [
            (0, 0x5043_4c57),
            (4, 1),
            (8, 1),
            (12, 4),
            (16, 1),
            (20, 0),
            (24, 0),
            (28, 1),
            (32, 1),
        ] {
            d[p..p + 4].copy_from_slice(&u32::to_le_bytes(v));
        }
        let mut offset = 0;
        for (i, (kind, op)) in kinds.into_iter().enumerate() {
            let p = 64 + i * 16;
            for (at, v) in [(p, kind), (p + 4, op), (p + 12, offset as u32)] {
                d[at..at + 4].copy_from_slice(&v.to_le_bytes());
            }
            offset += length(kind);
        }
        put_mask(&mut d, 136, 15);
        d[160 + 8] = 0xff;
        d[160 + 8 + 2048] = 1;
        for x in 0..8 {
            d[160 + 8 + 2048 + 8 + x] = x as u8 + 1;
        }
        d
    }
    #[test]
    fn overlapping_chunk_clear_changes_the_live_source() {
        let mut d = fixture();
        copy(&mut d).unwrap();
        assert_eq!(get(&d, 160 + 8 + 2048, 4, 0), 0);
        assert_eq!(
            &d[160 + 8 + 2048 + 8..160 + 8 + 2048 + 16],
            &[1, 1, 2, 3, 4, 5, 6, 7]
        );
        assert_eq!(mask(&d, 144), 12);
    }
    #[test]
    fn invalid_frame_is_unchanged() {
        let mut d = fixture();
        d[100] = 9;
        let before = d.clone();
        assert!(copy(&mut d).is_err());
        assert_eq!(d, before);
        let mut d = fixture();
        d.push(0);
        let before = d.clone();
        assert!(copy(&mut d).is_err());
        assert_eq!(d, before);
    }
}
