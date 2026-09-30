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
        let noise_x = f64::from((world_x as f32) / noise_scale);
        for y_offset in 0..height {
            let index = x_offset * height + y_offset;
            let current_height = heights[index];
            let world_y = origin_y.wrapping_add(y_offset as i32);
            let strength = strengths[index];
            let allowable_noise_range = (0.5_f32 - (strength - 0.5_f32).abs()) / 5.0_f32;
            let noise_y = f64::from((world_y as f32) / noise_scale);
            let noise = perlin.get_perlin_noise_2d(noise_x, noise_y);
            let noisy_strength =
                clamp_java_strength(strength + noise * allowable_noise_range * strength);

            let target_height = if inverse {
                java_max(
                    max_range - (max_range - peak_height) * peak_factor * noisy_strength,
                    0.0,
                ) + min_z
            } else {
                java_min(peak_height * peak_factor * noisy_strength, max_range) + min_z
            };
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
