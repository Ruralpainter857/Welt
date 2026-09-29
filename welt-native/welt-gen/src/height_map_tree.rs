//! Bulk evaluator for pure constant/noise/composite height-map expression trees.

use crate::noise_height_map::{NoiseHeightMapBulk, NoiseHeightMapError};
use fastnoise_lite::{FastNoiseLite, FractalType, NoiseType};

pub const MAX_PROGRAM_NODES: usize = 64;
pub const MAX_NOISE_VALUES: usize = 1_048_576;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum HeightMapNode {
    Constant(f64),
    Noise {
        d_height: f64,
        scale: f64,
        octaves: i32,
        effective_seed: i64,
    },
    FastNoiseLite {
        height: f64,
        frequency: f64,
        octaves: i32,
        effective_seed: i64,
        noise_type: i32,
        fractal_type: i32,
    },
    Mandelbrot,
    Banded {
        segment1_length: i32,
        segment1_end_height: f64,
        segment2_length: i32,
        segment2_end_height: f64,
        smooth: bool,
    },
    Shelving {
        shelve_height: i32,
        shelve_strength: i32,
    },
    NinePatch {
        inner_size: i32,
        border_size: i32,
        coast_size: i32,
        height: f64,
    },
    Add,
    Subtract,
    Multiply,
    Minimum,
    Maximum,
}

#[derive(Debug, PartialEq, Eq)]
pub enum HeightMapTreeError {
    InvalidProgram,
    TooManyNodes(usize),
    TooManyNoiseValues,
    AreaOverflow,
    OutputLength { expected: usize, actual: usize },
    Noise(NoiseHeightMapError),
}

enum NoiseMapEvaluator {
    Legacy(NoiseHeightMapBulk),
    FastNoiseLite(FastNoiseLiteHeightMapBulk),
}

impl NoiseMapEvaluator {
    fn get_value(&self, x: f64, y: f64) -> f64 {
        match self {
            Self::Legacy(map) => map.get_value(x, y),
            Self::FastNoiseLite(map) => map.get_value(x as f32, y as f32),
        }
    }

    fn fill_bulk(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        output: &mut [f64],
    ) -> Result<(), HeightMapTreeError> {
        match self {
            Self::Legacy(map) => {
                map.fill_bulk(origin_x, origin_y, width, height, output)?;
                Ok(())
            }
            Self::FastNoiseLite(map) => map.fill_bulk(origin_x, origin_y, width, height, output),
        }
    }
}

struct FastNoiseLiteHeightMapBulk {
    noise: FastNoiseLite,
    height: f64,
}

impl FastNoiseLiteHeightMapBulk {
    fn new(
        height: f64,
        frequency: f64,
        octaves: i32,
        effective_seed: i64,
        noise_type_code: i32,
        fractal_type_code: i32,
    ) -> Option<Self> {
        if !height.is_finite()
            || !frequency.is_finite()
            || frequency <= 0.0
            || !(1..=10).contains(&octaves)
        {
            return None;
        }
        let noise_type = match noise_type_code {
            0 => NoiseType::OpenSimplex2,
            1 => NoiseType::OpenSimplex2S,
            2 => NoiseType::Cellular,
            3 => NoiseType::Perlin,
            4 => NoiseType::ValueCubic,
            5 => NoiseType::Value,
            _ => return None,
        };
        let fractal_type = match fractal_type_code {
            0 => FractalType::None,
            1 => FractalType::FBm,
            2 => FractalType::Ridged,
            3 => FractalType::PingPong,
            _ => return None,
        };
        let mut noise = FastNoiseLite::with_seed(effective_seed as i32);
        noise.set_noise_type(Some(noise_type));
        noise.set_fractal_type(Some(fractal_type));
        noise.set_fractal_octaves(Some(octaves));
        noise.set_fractal_lacunarity(Some(2.0));
        noise.set_fractal_gain(Some(0.5));
        noise.set_frequency(Some(frequency as f32));
        Some(Self { noise, height })
    }

    #[inline]
    fn get_value(&self, x: f32, y: f32) -> f64 {
        let normalized = (self.noise.get_noise_2d(x, y) + 1.0_f32) * 0.5_f32;
        f64::from(normalized) * self.height
    }

    fn fill_bulk(
        &self,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        height: usize,
        output: &mut [f64],
    ) -> Result<(), HeightMapTreeError> {
        let area = width
            .checked_mul(height)
            .ok_or(HeightMapTreeError::AreaOverflow)?;
        if output.len() != area {
            return Err(HeightMapTreeError::OutputLength {
                expected: area,
                actual: output.len(),
            });
        }
        for row in 0..height {
            let y = origin_y.wrapping_add(row as i32) as f32;
            for col in 0..width {
                let x = origin_x.wrapping_add(col as i32) as f32;
                output[row * width + col] = self.get_value(x, y);
            }
        }
        Ok(())
    }
}

