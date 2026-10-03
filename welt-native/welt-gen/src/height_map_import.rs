//! WHIM v1/v2/v3/v4/v5: factory initialization, image height conversion and both themes share
//! one packed tile. Integers are little endian; cells are x + y * 128.
//! The 256-byte header contains offsets into the frame, followed by 16-byte
//! plane descriptors (kind, role, default, payload offset), image samples,
//! v3 factory-only generation shares the bitmap sampler and ordered theme application.
//! optional factory samples, a 32-byte tile record and packed output planes.
//! Theme records contain a 32-byte header, terrain ordinals and ordered level
//! tables. The raw 48-bit Java random state is committed only after success.

use crate::theme_terrain::{SimpleThemeTerrainBulk, SimpleThemeTerrainScratch};
use welt_core::{error::WeltError, noise::PerlinNoise, rng::JavaRandom};

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
const AREA: usize = 16384;
const HEADER: usize = 256;
const MEDIUM_BLOBS: f32 = 32.771;
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn long(d: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn double(d: &[u8], p: usize) -> f64 {
    f64::from_bits(long(d, p))
}
fn put_long(d: &mut [u8], p: usize, v: u64) {
    d[p..p + 8].copy_from_slice(&v.to_le_bytes());
}
fn length(kind: u32) -> usize {
    match kind {
        0 => AREA * 4,
        1 => AREA,
        2 => AREA / 2,
        3 => AREA / 8,
        4 => 8,
        _ => 0,
    }
}
#[derive(Clone, Copy)]
pub(crate) struct Plane {
    pub(crate) kind: u32,
    pub(crate) base: usize,
}
impl Plane {
    fn index(self, i: usize) -> usize {
        if self.kind == 4 {
            (i % 128) / 16 + (i / 128) / 16 * 8
        } else {
            i
        }
    }
    pub(crate) fn get(self, d: &[u8], i: usize) -> u32 {
        let i = self.index(i);
        match self.kind {
            0 => word(d, self.base + i * 4),
            1 => d[self.base + i] as u32,
            2 => ((d[self.base + i / 2] >> (i % 2 * 4)) & 15) as u32,
            _ => ((d[self.base + i / 8] >> (i % 8)) & 1) as u32,
        }
    }
    fn set(self, d: &mut [u8], i: usize, value: u32) {
        let i = self.index(i);
        match self.kind {
            0 => d[self.base + i * 4..self.base + i * 4 + 4].copy_from_slice(&value.to_le_bytes()),
            1 => d[self.base + i] = value as u8,
            2 => {
                let s = i % 2 * 4;
                d[self.base + i / 2] = (d[self.base + i / 2] & !(15 << s)) | (value as u8) << s;
            }
            _ => {
                let s = i % 8;
                d[self.base + i / 8] = (d[self.base + i / 8] & !(1 << s)) | (value as u8) << s;
            }
        }
    }
}
#[derive(Default)]
struct Levels {
    plane: usize,
    values: Vec<i32>,
}
#[derive(Default)]
pub(crate) struct Theme {
    min: i32,
    max: i32,
    terrain: Option<SimpleThemeTerrainBulk>,
    ranges: Vec<i32>,
    layers: Vec<Levels>,
    axes: SimpleThemeTerrainScratch,
}
impl Theme {
    pub(crate) fn read(
        &mut self,
        d: &[u8],
        start: usize,
        planes: &[Plane],
        beach: i32,
    ) -> Result<usize, WeltError> {
        let bad = WeltError::IllegalArgument;
        if start.checked_add(32).is_none_or(|end| end > d.len()) {
            return Err(bad);
        }
        self.min = word(d, start) as i32;
        self.max = word(d, start + 4) as i32;
        let range = i64::from(self.max) - i64::from(self.min);
        let n = word(d, start + 24) as usize;
        let flags = word(d, start + 12);
        if !(1..=8192).contains(&range) || n > 61 || flags > 3 || word(d, start + 28) != 0 {
            return Err(bad);
        }
        let range = range as usize;
        let end = start + 32 + range * 4 + n * (4 + range * 4);
        if end > d.len() {
            return Err(bad);
        }
        self.ranges.resize(range, 0);
        for (i, v) in self.ranges.iter_mut().enumerate() {
            *v = word(d, start + 32 + i * 4) as i32;
            if !(0..=255).contains(v) {
                return Err(bad);
            }
        }
        let water = word(d, start + 8) as i32;
        let seed = long(d, start + 16) as i64;
        if let Some(t) = &mut self.terrain {
            t.configure(
                self.min,
                self.max,
                water,
                flags & 1 != 0,
                flags & 2 != 0,
                beach,
                seed,
                &self.ranges,
            )
            .map_err(|_| bad)?;
        } else {
            self.terrain = Some(
                SimpleThemeTerrainBulk::new(
                    self.min,
                    self.max,
                    water,
                    flags & 1 != 0,
                    flags & 2 != 0,
                    beach,
                    seed,
                    &self.ranges,
                )
                .map_err(|_| bad)?,
            );
        }
        self.layers.resize_with(n, Levels::default);
        let mut p = start + 32 + range * 4;
        let mut seen = 0u64;
        let mut bits = false;
        for l in &mut self.layers {
            l.plane = word(d, p) as usize;
            p += 4;
            if l.plane < 3 || l.plane >= planes.len() || seen & (1 << l.plane) != 0 {
                return Err(bad);
            }
            seen |= 1 << l.plane;
            let kind = planes[l.plane].kind;
            if kind >= 3 {
                bits = true;
            } else if bits {
                return Err(bad);
            }
            l.values.resize(range, 0);
            for v in &mut l.values {
                *v = word(d, p) as i32;
                p += 4;
                if kind < 3 && (*v < 0 || *v > if kind == 2 { 15 } else { 255 }) {
                    return Err(bad);
                }
            }
        }
        Ok(end)
    }
    pub(crate) fn prepare_selected_terrains(
        &mut self,
        heights: &[i32],
        selected: &[bool],
        output: &mut [u8],
    ) {
        self.terrain
            .as_ref()
            .unwrap()
            .fill_selected_tile_with_scratch(heights, selected, output, &mut self.axes)
            .unwrap();
    }
    pub(crate) fn apply_cell(
        &self,
        d: &mut [u8],
        planes: &[Plane],
        state: &mut TileState,
        i: usize,
        result: (i32, u8),
        random: &mut JavaRandom,
    ) {
        state.set(d, planes, 2, i, result.1 as u32, false);
        let h = result.0.clamp(self.min, self.max - 1);
        for l in &self.layers {
            let level = l.values[(h - self.min) as usize];
            let value = if planes[l.plane].kind >= 3 {
                u32::from(level > 0 && (level == 15 || random.next_int_bound(15) < level))
            } else {
                level as u32
            };
            state.set(d, planes, l.plane, i, value, false);
        }
    }
    #[allow(clippy::too_many_arguments)]
    fn apply(
        &mut self,
        d: &mut [u8],
        heights: &[i32],
        mask: &[bool],
        planes: &[Plane],
        random: &mut JavaRandom,
        fresh: bool,
        state: &mut TileState,
        terrains: &mut [u8],
    ) {
        self.terrain
            .as_ref()
            .unwrap()
            .fill_bulk_compact_with_scratch(0, 0, 128, 128, heights, terrains, &mut self.axes)
            .unwrap();
        for x in 0..128 {
            for y in 0..128 {
                let i = x + y * 128;
                if !mask[i] {
                    continue;
                }
                state.set(d, planes, 2, i, terrains[i] as u32, false);
                let h = heights[i].clamp(self.min, self.max - 1);
                for l in &self.layers {
                    let level = l.values[(h - self.min) as usize];
                    let value = if planes[l.plane].kind >= 3 {
                        u32::from(level > 0 && (level == 15 || random.next_int_bound(15) < level))
                    } else {
                        level as u32
                    };
                    // Fresh bit planes only accumulate true values, matching the factory.
                    if !fresh || planes[l.plane].kind < 3 || value != 0 {
                        state.set(d, planes, l.plane, i, value, false);
                    }
                }
            }
        }
    }
}
pub(crate) struct TileState {
    pub(crate) present: u64,
    pub(crate) changed: u64,
    pub(crate) order: [u8; 64],
    pub(crate) count: usize,
}
impl TileState {
    pub(crate) fn set(
        &mut self,
        d: &mut [u8],
        planes: &[Plane],
        p: usize,
        i: usize,
        value: u32,
        always: bool,
    ) {
        if !always && planes[p].get(d, i) == value {
            return;
        }
        if self.changed & (1 << p) == 0 {
            self.order[self.count] = p as u8;
            self.count += 1;
        }
        planes[p].set(d, i, value);
        self.present |= 1 << p;
        self.changed |= 1 << p;
    }
}
/// Retained by the JNI worker; all per-cell arrays and theme caches are reused.
#[derive(Default)]
pub struct ImportScratch {
    fancy: crate::fancy_generation::Scratch,
    factory: Theme,
    imported: Theme,
    heights: Vec<i32>,
    procedural_samples: Vec<f64>,
    selected: Vec<bool>,
    terrains: Vec<u8>,
    border: Option<PerlinNoise>,
}
fn raw_height(h: f32, min: i32, tall: bool) -> u32 {
    let raw = ((h - min as f32) * 256f32) as i32;
    if tall {
        raw as u32
    } else {
        (raw as u16) as u32
    }
}
fn quantised(raw: u32, min: i32) -> i32 {
    let h = raw as i32 as f32 / 256f32 + min as f32;
    (f64::from(h) + 0.5).floor() as i32
}
/// Validate the whole frame before editing planes or advancing the Java RNG.
pub fn import(d: &mut [u8], s: &mut ImportScratch) -> Result<(), WeltError> {
    if d.len() >= HEADER && word(d, 4) == 5 {
        return crate::fancy_generation::fill(d, &mut s.fancy);
    }
    let bad = WeltError::IllegalArgument;
    if d.len() < HEADER
        || d.len() > MAX_BYTES
        || word(d, 0) != 0x4d49_4857
        || !(1..=4).contains(&word(d, 4))
        || word(d, 8) as usize != d.len()
        || word(d, 124) as usize != HEADER
        || d[if word(d, 4) == 1 { 208 } else { 212 }..HEADER]
            .iter()
            .any(|v| *v != 0)
    {
        return Err(bad);
    }
    let n = word(d, 12) as usize;
    let min = word(d, 16) as i32;
    let max = word(d, 20) as i32;
    let flags = word(d, 24);
    let fresh = flags & 1 != 0;
    let factory_only = word(d, 4) >= 3;
    let raise = flags & 2 != 0;
    let void = flags & 4 != 0;
    let tall = i64::from(max) - i64::from(min) > 256;
    if !(3..=64).contains(&n)
        || (if factory_only {
            flags != 65
        } else {
            flags > 63
        })
        || !(1..=65536).contains(&(i64::from(max) - i64::from(min)))
    {
        return Err(bad);
    }
    let samples = word(d, 112) as usize;
    let initial = word(d, 116) as usize;
    let meta = word(d, 120) as usize;
    if samples != HEADER + n * 16
        || initial != samples + AREA * 8
        || meta != initial + if fresh && !factory_only { AREA * 8 } else { 0 }
        || meta + 32 > d.len()
        || long(d, meta + 24) != 0
    {
        return Err(bad);
    }
    let mut planes = [Plane { kind: 0, base: 0 }; 64];
    let mut bytes = 0;
    for (p, plane) in planes[..n].iter_mut().enumerate() {
        let desc = HEADER + p * 16;
        let kind = word(d, desc);
        let role = word(d, desc + 4);
        let default = word(d, desc + 8);
        let len = length(kind);
        if len == 0
            || word(d, desc + 12) as usize != bytes
            || role != if p < 3 { p as u32 } else { 3 }
            || p < 2 && kind != 0
            || p == 2 && kind != 1
            || p >= 3 && kind == 0
            || default
                > match kind {
                    0 => u32::MAX,
                    1 => 255,
                    2 => 15,
                    _ => 1,
                }
        {
            return Err(bad);
        }
        *plane = Plane {
            kind,
            base: meta + 32 + bytes,
        };
        bytes += len;
    }
    let factory = word(d, 128) as usize;
    let imported = word(d, 132) as usize;
    let mut end = meta + 32 + bytes;
    if end > d.len() {
        return Err(bad);
    }
    let beach = word(d, 136) as i32;
    if !(0..=255).contains(&beach) || fresh && factory == 0 || !fresh && factory != 0 {
        return Err(bad);
    }
    if factory != 0 {
        if factory != end {
            return Err(bad);
        }
        end = s.factory.read(d, factory, &planes[..n], beach)?;
    }
    if factory_only && imported != 0 {
        return Err(bad);
    }
    if imported != 0 {
        if imported != end {
            return Err(bad);
        }
        end = s.imported.read(d, imported, &planes[..n], beach)?;
    }
    let source = if (2..=3).contains(&word(d, 4)) {
        if word(d, 208) as usize != end {
            return Err(bad);
        }
        Some(crate::bitmap_import::BitmapSource::read(
            d,
            end,
            word(d, 40) as i32,
            word(d, 44) as i32,
        )?)
    } else {
        if word(d, 4) == 4 {
            if word(d, 208) as usize != end {
                return Err(bad);
            }
        } else if end != d.len() {
            return Err(bad);
        }
        None
    };
    let void_plane = word(d, 32) as usize;
    if void && (void_plane < 3 || void_plane >= n || planes[void_plane].kind != 3) {
        return Err(bad);
    }
    let mut random = JavaRandom::from_lcg_state(long(d, 104)).ok_or(bad)?;
    let ex = word(d, 48) as i32;
    let ey = word(d, 52) as i32;
    let ew = word(d, 56) as i32;
    let eh = word(d, 60) as i32;
    if ew <= 0 || eh <= 0 {
        return Err(bad);
    }
    let x0 = word(d, 40) as i32;
    let y0 = word(d, 44) as i32;
    if factory_only && (long(d, meta + 8) != 7 || long(d, meta + 16) != 0) {
        return Err(bad);
    }
    if word(d, 4) == 4 {
        if end + 128 > d.len() || word(d, end + 28) != 128 || word(d, end + 32) != 128 {
            return Err(bad);
        }
        s.procedural_samples.resize(AREA, 0.0);
        crate::height_map_program::fill_source(d, end, &mut s.procedural_samples)?;
        for (i, value) in s.procedural_samples.iter().enumerate() {
            put_long(d, samples + i * 8, value.to_bits());
        }
    }
    if let Some(source) = source {
        for y in 0..128 {
            for x in 0..128 {
                let wx = x0.wrapping_add(x);
                let wy = y0.wrapping_add(y);
                let covered = i64::from(wx) >= i64::from(ex)
                    && i64::from(wx) < i64::from(ex) + i64::from(ew)
                    && i64::from(wy) >= i64::from(ey)
                    && i64::from(wy) < i64::from(ey) + i64::from(eh);
                let value = if covered {
                    source.sample(d, wx, wy)
                } else {
                    0.0
                };
                put_long(
                    d,
                    samples + (x as usize + y as usize * 128) * 8,
                    value.to_bits(),
                );
            }
        }
    }
    if factory_only {
        // Fresh output is initialized in Rust, without reading empty Java tile arrays.
        for (p, plane) in planes[..n].iter().enumerate() {
            let default = word(d, HEADER + p * 16 + 8) as u8;
            let value = if p < 3 || plane.kind >= 3 {
                0
            } else if plane.kind == 2 {
                default | (default << 4)
            } else {
                default
            };
            d[plane.base..plane.base + length(plane.kind) as usize].fill(value);
        }
    }
    s.heights.resize(AREA, 0);
    s.selected.resize(AREA, true);
    s.terrains.resize(AREA, 0);
    let mut state = TileState {
        present: long(d, meta + 8),
        changed: 0,
        order: [255; 64],
        count: 0,
    };
    let water = word(d, 28);
    let factory_water = word(d, 36);
    if fresh {
        s.selected.fill(true);
        for i in 0..AREA {
            let h = (double(d, (if factory_only { samples } else { initial }) + i * 8) as f32)
                .clamp(min as f32, (max - 1) as f32);
            let raw = raw_height(h, min, tall);
            state.set(d, &planes, 0, i, raw, true);
            state.set(d, &planes, 1, i, factory_water, true);
            s.heights[i] = quantised(raw, min);
        }
        s.factory.apply(
            d,
            &s.heights,
            &s.selected,
            &planes,
            &mut random,
            true,
            &mut state,
            &mut s.terrains,
        );
        // Factory initializers allocate cached layer planes in cache order,
        // independently of the first cell whose bit draw succeeds.
        state.order.fill(255);
        state.count = 0;
        for p in (0..3).chain(s.factory.layers.iter().map(|l| l.plane)) {
            if state.changed & (1 << p) != 0 {
                state.order[state.count] = p as u8;
                state.count += 1;
            }
        }
    }
    if !factory_only {
        let seed = long(d, 96) as i64;
        if let Some(p) = &mut s.border {
            p.set_seed(seed);
        } else {
            s.border = Some(PerlinNoise::new(seed));
        }
        let floor = (water as i32).wrapping_sub(20).max(min);
        let variation = 15.min((water as i32).wrapping_sub(floor) / 2);
        let low = double(d, 64);
        let scale = double(d, 72);
        let world_low = word(d, 80) as i32;
        let threshold = double(d, 88);
        for x in 0..128 {
            for y in 0..128 {
                let i = x + y * 128;
                let wx = x0.wrapping_add(x as i32);
                let wy = y0.wrapping_add(y as i32);
                let covered = i64::from(wx) >= i64::from(ex)
                    && i64::from(wx) < i64::from(ex) + i64::from(ew)
                    && i64::from(wy) >= i64::from(ey)
                    && i64::from(wy) < i64::from(ey) + i64::from(eh);
                s.selected[i] = false;
                if covered {
                    let level = double(d, samples + i * 8);
                    let h = (if flags & 8 == 0 && flags & 16 != 0 {
                        if flags & 32 != 0 {
                            level
                        } else {
                            level - 0.4375
                        }
                    } else {
                        (level - low) * scale + f64::from(world_low)
                    } as f32)
                        .clamp(min as f32, (max - 1) as f32);
                    let old = planes[0].get(d, i) as i32 as f32 / 256f32 + min as f32;
                    if raise && !fresh && h.partial_cmp(&old) != Some(std::cmp::Ordering::Greater) {
                        continue;
                    }
                    let raw = raw_height(h, min, tall);
                    state.set(d, &planes, 0, i, raw, true);
                    s.heights[i] = quantised(raw, min);
                    s.selected[i] = true;
                    if !raise || fresh {
                        state.set(d, &planes, 1, i, water, true);
                        // Void may also occur in the theme: its final mutation stays ordered per cell below.
                    }
                } else if fresh {
                    let noise = s.border.as_ref().unwrap().get_perlin_noise_2d(
                        f64::from(wx as f32 / MEDIUM_BLOBS),
                        f64::from(wy as f32 / MEDIUM_BLOBS),
                    );
                    let h = floor as f32 + (noise + 0.5f32) * variation as f32;
                    state.set(d, &planes, 0, i, raw_height(h, min, tall), true);
                    state.set(d, &planes, 1, i, water, true);
                    state.set(d, &planes, 2, i, beach as u32, true);
                    if void {
                        state.set(d, &planes, void_plane, i, 1, true);
                    }
                }
            }
        }
        // Apply each cell's Void mutation immediately before that cell's theme layers.
        if imported != 0 {
            s.imported
                .terrain
                .as_ref()
                .unwrap()
                .fill_bulk_compact_with_scratch(
                    0,
                    0,
                    128,
                    128,
                    &s.heights,
                    &mut s.terrains,
                    &mut s.imported.axes,
                )
                .unwrap();
        }
        for x in 0..128 {
            for y in 0..128 {
                let i = x + y * 128;
                if !s.selected[i] {
                    continue;
                }
                if void && (!raise || fresh) && double(d, samples + i * 8) <= threshold {
                    state.set(d, &planes, void_plane, i, 1, true);
                }
                if imported == 0 {
                    continue;
                }
                state.set(d, &planes, 2, i, s.terrains[i] as u32, false);
                let h = s.heights[i].clamp(s.imported.min, s.imported.max - 1);
                for l in &s.imported.layers {
                    let level = l.values[(h - s.imported.min) as usize];
                    let value = if planes[l.plane].kind >= 3 {
                        u32::from(level > 0 && (level == 15 || random.next_int_bound(15) < level))
                    } else {
                        level as u32
                    };
                    state.set(d, &planes, l.plane, i, value, false);
                }
            }
        }
    }
    d[140..204].copy_from_slice(&state.order);
    d[204..208].copy_from_slice(&(state.count as u32).to_le_bytes());
    put_long(d, meta + 8, state.present);
    put_long(d, meta + 16, state.changed);
    put_long(d, 104, random.lcg_state());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn put(d: &mut [u8], p: usize, v: u32) {
        d[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn number(d: &mut [u8], p: usize, v: f64) {
        put_long(d, p, v.to_bits());
    }
    fn frame(theme: bool) -> Vec<u8> {
        let n = if theme { 4 } else { 3 };
        let samples = 256 + n * 16;
        let meta = samples + AREA * 8;
        let payload = AREA * 9 + if theme { AREA / 8 } else { 0 };
        let end = meta + 32 + payload;
        let size = end + if theme { 32 + 256 * 4 + 4 + 256 * 4 } else { 0 };
        let mut d = vec![0; size];
        for (p, v) in [
            (0, 0x4d494857),
            (4, 1),
            (8, size as u32),
            (12, n as u32),
            (20, 256),
            (24, 8),
            (28, 62),
            (32, u32::MAX),
            (56, 128),
            (60, 128),
            (112, samples as u32),
            (116, meta as u32),
            (120, meta as u32),
            (124, 256),
            (136, 5),
        ] {
            put(&mut d, p, v);
        }
        number(&mut d, 72, 255.0 / 65535.0);
        put_long(&mut d, 104, JavaRandom::new(42).lcg_state());
        put_long(&mut d, meta + 8, 7);
        let mut offset = 0;
        for p in 0..n {
            let kind = if p < 2 {
                0
            } else if p == 2 {
                1
            } else {
                3
            };
            put(&mut d, 256 + p * 16, kind);
            put(&mut d, 260 + p * 16, p as u32);
            put(&mut d, 268 + p * 16, offset as u32);
            offset += length(kind);
        }
        if theme {
            put(&mut d, 132, end as u32);
            put(&mut d, end + 4, 256);
            put(&mut d, end + 8, 62);
            put(&mut d, end + 24, 1);
            for h in 0..256 {
                put(&mut d, end + 32 + h * 4, 3);
            }
            put(&mut d, end + 32 + 256 * 4, 3);
            for h in 0..256 {
                put(&mut d, end + 32 + 256 * 4 + 4 + h * 4, 7);
            }
        }
        d
    }
    fn factory_frame() -> Vec<u8> {
        let mut data = frame(true);
        let theme = word(&data, 132);
        put(&mut data, 128, theme);
        put(&mut data, 132, 0);
        put(&mut data, 24, 65);
        put(&mut data, 36, 62);
        put(&mut data, 4, 3);
        let source = data.len();
        data.resize(source + 112 + AREA * 8, 0);
        let size = data.len();
        put(&mut data, 8, size as u32);
        put(&mut data, 208, source as u32);
        for (p, value) in [
            (0, 0x4d53_4257),
            (4, 1),
            (8, (112 + AREA * 8) as u32),
            (12, 1),
            (24, 128),
            (28, 128),
            (32, 128),
            (36, 128),
        ] {
            put(&mut data, source + p, value);
        }
        number(&mut data, source + 48, 1.0);
        number(&mut data, source + 56, 1.0);
        for i in 0..AREA {
            number(&mut data, source + 112 + i * 8, (i * 31 % 256) as f64);
        }
        data
    }
    fn procedural_frame() -> Vec<u8> {
        let mut data = factory_frame();
        let source = word(&data, 208) as usize;
        data.truncate(source);
        data.resize(source + 160, 0);
        let size = data.len();
        put(&mut data, 4, 4);
        put(&mut data, 8, size as u32);
        for (p, value) in [
            (0, 0x50475457),
            (4, 1),
            (8, 160),
            (12, 1),
            (28, 128),
            (32, 128),
        ] {
            put(&mut data, source + p, value);
        }
        number(&mut data, source + 136, 93.125);
        data
    }
    #[test]
    fn procedural_generation_preserves_quantisation_and_rejects_invalid_programs_atomically() {
        let valid = procedural_frame();
        let meta = word(&valid, 120) as usize;
        let source = word(&valid, 208) as usize;
        let mut data = valid.clone();
        import(&mut data, &mut ImportScratch::default()).unwrap();
        for i in 0..AREA {
            assert_eq!(word(&data, meta + 32 + i * 4), 93 * 256 + 32);
            assert_eq!(word(&data, meta + 32 + AREA * 4 + i * 4), 62);
        }
        for (p, value) in [
            (source, 0),
            (source + 4, 2),
            (source + 8, 159),
            (source + 12, 65),
            (source + 16, 4),
            (source + 20, 16777216),
            (source + 28, 127),
            (source + 40, 1),
            (source + 128, 99),
        ] {
            let mut invalid = valid.clone();
            put(&mut invalid, p, value);
            let before = invalid.clone();
            assert_eq!(
                import(&mut invalid, &mut ImportScratch::default()),
                Err(WeltError::IllegalArgument)
            );
            assert_eq!(invalid, before);
        }
    }
    #[test]
    fn factory_only_sampling_quantisation_and_stochastic_theme_share_the_tile() {
        let mut data = factory_frame();
        let meta = word(&data, 120) as usize;
        let mut random = JavaRandom::new(42);
        import(&mut data, &mut ImportScratch::default()).unwrap();
        let bits = meta + 32 + AREA * 9;
        for x in 0..128 {
            for y in 0..128 {
                let i = x + y * 128;
                assert_eq!(word(&data, meta + 32 + i * 4), (i * 31 % 256 * 256) as u32);
                assert_eq!(word(&data, meta + 32 + AREA * 4 + i * 4), 62);
                assert_eq!(data[meta + 32 + AREA * 8 + i], 3);
                assert_eq!(
                    (data[bits + i / 8] >> (i & 7)) & 1,
                    u8::from(random.next_int_bound(15) < 7)
                );
            }
        }
        assert_eq!(long(&data, 104), random.lcg_state());
        assert_eq!(long(&data, meta + 8), 15);
        assert_eq!(&data[140..144], &[0, 1, 2, 3]);
    }
    #[test]
    fn malformed_factory_only_frames_do_not_change_samples_or_output() {
        let valid = factory_frame();
        let meta = word(&valid, 120) as usize;
        for (p, value) in [
            (4, 2),
            (24, 1),
            (128, 0),
            (132, 1),
            (208, 0),
            (meta + 8, 0),
            (meta + 16, 1),
        ] {
            let mut data = valid.clone();
            put(&mut data, p, value);
            let before = data.clone();
            assert_eq!(
                import(&mut data, &mut ImportScratch::default()),
                Err(WeltError::IllegalArgument)
            );
            assert_eq!(data, before);
        }
    }

    #[test]
    fn fused_raster_sampling_matches_legacy_samples_and_rejects_bad_sources_atomically() {
        let mut legacy = frame(true);
        let samples = word(&legacy, 112) as usize;
        for i in 0..AREA {
            number(&mut legacy, samples + i * 8, (i * 31 % 65536) as f64);
        }
        let mut fused = legacy.clone();
        let source = fused.len();
        fused.resize(source + 112 + AREA * 8, 0);
        let size = fused.len();
        put(&mut fused, 4, 2);
        put(&mut fused, 8, size as u32);
        put(&mut fused, 208, source as u32);
        for (p, v) in [
            (0, 0x4d53_4257),
            (4, 1),
            (8, (112 + AREA * 8) as u32),
            (12, 1),
            (24, 128),
            (28, 128),
            (32, 128),
            (36, 128),
        ] {
            put(&mut fused, source + p, v);
        }
        number(&mut fused, source + 48, 1.0);
        number(&mut fused, source + 56, 1.0);
        for i in 0..AREA {
            number(&mut fused, source + 112 + i * 8, (i * 31 % 65536) as f64);
            number(&mut fused, samples + i * 8, -1.0);
        }
        let original = fused.clone();
        import(&mut fused, &mut ImportScratch::default()).unwrap();
        import(&mut legacy, &mut ImportScratch::default()).unwrap();
        put(&mut fused, 4, 1);
        put(&mut fused, 8, source as u32);
        put(&mut fused, 208, 0);
        assert_eq!(&fused[..source], &legacy);
        for (p, v) in [(8, 1), (12, 8), (24, u32::MAX), (16, 100), (104, 1)] {
            let mut invalid = original.clone();
            put(&mut invalid, source + p, v);
            let before = invalid.clone();
            assert_eq!(
                import(&mut invalid, &mut ImportScratch::default()),
                Err(WeltError::IllegalArgument)
            );
            assert_eq!(invalid, before);
        }
        let mut invalid = original.clone();
        put(&mut invalid, 208, (source - 1) as u32);
        let before = invalid.clone();
        assert_eq!(
            import(&mut invalid, &mut ImportScratch::default()),
            Err(WeltError::IllegalArgument)
        );
        assert_eq!(invalid, before);
    }

    #[test]
    fn conversion_preserves_java_float_quantisation() {
        let mut d = frame(false);
        let samples = word(&d, 112) as usize;
        let meta = word(&d, 120) as usize;
        for i in 0..AREA {
            number(&mut d, samples + i * 8, (i * 31 % 65536) as f64);
        }
        import(&mut d, &mut ImportScratch::default()).unwrap();
        for i in 0..AREA {
            let h = ((i * 31 % 65536) as f64 * (255.0 / 65535.0)) as f32;
            assert_eq!(word(&d, meta + 32 + i * 4), ((h * 256f32) as i32) as u32);
            assert_eq!(word(&d, meta + 32 + AREA * 4 + i * 4), 62);
        }
        assert_eq!(long(&d, meta + 16), 3);
    }
    #[test]
    fn stochastic_theme_uses_exact_xy_draw_order() {
        let mut d = frame(true);
        let meta = word(&d, 120) as usize;
        let mut expected = JavaRandom::new(42);
        import(&mut d, &mut ImportScratch::default()).unwrap();
        let bits = meta + 32 + AREA * 9;
        for x in 0..128 {
            for y in 0..128 {
                let i = x + y * 128;
                assert_eq!(
                    (d[bits + i / 8] >> (i % 8)) & 1,
                    u8::from(expected.next_int_bound(15) < 7)
                );
            }
        }
        assert_eq!(long(&d, 104), expected.lcg_state());
        assert_eq!(long(&d, meta + 8), 15);
    }
    #[test]
    fn rejects_every_malformed_section_before_writing() {
        for (offset, value) in [
            (0, 0),
            (4, 2),
            (8, 256),
            (12, 65),
            (20, 0),
            (24, 64),
            (56, 0),
            (112, 0),
            (120, u32::MAX),
            (124, 0),
            (132, u32::MAX),
            (136, 256),
            (208, 1),
            (256, 5),
            (260, 3),
            (268, 1),
            (256 + 3 * 16, 0),
        ] {
            let mut d = frame(true);
            put(&mut d, offset, value);
            let before = d.clone();
            assert_eq!(
                import(&mut d, &mut ImportScratch::default()),
                Err(WeltError::IllegalArgument)
            );
            assert_eq!(d, before);
        }
        let mut d = frame(true);
        let start = word(&d, 132) as usize;
        put(&mut d, start + 32, 256);
        let before = d.clone();
        assert_eq!(
            import(&mut d, &mut ImportScratch::default()),
            Err(WeltError::IllegalArgument)
        );
        assert_eq!(d, before);
    }
    #[test]
    fn only_raise_keeps_water_and_rng_for_unchanged_cells() {
        let mut d = frame(true);
        put(&mut d, 24, 10);
        let meta = word(&d, 120) as usize;
        for i in 0..AREA {
            put(&mut d, meta + 32 + i * 4, 80 * 256);
            put(&mut d, meta + 32 + AREA * 4 + i * 4, 49);
        }
        d[140..204].fill(255);
        let before = d.clone();
        import(&mut d, &mut ImportScratch::default()).unwrap();
        assert_eq!(d, before);
    }
    #[test]
    fn workers_reuse_their_cell_and_theme_storage() {
        let mut d = frame(true);
        let mut s = ImportScratch::default();
        import(&mut d, &mut s).unwrap();
        let hp = s.heights.as_ptr();
        let tp = s.terrains.as_ptr();
        let lp = s.imported.layers[0].values.as_ptr();
        import(&mut d, &mut s).unwrap();
        assert_eq!(hp, s.heights.as_ptr());
        assert_eq!(tp, s.terrains.as_ptr());
        assert_eq!(lp, s.imported.layers[0].values.as_ptr());
    }
}
