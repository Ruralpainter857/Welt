//! Whole-tile terrain and layer decisions for WorldPainter's built-in FancyTheme.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FancyThemeError {
    InvalidDimensions,
    AreaOverflow,
    HeightLength { expected: usize, actual: usize },
    HeightNeighborhoodLength { expected: usize, actual: usize },
    ClimateLength { expected: usize, actual: usize },
    OutputLength { expected: usize, actual: usize },
}

pub const FANCY_THEME_OUTPUT_PLANES: usize = 7;
pub const FANCY_THEME_LAYER_PLANES: usize = 6;
pub const FANCY_THEME_HEIGHT_BORDER: usize = 5;

/// Computes terrain and layer values for one rectangular tile from its height neighborhood.
///
/// Output is plane-major: terrain ordinals, Jungle, SwampLand, DeciduousForest,
/// PineForest, Frost, and the theme's snow layer. Forest layers use 0 or 8;
/// bit layers use 0 or 1. Java remains responsible for applying these values
/// through Tile's normal mutation APIs.
#[allow(clippy::too_many_arguments)]
pub fn fill_fancy_theme_tile(
    width: usize,
    height: usize,
    water_height: i32,
    desert_max_height: i32,
    terrain_base: u8,
    terrain_desert: u8,
    terrain_sandstone: u8,
    terrain_bare_grass: u8,
    terrain_beaches: u8,
    terrain_dirt_and_gravel: u8,
    terrain_stone_and_gravel: u8,
    tile_heights: &[f32],
    height_neighborhood: &[f32],
    temperatures: &[f64],
    humidities: &[f64],
    forest_values: &[f64],
    output: &mut [u8],
) -> Result<(), FancyThemeError> {
    if width == 0 || height == 0 || width > 256 || height > 256 {
        return Err(FancyThemeError::InvalidDimensions);
    }
    let area = width
        .checked_mul(height)
        .ok_or(FancyThemeError::AreaOverflow)?;
    let neighborhood_width = width
        .checked_add(FANCY_THEME_HEIGHT_BORDER * 2)
        .ok_or(FancyThemeError::AreaOverflow)?;
    let neighborhood_height = height
        .checked_add(FANCY_THEME_HEIGHT_BORDER * 2)
        .ok_or(FancyThemeError::AreaOverflow)?;
    let neighborhood_area = neighborhood_width
        .checked_mul(neighborhood_height)
        .ok_or(FancyThemeError::AreaOverflow)?;
    let output_length = area
        .checked_mul(FANCY_THEME_OUTPUT_PLANES)
        .ok_or(FancyThemeError::AreaOverflow)?;
    if tile_heights.len() != area {
        return Err(FancyThemeError::HeightLength {
            expected: area,
            actual: tile_heights.len(),
        });
    }
    if height_neighborhood.len() != neighborhood_area {
        return Err(FancyThemeError::HeightNeighborhoodLength {
            expected: neighborhood_area,
            actual: height_neighborhood.len(),
        });
    }
    if temperatures.len() != area || humidities.len() != area || forest_values.len() != area {
        return Err(FancyThemeError::ClimateLength {
            expected: area,
            actual: temperatures
                .len()
                .min(humidities.len())
                .min(forest_values.len()),
        });
    }
    if output.len() != output_length {
        return Err(FancyThemeError::OutputLength {
            expected: output_length,
            actual: output.len(),
        });
    }

    output.fill(0);
    let plane = |index: usize, plane_index: usize| plane_index * area + index;
    let border = FANCY_THEME_HEIGHT_BORDER;
    let below_water_threshold = water_height.wrapping_sub(4) as f32;
    let above_water_threshold = water_height.wrapping_add(2) as f32;
    for y in 0..height {
        for x in 0..width {
            let index = y * width + x;
            let center_x = x + border;
            let center_y = y + border;
            let center = center_y * neighborhood_width + center_x;
            let north_south = (height_neighborhood[center - neighborhood_width]
                - height_neighborhood[center + neighborhood_width])
                .abs();
            let northwest_southeast = (height_neighborhood[center - neighborhood_width + 1]
                - height_neighborhood[center + neighborhood_width - 1])
                .abs();
            let east_west =
                (height_neighborhood[center + 1] - height_neighborhood[center - 1]).abs();
            let southeast_northwest = (height_neighborhood[center + neighborhood_width + 1]
                - height_neighborhood[center - neighborhood_width - 1])
                .abs();
            let slope = java_max(
                java_max(north_south, northwest_southeast),
                java_max(east_west, southeast_northwest),
            );

            let mut water_near = false;
            'water: for dy in -(border as isize)..=border as isize {
                let row = (center_y as isize + dy) as usize * neighborhood_width;
                for dx in -(border as isize)..=border as isize {
                    let sample = (center_x as isize + dx) as usize + row;
                    if height_neighborhood[sample] < water_height as f32 {
                        water_near = true;
                        break 'water;
                    }
                }
            }

            let height = tile_heights[index];
            let temperature = temperatures[index];
            let humidity = humidities[index];
            let forest = forest_values[index];
            let terrain = if slope > 2.0 {
                terrain_stone_and_gravel
            } else if slope > 1.5 {
                terrain_dirt_and_gravel
            } else if height < below_water_threshold {
                terrain_beaches
            } else if height < above_water_threshold && water_near {
                if temperature > 20.0
                    && humidity > 55.0
                    && slope < 0.75
                    && height < desert_max_height as f32
                    && forest > 0.35
                {
                    output[plane(index, 1)] = 8;
                }
                terrain_beaches
            } else if temperature < -5.0 {
                terrain_bare_grass
            } else if humidity < 40.0 {
                if slope < 0.75 && height < desert_max_height as f32 {
                    terrain_desert
                } else {
                    terrain_sandstone
                }
            } else {
                terrain_base
            };
            output[plane(index, 0)] = terrain;

            if !(slope > 2.0) && height > below_water_threshold && forest > 0.35 {
                if temperature > 20.0 {
                    if humidity > 55.0 {
                        if height < above_water_threshold {
                            output[plane(index, 2)] = 8;
                        } else {
                            output[plane(index, 1)] = 8;
                        }
                    } else if humidity > 40.0 {
                        output[plane(index, 3)] = 8;
                    }
                } else if temperature > 10.0 {
                    if humidity > 50.0 {
                        output[plane(index, 3)] = 8;
                    }
                } else if temperature > -20.0 && humidity > 50.0 {
                    output[plane(index, 4)] = 8;
                }
            }
            if temperature < 0.0 {
                output[plane(index, 5)] = 1;
                if temperature < -10.0
                    && humidity > 50.0
                    && height > water_height as f32
                    && slope < 1.5
                {
                    output[plane(index, 6)] = 1;
                }
            }
        }
    }
    Ok(())
}

