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
    resource_candidate_offsets: Vec<usize>,
    resource_candidates: Vec<usize>,
    resource_candidate_key_valid: bool,
    resource_candidate_min_z: i32,
    resource_candidate_height: usize,
    resource_candidate_material_min_z: Vec<i32>,
    resource_candidate_material_max_z: Vec<i32>,
    resource_candidate_chances: Vec<u32>,
    resource_candidate_cached_levels: [bool; 16],
    column_contexts: Vec<ResourceColumnContext>,
    context_epoch: u64,
}

impl ResourceNoiseWorkspace {
    fn prepare_resource_candidates(
        &mut self,
        min_z: i32,
        height: usize,
        material_min_z: &[i32],
        material_max_z: &[i32],
        chances: &[f32],
        resource_values: &[i32],
    ) {
        let cache_matches = self.resource_candidate_key_valid
            && self.resource_candidate_min_z == min_z
            && self.resource_candidate_height == height
            && self.resource_candidate_material_min_z == material_min_z
            && self.resource_candidate_material_max_z == material_max_z
            && self.resource_candidate_chances.len() == chances.len()
            && self
                .resource_candidate_chances
                .iter()
                .copied()
                .eq(chances.iter().map(|chance| chance.to_bits()));
        if !cache_matches {
            self.resource_candidate_key_valid = true;
            self.resource_candidate_min_z = min_z;
            self.resource_candidate_height = height;
            self.resource_candidate_material_min_z.clear();
            self.resource_candidate_material_min_z
                .extend_from_slice(material_min_z);
            self.resource_candidate_material_max_z.clear();
            self.resource_candidate_material_max_z
                .extend_from_slice(material_max_z);
            self.resource_candidate_chances.clear();
            self.resource_candidate_chances
                .extend(chances.iter().map(|chance| chance.to_bits()));
            self.resource_candidate_cached_levels = [false; 16];
            self.reset_resource_candidates(height);
        }

        let mut used_levels = [false; 16];
        for &resource_value in resource_values {
            used_levels[resource_value as usize] = true;
        }
        let stride = height + 1;
        for resource_value in 1..16 {
            if !used_levels[resource_value] || self.resource_candidate_cached_levels[resource_value]
            {
                continue;
            }
            let level_offset = resource_value * stride;
            for z_index in 0..height {
                let y = min_z + z_index as i32;
                self.resource_candidate_offsets[level_offset + z_index] =
                    self.resource_candidates.len();
                for material in 0..material_min_z.len() {
                    if chances[material * 16 + resource_value] <= 0.5
                        && y >= material_min_z[material]
                        && y <= material_max_z[material]
                    {
                        self.resource_candidates.push(material);
                    }
                }
            }
            self.resource_candidate_offsets[level_offset + height] = self.resource_candidates.len();
            self.resource_candidate_cached_levels[resource_value] = true;
        }
    }

    fn reset_resource_candidates(&mut self, height: usize) {
        self.resource_candidate_offsets.resize(16 * (height + 1), 0);
        self.resource_candidate_offsets.fill(0);
        self.resource_candidates.clear();
    }
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
        let stride = height + 1;
        workspace.prepare_resource_candidates(
            min_z,
            height,
            material_min_z,
            material_max_z,
            chances,
            resource_values,
        );

        let ResourceNoiseWorkspace {
            noises,
            column_contexts,
            tiny_x,
            tiny_y,
            dirt_x,
            dirt_y,
            tiny_z,
            dirt_z,
            resource_candidate_offsets,
            resource_candidates,
            ..
        } = &mut *workspace;
        for column in 0..columns {
            let start_y = column_min_z[column].max(min_z);
            let end_y = column_max_z[column].min(max_z);
            if resource_values[column] == 0 || start_y > end_y {
                continue;
            }
            for y in (start_y..=end_y).rev() {
                let z_index = (y - min_z) as usize;
                let resource_value = resource_values[column] as usize;
                let level_offset = resource_value * stride;
                let candidate_start = resource_candidate_offsets[level_offset + z_index];
                let candidate_end = resource_candidate_offsets[level_offset + z_index + 1];
                for &material in &resource_candidates[candidate_start..candidate_end] {
                    let chance = chances[material * 16 + resource_value];
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
                    let z = if is_dirt {
                        dirt_z[z_index]
                    } else {
                        tiny_z[z_index]
                    };
                    let value =
                        noises[material].get_perlin_noise_3d_column_prepared(&mut slot.context, z);
                    if value >= chance {
                        output[column * height + z_index] = (material + 1) as i8;
                        break;
                    }
                }
            }
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::ResourceNoiseWorkspace;

    #[test]
    fn candidate_lists_are_reused_and_refreshed_for_changed_inputs() {
        let mut workspace = ResourceNoiseWorkspace::default();
        let mut chances = vec![-1.0_f32; 2 * 16];
        let material_min_z = [-2, -2];
        let material_max_z = [2, 2];
        let resource_values = [1, 1];

        workspace.prepare_resource_candidates(
            -2,
            5,
            &material_min_z,
            &material_max_z,
            &chances,
            &resource_values,
        );
        assert_eq!(
            workspace.resource_candidate_candidates_for(1, 5),
            &[0, 1, 0, 1, 0, 1, 0, 1, 0, 1]
        );

        let first_candidates = workspace.resource_candidates.clone();
        workspace.prepare_resource_candidates(
            -2,
            5,
            &material_min_z,
            &material_max_z,
            &chances,
            &resource_values,
        );
        assert_eq!(workspace.resource_candidates, first_candidates);

        chances[1] = 0.75;
        let changed_min_z = [-2, 0];
        workspace.prepare_resource_candidates(
            -2,
            5,
            &changed_min_z,
            &material_max_z,
            &chances,
            &resource_values,
        );
        assert_eq!(
            workspace.resource_candidate_candidates_for(1, 5),
            &[1, 1, 1]
        );
    }
}

impl ResourceNoiseWorkspace {
    #[cfg(test)]
    fn resource_candidate_candidates_for(&self, resource_value: usize, height: usize) -> &[usize] {
        let stride = height + 1;
        let offset = resource_value * stride;
        &self.resource_candidates[self.resource_candidate_offsets[offset]
            ..self.resource_candidate_offsets[offset + height]]
    }
}
