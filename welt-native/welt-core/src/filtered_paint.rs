//! WFPT v1/v2/v3: shared filter and painting transactions for terrain, numeric and bit layers.
use crate::editor_filter::{CellData, Levels, Node, Predicate, Program};
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
fn child(value: i32) -> Option<usize> {
    if value == -1 {
        None
    } else {
        Some(value as usize)
    }
}

struct Frame {
    record: usize,
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
        || !matches!(int(data, 4), 1..=3)
    {
        return bad();
    }
    let nibble = int(data, 4) == 2;
    let bit = int(data, 4) == 3;
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
        || target as usize >= palette_count
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
    let mut nodes = Vec::with_capacity(count);
    let mut cursor = HEADER;
    for _ in 0..count {
        if cursor + 48 > data.len() {
            return bad();
        }
        let node = match int(data, cursor) {
            0 => {
                let value = int(data, cursor + 8);
                let plane = int(data, cursor + 12) as usize;
                let predicate = match int(data, cursor + 4) {
                    0 => Predicate::Terrain(value),
                    1 => Predicate::BitLayer(plane),
                    2 => Predicate::LayerAny(plane),
                    3 => Predicate::LayerEqual(plane, value),
                    4 => Predicate::LayerAtLeast(plane, value),
                    5 => Predicate::LayerAtMost(plane, value),
                    6 => Predicate::Biome(value),
                    7 => Predicate::Water,
                    8 => Predicate::Land,
                    9 => Predicate::Lava,
                    10 => Predicate::AutoBiome(value),
                    11 => Predicate::AnnotationAny,
                    12 => Predicate::Annotation(value),
                    _ => return bad(),
                };
                Node::Predicate {
                    predicate,
                    except: boolean(data, cursor + 16)?,
                }
            }
            1 => {
                let children = int(data, cursor + 4) as usize;
                if children > 128 || cursor + 48 + children * 4 > data.len() {
                    return bad();
                }
                let node = Node::Combined(
                    (0..children)
                        .map(|i| int(data, cursor + 48 + i * 4) as usize)
                        .collect(),
                );
                cursor += children * 4;
                node
            }
            2 => {
                let above = int(data, cursor + 20);
                let below = int(data, cursor + 24);
                let levels = match int(data, cursor + 16) {
                    -1 => None,
                    0 => Some(Levels::Between(above, below)),
                    1 => Some(Levels::Outside(above, below)),
                    2 => Some(Levels::Above(above)),
                    3 => Some(Levels::Below(below)),
                    _ => return bad(),
                };
                let selection = int(data, cursor + 4);
                if !(-1..=1).contains(&selection) {
                    return bad();
                }
                Node::Default {
                    selection: selection as i8,
                    except: child(int(data, cursor + 8)),
                    only: child(int(data, cursor + 12)),
                    levels,
                    feather: boolean(data, cursor + 28)?,
                    slope: boolean(data, cursor + 32)?
                        .then_some((float(data, cursor + 36), boolean(data, cursor + 40)?)),
                }
            }
            _ => return bad(),
        };
        cursor += 48;
        nodes.push(node);
    }
    if cursor != int(data, 28) as usize {
        return bad();
    }
    let program = Program::new(nodes, plane_count)?;
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
    if cursor != int(data, 44) as usize || cursor + tile_count * 32 > data.len() {
        return bad();
    }
    let records = cursor;
    cursor += tile_count * 32;
    if cursor != int(data, 48) as usize {
        return bad();
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
                if bit && filtered > 0.75 {
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
                } else if !nibble && !bit && filtered > 0.75 {
                    data[frame.base + cell] = target as u8;
                    writes += 1;
                }
            }
        }
        data[frame.record + 28..frame.record + 32].copy_from_slice(&writes.to_le_bytes());
    }
    Ok(())
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
