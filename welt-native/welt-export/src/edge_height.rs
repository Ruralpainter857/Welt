//! Bulk equivalent of `Dimension.getEdgeHeights` for a packed source grid.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EdgeHeightError {
    InvalidBounds,
    InvalidRadius,
    InvalidLength,
}

/// Propagate each edge source's floor height over the exact integer disk
/// visited by WorldPainter's `GeometryUtil.visitFilledCircle`.
/// Arrays are row-major. Source heights are selected by nonzero `sources`.
pub fn bake_edge_heights(
    sources: &[u8],
    source_heights: &[f32],
    width: usize,
    height: usize,
    radius: usize,
    min_height: f32,
    output: &mut [f32],
) -> Result<(), EdgeHeightError> {
    let Some(area) = width.checked_mul(height) else {
        return Err(EdgeHeightError::InvalidBounds);
    };
    if width == 0 || height == 0 || area > 1_048_576 {
        return Err(EdgeHeightError::InvalidBounds);
    }
    if radius > 512 {
        return Err(EdgeHeightError::InvalidRadius);
    }
    if sources.len() != area || source_heights.len() != area || output.len() != area {
        return Err(EdgeHeightError::InvalidLength);
    }

    output.fill(min_height);
    for (index, &is_source) in sources.iter().enumerate() {
        if is_source == 0 {
            continue;
        }
        let center_x = index % width;
        let center_y = index / width;
        visit_filled_circle(radius, |dx, dy| {
            let x = center_x as isize + dx;
            let y = center_y as isize + dy;
            if x < 0 || y < 0 || x >= width as isize || y >= height as isize {
                return;
            }
            let target = y as usize * width + x as usize;
            let value = source_heights[index];
            if value > output[target] {
                output[target] = value;
            }
        });
    }
    Ok(())
}

/// This deliberately mirrors the integer rasterization in GeometryUtil.
fn visit_filled_circle(radius: usize, mut visit: impl FnMut(isize, isize)) {
    let mut dx = radius as isize;
    let mut dy = 0_isize;
    let mut radius_error = 1_isize - dx;
    while dx >= dy {
        visit(0, -dy);
        if dy > 0 {
            visit(0, dy);
        }
        for i in 1..=dx {
            visit(-i, -dy);
            visit(-i, dy);
            visit(i, -dy);
            visit(i, dy);
        }
        if dx > 0 {
            visit(0, -dx);
            visit(0, dx);
        }
        for i in 1..=dy {
            visit(-i, -dx);
            visit(-i, dx);
            visit(i, -dx);
            visit(i, dx);
        }
        dy += 1;
        if radius_error < 0 {
            radius_error += 2 * dy + 1;
        } else {
            dx -= 1;
            radius_error += 2 * (dy - dx + 1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{bake_edge_heights, visit_filled_circle, EdgeHeightError};

    #[test]
    fn matches_worldpainter_circle_rasterization() {
        for radius in 0..=32 {
            let (mut actual, mut expected) = (Vec::new(), Vec::new());
            visit_filled_circle(radius, |x, y| actual.push((x, y)));
            java_circle_reference(radius, |x, y| expected.push((x, y)));
            actual.sort_unstable();
            expected.sort_unstable();
            assert_eq!(actual, expected, "radius={radius}");
        }
    }

    #[test]
    fn maximum_height_wins_within_rasterized_circle() {
        let (width, height) = (11, 9);
        let mut sources = vec![0; width * height];
        let mut heights = vec![0.0; width * height];
        sources[4 * width + 4] = 1;
        heights[4 * width + 4] = 12.5;
        sources[4 * width + 6] = 1;
        heights[4 * width + 6] = 18.0;
        let mut output = vec![0.0; width * height];
        bake_edge_heights(&sources, &heights, width, height, 2, -64.0, &mut output).unwrap();
        assert_eq!(output[4 * width + 5], 18.0);
        assert_eq!(output[0], -64.0);
    }

    #[test]
    fn rejects_mismatched_buffers() {
        let mut output = [0.0; 4];
        assert_eq!(
            bake_edge_heights(&[0; 3], &[0.0; 4], 2, 2, 1, 0.0, &mut output),
            Err(EdgeHeightError::InvalidLength)
        );
    }

    fn java_circle_reference(radius: usize, mut visit: impl FnMut(isize, isize)) {
        let mut dx = radius as isize;
        let mut dy = 0_isize;
        let mut radius_error = 1_isize - dx;
        while dx >= dy {
            visit(0, -dy);
            if dy > 0 {
                visit(0, dy);
            }
            for i in 1..=dx {
                visit(-i, -dy);
                visit(-i, dy);
                visit(i, -dy);
                visit(i, dy);
            }
            if dx > 0 {
                visit(0, -dx);
                visit(0, dx);
            }
            for i in 1..=dy {
                visit(-i, -dx);
                visit(-i, dx);
                visit(i, -dx);
                visit(i, dx);
            }
            dy += 1;
            if radius_error < 0 {
                radius_error += 2 * dy + 1;
            } else {
                dx -= 1;
                radius_error += 2 * (dy - dx + 1);
            }
        }
    }
}
