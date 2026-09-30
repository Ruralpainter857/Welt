//! Chunk-local first pass for block light, sky light, and leaf distance.
//!
//! The caller supplies palette-indexed block sections and mutable packed light
//! arrays. This keeps the complete scan in one native call per chunk while
//! retaining the Java world's palette and serialization ownership.

const OPAQUE: u32 = 1;
const LEAF_BLOCK: u32 = 1 << 1;
const HAS_LEAF_DISTANCE: u32 = 1 << 2;
const CONTAINS_WATER: u32 = 1 << 3;
const BLOCK_LIGHT_SHIFT: u32 = 4;
const OPACITY_SHIFT: u32 = 8;

#[derive(Debug)]
pub struct BlockSection<'a> {
    /// Canonical x/z/y packed palette indexes, 4096 entries.
    pub indexes: &'a mut [i32],
    /// One flags value per source palette index.
    pub palette_flags: &'a [u32],
    /// Index to use after clearing a leaf's explicit distance; -1 otherwise.
    pub clear_distance_indexes: &'a [i32],
    /// Packed low/high light nibbles, 2048 bytes each.
    pub sky_light: &'a mut [u8],
    pub block_light: &'a mut [u8],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FirstPassResult {
    pub dirty_low_y: i32,
    pub dirty_high_y: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FirstPassError {
    InvalidBounds,
    InvalidSectionCount,
    InvalidSectionLength,
    InvalidPaletteLength,
    InvalidPaletteIndex,
    MissingLeafDistanceTarget,
    InvalidHeightOutput,
}

