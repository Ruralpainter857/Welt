//! Compact immutable object snapshots. Index zero represents an absent block.
pub fn pack(indices: &[i32], palette: usize, output: &mut [u8]) -> bool {
    if indices.is_empty() || indices.len() > 16 * 1024 * 1024 || !(1..=65536).contains(&palette) {
        return false;
    }
    let stride = if palette <= 256 { 1 } else { 2 };
    if output.len() != indices.len() * stride
        || indices.iter().any(|&i| i < 0 || i as usize >= palette)
    {
        return false;
    }
    for (&index, cell) in indices.iter().zip(output.chunks_exact_mut(stride)) {
        cell[0] = index as u8;
        if stride == 2 {
            cell[1] = (index >> 8) as u8;
        }
    }
    true
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn byte_palette() {
        let mut out = [9; 4];
        assert!(pack(&[0, 1, 255, 2], 256, &mut out));
        assert_eq!(out, [0, 1, 255, 2]);
    }
    #[test]
    fn wide_palette() {
        let mut out = [0; 6];
        assert!(pack(&[0, 256, 65535], 65536, &mut out));
        assert_eq!(out, [0, 0, 0, 1, 255, 255]);
    }
    #[test]
    fn invalid_input_does_not_mutate() {
        for indices in [&[-1, 1][..], &[0, 3][..]] {
            let mut out = [7; 2];
            assert!(!pack(indices, 3, &mut out));
            assert_eq!(out, [7; 2]);
        }
        assert!(!pack(&[], 1, &mut []));
        assert!(!pack(&[0], 65537, &mut [0]));
        assert!(!pack(&[0], 257, &mut [0]));
    }
}

/// Remap existing palette indices directly, without a material-reference plane.
pub fn remap(
    packed: Option<(&[u8], usize)>,
    integers: Option<&[i32]>,
    mapping: &[i32],
    palette: usize,
    output: &mut [u8],
) -> bool {
    if !(1..=65536).contains(&palette) || mapping.is_empty() || mapping.len() > 65536 {
        return false;
    }
    let stride = if palette <= 256 { 1 } else { 2 };
    let count = output.len() / stride;
    if count == 0
        || count > 16 * 1024 * 1024
        || output.len() != count * stride
        || packed.is_some() == integers.is_some()
    {
        return false;
    }
    if let Some((bytes, width)) = packed {
        if !(1..=2).contains(&width) || bytes.len() != count * width {
            return false;
        }
    }
    if let Some(values) = integers {
        if values.len() != count {
            return false;
        }
    }
    let index = |i: usize| -> i32 {
        if let Some((bytes, width)) = packed {
            let p = i * width;
            i32::from(bytes[p])
                | if width == 2 {
                    i32::from(bytes[p + 1]) << 8
                } else {
                    0
                }
        } else {
            integers.unwrap()[i]
        }
    };
    for i in 0..count {
        let id = index(i);
        if id < 0
            || id as usize >= mapping.len()
            || mapping[id as usize] < 0
            || mapping[id as usize] as usize >= palette
        {
            return false;
        }
    }
    for (i, cell) in output.chunks_exact_mut(stride).enumerate() {
        let id = mapping[index(i) as usize];
        cell[0] = id as u8;
        if stride == 2 {
            cell[1] = (id >> 8) as u8;
        }
    }
    true
}
#[cfg(test)]
mod remap_tests {
    use super::*;
    #[test]
    fn remaps_both_source_representations() {
        let mut out = [7; 3];
        assert!(remap(None, Some(&[2, 0, 1]), &[1, 0, 2], 3, &mut out));
        assert_eq!(out, [2, 1, 0]);
        assert!(remap(
            Some((&[2, 0, 0, 0, 1, 0], 2)),
            None,
            &[1, 0, 2],
            3,
            &mut out
        ));
        assert_eq!(out, [2, 1, 0]);
    }
    #[test]
    fn rejects_invalid_indices_before_writing() {
        let mut out = [7; 2];
        assert!(!remap(None, Some(&[0, -1]), &[0], 1, &mut out));
        assert_eq!(out, [7; 2]);
        assert!(!remap(Some((&[0], 2)), None, &[0], 1, &mut out));
    }
}