fn java_max(left: f32, right: f32) -> f32 {
    if left.is_nan() || right.is_nan() {
        f32::NAN
    } else if left == 0.0 && right == 0.0 {
        if left.is_sign_positive() || right.is_sign_positive() {
            0.0
        } else {
            -0.0
        }
    } else if left > right {
        left
    } else {
        right
    }
}

#[cfg(test)]
mod tests {
    use super::{
        fill_fancy_theme_tile, FancyThemeError, FANCY_THEME_HEIGHT_BORDER,
        FANCY_THEME_OUTPUT_PLANES,
    };

    const BASE: u8 = 1;
    const DESERT: u8 = 2;
    const SANDSTONE: u8 = 3;
    const BEACH: u8 = 4;
    const DIRT_GRAVEL: u8 = 5;
    const STONE_GRAVEL: u8 = 6;

    fn run_case(
        center_height: f32,
        neighborhood: &[f32],
        temperature: f64,
        humidity: f64,
        forest: f64,
    ) -> Vec<u8> {
        let mut output = vec![0; FANCY_THEME_OUTPUT_PLANES];
        fill_fancy_theme_tile(
            1,
            1,
            62,
            82,
            BASE,
            DESERT,
            SANDSTONE,
            7,
            BEACH,
            DIRT_GRAVEL,
            STONE_GRAVEL,
            &[center_height],
            neighborhood,
            &[temperature],
            &[humidity],
            &[forest],
            &mut output,
        )
        .unwrap();
        output
    }

