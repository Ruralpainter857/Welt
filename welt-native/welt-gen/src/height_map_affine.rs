//! Affine coordinates and base-map evaluation in one worker-owned chain.
use crate::height_map_tree::{fill_height_map_tree_points, HeightMapNode, HeightMapTreeError};
use std::cell::RefCell;

#[derive(Default)]
struct Scratch {
    x: Vec<f32>,
    y: Vec<f32>,
}
thread_local! { static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default()); }

/// Matches the operation states of Java AffineTransform.transform(Point2D).
fn point(x: f64, y: f64, m: &[f64; 6]) -> (f32, f32) {
    let shear = m[1] != 0.0 || m[2] != 0.0;
    let scale = if shear {
        m[0] != 0.0 || m[3] != 0.0
    } else {
        m[0] != 1.0 || m[3] != 1.0
    };
    let translate = m[4] != 0.0 || m[5] != 0.0;
    let (mut px, mut py) = if shear {
        if scale {
            (x * m[0] + y * m[2], x * m[1] + y * m[3])
        } else {
            (y * m[2], x * m[1])
        }
    } else if scale {
        (x * m[0], y * m[3])
    } else {
        (x, y)
    };
    if translate {
        px += m[4];
        py += m[5];
    }
    (px as f32, py as f32)
}

#[allow(clippy::too_many_arguments)]
pub fn fill_affine_height_map_tree(
    nodes: &[HeightMapNode],
    origin_x: i32,
    origin_y: i32,
    width: usize,
    height: usize,
    shift: u32,
    matrix: &[f64; 6],
    output: &mut [f64],
) -> Result<(), HeightMapTreeError> {
    let area = width
        .checked_mul(height)
        .ok_or(HeightMapTreeError::AreaOverflow)?;
    if width == 0
        || height == 0
        || area > 16384
        || shift > 31
        || matrix.iter().any(|v| !v.is_finite())
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
        let Scratch { x, y } = &mut *scratch;
        x.resize(area, 0.0);
        y.resize(area, 0.0);
        for row in 0..height {
            for col in 0..width {
                // Java first stores the incoming integer coordinates in Point2D.Float.
                let cx = origin_x.wrapping_add((col as i32).wrapping_shl(shift)) as f32 as f64;
                let cy = origin_y.wrapping_add((row as i32).wrapping_shl(shift)) as f32 as f64;
                let p = col + row * width;
                (x[p], y[p]) = point(cx, cy, matrix);
                if !x[p].is_finite() || !y[p].is_finite() {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
            }
        }
        fill_height_map_tree_points(nodes, x, y, output)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn java_matrix_states_and_float_rounding_are_preserved() {
        assert_eq!(
            point(3.0, -5.0, &[1.0, 0.0, 0.0, 1.0, 0.0, 0.0]),
            (3.0, -5.0)
        );
        assert_eq!(
            point(3.0, -5.0, &[1.0, 0.0, 0.0, 1.0, -7.0, 11.0]),
            (-4.0, 6.0)
        );
        assert_eq!(
            point(3.0, -5.0, &[0.0, -1.0, 1.0, 0.0, 0.0, 0.0]),
            (-5.0, -3.0)
        );
        assert_eq!(
            point(3.0, -5.0, &[2.0, 0.0, 0.0, -3.0, -7.0, 11.0]),
            (-1.0, 26.0)
        );
    }
    #[test]
    fn complete_chain_matches_explicit_points_and_rejects_invalid_matrices() {
        let nodes = [HeightMapNode::Noise {
            d_height: 128.0,
            scale: 1.7,
            octaves: 3,
            effective_seed: -123,
        }];
        let matrix = [0.7, 0.13, -0.21, 1.3, -11.0, 7.0];
        let mut actual = [0.0; 12];
        fill_affine_height_map_tree(&nodes, -128, 64, 4, 3, 2, &matrix, &mut actual).unwrap();
        let mut x = [0.0; 12];
        let mut y = [0.0; 12];
        for row in 0..3 {
            for col in 0..4 {
                (x[col + row * 4], y[col + row * 4]) = point(
                    (-128 + col as i32 * 4) as f64,
                    (64 + row as i32 * 4) as f64,
                    &matrix,
                );
            }
        }
        let mut expected = [0.0; 12];
        fill_height_map_tree_points(&nodes, &x, &y, &mut expected).unwrap();
        assert_eq!(actual, expected);
        let before = actual;
        assert!(
            fill_affine_height_map_tree(&nodes, 0, 0, 4, 3, 0, &[f64::NAN; 6], &mut actual)
                .is_err()
        );
        assert_eq!(actual, before);
    }
}