/// Compose horizontal transforms before scanning the immutable index plane once.
/// Operations are source-to-output: 0..3 clockwise quarter turns, 4 X mirror, 5 Y mirror.
pub fn transform(
    source: &[u8],
    width: usize,
    length: usize,
    height: usize,
    stride: usize,
    operations: &[i32],
    output: &mut [u8],
) -> bool {
    if width == 0
        || length == 0
        || height == 0
        || !(1..=2).contains(&stride)
        || operations.len() > 64
    {
        return false;
    }
    let Some(count) = width
        .checked_mul(length)
        .and_then(|n| n.checked_mul(height))
    else {
        return false;
    };
    if count > 16 * 1024 * 1024 || source.len() != count * stride || output.len() != source.len() {
        return false;
    }
    visit_transformed(width, length, height, operations, |target, input| {
        let target = target * stride;
        let input = input * stride;
        output[target..target + stride].copy_from_slice(&source[input..input + stride]);
    })
}

fn visit_transformed(
    width: usize,
    length: usize,
    height: usize,
    operations: &[i32],
    mut visit: impl FnMut(usize, usize),
) -> bool {
    if width == 0 || length == 0 || height == 0 || operations.len() > 64 {
        return false;
    }
    if width
        .checked_mul(length)
        .and_then(|n| n.checked_mul(height))
        .is_none_or(|n| n > 16 * 1024 * 1024)
    {
        return false;
    }
    let (mut w, mut h) = (width as i64, length as i64);
    let (mut a, mut b, mut c, mut d, mut tx, mut ty) = (1i64, 0i64, 0i64, 1i64, 0i64, 0i64);
    for &op in operations {
        let next = match op {
            0 => (a, b, c, d, tx, ty),
            1 => (-b, a, -d, c, tx + b * (h - 1), ty + d * (h - 1)),
            2 => (
                -a,
                -b,
                -c,
                -d,
                tx + a * (w - 1) + b * (h - 1),
                ty + c * (w - 1) + d * (h - 1),
            ),
            3 => (b, -a, d, -c, tx + a * (w - 1), ty + c * (w - 1)),
            4 => (-a, b, -c, d, tx + a * (w - 1), ty + c * (w - 1)),
            5 => (a, -b, c, -d, tx + b * (h - 1), ty + d * (h - 1)),
            _ => return false,
        };
        (a, b, c, d, tx, ty) = next;
        if op == 1 || op == 3 {
            std::mem::swap(&mut w, &mut h);
        }
    }
    let (out_w, out_h) = (w as usize, h as usize);
    for z in 0..height {
        for y in 0..out_h {
            for x in 0..out_w {
                let sx = (a * x as i64 + b * y as i64 + tx) as usize;
                let sy = (c * x as i64 + d * y as i64 + ty) as usize;
                let input = sx + sy * width + z * width * length;
                let target = x + y * out_w + z * out_w * out_h;
                visit(target, input);
            }
        }
    }
    true
}
#[cfg(test)]
mod transform_tests {
    use super::*;
    #[test]
    fn rectangular_rotation_and_mirrors() {
        let source = [0, 1, 2, 3, 4, 5];
        let mut out = [9; 6];
        assert!(transform(&source, 3, 2, 1, 1, &[1], &mut out));
        assert_eq!(out, [3, 0, 4, 1, 5, 2]);
        assert!(transform(&source, 3, 2, 1, 1, &[4], &mut out));
        assert_eq!(out, [2, 1, 0, 5, 4, 3]);
        assert!(transform(&source, 3, 2, 1, 1, &[5], &mut out));
        assert_eq!(out, [3, 4, 5, 0, 1, 2]);
    }
    #[test]
    fn composition_and_wide_indices() {
        let source = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
        let mut out = [0; 12];
        assert!(transform(&source, 3, 2, 1, 2, &[1, 1, 1, 1], &mut out));
        assert_eq!(out, source);
        assert!(transform(&source, 3, 2, 1, 2, &[3, 5, 1], &mut out));
        assert_eq!(out, [5, 6, 3, 4, 1, 2, 11, 12, 9, 10, 7, 8]);
    }
    #[test]
    fn invalid_transaction_preserves_output() {
        let mut out = [7; 6];
        assert!(!transform(&[0; 6], 3, 2, 1, 1, &[6], &mut out));
        assert_eq!(out, [7; 6]);
        assert!(!transform(&[0; 6], 3, 2, 1, 3, &[], &mut out));
        assert!(!transform(&[0; 6], usize::MAX, 2, 1, 1, &[], &mut out));
    }
}

