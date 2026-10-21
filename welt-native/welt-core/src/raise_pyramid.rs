//! Ordered square-pyramid terrain shaping for the interactive editor.

const MAX_PYRAMID_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PyramidError {
    InvalidRadius,
    AreaOverflow,
    TooManyCells,
    HeightLength { expected: usize, actual: usize },
    ModifiedLength { expected: usize, actual: usize },
}

/// Builds a square sandstone pyramid's height changes in Java setter order.
///
/// The height plane covers the center and every possible ring. A modification
/// flag of 2 marks the optional center raise; 1 marks a raised ring cell.
pub fn raise_square_pyramid(
    max_ring: i32,
    center_height: f32,
    max_height: f32,
    heights: &mut [f32],
    modified: &mut [i8],
) -> Result<(), PyramidError> {
    raise_pyramid(
        false,
        max_ring,
        center_height,
        max_height,
        heights,
        modified,
    )
}

/// Builds a 45-degree rotated sandstone pyramid's height changes.
pub fn raise_rotated_pyramid(
    max_ring: i32,
    center_height: f32,
    max_height: f32,
    heights: &mut [f32],
    modified: &mut [i8],
) -> Result<(), PyramidError> {
    raise_pyramid(true, max_ring, center_height, max_height, heights, modified)
}

fn raise_pyramid(
    rotated: bool,
    max_ring: i32,
    center_height: f32,
    max_height: f32,
    heights: &mut [f32],
    modified: &mut [i8],
) -> Result<(), PyramidError> {
    if max_ring > 128 {
        return Err(PyramidError::TooManyCells);
    }
    let radius = if max_ring > 1 {
        usize::try_from(max_ring - 1).map_err(|_| PyramidError::InvalidRadius)?
    } else {
        0
    };
    let side = radius
        .checked_mul(2)
        .and_then(|value| value.checked_add(1))
        .ok_or(PyramidError::AreaOverflow)?;
    let area = side.checked_mul(side).ok_or(PyramidError::AreaOverflow)?;
    if area > MAX_PYRAMID_CELLS {
        return Err(PyramidError::TooManyCells);
    }
    if heights.len() != area {
        return Err(PyramidError::HeightLength {
            expected: area,
            actual: heights.len(),
        });
    }
    if modified.len() != area {
        return Err(PyramidError::ModifiedLength {
            expected: area,
            actual: modified.len(),
        });
    }

    modified.fill(0);
    let center_index = radius * side + radius;
    if center_height < max_height - 1.5_f32 {
        heights[center_index] = center_height + 1.0_f32;
        modified[center_index] = 2;
    }

    let mut desired_height = center_height;
    for ring in 1..max_ring {
        let mut raised = false;
        if rotated {
            for offset in 0..ring as isize {
                let ring = ring as isize;
                let center = radius as isize;
                raised |= raise_if_lower(
                    center - ring + offset,
                    center - offset,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
                raised |= raise_if_lower(
                    center + offset,
                    center - ring + offset,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
                raised |= raise_if_lower(
                    center + ring - offset,
                    center + offset,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
                raised |= raise_if_lower(
                    center - offset,
                    center + ring - offset,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
            }
        } else {
            for offset in -(ring) as isize..=ring as isize {
                raised |= raise_if_lower(
                    offset + radius as isize,
                    -(ring as isize) + radius as isize,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
                raised |= raise_if_lower(
                    offset + radius as isize,
                    ring as isize + radius as isize,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
            }
            for offset in (-ring + 1) as isize..ring as isize {
                raised |= raise_if_lower(
                    -(ring as isize) + radius as isize,
                    offset + radius as isize,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
                raised |= raise_if_lower(
                    ring as isize + radius as isize,
                    offset + radius as isize,
                    side,
                    desired_height,
                    heights,
                    modified,
                );
            }
        }
        if !raised {
            break;
        }
        desired_height -= 1.0_f32;
    }
    Ok(())
}

fn raise_if_lower(
    x: isize,
    y: isize,
    side: usize,
    desired_height: f32,
    heights: &mut [f32],
    modified: &mut [i8],
) -> bool {
    let index = x as usize * side + y as usize;
    if heights[index] < desired_height {
        heights[index] = desired_height;
        modified[index] = 1;
        true
    } else {
        false
    }
}
