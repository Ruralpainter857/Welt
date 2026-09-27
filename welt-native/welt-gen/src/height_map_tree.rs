//! Bulk evaluator for pure constant/noise/sum height-map expression trees.

use crate::noise_height_map::{NoiseHeightMapBulk, NoiseHeightMapError};

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
    Add,
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
                noise_maps.push(NoiseHeightMapBulk::new(
                    d_height,
                    scale,
                    octaves,
                    effective_seed,
                )?);
                parsed.push(ParsedNode::Noise(noise_maps.len() - 1));
                depth += 1;
            }
            HeightMapNode::Add => {
                if depth < 2 {
                    return Err(HeightMapTreeError::InvalidProgram);
                }
                depth -= 1;
                parsed.push(ParsedNode::Add);
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

    let mut noise_values = vec![0.0_f64; noise_maps.len() * area];
    for (index, map) in noise_maps.iter().enumerate() {
        map.fill_bulk(
            origin_x,
            origin_y,
            width,
            height,
            &mut noise_values[index * area..(index + 1) * area],
        )?;
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
                    stack[stack_depth] = noise_values[index * area + cell];
                    stack_depth += 1;
                }
                ParsedNode::Add => {
                    let right = stack[stack_depth - 1];
                    let left = stack[stack_depth - 2];
                    stack_depth -= 1;
                    stack[stack_depth - 1] = left + right;
                }
            }
        }
        output[cell] = stack[0];
    }
    Ok(())
}

#[derive(Clone, Copy, Debug)]
enum ParsedNode {
    Constant(f64),
    Noise(usize),
    Add,
}

#[cfg(test)]
mod tests {
    use super::{fill_height_map_tree, HeightMapNode, HeightMapTreeError};
    use crate::noise_height_map::NoiseHeightMapBulk;

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
}
