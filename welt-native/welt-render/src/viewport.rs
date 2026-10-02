//! WVR1: complete tile colours, compact overlays, contours and terrain/fluid lighting.
use crate::shade::java_multiply;

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
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
}
impl Plane {
    const EMPTY: Self = Self {
        bits: 0,
        kind: 0,
        colour: 0,
        offset: 0,
        pattern: 0,
        frost: false,
    };
    fn value(self, data: &[u8], x: usize, y: usize) -> i32 {
        let index = if self.bits == 0 {
            x / 16 + y / 16 * 8
        } else {
            x + y * 128
        };
        match self.bits {
            0 | 1 => ((data[self.offset + index / 8] >> (index & 7)) & 1) as i32,
            4 => ((data[self.offset + index / 2] >> ((index & 1) * 4)) & 15) as i32,
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
fn bytes(bits: usize) -> usize {
    if bits == 0 {
        8
    } else {
        AREA * bits / 8
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

pub fn render(data: &mut [u8]) -> Result<(), RenderError> {
    let bad = RenderError::InvalidFrame;
    if data.len() < 128 || data.len() > MAX_BYTES || int(data, 0) != 0x31525657 || int(data, 4) != 1
    {
        return Err(bad);
    }
    let count = int(data, 8) as usize;
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
    let heights = terrain + AREA;
    let wet = heights + HALO * 4;
    let mut cursor = wet + HALO * 4;
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
        if !matches!(bits, 0 | 1 | 4)
            || !(0..=4).contains(&kind)
            || int(data, p + 12) as usize != cursor
            || !matches!(int(data, p + 24), 0 | 1)
            || int(data, p + 28) != 0
            || matches!(kind, 2 | 3) && bits == 4
            || kind == 4 && bits != 4
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
        };
        cursor += bytes(bits);
    }
    let output = cursor;
    if int(data, 64) as usize != output
        || output + AREA * 4 != data.len()
        || data[terrain..terrain + AREA]
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
    for y in 0..128 {
        for x in 0..128 {
            let cell = x + y * 128;
            let halo = x + 1 + (y + 1) * 130;
            let set = |index: i32| index >= 0 && planes[index as usize].value(data, x, y) != 0;
            let colour = if set(masks[1]) || set(masks[2]) {
                int(data, 48)
            } else if set(masks[0]) {
                int(data, 44)
            } else {
                let h = int(data, heights + halo * 4);
                let neighbors = [halo - 130, halo - 1, halo + 1, halo + 130];
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
                        let value = plane.value(data, x, y);
                        if value == 0 || hide_fluids && flooded && plane.frost {
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
            (4, 2_i32),
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
}
