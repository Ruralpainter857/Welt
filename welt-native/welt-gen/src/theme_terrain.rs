//! Bulk `SimpleTheme.getTerrain` equivalent used during fresh tile creation.
//!
//! Inputs are the integer heights already quantised by `Tile`; coordinates use
//! Java's wrapping `int` addition and `float` division before widening to the
//! double arguments of `PerlinNoise`.

use welt_core::noise::{PerlinAxis3D, PerlinNoise};

const SMALL_BLOBS: f32 = 16.411_f32;
const TINY_BLOBS: f32 = 4.099_f32;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SimpleThemeTerrainError {
    InvalidHeightRange { min_height: i32, max_height: i32 },
    TerrainRangeLength { expected: usize, actual: usize },
    HeightLength { expected: usize, actual: usize },
    OutputLength { expected: usize, actual: usize },
    TerrainOrdinalOutOfRange { value: i32 },
    AreaOverflow,
}

/// Immutable batch terrain selector matching `SimpleTheme.getTerrain`.
#[derive(Debug)]
pub struct SimpleThemeTerrainBulk {
    perlin: PerlinNoise,
    min_height: i32,
    max_height: i32,
    water_height: i32,
    randomise: bool,
    beaches: bool,
    beach_ordinal: i32,
    terrain_range_ordinals: Vec<i32>,
}

/// Reusable axis preparation buffers for repeated tile evaluations on one worker.
#[derive(Default)]
pub struct SimpleThemeTerrainScratch {
    small_x: Vec<PerlinAxis3D>,
    tiny_x: Vec<PerlinAxis3D>,
    small_y: Vec<PerlinAxis3D>,
    tiny_y: Vec<PerlinAxis3D>,
}

