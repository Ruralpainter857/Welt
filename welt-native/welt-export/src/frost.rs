//! Bulk column kernel for the `FrostExporter` ADD_FEATURES pass (Phase 0, G5).
//!
//! Java adapts `Material` and `Dimension` snapshots to compact flags, supplies
//! its already-drawn random snow thickness for MODE_RANDOM, then applies the
//! resulting per-block replacements through `MinecraftWorld.setMaterialAt`.
//! This keeps Java's event/undo ownership outside the native kernel.

const WATER_SOURCE: u8 = 1 << 0;
const WATERLOGGED: u8 = 1 << 1;
const INSUBSTANTIAL: u8 = 1 << 2;
const CAN_SUPPORT_SNOW: u8 = 1 << 3;
const LEAF_BLOCK: u8 = 1 << 4;
const SUSTAINS_LEAVES: u8 = 1 << 5;
const EMPTY: u8 = 1 << 6;
const GRASS_OR_FERN: u8 = 1 << 7;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum FrostUpdate {
    #[default]
    Unchanged,
    Air,
    Ice,
    Snow(u8),
}

/// Classification snapshot for one vertical block position.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FrostCell {
    flags: u8,
    /// Existing snow layer count, if this cell is a snow block (1..=8); zero means not snow.
    snow_layers: u8,
    canonical_snow: bool,
    /// Kernel output; Java applies this after the complete column calculation.
    pub update: FrostUpdate,
}

impl FrostCell {
    pub const fn new(flags: u8, snow_layers: u8) -> Self {
        Self {
            flags,
            snow_layers,
            canonical_snow: snow_layers == 1,
            update: FrostUpdate::Unchanged,
        }
    }
    /// JNI adapter preserves Java's `previousMaterial == SNOW` identity test.
    pub const fn with_canonical_snow(flags: u8, snow_layers: u8, canonical_snow: bool) -> Self {
        Self {
            flags,
            snow_layers,
            canonical_snow,
            update: FrostUpdate::Unchanged,
        }
    }
    pub const fn air() -> Self {
        Self::new(EMPTY, 0)
    }
    pub const fn is_insubstantial(self) -> bool {
        self.flags & INSUBSTANTIAL != 0
    }
}

