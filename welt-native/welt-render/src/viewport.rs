//! WVR versions 1 through 10: tile composition, compact overlays and deterministic terrain fields.
//!
//! Versions 7 to 10 are versions 3 to 6 plus a linked surface intersection (ceiling/floor dimensions or tunnel
//! roofs), described by a header extension at bytes 128..208 and one plane of linked heights.
use crate::shade::java_multiply;

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
#[cfg(test)]
const AREA: usize = 16384;
/// Cells of a full resolution tile plane (the linked heights are always full resolution).
const AREA_FULL: usize = 16384;
const HALO: usize = 130 * 130;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RenderError {
    InvalidFrame,
}
/// Parameters of a Java `NoiseHeightMap` (its `getHeight(int, int)`), with the seed already combined with the offset.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NoiseParams {
    pub d_height: f64,
    pub scale: f64,
    pub octaves: i32,
    pub seed: i64,
}

/// Evaluates tunnel roof noise for an intersection frame. Implemented by the JNI layer, which owns the noise code.
pub trait NoiseSource {
    /// Fill `output[col + row * width]` with `(int) noise.getHeight(origin_x + (col << shift), origin_y + (row << shift))`
    /// (Java's saturating cast). Returns false when the parameters are not supported.
    fn fill(
        &self,
        params: &NoiseParams,
        origin_x: i32,
        origin_y: i32,
        width: usize,
        shift: u32,
        output: &mut [i32],
    ) -> bool;
}

/// The linked surface intersection of a version 7..10 frame.
#[derive(Clone, Copy)]
enum Intersection {
    None,
    Ceiling {
        linked: usize,
        reflection: i32,
    },
    TunnelRoof {
        linked: usize,
        mode: i32,
        level: i32,
        min: i32,
        max: i32,
        min_z: i32,
        max_z: i32,
        noise: Option<(NoiseParams, i32)>,
    },
}

/// Java's `TileRenderer.CEILING_PATTERN`, indexed `[x & 7][y & 7]`.
const CEILING_PATTERN: [[bool; 8]; 8] = [
    [true, true, false, false, true, true, false, false],
    [false, true, true, true, true, false, false, false],
    [false, false, true, true, false, false, false, false],
    [false, true, true, true, true, false, false, false],
    [true, true, false, false, true, true, false, false],
    [true, false, false, false, false, true, true, true],
    [false, false, false, false, false, false, true, true],
    [true, false, false, false, false, true, true, true],
];

