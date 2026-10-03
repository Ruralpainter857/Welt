//! WTGP v1: bounded procedural source for the complete packed factory transaction.
use crate::height_map_affine::fill_affine_height_map_tree;
use crate::height_map_displacement::fill_displacement_height_map_tree;
use crate::height_map_slope::fill_slope_height_map_tree;
use crate::height_map_tree::{fill_height_map_tree, HeightMapNode};
use welt_core::error::WeltError;
pub fn unpack_fast_noise_lite_settings(packed: i32) -> (i32, i32, i32) {
    if packed & 0x4000_0000 == 0 {
        (packed, 0, 1)
    } else {
        (packed & 0xff, (packed >> 8) & 0xff, (packed >> 16) & 0xff)
    }
}

pub fn decode_height_map_node(
    opcode: i32,
    value: f64,
    scale: f64,
    octaves: i32,
    seed: i64,
) -> Option<HeightMapNode> {
    Some(match opcode {
        0 => HeightMapNode::Constant(value),
        1 => HeightMapNode::Noise {
            d_height: value,
            scale,
            octaves,
            effective_seed: seed,
        },
        13 => {
            let (octaves, noise_type, fractal_type) = unpack_fast_noise_lite_settings(octaves);
            HeightMapNode::FastNoiseLite {
                height: value,
                frequency: scale,
                octaves,
                effective_seed: seed,
                noise_type,
                fractal_type,
            }
        }
        8 => HeightMapNode::Mandelbrot,
        9 | 10 => HeightMapNode::Banded {
            segment1_length: octaves,
            segment1_end_height: value,
            segment2_length: seed as i32,
            segment2_end_height: scale,
            smooth: opcode == 10,
        },
        11 => HeightMapNode::Shelving {
            shelve_height: octaves,
            shelve_strength: seed as i32,
        },
        12 => HeightMapNode::NinePatch {
            inner_size: octaves,
            border_size: seed as i32,
            coast_size: scale as i32,
            height: value,
        },
        2 => HeightMapNode::Add,
        3 => HeightMapNode::Subtract,
        4 => HeightMapNode::Multiply,
        5 => HeightMapNode::Minimum,
        6 => HeightMapNode::Maximum,
        _ => return None,
    })
}

fn word(data: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(data[p..p + 4].try_into().unwrap())
}
fn number(data: &[u8], p: usize) -> f64 {
    f64::from_le_bytes(data[p..p + 8].try_into().unwrap())
}
fn seed(data: &[u8], p: usize) -> i64 {
    i64::from_le_bytes(data[p..p + 8].try_into().unwrap())
}
/// Evaluates all intermediate planes in reusable Rust worker storage, returning only final samples.
pub(crate) fn fill_source(data: &[u8], base: usize, output: &mut [f64]) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if base > data.len()
        || data.len() - base < 128
        || word(data, base) != 0x50475457
        || word(data, base + 4) != 1
    {
        return Err(bad);
    }
    let count = word(data, base + 12) as usize;
    let mode = word(data, base + 16);
    if !(1..=64).contains(&count)
        || !(0..=3).contains(&mode)
        || word(data, base + 8) as usize != 128 + count * 32
        || data.len() - base != 128 + count * 32
        || word(data, base + 28) != 128
        || word(data, base + 32) != 128
        || word(data, base + 36) != 0
        || data[base + 52..base + 64]
            .iter()
            .chain(data[base + 112..base + 128].iter())
            .any(|v| *v != 0)
    {
        return Err(bad);
    }
    let x = word(data, base + 20);
    let y = word(data, base + 24);
    let margin = i64::from(mode == 2);
    for origin in [x, y] {
        if i64::from(origin) - margin < -16777216 || i64::from(origin) + 127 + margin > 16777216 {
            return Err(bad);
        }
    }
    let first = word(data, base + 40) as usize;
    let second = word(data, base + 44) as usize;
    if mode != 3 && (first != 0 || second != 0) {
        return Err(bad);
    }
    let mut nodes = [HeightMapNode::Constant(0.0); 64];
    for (i, node) in nodes.iter_mut().enumerate().take(count) {
        let p = base + 128 + i * 32;
        *node = decode_height_map_node(
            word(data, p),
            number(data, p + 8),
            number(data, p + 16),
            word(data, p + 4),
            seed(data, p + 24),
        )
        .ok_or(bad)?;
    }
    let nodes = &nodes[..count];
    let result = match mode {
        0 => fill_height_map_tree(nodes, x, y, 128, 128, output),
        1 => {
            let matrix = std::array::from_fn(|i| number(data, base + 64 + i * 8));
            fill_affine_height_map_tree(nodes, x, y, 128, 128, 0, &matrix, output)
        }
        2 => fill_slope_height_map_tree(
            nodes,
            x,
            y,
            128,
            128,
            0,
            f32::from_bits(word(data, base + 48) as u32),
            output,
        ),
        3 => fill_displacement_height_map_tree(nodes, first, second, x, y, 128, 128, 0, output),
        _ => unreachable!(),
    };
    result.map_err(|_| bad)
}