    #[test]
    fn applies_base_climate_forest_and_frost_rules() {
        let neighborhood = vec![70.0; (1 + 2 * FANCY_THEME_HEIGHT_BORDER).pow(2)];
        let output = run_case(70.0, &neighborhood, 11.0, 60.0, 0.5);
        assert_eq!(output[0], BASE);
        assert_eq!(output[3], 8); // DeciduousForest plane.
        assert_eq!(output[5], 0); // Frost plane.

        let frozen = run_case(70.0, &neighborhood, -12.0, 60.0, 0.5);
        assert_eq!(frozen[0], BASE);
        assert_eq!(frozen[5], 1);
        assert_eq!(frozen[6], 1);

        let cold = run_case(70.0, &neighborhood, -6.0, 60.0, 0.5);
        assert_eq!(cold[0], 7); // Bare grass.
    }

    #[test]
    fn beach_can_add_jungle_and_swamp_layers() {
        let neighborhood = vec![62.0; (1 + 2 * FANCY_THEME_HEIGHT_BORDER).pow(2)];
        let output = run_case(62.0, &neighborhood, 25.0, 60.0, 0.5);
        assert_eq!(output[0], BEACH);
        assert_eq!(output[1], 8); // The near-water jungle rule.
        assert_eq!(output[2], 8); // The low-elevation swamp rule.
    }

    #[test]
    fn steep_slopes_override_climate_but_still_allow_frost() {
        let width = 1;
        let side = width + 2 * FANCY_THEME_HEIGHT_BORDER;
        let mut neighborhood = vec![70.0; side * side];
        let center = FANCY_THEME_HEIGHT_BORDER * side + FANCY_THEME_HEIGHT_BORDER;
        neighborhood[center - side] = 74.0;
        let output = run_case(70.0, &neighborhood, -1.0, 60.0, 0.5);
        assert_eq!(output[0], STONE_GRAVEL);
        assert_eq!(output[5], 1);
        assert_eq!(output[6], 0);
    }

    #[test]
    fn nan_slope_follows_java_comparison_branches() {
        let width = 1;
        let side = width + 2 * FANCY_THEME_HEIGHT_BORDER;
        let mut neighborhood = vec![70.0; side * side];
        let center = FANCY_THEME_HEIGHT_BORDER * side + FANCY_THEME_HEIGHT_BORDER;
        neighborhood[center - 1] = f32::NAN;
        let output = run_case(70.0, &neighborhood, 11.0, 60.0, 0.5);
        assert_eq!(output[0], BASE);
        assert_eq!(output[3], 8);
    }

    #[test]
    fn rejects_short_neighborhood_without_touching_output() {
        let mut output = [0x55; FANCY_THEME_OUTPUT_PLANES];
        let result = fill_fancy_theme_tile(
            1,
            1,
            62,
            82,
            BASE,
            DESERT,
            SANDSTONE,
            7,
            BEACH,
            DIRT_GRAVEL,
            STONE_GRAVEL,
            &[70.0],
            &[70.0; 120],
            &[11.0],
            &[60.0],
            &[0.5],
            &mut output,
        );
        assert_eq!(
            result,
            Err(FancyThemeError::HeightNeighborhoodLength {
                expected: 121,
                actual: 120,
            })
        );
        assert_eq!(output, [0x55; FANCY_THEME_OUTPUT_PLANES]);
    }
}