/// Java's `MathUtils.clamp(int, int, int)`.
fn clamp(min: i32, value: i32, max: i32) -> i32 {
    if value < min {
        min
    } else if value > max {
        max
    } else {
        value
    }
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

#[derive(Default)]
pub struct Workspace {
    materials: Vec<welt_core::material_field::Workspace>,
    noise: Vec<i32>,
}

pub fn render(data: &mut [u8]) -> Result<(), RenderError> {
    render_with_workspace(data, &mut Workspace::default())
}

pub fn render_with_workspace(data: &mut [u8], worker: &mut Workspace) -> Result<(), RenderError> {
    render_with_noise(data, worker, None)
}

/// Renders a frame; tunnel roof intersections with noise require a `noise` source and are rejected without one.
pub fn render_with_noise(
    data: &mut [u8],
    worker: &mut Workspace,
    noise: Option<&dyn NoiseSource>,
) -> Result<(), RenderError> {
    let bad = RenderError::InvalidFrame;
    if data.len() < 128
        || data.len() > MAX_BYTES
        || int(data, 0) != 0x31525657
        || !matches!(int(data, 4), 1..=10)
    {
        return Err(bad);
    }
    let count = int(data, 8) as usize;
    // Versions 7..10 are the intersection variants of 3..6; `version` keeps meaning the base layout below
    let extended = int(data, 4) >= 7;
    let version = if extended {
        int(data, 4) - 4
    } else {
        int(data, 4)
    };
    if extended && data.len() < 208 {
        return Err(bad);
    }
    let shift = if matches!(version, 4 | 6) {
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
    let compact = matches!(version, 4 | 6);
    let palette_count = int(data, 12) as usize;
    let flags = int(data, 20);
    let separation = int(data, 24);
    let light = int(data, 28);
    let visible = int(data, 76) as usize;
    if count > 128
        || visible > count
        || !(1..=256).contains(&palette_count)
        || !(0..=if version >= 5 { 15 } else { 7 }).contains(&flags)
        || !(0..=4).contains(&light)
        || flags & 1 != 0 && separation == 0
    {
        return Err(bad);
    }
    let palette = if extended { 208 } else { 128 };
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
    let mut selector_offsets = [(0_usize, 0_usize); 256];
    let mut selector_terrains = [0_usize; 256];
    let mut selector_count = 0_usize;
    let mut surfaces = 0_usize;
    if version >= 5 {
        selector_count = int(data, 124) as usize;
        if !(1..=palette_count).contains(&selector_count)
            || int(data, 108) as usize != cursor
            || cursor + selector_count * 12 > data.len()
        {
            return Err(bad);
        }
        let entries = cursor;
        cursor += selector_count * 12;
        let mut used = [false; 256];
        let mut noise_budget = 0;
        for slot in 0..selector_count {
            let p = entries + slot * 12;
            let terrain = int(data, p) as usize;
            let offset = int(data, p + 4) as usize;
            let size = int(data, p + 8) as usize;
            if terrain >= palette_count
                || used[terrain]
                || offset != cursor
                || size < 96
                || size > data.len() - cursor
            {
                return Err(bad);
            }
            let model =
                welt_core::material_field::Field::read(&data[offset..offset + size]).ok_or(bad)?;
            noise_budget += model.noise_count();
            if noise_budget > 512 {
                return Err(bad);
            }
            selector_offsets[slot] = (offset, size);
            selector_terrains[slot] = terrain;
            used[terrain] = true;
            cursor += size;
        }
        surfaces = cursor;
        if int(data, 120) as usize != surfaces || surfaces + area * 4 > data.len() {
            return Err(bad);
        }
        cursor += area * 4;
        let ox = int(data, 96) as f64;
        let oy = int(data, 100) as f64;
        let mut domain = ox
            .abs()
            .max((ox + 127.0).abs())
            .max(oy.abs())
            .max((oy + 127.0).abs());
        for cell in 0..area {
            let value = f32::from_bits(int(data, surfaces + cell * 4) as u32);
            let x = (cell % width) << shift;
            let y = (cell / width) << shift;
            let at = if compact {
                cell * 5
            } else {
                x + 1 + (y + 1) * 130
            };
            if !value.is_finite() || java_round(value) != int(data, heights + at * 4) {
                return Err(bad);
            }
            domain = domain.max(value.abs() as f64);
        }
        for (offset, size) in selector_offsets.iter().copied().take(selector_count) {
            if !welt_core::material_field::Field::read(&data[offset..offset + size])
                .ok_or(bad)?
                .supports_domain(domain)
            {
                return Err(bad);
            }
        }
    }
    let mut intersection = Intersection::None;
    if extended {
        let mode = int(data, 128);
        let linked = int(data, 132) as usize;
        if !matches!(mode, 1 | 2)
            || linked != cursor
            || cursor + AREA_FULL * 4 > data.len()
            || data[200..208].iter().any(|&b| b != 0)
        {
            return Err(bad);
        }
        cursor += AREA_FULL * 4;
        intersection = if mode == 1 {
            if data[140..200].iter().any(|&b| b != 0) {
                return Err(bad);
            }
            Intersection::Ceiling {
                linked,
                reflection: int(data, 136),
            }
        } else {
            let roof_mode = int(data, 136);
            let noise_flag = int(data, 160);
            if !(0..=2).contains(&roof_mode) || !matches!(noise_flag, 0 | 1) {
                return Err(bad);
            }
            let noise_params = if noise_flag == 1 {
                let params = NoiseParams {
                    d_height: f64::from_bits(word(data, 168)),
                    scale: f64::from_bits(word(data, 176)),
                    octaves: int(data, 164),
                    seed: word(data, 184) as i64,
                };
                if !(1..=10).contains(&params.octaves)
                    || !params.d_height.is_finite()
                    || !params.scale.is_finite()
                    || params.scale == 0.0
                {
                    return Err(bad);
                }
                Some((params, int(data, 192)))
            } else {
                if data[164..196].iter().any(|&b| b != 0) {
                    return Err(bad);
                }
                None
            };
            if data[196..200].iter().any(|&b| b != 0) {
                return Err(bad);
            }
            Intersection::TunnelRoof {
                linked,
                mode: roof_mode,
                level: int(data, 140),
                min: int(data, 144),
                max: int(data, 148),
                min_z: int(data, 152),
                max_z: int(data, 156),
                noise: noise_params,
            }
        };
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
    let origin_x = if version >= 3 { int(data, 96) } else { 0 };
    let origin_y = if version >= 3 { int(data, 100) } else { 0 };
    if let Intersection::TunnelRoof {
        noise: Some((params, _)),
        ..
    } = intersection
    {
        // Evaluated into the worker's buffer before the first output write; refused noise rejects the frame
        let source = noise.ok_or(bad)?;
        worker.noise.resize(area, 0);
        if !source.fill(
            &params,
            origin_x,
            origin_y,
            width,
            shift,
            &mut worker.noise[..area],
        ) {
            return Err(bad);
        }
    }
    // All descriptors and bounds are validated before the first output write.
    let min = int(data, 16);
    let hide_fluids = flags & 2 != 0;
    worker
        .materials
        .resize_with(selector_count, Default::default);
    worker.materials.truncate(selector_count);
    let (inputs, pixels) = data.split_at_mut(output);
    let data = &*inputs;
    let mut fields = [None; 256];
    let seed = if version >= 5 {
        word(data, 112) as i64
    } else {
        0
    };
    for slot in 0..selector_count {
        let (offset, size) = selector_offsets[slot];
        let model =
            welt_core::material_field::Field::read(&data[offset..offset + size]).ok_or(bad)?;
        model.prepare(seed, &mut worker.materials[slot]);
        fields[selector_terrains[slot]] = Some((model, slot));
    }
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
            } else if intersects(
                &intersection,
                data,
                &worker.noise,
                heights + halo * 4,
                x,
                y,
                cell,
            ) && (compact || CEILING_PATTERN[x & 7][y & 7])
            {
                // Java writes opaque black without shading, the full block when zoomed out
                0xff000000u32 as i32
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
                    let ordinal = data[terrain + cell] as usize;
                    if let Some((model, slot)) = fields[ordinal] {
                        model.colour(
                            &worker.materials[slot],
                            origin_x.wrapping_add(x as i32),
                            origin_y.wrapping_add(y as i32),
                            f32::from_bits(int(data, surfaces + cell * 4) as u32),
                            h,
                            flags & 8 != 0,
                        )
                    } else {
                        int(data, palette + ordinal * 4)
                    }
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
            pixels[cell * 4..cell * 4 + 4].copy_from_slice(&colour.to_le_bytes());
        }
    }
    Ok(())
}

/// Java's `oppositesOverlap` for one rendered cell at tile coordinates (x, y).
fn intersects(
    intersection: &Intersection,
    data: &[u8],
    noise: &[i32],
    height_at: usize,
    x: usize,
    y: usize,
    cell: usize,
) -> bool {
    match *intersection {
        Intersection::None => false,
        Intersection::Ceiling { linked, reflection } => {
            reflection.wrapping_sub(int(data, linked + (x + y * 128) * 4)) <= int(data, height_at)
        }
        Intersection::TunnelRoof {
            linked,
            mode,
            level,
            min,
            max,
            min_z,
            max_z,
            noise: noise_settings,
        } => {
            let terrain = int(data, linked + (x + y * 128) * 4);
            let offset = match noise_settings {
                Some((_, noise_offset)) => noise[cell].wrapping_sub(noise_offset),
                None => 0,
            };
            int(data, height_at) >= roof_level(mode, terrain, level, min, max, min_z, max_z, offset)
        }
    }
}

/// Java's `TunnelLayerHelper.calculateRoofLevel()` for CONSTANT_DEPTH (0), FIXED_HEIGHT (1) and INVERTED_DEPTH (2),
/// with `offset` the roof noise term, in int arithmetic.
#[allow(clippy::too_many_arguments)]
fn roof_level(
    mode: i32,
    terrain: i32,
    level: i32,
    min: i32,
    max: i32,
    min_z: i32,
    max_z: i32,
    offset: i32,
) -> i32 {
    match mode {
        0 => clamp(
            min_z,
            clamp(min, terrain.wrapping_sub(level), max).wrapping_add(offset),
            max_z,
        ),
        1 => clamp(min_z, level.wrapping_add(offset), max_z),
        _ => clamp(
            min_z,
            clamp(min, level.wrapping_sub(terrain.wrapping_sub(level)), max).wrapping_add(offset),
            max_z,
        ),
    }
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

    // A deterministic last-row blob model keeps these ABI tests independent of noise output.
    fn field_frame(version: i32, rows: usize, models: usize) -> Vec<u8> {
        let compact = version == 6;
        let area = if compact { 1 } else { AREA };
        let terrain = 128 + models * 4;
        let heights = terrain + area;
        let height_bytes = if compact { 20 } else { HALO * 4 };
        let wet = heights + height_bytes;
        let entries = wet + height_bytes;
        let model_size = 96 + rows * 16;
        let surfaces = entries + models * 12 + models * model_size;
        let output = surfaces + area * 4;
        let mut data = vec![0; output + area * 4];
        for (p, v) in [
            (0, 0x31525657),
            (4, version),
            (12, models as i32),
            (28, 4),
            (52, terrain as i32),
            (56, heights as i32),
            (60, wet as i32),
            (64, output as i32),
            (68, 128),
            (72, terrain as i32),
            (80, -1),
            (84, -1),
            (88, -1),
            (92, -1),
            (104, if compact { 7 } else { 0 }),
            (108, entries as i32),
            (120, surfaces as i32),
            (124, models as i32),
        ] {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for p in (heights..wet).step_by(4) {
            data[p..p + 4].copy_from_slice(&64_i32.to_le_bytes());
        }
        for p in (wet..entries).step_by(4) {
            data[p..p + 4].copy_from_slice(&i32::MIN.to_le_bytes());
        }
        for slot in 0..models {
            let offset = entries + models * 12 + slot * model_size;
            for (p, v) in [(0, slot as i32), (4, offset as i32), (8, model_size as i32)] {
                data[entries + slot * 12 + p..entries + slot * 12 + p + 4]
                    .copy_from_slice(&v.to_le_bytes());
            }
            for (p, v) in [
                (0, 0x31464d57),
                (4, 1),
                (8, 2),
                (12, rows as i32),
                (24, 1_f32.to_bits() as i32),
                (72, model_size as i32),
            ] {
                data[offset + p..offset + p + 4].copy_from_slice(&v.to_le_bytes());
            }
            for row in 0..rows {
                let at = offset + 96 + row * 16;
                for (p, v) in [
                    (0, 1_f32.to_bits() as i32),
                    (4, 1e9_f32.to_bits() as i32),
                    (8, 0x123456),
                ] {
                    data[at + p..at + p + 4].copy_from_slice(&v.to_le_bytes());
                }
            }
        }
        for p in (surfaces..output).step_by(4) {
            data[p..p + 4].copy_from_slice(&64_f32.to_le_bytes());
        }
        data[output..].fill(0x5a);
        data
    }

    #[test]
    fn deterministic_fields_validate_every_plane_before_writing() {
        for version in [5, 6] {
            let frame = field_frame(version, 2, 2);
            let entries = int(&frame, 108) as usize;
            let model = int(&frame, entries + 4) as usize;
            let surfaces = int(&frame, 120) as usize;
            for (p, v) in [
                (108, entries as i32 + 1),
                (124, 0),
                (entries + 12, 0),
                (entries + 4, -1),
                (entries + 8, i32::MAX),
                (model + 72, 1),
                (model + 76, 1),
                (surfaces, f32::NAN.to_bits() as i32),
                (surfaces, 63_f32.to_bits() as i32),
                (64, -1),
            ] {
                let mut invalid = frame.clone();
                invalid[p..p + 4].copy_from_slice(&v.to_le_bytes());
                let before = invalid.clone();
                assert_eq!(render(&mut invalid), Err(RenderError::InvalidFrame));
                assert_eq!(invalid, before, "version {version}, offset {p}");
            }
            let mut valid = frame;
            let output = int(&valid, 64) as usize;
            render(&mut valid).unwrap();
            assert!(valid[output..]
                .chunks_exact(4)
                .all(|p| u32::from_le_bytes(p.try_into().unwrap()) == 0xff123456));
        }
    }

    #[test]
    fn deterministic_field_workers_enforce_total_noise_budget_and_release_unused_slots() {
        let mut worker = Workspace::default();
        let mut valid = field_frame(6, 256, 2);
        render_with_workspace(&mut valid, &mut worker).unwrap();
        assert_eq!(worker.materials.len(), 2);
        let mut excessive = field_frame(6, 256, 3);
        let before = excessive.clone();
        assert_eq!(
            render_with_workspace(&mut excessive, &mut worker),
            Err(RenderError::InvalidFrame)
        );
        assert_eq!(excessive, before);
        let mut smaller = field_frame(6, 2, 1);
        render_with_workspace(&mut smaller, &mut worker).unwrap();
        assert_eq!(worker.materials.len(), 1);
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

    /// A flat full resolution (version 7) or compact shift 2 (version 8) intersection frame of height 64 with one
    /// palette colour and no layers; `linked` gives the linked height of each full resolution cell.
    fn intersection_frame(
        compact: bool,
        header: &[(usize, i32)],
        linked: impl Fn(usize, usize) -> i32,
    ) -> Vec<u8> {
        let shift = if compact { 2 } else { 0 };
        let width = 128 >> shift;
        let area = width * width;
        let terrain = 212;
        let heights = terrain + area;
        let height_bytes = if compact { area * 20 } else { HALO * 4 };
        let wet = heights + height_bytes;
        let plane = wet + height_bytes;
        let output = plane + AREA_FULL * 4;
        let mut data = vec![0; output + area * 4];
        for (p, v) in [
            (0, 0x31525657),
            (4, if compact { 8 } else { 7 }),
            (12, 1),
            (28, 4),
            (52, terrain as i32),
            (56, heights as i32),
            (60, wet as i32),
            (64, output as i32),
            (68, 208),
            (72, 212),
            (80, -1),
            (84, -1),
            (88, -1),
            (92, -1),
            (96, -256),
            (100, 128),
            (104, shift as i32),
            (132, plane as i32),
            (208, 0x123456),
        ] {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for &(p, v) in header {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for i in 0..height_bytes / 4 {
            data[heights + i * 4..heights + i * 4 + 4].copy_from_slice(&64i32.to_le_bytes());
            data[wet + i * 4..wet + i * 4 + 4].copy_from_slice(&i32::MIN.to_le_bytes());
        }
        for y in 0..128 {
            for x in 0..128 {
                let p = plane + (x + y * 128) * 4;
                data[p..p + 4].copy_from_slice(&linked(x, y).to_le_bytes());
            }
        }
        data
    }

    fn pixel(data: &[u8], cell: usize) -> u32 {
        int(data, int(data, 64) as usize + cell * 4) as u32
    }

    #[test]
    fn ceiling_intersection_uses_the_pattern_at_full_zoom_and_whole_blocks_when_zoomed_out() {
        // Reflection 150: overlap where 150 - linked <= 64, i.e. linked >= 86, here for x < 64
        let linked = |x: usize, _y: usize| if x < 64 { 86 } else { 85 };
        let mut full = intersection_frame(false, &[(128, 1), (136, 150)], linked);
        render(&mut full).unwrap();
        for y in 0..128 {
            for x in 0..128 {
                let expected = if x < 64 && CEILING_PATTERN[x & 7][y & 7] {
                    0xff000000
                } else {
                    0xff123456
                };
                assert_eq!(pixel(&full, x + y * 128), expected, "({x}, {y})");
            }
        }
        let mut compact = intersection_frame(true, &[(128, 1), (136, 150)], linked);
        render(&mut compact).unwrap();
        for row in 0..32 {
            for col in 0..32 {
                let expected = if (col << 2) < 64 {
                    0xff000000
                } else {
                    0xff123456
                };
                assert_eq!(pixel(&compact, col + row * 32), expected, "({col}, {row})");
            }
        }
        // Java's int arithmetic: a reflection point near MIN_VALUE wraps
        let mut wrapped = intersection_frame(false, &[(128, 1), (136, i32::MIN)], |_, _| 1);
        render(&mut wrapped).unwrap();
        assert_eq!(pixel(&wrapped, 0), 0xff123456); // MIN_VALUE - 1 = MAX_VALUE > 64
    }

    #[test]
    fn roof_levels_follow_java_clamps_and_int_overflow() {
        assert_eq!(clamp(10, 5, 0), 10);
        assert_eq!(clamp(10, 20, 0), 0);
        // A missing detail tile gives Integer.MIN_VALUE as terrain height
        assert_eq!(roof_level(0, i32::MIN, 18, -60, 280, -63, 319, 0), 280);
        assert_eq!(roof_level(2, i32::MIN, 18, -60, 280, -63, 319, 0), -60);
        assert_eq!(roof_level(1, i32::MIN, 44, -60, 280, -63, 319, 3), 47);
        assert_eq!(roof_level(0, 70, 18, -60, 280, -63, 319, -5), 47);
        assert_eq!(roof_level(2, 70, 18, -60, 280, -63, 319, 2), -32);
        assert_eq!(roof_level(1, 70, i32::MAX, -60, 280, -63, 319, 1), -63); // MAX_VALUE + 1 wraps below minZ
    }

    struct ConstantNoise(i32, std::cell::Cell<usize>);
    impl NoiseSource for ConstantNoise {
        fn fill(
            &self,
            params: &NoiseParams,
            origin_x: i32,
            origin_y: i32,
            width: usize,
            shift: u32,
            output: &mut [i32],
        ) -> bool {
            assert_eq!(
                (
                    params.octaves,
                    params.seed,
                    origin_x,
                    origin_y,
                    width,
                    shift
                ),
                (2, 99, -256, 128, 128, 0)
            );
            output.fill(self.0);
            self.1.set(self.1.get() + 1);
            true
        }
    }

    #[test]
    fn tunnel_roof_intersection_with_noise_requires_a_noise_source() {
        // CONSTANT_DEPTH, level 18, noise value 30 with offset 10: roof = clamp(-63, clamp(-60, terrain - 18, 280) + 20, 319)
        let header = [
            (128, 2),
            (136, 0),
            (140, 18),
            (144, -60),
            (148, 280),
            (152, -63),
            (156, 319),
            (160, 1),
            (164, 2),
            (168, (1.0_f64.to_bits() & 0xffff_ffff) as i32),
            (172, (1.0_f64.to_bits() >> 32) as i32),
            (176, (2.0_f64.to_bits() & 0xffff_ffff) as i32),
            (180, (2.0_f64.to_bits() >> 32) as i32),
            (184, 99),
            (192, 10),
        ];
        // Terrain 62 gives roof 64 (intersects at height 64), 63 gives roof 65 (does not)
        let valid = intersection_frame(false, &header, |x, _| if x < 64 { 62 } else { 63 });
        let mut data = valid.clone();
        assert!(render(&mut data).is_err());
        assert_eq!(data, valid, "rejected frame must be unchanged");
        let noise = ConstantNoise(30, std::cell::Cell::new(0));
        render_with_noise(&mut data, &mut Workspace::default(), Some(&noise)).unwrap();
        assert_eq!(noise.1.get(), 1);
        for y in 0..128 {
            for x in 0..128 {
                let expected = if x < 64 && CEILING_PATTERN[x & 7][y & 7] {
                    0xff000000
                } else {
                    0xff123456
                };
                assert_eq!(pixel(&data, x + y * 128), expected, "({x}, {y})");
            }
        }
    }

    #[test]
    fn invalid_intersection_extensions_are_rejected_before_writing() {
        let ceiling = [(128, 1), (136, 150)];
        for (p, v) in [
            (128, 3_i32), // Unknown mode
            (128, 0),
            (132, 0), // Linked plane not where the layout puts it
            (140, 1), // Tunnel fields in a ceiling frame
            (160, 1),
            (200, 1), // Reserved
            (204, 1),
        ] {
            let mut data = intersection_frame(false, &ceiling, |_, _| 0);
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = data.clone();
            assert!(render(&mut data).is_err(), "offset {p} value {v}");
            assert_eq!(data, before);
        }
        let roof = [(128, 2), (136, 0), (152, -63), (156, 319)];
        for (p, v) in [
            (136, 3_i32),
            (160, 2),
            (164, 1), /* noise settings without noise */
            (196, 1),
        ] {
            let mut data = intersection_frame(false, &roof, |_, _| 0);
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
            let before = data.clone();
            assert!(render(&mut data).is_err(), "offset {p} value {v}");
            assert_eq!(data, before);
        }
        for octaves in [0_i32, 11] {
            let mut data = intersection_frame(
                false,
                &[
                    (128, 2),
                    (160, 1),
                    (164, octaves),
                    (176, 0),
                    (180, 0x3ff0_0000),
                ],
                |_, _| 0,
            );
            let before = data.clone();
            assert!(render_with_noise(
                &mut data,
                &mut Workspace::default(),
                Some(&ConstantNoise(0, Default::default()))
            )
            .is_err());
            assert_eq!(data, before);
        }
        // A version 7 frame is too short for the extension; an old layout (palette at 128) is not accepted as 7
        let mut short = intersection_frame(false, &ceiling, |_, _| 0);
        short[68..72].copy_from_slice(&128_i32.to_le_bytes());
        let before = short.clone();
        assert!(render(&mut short).is_err());
        assert_eq!(short, before);
    }
}