impl From<NoiseHeightMapError> for HeightMapTreeError {
    fn from(error: NoiseHeightMapError) -> Self {
        Self::Noise(error)
    }
}

/// Evaluates a post-order expression, preserving Java's left-to-right sum tree.
pub fn fill_height_map_tree(
    nodes: &[HeightMapNode],
    origin_x: i32,
    origin_y: i32,
    width: usize,
    height: usize,
    output: &mut [f64],
) -> Result<(), HeightMapTreeError> {
    if nodes.is_empty() {
        return Err(HeightMapTreeError::InvalidProgram);
    }
    if nodes.len() > MAX_PROGRAM_NODES {
        return Err(HeightMapTreeError::TooManyNodes(nodes.len()));
    }
    let area = width
        .checked_mul(height)
        .ok_or(HeightMapTreeError::AreaOverflow)?;
    if output.len() != area {
        return Err(HeightMapTreeError::OutputLength {
            expected: area,
            actual: output.len(),
        });
    }

    if let [HeightMapNode::Banded {
        segment1_length,
        segment1_end_height,
        segment2_length,
        segment2_end_height,
        smooth,
    }] = nodes
    {
        for x in 0..width {
            let banded_value = banded_height(
                origin_x.wrapping_add(x as i32) as f32,
                *segment1_length,
                *segment1_end_height,
                *segment2_length,
                *segment2_end_height,
                *smooth,
            );
            for y in 0..height {
                output[y * width + x] = banded_value;
            }
        }
        return Ok(());
    }

    if let [HeightMapNode::NinePatch {
        inner_size,
        border_size,
        coast_size,
        height: map_height,
    }] = nodes
    {
        for x in 0..width {
            let world_x = origin_x.wrapping_add(x as i32) as f32;
            for y in 0..height {
                let world_y = origin_y.wrapping_add(y as i32) as f32;
                output[y * width + x] = nine_patch_height(
                    world_x,
                    world_y,
                    *inner_size,
                    *border_size,
                    *coast_size,
                    *map_height,
                );
            }
        }
        return Ok(());
    }

    let mut depth = 0_usize;
    let mut noise_maps = Vec::new();
    let mut parsed = Vec::with_capacity(nodes.len());
    for &node in nodes {
        match node {
            HeightMapNode::Constant(value) => {
                parsed.push(ParsedNode::Constant(value));
                depth += 1;
            }
            HeightMapNode::Noise {
                d_height,
                scale,
                octaves,
                effective_seed,
            } => {
                if octaves > 10 {
                    return Err(HeightMapTreeError::Noise(
                        NoiseHeightMapError::TooManyOctaves(octaves),
                    ));
                }
                noise_maps.push(NoiseMapEvaluator::Legacy(NoiseHeightMapBulk::new(
                    d_height,
                    scale,
                    octaves,
                    effective_seed,
                )?));
                parsed.push(ParsedNode::Noise(noise_maps.len() - 1));
                depth += 1;
            }
            HeightMapNode::FastNoiseLite {
                height,
                frequency,
                octaves,
                effective_seed,
                noise_type,
                fractal_type,
            } => {
                let Some(map) = FastNoiseLiteHeightMapBulk::new(
                    height,
                    frequency,
                    octaves,
                    effective_seed,
                    noise_type,
                    fractal_type,
                ) else {
                    return Err(HeightMapTreeError::InvalidProgram);
                };
                noise_maps.push(NoiseMapEvaluator::FastNoiseLite(map));
                parsed.push(ParsedNode::Noise(noise_maps.len() - 1));
                depth += 1;
            }
            HeightMapNode::Mandelbrot => {
                parsed.push(ParsedNode::Mandelbrot);
                depth += 1;
            }
            HeightMapNode::Banded {
                segment1_length,
                segment1_end_height,
                segment2_length,
                segment2_end_height,
                smooth,
            } => {
                parsed.push(ParsedNode::Banded {
                    segment1_length,
                    segment1_end_height,
                    segment2_length,
                    segment2_end_height,
                    smooth,
                });
                depth += 1;
            }
            HeightMapNode::Shelving {
                shelve_height,
                shelve_strength,
            } => {
                if depth < 1 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                parsed.push(ParsedNode::Shelving {
                    shelve_height,
                    shelve_strength,
                });
            }
            HeightMapNode::NinePatch {
                inner_size,
                border_size,
                coast_size,
                height,
            } => {
                parsed.push(ParsedNode::NinePatch {
                    inner_size,
                    border_size,
                    coast_size,
                    height,
                });
                depth += 1;
            }
            HeightMapNode::Add => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Add);
            }
            HeightMapNode::Subtract => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Subtract);
            }
            HeightMapNode::Multiply => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Multiply);
            }
            HeightMapNode::Minimum => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Minimum);
            }
            HeightMapNode::Maximum => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Maximum);
            }
        }
    }
    if depth != 1 {
        return Err(HeightMapTreeError::InvalidProgram);
    }
    if noise_maps
        .len()
        .checked_mul(area)
        .ok_or(HeightMapTreeError::AreaOverflow)?
        > MAX_NOISE_VALUES
    {
        return Err(HeightMapTreeError::TooManyNoiseValues);
    }

    // A single noise node is the common case for a generated tile. Use the
    // caller's result slice as its scratch buffer instead of allocating a
    // second `area`-sized array for every tile.
    let single_noise_map = noise_maps.len() == 1;
    let mut noise_values = Vec::new();
    if single_noise_map {
        noise_maps[0].fill_bulk(origin_x, origin_y, width, height, output)?;
    } else {
        noise_values.resize(noise_maps.len() * area, 0.0_f64);
        for (index, map) in noise_maps.iter().enumerate() {
            map.fill_bulk(
                origin_x,
                origin_y,
                width,
                height,
                &mut noise_values[index * area..(index + 1) * area],
            )?;
        }
    }

    let mut stack = [0.0_f64; MAX_PROGRAM_NODES];
    for cell in 0..area {
        let mut stack_depth = 0_usize;
        for node in &parsed {
            match *node {
                ParsedNode::Constant(value) => {
                    stack[stack_depth] = value;
                    stack_depth += 1;
                }
                ParsedNode::Noise(index) => {
                    stack[stack_depth] = if single_noise_map {
                        output[cell]
                    } else {
                        noise_values[index * area + cell]
                    };
                    stack_depth += 1;
                }
                ParsedNode::Mandelbrot => {
                    let x = origin_x.wrapping_add((cell % width) as i32) as f32;
                    let y = origin_y.wrapping_add((cell / width) as i32) as f32;
                    stack[stack_depth] = mandelbrot_height(x, y);
                    stack_depth += 1;
                }
                ParsedNode::Banded {
                    segment1_length,
                    segment1_end_height,
                    segment2_length,
                    segment2_end_height,
                    smooth,
                } => {
                    let x = origin_x.wrapping_add((cell % width) as i32) as f32;
                    stack[stack_depth] = banded_height(
                        x,
                        segment1_length,
                        segment1_end_height,
                        segment2_length,
                        segment2_end_height,
                        smooth,
                    );
                    stack_depth += 1;
                }
                ParsedNode::Shelving {
                    shelve_height,
                    shelve_strength,
                } => {
                    let value = stack[stack_depth - 1];
                    stack[stack_depth - 1] = value
                        - (value * std::f64::consts::TAU / f64::from(shelve_height)).sin()
                            * f64::from(shelve_strength);
                }
                ParsedNode::NinePatch {
                    inner_size,
                    border_size,
                    coast_size,
                    height,
                } => {
                    let x = origin_x.wrapping_add((cell % width) as i32) as f32;
                    let y = origin_y.wrapping_add((cell / width) as i32) as f32;
                    stack[stack_depth] =
                        nine_patch_height(x, y, inner_size, border_size, coast_size, height);
                    stack_depth += 1;
                }
                ParsedNode::Add => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = left + right;
                }
                ParsedNode::Subtract => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = left - right;
                }
                ParsedNode::Multiply => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = if left == 0.0 { 0.0 } else { left * right };
                }
                ParsedNode::Minimum => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = java_min(left, right);
                }
                ParsedNode::Maximum => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = java_max(left, right);
                }
            }
        }
        output[cell] = stack[0];
    }
    Ok(())
}

