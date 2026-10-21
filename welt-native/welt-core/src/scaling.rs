//! Bulk world-height/layer resampling. WSCL v1 keeps source planes compact:
//! f32 heights and u8 layers. All planes share source coordinates and one call.
//! Header: 14 LE u32 fields; four 128-element axes; 32-byte plane descriptors.
//! Header fields: magic, version, source width/height, signed source origin X/Y,
//! plane count, integer-sampling flag, interpolated X/Y axis offsets, nearest
//! X/Y axis offsets, descriptor-table offset, and total byte length.
//! Interpolated axes are f32, nearest axes are i32. Source/output cells are
//! row-major; descriptors and both source/output ranges are contiguous.
//! Descriptors: mode, min/max f32, default u32, source/output offsets, reserved.
//! Modes: 0 bicubic height, 1 bicubic rounded layer, 2 nearest discrete layer.

pub const MAGIC: u32 = 0x4c43_5357;
pub const MAX_BYTES: usize = 8 * 1024 * 1024;
const SIDE: usize = 128;
const TABLE: usize = 56 + 4 * SIDE * 4;

fn u32_at(data: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}
fn f32_at(data: &[u8], offset: usize) -> f32 {
    f32::from_bits(u32_at(data, offset))
}
fn cubic(a: f64, b: f64, c: f64, d: f64, t: f32) -> f64 {
    let t = t as f64;
    b + 0.5 * t * (c - a + t * (2.0 * a - 5.0 * b + 4.0 * c - d + t * (3.0 * (b - c) + d - a)))
}
fn shifted(x: f32) -> f32 {
    x - if x > 0.0 {
        0.5
    } else if x < 0.0 {
        -0.5
    } else {
        0.0
    }
}

pub fn resample(data: &mut [u8]) -> Result<(), &'static str> {
    if data.len() < TABLE
        || data.len() > MAX_BYTES
        || u32_at(data, 0) != MAGIC
        || u32_at(data, 4) != 1
        || u32_at(data, 52) as usize != data.len()
    {
        return Err("invalid scaling header");
    }
    let width = u32_at(data, 8) as usize;
    let height = u32_at(data, 12) as usize;
    let ox = u32_at(data, 16) as i32;
    let oy = u32_at(data, 20) as i32;
    let count = u32_at(data, 24) as usize;
    let integer = u32_at(data, 28) == 1;
    if !(1..=1024).contains(&width)
        || !(1..=1024).contains(&height)
        || !(1..=128).contains(&count)
        || u32_at(data, 28) > 1
        || TABLE + count * 32 > data.len()
        || [32, 36, 40, 44]
            .iter()
            .enumerate()
            .any(|(i, &o)| u32_at(data, o) as usize != 56 + i * SIDE * 4)
        || u32_at(data, 48) as usize != TABLE
    {
        return Err("invalid scaling program");
    }
    let mut floors_x = [0_usize; SIDE];
    let mut floors_y = [0_usize; SIDE];
    let mut dx = [0_f32; SIDE];
    let mut dy = [0_f32; SIDE];
    let mut nx = [0_usize; SIDE];
    let mut ny = [0_usize; SIDE];
    for i in 0..SIDE {
        for axis in 0..2 {
            let origin = if axis == 0 { ox } else { oy };
            let extent = if axis == 0 { width } else { height };
            let coord = f32_at(data, 56 + axis * SIDE * 4 + i * 4);
            if !coord.is_finite() || coord.abs() > 16_000_000.0 {
                return Err("invalid coordinate");
            }
            let coord = if integer { coord } else { shifted(coord) };
            let floor = coord.floor() as i32;
            let local = floor as i64 - origin as i64;
            let near =
                u32_at(data, 56 + (axis + 2) * SIDE * 4 + i * 4) as i32 as i64 - origin as i64;
            if local < if integer { 0 } else { 1 }
                || local + if integer { 0 } else { 2 } >= extent as i64
                || near < 0
                || near >= extent as i64
            {
                return Err("source border missing");
            }
            if axis == 0 {
                floors_x[i] = local as usize;
                dx[i] = coord - floor as f32;
                nx[i] = near as usize;
            } else {
                floors_y[i] = local as usize;
                dy[i] = coord - floor as f32;
                ny[i] = near as usize;
            }
        }
    }
    let mut end = TABLE + count * 32;
    for p in 0..count {
        let descriptor = TABLE + p * 32;
        let mode = u32_at(data, descriptor);
        let source = u32_at(data, descriptor + 16) as usize;
        let output = u32_at(data, descriptor + 20) as usize;
        let bytes = if mode == 0 { 4 } else { 1 };
        if mode > 2 || source != end || width * height * bytes > data.len() - end {
            return Err("invalid source plane");
        }
        end += width * height * bytes;
        if output != end
            || SIDE * SIDE * bytes > data.len() - end
            || !f32_at(data, descriptor + 4).is_finite()
            || !f32_at(data, descriptor + 8).is_finite()
            || f32_at(data, descriptor + 4) > f32_at(data, descriptor + 8)
            || (mode != 0
                && (f32_at(data, descriptor + 4) != 0.0 || f32_at(data, descriptor + 8) > 255.0))
        {
            return Err("invalid output plane");
        }
        end += SIDE * SIDE * bytes;
    }
    if end != data.len() {
        return Err("trailing scaling bytes");
    }
    for p in 0..count {
        let descriptor = TABLE + p * 32;
        let mode = u32_at(data, descriptor);
        let source = u32_at(data, descriptor + 16) as usize;
        let output = u32_at(data, descriptor + 20) as usize;
        let lo = f32_at(data, descriptor + 4) as f64;
        let hi = f32_at(data, descriptor + 8) as f64;
        for y in 0..SIDE {
            for x in 0..SIDE {
                let sample = |sx: usize, sy: usize| {
                    let index = sx + sy * width;
                    if mode == 0 {
                        f32_at(data, source + index * 4) as f64
                    } else {
                        data[source + index] as f64
                    }
                };
                let value = if mode == 2 {
                    sample(nx[x], ny[y])
                } else if integer {
                    sample(floors_x[x], floors_y[y])
                } else {
                    let fx = floors_x[x];
                    let fy = floors_y[y];
                    let corners = [
                        sample(fx, fy),
                        sample(fx, fy + 1),
                        sample(fx + 1, fy),
                        sample(fx + 1, fy + 1),
                    ];
                    let min = corners[0].min(corners[1]).min(corners[2].min(corners[3]));
                    let max = corners[0].max(corners[1]).max(corners[2].max(corners[3]));
                    let column = |sx| {
                        cubic(
                            sample(sx, fy - 1),
                            sample(sx, fy),
                            sample(sx, fy + 1),
                            sample(sx, fy + 2),
                            dy[y],
                        )
                    };
                    cubic(
                        column(fx - 1),
                        column(fx),
                        column(fx + 1),
                        column(fx + 2),
                        dx[x],
                    )
                    .max(min)
                    .min(max)
                };
                let value = value.min(hi).max(lo);
                let index = x + y * SIDE;
                if mode == 0 {
                    data[output + index * 4..output + index * 4 + 4]
                        .copy_from_slice(&(value as f32).to_le_bytes());
                } else {
                    data[output + index] = (value + 0.5).floor() as u8;
                }
            }
        }
    }
    Ok(())
}
