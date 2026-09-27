//! Validator for the direct, little-endian chunk palette ABI used by Java.

pub const MAGIC: u32 = 0x4b48_4357; // "WCHK"
pub const ABI_VERSION: u32 = 1;
pub const HEADER_BYTES: usize = 68;
pub const SECTION_DESCRIPTOR_BYTES: usize = 16;
const NEIGHBOR_DESCRIPTOR_BYTES: usize = 16;
const MAX_SECTIONS: usize = 256;
const MAX_NEIGHBORS: usize = 256;
const MAX_PALETTE: usize = 1_048_576;
const SEMANTIC_MASK: u32 = 0xff;

fn read_u32(data: &[u8], offset: usize) -> Option<u32> {
    let bytes: [u8; 4] = data.get(offset..offset.checked_add(4)?)?.try_into().ok()?;
    Some(u32::from_le_bytes(bytes))
}

/// Checks the complete v1 buffer before any future native kernel is allowed to mutate it.
pub fn validate(data: &[u8]) -> bool {
    if data.len() < HEADER_BYTES
        || read_u32(data, 0) != Some(MAGIC)
        || read_u32(data, 4) != Some(ABI_VERSION)
        || read_u32(data, 8) != Some(HEADER_BYTES as u32)
    {
        return false;
    }
    let Some(total_bytes) = read_u32(data, 12).map(|v| v as usize) else {
        return false;
    };
    let Some(min_y) = read_u32(data, 24).map(|v| v as i32) else {
        return false;
    };
    let Some(section_count) = read_u32(data, 28).map(|v| v as usize) else {
        return false;
    };
    let Some(palette_count) = read_u32(data, 32).map(|v| v as usize) else {
        return false;
    };
    let Some(section_table) = read_u32(data, 36).map(|v| v as usize) else {
        return false;
    };
    let Some(flags_offset) = read_u32(data, 40).map(|v| v as usize) else {
        return false;
    };
    let Some(data_offset) = read_u32(data, 44).map(|v| v as usize) else {
        return false;
    };
    let Some(format_code) = read_u32(data, 48) else {
        return false;
    };
    let Some(data_version) = read_u32(data, 52).map(|v| v as i32) else {
        return false;
    };
    let Some(neighbor_count) = read_u32(data, 56).map(|v| v as usize) else {
        return false;
    };
    let Some(neighbor_offset) = read_u32(data, 60).map(|v| v as usize) else {
        return false;
    };
    let Some(mutation_sequence) = read_u32(data, 64) else {
        return false;
    };

    if total_bytes != data.len()
        || !(1..=MAX_SECTIONS).contains(&section_count)
        || palette_count == 0
        || palette_count > MAX_PALETTE
        || min_y & 15 != 0
        || section_table != HEADER_BYTES
        || format_code > 3
        || data_version < -1
        || neighbor_count > MAX_NEIGHBORS
        || mutation_sequence > i32::MAX as u32
    {
        return false;
    }
    let Some(expected_flags_offset) =
        section_table.checked_add(section_count * SECTION_DESCRIPTOR_BYTES)
    else {
        return false;
    };
    let Some(expected_neighbor_offset) = expected_flags_offset.checked_add(palette_count * 4)
    else {
        return false;
    };
    let Some(expected_data_offset) =
        expected_neighbor_offset.checked_add(neighbor_count * NEIGHBOR_DESCRIPTOR_BYTES)
    else {
        return false;
    };
    if flags_offset != expected_flags_offset
        || neighbor_offset != expected_neighbor_offset
        || data_offset != expected_data_offset
        || data_offset > total_bytes
    {
        return false;
    }

    for neighbor in 0..neighbor_count {
        let descriptor = neighbor_offset + neighbor * NEIGHBOR_DESCRIPTOR_BYTES;
        let Some(neighbor_min_y) = read_u32(data, descriptor + 8).map(|v| v as i32) else {
            return false;
        };
        let Some(neighbor_max_y) = read_u32(data, descriptor + 12).map(|v| v as i32) else {
            return false;
        };
        if neighbor_min_y >= neighbor_max_y || neighbor_min_y & 15 != 0 || neighbor_max_y & 15 != 0
        {
            return false;
        }
    }

    let Some(y_extent) = section_count.checked_mul(16) else {
        return false;
    };
    if min_y.checked_add(y_extent as i32).is_none() {
        return false;
    }
    let mut expected_cell_offset = data_offset;
    for section in 0..section_count {
        let descriptor = section_table + section * SECTION_DESCRIPTOR_BYTES;
        let Some(y) = read_u32(data, descriptor).map(|v| v as i32) else {
            return false;
        };
        let Some(width) = read_u32(data, descriptor + 4).map(|v| v as usize) else {
            return false;
        };
        let Some(offset) = read_u32(data, descriptor + 8).map(|v| v as usize) else {
            return false;
        };
        let Some(cells) = read_u32(data, descriptor + 12).map(|v| v as usize) else {
            return false;
        };
        if y != min_y + (section as i32) * 16
            || !matches!(width, 1 | 2 | 4)
            || cells != 4096
            || offset != expected_cell_offset
            || (width == 1 && palette_count > 256)
            || (width == 2 && palette_count > 65_536)
        {
            return false;
        }
        let Some(section_bytes) = cells.checked_mul(width) else {
            return false;
        };
        let Some(next_offset) = expected_cell_offset.checked_add(section_bytes) else {
            return false;
        };
        if next_offset > total_bytes {
            return false;
        }
        for cell in 0..cells {
            let index_offset = offset + cell * width;
            let index = match width {
                1 => data[index_offset] as u32,
                2 => u16::from_le_bytes([data[index_offset], data[index_offset + 1]]) as u32,
                4 => u32::from_le_bytes(data[index_offset..index_offset + 4].try_into().unwrap()),
                _ => unreachable!(),
            } as usize;
            if index >= palette_count {
                return false;
            }
        }
        expected_cell_offset = next_offset;
    }
    if expected_cell_offset != total_bytes {
        return false;
    }
    for palette_index in 0..palette_count {
        let Some(flags) = read_u32(data, flags_offset + palette_index * 4) else {
            return false;
        };
        if flags & !SEMANTIC_MASK != 0 {
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_buffer() -> Vec<u8> {
        let sections = 1usize;
        let palette = 2usize;
        let descriptor = HEADER_BYTES;
        let flags = descriptor + SECTION_DESCRIPTOR_BYTES;
        let blocks = flags + palette * 4;
        let total = blocks + 4096;
        let mut bytes = vec![0; total];
        let put = |bytes: &mut [u8], offset: usize, value: u32| {
            bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        };
        put(&mut bytes, 0, MAGIC);
        put(&mut bytes, 4, ABI_VERSION);
        put(&mut bytes, 8, HEADER_BYTES as u32);
        put(&mut bytes, 12, total as u32);
        put(&mut bytes, 24, (-64_i32) as u32);
        put(&mut bytes, 28, sections as u32);
        put(&mut bytes, 32, palette as u32);
        put(&mut bytes, 36, descriptor as u32);
        put(&mut bytes, 40, flags as u32);
        put(&mut bytes, 44, blocks as u32);
        put(&mut bytes, 48, 0);
        put(&mut bytes, 52, (-1_i32) as u32);
        put(&mut bytes, 56, 0);
        put(&mut bytes, 60, blocks as u32);
        put(&mut bytes, 64, 0);
        put(&mut bytes, descriptor, (-64_i32) as u32);
        put(&mut bytes, descriptor + 4, 1);
        put(&mut bytes, descriptor + 8, blocks as u32);
        put(&mut bytes, descriptor + 12, 4096);
        bytes
    }

    #[test]
    fn accepts_complete_buffer_with_negative_section_y() {
        assert!(validate(&valid_buffer()));
    }

    #[test]
    fn rejects_invalid_offsets_and_palette_indexes() {
        let mut bad_offset = valid_buffer();
        bad_offset[40..44].copy_from_slice(&0_u32.to_le_bytes());
        assert!(!validate(&bad_offset));

        let mut bad_index = valid_buffer();
        let index_offset = HEADER_BYTES + SECTION_DESCRIPTOR_BYTES + 8;
        bad_index[index_offset] = 2;
        assert!(!validate(&bad_index));
    }
}
