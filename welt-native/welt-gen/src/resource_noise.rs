//! Batched 3D noise decisions for the Java `ResourcesExporter`.
//!
//! World and chunk mutation stays on the Java side. This module only finds
//! the first matching resource for each eligible block, in the same material
//! and vertical order as the original exporter.

use std::cell::RefCell;
use welt_core::noise::perlin::PerlinAxis3D;
use welt_core::noise::PerlinNoise;

thread_local! {
    static RESOURCE_NOISE_WORKSPACE: RefCell<ResourceNoiseWorkspace> =
        RefCell::new(ResourceNoiseWorkspace::default());
}

#[derive(Default)]
struct ResourceNoiseWorkspace {
    noises: Vec<PerlinNoise>,
    tiny_x: Vec<PerlinAxis3D>,
    tiny_y: Vec<PerlinAxis3D>,
    dirt_x: Vec<PerlinAxis3D>,
    dirt_y: Vec<PerlinAxis3D>,
    tiny_z: Vec<PerlinAxis3D>,
    dirt_z: Vec<PerlinAxis3D>,
}

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
    let columns = tiny_x.len();
    let output_len = columns
        .checked_mul(height.max(0) as usize)
        .filter(|&length| length <= 1_048_576)
        .ok_or(ResourceNoiseError::InvalidDimensions)?;
    let mut output = vec![0_i8; output_len];
    fill_resource_materials_into(
        min_z,
        max_z,
        tiny_x,
        tiny_y,
        dirt_x,
        dirt_y,
        column_min_z,
        column_max_z,
        resource_values,
        seeds,
        material_min_z,
        material_max_z,
        dirt_materials,
        chances,
        &mut output,
    )?;
    Ok(output)
}

/// Same calculation as [`fill_resource_materials`], writing into a caller-owned
/// output buffer so JNI can return decisions directly into Java storage.
#[allow(clippy::too_many_arguments)]
pub fn fill_resource_materials_into(
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
    output: &mut [i8],
) -> Result<(), ResourceNoiseError> {
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
    if output.len() != output_len {
        return Err(ResourceNoiseError::InvalidInputLengths);
    }
    output.fill(0);
    RESOURCE_NOISE_WORKSPACE.with(|workspace| {
        let mut workspace = workspace.borrow_mut();
        while workspace.noises.len() < seeds.len() {
            workspace.noises.push(PerlinNoise::new(0));
        }
        workspace.noises.truncate(seeds.len());
        for (noise, &seed) in workspace.noises.iter_mut().zip(seeds) {
            noise.set_seed(seed);
        }

        workspace.tiny_x.clear();
        workspace
            .tiny_x
            .extend(tiny_x.iter().copied().map(PerlinNoise::prepare_axis_3d));
        workspace.tiny_y.clear();
        workspace
            .tiny_y
            .extend(tiny_y.iter().copied().map(PerlinNoise::prepare_axis_3d));
        workspace.dirt_x.clear();
        workspace
            .dirt_x
            .extend(dirt_x.iter().copied().map(PerlinNoise::prepare_axis_3d));
        workspace.dirt_y.clear();
        workspace
            .dirt_y
            .extend(dirt_y.iter().copied().map(PerlinNoise::prepare_axis_3d));
        workspace.tiny_z.clear();
        workspace.tiny_z.extend(
            (min_z..=max_z).map(|y| PerlinNoise::prepare_axis_3d(f64::from(y as f32 / 4.099_f32))),
        );
        workspace.dirt_z.clear();
        workspace.dirt_z.extend(
            (min_z..=max_z).map(|y| PerlinNoise::prepare_axis_3d(f64::from(y as f32 / 16.411_f32))),
        );

        for column in 0..columns {
            let start_y = column_min_z[column].max(min_z);
            let end_y = column_max_z[column].min(max_z);
            if resource_values[column] == 0 || start_y > end_y {
                continue;
            }
            for y in (start_y..=end_y).rev() {
                let z_index = (y - min_z) as usize;
                let resource_value = resource_values[column] as usize;
                for (material, noise) in workspace.noises.iter().enumerate() {
                    let chance = chances[material * 16 + resource_value];
                    if chance > 0.5 || y < material_min_z[material] || y > material_max_z[material]
                    {
                        continue;
                    }
                    let value = if dirt_materials[material] != 0 {
                        noise.get_perlin_noise_3d_prepared(
                            workspace.dirt_x[column],
                            workspace.dirt_y[column],
                            workspace.dirt_z[z_index],
                        )
                    } else {
                        noise.get_perlin_noise_3d_prepared(
                            workspace.tiny_x[column],
                            workspace.tiny_y[column],
                            workspace.tiny_z[z_index],
                        )
                    };
                    if value >= chance {
                        output[column * height as usize + z_index] = (material + 1) as i8;
                        break;
                    }
                }
            }
        }
        Ok(())
    })
}
