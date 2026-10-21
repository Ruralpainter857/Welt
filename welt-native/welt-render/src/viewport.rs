//! WVR versions 1 through 4: complete tile composition with compact overlay planes.
use crate::shade::java_multiply;

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
#[cfg(test)]
const AREA: usize = 16384;
const HALO: usize = 130 * 130;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RenderError {
    InvalidFrame,
}
#[derive(Clone, Copy)]
struct Plane {
    bits: usize,
    kind: i32,
    colour: i32,
    offset: usize,
    pattern: u64,
    frost: bool,
    lookup: usize,
    paint_width: usize,
    paint_height: usize,
    opacity: f32,
}
impl Plane {
    const EMPTY: Self = Self {
        bits: 0,
        kind: 0,
        colour: 0,
        offset: 0,
        pattern: 0,
        frost: false,
        lookup: 0,
        paint_width: 0,
        paint_height: 0,
        opacity: 0.0,
    };
    fn value(self, data: &[u8], x: usize, y: usize, cell: usize) -> i32 {
        let index = if self.bits == 0 {
            x / 16 + y / 16 * 8
        } else {
            cell
        };
        match self.bits {
            0 | 1 => ((data[self.offset + index / 8] >> (index & 7)) & 1) as i32,
            4 => ((data[self.offset + index / 2] >> ((index & 1) * 4)) & 15) as i32,
            8 => data[self.offset + index] as i32,
            _ => unreachable!(),
        }
    }
}
fn int(data: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(data[p..p + 4].try_into().unwrap())
}
fn word(data: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(data[p..p + 8].try_into().unwrap())
}
fn bytes(bits: usize, area: usize) -> usize {
    if bits == 0 {
        8
    } else {
        (area * bits).div_ceil(8)
    }
}
fn brighten(h: [i32; 4], light: i32) -> i32 {
    let [north, west, east, south] = h;
    let value = match light {
        0 => east
            .wrapping_sub(west)
            .wrapping_add(south)
            .wrapping_sub(north),
        1 => west
            .wrapping_sub(east)
            .wrapping_add(south)
            .wrapping_sub(north),
        2 => west
            .wrapping_sub(east)
            .wrapping_add(north)
            .wrapping_sub(south),
        3 => east
            .wrapping_sub(west)
            .wrapping_add(north)
            .wrapping_sub(south),
        _ => return 256,
    };
    value.wrapping_shl(5).wrapping_add(256).max(0)
}
fn mix(a: i32, b: i32, alpha: i32, half: bool) -> i32 {
    let mut result = 0;
    for shift in [0, 8, 16] {
        let c1 = (a >> shift) & 255;
        let c2 = (b >> shift) & 255;
        let c = if half {
            (c1 >> 1) + (c2 >> 1)
        } else {
            (c1 * alpha + c2 * (256 - alpha)) / 256
        };
        result |= c << shift;
    }
    result
}
fn frost(colour: i32) -> i32 {
    let mut result = 0;
    for shift in [0, 8, 16] {
        result |= (255 - (255 - ((colour >> shift) & 255)) / 2) << shift;
    }
    result
}

