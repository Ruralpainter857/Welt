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
        Ok(Self {
            perlin: PerlinNoise::new(seed),
            min_height,
            max_height,
            water_height,
            randomise,
            beaches,
            beach_ordinal,
            terrain_range_ordinals: terrain_range_ordinals.to_vec(),
        })
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

    pub fn fill_bulk(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        heights: &[i32],
        output: &mut [i32],
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
        let small_x: Vec<_> = (0..width)
            .map(|col| Self::scaled_axis(origin_x.wrapping_add(col as i32), SMALL_BLOBS))
            .collect();
        let tiny_x: Vec<_> = (0..width)
            .map(|col| Self::scaled_axis(origin_x.wrapping_add(col as i32), TINY_BLOBS))
            .collect();
        let small_y: Vec<_> = (0..height)
            .map(|row| Self::scaled_axis(origin_y.wrapping_add(row as i32), SMALL_BLOBS))
            .collect();
        let tiny_y: Vec<_> = (0..height)
            .map(|row| Self::scaled_axis(origin_y.wrapping_add(row as i32), TINY_BLOBS))
            .collect();
        for row in 0..height {
            for col in 0..width {
                let index = row * width + col;
                output[index] = self.get_terrain_ordinal_with_axes(
                    small_x[col],
                    small_y[row],
                    tiny_x[col],
                    tiny_y[row],
                    heights[index],
                );
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{SimpleThemeTerrainBulk, SimpleThemeTerrainError};

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
    fn non_random_theme_uses_range_table_without_noise() {
        let terrain =
            SimpleThemeTerrainBulk::new(-2, 2, 0, false, false, 99, 42, &[4, 5, 6, 7]).unwrap();
        assert_eq!(terrain.get_terrain_ordinal(100, -40, -10), 4);
        assert_eq!(terrain.get_terrain_ordinal(100, -40, 0), 6);
        assert_eq!(terrain.get_terrain_ordinal(100, -40, 10), 7);
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
