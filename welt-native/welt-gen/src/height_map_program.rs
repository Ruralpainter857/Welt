//! WTGP v1/v2: bounded procedural source for the complete packed factory transaction.
use crate::height_map_affine::fill_affine_height_map_tree;
use crate::height_map_displacement::{
    fill_affine_displacement_height_map_tree, fill_displacement_height_map_tree,
};
use crate::height_map_slope::fill_slope_height_map_tree;
use crate::height_map_tree::{fill_height_map_tree, HeightMapNode};
use welt_core::error::WeltError;
thread_local! { static COMPOSITION_SCRATCH: std::cell::RefCell<Vec<f64>> = const { std::cell::RefCell::new(Vec::new()) }; }
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
        || !(1..=2).contains(&word(data, base + 4))
    {
        return Err(bad);
    }
    let version = word(data, base + 4);
    let composition = word(data, base + 60);
    let count = word(data, base + 12) as usize;
    let mode = word(data, base + 16);
    let width = word(data, base + 28) as usize;
    let height = word(data, base + 32) as usize;
    if !(1..=64).contains(&count)
        || !(0..=5).contains(&mode)
        || word(data, base + 8) as usize != 128 + count * 32
        || data.len() - base != 128 + count * 32
        || !(1..=256).contains(&width)
        || !(1..=256).contains(&height)
        || width.checked_mul(height) != Some(output.len())
        || word(data, base + 36) != 0
        || (version == 2 && mode != 4)
        || !(0..=4).contains(&composition)
        || ((version == 1 || mode != 4) && composition != 0)
        || data[base + 112..base + 128].iter().any(|v| *v != 0)
    {
        return Err(bad);
    }
    let x = word(data, base + 20);
    let y = word(data, base + 24);
    let margin = i64::from(mode == 2);
    for (origin, extent) in [(x, width), (y, height)] {
        if i64::from(origin) - margin < -16777216
            || i64::from(origin) + extent as i64 - 1 + margin > 16777216
        {
            return Err(bad);
        }
    }
    let first = word(data, base + 40) as usize;
    let second = word(data, base + 44) as usize;
    let third = word(data, base + 52) as usize;
    let order = word(data, base + 56);
    if mode != 4 && (third != 0 || order != 0) {
        return Err(bad);
    }
    if mode != 3 && mode != 4 && mode != 5 && (first != 0 || second != 0) {
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
        0 => fill_height_map_tree(nodes, x, y, width, height, output),
        1 => {
            let matrix = std::array::from_fn(|i| number(data, base + 64 + i * 8));
            fill_affine_height_map_tree(nodes, x, y, width, height, 0, &matrix, output)
        }
        2 => fill_slope_height_map_tree(
            nodes,
            x,
            y,
            width,
            height,
            0,
            f32::from_bits(word(data, base + 48) as u32),
            output,
        ),
        3 => {
            fill_displacement_height_map_tree(nodes, first, second, x, y, width, height, 0, output)
        }
        4 => {
            let split = first
                .checked_add(second)
                .and_then(|v| v.checked_add(third))
                .ok_or(bad)?;
            if first == 0
                || second == 0
                || third == 0
                || split >= count
                || !(0..=1).contains(&order)
            {
                return Err(bad);
            }
            COMPOSITION_SCRATCH.with(|cell| {
                let mut displaced = cell.borrow_mut();
                displaced.resize(output.len(), 0.0);
                fill_displacement_height_map_tree(
                    &nodes[..split],
                    first,
                    second,
                    x,
                    y,
                    width,
                    height,
                    0,
                    &mut displaced,
                )?;
                fill_height_map_tree(&nodes[split..], x, y, width, height, output)?;
                for (other, displaced) in output.iter_mut().zip(displaced.iter()) {
                    let (left, right) = if order == 1 {
                        (*displaced, *other)
                    } else {
                        (*other, *displaced)
                    };
                    *other = combine(left, right, composition);
                }
                Ok(())
            })
        }
        5 => {
            let matrix = std::array::from_fn(|i| number(data, base + 64 + i * 8));
            fill_affine_displacement_height_map_tree(
                nodes, first, second, x, y, width, height, &matrix, output,
            )
        }
        _ => unreachable!(),
    };
    result.map_err(|_| bad)
}

/// Java arithmetic order and Math.min/max rules, including NaN payloads and signed zero.
fn combine(left: f64, right: f64, operation: i32) -> f64 {
    match operation {
        2 => left + right,
        3 => left - right,
        4 => left * right,
        0 | 1 => {
            if left.is_nan() {
                left
            } else if right.is_nan() {
                right
            } else if left == 0.0 && right == 0.0 {
                if left.is_sign_negative() == (operation == 1) {
                    left
                } else {
                    right
                }
            } else if (operation == 0 && left >= right) || (operation == 1 && left <= right) {
                left
            } else {
                right
            }
        }
        _ => unreachable!(),
    }
}
#[cfg(test)]
mod composition_tests {
    use super::*;
    #[test]
    fn extrema_preserve_signed_zero_and_nan_operand_order() {
        let nan = f64::from_bits(0x7ff8000000000051);
        assert_eq!(combine(-0.0, 0.0, 0).to_bits(), 0.0f64.to_bits());
        assert_eq!(combine(0.0, -0.0, 1).to_bits(), (-0.0f64).to_bits());
        for op in [0, 1] {
            assert_eq!(combine(nan, 1.0, op).to_bits(), nan.to_bits());
            assert_eq!(combine(1.0, nan, op).to_bits(), nan.to_bits());
        }
    }
    fn packet(operation: i32, order: i32) -> Vec<u8> {
        let mut data = vec![0u8; 256];
        for (offset, value) in [
            (0, 0x50475457),
            (4, 2),
            (8, 256),
            (12, 4),
            (16, 4),
            (28, 1),
            (32, 1),
            (40, 1),
            (44, 1),
            (52, 1),
            (56, order),
            (60, operation),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for (i, value) in [0.0f64, 0.0, 7.0, 3.0].into_iter().enumerate() {
            let p = 128 + i * 32 + 8;
            data[p..p + 8].copy_from_slice(&value.to_le_bytes());
        }
        data
    }
    #[test]
    fn complete_packets_apply_the_operator_and_validate_version_boundaries() {
        for (operation, expected) in [(0, 7.0), (1, 3.0), (2, 10.0), (3, 4.0), (4, 21.0)] {
            let mut output = [-99.0];
            fill_source(&packet(operation, 1), 0, &mut output).unwrap();
            assert_eq!(output, [expected]);
        }
        let mut output = [-99.0];
        fill_source(&packet(3, 0), 0, &mut output).unwrap();
        assert_eq!(output, [-4.0]);
        for (version, operation, mode) in [(1_i32, 2, 4_i32), (2, 5, 4), (2, 2, 0)] {
            let mut data = packet(operation, 1);
            data[4..8].copy_from_slice(&version.to_le_bytes());
            data[16..20].copy_from_slice(&mode.to_le_bytes());
            let mut output = [-99.0];
            assert!(fill_source(&data, 0, &mut output).is_err());
            assert_eq!(output, [-99.0]);
        }
    }
    #[test]
    fn arithmetic_keeps_left_and_right_distinct() {
        assert_eq!(combine(7.0, 3.0, 2), 10.0);
        assert_eq!(combine(7.0, 3.0, 3), 4.0);
        assert_eq!(combine(3.0, 7.0, 3), -4.0);
        assert_eq!(combine(7.0, 3.0, 4), 21.0);
    }
}