/// Evaluates a post-order height-map expression at explicit float coordinates.
/// This is used by maps such as `DisplacementHeightMap` whose samples do not lie
/// on a regular integer grid.
pub fn fill_height_map_tree_points(
    nodes: &[HeightMapNode],
    x_coordinates: &[f32],
    y_coordinates: &[f32],
    output: &mut [f64],
) -> Result<(), HeightMapTreeError> {
    if nodes.is_empty() {
        return Err(HeightMapTreeError::InvalidProgram);
    }
    if nodes.len() > MAX_PROGRAM_NODES {
        return Err(HeightMapTreeError::TooManyNodes(nodes.len()));
    }
    let area = x_coordinates.len();
    if (y_coordinates.len() != area) || (output.len() != area) {
        return Err(HeightMapTreeError::OutputLength {
            expected: area,
            actual: output.len(),
        });
    }

    let mut depth = 0_usize;
    let mut noise_maps = Vec::new();
    let mut parsed = Vec::with_capacity(nodes.len());
    for &node in nodes {
        match node {
            HeightMapNode::Constant(value) => {
                parsed.push(ParsedNode::Constant(value));
                depth += 1;
            }
            HeightMapNode::Noise {
                d_height,
                scale,
                octaves,
                effective_seed,
            } => {
                noise_maps.push(NoiseMapEvaluator::Legacy(NoiseHeightMapBulk::new(
                    d_height,
                    scale,
                    octaves,
                    effective_seed,
                )?));
                parsed.push(ParsedNode::Noise(noise_maps.len() - 1));
                depth += 1;
            }
            HeightMapNode::FastNoiseLite {
                height,
                frequency,
                octaves,
                effective_seed,
                noise_type,
                fractal_type,
            } => {
                let Some(map) = FastNoiseLiteHeightMapBulk::new(
                    height,
                    frequency,
                    octaves,
                    effective_seed,
                    noise_type,
                    fractal_type,
                ) else {
                    return Err(HeightMapTreeError::InvalidProgram);
                };
                noise_maps.push(NoiseMapEvaluator::FastNoiseLite(map));
                parsed.push(ParsedNode::Noise(noise_maps.len() - 1));
                depth += 1;
            }
            HeightMapNode::Mandelbrot => {
                parsed.push(ParsedNode::Mandelbrot);
                depth += 1;
            }
            HeightMapNode::Banded {
                segment1_length,
                segment1_end_height,
                segment2_length,
                segment2_end_height,
                smooth,
            } => {
                parsed.push(ParsedNode::Banded {
                    segment1_length,
                    segment1_end_height,
                    segment2_length,
                    segment2_end_height,
                    smooth,
                });
                depth += 1;
            }
            HeightMapNode::Shelving {
                shelve_height,
                shelve_strength,
            } => {
                if depth < 1 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                parsed.push(ParsedNode::Shelving {
                    shelve_height,
                    shelve_strength,
                });
            }
            HeightMapNode::NinePatch {
                inner_size,
                border_size,
                coast_size,
                height,
            } => {
                parsed.push(ParsedNode::NinePatch {
                    inner_size,
                    border_size,
                    coast_size,
                    height,
                });
                depth += 1;
            }
            HeightMapNode::Add => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Add);
            }
            HeightMapNode::Subtract => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Subtract);
            }
            HeightMapNode::Multiply => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Multiply);
            }
            HeightMapNode::Minimum => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Minimum);
            }
            HeightMapNode::Maximum => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Maximum);
            }
        }
    }
    if depth != 1 {
        return Err(HeightMapTreeError::InvalidProgram);
    }
    if noise_maps
        .len()
        .checked_mul(area)
        .ok_or(HeightMapTreeError::AreaOverflow)?
        > MAX_NOISE_VALUES
    {
        return Err(HeightMapTreeError::TooManyNoiseValues);
    }

    let mut stack = [0.0_f64; MAX_PROGRAM_NODES];
    for cell in 0..area {
        let x = x_coordinates[cell];
        let y = y_coordinates[cell];
        let mut stack_depth = 0_usize;
        for node in &parsed {
            match *node {
                ParsedNode::Constant(value) => {
                    stack[stack_depth] = value;
                    stack_depth += 1;
                }
                ParsedNode::Noise(index) => {
                    stack[stack_depth] = noise_maps[index].get_value(f64::from(x), f64::from(y));
                    stack_depth += 1;
                }
                ParsedNode::Mandelbrot => {
                    stack[stack_depth] = mandelbrot_height(x, y);
                    stack_depth += 1;
                }
                ParsedNode::Banded {
                    segment1_length,
                    segment1_end_height,
                    segment2_length,
                    segment2_end_height,
                    smooth,
                } => {
                    stack[stack_depth] = banded_height(
                        x,
                        segment1_length,
                        segment1_end_height,
                        segment2_length,
                        segment2_end_height,
                        smooth,
                    );
                    stack_depth += 1;
                }
                ParsedNode::Shelving {
                    shelve_height,
                    shelve_strength,
                } => {
                    let value = stack[stack_depth - 1];
                    stack[stack_depth - 1] = value
                        - (value * std::f64::consts::TAU / f64::from(shelve_height)).sin()
                            * f64::from(shelve_strength);
                }
                ParsedNode::NinePatch {
                    inner_size,
                    border_size,
                    coast_size,
                    height,
                } => {
                    stack[stack_depth] =
                        nine_patch_height(x, y, inner_size, border_size, coast_size, height);
                    stack_depth += 1;
                }
                ParsedNode::Add => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = left + right;
                }
                ParsedNode::Subtract => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = left - right;
                }
                ParsedNode::Multiply => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = if left == 0.0 { 0.0 } else { left * right };
                }
                ParsedNode::Minimum => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = java_min(left, right);
                }
                ParsedNode::Maximum => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = java_max(left, right);
                }
            }
        }
        output[cell] = stack[0];
    }
    Ok(())
}

