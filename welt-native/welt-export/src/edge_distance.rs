//! Bulk equivalent of `Dimension.getDistancesToEdge` for a packed pixel mask.
//!
//! Java supplies a one-pixel empty halo around the tile bounds. The separable
//! squared Euclidean distance transform keeps boundary edges explicit and
//! preserves WorldPainter's pixel-centre distance convention in linear time.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EdgeDistanceError {
    InvalidBounds,
    InvalidDistance,
    MaskLength { expected: usize, actual: usize },
}

/// Bake capped Euclidean distances from each painted pixel to the closest
/// unpainted pixel. `mask` and `output` are row-major (`y * width + x`); any
/// nonzero mask byte is painted. Unpainted output values remain `max_distance`
/// because WorldPainter only defines cache values for painted pixels.
pub fn bake_edge_distances(
    mask: &[u8],
    width: usize,
    height: usize,
    max_distance: f32,
    output: &mut [f32],
) -> Result<(), EdgeDistanceError> {
    if width == 0 || height == 0 {
        return Err(EdgeDistanceError::InvalidBounds);
    }
    if !max_distance.is_finite() || max_distance < 0.0 || max_distance > 512.0 {
        return Err(EdgeDistanceError::InvalidDistance);
    }
    let Some(area) = width.checked_mul(height) else {
        return Err(EdgeDistanceError::InvalidBounds);
    };
    if area > 1_048_576 {
        return Err(EdgeDistanceError::InvalidBounds);
    }
    if mask.len() != area {
        return Err(EdgeDistanceError::MaskLength {
            expected: area,
            actual: mask.len(),
        });
    }
    if output.len() != area {
        return Err(EdgeDistanceError::MaskLength {
            expected: area,
            actual: output.len(),
        });
    }

    let mut horizontal = vec![f64::INFINITY; area];
    let scratch_len = width.max(height);
    let mut input = vec![f64::INFINITY; scratch_len];
    let mut transformed = vec![f64::INFINITY; scratch_len];
    let mut sites = vec![0_usize; scratch_len];
    let mut boundaries = vec![0.0_f64; scratch_len + 1];
    for y in 0..height {
        for x in 0..width {
            input[x] = if mask[y * width + x] == 0 {
                0.0
            } else {
                f64::INFINITY
            };
        }
        squared_distance_transform(
            &input[..width],
            &mut transformed[..width],
            &mut sites[..width],
            &mut boundaries[..=width],
        );
        for x in 0..width {
            horizontal[y * width + x] = transformed[x];
        }
    }

    for x in 0..width {
        for y in 0..height {
            input[y] = horizontal[y * width + x];
        }
        squared_distance_transform(
            &input[..height],
            &mut transformed[..height],
            &mut sites[..height],
            &mut boundaries[..=height],
        );
        for (y, &distance_squared) in transformed[..height].iter().enumerate() {
            let index = y * width + x;
            output[index] = if mask[index] == 0 {
                max_distance
            } else {
                distance_squared.sqrt().min(f64::from(max_distance)) as f32
            };
        }
    }
    Ok(())
}

/// Felzenszwalb-Huttenlocher one-dimensional squared distance transform.
fn squared_distance_transform(
    input: &[f64],
    output: &mut [f64],
    sites: &mut [usize],
    boundaries: &mut [f64],
) {
    let Some(first_source) = input.iter().position(|value| value.is_finite()) else {
        output.fill(f64::INFINITY);
        return;
    };
    let mut last_site = 0_usize;
    sites[0] = first_source;
    boundaries[0] = f64::NEG_INFINITY;
    boundaries[1] = f64::INFINITY;

    for q in first_source + 1..input.len() {
        if !input[q].is_finite() {
            continue;
        }
        let mut intersection = parabola_intersection(input, q, sites[last_site]);
        while intersection <= boundaries[last_site] {
            last_site -= 1;
            intersection = parabola_intersection(input, q, sites[last_site]);
        }
        last_site += 1;
        sites[last_site] = q;
        boundaries[last_site] = intersection;
        boundaries[last_site + 1] = f64::INFINITY;
    }

    let mut site_index = 0_usize;
    for (q, value) in output.iter_mut().enumerate() {
        while boundaries[site_index + 1] < q as f64 {
            site_index += 1;
        }
        let delta = q as f64 - sites[site_index] as f64;
        *value = delta * delta + input[sites[site_index]];
    }
}