/// Fuse source palette remapping and composed coordinates without a temporary voxel plane.
pub fn remap_transform(
    packed: Option<(&[u8], usize)>,
    integers: Option<&[i32]>,
    mapping: &[i32],
    palette: usize,
    dimensions: [usize; 3],
    operations: &[i32],
    output: &mut [u8],
) -> bool {
    let [width, length, height] = dimensions;
    let Some(count) = width
        .checked_mul(length)
        .and_then(|n| n.checked_mul(height))
    else {
        return false;
    };
    if count == 0
        || count > 16 * 1024 * 1024
        || !(1..=65536).contains(&palette)
        || mapping.is_empty()
        || mapping.len() > 65536
        || packed.is_some() == integers.is_some()
    {
        return false;
    }
    let stride = if palette <= 256 { 1 } else { 2 };
    if output.len() != count * stride {
        return false;
    }
    if let Some((bytes, input_stride)) = packed {
        if !(1..=2).contains(&input_stride) || bytes.len() != count * input_stride {
            return false;
        }
    }
    if let Some(values) = integers {
        if values.len() != count {
            return false;
        }
    }
    let source_index = |i: usize| -> i32 {
        if let Some((bytes, width)) = packed {
            let p = i * width;
            i32::from(bytes[p])
                | if width == 2 {
                    i32::from(bytes[p + 1]) << 8
                } else {
                    0
                }
        } else {
            integers.unwrap()[i]
        }
    };
    for i in 0..count {
        let id = source_index(i);
        if id < 0
            || id as usize >= mapping.len()
            || mapping[id as usize] < 0
            || mapping[id as usize] as usize >= palette
        {
            return false;
        }
    }
    visit_transformed(width, length, height, operations, |target, input| {
        let id = mapping[source_index(input) as usize];
        let p = target * stride;
        output[p] = id as u8;
        if stride == 2 {
            output[p + 1] = (id >> 8) as u8;
        }
    })
}
#[cfg(test)]
mod fused_transform_tests {
    use super::*;
    #[test]
    fn fused_matches_separate_operations() {
        let indices = [0, 1, 2, 3, 2, 1, 3, 2, 1, 0, 3, 2];
        let packed: Vec<u8> = indices
            .iter()
            .flat_map(|&v| (v as u16).to_le_bytes())
            .collect();
        for a in 0..6 {
            for b in 0..6 {
                for c in 0..6 {
                    let operations = [a, b, c];
                    let mut plane = [0; 24];
                    let mut expected = [0; 24];
                    let mut actual = [0; 24];
                    assert!(remap(
                        None,
                        Some(&indices),
                        &[0, 300, 1, 65535],
                        65536,
                        &mut plane
                    ));
                    assert!(transform(&plane, 3, 2, 2, 2, &operations, &mut expected));
                    assert!(remap_transform(
                        None,
                        Some(&indices),
                        &[0, 300, 1, 65535],
                        65536,
                        [3, 2, 2],
                        &operations,
                        &mut actual
                    ));
                    assert_eq!(actual, expected);
                    assert!(remap_transform(
                        Some((&packed, 2)),
                        None,
                        &[0, 300, 1, 65535],
                        65536,
                        [3, 2, 2],
                        &operations,
                        &mut actual
                    ));
                    assert_eq!(actual, expected);
                }
            }
        }
    }
    #[test]
    fn invalid_input_does_not_write() {
        let mut output = [9; 2];
        assert!(!remap_transform(
            None,
            Some(&[0, -1]),
            &[0],
            1,
            [2, 1, 1],
            &[1],
            &mut output
        ));
        assert_eq!(output, [9; 2]);
        assert!(!remap_transform(
            None,
            Some(&[0, 0]),
            &[0],
            1,
            [2, 1, 1],
            &[6],
            &mut output
        ));
        assert_eq!(output, [9; 2]);
    }
}

