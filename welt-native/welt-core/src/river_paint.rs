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
/// WLRV v2: up to 1023 x 1023 cells of thirteen bytes.
pub const MAX_V2_SIDE: usize = 1023;
pub const MAX_V2_BYTES: usize = 64 + MAX_V2_SIDE * MAX_V2_SIDE * 13;
/// The largest frame of any version, for the JNI entry point.
pub const MAX_FRAME_BYTES: usize = if MAX_V2_BYTES > MAX_BYTES {
    MAX_V2_BYTES
} else {
    MAX_BYTES
};
const MAGIC: i32 = 0x56524c57;
/// Input classes of a v2 cell, in the mask byte before the edit.
const CLASS_NONE: u8 = 0;
const CLASS_SLOPE: u8 = 1;
const CLASS_FLOOD: u8 = 2;

pub fn edit_compact(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn int(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(int(data, offset) as u32)
    }
    if data.len() >= 64 && int(data, 0) == MAGIC && int(data, 4) == 2 {
        return edit_v2(data);
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

/// `Math.round(float)` of Java 17 exactly: floor(a + 1/2) without intermediate rounding, NaN to 0 and saturation
/// otherwise, so that -Float.MAX_VALUE (a missing tile) becomes Integer.MIN_VALUE.
pub fn java_round(a: f32) -> i32 {
    let bits = a.to_bits() as i32;
    let biased_exp = (bits & 0x7f80_0000) >> 23;
    let shift = (24 - 2 + 127) - biased_exp;
    if (shift & -32) == 0 {
        let mut r = (bits & 0x007f_ffff) | 0x0080_0000;
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i32
    }
}

/// WLRV v2: heights (f32, edited in place), water levels (i32), values (f32: the strength of flooded cells, the
/// slope of sloped cells) and one byte per cell, holding the class of the cell on input (0 none, 1 slope, 2 flood)
/// and the flags of the edit on output (1 height, 2 water and fluid, 4 beach). The integer heights are derived from
/// the heights as Java does. Everything is validated before anything is written.
fn edit_v2(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn int(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(int(data, offset) as u32)
    }
    let side = int(data, 8);
    let lava = int(data, 20);
    if !(1..=MAX_V2_SIDE as i32).contains(&side)
        || side % 2 != 1
        || !matches!(lava, 0 | 1)
        || data[24..64].iter().any(|&v| v != 0)
    {
        return Err(IllegalArgument);
    }
    let side = side as usize;
    let area = side * side;
    if data.len() != 64 + area * 13 {
        return Err(IllegalArgument);
    }
    let waters = 64 + area * 4;
    let values = 64 + area * 8;
    let mask = 64 + area * 12;
    if data[mask..].iter().any(|&class| class > CLASS_FLOOD) {
        return Err(IllegalArgument);
    }
    let mut level = i32::MAX;
    for x in 0..side {
        for y in 0..side {
            let i = x * side + y;
            if data[mask + i] == CLASS_FLOOD {
                continue;
            }
            let height = java_round(float(data, 64 + i * 4));
            let neighbour = (x > 0 && data[mask + i - side] == CLASS_FLOOD)
                || (x + 1 < side && data[mask + i + side] == CLASS_FLOOD)
                || (y > 0 && data[mask + i - 1] == CLASS_FLOOD)
                || (y + 1 < side && data[mask + i + 1] == CLASS_FLOOD);
            if int(data, waters + i * 4) < height && height < level && neighbour {
                level = height;
            }
        }
    }
    level = level.min(int(data, 12));
    let depth = float(data, 16);
    for i in 0..area {
        let class = data[mask + i];
        let value = float(data, values + i * 4);
        let current = float(data, 64 + i * 4);
        let mut flags = 0u8;
        if class == CLASS_FLOOD {
            let required = level as f32 - value / 0.75 * depth;
            if current > required {
                data[64 + i * 4..68 + i * 4].copy_from_slice(&required.to_le_bytes());
                flags |= 1;
            }
            flags |= 2;
            if lava == 0 {
                flags |= 4;
            }
        } else if class == CLASS_SLOPE {
            let maximum = level as f32 + value;
            if current > maximum {
                data[64 + i * 4..68 + i * 4].copy_from_slice(&maximum.to_le_bytes());
                flags |= 1;
            }
            if lava == 0 && maximum - (level as f32) < 2.0 {
                flags |= 4;
            }
        } else {
            debug_assert_eq!(class, CLASS_NONE);
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

    fn put(data: &mut [u8], offset: usize, bits: u32) {
        data[offset..offset + 4].copy_from_slice(&bits.to_le_bytes());
    }

    /// The same cells as a v1 frame and as a v2 frame, from pseudo random heights, waters and strengths.
    fn frames(side: usize, previous: i32, depth: f32, lava: bool, seed: u64) -> (Vec<u8>, Vec<u8>) {
        let area = side * side;
        let mut v1 = vec![0u8; 64 + area * 21];
        let mut v2 = vec![0u8; 64 + area * 13];
        for data in [&mut v1, &mut v2] {
            put(data, 0, 0x56524c57);
            put(data, 8, side as u32);
            put(data, 12, previous as u32);
            put(data, 16, depth.to_bits());
            put(data, 20, u32::from(lava));
        }
        put(&mut v1, 4, 1);
        put(&mut v2, 4, 2);
        let mut state = seed;
        let mut next = || {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            (state >> 33) as u32
        };
        let centre = (side / 2) as f32;
        for x in 0..side {
            for y in 0..side {
                let i = x * side + y;
                let missing = next() % 41 == 0;
                // Heights are multiples of 1/256 as in tiles, or the missing tile sentinel
                let height = if missing {
                    -f32::MAX
                } else {
                    (next() % 40000) as f32 / 256.0 - 64.0
                };
                let water = if missing {
                    i32::MIN
                } else {
                    20 + (next() % 60) as i32
                };
                let distance = ((x as f32 - centre).powi(2) + (y as f32 - centre).powi(2)).sqrt()
                    / (centre + 1.0);
                let mut strength = (1.0 - distance).max(0.0);
                match next() % 53 {
                    0 => strength = f32::NAN,
                    1 => strength = 0.25,
                    2 => strength = 0.250_001,
                    3 => strength = -1.0,
                    _ => {}
                }
                let slope = if strength > 0.0 && strength <= 0.25 {
                    ((-(strength as f64) * std::f64::consts::TAU + std::f64::consts::FRAC_PI_2)
                        .tan()
                        / std::f64::consts::TAU) as f32
                } else {
                    0.0
                };
                put(&mut v1, 64 + i * 4, height.to_bits());
                put(
                    &mut v1,
                    64 + area * 4 + i * 4,
                    super::java_round(height) as u32,
                );
                put(&mut v1, 64 + area * 8 + i * 4, water as u32);
                put(&mut v1, 64 + area * 12 + i * 4, strength.to_bits());
                put(&mut v1, 64 + area * 16 + i * 4, slope.to_bits());
                put(&mut v2, 64 + i * 4, height.to_bits());
                put(&mut v2, 64 + area * 4 + i * 4, water as u32);
                let (class, value) = if strength > 0.25 {
                    (2u8, strength)
                } else if strength > 0.0 {
                    (1u8, slope)
                } else {
                    (0u8, 0.0)
                };
                put(&mut v2, 64 + area * 8 + i * 4, value.to_bits());
                v2[64 + area * 12 + i] = class;
            }
        }
        (v1, v2)
    }

    #[test]
    fn v2_frames_match_v1_on_the_same_cells() {
        for (side, previous, lava, seed) in [
            (1, 65, false, 1),
            (3, 40, true, 2),
            (31, 62, false, 3),
            (101, 45, true, 4),
            (101, 70, false, 5),
        ] {
            let (mut v1, mut v2) = frames(side, previous, 7.5, lava, seed);
            super::edit_compact(&mut v1).unwrap();
            super::edit_compact(&mut v2).unwrap();
            let area = side * side;
            assert_eq!(&v1[12..16], &v2[12..16], "level, side {side}");
            assert_eq!(
                &v1[64..64 + area * 4],
                &v2[64..64 + area * 4],
                "heights, side {side}"
            );
            assert_eq!(
                &v1[64 + area * 20..],
                &v2[64 + area * 12..],
                "flags, side {side}"
            );
        }
    }

    #[test]
    fn the_largest_v2_frame_is_accepted() {
        let (_, mut v2) = frames(super::MAX_V2_SIDE, 62, 3.0, false, 6);
        assert_eq!(v2.len(), super::MAX_V2_BYTES);
        super::edit_compact(&mut v2).unwrap();
    }

    #[test]
    fn invalid_v2_frames_are_refused_without_changes() {
        let (_, valid) = frames(31, 62, 3.0, false, 7);
        let area = 31 * 31;
        type Corruption = Box<dyn Fn(&mut Vec<u8>)>;
        let cases: Vec<Corruption> = vec![
            Box::new(|data| put(data, 4, 3)),
            Box::new(|data| put(data, 8, 30)),
            Box::new(|data| put(data, 8, 1025)),
            Box::new(|data| put(data, 20, 2)),
            Box::new(|data| data[40] = 1),
            Box::new(|data| {
                data.pop();
            }),
            Box::new(|data| data.push(0)),
            Box::new(move |data| data[64 + area * 12 + area - 1] = 3),
            Box::new(move |data| data[64 + area * 12] = 4),
        ];
        for (number, corrupt) in cases.iter().enumerate() {
            let mut data = valid.clone();
            corrupt(&mut data);
            let before = data.clone();
            assert!(super::edit_compact(&mut data).is_err(), "case {number}");
            assert_eq!(data, before, "case {number}");
        }
    }

    #[test]
    fn rounding_matches_java() {
        // Expected values of Math.round(float) in Java 17
        for (value, expected) in [
            (0.0f32, 0),
            (0.49999997, 0),
            (0.5, 1),
            (-0.5, 0),
            (-0.50000006, -1),
            (2.5, 3),
            (-2.5, -2),
            (1.5, 2),
            (-1.5, -1),
            (8388607.5, 8388608),
            (16777215.0, 16777215),
            (2147483647.0, i32::MAX),
            (3.0e9, i32::MAX),
            (-3.0e9, i32::MIN),
            (-f32::MAX, i32::MIN),
            (f32::INFINITY, i32::MAX),
            (f32::NAN, 0),
            (62.0 + 1.0 / 256.0, 62),
            (-64.0 + 1.0 / 256.0, -64),
        ] {
            assert_eq!(super::java_round(value), expected, "{value}");
        }
    }
}
