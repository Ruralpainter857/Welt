//! WSTP v2: complete constant-target terrain and binary-layer lines.
//! The v1 geometry header is followed by kind/role/target/flags at 72/76/80/84.
//! Flags 1 selects an exact Java Math.random selection mask. Its x/y/width/height,
//! offset and length occupy 88..108. Strengths start at 256, then tile metadata
//! and one packed plane, then the optional low-bit-first global selection mask.
//! Java owns random draws; Rust never substitutes or advances that shared stream.
use crate::{
    error::WeltError,
    line_raster::rasterize_line_centers,
    selection_copy::{index, length, set},
};
use std::cell::RefCell;
struct Scratch {
    centers: Vec<i32>,
    brush: Vec<u8>,
    selected: [u8; 2048],
}
impl Default for Scratch {
    fn default() -> Self {
        Self {
            centers: Vec::new(),
            brush: Vec::new(),
            selected: [0; 2048],
        }
    }
}
thread_local! {static SCRATCH:RefCell<Scratch>=RefCell::new(Scratch::default());}
fn word(d: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn float(d: &[u8], p: usize) -> f32 {
    f32::from_bits(word(d, p) as u32)
}
/// Validates all source planes, metadata and mask bounds before changing any bytes.
pub fn paint(d: &mut [u8]) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 256
        || d.len() > crate::line_stroke::MAX_BYTES
        || word(d, 0) != 0x50545357
        || word(d, 4) != 2
        || word(d, 8) as usize != d.len()
        || d[112..140]
            .iter()
            .chain(d[208..256].iter())
            .any(|v| *v != 0)
    {
        return Err(bad);
    }
    let (x1, y1, x2, y2) = (word(d, 12), word(d, 16), word(d, 20), word(d, 24));
    let (tx, ty) = (word(d, 28), word(d, 32));
    let (bx, by, bw, bh) = (word(d, 36), word(d, 40), word(d, 44), word(d, 48));
    let dynamic = float(d, 52);
    let undo = word(d, 56);
    let meta = word(d, 64) as usize;
    let pixel = word(d, 68);
    let kind = word(d, 72) as u32;
    let role = word(d, 76);
    let target = word(d, 80) as u32;
    let flags = word(d, 84);
    let points = (i64::from(x2) - i64::from(x1))
        .abs()
        .max((i64::from(y2) - i64::from(y1)).abs()) as usize
        + 1;
    if [x1, y1, x2, y2]
        .iter()
        .any(|v| !(-1_048_576..=1_048_576).contains(v))
        || points > 65536
        || !(-16384..=16384).contains(&tx)
        || !(-16384..=16384).contains(&ty)
        || !(1..=256).contains(&bw)
        || !(1..=256).contains(&bh)
        || !(-256..=256).contains(&bx)
        || !(-256..=256).contains(&by)
        || !(0.0..=1.0).contains(&dynamic)
        || !dynamic.is_finite()
        || !(0..=1).contains(&undo)
        || !(0..=1).contains(&pixel)
        || pixel == 1 && (bx != 0 || by != 0 || bw != 1 || bh != 1)
        || word(d, 60) != 0
        || !matches!((kind, role), (1, 2) | (3, 3) | (4, 3))
        || target > if kind == 1 { 255 } else { 1 }
        || !(0..=1).contains(&flags)
        || flags == 1 && pixel == 1
        || meta != 256 + bw as usize * bh as usize * 4
        || meta + 32 + length(kind) > d.len()
    {
        return Err(bad);
    }
    let bytes = length(kind);
    let base = meta + 32;
    let end = base + bytes;
    let present = u64::from_le_bytes(d[meta + 8..meta + 16].try_into().unwrap());
    if word(d, meta) != tx
        || word(d, meta + 4) != ty
        || present > 1
        || kind == 1 && present != 1
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
    let (mx, my, mw, mh, mask, mask_bytes) = (
        word(d, 88),
        word(d, 92),
        word(d, 96),
        word(d, 100),
        word(d, 104) as usize,
        word(d, 108) as usize,
    );
    if flags == 0 {
        if d[88..112].iter().any(|v| *v != 0) || end != d.len() {
            return Err(bad);
        }
    } else {
        if !(-2_097_152..=2_097_152).contains(&mx)
            || !(-2_097_152..=2_097_152).contains(&my)
            || !(1..=131072).contains(&mw)
            || !(1..=131072).contains(&mh)
        {
            return Err(bad);
        }
        let cells = (mw as usize).checked_mul(mh as usize).ok_or(bad)?;
        if cells > 256 * 16384
            || mask != end
            || mask_bytes != cells.div_ceil(8)
            || mask_bytes != d.len() - end
        {
            return Err(bad);
        }
        if cells % 8 != 0 && d[d.len() - 1] >> (cells % 8) != 0 {
            return Err(bad);
        }
    }
    SCRATCH.with(|slot| {
        let mut scratch = slot.borrow_mut();
        let Scratch {
            centers,
            brush,
            selected,
        } = &mut *scratch;
        selected.fill(0);
        let (ox, oy) = (tx * 128, ty * 128);
        if flags == 1 {
            for y in 0..128 {
                for x in 0..128 {
                    let (wx, wy) = (ox + x, oy + y);
                    if wx >= mx && wy >= my && wx - mx < mw && wy - my < mh {
                        let source = ((wx - mx) + (wy - my) * mw) as usize;
                        if d[mask + source / 8] & (1 << (source % 8)) != 0 {
                            let p = (x + y * 128) as usize;
                            selected[p / 8] |= 1 << (p % 8);
                        }
                    }
                }
            }
        } else {
            let area = (bw * bh) as usize;
            brush.resize(area, 0);
            for (p, value) in brush.iter_mut().enumerate() {
                *value = u8::from(pixel == 1 || dynamic * float(d, 256 + p * 4) > 0.75);
            }
            centers.resize(points * 2, 0);
            rasterize_line_centers(x1, y1, x2, y2, centers).map_err(|_| bad)?;
            for p in 0..points {
                let (sx, sy) = (centers[p * 2] + bx, centers[p * 2 + 1] + by);
                for y in sy.max(oy)..=(sy + bh - 1).min(oy + 127) {
                    for x in sx.max(ox)..=(sx + bw - 1).min(ox + 127) {
                        if brush[((x - sx) + (y - sy) * bw) as usize] != 0 {
                            let p = (x - ox + (y - oy) * 128) as usize;
                            selected[p / 8] |= 1 << (p % 8);
                        }
                    }
                }
            }
        }
        let mut changed = false;
        if kind == 1 || present != 0 || target != 0 {
            for (b, bits) in selected.iter().enumerate() {
                let mut bits = *bits;
                while bits != 0 {
                    let bit = bits.trailing_zeros() as usize;
                    bits &= bits - 1;
                    let p = b * 8 + bit;
                    set(d, base, kind, index(kind, p % 128, p / 128), target);
                    changed = true;
                }
            }
        }
        d[140..204].fill(255);
        d[204..208].copy_from_slice(&u32::from(changed).to_le_bytes());
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
    fn frame(kind: u32, role: i32, target: u32) -> Vec<u8> {
        let meta = 260;
        let mut d = vec![0; meta + 32 + length(kind)];
        for (p, v) in [
            (0, 0x50545357),
            (4, 2),
            (8, d.len() as i32),
            (12, 64),
            (16, 64),
            (20, 70),
            (24, 64),
            (44, 1),
            (48, 1),
            (52, 1f32.to_bits() as i32),
            (64, meta as i32),
            (68, 1),
            (72, kind as i32),
            (76, role),
            (80, target as i32),
        ] {
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        d[256..260].copy_from_slice(&1f32.to_le_bytes());
        if kind == 1 {
            d[meta + 8] = 1;
        }
        d
    }
    #[test]
    fn constant_setters_preserve_presence_and_allocated_notifications() {
        for (kind, role, target) in [(1, 2, 117), (3, 3, 1), (4, 3, 1)] {
            let mut d = frame(kind, role, target);
            paint(&mut d).unwrap();
            assert_eq!(d[268], 1);
            assert_eq!(d[276], 1);
            d[276..284].fill(0);
            paint(&mut d).unwrap();
            assert_eq!(word(&d, 204), 1);
        }
        let mut absent = frame(3, 3, 0);
        paint(&mut absent).unwrap();
        assert_eq!(word(&absent, 204), 0);
    }
    #[test]
    fn malformed_headers_never_change_any_byte() {
        for (p, v) in [
            (4, 0),
            (44, 0),
            (72, 2),
            (76, 0),
            (80, 256),
            (84, 2),
            (52, f32::NAN.to_bits() as i32),
            (104, 1),
        ] {
            let mut d = frame(1, 2, 117);
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = d.clone();
            assert_eq!(paint(&mut d), Err(WeltError::IllegalArgument));
            assert_eq!(d, before);
        }
    }
    #[test]
    fn prepared_mask_selects_only_encoded_pixels_and_rejects_dirty_tail() {
        let mut d = frame(1, 2, 117);
        let offset = d.len();
        d.extend_from_slice(&[0b0000_0101, 0b0000_0001]);
        for (p, v) in [
            (8, d.len() as i32),
            (68, 0),
            (84, 1),
            (88, 63),
            (92, 64),
            (96, 3),
            (100, 3),
            (104, offset as i32),
            (108, 2),
        ] {
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        let mut invalid = d.clone();
        invalid[offset + 1] |= 128;
        let before = invalid.clone();
        assert_eq!(paint(&mut invalid), Err(WeltError::IllegalArgument));
        assert_eq!(invalid, before);
        paint(&mut d).unwrap();
        for y in 0..128 {
            for x in 0..128 {
                let selected = matches!((x, y), (63, 64) | (65, 64) | (65, 66));
                assert_eq!(d[292 + x + y * 128], if selected { 117 } else { 0 });
            }
        }
    }
}