/// Computes WorldPainter's slope operator from a row-major base grid with a
/// one-cell halo. The output contains `(input_width - 2) * (input_height - 2)`
/// row-major values and preserves the Java operator's distinct scale==1 path.
pub fn fill_slope_samples(
    base_samples: &[f64],
    input_width: usize,
    input_height: usize,
    vertical_scaling: f32,
    output: &mut [f64],
) -> bool {
    let Some(input_area) = input_width.checked_mul(input_height) else {
        return false;
    };
    let Some(output_width) = input_width.checked_sub(2) else {
        return false;
    };
    let Some(output_height) = input_height.checked_sub(2) else {
        return false;
    };
    let Some(output_area) = output_width.checked_mul(output_height) else {
        return false;
    };
    if input_width < 3
        || input_height < 3
        || input_area != base_samples.len()
        || output_area != output.len()
    {
        return false;
    }

    let root_eight = 8.0_f64.sqrt();
    let radians_to_degrees = 180.0_f64 / std::f64::consts::PI;
    let scaled = vertical_scaling != 1.0_f32;
    let scale = vertical_scaling as f64;
    for y in 0..output_height {
        let source_row = (y + 1) * input_width;
        let north_row = source_row - input_width;
        let south_row = source_row + input_width;
        let output_row = y * output_width;
        for x in 0..output_width {
            let west = source_row + x;
            let centre = west + 1;
            let east = west + 2;
            let horizontal = if scaled {
                ((base_samples[east] / scale - base_samples[west] / scale) / 2.0).abs()
            } else {
                ((base_samples[east] / scale - base_samples[west]) / 2.0).abs()
            };
            let (diagonal1, vertical, diagonal2) = if scaled {
                (
                    ((base_samples[south_row + east - source_row] / scale
                        - base_samples[north_row + west - source_row] / scale)
                        / root_eight)
                        .abs(),
                    ((base_samples[south_row + centre - source_row] / scale
                        - base_samples[north_row + centre - source_row] / scale)
                        / 2.0)
                        .abs(),
                    ((base_samples[south_row + west - source_row] / scale
                        - base_samples[north_row + east - source_row] / scale)
                        / root_eight)
                        .abs(),
                )
            } else {
                (
                    ((base_samples[south_row + east - source_row]
                        - base_samples[north_row + west - source_row])
                        / root_eight)
                        .abs(),
                    ((base_samples[south_row + centre - source_row]
                        - base_samples[north_row + centre - source_row])
                        / 2.0)
                        .abs(),
                    ((base_samples[south_row + west - source_row]
                        - base_samples[north_row + east - source_row])
                        / root_eight)
                        .abs(),
                )
            };
            let maximum = java_max(
                java_max(horizontal, diagonal1),
                java_max(vertical, diagonal2),
            );
            output[output_row + x] = maximum.tan() * radians_to_degrees;
        }
    }
    true
}

