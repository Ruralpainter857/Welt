//! WGLY v1: rotate a packed Java glyph crop and edit up to three tile planes.
//! Header (256 bytes, little endian): magic/version/frame bytes/plane count,
//! destination x/y/width/height at 16/20/24/28, angle at 32, packed row stride
//! at 36, leading bit offset at 40, source width/height at 44/48, bitmap/tile
//! record offsets at 52/56. Plane defaults and roles are at 64 and 80.
//! Output first-mutation order occupies 140..204, with its count at 204.
//! Descriptors (16 bytes): packed kind, setter/max operation, target, offset.
//! Bitmap bits are high bit first, matching TYPE_BYTE_BINARY. Tile planes use
//! selection-copy packing, including low-bit-first block/chunk bit layers.
//! Rendering stays with Java's font engine; no font substitution occurs here.

use crate::{
    error::WeltError,
    selection_copy::{get, index, length, set},
};
pub const MAX_BYTES: usize = 64 * 1024;
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn long(d: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn put_long(d: &mut [u8], p: usize, v: u64) {
    d[p..p + 8].copy_from_slice(&v.to_le_bytes());
}
#[derive(Clone, Copy, Default)]
struct Plane {
    kind: u32,
    op: u32,
    target: u32,
    default: u32,
    base: usize,
    role: u32,
}

/// The frame is validated before its pixels, layer presence or change masks mutate.
/// The function performs no heap allocation and needs no image-sized scratch.
pub fn paint(d: &mut [u8]) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 256
        || d.len() > MAX_BYTES
        || word(d, 0) != 0x594c4757
        || word(d, 4) != 1
        || word(d, 8) as usize != d.len()
        || word(d, 60) != 0
        || d[92..140].iter().any(|b| *b != 0)
        || d[208..256].iter().any(|b| *b != 0)
    {
        return Err(bad);
    }
    let n = word(d, 12) as usize;
    let x = word(d, 16) as usize;
    let y = word(d, 20) as usize;
    let w = word(d, 24) as usize;
    let h = word(d, 28) as usize;
    let angle = word(d, 32);
    let stride = word(d, 36) as usize;
    let bit = word(d, 40) as usize;
    let sw = word(d, 44) as usize;
    let sh = word(d, 48) as usize;
    let bitmap = word(d, 52) as usize;
    let meta = word(d, 56) as usize;
    if !(1..=3).contains(&n)
        || x >= 128
        || y >= 128
        || w == 0
        || h == 0
        || w > 128 - x
        || h > 128 - y
        || angle > 3
        || bit > 7
        || sw != if angle & 1 == 0 { w } else { h }
        || sh != if angle & 1 == 0 { h } else { w }
        || stride != (sw + bit).div_ceil(8)
        || bitmap != 256 + n * 16
        || meta != bitmap + stride * sh
        || meta + 32 > d.len()
        || long(d, meta + 24) != 0
    {
        return Err(bad);
    }
    let mut planes = [Plane::default(); 3];
    let mut bytes = 0;
    for (p, plane) in planes[..n].iter_mut().enumerate() {
        let desc = 256 + p * 16;
        let kind = word(d, desc);
        let op = word(d, desc + 4);
        let target = word(d, desc + 8);
        let default = word(d, 64 + p * 4);
        let role = word(d, 80 + p * 4);
        let max = match kind {
            1 => 255,
            2 => 15,
            3 | 4 => 1,
            _ => return Err(bad),
        };
        if op > 1
            || op == 1 && kind >= 3
            || role != 2 && role != 3
            || role == 2 && kind != 1
            || target > max
            || default > max
            || kind >= 3 && default != 0
            || word(d, desc + 12) as usize != bytes
        {
            return Err(bad);
        }
        *plane = Plane {
            kind,
            op,
            target,
            default,
            base: meta + 32 + bytes,
            role,
        };
        bytes += length(kind);
    }
    if meta + 32 + bytes != d.len() || long(d, meta + 8) >> n != 0 {
        return Err(bad);
    }
    let mut present = long(d, meta + 8);
    let mut changed = 0u64;
    let mut order = [255u8; 64];
    let mut count = 0;
    // Preserve original source X/Y order within each destination tile.
    for sx in 0..sw {
        for sy in 0..sh {
            let b = bit + sx;
            if d[bitmap + sy * stride + b / 8] & (1 << (7 - b % 8)) == 0 {
                continue;
            }
            let (dx, dy) = match angle {
                0 => (sx, sy),
                1 => (sy, sw - 1 - sx),
                2 => (sw - 1 - sx, sh - 1 - sy),
                _ => (sh - 1 - sy, sx),
            };
            for (p, plane) in planes[..n].iter().enumerate() {
                let i = index(plane.kind, x + dx, y + dy);
                let value = get(d, plane.base, plane.kind, i);
                if plane.op == 1 && value >= plane.target
                    || plane.role == 3 && present & (1 << p) == 0 && plane.target == plane.default
                {
                    continue;
                }
                // Setters still notify when an already allocated plane keeps its value.
                if changed & (1 << p) == 0 {
                    order[count] = p as u8;
                    count += 1;
                }
                set(d, plane.base, plane.kind, i, plane.target);
                present |= 1 << p;
                changed |= 1 << p;
            }
        }
    }
    d[140..204].copy_from_slice(&order);
    d[204..208].copy_from_slice(&(count as u32).to_le_bytes());
    put_long(d, meta + 8, present);
    put_long(d, meta + 16, changed);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn put(d: &mut [u8], p: usize, v: u32) {
        d[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn frame(kind: u32, target: u32, default: u32, angle: u32) -> Vec<u8> {
        let bitmap = 272;
        let meta = 280;
        let size = meta + 32 + length(kind);
        let mut d = vec![0; size];
        for (p, v) in [
            (0, 0x594c4757),
            (4, 1),
            (8, size as u32),
            (12, 1),
            (16, 5),
            (20, 6),
            (24, 8),
            (28, 8),
            (32, angle),
            (36, 1),
            (44, 8),
            (48, 8),
            (52, bitmap as u32),
            (56, meta as u32),
            (64, default),
            (80, 3),
            (256, kind),
            (264, target),
        ] {
            put(&mut d, p, v);
        }
        let packed = if kind == 2 {
            default | default << 4
        } else if kind >= 3 {
            if default == 0 {
                0
            } else {
                255
            }
        } else {
            default
        };
        d[meta + 32..].fill(packed as u8);
        d[bitmap + 3] = 1 << 5;
        d[bitmap] = 1;
        d
    }
    #[test]
    fn glyphs_rotate_without_changing_other_cells() {
        for angle in 0..4 {
            let mut d = frame(2, 10, 7, angle);
            paint(&mut d).unwrap();
            let mut expected = vec![7; 16384];
            for (sx, sy) in [(2, 3), (7, 0)] {
                let (dx, dy) = match angle {
                    0 => (sx, sy),
                    1 => (sy, 7 - sx),
                    2 => (7 - sx, 7 - sy),
                    _ => (7 - sy, sx),
                };
                expected[5 + dx + (6 + dy) * 128] = 10;
            }
            for (i, value) in expected.iter().enumerate() {
                assert_eq!(get(&d, 312, 2, i), *value);
            }
            assert_eq!(long(&d, 288), 1);
            assert_eq!(long(&d, 296), 1);
        }
    }
    #[test]
    fn default_setters_do_not_allocate_absent_planes() {
        for (kind, default) in [(1, 255), (2, 7), (3, 0), (4, 0)] {
            let mut d = frame(kind, default, default, 0);
            paint(&mut d).unwrap();
            assert_eq!(long(&d, 288), 0);
            assert_eq!(long(&d, 296), 0);
            // Existing planes notify even when a setter leaves their value unchanged.
            put_long(&mut d, 288, 1);
            paint(&mut d).unwrap();
            assert_eq!(long(&d, 296), 1);
        }
    }
    #[test]
    fn maximum_paint_keeps_stronger_values_and_chunk_bits_keep_their_addressing() {
        let mut d = frame(2, 6, 7, 0);
        put(&mut d, 260, 1);
        paint(&mut d).unwrap();
        assert_eq!(long(&d, 296), 0);
        let mut d = frame(4, 1, 0, 2);
        paint(&mut d).unwrap();
        assert_eq!(d[312], 1);
        assert_eq!(long(&d, 296), 1);
    }
    #[test]
    fn empty_glyphs_and_bad_frames_leave_planes_unchanged() {
        let mut d = frame(1, 42, 255, 0);
        d[272..280].fill(0);
        let before = d[280..].to_vec();
        paint(&mut d).unwrap();
        assert_eq!(&d[280..], before);
        for (p, v) in [
            (0, 0),
            (4, 2),
            (8, 1),
            (12, 4),
            (16, 128),
            (24, 0),
            (28, 129),
            (32, 4),
            (36, 2),
            (40, 8),
            (44, 7),
            (52, 0),
            (56, 0),
            (60, 1),
            (80, 0),
            (92, 1),
            (208, 1),
            (256, 0),
            (260, 2),
            (264, 256),
            (268, 1),
        ] {
            let mut d = frame(1, 42, 255, 0);
            put(&mut d, p, v);
            let before = d.clone();
            assert_eq!(paint(&mut d), Err(WeltError::IllegalArgument));
            assert_eq!(d, before);
        }
    }
}