pub mod material_flags {
    //! Flags used to adapt WorldPainter/Minecraft `Material` instances.
    pub const WATER_SOURCE: u8 = super::WATER_SOURCE;
    pub const WATERLOGGED: u8 = super::WATERLOGGED;
    pub const INSUBSTANTIAL: u8 = super::INSUBSTANTIAL;
    pub const CAN_SUPPORT_SNOW: u8 = super::CAN_SUPPORT_SNOW;
    pub const LEAF_BLOCK: u8 = super::LEAF_BLOCK;
    pub const SUSTAINS_LEAVES: u8 = super::SUSTAINS_LEAVES;
    pub const EMPTY: u8 = super::EMPTY;
    pub const GRASS_OR_FERN: u8 = super::GRASS_OR_FERN;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FrostMode {
    Flat,
    Random,
    Smooth,
    SmoothAtAllElevations,
}

#[derive(Clone, Copy, Debug)]
pub struct FrostSettings {
    pub frost_everywhere: bool,
    pub frost_layer_present: bool,
    pub snow_under_trees: bool,
    pub mode: FrostMode,
    /// Java Random draw supplied by the caller for MODE_RANDOM (must be 1..=3).
    pub random_snow_layers: u8,
    pub height_float: f32,
    pub height_int: i32,
    /// `Dimension.getBitLayerCount(Frost.INSTANCE, x, y, 1)` snapshot.
    pub frost_bit_count: i32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FrostError {
    InvalidBounds,
    ColumnLength { expected: usize, actual: usize },
    HighestNonAirOutOfBounds(i32),
    InvalidRandomLayerCount(u8),
    InvalidSnowLayerCount(i32),
}

/// Applies one column's frost decisions in-place to the output fields.
///
/// `cells[0]` corresponds to `min_z`; the slice must cover `[min_z, max_z]`.
/// Only output markers change, so flags remain the immutable source snapshot.
pub fn apply_frost_column(
    cells: &mut [FrostCell],
    min_z: i32,
    max_z: i32,
    highest_non_air: i32,
    settings: FrostSettings,
) -> Result<(), FrostError> {
    if min_z > max_z {
        return Err(FrostError::InvalidBounds);
    }
    let expected = (i64::from(max_z) - i64::from(min_z) + 1) as usize;
    if cells.len() != expected {
        return Err(FrostError::ColumnLength {
            expected,
            actual: cells.len(),
        });
    }
    if highest_non_air < min_z || highest_non_air > max_z {
        return Err(FrostError::HighestNonAirOutOfBounds(highest_non_air));
    }
    for cell in cells.iter_mut() {
        cell.update = FrostUpdate::Unchanged;
    }
    if settings.mode == FrostMode::Random && !(1..=3).contains(&settings.random_snow_layers) {
        return Err(FrostError::InvalidRandomLayerCount(
            settings.random_snow_layers,
        ));
    }
    if !settings.frost_everywhere && !settings.frost_layer_present {
        return Ok(());
    }

    let index = |z: i32| (i64::from(z) - i64::from(min_z)) as usize;
    let mut previous_flags = if highest_non_air == max_z {
        cells[index(max_z)].flags
    } else {
        EMPTY
    };
    // Java tests `previousMaterial == SNOW` by identity, which only matches the
    // canonical one-layer snow material, not arbitrary SNOW-with-LAYERS values.
    let mut previous_is_snow = highest_non_air == max_z && cells[index(max_z)].canonical_snow;
    let mut leaf_blocks = 0_i32;
    let last_scan_z = highest_non_air.min(max_z.saturating_sub(1));
    if last_scan_z < min_z {
        return Ok(());
    }

    for z in (min_z..=last_scan_z).rev() {
        let flags = cells[index(z)].flags;
        let is_freezable =
            flags & WATER_SOURCE != 0 || (flags & WATERLOGGED != 0 && flags & INSUBSTANTIAL != 0);
        if is_freezable {
            cells[index(z)].update = FrostUpdate::Ice;
            for above_z in z.saturating_add(1)..=highest_non_air {
                let above = &mut cells[index(above_z)];
                if above.flags & INSUBSTANTIAL != 0 {
                    above.update = FrostUpdate::Air;
                } else {
                    break;
                }
            }
            break;
        }

        if flags & CAN_SUPPORT_SNOW != 0 {
            if flags & (LEAF_BLOCK | SUSTAINS_LEAVES) != 0 {
                if previous_flags & EMPTY != 0 {
                    cells[index(z + 1)].update = FrostUpdate::Snow(1);
                }
                leaf_blocks += 1;
                if !settings.snow_under_trees && leaf_blocks > 1 {
                    break;
                }
            } else {
                if previous_flags & (EMPTY | GRASS_OR_FERN) != 0 || previous_is_snow {
                    let at_surface = z == settings.height_int;
                    let layers = if settings.mode == FrostMode::SmoothAtAllElevations || at_surface
                    {
                        match settings.mode {
                            FrostMode::Flat => 1_i32,
                            FrostMode::Random => i32::from(settings.random_snow_layers),
                            FrostMode::Smooth | FrostMode::SmoothAtAllElevations => {
                                let level = (settings.height_float + 0.5_f32
                                    - settings.height_int as f32)
                                    / 0.125_f32;
                                let mut value = level.floor() as i32 + 1;
                                if value > 1 && !settings.frost_everywhere {
                                    value =
                                        value.min(settings.frost_bit_count.wrapping_sub(1)).max(1);
                                }
                                value
                            }
                        }
                    } else {
                        1
                    };
                    place_snow(cells, min_z, max_z, z + 1, layers)?;
                }
                break;
            }
        }
        previous_flags = flags;
        previous_is_snow = cells[index(z)].canonical_snow;
    }
    Ok(())
}

fn place_snow(
    cells: &mut [FrostCell],
    min_z: i32,
    max_z: i32,
    z: i32,
    layers: i32,
) -> Result<(), FrostError> {
    if !(1..=8).contains(&layers) {
        return Err(FrostError::InvalidSnowLayerCount(layers));
    }
    if z < min_z || z > max_z {
        return Err(FrostError::InvalidBounds);
    }
    let index = (z - min_z) as usize;
    let cell = &mut cells[index];
    let current = if cell.snow_layers > 0 {
        i32::from(cell.snow_layers)
    } else {
        0
    };
    cell.update = FrostUpdate::Snow(layers.max(current) as u8);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{
        apply_frost_column, material_flags as f, FrostCell, FrostError, FrostMode, FrostSettings,
        FrostUpdate,
    };

    fn settings(mode: FrostMode) -> FrostSettings {
        FrostSettings {
            frost_everywhere: false,
            frost_layer_present: true,
            snow_under_trees: true,
            mode,
            random_snow_layers: 2,
            height_float: 10.75,
            height_int: 10,
            frost_bit_count: 5,
        }
    }

    #[test]
    fn freezes_water_and_clears_insubstantial_blocks_above() {
        let mut cells = [
            FrostCell::air(),
            FrostCell::new(f::WATER_SOURCE, 0),
            FrostCell::new(f::INSUBSTANTIAL, 0),
            FrostCell::new(f::INSUBSTANTIAL, 0),
            FrostCell::new(f::CAN_SUPPORT_SNOW, 0),
        ];
        apply_frost_column(&mut cells, 0, 4, 3, settings(FrostMode::Flat)).unwrap();
        assert_eq!(cells[1].update, FrostUpdate::Ice);
        assert_eq!(cells[2].update, FrostUpdate::Air);
        assert_eq!(cells[3].update, FrostUpdate::Air);
    }

    #[test]
    fn snow_modes_and_existing_snow_preserve_exporter_rules() {
        let mut cells = [
            FrostCell::air(),
            FrostCell::new(f::CAN_SUPPORT_SNOW, 0),
            FrostCell::new(0, 4),
            FrostCell::air(),
        ];
        apply_frost_column(&mut cells, 0, 3, 1, settings(FrostMode::Flat)).unwrap();
        assert_eq!(cells[2].update, FrostUpdate::Snow(4));

        let mut random = [
            FrostCell::air(),
            FrostCell::new(f::CAN_SUPPORT_SNOW, 0),
            FrostCell::air(),
        ];
        let mut random_settings = settings(FrostMode::Random);
        random_settings.height_int = 1;
        apply_frost_column(&mut random, 0, 2, 1, random_settings).unwrap();
        assert_eq!(random[2].update, FrostUpdate::Snow(2));
    }

    #[test]
    fn layer_presence_and_column_shape_are_checked() {
        let mut cells = [FrostCell::air(); 2];
        let mut no_frost = settings(FrostMode::Flat);
        no_frost.frost_layer_present = false;
        apply_frost_column(&mut cells, 0, 1, 0, no_frost).unwrap();
        assert_eq!(cells[1].update, FrostUpdate::Unchanged);
        assert_eq!(
            apply_frost_column(&mut cells, 0, 2, 0, settings(FrostMode::Flat)),
            Err(FrostError::ColumnLength {
                expected: 3,
                actual: 2
            })
        );
    }

    #[test]
    fn production_java_frost_exporter_golden_is_bit_exact() {
        let mut lines = include_str!("../../golden/frost-export-golden.txt")
            .lines()
            .filter(|line| !line.is_empty() && !line.starts_with('#'));
        let mut case_count = 0;

        while let Some(header) = lines.next() {
            let header: Vec<_> = header.split_whitespace().collect();
            assert_eq!(header.first(), Some(&"case"));
            assert_eq!(header.len(), 13, "malformed frost header: {header:?}");

            let parse_i32 = |index: usize| {
                header[index]
                    .parse::<i32>()
                    .unwrap_or_else(|error| panic!("bad frost header {header:?}: {error}"))
            };
            let name = header[1];
            let min_z = parse_i32(2);
            let max_z = parse_i32(3);
            let highest_non_air = parse_i32(4);
            let frost_everywhere = parse_i32(5) != 0;
            let frost_layer_present = parse_i32(6) != 0;
            let snow_under_trees = parse_i32(7) != 0;
            let mode = match parse_i32(8) {
                0 => FrostMode::Flat,
                1 => FrostMode::Random,
                2 => FrostMode::Smooth,
                3 => FrostMode::SmoothAtAllElevations,
                value => panic!("unknown frost mode {value} in case {name}"),
            };
            let random_snow_layers = parse_i32(9) as u8;
            let height_float_bits = u32::from_str_radix(header[10], 16)
                .unwrap_or_else(|error| panic!("bad height float in {name}: {error}"));
            let height_float = f32::from_bits(height_float_bits);
            let height_int = parse_i32(11);
            let frost_bit_count = parse_i32(12);

            let mut cells = Vec::with_capacity((max_z - min_z + 1) as usize);
            let mut expected = Vec::with_capacity(cells.capacity());
            for z in min_z..=max_z {
                let row = lines
                    .next()
                    .unwrap_or_else(|| panic!("truncated frost golden in case {name} at z={z}"));
                let fields: Vec<_> = row.split_whitespace().collect();
                assert_eq!(fields.len(), 6, "malformed frost cell: {fields:?}");
                assert_eq!(fields[0], "cell", "malformed frost cell: {fields:?}");
                assert_eq!(fields[1].parse::<i32>().unwrap(), z, "case {name}");
                let flags = fields[2].parse::<u8>().unwrap();
                let snow_layers = fields[3].parse::<u8>().unwrap();
                let update_kind = fields[4].parse::<u8>().unwrap();
                let update_layers = fields[5].parse::<u8>().unwrap();
                cells.push(FrostCell::new(flags, snow_layers));
                expected.push(match update_kind {
                    0 => FrostUpdate::Unchanged,
                    1 => FrostUpdate::Air,
                    2 => FrostUpdate::Ice,
                    3 => FrostUpdate::Snow(update_layers),
                    value => panic!("unknown update kind {value} in case {name} at z={z}"),
                });
            }

            let settings = FrostSettings {
                frost_everywhere,
                frost_layer_present,
                snow_under_trees,
                mode,
                random_snow_layers,
                height_float,
                height_int,
                frost_bit_count,
            };
            apply_frost_column(&mut cells, min_z, max_z, highest_non_air, settings)
                .unwrap_or_else(|error| panic!("kernel failed in case {name}: {error:?}"));

            for (offset, (cell, expected_update)) in cells.iter().zip(expected).enumerate() {
                assert_eq!(
                    cell.update,
                    expected_update,
                    "Java/Rust frost mismatch in case {name} at z={}",
                    min_z + offset as i32
                );
            }
            case_count += 1;
        }

        assert_eq!(case_count, 6, "unexpected production frost golden coverage");
    }
}
