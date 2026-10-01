//! WMIM v1: mapping gates, bounded raster sampling and one packed target plane.
//! All fields are little endian. The 256-byte header is followed by ordered
//! 16-byte gate records, an optional colour palette/input, a tile record,
//! packed output and an optional WBSM raster window. Validation and target
//! preparation complete before mutation; gate LCG states commit on success.
use crate::bitmap_import::BitmapSource;
use welt_core::{error::WeltError, rng::JavaRandom};
pub const MAX_BYTES: usize = 4 * 1024 * 1024;
const AREA: usize = 16384;
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn long(d: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn number(d: &[u8], p: usize) -> f64 {
    f64::from_bits(long(d, p))
}
fn put(d: &mut [u8], p: usize, v: u64) {
    d[p..p + 8].copy_from_slice(&v.to_le_bytes());
}
fn length(kind: u32) -> usize {
    match kind {
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
fn get(d: &[u8], base: usize, kind: u32, i: usize) -> i32 {
    match kind {
        1 => d[base + i] as i32,
        2 => ((d[base + i / 2] >> (i % 2 * 4)) & 15) as i32,
        _ => ((d[base + i / 8] >> (i % 8)) & 1) as i32,
    }
}
fn set(d: &mut [u8], base: usize, kind: u32, i: usize, value: i32) {
    match kind {
        1 => d[base + i] = value as u8,
        2 => {
            let shift = i % 2 * 4;
            d[base + i / 2] = (d[base + i / 2] & !(15 << shift)) | ((value as u8) << shift);
        }
        _ => {
            let bit = 1 << (i % 8);
            d[base + i / 8] = (d[base + i / 8] & !bit) | if value != 0 { bit } else { 0 };
        }
    }
}
fn minimum(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        f64::from_bits(a.to_bits() | b.to_bits())
    } else if a <= b {
        a
    } else {
        b
    }
}
fn maximum(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        f64::from_bits(a.to_bits() & b.to_bits())
    } else if a >= b {
        a
    } else {
        b
    }
}
fn round(value: f64) -> i64 {
    if value.is_nan() {
        0
    } else {
        let floor = value.floor();
        (if value - floor >= 0.5 {
            floor + 1.0
        } else {
            floor
        }) as i64
    }
}
struct Target {
    base: usize,
    kind: u32,
    role: u32,
    default: i32,
    present: bool,
    changed: bool,
}
impl Target {
    fn write(&mut self, d: &mut [u8], i: usize, value: i32) {
        if self.role == 3 && !self.present && value == self.default {
            return;
        }
        self.present = true;
        self.changed = true;
        set(d, self.base, self.kind, i, value);
    }
}
pub fn import(d: &mut [u8]) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 256
        || d.len() > MAX_BYTES
        || word(d, 0) != 0x4d49_4d57
        || word(d, 4) != 1
        || word(d, 8) as usize != d.len()
        || d[68..72].iter().any(|v| *v != 0)
        || d[128..256].iter().any(|v| *v != 0)
    {
        return Err(bad);
    }
    let kind = word(d, 12);
    let role = word(d, 16);
    let op = word(d, 20);
    let target = word(d, 24) as i32;
    let default = word(d, 28) as i32;
    let flags = word(d, 32);
    let maximum_value = word(d, 60) as i32;
    let lx = word(d, 36) as usize;
    let ly = word(d, 40) as usize;
    let rw = word(d, 44) as usize;
    let rh = word(d, 48) as usize;
    let wx = word(d, 52) as i32;
    let wy = word(d, 56) as i32;
    let colour = flags & 32 != 0;
    let discrete = flags & 4 != 0;
    let clear = flags & 3;
    let bytes = length(kind);
    let gate_count = word(d, 104) as usize;
    let gates = word(d, 108) as usize;
    let palette = word(d, 112) as usize;
    let palette_bytes = word(d, 64) as usize;
    let pixels = word(d, 116) as usize;
    let source_base = word(d, 120) as usize;
    let meta = word(d, 124) as usize;
    if bytes == 0
        || !(2..=3).contains(&role)
        || op > 12
        || flags & !55 != 0
        || clear == 3
        || role == 2 && clear != 0
        || lx >= 128
        || ly >= 128
        || rw == 0
        || rh == 0
        || rw > 128 - lx
        || rh > 128 - ly
        || gate_count > 8
        || gates != 256
        || default < 0
        || default
            > if kind == 1 {
                255
            } else if kind == 2 {
                15
            } else {
                1
            }
        || kind >= 3 && default != 0
        || maximum_value < 0
        || maximum_value
            > if kind == 1 {
                255
            } else if kind == 2 {
                15
            } else {
                1
            }
        || (role == 2) != (op == 0 || op == 4)
        || role == 2 && kind != 1
        || colour != (op == 12)
        || (!colour && discrete) != (op == 4 || op == 5)
        || matches!(op, 1 | 6) && kind < 3
        || matches!(op, 2 | 3 | 5 | 7 | 8 | 9 | 10 | 11 | 12) && kind >= 3
    {
        return Err(bad);
    }
    let gate_end = gates + gate_count * 16;
    if gate_end > d.len() {
        return Err(bad);
    }
    let mut streams: [JavaRandom; 8] = std::array::from_fn(|_| JavaRandom::new(0));
    for (g, stream) in streams[..gate_count].iter_mut().enumerate() {
        if !(1..=3).contains(&word(d, gates + g * 16)) || word(d, gates + g * 16 + 4) != 0 {
            return Err(bad);
        }
        *stream = JavaRandom::from_lcg_state(long(d, gates + g * 16 + 8)).ok_or(bad)?;
    }
    let expected_pixels = if colour || op == 4 {
        if palette != gate_end
            || palette_bytes == 0
            || palette_bytes > 4096
            || (colour && palette_bytes != 4096)
            || (!colour && palette_bytes > 256)
            || palette + palette_bytes > d.len()
        {
            return Err(bad);
        }
        if d[palette..palette + palette_bytes]
            .iter()
            .any(|v| *v as i32 > maximum_value)
        {
            return Err(bad);
        }
        palette + palette_bytes
    } else {
        if palette != 0 || palette_bytes != 0 {
            return Err(bad);
        }
        gate_end
    };
    let expected_meta = if colour {
        if pixels != expected_pixels {
            return Err(bad);
        }
        pixels + rw * rh * 4
    } else {
        if pixels != 0 {
            return Err(bad);
        }
        expected_pixels
    };
    if meta != expected_meta
        || meta + 32 + bytes > d.len()
        || long(d, meta + 8) > 1
        || long(d, meta + 16) != 0
        || long(d, meta + 24) != 0
    {
        return Err(bad);
    }
    let base = meta + 32;
    let end = base + bytes;
    let source = if colour {
        if source_base != 0 || end != d.len() {
            return Err(bad);
        }
        None
    } else {
        if source_base != end {
            return Err(bad);
        }
        Some(BitmapSource::read(d, source_base, wx, wy)?)
    };
    let low = number(d, 72);
    let high = number(d, 80);
    let max = number(d, 88);
    let threshold = number(d, 96);
    let mut values = [-1i32; AREA];
    for x in lx..lx + rw {
        for y in ly..ly + rh {
            let i = index(kind, x, y);
            let old = if clear != 0 {
                default
            } else {
                get(d, base, kind, i)
            };
            let value = if colour {
                let rgb = word(d, pixels + ((x - lx) + (y - ly) * rw) * 4);
                if rgb >> 24 <= 127 {
                    continue;
                }
                d[palette
                    + (((rgb >> 12) & 0xf00) | ((rgb >> 8) & 0xf0) | ((rgb >> 4) & 0xf)) as usize]
                    as i32
            } else {
                let mut mask = source.as_ref().unwrap().sample(
                    d,
                    wx.wrapping_add(x as i32),
                    wy.wrapping_add(y as i32),
                );
                if flags & 16 != 0 {
                    mask = maximum(minimum(mask, high), low);
                }
                if discrete {
                    mask = f64::from(mask as i32);
                }
                let mut selected = true;
                for (g, stream) in streams[..gate_count].iter_mut().enumerate() {
                    selected = match word(d, gates + g * 16) {
                        1 => mask >= threshold,
                        2 => {
                            mask >= high
                                || (mask > low && mask > stream.next_double() * (high - low) + low)
                        }
                        _ => mask > 0.0 && mask > stream.next_double() * max,
                    };
                    if !selected {
                        break;
                    }
                }
                if !selected {
                    continue;
                }
                match op {
                    0 | 2 => {
                        if mask.partial_cmp(&0.5) != Some(std::cmp::Ordering::Greater) {
                            continue;
                        }
                        target
                    }
                    1 => {
                        if target == 0
                            || mask.partial_cmp(&0.5) != Some(std::cmp::Ordering::Greater)
                        {
                            continue;
                        }
                        1
                    }
                    3 => target.max(old),
                    4 => {
                        let index = mask as i32;
                        if index < 0 || index as usize >= palette_bytes {
                            return Err(bad);
                        }
                        d[palette + index as usize] as i32
                    }
                    5 => {
                        let mask = mask as i32;
                        if mask == 0 {
                            continue;
                        }
                        mask.max(old)
                    }
                    6 => {
                        if mask.partial_cmp(&0.5) != Some(std::cmp::Ordering::Greater) {
                            continue;
                        }
                        1
                    }
                    7 => {
                        if mask.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
                            continue;
                        }
                        (round(mask).max(i64::from(old))) as i32
                    }
                    8 => {
                        if mask.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
                            continue;
                        }
                        round((mask - low) * f64::from(maximum_value) / (high - low)) as i32
                    }
                    9 => (round((mask - low) * f64::from(maximum_value) / (high - low)) as i32)
                        .max(old),
                    10 => {
                        if mask.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
                            continue;
                        }
                        round(mask * f64::from(maximum_value) / max) as i32
                    }
                    11 => (round(mask * f64::from(maximum_value) / max) as i32).max(old),
                    _ => return Err(bad),
                }
            };
            if value < 0 || value > maximum_value {
                return Err(bad);
            }
            values[x + y * 128] = value;
        }
    }
    let mut plane = Target {
        base,
        kind,
        role,
        default,
        present: long(d, meta + 8) != 0,
        changed: false,
    };
    if clear == 2 {
        let packed = if kind == 2 {
            default | (default << 4)
        } else {
            default
        };
        d[base..end].fill(packed as u8);
        plane.present = false;
    } else if clear == 1 {
        for x in lx..lx + rw {
            for y in ly..ly + rh {
                plane.write(d, index(kind, x, y), default);
            }
        }
    }
    for x in lx..lx + rw {
        for y in ly..ly + rh {
            let value = values[x + y * 128];
            if value >= 0 {
                plane.write(d, index(kind, x, y), value);
            }
        }
    }
    for (g, stream) in streams[..gate_count].iter().enumerate() {
        put(d, gates + g * 16 + 8, stream.lcg_state());
    }
    put(d, meta + 8, u64::from(plane.present));
    put(d, meta + 16, u64::from(plane.changed));
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn frame() -> Vec<u8> {
        let meta = 256;
        let source = meta + 32 + AREA / 2;
        let mut d = vec![0; source + 112 + AREA * 8];
        let size = d.len() as u32;
        for (p, v) in [
            (0, 0x4d49_4d57),
            (4, 1),
            (8, size),
            (12, 2),
            (16, 3),
            (20, 9),
            (44, 128),
            (48, 128),
            (60, 15),
            (108, 256),
            (120, source as u32),
            (124, meta as u32),
            (source, 0x4d53_4257),
            (source + 4, 1),
            (source + 8, (112 + AREA * 8) as u32),
            (source + 12, 1),
            (source + 24, 128),
            (source + 28, 128),
            (source + 32, 128),
            (source + 36, 128),
        ] {
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        put(&mut d, 80, 255f64.to_bits());
        put(&mut d, 88, 255f64.to_bits());
        put(&mut d, source + 48, 1f64.to_bits());
        put(&mut d, source + 56, 1f64.to_bits());
        for i in 0..AREA {
            put(&mut d, source + 112 + i * 8, ((i % 256) as f64).to_bits());
        }
        d
    }
    #[test]
    fn ranged_values_are_fused_into_a_packed_layer() {
        let mut d = frame();
        import(&mut d).unwrap();
        for i in 0..AREA {
            assert_eq!(
                get(&d, 288, 2, i),
                round((i % 256) as f64 * 15.0 / 255.0) as i32
            );
        }
        assert_eq!(long(&d, 272), 1);
    }
    #[test]
    fn invalid_targets_and_headers_leave_all_bytes_unchanged() {
        for (p, v) in [
            (20, 99u32),
            (32, 3),
            (44, 129),
            (104, 9),
            (120, 0),
            (128, 1),
            (60, 256),
        ] {
            let mut d = frame();
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = d.clone();
            assert_eq!(import(&mut d), Err(WeltError::IllegalArgument));
            assert_eq!(d, before);
        }
        let mut d = frame();
        d[20..24].copy_from_slice(&7u32.to_le_bytes());
        let before = d.clone();
        assert_eq!(import(&mut d), Err(WeltError::IllegalArgument));
        assert_eq!(d, before);
    }
}
