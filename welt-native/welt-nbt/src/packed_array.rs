//! Bit-exact palette-index packing for Minecraft's long-array formats.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PackError {
    InvalidWordSize,
    IndexOutOfRange { position: usize, value: u32 },
    OutputTooShort,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UnpackError {
    InvalidWordSize,
    InvalidPaletteSize,
    IndexOutOfRange { position: usize, value: u32 },
    InputTooShort,
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

/// Decode palette indexes using the branches and layouts in `PackedArrayCube`'s Java constructor.
pub fn unpack_indices(
    data: &[u64],
    array_size: usize,
    bits_per_index: u32,
    palette_size: usize,
) -> Result<Vec<u32>, UnpackError> {
    if !(1..=32).contains(&bits_per_index) {
        return Err(UnpackError::InvalidWordSize);
    }
    if palette_size == 0 {
        return Err(UnpackError::InvalidPaletteSize);
    }
    if array_size == 0 {
        return Ok(Vec::new());
    }

    let bits = bits_per_index as usize;
    let mut indexes = vec![0_u32; array_size];
    if bits_per_index == 4 {
        if !array_size.is_multiple_of(16) || data.len() < array_size / 16 {
            return Err(UnpackError::InputTooShort);
        }
        for (position, index) in indexes.iter_mut().enumerate() {
            *index = ((data[position / 16] >> ((position % 16) * 4)) & 0xf) as u32;
        }
    } else {
        let expected_bytes = bits
            .checked_mul(array_size)
            .ok_or(UnpackError::InputTooShort)?
            / 8;
        let actual_bytes = data
            .len()
            .checked_mul(8)
            .ok_or(UnpackError::InputTooShort)?;
        if actual_bytes != expected_bytes {
            let values_per_long = 64 / bits;
            let bits_in_use = values_per_long * bits;
            let mut position = 0;
            'longs: for &packed in data {
                for offset in (0..bits_in_use).step_by(bits) {
                    indexes[position] = ((packed >> offset) & bit_mask(bits_per_index)) as u32;
                    position += 1;
                    if position == array_size {
                        break 'longs;
                    }
                }
            }
            if position != array_size {
                return Err(UnpackError::InputTooShort);
            }
        } else {
            for (position, index) in indexes.iter_mut().enumerate() {
                let bit_offset = position
                    .checked_mul(bits)
                    .ok_or(UnpackError::InputTooShort)?;
                let word = bit_offset / 64;
                let offset = bit_offset % 64;
                let Some(&low_word) = data.get(word) else {
                    return Err(UnpackError::InputTooShort);
                };
                let mut value = low_word >> offset;
                if offset + bits > 64 {
                    let Some(&high_word) = data.get(word + 1) else {
                        return Err(UnpackError::InputTooShort);
                    };
                    value |= high_word << (64 - offset);
                }
                *index = (value & bit_mask(bits_per_index)) as u32;
            }
        }
    }

    if let Some((position, &value)) = indexes
        .iter()
        .enumerate()
        .find(|(_, value)| **value as usize >= palette_size)
    {
        return Err(UnpackError::IndexOutOfRange { position, value });
    }
    Ok(indexes)
}

fn bit_mask(bits_per_index: u32) -> u64 {
    if bits_per_index == 32 {
        u64::from(u32::MAX)
    } else {
        (1_u64 << bits_per_index) - 1
    }
}

#[cfg(test)]
mod tests {
    use super::{pack_indices, unpack_indices, PackError, UnpackError};

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

    #[test]
    fn unpacks_nibble_layout() {
        let input: Vec<u32> = (0..4096).map(|index| (index % 16) as u32).collect();
        let packed = pack_indices(&input, 4, false).unwrap();
        assert_eq!(unpack_indices(&packed, input.len(), 4, 16).unwrap(), input);
    }

    #[test]
    fn unpacks_non_straddling_layout_with_padding_per_long() {
        let input: Vec<u32> = (0..4096).map(|index| (index % 32) as u32).collect();
        let packed = pack_indices(&input, 5, false).unwrap();
        assert_eq!(unpack_indices(&packed, input.len(), 5, 32).unwrap(), input);
    }

    #[test]
    fn unpacks_straddling_layout_across_long_boundaries() {
        let input: Vec<u32> = (0..4096).map(|index| (index % 32) as u32).collect();
        let packed = pack_indices(&input, 5, true).unwrap();
        assert_eq!(unpack_indices(&packed, input.len(), 5, 32).unwrap(), input);
    }

    #[test]
    fn rejects_short_or_out_of_palette_input_for_java_fallback() {
        assert_eq!(
            unpack_indices(&[], 16, 5, 32),
            Err(UnpackError::InputTooShort)
        );
        assert_eq!(
            unpack_indices(&[u64::MAX; 256], 4096, 4, 15),
            Err(UnpackError::IndexOutOfRange {
                position: 0,
                value: 15
            })
        );
    }
}
