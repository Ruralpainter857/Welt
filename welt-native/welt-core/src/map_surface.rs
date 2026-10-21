//! Complete surface projection over section palettes, without Minecraft objects or per-cell JNI.
//!
//! WMSF v1/v2 is little endian. Its 64-byte header stores magic/version/length/section count,
//! source minimum Y, inclusive scan floor/top, destination minimum Y, default water,
//! bedrock terrain, deep-scan flag, terrain count, palette base, result offset/count,
//! and a reserved zero word. Each 16-byte section descriptor stores palette offset/count,
//! a uniform flag and a reserved zero word. Palette records are flags/terrain/snow (12 bytes).
//! Nonuniform sections borrow exactly 4096 indices in X + Z*16 + localY*256 order;
//! uniform sections have one palette record and no array. V2 source kind 2 retains padded
//! NBT longs (width max(4, ceil(log2(palette count)))), with no expanded index array.
//! All 256 results are X-major
//! height-f32/water-i32/terrain-i32/flags-u32 records. Only those result bytes may change.
//! JNI owns the array borrows until this synchronous operation finishes; the direct
//! metadata/result frame belongs to one Java worker and is reused for its next chunk.
use crate::error::WeltError;
pub const MAX_BYTES: usize = 16 * 1024 * 1024;
pub const MAGIC: u32 = 0x46534d57;
const AIR: u32 = 1;
const NATURAL: u32 = 2;
const FROST: u32 = 4;
const FLUID: u32 = 8;
const LAVA: u32 = 16;
const SNOW: u32 = 32;
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
#[derive(Clone, Copy)]
struct MaterialInfo {
    flags: u32,
    terrain: i32,
    snow: i32,
}
/// A section can retain its serialized padded longs throughout the complete projection.
#[derive(Clone, Copy)]
pub enum SectionSource<'a> {
    Uniform,
    Indices(&'a [i32]),
    Packed { words: &'a [i64], bits: u32 },
}
impl SectionSource<'_> {
    fn index(self, position: usize) -> usize {
        match self {
            Self::Uniform => 0,
            Self::Indices(a) => a[position] as usize,
            Self::Packed { words, bits } => {
                if bits == 4 {
                    return ((words[position >> 4] as u64 >> ((position & 15) * 4)) & 15) as usize;
                }
                let per_word = 64 / bits as usize;
                ((words[position / per_word] as u64 >> ((position % per_word) * bits as usize))
                    & ((1u64 << bits) - 1)) as usize
            }
        }
    }
}
struct View<'a> {
    palettes: &'a [MaterialInfo],
    offsets: &'a [usize],
    sections: &'a [SectionSource<'a>],
    min: i32,
}
impl View<'_> {
    fn material(&self, x: usize, y: i32, z: usize) -> MaterialInfo {
        let dy = i64::from(y) - i64::from(self.min);
        if dy < 0 || dy >= self.sections.len() as i64 * 16 {
            return MaterialInfo {
                flags: AIR | NATURAL,
                terrain: -1,
                snow: 0,
            };
        }
        let section = dy as usize / 16;
        let index = self.sections[section].index(x + z * 16 + (dy as usize & 15) * 256);
        self.palettes[self.offsets[section] + index]
    }
}
/// All metadata and palette indices are validated before the first result is written.
/// Outputs are X-major records of height float, water int, terrain int and frost/lava/void/man-made bits.
#[derive(Default)]
pub struct Scratch {
    palettes: Vec<MaterialInfo>,
    offsets: Vec<usize>,
}
pub fn analyze(d: &mut [u8], sections: &[Option<&[i32]>]) -> Result<(), WeltError> {
    analyze_with_scratch(d, sections, &mut Scratch::default())
}
/// Decode palette semantics once and reuse allocations across chunks on the same worker.
pub fn analyze_with_scratch(
    d: &mut [u8],
    sections: &[Option<&[i32]>],
    scratch: &mut Scratch,
) -> Result<(), WeltError> {
    let sources: Vec<_> = sections
        .iter()
        .map(|s| s.map_or(SectionSource::Uniform, SectionSource::Indices))
        .collect();
    analyze_sources_with_scratch(d, &sources, scratch)
}
/// WMSF v2 source kind 2 borrows validated padded longs, without a 4096-index expansion.
pub fn analyze_sources_with_scratch(
    d: &mut [u8],
    sections: &[SectionSource<'_>],
    scratch: &mut Scratch,
) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 64
        || d.len() > MAX_BYTES
        || word(d, 0) != MAGIC
        || !(1..=2).contains(&word(d, 4))
        || word(d, 8) as usize != d.len()
    {
        return Err(bad);
    }
    let n = word(d, 12) as usize;
    let min = word(d, 16) as i32;
    let floor = word(d, 20) as i32;
    let top = word(d, 24) as i32;
    let world_min = word(d, 28) as i32;
    let default_water = word(d, 32) as i32;
    let bedrock = word(d, 36) as i32;
    let deep = word(d, 40);
    let terrains = word(d, 44) as usize;
    let output = word(d, 52) as usize;
    if !(1..=256).contains(&n)
        || n != sections.len()
        || min & 15 != 0
        || floor < min
        || floor > top
        || i64::from(top) >= i64::from(min) + n as i64 * 16
        || i64::from(min) + n as i64 * 16 > i64::from(i32::MAX)
        || deep > 1
        || terrains == 0
        || bedrock < 0
        || bedrock as usize >= terrains
        || word(d, 48) as usize != 64 + n * 16
        || word(d, 56) != 256
        || word(d, 60) != 0
        || output.checked_add(4096) != Some(d.len())
        || 64 + n * 16 > output
    {
        return Err(bad);
    }
    let mut cursor = 64 + n * 16;
    for (s, indices) in sections.iter().enumerate() {
        let p = 64 + s * 16;
        let count = word(d, p + 4) as usize;
        let uniform = word(d, p + 8);
        if !(1..=65536).contains(&count)
            || word(d, p) as usize != cursor
            || uniform > if word(d, 4) == 2 { 2 } else { 1 }
            || word(d, p + 12) != 0
            || cursor
                .checked_add(count * 12)
                .is_none_or(|end| end > output)
            || (uniform == 1 && (count != 1 || !matches!(indices, SectionSource::Uniform)))
            || (uniform == 0 && !matches!(indices, SectionSource::Indices(_)))
            || (uniform == 2 && !matches!(indices, SectionSource::Packed { .. }))
        {
            return Err(bad);
        }
        match indices {
            SectionSource::Uniform => {}
            SectionSource::Indices(a) => {
                if a.len() != 4096 || a.iter().any(|&i| i < 0 || i as usize >= count) {
                    return Err(bad);
                }
            }
            SectionSource::Packed { words, bits } => {
                let expected_bits = (usize::BITS - (count - 1).leading_zeros()).max(4);
                if *bits != expected_bits
                    || words.len() != 4096usize.div_ceil(64 / *bits as usize)
                    || (0..4096).any(|i| indices.index(i) >= count)
                {
                    return Err(bad);
                }
            }
        }
        for i in 0..count {
            let q = cursor + i * 12;
            let terrain = word(d, q + 4) as i32;
            if word(d, q) & !63 != 0 || terrain < -1 || terrain >= terrains as i32 {
                return Err(bad);
            }
        }
        cursor += count * 12;
    }
    if cursor != output {
        return Err(bad);
    }
    scratch.palettes.clear();
    scratch.offsets.clear();
    for s in 0..n {
        scratch.offsets.push(scratch.palettes.len());
        let descriptor = 64 + s * 16;
        let base = word(d, descriptor) as usize;
        for i in 0..word(d, descriptor + 4) as usize {
            let p = base + i * 12;
            scratch.palettes.push(MaterialInfo {
                flags: word(d, p),
                terrain: word(d, p + 4) as i32,
                snow: word(d, p + 8) as i32,
            });
        }
    }
    let (_, result) = d.split_at_mut(output);
    let view = View {
        palettes: &scratch.palettes,
        offsets: &scratch.offsets,
        sections,
        min,
    };
    for x in 0..16 {
        for z in 0..16 {
            let mut height = -f32::MAX;
            let mut water = i32::MIN;
            let mut terrain = bedrock;
            let mut flags = 0u32;
            // Skip uniform air sections as the Java chunk does, rather than probing every air cell.
            let ceiling = (0..n).rev().find_map(|s| {
                let base = min + s as i32 * 16;
                if matches!(sections[s], SectionSource::Uniform) {
                    return (view.material(x, base, z).flags & AIR == 0).then_some(base + 15);
                }
                (0..16)
                    .rev()
                    .map(|dy| base + dy)
                    .find(|&y| view.material(x, y, z).flags & AIR == 0)
            });
            if let Some(ceiling) = ceiling {
                for y in (floor..=top.min(ceiling)).rev() {
                    let material = view.material(x, y, z);
                    if material.flags & NATURAL == 0 {
                        flags |= if height == -f32::MAX { 8 } else { 16 };
                    }
                    if material.flags & FROST != 0 {
                        flags |= 1;
                    }
                    if water == i32::MIN && material.flags & FLUID != 0 {
                        water = y;
                        if material.flags & LAVA != 0 {
                            flags |= 2;
                        }
                    } else if height == -f32::MAX && material.terrain >= 0 {
                        height = y as f32 - 0.4375f32;
                        terrain = material.terrain;
                        if water == i32::MIN {
                            water = if y >= default_water {
                                default_water
                            } else {
                                world_min
                            };
                        }
                        if deep == 0 {
                            break;
                        }
                    }
                }
            }
            let rounded = (f64::from(height) + 0.5).floor() as i32;
            if height != -f32::MAX && rounded < top {
                let above = view.material(x, rounded.saturating_add(1), z);
                if above.flags & SNOW != 0 {
                    height = (f64::from(height) + f64::from(above.snow) * 0.125f64) as f32;
                }
            }
            if water == i32::MIN {
                water = if height >= 61.5f32 {
                    default_water
                } else {
                    world_min
                };
            }
            if height == -f32::MAX {
                flags |= 4;
            }
            let p = (x * 16 + z) * 16;
            result[p..p + 4].copy_from_slice(&height.to_bits().to_le_bytes());
            result[p + 4..p + 8].copy_from_slice(&water.to_le_bytes());
            result[p + 8..p + 12].copy_from_slice(&terrain.to_le_bytes());
            result[p + 12..p + 16].copy_from_slice(&flags.to_le_bytes());
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn put(d: &mut [u8], p: usize, v: u32) {
        d[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn frame() -> Vec<u8> {
        let out = 80 + 4 * 12;
        let mut d = vec![0; out + 4096];
        let len = d.len() as u32;
        for (p, v) in [
            (0, MAGIC),
            (4, 1),
            (8, len),
            (12, 1),
            (16, 0),
            (20, 0),
            (24, 15),
            (28, 0),
            (32, 62),
            (36, 0),
            (40, 1),
            (44, 8),
            (48, 80),
            (52, out as u32),
            (56, 256),
            (64, 80),
            (68, 4),
        ] {
            put(&mut d, p, v);
        }
        for (i, (flags, terrain, snow)) in [
            (AIR | NATURAL, -1, 0),
            (NATURAL, 3, 0),
            (NATURAL | FROST | SNOW, -1, 4),
            (0, -1, 0),
        ]
        .into_iter()
        .enumerate()
        {
            let p = 80 + i * 12;
            put(&mut d, p, flags);
            put(&mut d, p + 4, terrain as u32);
            put(&mut d, p + 8, snow);
        }
        d
    }
    #[test]
    fn snow_and_both_structure_positions_match_column_order() {
        let mut d = frame();
        let mut ids = vec![0; 4096];
        for c in 0..256 {
            ids[c + 3 * 256] = 3;
            ids[c + 5 * 256] = 1;
            ids[c + 6 * 256] = 2;
            ids[c + 8 * 256] = 3;
        }
        analyze(&mut d, &[Some(&ids)]).unwrap();
        let p = word(&d, 52) as usize;
        assert_eq!(f32::from_bits(word(&d, p)), 5.0625f32);
        assert_eq!(word(&d, p + 4), 0);
        assert_eq!(word(&d, p + 8), 3);
        assert_eq!(word(&d, p + 12), 25);
    }
    #[test]
    fn early_stop_retains_only_above_ground_structure_flags() {
        let mut d = frame();
        put(&mut d, 40, 0);
        let mut ids = vec![0; 4096];
        ids[3 * 256] = 3;
        ids[5 * 256] = 1;
        ids[8 * 256] = 3;
        analyze(&mut d, &[Some(&ids)]).unwrap();
        let p = word(&d, 52) as usize;
        assert_eq!(word(&d, p + 12), 8);
        assert_eq!(word(&d, p + 16 + 12), 4);
    }
    #[test]
    fn packed_sources_match_indices_and_reuse_the_worker_scratch() {
        let mut indices = vec![0i32; 4096];
        for column in 0..256 {
            indices[column + 5 * 256] = 1;
            indices[column + 6 * 256] = 2;
            indices[column + 8 * 256] = 3;
        }
        let mut expected = frame();
        analyze(&mut expected, &[Some(&indices)]).unwrap();
        let mut words = vec![0i64; 256];
        for (i, &index) in indices.iter().enumerate() {
            words[i / 16] |= i64::from(index) << ((i % 16) * 4);
        }
        let mut scratch = Scratch::default();
        for deep in [1, 0, 1] {
            let mut reference = frame();
            put(&mut reference, 40, deep);
            analyze(&mut reference, &[Some(&indices)]).unwrap();
            let mut packed = frame();
            put(&mut packed, 4, 2);
            put(&mut packed, 40, deep);
            put(&mut packed, 72, 2);
            analyze_sources_with_scratch(
                &mut packed,
                &[SectionSource::Packed {
                    words: &words,
                    bits: 4,
                }],
                &mut scratch,
            )
            .unwrap();
            let output = word(&packed, 52) as usize;
            assert_eq!(&packed[output..], &reference[output..]);
        }
    }
    #[test]
    fn malformed_packed_sources_never_write_results() {
        let mut packed = frame();
        put(&mut packed, 4, 2);
        put(&mut packed, 72, 2);
        let before = packed.clone();
        let mut scratch = Scratch::default();
        assert!(analyze_sources_with_scratch(
            &mut packed,
            &[SectionSource::Packed {
                words: &[0; 255],
                bits: 4
            }],
            &mut scratch
        )
        .is_err());
        assert_eq!(packed, before);
        let mut words = [0i64; 256];
        words[255] = 15i64 << 60;
        assert!(analyze_sources_with_scratch(
            &mut packed,
            &[SectionSource::Packed {
                words: &words,
                bits: 4
            }],
            &mut scratch
        )
        .is_err());
        assert_eq!(packed, before);
    }
    #[test]
    fn invalid_index_and_metadata_preserve_the_output() {
        let mut d = frame();
        let before = d.clone();
        let mut ids = vec![0; 4096];
        ids[300] = 4;
        assert!(analyze(&mut d, &[Some(&ids)]).is_err());
        assert_eq!(d, before);
        ids[300] = 0;
        put(&mut d, 60, 1);
        let before = d.clone();
        assert!(analyze(&mut d, &[Some(&ids)]).is_err());
        assert_eq!(d, before);
    }
}
