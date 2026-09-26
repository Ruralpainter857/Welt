//! Bulk kernel for `NoiseHeightMap.getValue(x, y)` (Phase 0, G4).
//!
//! The caller supplies the effective Perlin seed (`NoiseHeightMap.seed +
//! seedOffset` after Java's `setSeed`); the native kernel owns one noise
//! instance for the whole tile and writes row-major `f64` heights.

use welt_core::noise::PerlinNoise;

const LARGE_BLOBS: f32 = 65.537_f32;
const FACTORS: [i32; 10] = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NoiseHeightMapError {
    TooManyOctaves(i32),
    AreaOverflow,
    OutputLength { expected: usize, actual: usize },
}

/// Immutable batch evaluator matching WorldPainter's Java NoiseHeightMap.
#[derive(Debug)]
pub struct NoiseHeightMapBulk {
    perlin: PerlinNoise,
    d_height: f64,
    scale: f64,
    octaves: i32,
}

impl NoiseHeightMapBulk {
    /// `effective_seed` is the seed that Java has already combined with seedOffset.
    pub fn new(
        d_height: f64,
        scale: f64,
        octaves: i32,
        effective_seed: i64,
    ) -> Result<Self, NoiseHeightMapError> {
        if octaves > 10 {
            return Err(NoiseHeightMapError::TooManyOctaves(octaves));
        }
        Ok(Self {
            perlin: PerlinNoise::new(effective_seed),
            d_height,
            scale,
            octaves,
        })
    }

    #[inline]
    pub fn get_value(&self, x: f64, y: f64) -> f64 {
        let x = x / f64::from(LARGE_BLOBS) / self.scale;
        let y = y / f64::from(LARGE_BLOBS) / self.scale;
        self.get_value_normalized(x, y)
    }

    #[inline]
    fn get_value_normalized(&self, x: f64, y: f64) -> f64 {
        if self.octaves == 1 {
            return (f64::from(self.perlin.get_perlin_noise_2d(x, y)) + 0.5) * self.d_height;
        }
        let mut noise = 0.0_f64;
        for octave in 0..self.octaves {
            let factor = f64::from(FACTORS[octave as usize]);
            noise += f64::from(self.perlin.get_perlin_noise_2d(x * factor, y * factor));
        }
        noise /= f64::from(self.octaves);
        (noise + 0.5) * self.d_height
    }

    /// Computes a rectangle in a single call, with no per-cell JNI transitions.
    pub fn fill_bulk(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        output: &mut [f64],
    ) -> Result<(), NoiseHeightMapError> {
        let expected = width
            .checked_mul(height)
            .ok_or(NoiseHeightMapError::AreaOverflow)?;
        if output.len() != expected {
            return Err(NoiseHeightMapError::OutputLength {
                expected,
                actual: output.len(),
            });
        }
        // The two coordinate transforms do not depend on the other axis.
        // Reusing each exact Java-order division saves one pair per cell.
        let normalized_x: Vec<f64> = (0..width)
            .map(|col| {
                f64::from(origin_x.wrapping_add(col as i32)) / f64::from(LARGE_BLOBS) / self.scale
            })
            .collect();
        let normalized_y: Vec<f64> = (0..height)
            .map(|row| {
                f64::from(origin_y.wrapping_add(row as i32)) / f64::from(LARGE_BLOBS) / self.scale
            })
            .collect();
        let prepared_count = if self.octaves == 1 {
            1
        } else {
            self.octaves.max(0) as usize
        };
        let grids: Vec<_> = (0..prepared_count)
            .map(|octave| {
                if self.octaves == 1 {
                    self.perlin.prepare_grid_2d(&normalized_x, &normalized_y)
                } else {
                    let factor = f64::from(FACTORS[octave]);
                    let xs: Vec<_> = normalized_x.iter().map(|&x| x * factor).collect();
                    let ys: Vec<_> = normalized_y.iter().map(|&y| y * factor).collect();
                    self.perlin.prepare_grid_2d(&xs, &ys)
                }
            })
            .collect();
        for row in 0..height {
            for col in 0..width {
                output[row * width + col] = if self.octaves == 1 {
                    (f64::from(grids[0].get(col, row)) + 0.5) * self.d_height
                } else {
                    let mut noise = 0.0_f64;
                    for grid in &grids {
                        noise += f64::from(grid.get(col, row));
                    }
                    noise /= f64::from(self.octaves);
                    (noise + 0.5) * self.d_height
                };
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{NoiseHeightMapBulk, NoiseHeightMapError};
    use std::collections::HashMap;

    #[test]
    fn production_java_noise_height_map_golden_is_bit_exact() {
        let mut maps = HashMap::<(u64, u64, i32, i64), NoiseHeightMapBulk>::new();
        let mut checked = 0usize;
        for (line_index, raw) in include_str!("../../golden/noise-height-map-golden.txt")
            .lines()
            .enumerate()
        {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split_whitespace().collect();
            assert_eq!(fields.len(), 8, "line {}: {line}", line_index + 1);
            assert_eq!(fields[0], "noise_height_map", "line {}", line_index + 1);
            let d_height: f64 = fields[1].parse().unwrap();
            let scale: f64 = fields[2].parse().unwrap();
            let octaves: i32 = fields[3].parse().unwrap();
            let effective_seed: i64 = fields[4].parse().unwrap();
            let x: i32 = fields[5].parse().unwrap();
            let y: i32 = fields[6].parse().unwrap();
            let expected = u64::from_str_radix(fields[7], 16).unwrap();
            let key = (d_height.to_bits(), scale.to_bits(), octaves, effective_seed);
            let map = maps.entry(key).or_insert_with(|| {
                NoiseHeightMapBulk::new(d_height, scale, octaves, effective_seed).unwrap()
            });
            assert_eq!(
                map.get_value(f64::from(x), f64::from(y)).to_bits(),
                expected,
                "line {}: {line}",
                line_index + 1
            );
            checked += 1;
        }
        assert_eq!(checked, 512, "unexpected Java golden sample count");
    }

    #[test]
    fn bulk_tile_matches_scalar_values_and_order() {
        let map = NoiseHeightMapBulk::new(128.0, 2.75, 5, -7).unwrap();
        let (x0, y0, width, height) = (-64, 31, 17, 13);
        let mut values = vec![f64::NAN; width * height];
        map.fill_bulk(x0, y0, width, height, &mut values).unwrap();
        for row in 0..height {
            for col in 0..width {
                assert_eq!(
                    values[row * width + col].to_bits(),
                    map.get_value(f64::from(x0 + col as i32), f64::from(y0 + row as i32))
                        .to_bits()
                );
            }
        }
    }

    #[test]
    fn preserves_one_octave_and_octave_limit_contract() {
        assert!(NoiseHeightMapBulk::new(40.0, 3.0, 1, 9).is_ok());
        assert_eq!(
            NoiseHeightMapBulk::new(40.0, 3.0, 11, 9).unwrap_err(),
            NoiseHeightMapError::TooManyOctaves(11)
        );
    }

    #[test]
    fn bulk_rejects_wrong_output_size_and_overflow() {
        let map = NoiseHeightMapBulk::new(1.0, 1.0, 1, 0).unwrap();
        let mut wrong = [0.0; 3];
        assert_eq!(
            map.fill_bulk(0, 0, 2, 2, &mut wrong),
            Err(NoiseHeightMapError::OutputLength {
                expected: 4,
                actual: 3
            })
        );
        let mut empty = [];
        assert_eq!(
            map.fill_bulk(0, 0, usize::MAX, 2, &mut empty),
            Err(NoiseHeightMapError::AreaOverflow)
        );
    }
}
