//! Complete displacement evaluation without intermediate Java coordinate or height planes.
use crate::height_map_tree::{
    fill_height_map_tree, fill_height_map_tree_points, HeightMapNode, HeightMapTreeError,
};
use crate::noise_height_map::NoiseHeightMapBulk;
use std::cell::RefCell;

#[derive(Default)]
struct Scratch {
    x: Vec<f32>,
    y: Vec<f32>,
    angle: Vec<f64>,
    distance: Vec<f64>,
}
thread_local! { static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default()); }

#[allow(clippy::too_many_arguments)]
pub fn fill_displacement_height_map_tree(
    nodes: &[HeightMapNode],
    angle_count: usize,
    distance_count: usize,
    origin_x: i32,
    origin_y: i32,
    width: usize,
    height: usize,
    shift: u32,
    output: &mut [f64],
) -> Result<(), HeightMapTreeError> {
    let area = width
        .checked_mul(height)
        .ok_or(HeightMapTreeError::AreaOverflow)?;
    let base_start = angle_count
        .checked_add(distance_count)
        .ok_or(HeightMapTreeError::InvalidProgram)?;
    if width == 0
        || height == 0
        || area > 256 * 256
        || shift > 31
        || angle_count == 0
        || distance_count == 0
        || base_start >= nodes.len()
    {
        return Err(HeightMapTreeError::InvalidProgram);
    }
    if output.len() != area {
        return Err(HeightMapTreeError::OutputLength {
            expected: area,
            actual: output.len(),
        });
    }
    SCRATCH.with(|slot| {
        let mut scratch = slot.borrow_mut();
        let Scratch {
            x,
            y,
            angle,
            distance,
        } = &mut *scratch;
        x.resize(area, 0.0);
        y.resize(area, 0.0);
        angle.resize(area, 0.0);
        distance.resize(area, 0.0);
        for row in 0..height {
            for col in 0..width {
                let p = col + row * width;
                x[p] = origin_x.wrapping_add((col as i32).wrapping_shl(shift)) as f32;
                y[p] = origin_y.wrapping_add((row as i32).wrapping_shl(shift)) as f32;
            }
        }
        if shift == 0 {
            // Both controls share a regular grid; retain prepared Perlin axes before displacement.
            fill_height_map_tree(
                &nodes[..angle_count],
                origin_x,
                origin_y,
                width,
                height,
                angle,
            )?;
            fill_height_map_tree(
                &nodes[angle_count..base_start],
                origin_x,
                origin_y,
                width,
                height,
                distance,
            )?;
        } else {
            fill_control(
                &nodes[..angle_count],
                origin_x,
                origin_y,
                width,
                height,
                shift,
                x,
                y,
                angle,
            )?;
            fill_control(
                &nodes[angle_count..base_start],
                origin_x,
                origin_y,
                width,
                height,
                shift,
                x,
                y,
                distance,
            )?;
        }
        for p in 0..area {
            // Preserve separate sine/cosine operations and Java's final float conversion.
            x[p] = (f64::from(x[p]) + angle[p].sin() * distance[p]) as f32;
            y[p] = (f64::from(y[p]) + angle[p].cos() * distance[p]) as f32;
            if !x[p].is_finite() || !y[p].is_finite() {
                return Err(HeightMapTreeError::InvalidProgram);
            }
        }
        fill_height_map_tree_points(&nodes[base_start..], x, y, output)
    })
}

#[allow(clippy::too_many_arguments)]
fn fill_control(
    nodes: &[HeightMapNode],
    origin_x: i32,
    origin_y: i32,
    width: usize,
    height: usize,
    shift: u32,
    x: &[f32],
    y: &[f32],
    output: &mut [f64],
) -> Result<(), HeightMapTreeError> {
    if let [HeightMapNode::Noise {
        d_height,
        scale,
        octaves,
        effective_seed,
    }] = nodes
    {
        NoiseHeightMapBulk::new(*d_height, *scale, *octaves, *effective_seed)?
            .fill_sampled_bulk(origin_x, origin_y, width, height, shift, output)?;
        Ok(())
    } else {
        fill_height_map_tree_points(nodes, x, y, output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn complete_chain_matches_explicit_coordinates_and_rejects_invalid_inputs() {
        let nodes = [
            HeightMapNode::Constant(0.37),
            HeightMapNode::Constant(31.0),
            HeightMapNode::Noise {
                d_height: 128.0,
                scale: 1.7,
                octaves: 3,
                effective_seed: -123,
            },
        ];
        let mut actual = [0.0; 12];
        fill_displacement_height_map_tree(&nodes, 1, 1, -128, 64, 4, 3, 2, &mut actual).unwrap();
        let mut x = [0.0; 12];
        let mut y = [0.0; 12];
        for row in 0..3 {
            for col in 0..4 {
                let p = col + row * 4;
                x[p] = ((-128 + col as i32 * 4) as f64 + 0.37_f64.sin() * 31.0) as f32;
                y[p] = ((64 + row as i32 * 4) as f64 + 0.37_f64.cos() * 31.0) as f32;
            }
        }
        let mut expected = [0.0; 12];
        fill_height_map_tree_points(&nodes[2..], &x, &y, &mut expected).unwrap();
        assert_eq!(actual, expected);
        let before = actual;
        for (a, d, shift) in [
            (0, 1, 0),
            (1, 0, 0),
            (1, 2, 0),
            (usize::MAX, 1, 0),
            (1, 1, 32),
        ] {
            assert!(fill_displacement_height_map_tree(
                &nodes,
                a,
                d,
                0,
                0,
                4,
                3,
                shift,
                &mut actual
            )
            .is_err());
            assert_eq!(actual, before);
        }
        let invalid = [HeightMapNode::Constant(f64::NAN), nodes[1], nodes[2]];
        assert!(
            fill_displacement_height_map_tree(&invalid, 1, 1, 0, 0, 4, 3, 0, &mut actual).is_err()
        );
        assert_eq!(actual, before);
    }
}