fn parabola_intersection(input: &[f64], q: usize, site: usize) -> f64 {
    let qf = q as f64;
    let sitef = site as f64;
    ((input[q] + qf * qf) - (input[site] + sitef * sitef)) / (2.0 * (qf - sitef))
}

#[cfg(test)]
mod tests {
    use super::{bake_edge_distances, EdgeDistanceError};

    #[test]
    fn empty_mask_keeps_the_cap() {
        let mut output = [0.0; 9];
        bake_edge_distances(&[0; 9], 3, 3, 2.5, &mut output).unwrap();
        assert_eq!(output, [2.5; 9]);
    }

    #[test]
    fn one_pixel_shape_has_distance_one_to_its_empty_neighbours() {
        let mask = [0, 0, 0, 0, 1, 0, 0, 0, 0];
        let mut output = [0.0; 9];
        bake_edge_distances(&mask, 3, 3, 4.0, &mut output).unwrap();
        assert_eq!(output[4], 1.0);
        assert_eq!(output, [4.0, 4.0, 4.0, 4.0, 1.0, 4.0, 4.0, 4.0, 4.0]);
    }

    #[test]
    fn diagonal_nearest_empty_cell_uses_euclidean_distance() {
        let mask = [1, 1, 0, 1, 0, 1, 1, 1, 1];
        let mut output = [0.0; 9];
        bake_edge_distances(&mask, 3, 3, 4.0, &mut output).unwrap();
        assert_eq!(output[8], 2.0_f32.sqrt());
    }

    #[test]
    fn distance_transform_matches_edge_walk_on_varied_masks() {
        let (width, height) = (11, 9);
        let max_distance = 3.75_f32;
        let mut seed = 0x9e37_79b9_u32;
        for _case in 0..40 {
            let mut mask = vec![0_u8; width * height];
            for y in 1..height - 1 {
                for x in 1..width - 1 {
                    seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
                    mask[y * width + x] = ((seed >> 29) == 0) as u8;
                }
            }
            let mut actual = vec![0.0_f32; mask.len()];
            bake_edge_distances(&mask, width, height, max_distance, &mut actual).unwrap();
            let expected = edge_walk_reference(&mask, width, height, max_distance);
            for (index, (left, right)) in actual.iter().zip(expected).enumerate() {
                assert_eq!(left.to_bits(), right.to_bits(), "case index={index}");
            }
        }
    }

    #[test]
    fn invalid_input_is_rejected() {
        let mut output = [0.0; 4];
        assert_eq!(
            bake_edge_distances(&[0; 3], 2, 2, 1.0, &mut output),
            Err(EdgeDistanceError::MaskLength {
                expected: 4,
                actual: 3
            })
        );
        assert_eq!(
            bake_edge_distances(&[0; 4], 2, 2, f32::NAN, &mut output),
            Err(EdgeDistanceError::InvalidDistance)
        );
    }

    fn edge_walk_reference(mask: &[u8], width: usize, height: usize, cap: f32) -> Vec<f32> {
        let radius = cap.ceil() as isize;
        let mut output = vec![cap; mask.len()];
        for y in 0..height {
            for x in 0..width {
                let index = y * width + x;
                if mask[index] != 0
                    || !((x > 0 && mask[index - 1] != 0)
                        || (x + 1 < width && mask[index + 1] != 0)
                        || (y > 0 && mask[index - width] != 0)
                        || (y + 1 < height && mask[index + width] != 0))
                {
                    continue;
                }
                for dy in -radius..=radius {
                    for dx in -radius..=radius {
                        let target_x = x as isize + dx;
                        let target_y = y as isize + dy;
                        if !(0..width as isize).contains(&target_x)
                            || !(0..height as isize).contains(&target_y)
                        {
                            continue;
                        }
                        let target = target_y as usize * width + target_x as usize;
                        let distance = ((dx * dx + dy * dy) as f64).sqrt() as f32;
                        if mask[target] != 0 && distance < output[target] {
                            output[target] = distance;
                        }
                    }
                }
            }
        }
        output
    }
}