impl SimpleThemeTerrainBulk {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        min_height: i32,
        max_height: i32,
        water_height: i32,
        randomise: bool,
        beaches: bool,
        beach_ordinal: i32,
        seed: i64,
        terrain_range_ordinals: &[i32],
    ) -> Result<Self, SimpleThemeTerrainError> {
        let mut terrain = Self {
            perlin: PerlinNoise::new(seed),
            min_height,
            max_height,
            water_height,
            randomise,
            beaches,
            beach_ordinal,
            terrain_range_ordinals: Vec::new(),
        };
        terrain.configure(
            min_height,
            max_height,
            water_height,
            randomise,
            beaches,
            beach_ordinal,
            seed,
            terrain_range_ordinals,
        )?;
        Ok(terrain)
    }

    /// Updates a worker's cached theme without rebuilding its Perlin permutation.
    #[allow(clippy::too_many_arguments)]
    pub fn configure(
        &mut self,
        min_height: i32,
        max_height: i32,
        water_height: i32,
        randomise: bool,
        beaches: bool,
        beach_ordinal: i32,
        seed: i64,
        terrain_range_ordinals: &[i32],
    ) -> Result<(), SimpleThemeTerrainError> {
        let range = i64::from(max_height) - i64::from(min_height);
        if range <= 0 {
            return Err(SimpleThemeTerrainError::InvalidHeightRange {
                min_height,
                max_height,
            });
        }
        let expected = usize::try_from(range).map_err(|_| SimpleThemeTerrainError::AreaOverflow)?;
        if terrain_range_ordinals.len() != expected {
            return Err(SimpleThemeTerrainError::TerrainRangeLength {
                expected,
                actual: terrain_range_ordinals.len(),
            });
        }
        self.perlin.set_seed(seed);
        self.min_height = min_height;
        self.max_height = max_height;
        self.water_height = water_height;
        self.randomise = randomise;
        self.beaches = beaches;
        self.beach_ordinal = beach_ordinal;
        self.terrain_range_ordinals.resize(expected, 0);
        self.terrain_range_ordinals
            .copy_from_slice(terrain_range_ordinals);
        Ok(())
    }

    #[inline]
    pub fn get_terrain_ordinal(&self, x: i32, y: i32, input_height: i32) -> i32 {
        self.get_terrain_ordinal_with_axes(
            Self::scaled_axis(x, SMALL_BLOBS),
            Self::scaled_axis(y, SMALL_BLOBS),
            Self::scaled_axis(x, TINY_BLOBS),
            Self::scaled_axis(y, TINY_BLOBS),
            input_height,
        )
    }

    #[inline]
    fn get_terrain_ordinal_with_axes(
        &self,
        small_x: PerlinAxis3D,
        small_y: PerlinAxis3D,
        tiny_x: PerlinAxis3D,
        tiny_y: PerlinAxis3D,
        input_height: i32,
    ) -> i32 {
        let mut height = input_height.clamp(self.min_height, self.max_height - 1);
        if self.beaches
            && height >= self.water_height.wrapping_sub(2)
            && height <= self.water_height.wrapping_add(1)
        {
            return self.beach_ordinal;
        }
        if self.randomise {
            let noise_1 = self.perlin.get_perlin_noise_3d_prepared(
                small_x,
                small_y,
                Self::scaled_axis(height, SMALL_BLOBS),
            );
            height = (height as f32 + noise_1 * 5.0_f32) as i32;
            let noise_2 = self.perlin.get_perlin_noise_3d_prepared(
                tiny_x,
                tiny_y,
                Self::scaled_axis(height, TINY_BLOBS),
            );
            height = (height as f32 + noise_2 * 5.0_f32) as i32;
        }
        height = height.clamp(self.min_height, self.max_height - 1);
        self.terrain_range_ordinals[(height - self.min_height) as usize]
    }

    #[inline]
    fn scaled_axis(coordinate: i32, scale: f32) -> PerlinAxis3D {
        PerlinNoise::prepare_axis_3d(f64::from(coordinate as f32 / scale))
    }

    fn prepare_axes(
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        scratch: &mut SimpleThemeTerrainScratch,
    ) {
        scratch.small_x.resize(width, PerlinAxis3D::default());
        scratch.tiny_x.resize(width, PerlinAxis3D::default());
        scratch.small_y.resize(height, PerlinAxis3D::default());
        scratch.tiny_y.resize(height, PerlinAxis3D::default());
        for col in 0..width {
            let x = origin_x.wrapping_add(col as i32);
            scratch.small_x[col] = Self::scaled_axis(x, SMALL_BLOBS);
            scratch.tiny_x[col] = Self::scaled_axis(x, TINY_BLOBS);
        }
        for row in 0..height {
            let y = origin_y.wrapping_add(row as i32);
            scratch.small_y[row] = Self::scaled_axis(y, SMALL_BLOBS);
            scratch.tiny_y[row] = Self::scaled_axis(y, TINY_BLOBS);
        }
    }

    /// Prepare local Java tile coordinates once for interleaved height/filter/theme edits.
    pub(crate) fn prepare_tile_axes(scratch: &mut SimpleThemeTerrainScratch) {
        Self::prepare_axes(0, 0, 128, 128, scratch);
    }

    /// Evaluate one quantised height using the same prepared axes as the bulk path.
    /// The caller supplies a validated local tile index and a prepared worker scratch.
    pub(crate) fn terrain_at_tile_cell(
        &self,
        cell: usize,
        height: i32,
        scratch: &SimpleThemeTerrainScratch,
    ) -> u8 {
        let x = cell % 128;
        let y = cell / 128;
        self.get_terrain_ordinal_with_axes(
            scratch.small_x[x],
            scratch.small_y[y],
            scratch.tiny_x[x],
            scratch.tiny_y[y],
            height,
        ) as u8
    }

    pub fn fill_bulk(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        heights: &[i32],
        output: &mut [i32],
    ) -> Result<(), SimpleThemeTerrainError> {
        self.fill_bulk_with_scratch(
            origin_x,
            origin_y,
            width,
            height,
            heights,
            output,
            &mut SimpleThemeTerrainScratch::default(),
        )
    }

    /// Evaluates a region using caller-owned axis buffers retained by its worker.
    #[allow(clippy::too_many_arguments)]
    pub fn fill_bulk_with_scratch(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        heights: &[i32],
        output: &mut [i32],
        scratch: &mut SimpleThemeTerrainScratch,
    ) -> Result<(), SimpleThemeTerrainError> {
        let expected = width
            .checked_mul(height)
            .ok_or(SimpleThemeTerrainError::AreaOverflow)?;
        if heights.len() != expected {
            return Err(SimpleThemeTerrainError::HeightLength {
                expected,
                actual: heights.len(),
            });
        }
        if output.len() != expected {
            return Err(SimpleThemeTerrainError::OutputLength {
                expected,
                actual: output.len(),
            });
        }
        Self::prepare_axes(origin_x, origin_y, width, height, scratch);
        for row in 0..height {
            for col in 0..width {
                let index = row * width + col;
                output[index] = self.get_terrain_ordinal_with_axes(
                    scratch.small_x[col],
                    scratch.small_y[row],
                    scratch.tiny_x[col],
                    scratch.tiny_y[row],
                    heights[index],
                );
            }
        }
        Ok(())
    }

    /// Fills compact Java terrain ordinals into a reused byte plane.
    #[allow(clippy::too_many_arguments)]
    pub fn fill_bulk_compact_with_scratch(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        heights: &[i32],
        output: &mut [u8],
        scratch: &mut SimpleThemeTerrainScratch,
    ) -> Result<(), SimpleThemeTerrainError> {
        self.fill_compact_selected(
            origin_x, origin_y, width, height, heights, None, output, scratch,
        )
    }

    /// Evaluate only cells changed by a grouped editing transaction; other output bytes remain untouched.
    pub(crate) fn fill_selected_tile_with_scratch(
        &self,
        heights: &[i32],
        selected: &[bool],
        output: &mut [u8],
        scratch: &mut SimpleThemeTerrainScratch,
    ) -> Result<(), SimpleThemeTerrainError> {
        if selected.len() != 16384 {
            return Err(SimpleThemeTerrainError::HeightLength {
                expected: 16384,
                actual: selected.len(),
            });
        }
        self.fill_compact_selected(0, 0, 128, 128, heights, Some(selected), output, scratch)
    }

    #[allow(clippy::too_many_arguments)]
    fn fill_compact_selected(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        heights: &[i32],
        selected: Option<&[bool]>,
        output: &mut [u8],
        scratch: &mut SimpleThemeTerrainScratch,
    ) -> Result<(), SimpleThemeTerrainError> {
        let expected = width
            .checked_mul(height)
            .ok_or(SimpleThemeTerrainError::AreaOverflow)?;
        if heights.len() != expected {
            return Err(SimpleThemeTerrainError::HeightLength {
                expected,
                actual: heights.len(),
            });
        }
        if output.len() != expected {
            return Err(SimpleThemeTerrainError::OutputLength {
                expected,
                actual: output.len(),
            });
        }
        if self.beaches && !(0..=u8::MAX as i32).contains(&self.beach_ordinal) {
            return Err(SimpleThemeTerrainError::TerrainOrdinalOutOfRange {
                value: self.beach_ordinal,
            });
        }
        if let Some(value) = self
            .terrain_range_ordinals
            .iter()
            .copied()
            .find(|value| !(0..=u8::MAX as i32).contains(value))
        {
            return Err(SimpleThemeTerrainError::TerrainOrdinalOutOfRange { value });
        }
        Self::prepare_axes(origin_x, origin_y, width, height, scratch);
        for row in 0..height {
            for col in 0..width {
                let index = row * width + col;
                if selected.is_some_and(|mask| !mask[index]) {
                    continue;
                }
                let ordinal = self.get_terrain_ordinal_with_axes(
                    scratch.small_x[col],
                    scratch.small_y[row],
                    scratch.tiny_x[col],
                    scratch.tiny_y[row],
                    heights[index],
                );
                output[index] = ordinal as u8;
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{SimpleThemeTerrainBulk, SimpleThemeTerrainError, SimpleThemeTerrainScratch};

    #[test]
    fn bulk_matches_scalar_for_randomised_beach_and_clamped_heights() {
        let terrain_ranges: Vec<_> = (0..256).map(|index| 10 + index).collect();
        let terrain =
            SimpleThemeTerrainBulk::new(0, 256, 62, true, true, 99, -0x1234_5678, &terrain_ranges)
                .unwrap();
        let (origin_x, origin_y, width, height) = (i32::MAX - 2, i32::MIN + 3, 7, 5);
        let input: Vec<_> = (0..width * height)
            .map(|index| match index % 5 {
                0 => -20,
                1 => 60,
                2 => 62,
                3 => 63,
                _ => 900,
            })
            .collect();
        let mut output = vec![-1; input.len()];
        terrain
            .fill_bulk(origin_x, origin_y, width, height, &input, &mut output)
            .unwrap();
        for row in 0..height {
            for col in 0..width {
                let index = row * width + col;
                assert_eq!(
                    output[index],
                    terrain.get_terrain_ordinal(
                        origin_x.wrapping_add(col as i32),
                        origin_y.wrapping_add(row as i32),
                        input[index]
                    )
                );
            }
        }
    }

    #[test]
    fn interleaved_cell_terrain_matches_scalar_and_bulk_after_reconfiguration() {
        let mut scratch = SimpleThemeTerrainScratch::default();
        for seed in [0, 42, -17, i64::MAX] {
            for (min, max, water) in [(-64, 320, 62), (0, 256, 1)] {
                let ranges: Vec<_> = (min..max).map(|h| (h - min) % 251).collect();
                for beaches in [false, true] {
                    let terrain = SimpleThemeTerrainBulk::new(
                        min, max, water, true, beaches, 255, seed, &ranges,
                    )
                    .unwrap();
                    SimpleThemeTerrainBulk::prepare_tile_axes(&mut scratch);
                    let capacities = (
                        scratch.small_x.capacity(),
                        scratch.small_y.capacity(),
                        scratch.tiny_x.capacity(),
                        scratch.tiny_y.capacity(),
                    );
                    for cell in 0..16384 {
                        let height = match cell % 7 {
                            0 => min - 20,
                            1 => max + 20,
                            2 => water - 2,
                            3 => water + 1,
                            _ => min + (cell % ((max - min) as usize)) as i32,
                        };
                        assert_eq!(
                            terrain.terrain_at_tile_cell(cell, height, &scratch) as i32,
                            terrain.get_terrain_ordinal(
                                (cell % 128) as i32,
                                (cell / 128) as i32,
                                height
                            )
                        );
                    }
                    SimpleThemeTerrainBulk::prepare_tile_axes(&mut scratch);
                    assert_eq!(
                        capacities,
                        (
                            scratch.small_x.capacity(),
                            scratch.small_y.capacity(),
                            scratch.tiny_x.capacity(),
                            scratch.tiny_y.capacity()
                        )
                    );
                }
            }
        }
    }

    #[test]
    fn non_random_theme_uses_range_table_without_noise() {
        let terrain =
            SimpleThemeTerrainBulk::new(-2, 2, 0, false, false, 99, 42, &[4, 5, 6, 7]).unwrap();
        assert_eq!(terrain.get_terrain_ordinal(100, -40, -10), 4);
        assert_eq!(terrain.get_terrain_ordinal(100, -40, 0), 6);
        assert_eq!(terrain.get_terrain_ordinal(100, -40, 10), 7);
    }

    #[test]
    fn reused_worker_theme_and_scratch_match_fresh_themes_after_reconfiguration() {
        let first_ranges: Vec<_> = (0..64).map(|index| 100 + index).collect();
        let second_ranges: Vec<_> = (0..64).map(|index| 300 - index).collect();
        let first_heights: Vec<_> = (0..35).map(|index| (index * 7 % 80) - 8).collect();
        let second_heights: Vec<_> = (0..12).map(|index| (index * 11 % 75) - 5).collect();
        let mut reused =
            SimpleThemeTerrainBulk::new(0, 64, 31, true, true, 99, -123, &first_ranges).unwrap();
        let mut scratch = super::SimpleThemeTerrainScratch::default();

        let mut first_actual = vec![0; first_heights.len()];
        reused
            .fill_bulk_with_scratch(
                -19,
                i32::MAX - 4,
                7,
                5,
                &first_heights,
                &mut first_actual,
                &mut scratch,
            )
            .unwrap();
        let first_fresh =
            SimpleThemeTerrainBulk::new(0, 64, 31, true, true, 99, -123, &first_ranges).unwrap();
        let mut first_expected = vec![0; first_heights.len()];
        first_fresh
            .fill_bulk(-19, i32::MAX - 4, 7, 5, &first_heights, &mut first_expected)
            .unwrap();
        assert_eq!(first_actual, first_expected);

        reused
            .configure(-8, 56, 18, true, false, 77, i64::MIN + 9, &second_ranges)
            .unwrap();
        let mut second_actual = vec![0; second_heights.len()];
        reused
            .fill_bulk_with_scratch(
                i32::MIN + 3,
                29,
                3,
                4,
                &second_heights,
                &mut second_actual,
                &mut scratch,
            )
            .unwrap();
        let second_fresh =
            SimpleThemeTerrainBulk::new(-8, 56, 18, true, false, 77, i64::MIN + 9, &second_ranges)
                .unwrap();
        let mut second_expected = vec![0; second_heights.len()];
        second_fresh
            .fill_bulk(
                i32::MIN + 3,
                29,
                3,
                4,
                &second_heights,
                &mut second_expected,
            )
            .unwrap();
        assert_eq!(second_actual, second_expected);
    }

    #[test]
    fn compact_output_matches_full_ordinals_and_rejects_values_without_writing() {
        let ranges: Vec<_> = (0..64).map(|index| 40 + index).collect();
        let terrain =
            SimpleThemeTerrainBulk::new(0, 64, 31, true, true, 7, 0x1020_3040, &ranges).unwrap();
        let heights: Vec<_> = (0..35).map(|index| (index * 13 % 80) - 8).collect();
        let mut full = vec![0; heights.len()];
        let mut compact = vec![u8::MAX; heights.len()];
        let mut scratch = super::SimpleThemeTerrainScratch::default();
        terrain
            .fill_bulk(-31, 67, 7, 5, &heights, &mut full)
            .unwrap();
        terrain
            .fill_bulk_compact_with_scratch(-31, 67, 7, 5, &heights, &mut compact, &mut scratch)
            .unwrap();
        assert_eq!(
            compact,
            full.iter().map(|value| *value as u8).collect::<Vec<_>>()
        );

        let unrepresentable =
            SimpleThemeTerrainBulk::new(0, 2, 0, false, false, 999, 4, &[1, 300]).unwrap();
        let mut unchanged = [0xabu8; 2];
        assert_eq!(
            unrepresentable.fill_bulk_compact_with_scratch(
                0,
                0,
                2,
                1,
                &[0, 1],
                &mut unchanged,
                &mut scratch,
            ),
            Err(SimpleThemeTerrainError::TerrainOrdinalOutOfRange { value: 300 })
        );
        assert_eq!(unchanged, [0xab; 2]);
    }

    #[test]
    fn validates_ranges_input_and_output_lengths() {
        assert_eq!(
            SimpleThemeTerrainBulk::new(4, 4, 0, false, false, 0, 1, &[]).unwrap_err(),
            SimpleThemeTerrainError::InvalidHeightRange {
                min_height: 4,
                max_height: 4
            }
        );
        assert_eq!(
            SimpleThemeTerrainBulk::new(0, 2, 0, false, false, 0, 1, &[5]).unwrap_err(),
            SimpleThemeTerrainError::TerrainRangeLength {
                expected: 2,
                actual: 1
            }
        );
        let terrain = SimpleThemeTerrainBulk::new(0, 1, 0, false, false, 0, 1, &[5]).unwrap();
        let mut output = [];
        assert_eq!(
            terrain.fill_bulk(0, 0, 2, 2, &[0; 4], &mut output),
            Err(SimpleThemeTerrainError::OutputLength {
                expected: 4,
                actual: 0
            })
        );
    }
}
