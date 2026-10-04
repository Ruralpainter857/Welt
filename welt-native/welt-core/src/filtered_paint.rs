//! WFPT v1..v5: shared filter and painting transactions for terrain, numeric and bit layers.
use crate::editor_filter::{CellData, Program};
use crate::error::WeltError;
use crate::nibble_paint::{target as nibble_target, NibblePaintMode};

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
const HEADER: usize = 160;
const HEIGHT: usize = 16384;
const WATER: usize = HEIGHT + 130 * 130 * 4;
const LAYERS: usize = WATER + 16384 * 4;
fn int(d: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn float(d: &[u8], p: usize) -> f32 {
    f32::from_bits(int(d, p) as u32)
}
fn bad<T>() -> Result<T, WeltError> {
    Err(WeltError::IllegalArgument)
}
fn bytes(bits: usize) -> usize {
    if bits == 0 {
        8
    } else {
        16384 * bits / 8
    }
}
fn boolean(d: &[u8], p: usize) -> Result<bool, WeltError> {
    match int(d, p) {
        0 => Ok(false),
        1 => Ok(true),
        _ => bad(),
    }
}
struct Frame {
    record: usize,
    wx: i64,
    wy: i64,
    x: usize,
    y: usize,
    w: usize,
    h: usize,
    base: usize,
    present: bool,
}
struct Cell<'a> {
    data: &'a [u8],
    frame: &'a Frame,
    planes: &'a [(usize, usize)],
    palette: usize,
    cell: usize,
    x: usize,
    y: usize,
}
impl Cell<'_> {
    fn value(&self, plane: usize) -> i32 {
        let (bits, offset) = self.planes[plane];
        let cell = if bits == 0 {
            self.x / 16 + self.y / 16 * 8
        } else {
            self.cell
        };
        let bits = bits.max(1);
        ((self.data[self.frame.base + offset + cell * bits / 8] >> (cell * bits % 8))
            & ((1u16 << bits) - 1) as u8) as i32
    }
    fn helper(&self, slot: usize) -> i32 {
        let plane = int(self.data, 64 + slot * 4);
        if plane < 0 {
            0
        } else {
            self.value(plane as usize)
        }
    }
    fn height_at(&self, x: usize, y: usize) -> f32 {
        float(self.data, self.frame.base + HEIGHT + (x * 130 + y) * 4)
    }
    fn biome_id(&self, slot: usize) -> i32 {
        int(self.data, 112 + slot * 4)
    }
}
impl CellData for Cell<'_> {
    fn height(&self) -> i32 {
        (f64::from(self.height_at(self.x + 1, self.y + 1)) + 0.5).floor() as i32
    }
    fn water(&self) -> i32 {
        int(self.data, self.frame.base + WATER + self.cell * 4)
    }
    fn slope(&self) -> f32 {
        let x = self.x + 1;
        let y = self.y + 1;
        let diagonal = float(self.data, 108);
        let a = (self.height_at(x + 1, y) - self.height_at(x - 1, y)).abs() / 2.0;
        let b = (self.height_at(x + 1, y + 1) - self.height_at(x - 1, y - 1)).abs() / diagonal;
        let c = (self.height_at(x, y + 1) - self.height_at(x, y - 1)).abs() / 2.0;
        let d = (self.height_at(x - 1, y + 1) - self.height_at(x + 1, y - 1)).abs() / diagonal;
        if [a, b, c, d].iter().any(|v| v.is_nan()) {
            f32::NAN
        } else {
            a.max(b).max(c.max(d))
        }
    }
    fn terrain(&self) -> i32 {
        self.data[self.frame.base + self.cell] as i32
    }
    fn biome(&self) -> i32 {
        self.helper(0)
    }
    fn auto_biome(&self) -> i32 {
        let constant = int(self.data, 36);
        if constant >= 0 {
            return constant;
        }
        let frost = self.helper(5) != 0;
        let river = self.helper(6) != 0;
        let forest = self.helper(7) > 0 || self.helper(8) > 0;
        let swamp = self.helper(9) > 0;
        let jungle = self.helper(10) > 0;
        let depth = self.water().wrapping_sub(self.height());
        let flooded = depth > 0 && !self.lava();
        if frost {
            if river {
                self.biome_id(0)
            } else if forest || swamp || jungle {
                self.biome_id(1)
            } else if self.terrain() == int(self.data, 56) || (flooded && depth <= 5) {
                self.biome_id(0)
            } else if flooded {
                self.biome_id(2)
            } else {
                self.biome_id(3)
            }
        } else if river {
            self.biome_id(4)
        } else if swamp {
            self.biome_id(5)
        } else if jungle {
            self.biome_id(6)
        } else if flooded {
            if depth <= 5 {
                self.biome_id(4)
            } else if depth <= 20 {
                self.biome_id(7)
            } else {
                self.biome_id(8)
            }
        } else {
            let entry = self.palette + self.terrain() as usize * 8;
            if forest && int(self.data, entry + 4) != 0 {
                self.biome_id(9)
            } else {
                int(self.data, entry)
            }
        }
    }
    fn lava(&self) -> bool {
        self.helper(1) != 0
    }
    fn selected(&self) -> bool {
        self.helper(2) != 0 || self.helper(3) != 0
    }
    fn annotations(&self) -> i32 {
        self.helper(4)
    }
    fn layer(&self, plane: usize) -> i32 {
        self.value(plane)
    }
}

