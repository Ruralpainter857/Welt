//! Integer brush-center rasterization used by the Java drawing adapter.

const MAX_LINE_POINTS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineRasterError {
    TooManyPoints,
    OutputTooShort,
}

/// Writes the same pixel centers as DimensionPainter's slow line algorithm.
/// Coordinates are stored as interleaved `(x, y)` pairs.
pub fn rasterize_line_centers(
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
    output: &mut [i32],
) -> Result<usize, LineRasterError> {
    let delta_x = (i64::from(x2) - i64::from(x1)).abs();
    let delta_y = (i64::from(y2) - i64::from(y1)).abs();
    let point_count = (delta_x.max(delta_y) + 1) as usize;
    if point_count > MAX_LINE_POINTS {
        return Err(LineRasterError::TooManyPoints);
    }
    if output.len() < point_count * 2 {
        return Err(LineRasterError::OutputTooShort);
    }

    let mut index = 0;
    if delta_x < delta_y {
        let (mut start_x, mut start_y, mut end_x, mut end_y) = (x1, y1, x2, y2);
        if end_y < start_y {
            std::mem::swap(&mut start_x, &mut end_x);
            std::mem::swap(&mut start_y, &mut end_y);
        }
        let mut x = start_x as f32 - 0.5_f32;
        let x_step = (end_x - start_x) as f32 / (end_y - start_y) as f32;
        for y in start_y..=end_y {
            output[index] = java_round_f32(x);
            output[index + 1] = y;
            index += 2;
            x += x_step;
        }
    } else {
        let (mut start_x, mut start_y, mut end_x, mut end_y) = (x1, y1, x2, y2);
        if end_x < start_x {
            std::mem::swap(&mut start_x, &mut end_x);
            std::mem::swap(&mut start_y, &mut end_y);
        }
        let mut y = start_y as f32 - 0.5_f32;
        let y_step = (end_y - start_y) as f32 / (end_x - start_x) as f32;
        for x in start_x..=end_x {
            output[index] = x;
            output[index + 1] = java_round_f32(y);
            index += 2;
            y += y_step;
        }
    }

    Ok(point_count)
}

fn java_round_f32(value: f32) -> i32 {
    (f64::from(value) + 0.5).floor() as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn java_round_keeps_values_immediately_below_a_half_below_the_tie() {
        assert_eq!(java_round_f32(f32::from_bits(0x3eff_ffff)), 0);
        assert_eq!(java_round_f32(0.5), 1);
        assert_eq!(java_round_f32(-0.5), 0);
        assert_eq!(java_round_f32(f32::NAN), 0);
        assert_eq!(java_round_f32(f32::INFINITY), i32::MAX);
        assert_eq!(java_round_f32(f32::NEG_INFINITY), i32::MIN);
    }
}
