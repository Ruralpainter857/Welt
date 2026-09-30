//! Coordinate snapping for the Pencil operation.

const AUTO_AXIS: i32 = -2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PencilSnapError {
    InvalidAxis,
    OutputTooShort,
}

/// Writes `[axis, x, y]` to `output`. Axis values match Pencil.Axis ordinals;
/// `-1` means that the endpoints are identical and no axis was selected.
pub fn snap_pencil_coordinates(
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
    axis_hint: i32,
    output: &mut [i32],
) -> Result<(), PencilSnapError> {
    if output.len() < 3 {
        return Err(PencilSnapError::OutputTooShort);
    }

    let axis = if axis_hint == AUTO_AXIS {
        calculate_axis(x1, y1, x2, y2)?
    } else if (-1..=3).contains(&axis_hint) {
        axis_hint
    } else {
        return Err(PencilSnapError::InvalidAxis);
    };

    let (snapped_x, snapped_y) = match axis {
        -1 => (x2, y2),
        0 => (x2, y1),
        1 => closest_point(x1, y1, x2, y2, 1000, 1000),
        2 => (x1, y2),
        3 => closest_point(x1, y1, x2, y2, 1000, -1000),
        _ => return Err(PencilSnapError::InvalidAxis),
    };

    output[0] = axis;
    output[1] = snapped_x;
    output[2] = snapped_y;
    Ok(())
}

fn calculate_axis(x1: i32, y1: i32, x2: i32, y2: i32) -> Result<i32, PencilSnapError> {
    if (x1 == x2) && (y1 == y2) {
        return Ok(-1);
    }

    let delta_y = y2.wrapping_sub(y1);
    let delta_x = x2.wrapping_sub(x1);
    let mut angle = (f64::from(delta_y) / f64::from(delta_x)).atan();
    if x2 < x1 {
        angle += std::f64::consts::PI;
    } else if angle < 0.0 {
        angle += std::f64::consts::PI * 2.0;
    }

    let angle_index = (angle * 4.0 / std::f64::consts::PI).round() as i32;
    match angle_index {
        0 | 4 | 8 => Ok(0),
        1 | 5 => Ok(1),
        2 | 6 => Ok(2),
        3 | 7 => Ok(3),
        _ => Err(PencilSnapError::InvalidAxis),
    }
}

fn closest_point(
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
    direction_x: i32,
    direction_y: i32,
) -> (i32, i32) {
    let delta_x = f64::from(x1.wrapping_add(direction_x)) - f64::from(x1);
    let delta_y = f64::from(y1.wrapping_add(direction_y)) - f64::from(y1);
    let u = ((f64::from(x2) - f64::from(x1)) * delta_x + (f64::from(y2) - f64::from(y1)) * delta_y)
        / (delta_x * delta_x + delta_y * delta_y);
    let x = java_round_f64(f64::from(x1) + u * delta_x) as i32;
    let y = java_round_f64(f64::from(y1) + u * delta_y) as i32;
    (x, y)
}

fn java_round_f64(value: f64) -> i64 {
    (value + 0.5_f64).floor() as i64
}
