//! Raise Mountain operation kernel shared by the native WorldPainter adapter.

use crate::noise::perlin::PerlinNoise;

const MAX_MOUNTAIN_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MountainError {
    TooManyCells,
    HeightLength { expected: usize, actual: usize },
    StrengthLength { expected: usize, actual: usize },
    ModifiedLength { expected: usize, actual: usize },
    InvalidNoiseScale,
}

/// Calculates one Raise Mountain brush area, leaving undo-aware writes and
/// optional theme application to the Java caller.
#[allow(clippy::too_many_arguments)]
pub fn raise_mountain(
    origin_x: i32,
    origin_y: i32,
    width: usize,
    height: usize,
    min_z: f32,
    max_range: f32,
    peak_height: f32,
    peak_factor: f32,
    inverse: bool,
    noise_scale: f32,
    noise_seed: i64,
    heights: &mut [f32],
    strengths: &[f32],
    modified: &mut [i8],
) -> Result<(), MountainError> {
    let area = width
        .checked_mul(height)
        .filter(|&value| value <= MAX_MOUNTAIN_CELLS)
        .ok_or(MountainError::TooManyCells)?;
    if heights.len() != area {
        return Err(MountainError::HeightLength {
            expected: area,
            actual: heights.len(),
        });
    }
    if strengths.len() != area {
        return Err(MountainError::StrengthLength {
            expected: area,
            actual: strengths.len(),
        });
    }
    if modified.len() != area {
        return Err(MountainError::ModifiedLength {
            expected: area,
            actual: modified.len(),
        });
    }
    if !noise_scale.is_finite() || noise_scale <= 0.0 {
        return Err(MountainError::InvalidNoiseScale);
    }

    modified.fill(0);
    let perlin = PerlinNoise::new(noise_seed);
    for x_offset in 0..width {
        let world_x = origin_x.wrapping_add(x_offset as i32);
        for y_offset in 0..height {
            let index = x_offset * height + y_offset;
            let current_height = heights[index];
            let world_y = origin_y.wrapping_add(y_offset as i32);
            let strength = strengths[index];
            let target_height = target_height(
                &perlin,
                world_x,
                world_y,
                strength,
                min_z,
                max_range,
                peak_height,
                peak_factor,
                inverse,
                noise_scale,
            );
            if if inverse {
                target_height < current_height
            } else {
                target_height > current_height
            } {
                heights[index] = target_height;
                modified[index] = 1;
            }
        }
    }
    Ok(())
}

fn clamp_java_strength(value: f32) -> f32 {
    if value < 0.0 {
        0.0
    } else if value > 1.0 {
        1.0
    } else {
        value
    }
}

fn java_max(left: f32, right: f32) -> f32 {
    if left.is_nan() || right.is_nan() {
        f32::NAN
    } else if left == 0.0 && right == 0.0 {
        if left.is_sign_positive() || right.is_sign_positive() {
            0.0
        } else {
            -0.0
        }
    } else if left > right {
        left
    } else {
        right
    }
}

fn java_min(left: f32, right: f32) -> f32 {
    if left.is_nan() || right.is_nan() {
        f32::NAN
    } else if left == 0.0 && right == 0.0 {
        if left.is_sign_negative() || right.is_sign_negative() {
            -0.0
        } else {
            0.0
        }
    } else if left < right {
        left
    } else {
        right
    }
}

// Les deux adaptateurs partagent exactement les opérations flottantes et leur ordre.
#[allow(clippy::too_many_arguments)]
fn target_height(
    perlin: &PerlinNoise,
    world_x: i32,
    world_y: i32,
    strength: f32,
    min_z: f32,
    max_range: f32,
    peak_height: f32,
    peak_factor: f32,
    inverse: bool,
    noise_scale: f32,
) -> f32 {
    let noise_x = f64::from((world_x as f32) / noise_scale);
    let allowable_noise_range = (0.5_f32 - (strength - 0.5_f32).abs()) / 5.0_f32;
    let noise_y = f64::from((world_y as f32) / noise_scale);
    let noise = perlin.get_perlin_noise_2d(noise_x, noise_y);
    let noisy_strength = clamp_java_strength(strength + noise * allowable_noise_range * strength);

    if inverse {
        java_max(
            max_range - (max_range - peak_height) * peak_factor * noisy_strength,
            0.0,
        ) + min_z
    } else {
        java_min(peak_height * peak_factor * noisy_strength, max_range) + min_z
    }
}

