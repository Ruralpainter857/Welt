//! Deterministic in-place erosion kernel for a brush-sized height window.

const MAX_WINDOW_CELLS: usize = 1_048_576;
const ERODE_AMOUNT: i32 = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErosionError {
    InvalidRadius,
    AreaOverflow,
    WindowTooLarge,
    HeightLength { expected: usize, actual: usize },
    ControlLength { expected: usize, actual: usize },
    WriteLogLength { expected: usize, actual: usize },
}

/// Applies one Java-compatible erosion round to an x-major height window.
///
/// `heights` includes a one-cell border around the `2 * radius + 1` operation
/// area. `controls` stores selected, reverse-x, and reverse-y bytes for each
/// operation cell in the original x-then-y traversal order. `write_log` stores
/// each changed cell index and value in the exact order Java's setters used.
pub fn erode_raw_height_region(
    radius: i32,
    heights: &mut [i32],
    controls: &[i8],
    write_log: &mut [i32],
) -> Result<usize, ErosionError> {
    if radius < 0 {
        return Err(ErosionError::InvalidRadius);
    }
    let diameter = usize::try_from(radius)
        .ok()
        .and_then(|value| value.checked_mul(2))
        .and_then(|value| value.checked_add(1))
        .ok_or(ErosionError::AreaOverflow)?;
    let window_width = diameter.checked_add(2).ok_or(ErosionError::AreaOverflow)?;
    let window_area = window_width
        .checked_mul(window_width)
        .ok_or(ErosionError::AreaOverflow)?;
    let operation_area = diameter
        .checked_mul(diameter)
        .ok_or(ErosionError::AreaOverflow)?;
    let control_length = operation_area
        .checked_mul(3)
        .ok_or(ErosionError::AreaOverflow)?;
    let write_log_length = operation_area
        .checked_mul(4)
        .ok_or(ErosionError::AreaOverflow)?;
    if window_area > MAX_WINDOW_CELLS
        || control_length > MAX_WINDOW_CELLS
        || write_log_length > MAX_WINDOW_CELLS
    {
        return Err(ErosionError::WindowTooLarge);
    }
    if heights.len() != window_area {
        return Err(ErosionError::HeightLength {
            expected: window_area,
            actual: heights.len(),
        });
    }
    if controls.len() != control_length {
        return Err(ErosionError::ControlLength {
            expected: control_length,
            actual: controls.len(),
        });
    }
    if write_log.len() != write_log_length {
        return Err(ErosionError::WriteLogLength {
            expected: write_log_length,
            actual: write_log.len(),
        });
    }

    let mut write_count = 0;
    let sqrt_two = std::f32::consts::SQRT_2;
    for x in 0..diameter {
        for y in 0..diameter {
            let control_index = (x * diameter + y) * 3;
            if controls[control_index] == 0 {
                continue;
            }
            let reverse_x = controls[control_index + 1] != 0;
            let reverse_y = controls[control_index + 2] != 0;
            let mut lowest_x = 0_usize;
            let mut lowest_y = 0_usize;
            let mut lowest_height = i32::MAX;
            for x_order in 0..3 {
                let dx = if reverse_x { 2 - x_order } else { x_order };
                for y_order in 0..3 {
                    let dy = if reverse_y { 2 - y_order } else { y_order };
                    let value = heights[(x + dx) * window_width + (y + dy)];
                    if value < lowest_height {
                        lowest_height = value;
                        lowest_x = dx;
                        lowest_y = dy;
                    }
                }
            }

            if lowest_x == 1 && lowest_y == 1 {
                continue;
            }
            let center_index = (x + 1) * window_width + (y + 1);
            let lowest_index = (x + lowest_x) * window_width + (y + lowest_y);
            let difference = heights[center_index].wrapping_sub(heights[lowest_index]);
            let half_difference = difference / 2;
            let divisor = if lowest_x != 1 && lowest_y != 1 {
                sqrt_two
            } else {
                1.0_f32
            };
            let mut amount = ((half_difference as f32) / divisor) as i32;
            amount = amount.min(ERODE_AMOUNT);
            let fraction = amount as f32 / 64.0_f32;
            amount = (fraction * fraction * 64.0_f32) as i32;
            if amount > 0 {
                heights[center_index] = heights[center_index].wrapping_sub(amount);
                heights[lowest_index] = heights[lowest_index].wrapping_add(amount);
                write_log[write_count] = center_index as i32;
                write_log[write_count + 1] = heights[center_index];
                write_log[write_count + 2] = lowest_index as i32;
                write_log[write_count + 3] = heights[lowest_index];
                write_count += 4;
            }
        }
    }
    Ok(write_count)
}

#[cfg(test)]
mod tests {
    use super::{erode_raw_height_region, ErosionError};

    #[test]
    fn moves_height_to_the_lowest_neighbor_and_logs_java_write_order() {
        let mut heights = [1000, 1000, 1000, 1000, 1000, 1000, 700, 1000, 1000];
        let controls = [1, 0, 0];
        let mut write_log = [9; 4];
        let write_count =
            erode_raw_height_region(0, &mut heights, &controls, &mut write_log).unwrap();
        assert_eq!(write_count, 4);
        assert_eq!(heights[6], 764);
        assert_eq!(heights[4], 936);
        assert_eq!(write_log, [4, 936, 6, 764]);
    }

    #[test]
    fn preserves_java_neighbor_tie_order() {
        let mut heights = [
            600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600, 600,
            600, 600, 600, 600, 600, 600, 600, 600,
        ];
        heights[1 * 5 + 1] = 500;
        heights[1 * 5 + 2] = 400;
        heights[2 * 5 + 1] = 400;
        let controls = [
            1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        ];
        let mut write_log = [0; 36];
        let write_count =
            erode_raw_height_region(1, &mut heights, &controls, &mut write_log).unwrap();
        assert_eq!(write_count, 4);
        assert_eq!(heights[1 * 5 + 1], 461);
        assert_eq!(heights[2 * 5 + 1], 439);
        assert_eq!(&write_log[..4], &[6, 461, 11, 439]);
        assert_eq!(heights[1 * 5 + 2], 400);
    }

    #[test]
    fn validates_all_buffer_shapes_before_mutating() {
        let mut heights = [100; 9];
        let controls = [0; 2];
        let mut write_log = [7; 4];
        assert_eq!(
            erode_raw_height_region(0, &mut heights, &controls, &mut write_log),
            Err(ErosionError::ControlLength {
                expected: 3,
                actual: 2
            })
        );
        assert_eq!(heights, [100; 9]);
        assert_eq!(write_log, [7; 4]);
    }
}
