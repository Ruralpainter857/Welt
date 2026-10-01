//! WLPY v1 : hauteurs X-major et masque hauteur/terrain, traités sans copie JNI.

pub const MAX_BYTES: usize = 32 + 1023 * 1023 * 5;
fn int(data: &[u8], offset: usize) -> i32 {
    i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}
fn float(data: &[u8], offset: usize) -> f32 {
    f32::from_bits(int(data, offset) as u32)
}

pub fn edit(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    if data.len() < 32 || int(data, 0) != 0x59504c57 || int(data, 4) != 1 {
        return Err(IllegalArgument);
    }
    let rings = int(data, 8);
    let rotated = int(data, 20);
    if !(1..=512).contains(&rings) || !matches!(rotated, 0 | 1) || int(data, 28) != 0 {
        return Err(IllegalArgument);
    }
    let side = int(data, 24);
    if side < 1 || side % 2 != 1 || side > rings * 2 - 1 {
        return Err(IllegalArgument);
    }
    let side = side as usize;
    let radius = side / 2;
    let area = side * side;
    if data.len() != 32 + area * 5 {
        return Err(IllegalArgument);
    }
    let mask = 32 + area * 4;
    data[mask..].fill(0);
    let centre = radius * side + radius;
    let height = float(data, 16);
    data[mask + centre] = 2;
    if height < float(data, 12) - 1.5 {
        data[32 + centre * 4..36 + centre * 4].copy_from_slice(&(height + 1.0).to_le_bytes());
        data[mask + centre] = 3;
    }
    let mut desired = height;
    let centre = radius as isize;
    let mut completed = radius + 1 >= rings as usize;
    for ring in 1..=radius as isize {
        let mut raised = false;
        let mut cell = |x: isize, y: isize| {
            let index = (centre + x) as usize * side + (centre + y) as usize;
            if float(data, 32 + index * 4) < desired {
                data[32 + index * 4..36 + index * 4].copy_from_slice(&desired.to_le_bytes());
                data[mask + index] = 3;
                raised = true;
            }
        };
        if rotated != 0 {
            for offset in 0..ring {
                cell(-ring + offset, -offset);
                cell(offset, -ring + offset);
                cell(ring - offset, offset);
                cell(-offset, ring - offset);
            }
        } else {
            for offset in -ring..=ring {
                cell(offset, -ring);
                cell(offset, ring);
            }
            for offset in -ring + 1..ring {
                cell(-ring, offset);
                cell(ring, offset);
            }
        }
        if !raised {
            completed = true;
            break;
        }
        desired -= 1.0;
    }
    data[28..32].copy_from_slice(&i32::from(!completed).to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn bounded_group_reports_expansion_before_java_applies_any_changes() {
        let side = 33usize;
        let area = side * side;
        let mut data = vec![0u8; 32 + area * 5];
        for (offset, value) in [(0, 0x59504c57i32), (4, 1), (8, 384), (24, side as i32)] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        data[12..16].copy_from_slice(&320f32.to_le_bytes());
        data[16..20].copy_from_slice(&64f32.to_le_bytes());
        for i in 0..area {
            data[32 + i * 4..36 + i * 4].copy_from_slice(&62f32.to_le_bytes());
        }
        super::edit(&mut data).unwrap();
        assert_eq!(super::int(&data, 28), 0);
        data[28..32].fill(0);
        for i in 0..area {
            data[32 + i * 4..36 + i * 4].copy_from_slice(&(-f32::MAX).to_le_bytes());
        }
        super::edit(&mut data).unwrap();
        assert_eq!(super::int(&data, 28), 1);
    }
    #[test]
    fn matches_existing_kernel_and_rejects_invalid_header_without_mutation() {
        for rotated in [false, true] {
            let rings = 64;
            let side = rings * 2 - 1;
            let area = side * side;
            let mut data = vec![0u8; 32 + area * 5];
            for (offset, value) in [
                (0, 0x59504c57i32),
                (4, 1),
                (8, rings as i32),
                (20, i32::from(rotated)),
                (24, side as i32),
            ] {
                data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
            }
            data[12..16].copy_from_slice(&128f32.to_le_bytes());
            data[16..20].copy_from_slice(&126.75f32.to_le_bytes());
            let mut heights: Vec<f32> = (0..area).map(|i| (i % 87) as f32 + 0.25).collect();
            for (i, h) in heights.iter().enumerate() {
                data[32 + i * 4..36 + i * 4].copy_from_slice(&h.to_le_bytes());
            }
            let mut modified = vec![0i8; area];
            let kernel = if rotated {
                crate::raise_pyramid::raise_rotated_pyramid
            } else {
                crate::raise_pyramid::raise_square_pyramid
            };
            kernel(rings as i32, 126.75, 128.0, &mut heights, &mut modified).unwrap();
            super::edit(&mut data).unwrap();
            for i in 0..area {
                assert_eq!(
                    super::float(&data, 32 + i * 4).to_bits(),
                    heights[i].to_bits()
                );
                let expected = if i == area / 2 {
                    2
                } else if modified[i] != 0 {
                    3
                } else {
                    0
                };
                assert_eq!(data[32 + area * 4 + i], expected);
            }
            data[4..8].copy_from_slice(&2i32.to_le_bytes());
            let before = data.clone();
            assert!(super::edit(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
}
