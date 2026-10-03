//! WSTP v1: complete nibble brush lines over one packed destination tile.
//! Brush strengths are sampled once by Java. All centers and overlapping edits stay in Rust.
//! Little-endian header: magic/version/bytes at 0/4/8, endpoints 12..24, tile 28/32,
//! brush rectangle 36..48, dynamic/undo/default/meta/pixel at 52/56/60/64/68.
//! Strengths begin at 256; meta has tile coordinates, presence/change masks and
//! reserved bytes (32 total), followed by the 8192-byte low-nibble-first plane.
//! Output mutation order/count occupy 140..204/204, matching grouped Tile application.
use crate::nibble_paint::{target as nibble_target, NibblePaintMode};
use crate::{
    error::WeltError,
    line_raster::rasterize_line_centers,
    selection_copy::{get, set},
};
use std::cell::RefCell;
pub const MAX_BYTES: usize = 1024 * 1024;
thread_local! { static CENTERS: RefCell<Vec<i32>> = const { RefCell::new(Vec::new()) }; }
fn word(d: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn float(d: &[u8], p: usize) -> f32 {
    f32::from_bits(word(d, p) as u32)
}

/// Validates the complete frame before changing any layer values or metadata.
pub fn paint(d: &mut [u8]) -> Result<(), WeltError> {
    if d.len() >= 256 && word(d, 4) == 2 {
        return crate::line_set_stroke::paint(d);
    }
    let bad = WeltError::IllegalArgument;
    if d.len() < 256
        || d.len() > MAX_BYTES
        || word(d, 0) != 0x50545357
        || word(d, 4) != 1
        || word(d, 8) as usize != d.len()
        || d[72..140].iter().chain(d[208..256].iter()).any(|v| *v != 0)
    {
        return Err(bad);
    }
    let (x1, y1, x2, y2) = (word(d, 12), word(d, 16), word(d, 20), word(d, 24));
    let (tx, ty) = (word(d, 28), word(d, 32));
    let (bx, by, bw, bh) = (word(d, 36), word(d, 40), word(d, 44), word(d, 48));
    let dynamic = float(d, 52);
    let undo = word(d, 56);
    let default = word(d, 60);
    let meta = word(d, 64) as usize;
    let pixel = word(d, 68);
    let points = (i64::from(x2) - i64::from(x1))
        .abs()
        .max((i64::from(y2) - i64::from(y1)).abs()) as usize
        + 1;
    if [x1, y1, x2, y2]
        .iter()
        .any(|v| !(-1_048_576..=1_048_576).contains(v))
        || points > 65_536
        || !(-8194..=8194).contains(&tx)
        || !(-8194..=8194).contains(&ty)
        || !(1..=256).contains(&bw)
        || !(1..=256).contains(&bh)
        || !(-256..=256).contains(&bx)
        || !(-256..=256).contains(&by)
        || !dynamic.is_finite()
        || !(0.0..=1.0).contains(&dynamic)
        || !(0..=1).contains(&undo)
        || !(0..=15).contains(&default)
        || !(0..=1).contains(&pixel)
        || pixel == 1 && (bx != 0 || by != 0 || bw != 1 || bh != 1)
        || meta != 256 + bw as usize * bh as usize * 4
        || meta + 32 + 8192 != d.len()
        || word(d, meta) != tx
        || word(d, meta + 4) != ty
        || u64::from_le_bytes(d[meta + 8..meta + 16].try_into().unwrap()) > 1
        || d[meta + 16..meta + 32].iter().any(|v| *v != 0)
    {
        return Err(bad);
    }
    for p in (256..meta).step_by(4) {
        let v = float(d, p);
        if !v.is_finite() || !(0.0..=1.0).contains(&v) {
            return Err(bad);
        }
    }
    CENTERS.with(|slot| {
        let mut centers = slot.borrow_mut();
        centers.resize(points * 2, 0);
        rasterize_line_centers(x1, y1, x2, y2, &mut centers).map_err(|_| bad)?;
        let (ox, oy) = (tx * 128, ty * 128);
        let base = meta + 32;
        let initially_present = d[meta + 8] != 0;
        let mut changed = false;
        for point in 0..points {
            let (sx, sy) = (centers[point * 2] + bx, centers[point * 2 + 1] + by);
            let (ex, ey) = (sx + bw - 1, sy + bh - 1);
            let single = sx.div_euclid(128) == ex.div_euclid(128)
                && sy.div_euclid(128) == ey.div_euclid(128);
            for y in sy.max(oy)..=ey.min(oy + 127) {
                for x in sx.max(ox)..=ex.min(ox + 127) {
                    let strength =
                        dynamic * float(d, 256 + ((x - sx) + (y - sy) * bw) as usize * 4);
                    if strength == 0.0 && pixel == 0 {
                        continue;
                    }
                    let target = if pixel == 1 {
                        if undo == 1 {
                            0
                        } else {
                            nibble_target(NibblePaintMode::Apply, float(d, 256)) as u32
                        }
                    } else if undo == 0 {
                        nibble_target(NibblePaintMode::Apply, strength) as u32
                    } else if single {
                        nibble_target(NibblePaintMode::RemoveRounded, strength) as u32
                    } else {
                        nibble_target(NibblePaintMode::RemoveTruncated, strength) as u32
                    };
                    let index = (x - ox + (y - oy) * 128) as usize;
                    let old = get(d, base, 2, index);
                    if undo == 0 && target > old
                        || undo == 1 && target < old
                        || pixel == 1
                            && undo == 1
                            && (changed || initially_present || target != default as u32)
                    {
                        set(d, base, 2, index, target);
                        changed = true;
                    }
                }
            }
        }
        d[140..204].fill(255);
        d[204..208].copy_from_slice(&(u32::from(changed)).to_le_bytes());
        if changed {
            d[140] = 0;
            d[meta + 8..meta + 16].copy_from_slice(&1u64.to_le_bytes());
            d[meta + 16..meta + 24].copy_from_slice(&1u64.to_le_bytes());
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame(undo: i32) -> Vec<u8> {
        let mut d = vec![0; 256 + 9 * 4 + 32 + 8192];
        let meta = 292;
        for (p, v) in [
            (0, 0x50545357),
            (4, 1),
            (8, d.len() as i32),
            (12, 124),
            (16, 64),
            (20, 130),
            (24, 64),
            (28, 0),
            (32, 0),
            (36, -1),
            (40, -1),
            (44, 3),
            (48, 3),
            (52, 0.73f32.to_bits() as i32),
            (56, undo),
            (60, 3),
            (64, meta as i32),
        ] {
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for p in (256..292).step_by(4) {
            d[p..p + 4].copy_from_slice(&0.63f32.to_le_bytes());
        }
        d[324..].fill(0x33);
        d
    }
    #[test]
    fn overlap_and_boundary_rounding_match_explicit_stamps() {
        for undo in [0, 1] {
            let mut d = frame(undo);
            if undo == 1 {
                d[324..].fill(0xff);
            }
            paint(&mut d).unwrap();
            let target = if undo == 0 { 7 } else { 8 };
            for y in 63..=65 {
                for x in 123..128 {
                    assert_eq!(get(&d, 324, 2, x + y * 128), target);
                }
            }
            assert_eq!(word(&d, 204), 1);
            assert_eq!(word(&d, 308), 1);
        }
    }
    #[test]
    fn zero_radius_uses_brush_level_and_preserves_allocated_setter_notifications() {
        let mut d = vec![0u8; 256 + 4 + 32 + 8192];
        let meta = 260usize;
        for (p, v) in [
            (0, 0x50545357),
            (4, 1),
            (8, d.len() as i32),
            (12, 64),
            (16, 64),
            (20, 64),
            (24, 64),
            (44, 1),
            (48, 1),
            (52, 0f32.to_bits() as i32),
            (64, meta as i32),
            (68, 1),
        ] {
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        d[256..260].copy_from_slice(&0.63f32.to_le_bytes());
        paint(&mut d).unwrap();
        assert_eq!(get(&d, 292, 2, 64 + 64 * 128), 10);
        d[56..60].copy_from_slice(&1i32.to_le_bytes());
        d[meta + 16..meta + 24].fill(0);
        paint(&mut d).unwrap();
        assert_eq!(get(&d, 292, 2, 64 + 64 * 128), 0);
        d[meta + 16..meta + 24].fill(0);
        paint(&mut d).unwrap();
        assert_eq!(word(&d, 204), 1);
        d[meta + 8..meta + 24].fill(0);
        paint(&mut d).unwrap();
        assert_eq!(word(&d, 204), 0);
    }

    #[test]
    fn malformed_frames_leave_every_byte_unchanged() {
        for (p, v) in [
            (0, 0),
            (44, 0),
            (52, f32::NAN.to_bits() as i32),
            (56, 2),
            (64, 0),
            (256, 2f32.to_bits() as i32),
            (20, i32::MAX),
        ] {
            let mut d = frame(0);
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = d.clone();
            assert_eq!(paint(&mut d), Err(WeltError::IllegalArgument));
            assert_eq!(d, before);
        }
    }
}