fn java_round(value: f32) -> i32 {
    let bits = value.to_bits();
    let shift = 149 - ((bits >> 23) & 255) as i32;
    if (0..32).contains(&shift) {
        let mantissa = ((bits & 0x007fffff) | 0x00800000) as i32;
        let signed = if bits & 0x80000000 == 0 {
            mantissa
        } else {
            -mantissa
        };
        ((signed >> shift) + 1) >> 1
    } else {
        // Float-to-int saturation also matches Java for NaN and infinities.
        value as i32
    }
}
fn paint(plane: Plane, data: &[u8], x: i32, y: i32, colour: i32, value: i32) -> i32 {
    if plane.paint_width == 0 {
        let alpha = if plane.opacity < 1.0 {
            java_round(value as f32 * plane.opacity * 256.0 / 15.0)
        } else {
            value * 256 / 15
        };
        return mix(int(data, plane.lookup + 8), colour, alpha, false);
    }
    // Preserve PaintRenderer's historical x * width + y indexing, including tall textures.
    let cell = x.rem_euclid(plane.paint_width as i32) as usize * plane.paint_width
        + y.rem_euclid(plane.paint_height as i32) as usize;
    let p = plane.lookup + 16 + cell * 16;
    let alpha = f32::from_bits(int(data, p + 12) as u32) * plane.opacity * value as f32 / 15.0;
    let mut result = 0;
    for (offset, shift) in [(0, 16), (4, 8), (8, 0)] {
        let channel =
            alpha * int(data, p + offset) as f32 + (1.0 - alpha) * ((colour >> shift) & 255) as f32;
        result |= java_round(channel).wrapping_shl(shift);
    }
    result
}