#[derive(Clone, Copy, Debug)]
enum ParsedNode {
    Constant(f64),
    Noise(usize),
    Mandelbrot,
    Banded {
        segment1_length: i32,
        segment1_end_height: f64,
        segment2_length: i32,
        segment2_end_height: f64,
        smooth: bool,
    },
    Shelving {
        shelve_height: i32,
        shelve_strength: i32,
    },
    NinePatch {
        inner_size: i32,
        border_size: i32,
        coast_size: i32,
        height: f64,
    },
    Add,
    Subtract,
    Multiply,
    Minimum,
    Maximum,
}

fn mandelbrot_height(x0: f32, y0: f32) -> f64 {
    let (mut x, mut y) = (0.0_f32, 0.0_f32);
    let mut iteration = 0;
    while x * x + y * y < 4.0 && iteration < 255 {
        let x_temp = x * x - y * y + x0;
        y = 2.0 * x * y + y0;
        x = x_temp;
        iteration += 1;
    }
    f64::from(iteration)
}

fn nine_patch_height(
    x: f32,
    y: f32,
    inner_size: i32,
    border_size: i32,
    coast_size: i32,
    height: f64,
) -> f64 {
    let x = x.abs();
    let y = y.abs();
    let border_total_i32 = inner_size.wrapping_add(border_size);
    let coast_total_i32 = border_total_i32.wrapping_add(coast_size);
    let border_total = border_total_i32 as f32;
    let coast_total = coast_total_i32 as f32;
    let inner = inner_size as f32;
    let border = border_size as f32;
    let coast = coast_size as f32;
    let half_height = height / 2.0;
    if x < inner {
        if y < border_total {
            height
        } else if y < coast_total {
            nine_patch_coast(y - border_total, coast, half_height)
        } else {
            0.0
        }
    } else if x < border_total {
        if y < inner {
            height
        } else if y < coast_total {
            nine_patch_corner(x, y, inner, border, coast, half_height, height)
        } else {
            0.0
        }
    } else if x < coast_total {
        if y < inner {
            nine_patch_coast(x - border_total, coast, half_height)
        } else if y < coast_total {
            nine_patch_corner(x, y, inner, border, coast, half_height, height)
        } else {
            0.0
        }
    } else {
        0.0
    }
}