pub fn paint(data: &mut [u8]) -> Result<(), WeltError> {
    if data.len() < HEADER
        || data.len() > MAX_BYTES
        || int(data, 0) != 0x54504657
        || !matches!(int(data, 4), 1..=5)
    {
        return bad();
    }
    let heights = int(data, 4) == 5;
    let header = if heights { 192 } else { HEADER };
    if data.len() < header
        || heights
            && (!(0..=4).contains(&int(data, 160))
                || !matches!(int(data, 180), 0 | 1)
                || data[184..192].iter().any(|&v| v != 0))
    {
        return bad();
    }
    let nibble = int(data, 4) == 2;
    let bit = int(data, 4) == 3;
    let discrete = int(data, 4) == 4;
    let output = int(data, 152) as usize;
    let mode = match int(data, 156) {
        0 => NibblePaintMode::Apply,
        1 => NibblePaintMode::RemoveRounded,
        2 => NibblePaintMode::RemoveTruncated,
        _ => return bad(),
    };
    if nibble {
        if int(data, 60) != 4 {
            return bad();
        }
    } else if bit {
        if !matches!(int(data, 60), 0 | 1) || int(data, 156) > 1 {
            return bad();
        }
    } else if discrete {
        if !matches!(int(data, 60), 4 | 8) || int(data, 156) != 0 {
            return bad();
        }
    } else if int(data, 60) != 0 || int(data, 152) != 0 || int(data, 156) != 0 {
        return bad();
    }
    let count = int(data, 8) as usize;
    let plane_count = int(data, 12) as usize;
    let tile_count = int(data, 16) as usize;
    let target = int(data, 20);
    let palette_count = int(data, 32) as usize;
    if !(1..=128).contains(&count)
        || plane_count > 128
        || !(1..=9).contains(&tile_count)
        || !(1..=256).contains(&palette_count)
        || target < 0
        || target as usize
            >= if discrete {
                1usize << int(data, 60)
            } else {
                palette_count
            }
        || !(-1..=254).contains(&int(data, 36))
        || int(data, 56) < 0
        || int(data, 56) as usize >= palette_count
        || float(data, 108).to_bits() != 8.0f32.sqrt().to_bits()
    {
        return bad();
    }
    for i in 0..11 {
        if int(data, 64 + i * 4) < -1 || int(data, 64 + i * 4) >= plane_count as i32 {
            return bad();
        }
    }
    let (program, mut cursor) = Program::read(data, header, count, plane_count)?;
    if cursor != int(data, 28) as usize {
        return bad();
    }
    let palette = cursor;
    cursor += palette_count * 8;
    if cursor != int(data, 40) as usize || cursor + plane_count * 8 > data.len() {
        return bad();
    }
    for i in 0..palette_count {
        boolean(data, palette + i * 8 + 4)?;
    }
    let mut planes = Vec::with_capacity(plane_count);
    let mut end = LAYERS;
    for i in 0..plane_count {
        let bits = int(data, cursor + i * 8) as usize;
        if !matches!(bits, 0 | 1 | 4 | 8) || int(data, cursor + i * 8 + 4) as usize != end {
            return bad();
        }
        planes.push((bits, end));
        end += bytes(bits);
    }
    cursor += plane_count * 8;
    if nibble && (output >= planes.len() || planes[output].0 != 4) {
        return bad();
    }
    if bit && (output >= planes.len() || planes[output].0 != int(data, 60) as usize) {
        return bad();
    }
    if discrete && (output >= planes.len() || planes[output].0 != int(data, 60) as usize) {
        return bad();
    }
    if cursor != int(data, 44) as usize || cursor + tile_count * 32 > data.len() {
        return bad();
    }
    let records = cursor;
    cursor += tile_count * 32;
    if cursor != int(data, 48) as usize {
        return bad();
    }
    let height_output = end;
    if heights {
        end += 16384 * 5;
    }
    let mut frames = Vec::with_capacity(tile_count);
    for i in 0..tile_count {
        let record = records + i * 32;
        let x = int(data, record + 8) as usize;
        let y = int(data, record + 12) as usize;
        let w = int(data, record + 16) as usize;
        let h = int(data, record + 20) as usize;
        let state = int(data, record + 28);
        if w == 0
            || h == 0
            || w > 128
            || h > 128
            || x > 128 - w
            || y > 128 - h
            || !matches!(state, -1 | 0)
            || int(data, record + 24) as usize != cursor
            || cursor + end + w * h * 4 > data.len()
        {
            return bad();
        }
        if state == 0
            && data[cursor..cursor + 16384]
                .iter()
                .any(|&v| v as usize >= palette_count)
        {
            return bad();
        }
        frames.push(Frame {
            record,
            wx: i64::from(int(data, record)) * 128,
            wy: i64::from(int(data, record + 4)) * 128,
            x,
            y,
            w,
            h,
            base: cursor,
            present: state == 0,
        });
        cursor += end + w * h * 4;
    }
    if cursor != data.len() {
        return bad();
    }
    if heights {
        return edit_heights(
            data,
            &frames,
            &planes,
            &program,
            palette,
            height_output,
            end,
        );
    }
    // No writes until every program node, descriptor and input plane is validated.
    let dynamic = float(data, 24);
    for frame in frames {
        if !frame.present {
            continue;
        }
        let mut writes = 0i32;
        for dy in 0..frame.h {
            for dx in 0..frame.w {
                let x = frame.x + dx;
                let y = frame.y + dy;
                let cell = y * 128 + x;
                let strength = float(data, frame.base + end + (dy * frame.w + dx) * 4);
                let filtered = {
                    let context = Cell {
                        data,
                        frame: &frame,
                        planes: &planes,
                        palette,
                        cell,
                        x,
                        y,
                    };
                    dynamic * program.modify_strength(&context, strength)
                };
                if discrete && filtered > 0.75 {
                    let offset = frame.base + planes[output].1;
                    if planes[output].0 == 8 {
                        data[offset + cell] = target as u8;
                    } else {
                        let shift = (cell & 1) * 4;
                        data[offset + cell / 2] =
                            (data[offset + cell / 2] & !(15 << shift)) | ((target as u8) << shift);
                    }
                    writes += 1;
                } else if bit && filtered > 0.75 {
                    let index = if planes[output].0 == 0 {
                        x / 16 + y / 16 * 8
                    } else {
                        cell
                    };
                    let offset = frame.base + planes[output].1 + index / 8;
                    let mask = 1u8 << (index & 7);
                    if matches!(mode, NibblePaintMode::Apply) {
                        data[offset] |= mask;
                    } else {
                        data[offset] &= !mask;
                    }
                    writes += 1;
                } else if nibble && filtered != 0.0 {
                    let next = nibble_target(mode, filtered);
                    let offset = frame.base + planes[output].1 + cell / 2;
                    let shift = (cell & 1) * 4;
                    let current = ((data[offset] >> shift) & 15) as i32;
                    if if matches!(mode, NibblePaintMode::Apply) {
                        next > current
                    } else {
                        next < current
                    } {
                        // Java must replay invalid unclamped setters on its live
                        // world; no result planes are applied after this failure.
                        if !(0..=15).contains(&next) {
                            return bad();
                        }
                        data[offset] = (data[offset] & !(15 << shift)) | ((next as u8) << shift);
                        writes += 1;
                    }
                } else if !nibble && !bit && !discrete && filtered > 0.75 {
                    data[frame.base + cell] = target as u8;
                    writes += 1;
                }
            }
        }
        data[frame.record + 28..frame.record + 32].copy_from_slice(&writes.to_le_bytes());
    }
    Ok(())
}

