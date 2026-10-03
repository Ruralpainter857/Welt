//! WHTB v1/v2/v3 groups all intersecting tiles to preserve the global X/Y theme RNG order.
//! One frame carries forces, a theme, and compact planes reused by both passes.
//! V3 adds mode 5 (smooth), border offset/count at bytes 88/92, and reserves bytes 96..128.
//! Border floats precede the theme: left/right are 5 x (height + 10), top/bottom width x 5,
//! each in X-major order. Interior heights are captured from packed planes, never recopied by Java.
//! All neighbourhood sums retain Java's X/Y addition order; the worker snapshot is reused.
use crate::height_map_import::{Plane, Theme, TileState};
use welt_core::{error::WeltError, rng::JavaRandom};
pub const MAX_BYTES: usize = 4 * 1024 * 1024;
const AREA: usize = 16384;
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn long(d: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn length(k: u32) -> usize {
    match k {
        0 => 65536,
        1 => 16384,
        2 => 8192,
        3 => 2048,
        4 => 8,
        _ => 0,
    }
}
struct TileWork {
    base: usize,
    tx: i32,
    ty: i32,
    planes: [Plane; 64],
    state: TileState,
    heights: Vec<i32>,
    terrains: Vec<u8>,
    changed: Vec<bool>,
}
impl Default for TileWork {
    fn default() -> Self {
        Self {
            base: 0,
            tx: 0,
            ty: 0,
            planes: [Plane { kind: 0, base: 0 }; 64],
            state: TileState {
                present: 0,
                changed: 0,
                order: [255; 64],
                count: 0,
            },
            heights: vec![0; AREA],
            terrains: vec![0; AREA],
            changed: vec![false; AREA],
        }
    }
}
#[derive(Default)]
pub struct BrushScratch {
    theme: Theme,
    tiles: Vec<TileWork>,
    input: Vec<f32>,
}
/// Reject the complete frame before changing packed planes or advancing the RNG.
pub fn apply(d: &mut [u8], s: &mut BrushScratch) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 128
        || d.len() > MAX_BYTES
        || word(d, 0) != 0x42544857
        || !matches!(word(d, 4), 1..=3)
        || word(d, 8) as usize != d.len()
        || d[if word(d, 4) == 3 { 96 } else { 88 }..128]
            .iter()
            .any(|v| *v != 0)
    {
        return Err(bad);
    }
    let n = word(d, 12) as usize;
    let nt = word(d, 16) as usize;
    let min = word(d, 20) as i32;
    let max = word(d, 24) as i32;
    let mode = word(d, 28);
    let value = f32::from_bits(word(d, 32));
    let low = f32::from_bits(word(d, 36));
    let high = f32::from_bits(word(d, 40));
    let ox = word(d, 44) as i32;
    let oy = word(d, 48) as i32;
    let w = word(d, 52) as usize;
    let h = word(d, 56) as usize;
    let forces = word(d, 60) as usize;
    let theme = word(d, 64) as usize;
    let start = word(d, 68) as usize;
    let step = word(d, 72) as usize;
    let beach = word(d, 76) as i32;
    let smooth = word(d, 4) == 3;
    let halo_count = if smooth && w <= 246 && h <= 246 {
        10 * (w + h + 10)
    } else {
        0
    };
    if !(3..=64).contains(&n)
        || nt > 9
        || mode
            > if word(d, 4) == 1 {
                1
            } else if smooth {
                5
            } else {
                4
            }
        || !(1..=256).contains(&w)
        || !(1..=256).contains(&h)
        || !(1..=65536).contains(&(i64::from(max) - i64::from(min)))
        || !(0..=255).contains(&beach)
        || forces != 128 + n * 16
        || smooth
            && (mode != 5
                || w > 246
                || h > 246
                || i64::from(ox) - 5 < i64::from(i32::MIN)
                || i64::from(oy) - 5 < i64::from(i32::MIN)
                || i64::from(ox) + w as i64 + 4 > i64::from(i32::MAX)
                || i64::from(oy) + h as i64 + 4 > i64::from(i32::MAX)
                || word(d, 88) as usize != forces + w * h * 4
                || word(d, 92) as usize != halo_count)
        || theme != forces + w * h * 4 + halo_count * 4
        || theme + 32 > d.len()
        || i64::from(ox) + w as i64 - 1 > i64::from(i32::MAX)
        || i64::from(oy) + h as i64 - 1 > i64::from(i32::MAX)
    {
        return Err(bad);
    }
    let mut offsets = [0usize; 64];
    let mut kinds = [0u32; 64];
    let mut bytes = 0;
    for p in 0..n {
        let desc = 128 + p * 16;
        let k = word(d, desc);
        let role = word(d, desc + 4);
        let default = word(d, desc + 8);
        let len = length(k);
        if len == 0
            || word(d, desc + 12) as usize != bytes
            || role != if p < 3 { p as u32 } else { 3 }
            || p < 2 && k != 0
            || p == 2 && k != 1
            || p >= 3 && k == 0
            || default
                > match k {
                    0 => u32::MAX,
                    1 => 255,
                    2 => 15,
                    _ => 1,
                }
        {
            return Err(bad);
        }
        kinds[p] = k;
        offsets[p] = bytes;
        bytes += len;
    }
    if step != 288 + bytes || start.checked_add(nt * step) != Some(d.len()) || start < theme + 32 {
        return Err(bad);
    }
    if s.tiles.len() < nt {
        s.tiles.resize_with(nt, TileWork::default);
    }
    for t in 0..nt {
        let b = start + t * step;
        let tile = &mut s.tiles[t];
        if d[b..b + 256].iter().any(|v| *v != 0) || long(d, b + 280) != 0 || long(d, b + 272) != 0 {
            return Err(bad);
        }
        tile.base = b;
        tile.tx = word(d, b + 256) as i32;
        tile.ty = word(d, b + 260) as i32;
        let tx = i64::from(tile.tx) * 128;
        let ty = i64::from(tile.ty) * 128;
        if tx > i64::from(ox) + w as i64 - 1
            || tx + 127 < i64::from(ox)
            || ty > i64::from(oy) + h as i64 - 1
            || ty + 127 < i64::from(oy)
        {
            return Err(bad);
        }
        for p in 0..n {
            tile.planes[p] = Plane {
                kind: kinds[p],
                base: b + 288 + offsets[p],
            };
        }
        tile.state = TileState {
            present: long(d, b + 264),
            changed: 0,
            order: [255; 64],
            count: 0,
        };
        tile.changed.fill(false);
    }
    for a in 0..nt {
        for b in 0..a {
            if s.tiles[a].tx == s.tiles[b].tx && s.tiles[a].ty == s.tiles[b].ty {
                return Err(bad);
            }
        }
    }
    let dummy = [Plane { kind: 0, base: 0 }; 64];
    let mut descriptors = dummy;
    for p in 0..n {
        descriptors[p].kind = kinds[p];
    }
    if s.theme.read(d, theme, &descriptors[..n], beach)? != start {
        return Err(bad);
    }
    let mut random = JavaRandom::from_lcg_state(long(d, 80)).ok_or(bad)?;
    let tall = i64::from(max) - i64::from(min) > 256;
    if smooth {
        let iw = w + 10;
        let ih = h + 10;
        let halo = word(d, 88) as usize;
        let left = 5 * ih;
        s.input.resize(iw * ih, -f32::MAX);
        // Capture the immutable neighbourhood before any output plane changes.
        for x in 0..iw {
            for y in 0..ih {
                let border = if x < 5 {
                    Some(x * ih + y)
                } else if x >= w + 5 {
                    Some(left + (x - w - 5) * ih + y)
                } else if y < 5 {
                    Some(2 * left + (x - 5) * 5 + y)
                } else if y >= h + 5 {
                    Some(2 * left + w * 5 + (x - 5) * 5 + y - h - 5)
                } else {
                    None
                };
                s.input[x * ih + y] = if let Some(i) = border {
                    f32::from_bits(word(d, halo + i * 4))
                } else {
                    let wx = ox + (x - 5) as i32;
                    let wy = oy + (y - 5) as i32;
                    s.tiles[..nt]
                        .iter()
                        .find(|t| t.tx == wx >> 7 && t.ty == wy >> 7)
                        .map(|t| {
                            t.planes[0].get(d, (wx & 127) as usize + (wy & 127) as usize * 128)
                                as i32 as f32
                                / 256.0
                                + min as f32
                        })
                        .unwrap_or(-f32::MAX)
                };
            }
        }
    }
    // Height decisions use exactly the scalar float expression before quantization.
    for x in 0..w {
        for y in 0..h {
            let wx = ox + x as i32;
            let wy = oy + y as i32;
            let Some(t) = s.tiles[..nt]
                .iter_mut()
                .find(|t| t.tx == wx >> 7 && t.ty == wy >> 7)
            else {
                continue;
            };
            let i = (wx & 127) as usize + (wy & 127) as usize * 128;
            let force = f32::from_bits(word(d, forces + (x * h + y) * 4));
            if !(force > 0.0) {
                continue;
            }
            let current = t.planes[0].get(d, i) as i32 as f32 / 256.0 + min as f32;
            let target = match mode {
                0 => java_min(current + value, high),
                1 => java_max(current - value, low),
                5 => {
                    let mut total = 0.0f32;
                    let mut count = 0;
                    for sx in x..=x + 10 {
                        for sy in y..=y + 10 {
                            let sample = s.input[sx * (h + 10) + sy];
                            if sample != -f32::MAX {
                                total += sample;
                                count += 1;
                            }
                        }
                    }
                    total / count as f32
                }
                _ => value,
            };
            let edited = force * target + (1.0 - force) * current;
            let write = match mode {
                2 | 5 => true,
                0 | 3 => edited > current,
                _ => edited < current,
            };
            if write {
                let raw = ((edited - min as f32) * 256.0) as i32;
                t.state.set(
                    d,
                    &t.planes[..n],
                    0,
                    i,
                    if tall { raw as u32 } else { raw as u16 as u32 },
                    true,
                );
                t.changed[i] = true;
            }
        }
    }
    for t in &mut s.tiles[..nt] {
        for i in 0..AREA {
            let height = t.planes[0].get(d, i) as i32 as f32 / 256.0 + min as f32;
            t.heights[i] = (f64::from(height) + 0.5).floor() as i32;
        }
        s.theme
            .prepare_selected_terrains(&t.heights, &t.changed, &mut t.terrains);
    }
    // This global traversal is essential: processing one whole tile at a time changes bit-layer draws.
    for x in 0..w {
        for y in 0..h {
            let wx = ox + x as i32;
            let wy = oy + y as i32;
            let Some(t) = s.tiles[..nt]
                .iter_mut()
                .find(|t| t.tx == wx >> 7 && t.ty == wy >> 7)
            else {
                continue;
            };
            let i = (wx & 127) as usize + (wy & 127) as usize * 128;
            if t.changed[i] {
                s.theme.apply_cell(
                    d,
                    &t.planes[..n],
                    &mut t.state,
                    i,
                    (t.heights[i], t.terrains[i]),
                    &mut random,
                );
            }
        }
    }
    for t in &s.tiles[..nt] {
        d[t.base + 140..t.base + 204].copy_from_slice(&t.state.order);
        d[t.base + 204..t.base + 208].copy_from_slice(&(t.state.count as u32).to_le_bytes());
        d[t.base + 264..t.base + 272].copy_from_slice(&t.state.present.to_le_bytes());
        d[t.base + 272..t.base + 280].copy_from_slice(&t.state.changed.to_le_bytes());
    }
    d[80..88].copy_from_slice(&random.lcg_state().to_le_bytes());
    Ok(())
}
fn java_min(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == 0.0 && b == 0.0 {
        f32::from_bits(a.to_bits() | b.to_bits())
    } else {
        a.min(b)
    }
}
fn java_max(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == 0.0 && b == 0.0 {
        f32::from_bits(a.to_bits() & b.to_bits())
    } else {
        a.max(b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn put(d: &mut [u8], p: usize, v: u32) {
        d[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn frame() -> Vec<u8> {
        let n = 4;
        let forces = 128 + n * 16;
        let theme = forces + 16;
        let theme_len = 32 + 384 * 4 + 4 + 384 * 4;
        let start = theme + theme_len;
        let bytes = 65536 * 2 + 16384 + 2048;
        let step = 288 + bytes;
        let mut d = vec![0; start + 4 * step];
        for (p, v) in [
            (0, 0x42544857),
            (4, 1),
            (8, d.len() as u32),
            (12, 4),
            (16, 4),
            (20, (-64i32) as u32),
            (24, 320),
            (28, 0),
            (32, 8f32.to_bits()),
            (36, (-64f32).to_bits()),
            (40, 319f32.to_bits()),
            (44, 127),
            (48, 127),
            (52, 2),
            (56, 2),
            (60, forces as u32),
            (64, theme as u32),
            (68, start as u32),
            (72, step as u32),
            (76, 1),
        ] {
            put(&mut d, p, v);
        }
        d[80..88].copy_from_slice(&JavaRandom::new(9).lcg_state().to_le_bytes());
        let mut off = 0;
        for (p, k) in [0, 0, 1, 3].into_iter().enumerate() {
            put(&mut d, 128 + p * 16, k);
            put(&mut d, 132 + p * 16, p as u32);
            put(&mut d, 140 + p * 16, off);
            off += length(k) as u32;
        }
        for i in 0..4 {
            put(&mut d, forces + i * 4, 1f32.to_bits());
        }
        put(&mut d, theme, (-64i32) as u32);
        put(&mut d, theme + 4, 320);
        put(&mut d, theme + 8, 62);
        put(&mut d, theme + 24, 1);
        put(&mut d, theme + 32 + 384 * 4, 3);
        for i in 0..384 {
            put(&mut d, theme + 36 + 384 * 4 + i * 4, 8);
        }
        for t in 0..4 {
            let b = start + t * step;
            put(&mut d, b + 256, (t / 2) as u32);
            put(&mut d, b + 260, (t % 2) as u32);
            d[b + 264..b + 272].copy_from_slice(&7u64.to_le_bytes());
            for i in 0..AREA {
                put(&mut d, b + 288 + i * 4, 32000);
            }
        }
        d
    }
    #[test]
    fn grouped_theme_commits_exact_global_draws_and_reuses_workspaces() {
        let original = frame();
        let mut d = original.clone();
        let mut s = BrushScratch::default();
        apply(&mut d, &mut s).unwrap();
        let mut rng = JavaRandom::new(9);
        for _ in 0..4 {
            rng.next_int_bound(15);
        }
        assert_eq!(long(&d, 80), rng.lcg_state());
        let capacities: Vec<_> = s
            .tiles
            .iter()
            .map(|t| {
                (
                    t.heights.capacity(),
                    t.terrains.capacity(),
                    t.changed.capacity(),
                )
            })
            .collect();
        let expected = d.clone();
        d = original;
        apply(&mut d, &mut s).unwrap();
        assert_eq!(d, expected);
        assert_eq!(
            capacities,
            s.tiles
                .iter()
                .map(|t| (
                    t.heights.capacity(),
                    t.terrains.capacity(),
                    t.changed.capacity()
                ))
                .collect::<Vec<_>>()
        );
    }
    #[test]
    fn flatten_modes_and_legacy_rejection_preserve_the_frame_contract() {
        for mode in 2..=4 {
            let mut data = frame();
            put(&mut data, 4, 2);
            put(&mut data, 28, mode);
            put(&mut data, 32, 64.0f32.to_bits());
            assert!(apply(&mut data, &mut BrushScratch::default()).is_ok());
            let mut legacy = frame();
            put(&mut legacy, 28, mode);
            let before = legacy.clone();
            assert!(apply(&mut legacy, &mut BrushScratch::default()).is_err());
            assert_eq!(before, legacy);
        }
    }
    fn smooth_frame() -> Vec<u8> {
        let old = frame();
        let forces = word(&old, 60) as usize;
        let old_theme = word(&old, 64) as usize;
        let old_start = word(&old, 68) as usize;
        let halo_count = 140;
        let mut data = vec![0; old.len() + halo_count * 4];
        data[..old_theme].copy_from_slice(&old[..old_theme]);
        data[old_theme + halo_count * 4..].copy_from_slice(&old[old_theme..]);
        let size = data.len() as u32;
        for (p, v) in [
            (4, 3),
            (8, size),
            (28, 5),
            (64, (old_theme + halo_count * 4) as u32),
            (68, (old_start + halo_count * 4) as u32),
            (88, (forces + 16) as u32),
            (92, halo_count as u32),
        ] {
            put(&mut data, p, v);
        }
        for i in 0..halo_count {
            put(&mut data, old_theme + i * 4, 61.0f32.to_bits());
        }
        data
    }
    #[test]
    fn smoothing_shares_planes_theme_rng_and_reuses_the_snapshot() {
        let original = smooth_frame();
        let mut data = original.clone();
        let mut scratch = BrushScratch::default();
        apply(&mut data, &mut scratch).unwrap();
        let start = word(&data, 68) as usize;
        let step = word(&data, 72) as usize;
        for t in 0..4 {
            let i = if t / 2 == 0 { 127 } else { 0 } + if t % 2 == 0 { 127 * 128 } else { 0 };
            assert_eq!(word(&data, start + t * step + 288 + i * 4), 32000);
        }
        let mut random = JavaRandom::new(9);
        for _ in 0..4 {
            random.next_int_bound(15);
        }
        assert_eq!(long(&data, 80), random.lcg_state());
        let capacity = scratch.input.capacity();
        let expected = data;
        let mut repeated = original;
        apply(&mut repeated, &mut scratch).unwrap();
        assert_eq!(expected, repeated);
        assert_eq!(capacity, scratch.input.capacity());
    }
    #[test]
    fn malformed_smoothing_border_is_atomic() {
        for (p, v) in [
            (4, 2),
            (28, 4),
            (52, 247),
            (88, 0),
            (92, 139),
            (96, 1),
            (44, i32::MIN as u32),
        ] {
            let mut data = smooth_frame();
            put(&mut data, p, v);
            let before = data.clone();
            assert!(apply(&mut data, &mut BrushScratch::default()).is_err());
            assert_eq!(before, data);
        }
    }
    #[test]
    fn malformed_group_is_atomic() {
        let original = frame();
        for (p, v) in [
            (12, 65),
            (16, 10),
            (60, 128),
            (64, u32::MAX),
            (72, 0),
            (76, 256),
            (84, 1 << 16),
        ] {
            let mut d = original.clone();
            put(&mut d, p, v);
            let before = d.clone();
            assert!(apply(&mut d, &mut BrushScratch::default()).is_err());
            assert_eq!(d, before);
        }
        let start = word(&original, 68) as usize;
        let step = word(&original, 72) as usize;
        let mut d = original;
        put(&mut d, start + step + 260, 0);
        let before = d.clone();
        assert!(apply(&mut d, &mut BrushScratch::default()).is_err());
        assert_eq!(d, before);
    }
}