fn nine_patch_corner(
    x: f32,
    y: f32,
    inner: f32,
    border: f32,
    coast: f32,
    half_height: f64,
    height: f64,
) -> f64 {
    let dx = x - inner;
    let dy = y - inner;
    let distance = (f64::from(dx * dx + dy * dy).sqrt()) as f32;
    if distance < border {
        height
    } else if distance - border < coast {
        nine_patch_coast(distance - border, coast, half_height)
    } else {
        0.0
    }
}

fn nine_patch_coast(distance: f32, coast: f32, half_height: f64) -> f64 {
    (f64::from(distance / coast) * std::f64::consts::PI).cos() * half_height + half_height
}

fn banded_height(
    x: f32,
    segment1_length: i32,
    segment1_end_height: f64,
    segment2_length: i32,
    segment2_end_height: f64,
    smooth: bool,
) -> f64 {
    let total_length = segment1_length.wrapping_add(segment2_length) as f32;
    let d = java_mod_f32(x, total_length);
    let segment1_end_delta = segment1_end_height - segment2_end_height;
    let segment2_end_delta = segment2_end_height - segment1_end_height;
    if d < segment1_length as f32 {
        if smooth {
            segment2_end_height
                + (0.5 - ((d as f64 * std::f64::consts::PI) / segment1_length as f64).cos() / 2.0)
                    * segment1_end_delta
        } else {
            segment2_end_height + (d / segment1_length as f32) as f64 * segment1_end_delta
        }
    } else if smooth {
        let phase =
            (d - segment1_length as f32) as f64 * std::f64::consts::PI / segment2_length as f64;
        segment1_end_height + (0.5 - phase.cos() / 2.0) * segment2_end_delta
    } else {
        segment1_end_height
            + ((d - segment1_length as f32) / segment2_length as f32) as f64 * segment2_end_delta
    }
}

fn java_mod_f32(value: f32, modulus: f32) -> f32 {
    if value < 0.0 {
        let quotient = ((-value / modulus) as f64).ceil() as f32;
        value + quotient * modulus
    } else if value >= modulus {
        let quotient = ((value / modulus) as f64).floor() as f32;
        value - quotient * modulus
    } else {
        value
    }
}

fn java_min(left: f64, right: f64) -> f64 {
    if left.is_nan() {
        left
    } else if right.is_nan() {
        right
    } else if left == 0.0 && right == 0.0 {
        f64::from_bits(left.to_bits() | right.to_bits())
    } else if left <= right {
        left
    } else {
        right
    }
}

fn java_max(left: f64, right: f64) -> f64 {
    if left.is_nan() {
        left
    } else if right.is_nan() {
        right
    } else if left == 0.0 && right == 0.0 {
        f64::from_bits(left.to_bits() & right.to_bits())
    } else if left >= right {
        left
    } else {
        right
    }
}

#[cfg(test)]
mod tests {
    use super::{
        fill_height_map_tree, fill_height_map_tree_points, fill_slope_samples, HeightMapNode,
        HeightMapTreeError,
    };
    use crate::noise_height_map::NoiseHeightMapBulk;

    #[test]
    fn explicit_integer_points_match_regular_grid_evaluation() {
        let nodes = [
            HeightMapNode::Constant(13.25),
            HeightMapNode::Noise {
                d_height: 72.0,
                scale: 1.125,
                octaves: 4,
                effective_seed: -0x1020_3040,
            },
            HeightMapNode::Add,
            HeightMapNode::Mandelbrot,
            HeightMapNode::Add,
        ];
        let (origin_x, origin_y, width, height) = (-19_i32, 37_i32, 5_usize, 4_usize);
        let mut x_coordinates = Vec::with_capacity(width * height);
        let mut y_coordinates = Vec::with_capacity(width * height);
        for y in 0..height {
            for x in 0..width {
                x_coordinates.push((origin_x + x as i32) as f32);
                y_coordinates.push((origin_y + y as i32) as f32);
            }
        }
        let mut actual = vec![0.0; width * height];
        let mut expected = vec![0.0; width * height];

        fill_height_map_tree_points(&nodes, &x_coordinates, &y_coordinates, &mut actual).unwrap();
        fill_height_map_tree(&nodes, origin_x, origin_y, width, height, &mut expected).unwrap();

        assert_eq!(actual, expected);
    }

