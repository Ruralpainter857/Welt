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
