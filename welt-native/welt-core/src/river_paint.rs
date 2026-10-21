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

// WLRV v1 : cinq plans de quatre octets, puis un masque hauteur/eau/terrain.
pub const MAX_BYTES: usize = 64 + 511 * 511 * 21;
pub fn edit_compact(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn int(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(int(data, offset) as u32)
    }
    if data.len() < 64 || int(data, 0) != 0x56524c57 || int(data, 4) != 1 {
        return Err(IllegalArgument);
    }
    let side = int(data, 8);
    let lava = int(data, 20);
    if !(1..=511).contains(&side)
        || side % 2 != 1
        || !matches!(lava, 0 | 1)
        || data[24..64].iter().any(|&v| v != 0)
    {
        return Err(IllegalArgument);
    }
    let side = side as usize;
    let area = side * side;
    if data.len() != 64 + area * 21 {
        return Err(IllegalArgument);
    }
    let terrains = 64 + area * 4;
    let waters = 64 + area * 8;
    let strengths = 64 + area * 12;
    let slopes = 64 + area * 16;
    let mask = 64 + area * 20;
    let mut level = i32::MAX;
    for x in 0..side {
        for y in 0..side {
            let i = x * side + y;
            if float(data, strengths + i * 4) > FLOOD_THRESHOLD {
                continue;
            }
            let height = int(data, terrains + i * 4);
            let neighbour = (x > 0 && float(data, strengths + (i - side) * 4) > FLOOD_THRESHOLD)
                || (x + 1 < side && float(data, strengths + (i + side) * 4) > FLOOD_THRESHOLD)
                || (y > 0 && float(data, strengths + (i - 1) * 4) > FLOOD_THRESHOLD)
                || (y + 1 < side && float(data, strengths + (i + 1) * 4) > FLOOD_THRESHOLD);
            if int(data, waters + i * 4) < height && height < level && neighbour {
                level = height;
            }
        }
    }
    level = level.min(int(data, 12));
    let depth = float(data, 16);
    for i in 0..area {
        let strength = float(data, strengths + i * 4);
        let current = float(data, 64 + i * 4);
        let mut flags = 0u8;
        if strength > FLOOD_THRESHOLD {
            let required = level as f32 - strength / 0.75 * depth;
            if current > required {
                data[64 + i * 4..68 + i * 4].copy_from_slice(&required.to_le_bytes());
                flags |= 1;
            }
            flags |= 2;
            if lava == 0 {
                flags |= 4;
            }
        } else if strength > 0.0 {
            let maximum = level as f32 + float(data, slopes + i * 4);
            if current > maximum {
                data[64 + i * 4..68 + i * 4].copy_from_slice(&maximum.to_le_bytes());
                flags |= 1;
            }
            if lava == 0 && maximum - (level as f32) < 2.0 {
                flags |= 4;
            }
        }
        data[mask + i] = flags;
    }
    data[12..16].copy_from_slice(&level.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod compact_tests {
    #[test]
    fn compact_planes_match_legacy_kernel_and_invalid_header_is_atomic() {
        for lava in [false, true] {
            let area = 9;
            let mut data = vec![0u8; 64 + area * 21];
            for (offset, value) in [
                (0, 0x56524c57i32),
                (4, 1),
                (8, 3),
                (12, 65),
                (20, i32::from(lava)),
            ] {
                data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
            }
            data[16..20].copy_from_slice(&5f32.to_le_bytes());
            let mut heights = vec![62f32; area];
            let terrains = vec![62i32; area];
            let waters = vec![0i32; area];
            let forces = vec![
                0f32,
                0.25,
                0.3,
                0.5,
                1.0,
                f32::NAN,
                -1.0,
                f32::INFINITY,
                0.2,
            ];
            let slopes = vec![0f32; area];
            for i in 0..area {
                for (plane, value) in [
                    (0, heights[i].to_bits()),
                    (1, terrains[i] as u32),
                    (2, waters[i] as u32),
                    (3, forces[i].to_bits()),
                    (4, slopes[i].to_bits()),
                ] {
                    let offset = 64 + plane * area * 4 + i * 4;
                    data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
                }
            }
            let mut modified = vec![0i8; area];
            let mut flooded = modified.clone();
            let mut beaches = modified.clone();
            let level = super::apply_river_paint(
                1,
                65,
                5.0,
                lava,
                &mut heights,
                &terrains,
                &waters,
                &forces,
                &slopes,
                &mut modified,
                &mut flooded,
                &mut beaches,
            )
            .unwrap();
            super::edit_compact(&mut data).unwrap();
            assert_eq!(&data[12..16], &level.to_le_bytes());
            for i in 0..area {
                assert_eq!(&data[64 + i * 4..68 + i * 4], &heights[i].to_le_bytes());
                assert_eq!(
                    data[64 + area * 20 + i],
                    (modified[i] | flooded[i] << 1 | beaches[i] << 2) as u8
                );
            }
            data[20..24].copy_from_slice(&3i32.to_le_bytes());
            let before = data.clone();
            assert!(super::edit_compact(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
}
