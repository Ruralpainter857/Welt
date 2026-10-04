//! WHTB v5 filter metadata and live views over the shared height/theme planes.
//! Header bytes 96..128 hold program/palette/border offsets, dynamic force and dependencies.
//! Bytes 128..172 contain eleven helper plane indices; 172/176/180 contain the constant
//! biome, water terrain and Java slope diagonal. Bytes 184..256 are reserved and zero.
use super::{word, TileWork};
use welt_core::{
    auto_biome,
    editor_filter::{CellData, Program},
    error::WeltError,
};

pub(super) struct PreparedFilter {
    program: Program,
    helpers: [i32; 11],
    biomes: [i32; 10],
    palette: usize,
    count: usize,
    border: usize,
    min: i32,
    ox: i32,
    oy: i32,
    w: usize,
    h: usize,
    constant: i32,
    water_terrain: i32,
    diagonal: f32,
    dynamic: f32,
}
impl PreparedFilter {
    /// The complete metadata is checked before any packed plane or random state changes.
    pub(super) fn read(
        d: &[u8],
        kinds: &[u32],
        tiles: &[TileWork],
        border: usize,
    ) -> Result<Self, WeltError> {
        let bad = WeltError::IllegalArgument;
        let w = word(d, 52) as usize;
        let h = word(d, 56) as usize;
        let count = word(d, 108) as usize;
        let border_count = 2 * (w + h + 2);
        let program_offset = border + border_count * 4;
        if d.len() < 256
            || !(1..=256).contains(&count)
            || word(d, 112) as usize != border
            || word(d, 116) as usize != border_count
            || word(d, 96) as usize != program_offset
            || program_offset > d.len()
            || d[184..256].iter().any(|&v| v != 0)
            || word(d, 124) & !511 != 0
        {
            return Err(bad);
        }
        let (program, end) = Program::read(d, program_offset, word(d, 100) as usize, kinds.len())?;
        // Ten Java biome identifiers precede the terrain biome/forest table.
        let palette = end + 40;
        if word(d, 104) as usize != end
            || palette + count * 8 != word(d, 64) as usize
            || palette + count * 8 > d.len()
        {
            return Err(bad);
        }
        let helpers: [i32; 11] = std::array::from_fn(|i| word(d, 128 + i * 4) as i32);
        for (slot, &plane) in helpers.iter().enumerate() {
            if plane == -1 {
                continue;
            }
            if plane < 3 || plane as usize >= kinds.len() {
                return Err(bad);
            }
            let expected = match slot {
                0 => 1,
                3 => 4,
                4 | 7..=10 => 2,
                _ => 3,
            };
            if kinds[plane as usize] != expected {
                return Err(bad);
            }
        }
        let biomes = std::array::from_fn(|i| word(d, end + i * 4) as i32);
        if !(-1..=254).contains(&(word(d, 172) as i32))
            || biomes.iter().any(|&v| !(0..=254).contains(&v))
            || !(0..count as i32).contains(&(word(d, 176) as i32))
        {
            return Err(bad);
        }
        for i in 0..count {
            if word(d, palette + i * 8 + 4) > 1 {
                return Err(bad);
            }
        }
        for tile in tiles {
            for i in 0..16384 {
                if tile.planes[2].get(d, i) as usize >= count {
                    return Err(bad);
                }
            }
        }
        let diagonal = f32::from_bits(word(d, 180));
        let dynamic = f32::from_bits(word(d, 120));
        if !diagonal.is_finite()
            || diagonal <= 0.0
            || !dynamic.is_finite()
            || dynamic < 0.0
            || word(d, 44) as i32 == i32::MIN
            || word(d, 48) as i32 == i32::MIN
            || i64::from(word(d, 44) as i32) + w as i64 > i64::from(i32::MAX)
            || i64::from(word(d, 48) as i32) + h as i64 > i64::from(i32::MAX)
        {
            return Err(bad);
        }
        Ok(Self {
            program,
            helpers,
            biomes,
            palette,
            count,
            border,
            min: word(d, 20) as i32,
            ox: word(d, 44) as i32,
            oy: word(d, 48) as i32,
            w,
            h,
            constant: word(d, 172) as i32,
            water_terrain: word(d, 176) as i32,
            diagonal,
            dynamic,
        })
    }
    pub(super) fn palette_count(&self) -> usize {
        self.count
    }
    pub(super) fn strength(
        &self,
        data: &[u8],
        tiles: &[TileWork],
        tile: usize,
        cell: usize,
        wx: i32,
        wy: i32,
        force: f32,
    ) -> f32 {
        let view = Cell {
            context: self,
            data,
            tiles,
            tile,
            cell,
            wx,
            wy,
        };
        // Keep Java's dynamic * filter.modifyStrength(rawBrushForce) expression order.
        self.dynamic * self.program.modify_strength(&view, force)
    }
    fn height_at(&self, data: &[u8], tiles: &[TileWork], wx: i32, wy: i32) -> f32 {
        let x = i64::from(wx) - i64::from(self.ox);
        let y = i64::from(wy) - i64::from(self.oy);
        let left = self.h + 2;
        let index = if x == -1 {
            Some((y + 1) as usize)
        } else if x == self.w as i64 {
            Some(left + (y + 1) as usize)
        } else if y == -1 {
            Some(2 * left + x as usize)
        } else if y == self.h as i64 {
            Some(2 * left + self.w + x as usize)
        } else {
            None
        };
        if let Some(i) = index {
            return f32::from_bits(word(data, self.border + i * 4));
        }
        tiles
            .iter()
            .find(|t| t.tx == wx >> 7 && t.ty == wy >> 7)
            .map(|t| {
                t.planes[0].get(data, (wx & 127) as usize + (wy & 127) as usize * 128) as i32 as f32
                    / 256.0
                    + self.min as f32
            })
            .unwrap_or(-f32::MAX)
    }
}
struct Cell<'a> {
    context: &'a PreparedFilter,
    data: &'a [u8],
    tiles: &'a [TileWork],
    tile: usize,
    cell: usize,
    wx: i32,
    wy: i32,
}
impl Cell<'_> {
    fn helper(&self, slot: usize) -> i32 {
        let plane = self.context.helpers[slot];
        if plane < 0 {
            0
        } else {
            self.layer(plane as usize)
        }
    }
    fn height_at(&self, dx: i32, dy: i32) -> f32 {
        self.context
            .height_at(self.data, self.tiles, self.wx + dx, self.wy + dy)
    }
}
impl CellData for Cell<'_> {
    fn height(&self) -> i32 {
        (f64::from(self.height_at(0, 0)) + 0.5).floor() as i32
    }
    fn water(&self) -> i32 {
        self.tiles[self.tile].planes[1].get(self.data, self.cell) as i32
    }
    fn terrain(&self) -> i32 {
        self.tiles[self.tile].planes[2].get(self.data, self.cell) as i32
    }
    fn biome(&self) -> i32 {
        self.helper(0)
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
        self.tiles[self.tile].planes[plane].get(self.data, self.cell) as i32
    }
    fn slope(&self) -> f32 {
        let a = (self.height_at(1, 0) - self.height_at(-1, 0)).abs() / 2.0;
        let b = (self.height_at(1, 1) - self.height_at(-1, -1)).abs() / self.context.diagonal;
        let c = (self.height_at(0, 1) - self.height_at(0, -1)).abs() / 2.0;
        let d = (self.height_at(-1, 1) - self.height_at(1, -1)).abs() / self.context.diagonal;
        if [a, b, c, d].iter().any(|v| v.is_nan()) {
            f32::NAN
        } else {
            a.max(b).max(c.max(d))
        }
    }
    fn auto_biome(&self) -> i32 {
        if self.context.constant >= 0 {
            return self.context.constant;
        }
        let entry = self.context.palette + self.terrain() as usize * 8;
        let flags = u8::from(self.helper(5) != 0)
            | (u8::from(self.helper(6) != 0) << 1)
            | (u8::from(self.helper(9) > 0) << 2)
            | (u8::from(self.helper(10) > 0) << 3)
            | (u8::from(self.helper(7) > 0 || self.helper(8) > 0) << 4)
            | (u8::from(self.lava()) << 5)
            | (u8::from(self.terrain() == self.context.water_terrain) << 6)
            | (u8::from(word(self.data, entry + 4) != 0) << 7);
        auto_biome::classify(
            -1,
            self.water().wrapping_sub(self.height()),
            flags,
            word(self.data, entry) as i32,
            &self.context.biomes,
        )
    }
}