    #[test]
    fn explicit_fractional_points_preserve_float_coordinate_precision() {
        let nodes = [
            HeightMapNode::Constant(-7.5),
            HeightMapNode::Noise {
                d_height: 128.0,
                scale: 0.75,
                octaves: 3,
                effective_seed: 0x5566_7788,
            },
            HeightMapNode::Add,
        ];
        let x_coordinates = [-12.375_f32, 0.125, 98.75];
        let y_coordinates = [7.5_f32, -31.25, 14.875];
        let mut actual = [0.0; 3];
        let noise = NoiseHeightMapBulk::new(128.0, 0.75, 3, 0x5566_7788).unwrap();

        fill_height_map_tree_points(&nodes, &x_coordinates, &y_coordinates, &mut actual).unwrap();

        for index in 0..actual.len() {
            let expected = -7.5
                + noise.get_value(
                    f64::from(x_coordinates[index]),
                    f64::from(y_coordinates[index]),
                );
            assert_eq!(actual[index].to_bits(), expected.to_bits());
        }
    }

    #[test]
    fn nested_sum_preserves_java_tree_order_bit_for_bit() {
        let nodes = [
            HeightMapNode::Constant(0.1),
            HeightMapNode::Noise {
                d_height: 128.0,
                scale: 1.25,
                octaves: 3,
                effective_seed: -42,
            },
            HeightMapNode::Add,
            HeightMapNode::Constant(-12.5),
            HeightMapNode::Add,
        ];
        let first = NoiseHeightMapBulk::new(128.0, 1.25, 3, -42).unwrap();
        let (origin_x, origin_y, width, height) = (-19, 23, 9, 7);
        let mut noise = vec![0.0; width * height];
        first
            .fill_bulk(origin_x, origin_y, width, height, &mut noise)
            .unwrap();
        let mut actual = vec![f64::NAN; width * height];
        fill_height_map_tree(&nodes, origin_x, origin_y, width, height, &mut actual).unwrap();
        for index in 0..actual.len() {
            let java_order = (0.1_f64 + noise[index]) + -12.5_f64;
            assert_eq!(actual[index].to_bits(), java_order.to_bits());
        }
    }

    #[test]
    fn single_noise_node_matches_bulk_samples_bit_for_bit() {
        let node = HeightMapNode::Noise {
            d_height: 38.0,
            scale: 0.8,
            octaves: 3,
            effective_seed: -0x1020_3040,
        };
        let (origin_x, origin_y, width, height) = (-37, 19, 13, 9);
        let mut expected = vec![0.0; width * height];
        let mut actual = vec![0.0; width * height];
        NoiseHeightMapBulk::new(38.0, 0.8, 3, -0x1020_3040)
            .unwrap()
            .fill_bulk(origin_x, origin_y, width, height, &mut expected)
            .unwrap();

        fill_height_map_tree(&[node], origin_x, origin_y, width, height, &mut actual).unwrap();

        assert_eq!(actual, expected);
    }

    #[test]
    fn fast_noise_lite_grid_and_explicit_points_match_bit_for_bit() {
        let nodes = [HeightMapNode::FastNoiseLite {
            height: 384.0,
            frequency: 1.0 / (65.537_f64 * 2.25),
            octaves: 5,
            effective_seed: 0x1234_5678_9abc_def0,
            noise_type: 0,
            fractal_type: 1,
        }];
        let (origin_x, origin_y, width, height) = (-31, 47, 19, 11);
        let mut grid = vec![f64::NAN; width * height];
        fill_height_map_tree(&nodes, origin_x, origin_y, width, height, &mut grid).unwrap();
        let mut xs = Vec::with_capacity(width * height);
        let mut ys = Vec::with_capacity(width * height);
        for row in 0..height {
            for col in 0..width {
                xs.push(origin_x.wrapping_add(col as i32) as f32);
                ys.push(origin_y.wrapping_add(row as i32) as f32);
            }
        }
        let mut points = vec![f64::NAN; width * height];
        fill_height_map_tree_points(&nodes, &xs, &ys, &mut points).unwrap();
        for (index, (&grid_value, &point_value)) in grid.iter().zip(&points).enumerate() {
            assert_eq!(
                grid_value.to_bits(),
                point_value.to_bits(),
                "sample {index}"
            );
        }
    }

    #[test]
    fn rejects_malformed_programs_and_intermediate_overflow() {
        let mut output = [];
        assert_eq!(
            fill_height_map_tree(&[HeightMapNode::Add], 0, 0, 0, 0, &mut output),
            Err(HeightMapTreeError::InvalidProgram)
        );
        let noise = HeightMapNode::Noise {
            d_height: 1.0,
            scale: 1.0,
            octaves: 1,
            effective_seed: 0,
        };
        let nodes = [noise, noise, HeightMapNode::Add];
        let mut output = vec![0.0; 1024 * 1024];
        assert_eq!(
            fill_height_map_tree(&nodes, 0, 0, 1024, 1024, &mut output),
            Err(HeightMapTreeError::TooManyNoiseValues)
        );
    }