/// V5 extends the header with mode/value/clamps/minimum/storage at bytes 160..184.
/// Each tile appends an X-major raw output and mask; filter planes and mutable halos are shared.
fn edit_heights(
    data: &mut [u8],
    frames: &[Frame],
    planes: &[(usize, usize)],
    program: &Program,
    palette: usize,
    output: usize,
    forces: usize,
) -> Result<(), WeltError> {
    let ox = frames.iter().map(|f| f.wx + f.x as i64).min().unwrap();
    let oy = frames.iter().map(|f| f.wy + f.y as i64).min().unwrap();
    let ex = frames
        .iter()
        .map(|f| f.wx + (f.x + f.w) as i64)
        .max()
        .unwrap();
    let ey = frames
        .iter()
        .map(|f| f.wy + (f.y + f.h) as i64)
        .max()
        .unwrap();
    if ex - ox > 256
        || ey - oy > 256
        || ox < i64::from(i32::MIN) + 256
        || oy < i64::from(i32::MIN) + 256
        || ex > i64::from(i32::MAX) - 256
        || ey > i64::from(i32::MAX) - 256
    {
        return bad();
    }
    for (i, f) in frames.iter().enumerate() {
        if frames[..i]
            .iter()
            .any(|other| other.wx == f.wx && other.wy == f.wy)
            || f.present
                && data[f.base + output + 65536..f.base + output + 81920]
                    .iter()
                    .any(|&v| v != 0)
        {
            return bad();
        }
    }
    let mode = int(data, 160);
    let value = float(data, 164);
    let low = float(data, 168);
    let high = float(data, 172);
    let min = int(data, 176) as f32;
    let tall = int(data, 180) != 0;
    // Height filters observe previous edits, including diagonals across tile boundaries.
    for wx in ox..ex {
        for wy in oy..ey {
            let Some(frame) = frames.iter().find(|f| {
                f.present
                    && wx >= f.wx + f.x as i64
                    && wx < f.wx + (f.x + f.w) as i64
                    && wy >= f.wy + f.y as i64
                    && wy < f.wy + (f.y + f.h) as i64
            }) else {
                continue;
            };
            let x = (wx - frame.wx) as usize;
            let y = (wy - frame.wy) as usize;
            let current = float(data, frame.base + HEIGHT + ((x + 1) * 130 + y + 1) * 4);
            let strength = float(
                data,
                frame.base + forces + ((y - frame.y) * frame.w + x - frame.x) * 4,
            );
            let filtered = float(data, 24)
                * program.modify_strength(
                    &Cell {
                        data,
                        frame,
                        planes,
                        palette,
                        cell: y * 128 + x,
                        x,
                        y,
                    },
                    strength,
                );
            if !(filtered > 0.0) {
                continue;
            }
            let target = match mode {
                0 => java_bound(current + value, high, false),
                1 => java_bound(current - value, low, true),
                _ => value,
            };
            let edited = filtered * target + (1.0 - filtered) * current;
            if mode != 2
                && !(if mode == 0 || mode == 3 {
                    edited > current
                } else {
                    edited < current
                })
            {
                continue;
            }
            let raw = ((edited - min) * 256.0) as i32;
            let raw = if tall { raw } else { raw as u16 as i32 };
            let p = frame.base + output + (x * 128 + y) * 4;
            data[p..p + 4].copy_from_slice(&raw.to_le_bytes());
            data[frame.base + output + 65536 + x * 128 + y] = 1;
            let writes = int(data, frame.record + 28) + 1;
            data[frame.record + 28..frame.record + 32].copy_from_slice(&writes.to_le_bytes());
            let quantised = raw as f32 / 256.0 + min;
            for neighbour in frames.iter().filter(|f| f.present) {
                let hx = wx - neighbour.wx + 1;
                let hy = wy - neighbour.wy + 1;
                if (0..130).contains(&hx) && (0..130).contains(&hy) {
                    let p = neighbour.base + HEIGHT + (hx as usize * 130 + hy as usize) * 4;
                    data[p..p + 4].copy_from_slice(&quantised.to_bits().to_le_bytes());
                }
            }
        }
    }
    Ok(())
}
fn java_bound(a: f32, b: f32, maximum: bool) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == 0.0 && b == 0.0 {
        f32::from_bits(if maximum {
            a.to_bits() & b.to_bits()
        } else {
            a.to_bits() | b.to_bits()
        })
    } else if maximum {
        a.max(b)
    } else {
        a.min(b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn put(d: &mut [u8], p: usize, v: i32) {
        d[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn fixture() -> Vec<u8> {
        let mut data = vec![0; 256 + LAYERS + 4];
        for (p, v) in [
            (0, 0x54504657),
            (4, 1),
            (8, 1),
            (16, 1),
            (20, 1),
            (28, 208),
            (32, 2),
            (36, -1),
            (40, 224),
            (44, 224),
            (48, 256),
            (160, 1),
            (240, 1),
            (244, 1),
            (248, 256),
        ] {
            put(&mut data, p, v);
        }
        put(&mut data, 24, 1f32.to_bits() as i32);
        put(&mut data, 108, 8f32.sqrt().to_bits() as i32);
        for i in 0..11 {
            put(&mut data, 64 + i * 4, -1);
        }
        put(&mut data, 256 + LAYERS, 1f32.to_bits() as i32);
        data
    }
    fn height_fixture() -> Vec<u8> {
        let old = fixture();
        let mut data = vec![0; old.len() + 32 + 81920];
        data[..160].copy_from_slice(&old[..160]);
        data[192..288 + LAYERS].copy_from_slice(&old[160..256 + LAYERS]);
        for (p, v) in [
            (4, 5),
            (28, 240),
            (40, 256),
            (44, 256),
            (48, 288),
            (280, 288),
            (160, 0),
            (164, 8.0f32.to_bits() as i32),
            (168, (-64.0f32).to_bits() as i32),
            (172, 319.0f32.to_bits() as i32),
            (176, -64),
            (180, 1),
        ] {
            put(&mut data, p, v);
        }
        for i in 0..130 * 130 {
            put(&mut data, 288 + HEIGHT + i * 4, 85.0f32.to_bits() as i32);
        }
        put(&mut data, 288 + LAYERS + 81920, 0.5f32.to_bits() as i32);
        data
    }
    #[test]
    fn height_filter_output_keeps_quantisation_and_mutable_neighbourhood() {
        let mut data = height_fixture();
        paint(&mut data).unwrap();
        assert_eq!(int(&data, 284), 1);
        assert_eq!(int(&data, 288 + LAYERS), (89 + 64) * 256);
        assert_eq!(data[288 + LAYERS + 65536], 1);
        assert_eq!(float(&data, 288 + HEIGHT + 131 * 4), 89.0);
    }
    #[test]
    fn unsupported_height_frames_reject_atomically() {
        for (p, v) in [(4, 4), (160, 5), (180, 2), (184, 1), (256, i32::MAX)] {
            let mut data = height_fixture();
            put(&mut data, p, v);
            let before = data.clone();
            assert!(paint(&mut data).is_err());
            assert_eq!(before, data);
        }
    }
    fn nibble_fixture(mode: i32, strength: f32, current: u8) -> Vec<u8> {
        let mut data = fixture();
        data.resize(264 + LAYERS + 8192 + 4, 0);
        for (p, v) in [
            (4, 2),
            (12, 1),
            (44, 232),
            (48, 264),
            (60, 4),
            (156, mode),
            (224, 4),
            (228, LAYERS as i32),
            (232, 0),
            (236, 0),
            (240, 0),
            (244, 0),
            (248, 1),
            (252, 1),
            (256, 264),
            (260, 0),
        ] {
            put(&mut data, p, v);
        }
        data[264 + LAYERS] = 0xa0 | current;
        put(&mut data, 264 + LAYERS + 8192, strength.to_bits() as i32);
        data
    }
    #[test]
    fn nibble_modes_preserve_java_rounding_nan_and_neighbor_nibble() {
        for (mode, strength, current, expected) in [
            (0, 1.0, 0, 15),
            (1, 0.825, 15, 2),
            (2, 0.825, 15, 3),
            (0, f32::NAN, 0, 1),
        ] {
            let mut data = nibble_fixture(mode, strength, current);
            paint(&mut data).unwrap();
            assert_eq!(data[264 + LAYERS], 0xa0 | expected);
        }
    }
    #[test]
    fn invalid_nibble_output_descriptors_reject_before_writes() {
        for (p, v) in [(60, 8), (152, 1), (156, 3), (224, 8)] {
            let mut data = nibble_fixture(0, 1.0, 0);
            put(&mut data, p, v);
            let before = data.clone();
            assert!(paint(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
    #[test]
    fn bit_outputs_preserve_threshold_nan_and_adjacent_bits() {
        for bits in [0, 1] {
            for (mode, strength, expected, writes) in [
                (0, 0.75, 0xa0, 0),
                (0, f32::from_bits(0.75f32.to_bits() + 1), 0xa1, 1),
                (0, f32::NAN, 0xa0, 0),
                (1, 1.0, 0xa0, 1),
            ] {
                let mut data = nibble_fixture(mode, strength, 0);
                let length = bytes(bits);
                data.resize(264 + LAYERS + length + 4, 0);
                put(&mut data, 4, 3);
                put(&mut data, 60, bits as i32);
                put(&mut data, 224, bits as i32);
                data[264 + LAYERS] = if mode == 1 { 0xa1 } else { 0xa0 };
                put(&mut data, 264 + LAYERS + length, strength.to_bits() as i32);
                paint(&mut data).unwrap();
                assert_eq!(data[264 + LAYERS], expected);
                assert_eq!(int(&data, 260), writes);
            }
        }
    }
    #[test]
    fn invalid_bit_descriptors_reject_before_writes() {
        let mut valid = nibble_fixture(0, 1.0, 0);
        valid.resize(264 + LAYERS + 2048 + 4, 0);
        put(&mut valid, 4, 3);
        put(&mut valid, 60, 1);
        put(&mut valid, 224, 1);
        put(&mut valid, 264 + LAYERS + 2048, 1f32.to_bits() as i32);
        paint(&mut valid.clone()).unwrap();
        for (p, v) in [(60, 4), (152, 1), (156, 2), (224, 4)] {
            let mut data = valid.clone();
            put(&mut data, p, v);
            let before = data.clone();
            assert!(paint(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
    fn discrete_fixture(bits: usize, value: i32, strength: f32) -> Vec<u8> {
        let mut data = nibble_fixture(0, strength, 0);
        data.resize(264 + LAYERS + bytes(bits) + 4, 0);
        put(&mut data, 4, 4);
        put(&mut data, 20, value);
        put(&mut data, 60, bits as i32);
        put(&mut data, 224, bits as i32);
        put(
            &mut data,
            264 + LAYERS + bytes(bits),
            strength.to_bits() as i32,
        );
        data[264 + LAYERS] = 0xa0;
        data
    }
    #[test]
    fn discrete_values_preserve_threshold_nan_palette_independence_and_adjacent_nibble() {
        for (bits, value, expected) in [(4, 7, 0xa7), (8, 200, 200), (8, 255, 255)] {
            for strength in [0.75, f32::NAN, 1.0] {
                let mut data = discrete_fixture(bits, value, strength);
                paint(&mut data).unwrap();
                assert_eq!(
                    data[264 + LAYERS],
                    if strength > 0.75 { expected } else { 0xa0 }
                );
                assert_eq!(int(&data, 260), i32::from(strength > 0.75));
            }
        }
    }
    #[test]
    fn invalid_discrete_descriptors_reject_before_writes() {
        let valid = discrete_fixture(8, 200, 1.0);
        paint(&mut valid.clone()).unwrap();
        for (p, v) in [(60, 1), (152, 1), (156, 1), (224, 4), (20, -1), (20, 256)] {
            let mut data = valid.clone();
            put(&mut data, p, v);
            let before = data.clone();
            assert!(paint(&mut data).is_err());
            assert_eq!(data, before);
        }
    }
    #[test]
    fn filters_and_threshold_write_only_requested_cells() {
        let mut data = fixture();
        paint(&mut data).unwrap();
        assert_eq!(data[256], 1);
        assert_eq!(data[257], 0);
        assert_eq!(int(&data, 252), 1);
        let mut data = fixture();
        put(&mut data, 24, f32::NAN.to_bits() as i32);
        paint(&mut data).unwrap();
        assert_eq!(data[256], 0);
        assert_eq!(int(&data, 252), 0);
    }
    #[test]
    fn malformed_frames_are_atomic_and_never_index_unchecked_offsets() {
        for p in [
            0, 4, 8, 12, 16, 20, 28, 32, 36, 40, 44, 48, 56, 60, 64, 108, 152, 156, 160, 164, 232,
            236, 240, 244, 248, 252,
        ] {
            let mut data = fixture();
            put(&mut data, p, i32::MAX);
            let before = data.clone();
            assert!(paint(&mut data).is_err(), "field {p}");
            assert_eq!(data, before, "field {p}");
        }
        let mut data = fixture();
        data.pop();
        let before = data.clone();
        assert!(paint(&mut data).is_err());
        assert_eq!(data, before);
    }
}
