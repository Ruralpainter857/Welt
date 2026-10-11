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
                let input = (sx + sy * width + z * width * length) * stride;
                let target = (x + y * out_w + z * out_w * out_h) * stride;
                output[target..target + stride].copy_from_slice(&source[input..input + stride]);
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
