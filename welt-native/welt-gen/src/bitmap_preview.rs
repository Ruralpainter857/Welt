//! WBP v1: bounded live bitmap patch, affine coordinates and bicubic evaluation.
use crate::bitmap_import::{cubic, maximum, minimum, shifted};
use crate::height_map_affine::point;

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
#[derive(Debug, PartialEq, Eq)]
pub struct InvalidFrame;
fn int(data: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(data[p..p + 4].try_into().unwrap())
}
fn double(data: &[u8], p: usize) -> f64 {
    f64::from_le_bytes(data[p..p + 8].try_into().unwrap())
}
fn coordinates(data: &[u8], row: usize, col: usize, matrix: &[f64; 6]) -> (f32, f32) {
    let shift = int(data, 16) as u32;
    let x = int(data, 8).wrapping_add((col as i32).wrapping_shl(shift)) as f32;
    let y = int(data, 12).wrapping_add((row as i32).wrapping_shl(shift)) as f32;
    let (x, y) = point(f64::from(x), f64::from(y), matrix);
    (shifted(x), shifted(y))
}
pub fn fill(data: &mut [u8]) -> Result<(), InvalidFrame> {
    if data.len() < 128
        || data.len() > MAX_BYTES
        || int(data, 0) != 0x31504257
        || int(data, 4) != 1
        || !(0..=7).contains(&int(data, 16))
        || int(data, 44) != 0
        || int(data, 48) != 128
    {
        return Err(InvalidFrame);
    }
    let width = int(data, 20) as usize;
    let height = int(data, 24) as usize;
    let patch_width = int(data, 36) as usize;
    let patch_height = int(data, 40) as usize;
    let area = width.checked_mul(height).ok_or(InvalidFrame)?;
    let patch_area = patch_width.checked_mul(patch_height).ok_or(InvalidFrame)?;
    if width == 0
        || height == 0
        || area > 16384
        || patch_width < 4
        || patch_height < 4
        || patch_area > 262144
    {
        return Err(InvalidFrame);
    }
    let output = 128 + patch_area * 8;
    if int(data, 52) as usize != output || data.len() != output + area * 8 {
        return Err(InvalidFrame);
    }
    let matrix: [f64; 6] = std::array::from_fn(|i| double(data, 64 + i * 8));
    if matrix.iter().any(|v| !v.is_finite())
        || (0..patch_area).any(|i| !double(data, 128 + i * 8).is_finite())
    {
        return Err(InvalidFrame);
    }
    let patch_x = int(data, 28);
    let patch_y = int(data, 32);
    // Validate every transformed stencil before mutating any output. No per-pixel JNI or allocations.
    for row in 0..height {
        for col in 0..width {
            let (x, y) = coordinates(data, row, col, &matrix);
            if !x.is_finite() || !y.is_finite() || x.abs() >= 16777212.0 || y.abs() >= 16777212.0 {
                return Err(InvalidFrame);
            }
            let px = (x.floor() as i64) - i64::from(patch_x);
            let py = (y.floor() as i64) - i64::from(patch_y);
            if px < 1 || py < 1 || px + 2 >= patch_width as i64 || py + 2 >= patch_height as i64 {
                return Err(InvalidFrame);
            }
        }
    }
    for row in 0..height {
        for col in 0..width {
            let (x, y) = coordinates(data, row, col, &matrix);
            let xf = x.floor() as i32;
            let yf = y.floor() as i32;
            let dx = x - xf as f32;
            let dy = y - yf as f32;
            let px = (xf - patch_x) as usize;
            let py = (yf - patch_y) as usize;
            let sample = |sx: usize, sy: usize| double(data, 128 + (sx + sy * patch_width) * 8);
            let a = sample(px, py);
            let b = sample(px, py + 1);
            let c = sample(px + 1, py);
            let d = sample(px + 1, py + 1);
            let min = minimum(minimum(a, b), minimum(c, d));
            let max = maximum(maximum(a, b), maximum(c, d));
            let mut columns = [0.0; 4];
            for (i, value) in columns.iter_mut().enumerate() {
                let sx = px + i - 1;
                *value = cubic(
                    sample(sx, py - 1),
                    sample(sx, py),
                    sample(sx, py + 1),
                    sample(sx, py + 2),
                    dy,
                );
            }
            let value = cubic(columns[0], columns[1], columns[2], columns[3], dx).clamp(min, max);
            let p = output + (col + row * width) * 8;
            data[p..p + 8].copy_from_slice(&value.to_le_bytes());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preserves_constant_patch_and_rejects_bad_stencils_atomically() {
        let output = 128 + 16 * 8;
        let mut data = vec![0; output + 8];
        for (p, value) in [
            (0, 0x31504257_i32),
            (4, 1),
            (8, 2),
            (12, 2),
            (20, 1),
            (24, 1),
            (36, 4),
            (40, 4),
            (48, 128),
            (52, output as i32),
        ] {
            data[p..p + 4].copy_from_slice(&value.to_le_bytes());
        }
        for (p, value) in [(64, 1.0_f64), (88, 1.0)] {
            data[p..p + 8].copy_from_slice(&value.to_le_bytes());
        }
        for i in 0..16 {
            data[128 + i * 8..136 + i * 8].copy_from_slice(&42.0_f64.to_le_bytes());
        }
        let valid = data.clone();
        fill(&mut data).unwrap();
        assert_eq!(double(&data, output), 42.0);
        for (p, value) in [(4, 2_i32), (16, 8), (28, 99), (44, 1), (52, 0)] {
            let mut invalid = valid.clone();
            invalid[p..p + 4].copy_from_slice(&value.to_le_bytes());
            let before = invalid.clone();
            assert!(fill(&mut invalid).is_err());
            assert_eq!(invalid, before);
        }
    }
}
