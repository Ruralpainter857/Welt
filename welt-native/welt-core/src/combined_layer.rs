//! WCLB v1: apply an ordered combined-layer transaction to compact tile planes.
//! Java records the original random stream; this kernel never invents or reorders draws.
//! Header words: magic, version, pass count, source offset, random offset/count, two reserved zeros.
//! Descriptor words: role, packed bits (zero for chunk bits), factor bits, constant, plane offset,
//! presence, requested writes, effective writes. All words and recorded f64 samples are little-endian.
//! Roles: 0 numeric layer, 1 bit layer, 2 terrain, 3 biome. Only positive numeric values overwrite.
//! Chunk bits retain Java's 16 by 16 addressing; stochastic decisions still visit every source cell.
use crate::error::WeltError;

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
pub const AREA: usize = 16384;
const HEADER: usize = 32;
const DESCRIPTOR: usize = 32;
const MAGIC: u32 = 0x424c4357;

fn word(data: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}

fn strength(data: &[u8], source: usize, cell: usize) -> u8 {
    (data[source + cell / 2] >> (cell % 2 * 4)) & 15
}

fn bit_strength(value: u8, factor: f32) -> f32 {
    let value = (value as f32 / 15.0) * factor;
    if value.is_nan() {
        value
    } else {
        value.min(1.0)
    }
}

fn plane_bytes(bits: u32) -> Option<usize> {
    match bits {
        0 => Some(8),
        1 => Some(2048),
        4 => Some(8192),
        8 => Some(16384),
        _ => None,
    }
}

fn needs_draw(data: &[u8], source: usize, d: usize, cell: usize) -> bool {
    let value = strength(data, source, cell);
    match word(data, d) {
        1 => !(bit_strength(value, f32::from_bits(word(data, d + 8))) > 0.95),
        2 | 3 => value > 0 && (value as f32 / 15.0) < 0.5,
        _ => false,
    }
}

fn read_plane(data: &[u8], offset: usize, bits: u32, cell: usize) -> u8 {
    let bits = bits.max(1) as usize;
    (data[offset + cell * bits / 8] >> (cell * bits % 8)) & ((1u16 << bits) - 1) as u8
}

fn write_plane(data: &mut [u8], offset: usize, bits: u32, cell: usize, value: u8) {
    let bits = bits.max(1) as usize;
    let shift = cell * bits % 8;
    let mask = (((1u16 << bits) - 1) as u8) << shift;
    let byte = &mut data[offset + cell * bits / 8];
    *byte = (*byte & !mask) | (value << shift);
}

fn apply_cell(
    data: &mut [u8],
    source: usize,
    rng: usize,
    cursor: &mut usize,
    d: usize,
    cell: usize,
) {
    let role = word(data, d);
    let bits = word(data, d + 4);
    let factor = f32::from_bits(word(data, d + 8));
    let value = strength(data, source, cell);
    let random = if needs_draw(data, source, d, cell) {
        let offset = rng + *cursor * 8;
        *cursor += 1;
        f64::from_le_bytes(data[offset..offset + 8].try_into().unwrap())
    } else {
        0.0
    };
    let result = match role {
        0 => {
            // Promote the already-rounded float product before adding the half, as Math.round(float) does.
            let rounded = (f64::from(value as f32 * factor) + 0.5).floor() as i32;
            rounded.min(if bits == 4 { 15 } else { 255 })
        }
        1 => {
            let s = bit_strength(value, factor);
            if s > 0.95 || random < f64::from(s) {
                1
            } else {
                0
            }
        }
        _ => {
            let s = value as f32 / 15.0;
            if s > 0.0 && (s >= 0.5 || random / 2.0 < f64::from(s)) {
                word(data, d + 12) as i32
            } else {
                -1
            }
        }
    };
    if (role < 2 && result <= 0) || (role >= 2 && result < 0) {
        return;
    }
    data[d + 24..d + 28].copy_from_slice(&1u32.to_le_bytes());
    let offset = word(data, d + 16) as usize;
    let destination = if bits == 0 {
        (cell % 128 / 16) + (cell / 128 / 16) * 8
    } else {
        cell
    };
    let old = read_plane(data, offset, bits, destination);
    // Absent numeric layers setting their default remain absent, even though they count as added.
    if role != 0 || word(data, d + 20) != 0 || old != result as u8 {
        data[d + 28..d + 32].copy_from_slice(&1u32.to_le_bytes());
        data[d + 20..d + 24].copy_from_slice(&1u32.to_le_bytes());
        write_plane(data, offset, bits, destination, result as u8);
    }
}