/// Mirrors `BlockPropertiesCalculator.firstPass(Chunk)` for one chunk.
///
/// `heights[x * 16 + z]` receives the updated height map. Input section arrays
/// are modified in place, and materials with an explicit leaf distance are
/// replaced by their pre-reserved palette index.
pub fn first_pass(
    sections: &mut [BlockSection<'_>],
    min_y: i32,
    max_y: i32,
    highest_non_air: i32,
    water_opacity: i32,
    sky_light_enabled: bool,
    block_light_enabled: bool,
    leaf_distance_enabled: bool,
    heights: &mut [i32],
) -> Result<FirstPassResult, FirstPassError> {
    if min_y >= max_y || (min_y & 15) != 0 || (max_y & 15) != 0 {
        return Err(FirstPassError::InvalidBounds);
    }
    let section_count = ((max_y - min_y) / 16) as usize;
    if sections.len() != section_count {
        return Err(FirstPassError::InvalidSectionCount);
    }
    if heights.len() != 256 {
        return Err(FirstPassError::InvalidHeightOutput);
    }
    for section in sections.iter() {
        if section.indexes.len() != 4096
            || section.sky_light.len() != 2048
            || section.block_light.len() != 2048
        {
            return Err(FirstPassError::InvalidSectionLength);
        }
        if section.palette_flags.is_empty()
            || section.clear_distance_indexes.len() != section.palette_flags.len()
        {
            return Err(FirstPassError::InvalidPaletteLength);
        }
        if leaf_distance_enabled {
            for (palette_index, &flags) in section.palette_flags.iter().enumerate() {
                if flags & (LEAF_BLOCK | HAS_LEAF_DISTANCE) == (LEAF_BLOCK | HAS_LEAF_DISTANCE)
                    && section.clear_distance_indexes[palette_index] < 0
                {
                    return Err(FirstPassError::MissingLeafDistanceTarget);
                }
            }
        }
        // Palette-index storage is created and checked by PackedArrayCube.
        // Keep the expensive full-index validation in debug builds only; the
        // release path must not scan every section once before scanning it again.
        #[cfg(debug_assertions)]
        if section
            .indexes
            .iter()
            .any(|&index| index < 0 || index as usize >= section.palette_flags.len())
        {
            return Err(FirstPassError::InvalidPaletteIndex);
        }
    }

    let initial_height = min_y.max(highest_non_air.min(max_y - 1));
    for x in 0_usize..16 {
        for z in 0_usize..16 {
            heights[x * 16 + z] = initial_height;
        }
    }

    let mut dirty_min_y = max_y - 1;
    let mut dirty_max_y = min_y;
    let clamped_highest = (min_y - 1).max(highest_non_air.min(max_y - 1));
    let scan_max_y = (((clamped_highest >> 4) + 1) << 4) - 1;

    for x in 0_usize..16 {
        for z in 0_usize..16 {
            let mut daylight = true;
            for y in (min_y..=scan_max_y).rev() {
                let section_index = ((y - min_y) >> 4) as usize;
                let local_y = y & 15;
                let block_offset = x as i32 | (((z as i32) | (local_y << 4)) << 4);
                let block_index = block_offset as usize;
                let section = &mut sections[section_index];
                let palette_index = section.indexes[block_index];
                let properties = section.palette_flags[palette_index as usize];
                let is_opaque = properties & OPAQUE != 0;
                let is_leaf = properties & LEAF_BLOCK != 0;

                if leaf_distance_enabled && is_leaf {
                    if properties & HAS_LEAF_DISTANCE != 0 {
                        section.indexes[block_index] =
                            section.clear_distance_indexes[palette_index as usize];
                    }
                    dirty_min_y = dirty_min_y.min(y);
                    dirty_max_y = dirty_max_y.max(y);
                }

                if sky_light_enabled {
                    let sky_light = get_nibble(section.sky_light, block_offset);
                    let new_sky_light = if !is_opaque {
                        dirty_min_y = dirty_min_y.min(y);
                        let opacity = opacity(properties, water_opacity);
                        if opacity == 0 && daylight {
                            heights[x * 16 + z] = y;
                            15
                        } else {
                            if opacity > 0 {
                                dirty_max_y = dirty_max_y.max(y);
                            }
                            daylight = false;
                            0
                        }
                    } else {
                        dirty_max_y = dirty_max_y.max(y);
                        daylight = false;
                        0
                    };
                    if new_sky_light != sky_light {
                        set_nibble(section.sky_light, block_offset, new_sky_light);
                    }
                }

                if block_light_enabled {
                    let block_light = get_nibble(section.block_light, block_offset);
                    let material_block_light = ((properties >> BLOCK_LIGHT_SHIFT) & 0x0f) as u8;
                    if material_block_light > 0 {
                        dirty_max_y = dirty_max_y.max(y);
                        dirty_min_y = dirty_min_y.min(y);
                    }
                    if material_block_light != block_light {
                        set_nibble(section.block_light, block_offset, material_block_light);
                    }
                }
            }
        }
    }

    Ok(FirstPassResult {
        dirty_low_y: dirty_min_y,
        dirty_high_y: dirty_max_y,
    })
}

#[inline]
fn opacity(properties: u32, water_opacity: i32) -> i32 {
    if properties & CONTAINS_WATER != 0 {
        water_opacity
    } else {
        ((properties >> OPACITY_SHIFT) & 0xff) as i32
    }
}

#[inline]
fn get_nibble(data: &[u8], block_offset: i32) -> u8 {
    let packed = data[(block_offset >> 1) as usize];
    if block_offset & 1 == 0 {
        packed & 0x0f
    } else {
        packed >> 4
    }
}

#[inline]
fn set_nibble(data: &mut [u8], block_offset: i32, value: u8) {
    let offset = (block_offset >> 1) as usize;
    let packed = data[offset];
    data[offset] = if block_offset & 1 == 0 {
        (packed & 0xf0) | (value & 0x0f)
    } else {
        (packed & 0x0f) | ((value & 0x0f) << 4)
    };
}

#[cfg(test)]
mod tests {
    use super::{first_pass, BlockSection, FirstPassError, OPAQUE};

    fn section<'a>(
        indexes: &'a mut [i32],
        palette_flags: &'a [u32],
        clear_distance_indexes: &'a [i32],
        sky_light: &'a mut [u8],
        block_light: &'a mut [u8],
    ) -> BlockSection<'a> {
        BlockSection {
            indexes,
            palette_flags,
            clear_distance_indexes,
            sky_light,
            block_light,
        }
    }

    #[test]
    fn initializes_daylight_and_emissive_light_in_place() {
        let flags = [0, OPAQUE | (12 << 4)];
        let clear = [-1, -1];
        let mut indexes = vec![0; 4096];
        indexes[0] = 1;
        let mut sky = vec![0xff; 2048];
        let mut block = vec![0; 2048];
        let mut heights = [0; 256];
        let result = first_pass(
            &mut [section(&mut indexes, &flags, &clear, &mut sky, &mut block)],
            0,
            16,
            15,
            1,
            true,
            true,
            false,
            &mut heights,
        )
        .expect("one section should process");

        assert_eq!(result.dirty_low_y, 0);
        assert_eq!(result.dirty_high_y, 0);
        assert_eq!(heights[0], 1);
        assert_eq!(sky[0] & 0x0f, 0);
        assert_eq!(block[0] & 0x0f, 12);
        assert_eq!(sky[(15 << 7) | 0] & 0x0f, 15);
    }

    #[test]
    fn tracks_dirty_bounds_for_air_above_a_solid_floor() {
        let flags = [0, OPAQUE];
        let clear = [-1, -1];
        let mut indexes = vec![0; 4096];
        for z in 0..16 {
            for x in 0..16 {
                indexes[x | (z << 4)] = 1;
            }
        }
        let mut sky = vec![0; 2048];
        let mut block = vec![0; 2048];
        let mut heights = [0; 256];
        let result = first_pass(
            &mut [section(&mut indexes, &flags, &clear, &mut sky, &mut block)],
            0,
            16,
            0,
            1,
            true,
            false,
            false,
            &mut heights,
        )
        .expect("one section should process");

        assert_eq!(result.dirty_low_y, 1);
        assert_eq!(result.dirty_high_y, 0);
        assert!(heights.iter().all(|&height| height == 1));
    }

    #[test]
    fn rejects_leaf_distance_without_reserved_output_palette_index() {
        let flags = [OPAQUE | (1 << 1) | (1 << 2)];
        let clear = [-1];
        let mut indexes = vec![0; 4096];
        let mut sky = vec![0; 2048];
        let mut block = vec![0; 2048];
        let mut heights = [0; 256];
        let result = first_pass(
            &mut [section(&mut indexes, &flags, &clear, &mut sky, &mut block)],
            0,
            16,
            15,
            1,
            false,
            false,
            true,
            &mut heights,
        );
        assert_eq!(result, Err(FirstPassError::MissingLeafDistanceTarget));
    }
}