pub fn render(data: &mut [u8]) -> Result<(), RenderError> {
    let bad = RenderError::InvalidFrame;
    if data.len() < 128
        || data.len() > MAX_BYTES
        || int(data, 0) != 0x31525657
        || !matches!(int(data, 4), 1..=4)
    {
        return Err(bad);
    }
    let count = int(data, 8) as usize;
    let version = int(data, 4);
    let shift = if version == 4 {
        let shift = int(data, 104);
        if !(1..=7).contains(&shift) {
            return Err(bad);
        }
        shift as u32
    } else {
        0
    };
    let width = 128 >> shift;
    let area = width * width;
    let compact = version == 4;
    let palette_count = int(data, 12) as usize;
    let flags = int(data, 20);
    let separation = int(data, 24);
    let light = int(data, 28);
    let visible = int(data, 76) as usize;
    if count > 128
        || visible > count
        || !(1..=256).contains(&palette_count)
        || !(0..=7).contains(&flags)
        || !(0..=4).contains(&light)
        || flags & 1 != 0 && separation == 0
    {
        return Err(bad);
    }
    let palette = 128;
    let table = palette + palette_count * 4;
    let terrain = table + count * 32;
    let heights = terrain + area;
    let height_bytes = if compact { area * 20 } else { HALO * 4 };
    let wet = heights + height_bytes;
    let mut cursor = wet + height_bytes;
    if cursor > data.len()
        || int(data, 52) as usize != terrain
        || int(data, 56) as usize != heights
        || int(data, 60) as usize != wet
        || int(data, 68) as usize != palette
        || int(data, 72) as usize != table
    {
        return Err(bad);
    }
    let mut planes = [Plane::EMPTY; 128];
    for (i, plane) in planes.iter_mut().enumerate().take(count) {
        let p = table + i * 32;
        let bits = int(data, p) as usize;
        let kind = int(data, p + 4);
        if !matches!(bits, 0 | 1 | 4 | 8)
            || !(0..=if version == 1 {
                4
            } else if version == 2 {
                6
            } else {
                7
            })
                .contains(&kind)
            || int(data, p + 12) as usize != cursor
            || !matches!(int(data, p + 24), 0 | 1)
            || kind <= 4 && int(data, p + 28) != 0
            || matches!(kind, 2 | 3) && bits == 4
            || kind == 4 && bits != 4
            || kind == 5 && bits != 4
            || kind == 6 && bits != 8
            || bits == 8 && kind != 6
            || i < visible && kind == 0
            || i >= visible && kind != 0
        {
            return Err(bad);
        }
        *plane = Plane {
            bits,
            kind,
            colour: int(data, p + 8),
            offset: cursor,
            pattern: word(data, p + 16),
            frost: int(data, p + 24) != 0,
            lookup: int(data, p + 28) as usize,
            paint_width: 0,
            paint_height: 0,
            opacity: 0.0,
        };
        cursor += bytes(bits, area);
    }
    for plane in planes.iter_mut().take(count) {
        match plane.kind {
            5 => {
                if plane.lookup != cursor || cursor + 64 > data.len() {
                    return Err(bad);
                }
                cursor += 64;
            }
            6 => {
                if plane.lookup != cursor || cursor + 1024 > data.len() {
                    return Err(bad);
                }
                cursor += 1024;
                for value in 0..256 {
                    let pattern = int(data, plane.lookup + value * 4) as usize;
                    if pattern == 0 {
                        continue;
                    }
                    if value == 255 || pattern != cursor || cursor + 1024 > data.len() {
                        return Err(bad);
                    }
                    cursor += 1024;
                }
            }
            7 => {
                if plane.lookup != cursor || cursor + 16 > data.len() {
                    return Err(bad);
                }
                let w = int(data, cursor) as usize;
                let h = int(data, cursor + 4) as usize;
                let opacity = f32::from_bits(int(data, cursor + 12) as u32);
                if (w == 0) != (h == 0)
                    || w > h
                    || h > 65536
                    || w * h > 65536
                    || (!opacity.is_nan() && !(0.0..=1.0).contains(&opacity))
                {
                    return Err(bad);
                }
                cursor += 16 + w * h * 16;
                if cursor > data.len() {
                    return Err(bad);
                }
                plane.paint_width = w;
                plane.paint_height = h;
                plane.opacity = opacity;
            }
            _ => (),
        }
    }
    let output = cursor;
    if int(data, 64) as usize != output
        || output + area * 4 != data.len()
        || data[terrain..terrain + area]
            .iter()
            .any(|&v| v as usize >= palette_count)
    {
        return Err(bad);
    }
    let mut masks = [-1; 4];
    for (i, mask) in masks.iter_mut().enumerate() {
        let index = int(data, 80 + i * 4);
        if index < -1 || index >= count as i32 || index >= 0 && planes[index as usize].bits > 1 {
            return Err(bad);
        }
        *mask = index;
    }
    // All descriptors and bounds are validated before the first output write.
    let min = int(data, 16);
    let hide_fluids = flags & 2 != 0;
    let origin_x = if version >= 3 { int(data, 96) } else { 0 };
    let origin_y = if version >= 3 { int(data, 100) } else { 0 };
    for row in 0..width {
        for col in 0..width {
            let x = col << shift;
            let y = row << shift;
            let cell = col + row * width;
            let halo = if compact {
                cell * 5
            } else {
                x + 1 + (y + 1) * 130
            };
            let set =
                |index: i32| index >= 0 && planes[index as usize].value(data, x, y, cell) != 0;
            let colour = if set(masks[1]) || set(masks[2]) {
                int(data, 48)
            } else if set(masks[0]) {
                int(data, 44)
            } else {
                let h = int(data, heights + halo * 4);
                let neighbors = if compact {
                    [halo + 1, halo + 2, halo + 3, halo + 4]
                } else {
                    [halo - 130, halo - 1, halo + 1, halo + 130]
                };
                let mut delta = [0; 4];
                for i in 0..4 {
                    delta[i] = int(data, heights + neighbors[i] * 4).wrapping_sub(h);
                }
                let water = int(data, wet + halo * 4);
                let flooded = water != i32::MIN;
                let contour = flags & 1 != 0
                    && h.wrapping_rem(separation) == 0
                    && delta.iter().any(|&d| d < 0);
                let mut rgb = if contour {
                    0
                } else if !hide_fluids && flooded {
                    int(data, if set(masks[3]) { 36 } else { 32 })
                } else if flags & 4 == 0 && h == min {
                    int(data, 40)
                } else {
                    int(data, palette + data[terrain + cell] as usize * 4)
                };
                if !contour {
                    for plane in planes.iter().take(visible) {
                        let value = plane.value(data, x, y, cell);
                        if value == 0 && plane.bits != 8 || hide_fluids && flooded && plane.frost {
                            continue;
                        }
                        rgb = match plane.kind {
                            1 => mix(plane.colour, rgb, value * 256 / 15, plane.bits <= 1),
                            2 => frost(rgb),
                            3 => {
                                if (x & 15) == (y & 15) || (x & 15) == 15 - (y & 15) {
                                    0
                                } else {
                                    rgb
                                }
                            }
                            4 => {
                                if plane.pattern & (1u64 << ((x & 7) + (y & 7) * 8)) != 0 {
                                    mix(plane.colour, rgb, value * 256 / 15, false)
                                } else {
                                    rgb
                                }
                            }
                            5 => int(data, plane.lookup + value as usize * 4),
                            6 => {
                                let pattern = int(data, plane.lookup + value as usize * 4) as usize;
                                if pattern == 0 {
                                    rgb
                                } else {
                                    let pixel = int(data, pattern + ((x & 15) + (y & 15) * 16) * 4);
                                    if pixel as u32 & 0xff000000 == 0 {
                                        rgb
                                    } else {
                                        mix(pixel, rgb, 128, true)
                                    }
                                }
                            }
                            7 => paint(
                                *plane,
                                data,
                                origin_x.wrapping_add(x as i32),
                                origin_y.wrapping_add(y as i32),
                                rgb,
                                if plane.bits <= 1 { value * 15 } else { value },
                            ),
                            _ => unreachable!(),
                        };
                    }
                }
                rgb = java_multiply(rgb, brighten(delta, light));
                if flooded {
                    for i in 0..4 {
                        let neighbor = int(data, wet + neighbors[i] * 4);
                        delta[i] = if neighbor == i32::MIN {
                            0
                        } else {
                            neighbor.wrapping_sub(water)
                        };
                    }
                    rgb = java_multiply(rgb, brighten(delta, light));
                }
                rgb | 0xff000000u32 as i32
            };
            data[output + cell * 4..output + cell * 4 + 4].copy_from_slice(&colour.to_le_bytes());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let terrain = 132;
        let heights = terrain + AREA;
        let wet = heights + HALO * 4;
        let output = wet + HALO * 4;
        let mut data = vec![0; output + AREA * 4];
        for (p, v) in [
            (0, 0x31525657),
            (4, 1),
            (12, 1),
            (28, 4),
            (52, terrain as i32),
            (56, heights as i32),
            (60, wet as i32),
            (64, output as i32),
            (68, 128),
            (72, 132),
            (80, -1),
            (84, -1),
            (88, -1),
            (92, -1),
            (128, 0x123456),
        ] {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for i in 0..HALO {
            data[heights + i * 4..heights + i * 4 + 4].copy_from_slice(&64i32.to_le_bytes());
            data[wet + i * 4..wet + i * 4 + 4].copy_from_slice(&i32::MIN.to_le_bytes());
        }
        data
    }
    #[test]
    fn complete_flat_tile_is_opaque_and_invalid_frames_are_atomic() {
        let valid = fixture();
        let mut data = valid.clone();
        render(&mut data).unwrap();
        let output = int(&data, 64) as usize;
        for i in 0..AREA {
            assert_eq!(int(&data, output + i * 4) as u32, 0xff123456);
        }
        for (p, v) in [
            (4, 5_i32),
            (8, 129),
            (12, 257),
            (28, 5),
            (52, 0),
            (64, 0),
            (80, 0),
        ] {
            let mut data = valid.clone();
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = data.clone();
            assert!(render(&mut data).is_err());
            assert_eq!(data, before);
        }
        let mut data = valid;
        data[132] = 1;
        let before = data.clone();
        assert!(render(&mut data).is_err());
        assert_eq!(data, before);
    }
    #[test]
    fn custom_paint_frames_are_bounded_and_round_like_java() {
        assert_eq!(java_round(127.49999), 127);
        assert_eq!(java_round(127.5), 128);
        assert_eq!(java_round(-0.5), 0);
        assert_eq!(java_round(f32::NAN), 0);
        let mut bits = 123_u32;
        for _ in 0..65536 {
            bits = bits.wrapping_mul(1664525).wrapping_add(1013904223);
            let value = f32::from_bits(bits);
            assert_eq!(java_round(value), (f64::from(value) + 0.5).floor() as i32);
        }
        let terrain = 164;
        let heights = terrain + AREA;
        let wet = heights + HALO * 4;
        let plane = wet + HALO * 4;
        let lookup = plane + AREA / 2;
        let output = lookup + 16 + 16;
        let mut data = vec![0; output + AREA * 4];
        for (p, v) in [
            (0, 0x31525657),
            (4, 3),
            (8, 1),
            (12, 1),
            (28, 4),
            (52, terrain as i32),
            (56, heights as i32),
            (60, wet as i32),
            (64, output as i32),
            (68, 128),
            (72, 132),
            (76, 1),
            (80, -1),
            (84, -1),
            (88, -1),
            (92, -1),
            (96, -128),
            (100, -128),
            (128, 0x123456),
            (132, 4),
            (136, 7),
            (144, plane as i32),
            (160, lookup as i32),
            (lookup, 1),
            (lookup + 4, 1),
            (lookup + 12, 1.0_f32.to_bits() as i32),
            (lookup + 16, 255),
            (lookup + 20, 255),
            (lookup + 24, 255),
            (lookup + 28, 1.0_f32.to_bits() as i32),
        ] {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for i in 0..HALO {
            data[heights + i * 4..heights + i * 4 + 4].copy_from_slice(&64_i32.to_le_bytes());
            data[wet + i * 4..wet + i * 4 + 4].copy_from_slice(&i32::MIN.to_le_bytes());
        }
        data[plane] = 15;
        let valid = data.clone();
        render(&mut data).unwrap();
        assert_eq!(int(&data, output) as u32, 0xffffffff);
        assert_eq!(int(&data, output + 4) as u32, 0xff123456);
        for (p, v) in [
            (lookup, -1),
            (lookup, 2),
            (lookup + 4, 0),
            (lookup + 4, 65537),
            (lookup + 12, 2.0_f32.to_bits() as i32),
            (160, 0),
        ] {
            let mut invalid = valid.clone();
            invalid[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = invalid.clone();
            assert!(render(&mut invalid).is_err());
            assert_eq!(invalid, before);
        }
    }

    #[test]
    fn compact_frames_preserve_single_cell_planes_and_reject_bad_shapes() {
        for shift in 1..=7 {
            for bits in [0, 1, 4] {
                let width = 128 >> shift;
                let area = width * width;
                let terrain = 164;
                let heights = terrain + area;
                let wet = heights + area * 20;
                let plane = wet + area * 20;
                let output = plane + bytes(bits, area);
                let mut data = vec![0; output + area * 4];
                for (p, v) in [
                    (0, 0x31525657),
                    (4, 4),
                    (8, 1),
                    (12, 1),
                    (28, 4),
                    (52, terrain as i32),
                    (56, heights as i32),
                    (60, wet as i32),
                    (64, output as i32),
                    (68, 128),
                    (72, 132),
                    (76, 1),
                    (80, -1),
                    (84, -1),
                    (88, -1),
                    (92, -1),
                    (104, shift),
                    (128, 0x123456),
                    (132, bits as i32),
                    (136, 1),
                    (140, 0xffffff),
                    (144, plane as i32),
                ] {
                    data[p..p + 4].copy_from_slice(&v.to_le_bytes());
                }
                for point in 0..area * 5 {
                    data[heights + point * 4..heights + point * 4 + 4]
                        .copy_from_slice(&64_i32.to_le_bytes());
                    data[wet + point * 4..wet + point * 4 + 4]
                        .copy_from_slice(&i32::MIN.to_le_bytes());
                }
                data[plane] = if bits == 4 { 15 } else { 1 };
                let valid = data.clone();
                render(&mut data).unwrap();
                assert_eq!(
                    int(&data, output) as u32,
                    if bits == 4 {
                        0xffffffff
                    } else {
                        0xff000000 | mix(0xffffff, 0x123456, 128, true) as u32
                    }
                );
                for invalid_shift in [0_i32, 8, -1] {
                    let mut invalid = valid.clone();
                    invalid[104..108].copy_from_slice(&invalid_shift.to_le_bytes());
                    let before = invalid.clone();
                    assert!(render(&mut invalid).is_err());
                    assert_eq!(invalid, before);
                }
                let mut truncated = valid[..valid.len() - 1].to_vec();
                let before = truncated.clone();
                assert!(render(&mut truncated).is_err());
                assert_eq!(truncated, before);
            }
        }
    }

    #[test]
    fn half_mix_and_frost_use_different_java_rounding() {
        assert_eq!(mix(0x010101, 0x010101, 128, true), 0);
        assert_eq!(mix(0x010101, 0x010101, 128, false), 0x010101);
        assert_eq!(frost(0), 0x808080);
    }
    #[test]
    fn lighting_keeps_java_wrapped_integer_arithmetic() {
        assert_eq!(brighten([0, 0, 1, 0], 0), 288);
        assert_eq!(brighten([0, 0, 1, 0], 1), 224);
        assert_eq!(brighten([i32::MIN, 0, i32::MAX, 1], 0), 256);
    }
    #[test]
    fn indexed_tables_preserve_biome_zero_alpha_and_annotation_priority() {
        let terrain = 132 + 64;
        let heights = terrain + AREA;
        let wet = heights + HALO * 4;
        let nibble = wet + HALO * 4;
        let biome = nibble + AREA / 2;
        let annotation_lookup = biome + AREA;
        let biome_lookup = annotation_lookup + 64;
        let pattern = biome_lookup + 1024;
        let output = pattern + 1024;
        let mut data = vec![0; output + AREA * 4];
        for (p, v) in [
            (0, 0x31525657),
            (4, 2),
            (8, 2),
            (12, 1),
            (28, 4),
            (52, terrain as i32),
            (56, heights as i32),
            (60, wet as i32),
            (64, output as i32),
            (68, 128),
            (72, 132),
            (76, 2),
            (80, -1),
            (84, -1),
            (88, -1),
            (92, -1),
            (128, 0x123456),
            (132, 4),
            (136, 5),
            (144, nibble as i32),
            (160, annotation_lookup as i32),
            (164, 8),
            (168, 6),
            (176, biome as i32),
            (192, biome_lookup as i32),
            (annotation_lookup + 4, 0x010101),
            (biome_lookup, pattern as i32),
        ] {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for i in 0..HALO {
            data[heights + i * 4..heights + i * 4 + 4].copy_from_slice(&64i32.to_le_bytes());
            data[wet + i * 4..wet + i * 4 + 4].copy_from_slice(&i32::MIN.to_le_bytes());
        }
        data[nibble] = 1;
        data[biome + 2] = 255;
        data[pattern..pattern + 4].copy_from_slice(&0x01030507i32.to_le_bytes());
        data[pattern + 4..pattern + 8].copy_from_slice(&0x00030507i32.to_le_bytes());
        let valid = data.clone();
        render(&mut data).unwrap();
        assert_eq!(int(&data, output) as u32, 0xff010203);
        assert_eq!(int(&data, output + 4) as u32, 0xff123456);
        assert_eq!(int(&data, output + 8) as u32, 0xff123456);
        for (p, v) in [
            (160, 0),
            (192, 0),
            (biome_lookup, -1),
            (biome_lookup + 255 * 4, pattern as i32),
        ] {
            let mut invalid = valid.clone();
            invalid[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = invalid.clone();
            assert!(render(&mut invalid).is_err());
            assert_eq!(invalid, before);
        }
    }
}
