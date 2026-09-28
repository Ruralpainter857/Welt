//! Batched 3D noise decisions for the Java `ResourcesExporter`.
//!
//! World and chunk mutation stays on the Java side. This module only finds
//! the first matching resource for each eligible block, in the same material
//! and vertical order as the original exporter.

use std::cell::RefCell;
use welt_core::noise::perlin::{PerlinAxis3D, PerlinColumn3D};
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
    column_contexts: Vec<ResourceColumnContext>,
    context_epoch: u64,
}

#[derive(Default)]
struct ResourceColumnContext {
    epoch: u64,
    context: PerlinColumn3D,
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
        None,
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
    profile: Option<&mut [u64]>,
) -> Result<(), ResourceNoiseError> {
    let profile_enabled = profile.is_some();
    if profile.as_ref().is_some_and(|values| values.len() != 3) {
        return Err(ResourceNoiseError::InvalidInputLengths);
    }
    if profile_enabled {
        fill_resource_materials_into_impl::<true>(
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
            output,
            profile,
        )
    } else {
        fill_resource_materials_into_impl::<false>(
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
            output,
            None,
        )
    }
}

#[allow(clippy::too_many_arguments)]
fn fill_resource_materials_into_impl<const PROFILE: bool>(
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
    mut profile: Option<&mut [u64]>,
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
    let setup_start = if PROFILE {
        Some(std::time::Instant::now())
    } else {
        None
    };
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

        // Perlin's horizontal permutation hashes are stable for every sample
        // of a given material and column. Keep one lazy context per pair; its
        // eight gradients are refreshed only when the vertical lattice cell
        // changes.
        let context_count = columns * seeds.len();
        workspace
            .column_contexts
            .resize_with(context_count, ResourceColumnContext::default);
        let mut context_epoch = workspace.context_epoch.wrapping_add(1);
        if context_epoch == 0 {
            for context in &mut workspace.column_contexts {
                context.epoch = 0;
            }
            context_epoch = 1;
        }
        workspace.context_epoch = context_epoch;

        let height = height as usize;
        let ResourceNoiseWorkspace {
            noises,
            column_contexts,
            tiny_x,
            tiny_y,
            dirt_x,
            dirt_y,
            tiny_z,
            dirt_z,
            ..
        } = &mut *workspace;
        let setup_nanos = setup_start
            .map(|start| start.elapsed().as_nanos().min(u64::MAX as u128) as u64)
            .unwrap_or(0);
        let scan_start = if PROFILE {
            Some(std::time::Instant::now())
        } else {
            None
        };
        let mut noise_samples = 0_u64;
        for column in 0..columns {
            let start_y = column_min_z[column].max(min_z);
            let end_y = column_max_z[column].min(max_z);
            if resource_values[column] == 0 || start_y > end_y {
                continue;
            }
            let resource_value = resource_values[column] as usize;
            for material in 0..seeds.len() {
                let chance = chances[material * 16 + resource_value];
                let material_start_y = start_y.max(material_min_z[material]);
                let material_end_y = end_y.min(material_max_z[material]);
                if !(chance <= 0.5) || material_start_y > material_end_y {
                    continue;
                }

                let is_dirt = dirt_materials[material] != 0;
                let (x, y) = if is_dirt {
                    (dirt_x[column], dirt_y[column])
                } else {
                    (tiny_x[column], tiny_y[column])
                };
                let slot = &mut column_contexts[column * seeds.len() + material];
                if slot.epoch != context_epoch {
                    slot.context = noises[material].prepare_column_3d(x, y);
                    slot.epoch = context_epoch;
                }
                for y in (material_start_y..=material_end_y).rev() {
                    let z_index = (y - min_z) as usize;
                    let output_index = column * height + z_index;
                    if output[output_index] != 0 {
                        continue;
                    }
                    let z = if is_dirt {
                        dirt_z[z_index]
                    } else {
                        tiny_z[z_index]
                    };
                    let value =
                        noises[material].get_perlin_noise_3d_column_prepared(&mut slot.context, z);
                    if PROFILE {
                        noise_samples += 1;
                    }
                    if value >= chance {
                        output[output_index] = (material + 1) as i8;
                    }
                }
            }
        }
        if let Some(profile) = profile.as_deref_mut() {
            profile[0] = setup_nanos;
            profile[1] = scan_start
                .map(|start| start.elapsed().as_nanos().min(u64::MAX as u128) as u64)
                .unwrap_or(0);
            profile[2] = noise_samples;
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::fill_resource_materials_into;
    use welt_core::noise::PerlinNoise;

    #[test]
    fn material_major_columns_match_historical_material_priority() {
        let min_z = -17;
        let max_z = 51;
        let seeds = [17_i64, -91, 0x1234_5678, i64::MAX - 3];
        let tiny_x = [-23.5, 3.25, 48.0, -19.0];
        let tiny_y = [12.125, -3.5, 17.75, 0.0];
        let dirt_x = [-4.25, 0.75, 8.0, -5.5];
        let dirt_y = [2.25, -0.875, 3.625, 0.0];
        let column_min_z = [-17, -8, 0, 34];
        let column_max_z = [51, 27, 46, 48];
        let resource_values = [3, 9, 0, 15];
        let material_min_z = [-15, -2, 6, 40];
        let material_max_z = [16, 35, 44, 55];
        let dirt_materials = [0, 1, 0, 1];
        let thresholds = [-0.1_f32, 0.0, 0.2, 0.48, 0.51, 0.75, f32::NAN];
        let chances: Vec<f32> = (0..seeds.len())
            .flat_map(|material| {
                (0..16).map(move |level| thresholds[(material * 5 + level * 3) % thresholds.len()])
            })
            .collect();
        let noises: Vec<_> = seeds.iter().map(|&seed| PerlinNoise::new(seed)).collect();
        let height = (max_z - min_z + 1) as usize;
        let mut expected = vec![0_i8; tiny_x.len() * height];
        let mut expected_samples = 0_u64;

        for column in 0..tiny_x.len() {
            let start_y = column_min_z[column].max(min_z);
            let end_y = column_max_z[column].min(max_z);
            if resource_values[column] == 0 || start_y > end_y {
                continue;
            }
            for y in (start_y..=end_y).rev() {
                for material in 0..seeds.len() {
                    let chance = chances[material * 16 + resource_values[column] as usize];
                    if !(chance <= 0.5)
                        || y < material_min_z[material]
                        || y > material_max_z[material]
                    {
                        continue;
                    }
                    let (x, horizontal_y, z) = if dirt_materials[material] != 0 {
                        (
                            dirt_x[column],
                            dirt_y[column],
                            f64::from(y as f32 / 16.411_f32),
                        )
                    } else {
                        (
                            tiny_x[column],
                            tiny_y[column],
                            f64::from(y as f32 / 4.099_f32),
                        )
                    };
                    expected_samples += 1;
                    if noises[material].get_perlin_noise_3d(x, horizontal_y, z) >= chance {
                        expected[column * height + (y - min_z) as usize] = (material + 1) as i8;
                        break;
                    }
                }
            }
        }

        let mut output = vec![0_i8; tiny_x.len() * height];
        let mut profile = [0_u64; 3];
        fill_resource_materials_into(
            min_z,
            max_z,
            &tiny_x,
            &tiny_y,
            &dirt_x,
            &dirt_y,
            &column_min_z,
            &column_max_z,
            &resource_values,
            &seeds,
            &material_min_z,
            &material_max_z,
            &dirt_materials,
            &chances,
            &mut output,
            Some(&mut profile),
        )
        .unwrap();

        assert_eq!(output, expected);
        assert_eq!(profile[2], expected_samples);
    }
}
