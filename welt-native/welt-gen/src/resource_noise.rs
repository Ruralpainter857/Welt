//! Batched 3D noise decisions for the Java `ResourcesExporter`.
//!
//! World and chunk mutation stays on the Java side. This module only finds
//! the first matching resource for each eligible block, in the same material
//! and vertical order as the original exporter.

use welt_core::noise::PerlinNoise;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResourceNoiseError {
    InvalidDimensions,
    InvalidInputLengths,
    InvalidResourceValue,
}

/// Fill first matching material indices for packed columns.
///
/// Output is indexed as `column * (max_z - min_z + 1) + (y - min_z)`. Zero
/// means that Java should leave the block unchanged; 1..=64 encode material
/// indexes plus one. Coordinates retain Java's float-division rounding.
#[allow(clippy::too_many_arguments)]
pub fn fill_resource_materials(
    min_z: i32,
    max_z: i32,
    tiny_x: &[f64],
    tiny_y: &[f64],
    dirt_x: &[f64],
    dirt_y: &[f64],
    column_min_z: &[i32],
    column_max_z: &[i32],
    resource_values: &[i32],
    seeds: &[i64],
    material_min_z: &[i32],
    material_max_z: &[i32],
    dirt_materials: &[u8],
    chances: &[f32],
) -> Result<Vec<i8>, ResourceNoiseError> {
    let height = i64::from(max_z) - i64::from(min_z) + 1;
    if height <= 0 || height > 4096 || tiny_x.is_empty() || tiny_x.len() > 256 || seeds.len() > 64 {
        return Err(ResourceNoiseError::InvalidDimensions);
    }
    let columns = tiny_x.len();
    if [
        tiny_y.len(),
        dirt_x.len(),
        dirt_y.len(),
        column_min_z.len(),
        column_max_z.len(),
        resource_values.len(),
    ]
    .iter()
    .any(|&length| length != columns)
        || material_min_z.len() != seeds.len()
        || material_max_z.len() != seeds.len()
        || dirt_materials.len() != seeds.len()
        || chances.len() != seeds.len() * 16
    {
        return Err(ResourceNoiseError::InvalidInputLengths);
    }
    if resource_values
        .iter()
        .any(|&value| !(0..16).contains(&value))
    {
        return Err(ResourceNoiseError::InvalidResourceValue);
    }
    let output_len = columns
        .checked_mul(height as usize)
        .filter(|&length| length <= 1_048_576)
        .ok_or(ResourceNoiseError::InvalidDimensions)?;
    let noises: Vec<_> = seeds.iter().copied().map(PerlinNoise::new).collect();
    let tiny_z: Vec<_> = (min_z..=max_z)
        .map(|y| f64::from(y as f32 / 4.099_f32))
        .collect();
    let dirt_z: Vec<_> = (min_z..=max_z)
        .map(|y| f64::from(y as f32 / 16.411_f32))
        .collect();
    let tiny_z: Vec<_> = tiny_z
        .iter()
        .copied()
        .map(PerlinNoise::prepare_axis_3d)
        .collect();
    let dirt_z: Vec<_> = dirt_z
        .iter()
        .copied()
        .map(PerlinNoise::prepare_axis_3d)
        .collect();
    let tiny_x: Vec<_> = tiny_x
        .iter()
        .copied()
        .map(PerlinNoise::prepare_axis_3d)
        .collect();
    let tiny_y: Vec<_> = tiny_y
        .iter()
        .copied()
        .map(PerlinNoise::prepare_axis_3d)
        .collect();
    let dirt_x: Vec<_> = dirt_x
        .iter()
        .copied()
        .map(PerlinNoise::prepare_axis_3d)
        .collect();
    let dirt_y: Vec<_> = dirt_y
        .iter()
        .copied()
        .map(PerlinNoise::prepare_axis_3d)
        .collect();
    // Byte zero means no placement; values 1..=64 encode material indexes.
    let mut output = vec![0_i8; output_len];
    for column in 0..columns {
        let start_y = column_min_z[column].max(min_z);
        let end_y = column_max_z[column].min(max_z);
        if resource_values[column] == 0 || start_y > end_y {
            continue;
        }
        for y in (start_y..=end_y).rev() {
            let z_index = (y - min_z) as usize;
            let resource_value = resource_values[column] as usize;
            for (material, noise) in noises.iter().enumerate() {
                let chance = chances[material * 16 + resource_value];
                if chance > 0.5 || y < material_min_z[material] || y > material_max_z[material] {
                    continue;
                }
                let value = if dirt_materials[material] != 0 {
                    noise.get_perlin_noise_3d_prepared(
                        dirt_x[column],
                        dirt_y[column],
                        dirt_z[z_index],
                    )
                } else {
                    noise.get_perlin_noise_3d_prepared(
                        tiny_x[column],
                        tiny_y[column],
                        tiny_z[z_index],
                    )
                };
                if value >= chance {
                    output[column * height as usize + z_index] = (material + 1) as i8;
                    break;
                }
            }
        }
    }
    Ok(output)
}