/// Validate the entire transaction, including random draws, before changing a byte.
pub fn apply(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() < HEADER
        || data.len() > MAX_BYTES
        || word(data, 0) != MAGIC
        || word(data, 4) != 1
        || word(data, 24) != 0
        || word(data, 28) != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let count = word(data, 8) as usize;
    let source = word(data, 12) as usize;
    let rng = word(data, 16) as usize;
    let draws = word(data, 20) as usize;
    if count == 0
        || count > 128
        || source != HEADER + count * DESCRIPTOR
        || source + AREA / 2 > data.len()
        || rng > data.len()
        || !rng.is_multiple_of(8)
        || draws > (data.len() - rng) / 8
        || rng + draws * 8 != data.len()
    {
        return Err(WeltError::IllegalArgument);
    }
    let mut end = source + AREA / 2;
    let mut terrain = None;
    let mut biome = None;
    let mut expected_draws = 0;
    for plane in 0..count {
        let d = HEADER + plane * DESCRIPTOR;
        let role = word(data, d);
        let bits = word(data, d + 4);
        let bytes = plane_bytes(bits).ok_or(WeltError::IllegalArgument)?;
        if role > 3
            || (role == 0 && !matches!(bits, 4 | 8))
            || (role == 1 && !matches!(bits, 0 | 1))
            || (role >= 2 && bits != 8)
            || word(data, d + 16) as usize != end
            || end + bytes > rng
            || word(data, d + 20) > 1
            || word(data, d + 24) != 0
            || word(data, d + 28) != 0
            || (role >= 2 && word(data, d + 12) > 255)
        {
            return Err(WeltError::IllegalArgument);
        }
        if role == 2 {
            if terrain.is_some() || plane > 1 {
                return Err(WeltError::IllegalArgument);
            }
            terrain = Some(d);
        }
        if role == 3 {
            if biome.is_some() || plane > 1 {
                return Err(WeltError::IllegalArgument);
            }
            biome = Some(d);
        }
        for cell in 0..AREA {
            expected_draws += usize::from(needs_draw(data, source, d, cell));
        }
        end += bytes;
    }
    if end != rng || draws != expected_draws {
        return Err(WeltError::IllegalArgument);
    }
    for sample in data[rng..].as_chunks::<8>().0 {
        let value = f64::from_le_bytes(*sample);
        if !(0.0..1.0).contains(&value) {
            return Err(WeltError::IllegalArgument);
        }
    }
    let mut cursor = 0;
    // Terrain and biome draw interleaved per cell, before constituent layer passes.
    for x in 0..128 {
        for y in 0..128 {
            let cell = x + y * 128;
            if let Some(d) = terrain {
                apply_cell(data, source, rng, &mut cursor, d, cell);
            }
            if let Some(d) = biome {
                apply_cell(data, source, rng, &mut cursor, d, cell);
            }
        }
    }
    for plane in 0..count {
        let d = HEADER + plane * DESCRIPTOR;
        if word(data, d) >= 2 {
            continue;
        }
        for x in 0..128 {
            for y in 0..128 {
                apply_cell(data, source, rng, &mut cursor, d, x + y * 128);
            }
        }
    }
    debug_assert_eq!(cursor, draws);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(role: u32, bits: u32, factor: f32, value: u8, random: f64) -> Vec<u8> {
        let source = HEADER + DESCRIPTOR;
        let plane = source + AREA / 2;
        let rng = plane + plane_bytes(bits).unwrap();
        let needs = if role == 1 {
            !(bit_strength(value, factor) > 0.95)
        } else {
            role >= 2 && value > 0 && value < 8
        };
        let draws = if needs { AREA } else { 0 };
        let mut data = vec![0; rng + draws * 8];
        for (offset, v) in [
            (0, MAGIC),
            (4, 1),
            (8, 1),
            (12, source as u32),
            (16, rng as u32),
            (20, draws as u32),
            (HEADER, role),
            (HEADER + 4, bits),
            (HEADER + 8, factor.to_bits()),
            (HEADER + 12, 7),
            (HEADER + 16, plane as u32),
        ] {
            data[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
        }
        data[source..plane].fill(value | value << 4);
        for sample in data[rng..].as_chunks_mut::<8>().0 {
            sample.copy_from_slice(&random.to_le_bytes());
        }
        data
    }

    #[test]
    fn packed_numeric_bits_and_terrain_keep_java_thresholds() {
        for bits in [4, 8] {
            for value in 0..16 {
                for factor in [0.0, 0.5, 1.0, 1.25, f32::NAN, f32::INFINITY, -1.0] {
                    let mut data = fixture(0, bits, factor, value, 0.3);
                    let plane = word(&data, HEADER + 16) as usize;
                    apply(&mut data).unwrap();
                    let expected = ((f64::from(value as f32 * factor) + 0.5).floor() as i32)
                        .min(if bits == 4 { 15 } else { 255 })
                        .max(0) as u8;
                    for cell in 0..AREA {
                        assert_eq!(read_plane(&data, plane, bits, cell), expected);
                    }
                }
            }
        }
        for bits in [0, 1] {
            for factor in [0.0, 0.8, 2.0, f32::NAN, -1.0] {
                let mut data = fixture(1, bits, factor, 7, 0.3);
                let plane = word(&data, HEADER + 16) as usize;
                apply(&mut data).unwrap();
                let s = bit_strength(7, factor);
                let expected = u8::from(s > 0.95 || 0.3 < f64::from(s));
                for cell in 0..if bits == 0 { 64 } else { AREA } {
                    assert_eq!(read_plane(&data, plane, bits, cell), expected);
                }
            }
        }
        for role in [2, 3] {
            for value in [0, 7, 8, 15] {
                let mut data = fixture(role, 8, 1.0, value, 0.3);
                let plane = word(&data, HEADER + 16) as usize;
                apply(&mut data).unwrap();
                assert_eq!(data[plane], if value == 0 { 0 } else { 7 });
            }
        }
    }

    #[test]
    fn invalid_random_stream_is_rejected_without_partial_writes() {
        let mut data = fixture(1, 1, 0.5, 7, 0.3);
        let rng = word(&data, 16) as usize;
        data[rng..rng + 8].copy_from_slice(&f64::NAN.to_le_bytes());
        let before = data.clone();
        assert!(apply(&mut data).is_err());
        assert_eq!(data, before);
    }

    #[test]
    fn malformed_layouts_are_rejected_before_any_mutation() {
        for offset in [
            0,
            4,
            8,
            12,
            16,
            20,
            24,
            28,
            HEADER,
            HEADER + 4,
            HEADER + 16,
            HEADER + 20,
            HEADER + 24,
            HEADER + 28,
        ] {
            let mut data = fixture(1, 1, 0.5, 7, 0.3);
            data[offset..offset + 4].copy_from_slice(&u32::MAX.to_le_bytes());
            let before = data.clone();
            assert!(apply(&mut data).is_err(), "offset {offset}");
            assert_eq!(data, before);
        }
        for length in [0, 31, 32, 100] {
            let mut data = fixture(1, 1, 0.5, 7, 0.3);
            data.truncate(length);
            let before = data.clone();
            assert!(apply(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
}
