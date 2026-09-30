//! River Paint operation for one prepared brush area.

const MAX_RIVER_CELLS: usize = 65_536;
const FLOOD_THRESHOLD: f32 = 0.25;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RiverPaintError {
    TooManyCells,
    LengthMismatch,
}

/// Finds a safe water level around the edge, then prepares terrain and fluid
/// changes in the same X-major order used by the Java operation.
#[allow(clippy::too_many_arguments)]
pub fn apply_river_paint(
    radius: usize,
    previous_water_level: i32,
    depth: f32,
    lava: bool,
    heights: &mut [f32],
    terrain_heights: &[i32],
    water_levels: &[i32],
    strengths: &[f32],
    slope_offsets: &[f32],
    height_modified: &mut [i8],
    flooded: &mut [i8],
    beaches: &mut [i8],
) -> Result<i32, RiverPaintError> {
    let side = radius
        .checked_mul(2)
        .and_then(|value| value.checked_add(1))
        .ok_or(RiverPaintError::TooManyCells)?;
    let area = side
        .checked_mul(side)
        .filter(|&value| value <= MAX_RIVER_CELLS)
        .ok_or(RiverPaintError::TooManyCells)?;
    check_len(heights.len(), area)?;
    check_len(terrain_heights.len(), area)?;
    check_len(water_levels.len(), area)?;
    check_len(strengths.len(), area)?;
    check_len(slope_offsets.len(), area)?;
    check_len(height_modified.len(), area)?;
    check_len(flooded.len(), area)?;
    check_len(beaches.len(), area)?;

    let mut water_level = i32::MAX;
    for x in 0..side {
        for y in 0..side {
            let index = x * side + y;
            if strengths[index] > FLOOD_THRESHOLD {
                continue;
            }
            let height = terrain_heights[index];
            if water_levels[index] < height
                && height < water_level
                && has_flood_neighbour(x, y, side, strengths)
            {
                water_level = height;
            }
        }
    }
    if water_level > previous_water_level {
        water_level = previous_water_level;
    }

    height_modified.fill(0);
    flooded.fill(0);
    beaches.fill(0);
    let water_level_float = water_level as f32;
    for index in 0..area {
        let strength = strengths[index];
        if strength > FLOOD_THRESHOLD {
            let required_height = water_level_float - (strength / 0.75_f32) * depth;
            if heights[index] > required_height {
                heights[index] = required_height;
                height_modified[index] = 1;
            }
            flooded[index] = 1;
            if !lava {
                beaches[index] = 1;
            }
        } else if strength > 0.0_f32 {
            let maximum_height = water_level_float + slope_offsets[index];
            if heights[index] > maximum_height {
                heights[index] = maximum_height;
                height_modified[index] = 1;
            }
            if !lava && maximum_height - water_level_float < 2.0_f32 {
                beaches[index] = 1;
            }
        }
    }
    Ok(water_level)
}

fn has_flood_neighbour(x: usize, y: usize, side: usize, strengths: &[f32]) -> bool {
    (x > 0 && strengths[(x - 1) * side + y] > FLOOD_THRESHOLD)
        || (x + 1 < side && strengths[(x + 1) * side + y] > FLOOD_THRESHOLD)
        || (y > 0 && strengths[x * side + y - 1] > FLOOD_THRESHOLD)
        || (y + 1 < side && strengths[x * side + y + 1] > FLOOD_THRESHOLD)
}

fn check_len(actual: usize, expected: usize) -> Result<(), RiverPaintError> {
    if actual == expected {
        Ok(())
    } else {
        Err(RiverPaintError::LengthMismatch)
    }
}