/// Decode legacy IDs and metadata directly into a transformed immutable palette plane.
/// A zero low ID byte is absent even when AddBlocks contributes a nonzero high nibble.
pub fn legacy_transform(
    blocks: &[u8],
    data: &[u8],
    add: Option<&[u8]>,
    mapping: &[i32],
    palette: usize,
    dimensions: [usize; 3],
    operations: &[i32],
    output: &mut [u8],
) -> bool {
    let [width, length, height] = dimensions;
    let Some(count) = width
        .checked_mul(length)
        .and_then(|n| n.checked_mul(height))
    else {
        return false;
    };
    if count == 0
        || count > 16 * 1024 * 1024
        || !(1..=65536).contains(&palette)
        || mapping.len() != 65536
        || blocks.len() < count
        || data.len() < count
        || add.is_some_and(|bytes| bytes.len() < count.div_ceil(2))
    {
        return false;
    }
    let stride = if palette <= 256 { 1 } else { 2 };
    if output.len() != count * stride {
        return false;
    }
    let state = |i: usize| {
        let high = add.map_or(0, |bytes| (bytes[i / 2] >> ((i & 1) * 4)) & 15);
        ((usize::from(blocks[i]) | (usize::from(high) << 8)) << 4) | usize::from(data[i] & 15)
    };
    for i in 0..count {
        if blocks[i] != 0 {
            let id = mapping[state(i)];
            if id < 0 || id as usize >= palette {
                return false;
            }
        }
    }
    visit_transformed(width, length, height, operations, |target, source| {
        let id = if blocks[source] == 0 {
            0
        } else {
            mapping[state(source)]
        };
        output[target * stride] = id as u8;
        if stride == 2 {
            output[target * stride + 1] = (id >> 8) as u8;
        }
    })
}