// WHED v3 : plan brut de tuile, forces X-major et coordonnées mondiales explicites.
pub fn edit_compact_tile(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn int(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(int(data, offset) as u32)
    }
    if data.len() < 80 || int(data, 0) != 0x44454857 || int(data, 4) != 3 {
        return Err(IllegalArgument);
    }
    let bits = int(data, 20);
    let inverse = int(data, 24);
    let x = int(data, 40);
    let y = int(data, 44);
    let width = int(data, 48);
    let height = int(data, 52);
    let scale = float(data, 72);
    if !matches!(bits, 16 | 32)
        || !matches!(inverse, 0 | 1)
        || width < 1
        || height < 1
        || width > 128
        || height > 128
        || x < 0
        || y < 0
        || x > 128 - width
        || y > 128 - height
        || int(data, 36) != 0
        || int(data, 60) != 0
        || int(data, 76) != 0
        || !scale.is_finite()
        || scale <= 0.0
    {
        return Err(IllegalArgument);
    }
    let bytes = bits as usize / 8;
    let forces = 80 + 16384 * bytes;
    if int(data, 56) != forces as i32 || data.len() != forces + width as usize * height as usize * 4
    {
        return Err(IllegalArgument);
    }
    thread_local! { static NOISE: PerlinNoise = PerlinNoise::new(67); }
    let mut writes = 0u32;
    NOISE.with(|noise| {
        for dx in 0..width as usize {
            for dy in 0..height as usize {
                let offset = 80 + (x as usize + dx + (y as usize + dy) * 128) * bytes;
                let raw = if bits == 16 {
                    u16::from_le_bytes(data[offset..offset + 2].try_into().unwrap()) as i32
                } else {
                    int(data, offset)
                };
                let min = int(data, 8) as f32;
                let current = raw as f32 / 256.0 + min;
                let target = target_height(
                    noise,
                    int(data, 64).wrapping_add(x).wrapping_add(dx as i32),
                    int(data, 68).wrapping_add(y).wrapping_add(dy as i32),
                    float(data, forces + (dx * height as usize + dy) * 4),
                    min,
                    float(data, 12),
                    float(data, 16),
                    float(data, 28),
                    inverse != 0,
                    scale,
                );
                if if inverse != 0 {
                    target < current
                } else {
                    target > current
                } {
                    let raw = ((target - min) * 256.0) as i32;
                    if bits == 16 {
                        data[offset..offset + 2].copy_from_slice(&(raw as u16).to_le_bytes());
                    } else {
                        data[offset..offset + 4].copy_from_slice(&raw.to_le_bytes());
                    }
                    writes += 1;
                }
            }
        }
    });
    data[32..36].copy_from_slice(&writes.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod compact_tests {
    #[test]
    fn zero_strength_and_invalid_scale_preserve_raw_data() {
        let mut data = vec![0u8; 80 + 32768 + 4];
        for (offset, value) in [
            (0, 0x44454857i32),
            (4, 3),
            (8, -64),
            (20, 16),
            (48, 1),
            (52, 1),
            (56, 80 + 32768),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        data[12..16].copy_from_slice(&383f32.to_le_bytes());
        data[16..20].copy_from_slice(&220f32.to_le_bytes());
        data[28..32].copy_from_slice(&1f32.to_le_bytes());
        let before = data.clone();
        assert!(super::edit_compact_tile(&mut data).is_err());
        assert_eq!(data, before);
        data[72..76].copy_from_slice(&32.771f32.to_le_bytes());
        data[80..82].copy_from_slice(&100u16.to_le_bytes());
        data[24..28].copy_from_slice(&1i32.to_le_bytes());
        // À force nulle, la descente vise le plafond : ici aucune écriture ne doit être faite.
        super::edit_compact_tile(&mut data).unwrap();
        assert_eq!(&data[80..82], &100u16.to_le_bytes());
        assert_eq!(&data[32..36], &0u32.to_le_bytes());
    }
}
