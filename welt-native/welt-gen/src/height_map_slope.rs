//! One worker-owned chain for base-map sampling and complete slope tiles.
use crate::height_map_tree::{
    fill_height_map_tree, fill_height_map_tree_points, fill_slope_samples, HeightMapNode,
    HeightMapTreeError,
};
use std::cell::RefCell;

#[derive(Default)]
struct Scratch {
    samples: Vec<f64>,
    x: Vec<f32>,
    y: Vec<f32>,
}
thread_local! { static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default()); }

#[allow(clippy::too_many_arguments)]
pub fn fill_slope_height_map_tree(
    nodes: &[HeightMapNode],
    origin_x: i32,
    origin_y: i32,
    width: usize,
    height: usize,
    shift: u32,
    vertical_scaling: f32,
    output: &mut [f64],
) -> Result<(), HeightMapTreeError> {
    let area = width
        .checked_mul(height)
        .ok_or(HeightMapTreeError::AreaOverflow)?;
    if width == 0 || height == 0 || area > 256 * 256 || shift > 31 {
        return Err(HeightMapTreeError::InvalidProgram);
    }
    if output.len() != area {
        return Err(HeightMapTreeError::OutputLength {
            expected: area,
            actual: output.len(),
        });
    }
    // The dense halo path must agree with float x +/- 1 in the Java slope map.
    for i in 0..width.max(height) {
        for origin in [origin_x, origin_y] {
            let coordinate = origin.wrapping_add((i as i32).wrapping_shl(shift));
            if !(-(1 << 24) + 1..=(1 << 24) - 1).contains(&coordinate) {
                return Err(HeightMapTreeError::InvalidProgram);
            }
        }
    }
    SCRATCH.with(|slot| {
        let mut scratch = slot.borrow_mut();
        if shift == 0 {
            let input_width = width + 2;
            let input_height = height + 2;
            scratch.samples.resize(input_width * input_height, 0.0);
            fill_height_map_tree(
                nodes,
                origin_x - 1,
                origin_y - 1,
                input_width,
                input_height,
                &mut scratch.samples,
            )?;
            if !fill_slope_samples(
                &scratch.samples,
                input_width,
                input_height,
                vertical_scaling,
                output,
            ) {
                return Err(HeightMapTreeError::InvalidProgram);
            }
        } else {
            let Scratch { samples, x, y } = &mut *scratch;
            samples.resize(area * 9, 0.0);
            x.resize(area * 9, 0.0);
            y.resize(area * 9, 0.0);
            for row in 0..height {
                for col in 0..width {
                    let cell = col + row * width;
                    let cx = origin_x.wrapping_add((col as i32).wrapping_shl(shift)) as f32;
                    let cy = origin_y.wrapping_add((row as i32).wrapping_shl(shift)) as f32;
                    for dy in 0..3 {
                        for dx in 0..3 {
                            let p = cell * 9 + dx + dy * 3;
                            x[p] = cx + (dx as f32 - 1.0);
                            y[p] = cy + (dy as f32 - 1.0);
                        }
                    }
                }
            }
            fill_height_map_tree_points(nodes, x, y, samples)?;
            for cell in 0..area {
                if !fill_slope_samples(
                    &samples[cell * 9..cell * 9 + 9],
                    3,
                    3,
                    vertical_scaling,
                    &mut output[cell..cell + 1],
                ) {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
            }
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dense_and_zoomed_tiles_match_individual_neighborhoods() {
        let nodes = [HeightMapNode::Noise {
            d_height: 128.0,
            scale: 1.7,
            octaves: 3,
            effective_seed: -123,
        }];
        for shift in [0, 1, 3] {
            for scaling in [1.0, 3.7, 0.0, f32::NAN] {
                let mut actual = [0.0; 12];
                fill_slope_height_map_tree(&nodes, -128, 64, 4, 3, shift, scaling, &mut actual)
                    .unwrap();
                for row in 0..3 {
                    for col in 0..4 {
                        let cx = -128 + ((col as i32) << shift);
                        let cy = 64 + ((row as i32) << shift);
                        let mut samples = [0.0; 9];
                        fill_height_map_tree(&nodes, cx - 1, cy - 1, 3, 3, &mut samples).unwrap();
                        let mut expected = [0.0];
                        assert!(fill_slope_samples(&samples, 3, 3, scaling, &mut expected));
                        let value = actual[col + row * 4];
                        assert!(
                            value.to_bits() == expected[0].to_bits()
                                || value.is_nan() && expected[0].is_nan()
                        );
                    }
                }
            }
        }
    }
    #[test]
    fn invalid_inputs_leave_output_untouched() {
        let mut output = [17.0; 4];
        assert!(fill_slope_height_map_tree(
            &[HeightMapNode::Constant(1.0)],
            i32::MAX,
            0,
            2,
            2,
            0,
            1.0,
            &mut output
        )
        .is_err());
        assert!(fill_slope_height_map_tree(&[], 0, 0, 2, 2, 0, 1.0, &mut output).is_err());
        assert_eq!(output, [17.0; 4]);
    }
}