#[cfg(test)]
mod legacy_transform_tests {
    use super::*;
    #[test]
    fn nibbles_low_byte_mask_and_rectangular_rotation() {
        let blocks = [0, 1, 2, 0, 1, 2];
        let data = [3, 15, 4, 6, 8, 1];
        let add = [0x21, 0x43, 0x65];
        let mut mapping = vec![-1; 65536];
        for (state, id) in [
            (513 * 16 + 15, 1),
            (770 * 16 + 4, 2),
            (1281 * 16 + 8, 3),
            (1538 * 16 + 1, 4),
        ] {
            mapping[state] = id;
        }
        let mut out = [9; 6];
        assert!(legacy_transform(
            &blocks,
            &data,
            Some(&add),
            &mapping,
            5,
            [3, 2, 1],
            &[1],
            &mut out
        ));
        assert_eq!(out, [0, 0, 3, 1, 4, 2]);
        assert!(legacy_transform(
            &blocks,
            &data,
            Some(&add),
            &mapping,
            5,
            [3, 2, 1],
            &[3, 5, 1],
            &mut out
        ));
        assert_eq!(out, [2, 1, 0, 4, 3, 0]);
    }
    #[test]
    fn wide_palette_odd_height_and_rejection_before_writes() {
        let mut mapping = vec![-1; 65536];
        mapping[16] = 256;
        let mut out = [7; 6];
        assert!(legacy_transform(
            &[1, 0, 1],
            &[0; 3],
            Some(&[0; 2]),
            &mapping,
            257,
            [1, 1, 3],
            &[],
            &mut out
        ));
        assert_eq!(out, [0, 1, 0, 0, 0, 1]);
        for operation in [6, -1] {
            out.fill(7);
            assert!(!legacy_transform(
                &[1, 0, 1],
                &[0; 3],
                None,
                &mapping,
                257,
                [1, 1, 3],
                &[operation],
                &mut out
            ));
            assert_eq!(out, [7; 6]);
        }
        out.fill(7);
        assert!(!legacy_transform(
            &[1, 0, 1],
            &[0; 3],
            Some(&[0]),
            &mapping,
            257,
            [1, 1, 3],
            &[],
            &mut out
        ));
        assert_eq!(out, [7; 6]);
        mapping[16] = -1;
        assert!(!legacy_transform(
            &[1, 0, 1],
            &[0; 3],
            None,
            &mapping,
            257,
            [1, 1, 3],
            &[],
            &mut out
        ));
        assert_eq!(out, [7; 6]);
    }
}

/// Collect legacy states in the default visitor's Z/X/Y first-encounter order.
/// Scratch storage is supplied by the worker; no voxel plane is produced.
pub fn legacy_states(
    blocks: &[u8],
    data: &[u8],
    add: Option<&[u8]>,
    dimensions: [usize; 3],
    seen: &mut [u64; 1024],
    states: &mut Vec<i32>,
) -> bool {
    let [width, length, height] = dimensions;
    let Some(count) = width
        .checked_mul(length)
        .and_then(|n| n.checked_mul(height))
    else {
        return false;
    };
    if count == 0
        || count > 16 * 1024 * 1024
        || blocks.len() < count
        || data.len() < count
        || add.is_some_and(|bytes| bytes.len() < count.div_ceil(2))
    {
        return false;
    }
    seen.fill(0);
    states.clear();
    for z in 0..height {
        for x in 0..width {
            for y in 0..length {
                let i = x + y * width + z * width * length;
                if blocks[i] == 0 {
                    continue;
                }
                let high = add.map_or(0, |bytes| (bytes[i / 2] >> ((i & 1) * 4)) & 15);
                let state = ((usize::from(blocks[i]) | (usize::from(high) << 8)) << 4)
                    | usize::from(data[i] & 15);
                let bit = 1u64 << (state & 63);
                if seen[state / 64] & bit == 0 {
                    seen[state / 64] |= bit;
                    states.push(state as i32);
                }
            }
        }
    }
    true
}
#[cfg(test)]
mod legacy_states_tests {
    use super::*;
    #[test]
    fn visitor_order_masks_and_worker_reset() {
        let mut seen = [0; 1024];
        let mut states = vec![];
        assert!(legacy_states(
            &[1, 2, 3, 4, 0, 2],
            &[0; 6],
            None,
            [3, 2, 1],
            &mut seen,
            &mut states
        ));
        assert_eq!(states, [16, 64, 32, 48]);
        assert!(legacy_states(
            &[0, 1, 1],
            &[0, 0, 15],
            Some(&[0x21, 0x03]),
            [1, 1, 3],
            &mut seen,
            &mut states
        ));
        assert_eq!(states, [513 * 16, 769 * 16 + 15]);
        let original = states.clone();
        assert!(!legacy_states(
            &[1],
            &[],
            None,
            [1, 1, 1],
            &mut seen,
            &mut states
        ));
        assert_eq!(states, original);
        assert!(legacy_states(
            &[0],
            &[0],
            None,
            [1, 1, 1],
            &mut seen,
            &mut states
        ));
        assert!(states.is_empty());
    }
}
