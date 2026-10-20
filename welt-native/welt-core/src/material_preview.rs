//! Complete deterministic custom-material cross-section in a versioned frame.
use crate::noise::perlin::{PerlinAxis3D, PerlinColumn3D};
use crate::noise::PerlinNoise;

pub const MAX_BYTES: usize = 128 + 4096 * 8 + 1024 * 1024 * 4;
#[derive(Default)]
pub struct Workspace {
    noises: Vec<PerlinNoise>,
    z_axes: Vec<PerlinAxis3D>,
    columns: Vec<PerlinColumn3D>,
}
fn int(b: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(b[p..p + 4].try_into().unwrap())
}
fn float(b: &[u8], p: usize) -> f32 {
    f32::from_bits(int(b, p) as u32)
}
fn double(b: &[u8], p: usize) -> f64 {
    f64::from_le_bytes(b[p..p + 8].try_into().unwrap())
}
fn round(v: f32) -> i32 {
    (v as f64 + 0.5).floor() as i32
}

/// Invalid frames are rejected before changing any output byte.
pub fn fill(b: &mut [u8], w: &mut Workspace) -> bool {
    if b.len() < 128 || b.len() > MAX_BYTES || int(b, 0) != 0x31504d57 || int(b, 4) != 1 {
        return false;
    }
    let (width, height, mode, rows, palette, repeat, out) = (
        int(b, 8),
        int(b, 12),
        int(b, 16),
        int(b, 20),
        int(b, 24),
        int(b, 28),
        int(b, 32),
    );
    if !(1..=1024).contains(&width)
        || !(1..=1024).contains(&height)
        || !(1..=1_000_000).contains(&palette)
        || !(0..=256).contains(&rows)
        || !(0..=1).contains(&repeat)
        || !(2..=3).contains(&mode)
    {
        return false;
    }
    let (width, height, rows, palette, out) = (
        width as usize,
        height as usize,
        rows as usize,
        palette as usize,
        out as usize,
    );
    if out != 128 + rows * 8 || out + width * height * 4 != b.len() {
        return false;
    }
    let scale = float(b, 40);
    let octaves = int(b, 44);
    let noise_scale = double(b, 48);
    let noise_height = double(b, 56);
    let slope = double(b, 64);
    let offset = int(b, 72);
    if !slope.is_finite() || !(0..=10).contains(&octaves) {
        return false;
    }
    if octaves > 0 && (!noise_scale.is_finite() || noise_scale <= 0.0 || !noise_height.is_finite())
    {
        return false;
    }
    if mode == 2 {
        if rows < 2 || palette != rows || !scale.is_finite() || scale <= 0.0 {
            return false;
        }
        for r in 0..rows - 1 {
            let row_scale = float(b, 128 + r * 8) * scale;
            if !row_scale.is_finite() || row_scale <= 0.0 || !float(b, 132 + r * 8).is_finite() {
                return false;
            }
        }
    } else if rows != 0 {
        return false;
    }
    // Keep lattice coordinates in the exact integer range shared with Java.
    let extent = width.max(height) as f64;
    if mode == 2 {
        for r in 0..rows - 1 {
            if extent / 4.099_f32 as f64 / (float(b, 128 + r * 8) * scale) as f64
                > (1_u64 << 30) as f64
            {
                return false;
            }
        }
    } else if octaves > 0
        && extent / 65.537_f32 as f64 / noise_scale * (1 << (octaves - 1)) as f64
            > (1_u64 << 30) as f64
    {
        return false;
    }
    let count = if mode == 2 {
        rows - 1
    } else if octaves > 0 {
        1
    } else {
        0
    };
    while w.noises.len() < count {
        w.noises.push(PerlinNoise::new(0));
    }
    for i in 0..count {
        w.noises[i].set_seed(if mode == 2 { i as i64 + 1 } else { 55_904_327 });
    }
    let contexts = if mode == 2 {
        rows - 1
    } else {
        octaves as usize
    };
    w.z_axes.clear();
    w.columns.resize(contexts, PerlinColumn3D::default());
    for r in 0..contexts {
        for y in 0..height {
            let z = (height - y - 1) as i32;
            let position = if mode == 2 {
                ((z as f32 / 4.099_f32) as f64) / (float(b, 128 + r * 8) * scale) as f64
            } else {
                z as f64 / 65.537_f32 as f64 / noise_scale * (1 << r) as f64
            };
            w.z_axes.push(PerlinNoise::prepare_axis_3d(position));
        }
    }
    for x in 0..width {
        for r in 0..contexts {
            let position = if mode == 2 {
                ((x as f32 / 4.099_f32) as f64) / (float(b, 128 + r * 8) * scale) as f64
            } else {
                x as f64 / 65.537_f32 as f64 / noise_scale * (1 << r) as f64
            };
            let noise = if mode == 2 {
                &w.noises[r]
            } else {
                &w.noises[0]
            };
            w.columns[r] = noise.prepare_column_3d(
                PerlinNoise::prepare_axis_3d(position),
                PerlinNoise::prepare_axis_3d(0.0),
            );
        }
        for y in 0..height {
            let z = (height - y - 1) as i32;
            let material = if mode == 2 {
                let mut selected = rows - 1;
                for r in (0..rows - 1).rev() {
                    if w.noises[r].get_perlin_noise_3d_column_prepared(
                        &mut w.columns[r],
                        w.z_axes[r * height + y],
                    ) >= float(b, 132 + r * 8)
                    {
                        selected = r;
                    }
                }
                selected
            } else {
                let mut fz = z as f32;
                if octaves > 0 {
                    let mut noise = 0.0_f64;
                    for octave in 0..octaves as usize {
                        noise += w.noises[0].get_perlin_noise_3d_column_prepared(
                            &mut w.columns[octave],
                            w.z_axes[octave * height + y],
                        ) as f64;
                    }
                    let value = (noise / octaves as f64 + 0.5) * noise_height;
                    fz = (fz as f64 + (value - offset as f64)) as f32;
                }
                if repeat != 0 && slope != 0.0 {
                    fz = (fz as f64 + slope * x as f64) as f32;
                }
                let rounded = round(fz);
                if repeat != 0 {
                    rounded.rem_euclid(palette as i32) as usize
                } else {
                    rounded.clamp(0, palette as i32 - 1) as usize
                }
            };
            let at = out + (x + y * width) * 4;
            b[at..at + 4].copy_from_slice(&(material as i32).to_le_bytes());
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    fn put(b: &mut [u8], p: usize, v: i32) {
        b[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn frame() -> Vec<u8> {
        let mut b = vec![0; 128 + 8 * 4];
        for (p, v) in [
            (0, 0x31504d57),
            (4, 1),
            (8, 2),
            (12, 4),
            (16, 3),
            (24, 3),
            (28, 1),
            (32, 128),
        ] {
            put(&mut b, p, v);
        }
        b
    }
    #[test]
    fn layers_repeat_in_vertical_cross_section() {
        let mut b = frame();
        assert!(fill(&mut b, &mut Workspace::default()));
        let actual: Vec<i32> = (0..8).map(|i| int(&b, 128 + i * 4)).collect();
        assert_eq!(actual, vec![0, 0, 2, 2, 1, 1, 0, 0]);
    }
    #[test]
    fn invalid_frame_does_not_touch_output() {
        let mut b = frame();
        put(&mut b, 32, 124);
        let before = b.clone();
        assert!(!fill(&mut b, &mut Workspace::default()));
        assert_eq!(before, b);
    }
    #[test]
    fn java_round_saturates_and_rounds_negative_ties_up() {
        assert_eq!(round(-1.5), -1);
        assert_eq!(round(-0.5), 0);
        assert_eq!(round(f32::INFINITY), i32::MAX);
    }
}
