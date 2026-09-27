//! Bit-exact palette-index packing for Minecraft's long-array formats.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PackError {
    InvalidWordSize,
    IndexOutOfRange { position: usize, value: u32 },
    OutputTooShort,
}

/// Pack palette indexes into the layouts used by `PackedArrayCube.pack`.
pub fn pack_indices(
    indices: &[u32],
    bits_per_index: u32,
    straddle_longs: bool,
) -> Result<Vec<u64>, PackError> {
    if !(1..=32).contains(&bits_per_index) {
        return Err(PackError::InvalidWordSize);
    }
    let maximum = if bits_per_index == 32 {
        u32::MAX
    } else {
        (1_u32 << bits_per_index) - 1
    };
    if let Some((position, &value)) = indices
        .iter()
        .enumerate()
        .find(|(_, value)| **value > maximum)
    {
        return Err(PackError::IndexOutOfRange { position, value });
    }

    if bits_per_index == 4 && indices.len().is_multiple_of(16) {
        let mut output = vec![0_u64; indices.len() / 16];
        for (group, values) in indices.as_chunks::<16>().0.iter().enumerate() {
            let mut packed = 0_u64;
            for (offset, &value) in values.iter().enumerate() {
                packed |= u64::from(value) << (offset * 4);
            }
            output[group] = packed;
        }
        return Ok(output);
    }

    if straddle_longs {
        let output_len = 64_usize
            .checked_mul(bits_per_index as usize)
            .ok_or(PackError::OutputTooShort)?;
        let mut output = vec![0_u64; output_len];
        for (position, &value) in indices.iter().enumerate() {
            let bit_offset = position
                .checked_mul(bits_per_index as usize)
                .ok_or(PackError::OutputTooShort)?;
            set_straddling(&mut output, bit_offset, bits_per_index, value)?;
        }
        return Ok(output);
    }

    let bits = bits_per_index as usize;
    let values_per_long = 64 / bits;
    let output_len = indices.len().div_ceil(values_per_long);
    let mut output = vec![0_u64; output_len];
    for (position, &value) in indices.iter().enumerate() {
        let word = position / values_per_long;
        let offset = (position % values_per_long) * bits;
        output[word] |= u64::from(value) << offset;
    }
    Ok(output)
}

fn set_straddling(
    output: &mut [u64],
    bit_offset: usize,
    bits: u32,
    value: u32,
) -> Result<(), PackError> {
    let word = bit_offset / 64;
    let offset = bit_offset % 64;
    let Some(low_word) = output.get_mut(word) else {
        return Err(PackError::OutputTooShort);
    };
    *low_word |= u64::from(value) << offset;
    if offset + bits as usize > 64 {
        let Some(high_word) = output.get_mut(word + 1) else {
            return Err(PackError::OutputTooShort);
        };
        *high_word |= u64::from(value) >> (64 - offset);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{pack_indices, PackError};

    #[test]
    fn nibble_layout_matches_packed_array_cube() {
        let input: Vec<u32> = (0..64).map(|index| (index % 16) as u32).collect();
        let packed = pack_indices(&input, 4, false).unwrap();
        assert_eq!(packed.len(), 4);
        for (index, &value) in input.iter().enumerate() {
            assert_eq!(
                (packed[index / 16] >> ((index % 16) * 4)) & 0xf,
                u64::from(value)
            );
        }
    }

    #[test]
    fn non_straddling_layout_starts_each_word_at_a_long_boundary() {
        let input: Vec<u32> = (0..128).map(|index| (index % 32) as u32).collect();
        let packed = pack_indices(&input, 5, false).unwrap();
        assert_eq!(packed.len(), 11);
        for (index, &value) in input.iter().enumerate() {
            let word = index / 12;
            let offset = (index % 12) * 5;
            assert_eq!((packed[word] >> offset) & 0x1f, u64::from(value));
        }
    }

    #[test]
    fn straddling_layout_crosses_long_boundaries_and_zero_fills_padding() {
        let input: Vec<u32> = (0..4096)
            .map(|index| ((index * 17) % 2048) as u32)
            .collect();
        let packed = pack_indices(&input, 11, true).unwrap();
        assert_eq!(packed.len(), 704);
        for (index, &expected) in input.iter().enumerate() {
            let bit = index * 11;
            let word = bit / 64;
            let offset = bit % 64;
            let mut actual = packed[word] >> offset;
            if offset + 11 > 64 {
                actual |= packed[word + 1] << (64 - offset);
            }
            assert_eq!(actual & 0x7ff, u64::from(expected), "index={index}");
        }
        let short = pack_indices(&[3, 6, 1], 11, true).unwrap();
        assert_eq!(short.len(), 704);
        assert_eq!(short[0] & 0x7ff, 3);
        assert_eq!((short[0] >> 11) & 0x7ff, 6);
        assert_eq!((short[0] >> 22) & 0x7ff, 1);
        assert!(short[1..].iter().all(|&word| word == 0));
    }

    #[test]
    fn validates_palette_word_size_and_indexes() {
        assert_eq!(
            pack_indices(&[0], 0, false),
            Err(PackError::InvalidWordSize)
        );
        assert_eq!(
            pack_indices(&[4], 2, false),
            Err(PackError::IndexOutOfRange {
                position: 0,
                value: 4
            })
        );
    }
}