    #[test]
    fn difference_and_product_preserve_java_order_and_zero_short_circuit() {
        let first = NoiseHeightMapBulk::new(128.0, 1.25, 3, -42).unwrap();
        let second = NoiseHeightMapBulk::new(64.0, 0.75, 2, 91).unwrap();
        let (origin_x, origin_y, width, height) = (-12, 39, 11, 7);
        let mut noise_a = vec![0.0; width * height];
        let mut noise_b = vec![0.0; width * height];
        first
            .fill_bulk(origin_x, origin_y, width, height, &mut noise_a)
            .unwrap();
        second
            .fill_bulk(origin_x, origin_y, width, height, &mut noise_b)
            .unwrap();
        let nodes = [
            HeightMapNode::Constant(0.1),
            HeightMapNode::Noise {
                d_height: 128.0,
                scale: 1.25,
                octaves: 3,
                effective_seed: -42,
            },
            HeightMapNode::Add,
            HeightMapNode::Noise {
                d_height: 64.0,
                scale: 0.75,
                octaves: 2,
                effective_seed: 91,
            },
            HeightMapNode::Multiply,
            HeightMapNode::Constant(12.5),
            HeightMapNode::Subtract,
        ];
        let mut actual = vec![f64::NAN; width * height];
        fill_height_map_tree(&nodes, origin_x, origin_y, width, height, &mut actual).unwrap();
        for index in 0..actual.len() {
            let java_order = (0.1_f64 + noise_a[index]) * noise_b[index] - 12.5_f64;
            assert_eq!(actual[index].to_bits(), java_order.to_bits());
        }

        let zero_nodes = [
            HeightMapNode::Constant(-0.0),
            HeightMapNode::Noise {
                d_height: 64.0,
                scale: 0.75,
                octaves: 2,
                effective_seed: 91,
            },
            HeightMapNode::Multiply,
        ];
        fill_height_map_tree(&zero_nodes, origin_x, origin_y, width, height, &mut actual).unwrap();
        assert!(actual
            .iter()
            .all(|value| value.to_bits() == 0.0_f64.to_bits()));
    }

    #[test]
    fn mandelbrot_iteration_limits_match_worldpainter_coordinates() {
        let nodes = [HeightMapNode::Mandelbrot];
        let mut output = [0.0];
        fill_height_map_tree(&nodes, 0, 0, 1, 1, &mut output).unwrap();
        assert_eq!(output[0], 255.0);
        fill_height_map_tree(&nodes, 2, 0, 1, 1, &mut output).unwrap();
        assert_eq!(output[0], 1.0);
    }

    #[test]
    fn minimum_and_maximum_match_java_nan_payload_and_signed_zero() {
        let nan_a = f64::from_bits(0x7ff8_0000_0000_0001);
        let nan_b = f64::from_bits(0x7ff8_0000_0000_0002);
        for (left, right, min, max) in [
            (-0.0, 0.0, -0.0, 0.0),
            (0.0, -0.0, -0.0, 0.0),
            (nan_a, nan_b, nan_a, nan_a),
            (3.0, nan_b, nan_b, nan_b),
            (nan_a, 3.0, nan_a, nan_a),
        ] {
            let min_nodes = [
                HeightMapNode::Constant(left),
                HeightMapNode::Constant(right),
                HeightMapNode::Minimum,
            ];
            let max_nodes = [
                HeightMapNode::Constant(left),
                HeightMapNode::Constant(right),
                HeightMapNode::Maximum,
            ];
            let mut minimum = [f64::NAN];
            let mut maximum = [f64::NAN];
            fill_height_map_tree(&min_nodes, 0, 0, 1, 1, &mut minimum).unwrap();
            fill_height_map_tree(&max_nodes, 0, 0, 1, 1, &mut maximum).unwrap();
            assert_eq!(minimum[0].to_bits(), min.to_bits());
            assert_eq!(maximum[0].to_bits(), max.to_bits());
        }
    }

    #[test]
    fn slope_samples_match_the_java_scale_one_and_scaled_formulas() {
        let base = [
            0.0, 1.0, 2.0, 3.0, // north
            0.0, 1.0, 2.0, 3.0, // centre
            0.0, 1.0, 2.0, 3.0, // south
        ];
        let mut output = [0.0; 2];
        for (scale, maximum) in [(1.0_f32, 1.0_f64), (2.0_f32, 0.5_f64)] {
            assert!(fill_slope_samples(&base, 4, 3, scale, &mut output));
            let expected = maximum.tan() * (180.0 / std::f64::consts::PI);
            assert!(output
                .iter()
                .all(|value| value.to_bits() == expected.to_bits()));
        }
    }

    #[test]
    fn slope_samples_propagate_nan_and_reject_malformed_buffers() {
        let base = [f64::NAN; 9];
        let mut output = [0.0];
        assert!(fill_slope_samples(&base, 3, 3, 1.0, &mut output));
        assert!(output[0].is_nan());
        assert!(!fill_slope_samples(&base, 3, 3, 1.0, &mut []));
        assert!(!fill_slope_samples(&base, 2, 3, 1.0, &mut output));
    }
}
