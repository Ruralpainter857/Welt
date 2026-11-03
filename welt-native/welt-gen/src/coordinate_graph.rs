//! WTGP v3/v4 coordinate expressions and borrowed bitmap windows; quantisation/theme stay in WHIM.
use crate::noise_height_map::NoiseHeightMapBulk;
use welt_core::error::WeltError;
const HEADER: usize = 128;
const STRIDE: usize = 96;
const MAX: usize = 64;
pub(crate) const MAX_DYNAMIC_ANGLE: f64 = 1_048_576.0;
fn word(d: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn number(d: &[u8], p: usize) -> f64 {
    f64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
#[derive(Clone, Copy)]
enum Coordinates {
    Integer(i32, i32),
    Float(f32, f32),
}
impl Coordinates {
    fn floats(self) -> (f32, f32) {
        match self {
            Self::Integer(x, y) => (x as f32, y as f32),
            Self::Float(x, y) => (x, y),
        }
    }
    fn doubles(self) -> (f64, f64) {
        match self {
            Self::Integer(x, y) => (x as f64, y as f64),
            Self::Float(x, y) => (x as f64, y as f64),
        }
    }
}
enum Node {
    Constant(f64),
    Banded(i32, f64, i32, f64),
    Mandelbrot,
    Bitmap(usize),
    Bicubic(usize, [i32; 7], std::cell::RefCell<LatticeCache>),
    Noise(Box<NoiseHeightMapBulk>),
    FastNoise(fastnoise_lite::FastNoiseLite, f64),
    Binary(i32, usize, usize),
    Translate(usize, i32, i32),
    Affine(usize, [f64; 6]),
    Displacement(usize, usize, usize),
    FixedDisplacement(usize, usize, f64, f64),
}
// Fixed-size, collision-checked lattice storage. A tile epoch prevents cross-tile stale values.
// Each native worker owns its Program; no global cache or Java/native duplicate is retained.
#[derive(Clone, Copy, Default)]
struct LatticeEntry {
    coordinates: u64,
    value: f64,
    epoch: u32,
}
struct LatticeCache {
    entries: Vec<LatticeEntry>,
    epoch: u32,
}
impl Default for LatticeCache {
    fn default() -> Self {
        Self {
            entries: Vec::new(),
            epoch: 1,
        }
    }
}
impl LatticeCache {
    fn key(x: i32, y: i32) -> (usize, u64) {
        let slot =
            ((x as u32).wrapping_mul(0x9e37) ^ (y as u32).wrapping_mul(0x85eb)) as usize & 8191;
        (slot, x as u32 as u64 | ((y as u32 as u64) << 32))
    }
    fn get(&self, x: i32, y: i32) -> Option<f64> {
        let (slot, coordinates) = Self::key(x, y);
        let entry = *self.entries.get(slot)?;
        (entry.epoch == self.epoch && entry.coordinates == coordinates).then_some(entry.value)
    }
    fn insert(&mut self, x: i32, y: i32, value: f64) {
        let (slot, coordinates) = Self::key(x, y);
        if self.entries.is_empty() {
            self.entries.resize(8192, LatticeEntry::default());
        }
        self.entries[slot] = LatticeEntry {
            coordinates,
            value,
            epoch: self.epoch,
        };
    }
    fn next_tile(&mut self) {
        self.epoch = self.epoch.wrapping_add(1);
        if self.epoch == 0 {
            self.entries.fill(LatticeEntry::default());
            self.epoch = 1;
        }
    }
}
#[derive(Default)]
struct Program {
    bytes: Vec<u8>,
    nodes: Vec<Node>,
}
impl Program {
    fn configure(&mut self, records: &[u8]) -> Result<(), WeltError> {
        if self.bytes == records {
            return Ok(());
        }
        let mut nodes = Vec::with_capacity(records.len() / STRIDE);
        for (index, r) in records.as_chunks::<STRIDE>().0.iter().enumerate() {
            let op = word(r, 0);
            let left = word(r, 4);
            let right = word(r, 8);
            if word(r, 12) != 0 {
                return Err(WeltError::IllegalArgument);
            }
            let child = |v: i32| -> Result<usize, WeltError> {
                if v < 0 || v as usize >= index {
                    Err(WeltError::IllegalArgument)
                } else {
                    Ok(v as usize)
                }
            };
            let node = match op {
                0 => Node::Constant(number(r, 16)),
                1 => {
                    let height = number(r, 16);
                    let scale = number(r, 24);
                    if !height.is_finite() || !scale.is_finite() || scale <= 0.0 {
                        return Err(WeltError::IllegalArgument);
                    }
                    Node::Noise(Box::new(
                        NoiseHeightMapBulk::new(
                            height,
                            scale,
                            word(r, 40),
                            i64::from_le_bytes(r[32..40].try_into().unwrap()),
                        )
                        .map_err(|_| WeltError::IllegalArgument)?,
                    ))
                }
                12 => {
                    let first = word(r, 40);
                    let second = word(r, 44);
                    let a = number(r, 16);
                    let b = number(r, 24);
                    if first <= 0
                        || second <= 0
                        || first.checked_add(second).is_none()
                        || !a.is_finite()
                        || !b.is_finite()
                        || word(r, 48) != 0
                    {
                        return Err(WeltError::IllegalArgument);
                    }
                    Node::Banded(first, a, second, b)
                }
                14 => {
                    let bounds = std::array::from_fn(|i| word(r, 40 + i * 4));
                    if !(0..=3).contains(&bounds[0])
                        || bounds[0] & 1 != 0 && bounds[0] & 2 == 0
                        || bounds[0] & 2 != 0 && (bounds[1] <= 0 || bounds[2] <= 0)
                    {
                        return Err(WeltError::IllegalArgument);
                    }
                    Node::Bicubic(
                        child(left)?,
                        bounds,
                        std::cell::RefCell::new(LatticeCache::default()),
                    )
                }
                15 => {
                    let slot = word(r, 40);
                    if !(0..64).contains(&slot) {
                        return Err(WeltError::IllegalArgument);
                    }
                    Node::Bitmap(slot as usize)
                }
                13 => Node::Mandelbrot,
                11 => {
                    let height = number(r, 16);
                    let frequency = number(r, 24);
                    let octaves = word(r, 40);
                    if !height.is_finite()
                        || !frequency.is_finite()
                        || frequency < 0.0
                        || !(frequency as f32).is_finite()
                        || !(1..=10).contains(&octaves)
                    {
                        return Err(WeltError::IllegalArgument);
                    }
                    use fastnoise_lite::{FastNoiseLite, FractalType, NoiseType};
                    let noise_type = match word(r, 44) {
                        0 => NoiseType::OpenSimplex2,
                        1 => NoiseType::OpenSimplex2S,
                        2 => NoiseType::Cellular,
                        3 => NoiseType::Perlin,
                        4 => NoiseType::ValueCubic,
                        5 => NoiseType::Value,
                        _ => return Err(WeltError::IllegalArgument),
                    };
                    let fractal_type = match word(r, 48) {
                        0 => FractalType::None,
                        1 => FractalType::FBm,
                        2 => FractalType::Ridged,
                        3 => FractalType::PingPong,
                        _ => return Err(WeltError::IllegalArgument),
                    };
                    let seed = i64::from_le_bytes(r[32..40].try_into().unwrap()) as i32;
                    let mut noise = FastNoiseLite::with_seed(seed);
                    noise.set_noise_type(Some(noise_type));
                    noise.set_fractal_type(Some(fractal_type));
                    noise.set_fractal_octaves(Some(octaves));
                    noise.set_fractal_lacunarity(Some(2.0));
                    noise.set_fractal_gain(Some(0.5));
                    noise.set_frequency(Some(frequency as f32));
                    Node::FastNoise(noise, height)
                }
                2..=6 => Node::Binary(op, child(left)?, child(right)?),
                7 => Node::Translate(child(left)?, word(r, 40), word(r, 44)),
                8 => {
                    let matrix = std::array::from_fn(|i| number(r, 48 + i * 8));
                    if matrix.iter().any(|v| !v.is_finite()) {
                        return Err(WeltError::IllegalArgument);
                    }
                    Node::Affine(child(left)?, matrix)
                }
                9 => Node::Displacement(child(left)?, child(right)?, child(word(r, 40))?),
                10 => {
                    let base = child(left)?;
                    let angle = child(right)?;
                    let distance = child(word(r, 40))?;
                    let sine = number(r, 48);
                    let cosine = number(r, 56);
                    if !matches!(nodes[angle], Node::Constant(_))
                        || !sine.is_finite()
                        || !cosine.is_finite()
                        || sine.abs() > 1.0
                        || cosine.abs() > 1.0
                    {
                        return Err(WeltError::IllegalArgument);
                    }
                    Node::FixedDisplacement(base, distance, sine, cosine)
                }
                _ => return Err(WeltError::IllegalArgument),
            };
            nodes.push(node);
        }
        self.nodes = nodes;
        self.bytes.clear();
        self.bytes.extend_from_slice(records);
        Ok(())
    }
    #[cfg(test)]
    fn sample(&self, index: usize, coords: Coordinates) -> Result<f64, WeltError> {
        Sampling {
            program: self,
            images: &crate::coordinate_bitmap::BitmapWindows::empty(),
        }
        .sample(index, coords)
    }
    #[cfg(test)]
    fn sample_extended(
        &self,
        child: usize,
        bounds: &[i32; 7],
        x: f32,
        y: f32,
    ) -> Result<f64, WeltError> {
        Sampling {
            program: self,
            images: &crate::coordinate_bitmap::BitmapWindows::empty(),
        }
        .sample_extended(child, bounds, x, y)
    }
}
// The borrowed windows are scoped to one JNI transaction and never enter the worker cache.
struct Sampling<'p, 'i> {
    program: &'p Program,
    images: &'i crate::coordinate_bitmap::BitmapWindows<'i>,
}
impl Sampling<'_, '_> {
    fn sample_extended(
        &self,
        child: usize,
        bounds: &[i32; 7],
        mut x: f32,
        mut y: f32,
    ) -> Result<f64, WeltError> {
        if bounds[0] & 1 != 0 {
            let modulo = |a: f32, b: f32| {
                if a < 0.0 {
                    a + (-a / b).ceil() * b
                } else if a >= b {
                    a - (a / b).floor() * b
                } else {
                    a
                }
            };
            x = modulo(x, bounds[1] as f32);
            y = modulo(y, bounds[2] as f32);
        } else if bounds[0] & 2 != 0 {
            let inside = bounds[5] > 0
                && bounds[6] > 0
                && x as f64 >= bounds[3] as f64
                && y as f64 >= bounds[4] as f64
                && (x as f64) < bounds[3] as f64 + bounds[5] as f64
                && (y as f64) < bounds[4] as f64 + bounds[6] as f64;
            if !inside {
                x = if x < 0.0 {
                    0.0
                } else if x < bounds[1] as f32 {
                    x
                } else {
                    bounds[1] as f32 - 1.0
                };
                y = if y < 0.0 {
                    0.0
                } else if y < bounds[2] as f32 {
                    y
                } else {
                    bounds[2] as f32 - 1.0
                };
            }
        }
        self.sample(child, Coordinates::Float(x, y))
    }
    fn sample(&self, index: usize, coords: Coordinates) -> Result<f64, WeltError> {
        Ok(match &self.program.nodes[index] {
            Node::Bitmap(slot) => {
                let (x, y) = match coords {
                    Coordinates::Integer(x, y) => (x, y),
                    Coordinates::Float(x, y) => (x as i32, y as i32),
                };
                self.images.sample(*slot, x, y)?
            }
            Node::Constant(value) => *value,
            Node::Noise(noise) => {
                let (x, y) = coords.doubles();
                noise.get_value(x, y)
            }
            Node::Bicubic(child, bounds, cache) => match coords {
                Coordinates::Integer(x, y) => {
                    let (x, y) = if bounds[0] & 1 != 0 {
                        (x.rem_euclid(bounds[1]), y.rem_euclid(bounds[2]))
                    } else {
                        (x, y)
                    };
                    self.sample(*child, Coordinates::Integer(x, y))?
                }
                Coordinates::Float(x, y) => {
                    let half = |v: f32| {
                        if v == 0.0 || v.is_nan() {
                            v
                        } else if v < 0.0 {
                            -1.0
                        } else {
                            1.0
                        }
                    };
                    let x = x - half(x) / 2.0;
                    let y = y - half(y) / 2.0;
                    let ix = x.floor() as i32;
                    let iy = y.floor() as i32;
                    let dx = x - ix as f32;
                    let dy = y - iy as f32;
                    let sample = |a: i32, b: i32| -> Result<f64, WeltError> {
                        if let Some(value) = cache.borrow().get(a, b) {
                            return Ok(value);
                        }
                        let value = self.sample_extended(*child, bounds, a as f32, b as f32)?;
                        cache.borrow_mut().insert(a, b, value);
                        Ok(value)
                    };
                    let a = sample(ix, iy)?;
                    let b = sample(ix, iy.wrapping_add(1))?;
                    let c = sample(ix.wrapping_add(1), iy)?;
                    let d = sample(ix.wrapping_add(1), iy.wrapping_add(1))?;
                    let minimum = java_min(java_min(a, b), java_min(c, d));
                    let maximum = java_max(java_max(a, b), java_max(c, d));
                    let mut rows = [0.0; 4];
                    for (row, target) in rows.iter_mut().enumerate() {
                        let col = ix.wrapping_add(row as i32 - 1);
                        let (middle_a, middle_b) = if row == 1 {
                            (a, b)
                        } else if row == 2 {
                            (c, d)
                        } else {
                            (sample(col, iy)?, sample(col, iy.wrapping_add(1))?)
                        };
                        *target = cubic(
                            sample(col, iy.wrapping_sub(1))?,
                            middle_a,
                            middle_b,
                            sample(col, iy.wrapping_add(2))?,
                            dy,
                        );
                    }
                    let result = cubic(rows[0], rows[1], rows[2], rows[3], dx);
                    if result < minimum {
                        minimum
                    } else if result > maximum {
                        maximum
                    } else {
                        result
                    }
                }
            },
            Node::Mandelbrot => {
                let (x0, y0) = coords.floats();
                let mut x = 0_f32;
                let mut y = 0_f32;
                let mut iteration = 0;
                while x * x + y * y < 4.0 && iteration < 255 {
                    let next = x * x - y * y + x0;
                    y = 2.0 * x * y + y0;
                    x = next;
                    iteration += 1;
                }
                iteration as f64
            }
            Node::Banded(first, a, second, b) => {
                let (x, _) = coords.floats();
                let total = first.wrapping_add(*second) as f32;
                let d = if x < 0.0 {
                    x + ((-x / total) as f64).ceil() as f32 * total
                } else if x >= total {
                    x - ((x / total) as f64).floor() as f32 * total
                } else {
                    x
                };
                if d < *first as f32 {
                    b + (d / *first as f32) as f64 * (a - b)
                } else {
                    a + ((d - *first as f32) / *second as f32) as f64 * (b - a)
                }
            }
            Node::FastNoise(noise, height) => {
                // Java converts integer coordinates to float before FastNoiseLite sampling.
                let (x, y) = coords.floats();
                let normalised = (noise.get_noise_2d(x, y) + 1.0_f32) * 0.5_f32;
                f64::from(normalised) * height
            }
            Node::Binary(op, left, right) => {
                let a = self.sample(*left, coords)?;
                if *op == 4 && a == 0.0 {
                    return Ok(0.0);
                }
                let b = self.sample(*right, coords)?;
                match op {
                    2 => a + b,
                    3 => a - b,
                    4 => a * b,
                    5 => java_min(a, b),
                    6 => java_max(a, b),
                    _ => unreachable!(),
                }
            }
            Node::Translate(child, x, y) => {
                let coords = match coords {
                    Coordinates::Integer(a, b) => {
                        Coordinates::Integer(a.wrapping_sub(*x), b.wrapping_sub(*y))
                    }
                    Coordinates::Float(a, b) => Coordinates::Float(a - *x as f32, b - *y as f32),
                };
                self.sample(*child, coords)?
            }
            Node::FixedDisplacement(base, distance, sine, cosine) => {
                let (x, y) = coords.floats();
                let distance = self.sample(*distance, Coordinates::Float(x, y))?;
                let actual_x = (x as f64 + sine * distance) as f32;
                let actual_y = (y as f64 + cosine * distance) as f32;
                if !actual_x.is_finite() || !actual_y.is_finite() {
                    return Err(WeltError::IllegalArgument);
                }
                self.sample(*base, Coordinates::Float(actual_x, actual_y))?
            }
            Node::Displacement(base, angle, distance) => {
                // Java's int overload delegates to float before sampling either control.
                let (x, y) = coords.floats();
                let input = Coordinates::Float(x, y);
                let angle = self.sample(*angle, input)?;
                // Large dynamic angles require JVM-specific argument reduction. Reject
                // before WHIM writes any samples or planes, allowing complete Java fallback.
                if !angle.is_finite() || angle.abs() > MAX_DYNAMIC_ANGLE {
                    return Err(WeltError::IllegalArgument);
                }
                let distance = self.sample(*distance, input)?;
                let actual_x = (x as f64 + angle.sin() * distance) as f32;
                let actual_y = (y as f64 + angle.cos() * distance) as f32;
                if !actual_x.is_finite() || !actual_y.is_finite() {
                    return Err(WeltError::IllegalArgument);
                }
                self.sample(*base, Coordinates::Float(actual_x, actual_y))?
            }
            Node::Affine(child, m) => {
                let (x, y) = coords.floats();
                let (a, b) = affine(*m, x, y);
                if !a.is_finite() || !b.is_finite() {
                    return Err(WeltError::IllegalArgument);
                }
                self.sample(*child, Coordinates::Float(a, b))?
            }
        })
    }
}
// Preserve Java promotion of the float fraction and the original arithmetic order (no FMA).
fn cubic(y0: f64, y1: f64, y2: f64, y3: f64, fraction: f32) -> f64 {
    let mu = fraction as f64;
    y1 + 0.5
        * mu
        * (y2 - y0 + mu * (2.0 * y0 - 5.0 * y1 + 4.0 * y2 - y3 + mu * (3.0 * (y1 - y2) + y3 - y0)))
}
fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a == 0.0 && b == 0.0 {
        if a.is_sign_negative() || b.is_sign_negative() {
            -0.0
        } else {
            0.0
        }
    } else if a <= b {
        a
    } else {
        b
    }
}
fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a == 0.0 && b == 0.0 {
        if a.is_sign_negative() && b.is_sign_negative() {
            -0.0
        } else {
            0.0
        }
    } else if a >= b {
        a
    } else {
        b
    }
}
/// Match AffineTransform's state-specialised Point2D.Float path; no implicit FMA.
fn affine(m: [f64; 6], x: f32, y: f32) -> (f32, f32) {
    let x = x as f64;
    let y = y as f64;
    let translate = m[4] != 0.0 || m[5] != 0.0;
    let (a, b) = if m[1] == 0.0 && m[2] == 0.0 {
        if m[0] == 1.0 && m[3] == 1.0 {
            if translate {
                (x + m[4], y + m[5])
            } else {
                (x, y)
            }
        } else if translate {
            (x * m[0] + m[4], y * m[3] + m[5])
        } else {
            (x * m[0], y * m[3])
        }
    } else if m[0] == 0.0 && m[3] == 0.0 {
        if translate {
            (y * m[2] + m[4], x * m[1] + m[5])
        } else {
            (y * m[2], x * m[1])
        }
    } else if translate {
        (x * m[0] + y * m[2] + m[4], x * m[1] + y * m[3] + m[5])
    } else {
        (x * m[0] + y * m[2], x * m[1] + y * m[3])
    };
    (a as f32, b as f32)
}
thread_local! {static PROGRAM:std::cell::RefCell<Program>=std::cell::RefCell::new(Program::default());}
pub(crate) fn fill_source(data: &[u8], base: usize, output: &mut [f64]) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if base > data.len() || data.len() - base < HEADER {
        return Err(bad);
    }
    let d = &data[base..];
    let count = word(d, 12);
    let width = word(d, 28);
    let height = word(d, 32);
    let version = word(d, 4);
    if word(d, 0) != 0x50475457
        || !(3..=4).contains(&version)
        || word(d, 16) != 6
        || !(1..=MAX as i32).contains(&count)
        || word(d, 8) as usize != d.len()
        || !(1..=256).contains(&width)
        || !(1..=256).contains(&height)
        || width as usize * height as usize != output.len()
    {
        return Err(bad);
    }
    let records_end = HEADER + count as usize * STRIDE;
    if records_end > d.len() {
        return Err(bad);
    }
    let images = if version == 3 {
        if d.len() != records_end || d[36..HEADER].iter().any(|v| *v != 0) {
            return Err(bad);
        }
        crate::coordinate_bitmap::BitmapWindows::empty()
    } else {
        let table = word(d, 36);
        let image_count = word(d, 40);
        let pixels = word(d, 44);
        let length = word(d, 48);
        if table != records_end as i32
            || !(1..=64).contains(&image_count)
            || pixels < 0
            || length < 0
            || pixels as usize + length as usize != d.len()
            || d[52..HEADER].iter().any(|v| *v != 0)
        {
            return Err(bad);
        }
        crate::coordinate_bitmap::BitmapWindows::read(
            d,
            table as usize,
            image_count as usize,
            pixels as usize,
        )?
    };
    let ox = word(d, 20);
    let oy = word(d, 24);
    PROGRAM.with(|worker| {
        let mut p = worker.borrow_mut();
        p.configure(&d[HEADER..records_end])?;
        for node in &p.nodes {
            if let Node::Bitmap(slot) = node {
                if !images.contains(*slot) {
                    return Err(bad);
                }
            }
            if let Node::Bicubic(_, _, cache) = node {
                cache.borrow_mut().next_tile();
            }
        }
        let sampling = Sampling {
            program: &p,
            images: &images,
        };
        for y in 0..height as usize {
            for x in 0..width as usize {
                output[y * width as usize + x] = sampling.sample(
                    count as usize - 1,
                    Coordinates::Integer(ox.wrapping_add(x as i32), oy.wrapping_add(y as i32)),
                )?;
            }
        }
        Ok(())
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fixed_deformation_converts_integer_domain_before_controls() {
        let p = Program {
            bytes: vec![],
            nodes: vec![
                Node::Noise(Box::new(
                    NoiseHeightMapBulk::new(130.0, 0.7, 3, 17).unwrap(),
                )),
                Node::Constant(13.0),
                Node::FixedDisplacement(0, 1, 0.37_f64.sin(), 0.37_f64.cos()),
            ],
        };
        let x = 16_777_217_i32;
        let y = -16_777_217_i32;
        let actual_x = (x as f32 as f64 + 0.37_f64.sin() * 13.0) as f32;
        let actual_y = (y as f32 as f64 + 0.37_f64.cos() * 13.0) as f32;
        let expected = NoiseHeightMapBulk::new(130.0, 0.7, 3, 17)
            .unwrap()
            .get_value(actual_x as f64, actual_y as f64);
        assert_eq!(
            p.sample(2, Coordinates::Integer(x, y)).unwrap().to_bits(),
            expected.to_bits()
        );
    }
    #[test]
    fn invalid_third_child_preserves_previously_configured_program() {
        let mut records = vec![0_u8; 4 * STRIDE];
        records[16..24].copy_from_slice(&7.0_f64.to_le_bytes());
        records[STRIDE + 16..STRIDE + 24].copy_from_slice(&0.37_f64.to_le_bytes());
        records[2 * STRIDE + 16..2 * STRIDE + 24].copy_from_slice(&13.0_f64.to_le_bytes());
        let root = 3 * STRIDE;
        records[root..root + 4].copy_from_slice(&9_i32.to_le_bytes());
        records[root + 8..root + 12].copy_from_slice(&1_i32.to_le_bytes());
        records[root + 40..root + 44].copy_from_slice(&2_i32.to_le_bytes());
        let mut p = Program::default();
        p.configure(&records).unwrap();
        let valid = records.clone();
        records[root + 40..root + 44].copy_from_slice(&3_i32.to_le_bytes());
        assert!(p.configure(&records).is_err());
        assert_eq!(p.bytes, valid);
        assert_eq!(p.sample(3, Coordinates::Integer(0, 0)).unwrap(), 7.0);
        p.nodes[1] = Node::Constant(1.0e20);
        assert!(p.sample(3, Coordinates::Integer(0, 0)).is_err());
    }
    #[test]
    fn fast_noise_configurations_validate_and_keep_the_previous_cache() {
        let mut record = vec![0_u8; STRIDE];
        record[..4].copy_from_slice(&11_i32.to_le_bytes());
        record[16..24].copy_from_slice(&160_f64.to_le_bytes());
        record[24..32].copy_from_slice(&0.01_f64.to_le_bytes());
        record[32..40].copy_from_slice(&17_i64.to_le_bytes());
        record[40..44].copy_from_slice(&3_i32.to_le_bytes());
        let mut program = Program::default();
        for noise_type in 0..6_i32 {
            for fractal in 0..4_i32 {
                record[44..48].copy_from_slice(&noise_type.to_le_bytes());
                record[48..52].copy_from_slice(&fractal.to_le_bytes());
                program.configure(&record).unwrap();
                assert!(program
                    .sample(0, Coordinates::Integer(-128, 128))
                    .unwrap()
                    .is_finite());
            }
        }
        let valid = record.clone();
        record[44..48].copy_from_slice(&6_i32.to_le_bytes());
        assert!(program.configure(&record).is_err());
        assert_eq!(program.bytes, valid);
    }
    #[test]
    fn fast_noise_integer_and_float_entry_coordinates_agree() {
        let mut record = vec![0_u8; STRIDE];
        record[..4].copy_from_slice(&11_i32.to_le_bytes());
        record[16..24].copy_from_slice(&160_f64.to_le_bytes());
        record[24..32].copy_from_slice(&0.01_f64.to_le_bytes());
        record[32..40].copy_from_slice(&i64::MAX.to_le_bytes());
        record[40..44].copy_from_slice(&3_i32.to_le_bytes());
        let mut program = Program::default();
        program.configure(&record).unwrap();
        let integer = program
            .sample(0, Coordinates::Integer(16_777_217, -16_777_217))
            .unwrap();
        let floating = program
            .sample(
                0,
                Coordinates::Float(16_777_217_i32 as f32, -16_777_217_i32 as f32),
            )
            .unwrap();
        assert_eq!(integer.to_bits(), floating.to_bits());
        record[24..32].copy_from_slice(&0_f64.to_le_bytes());
        program.configure(&record).unwrap();
        assert_eq!(
            program
                .sample(0, Coordinates::Integer(0, 0))
                .unwrap()
                .to_bits(),
            program
                .sample(0, Coordinates::Integer(100, -100))
                .unwrap()
                .to_bits()
        );
    }
    #[test]
    fn linear_bands_keep_negative_modulo_and_mandelbrot_iteration_limit() {
        let p = Program {
            bytes: vec![],
            nodes: vec![Node::Banded(31, 43.0, 19, 177.0), Node::Mandelbrot],
        };
        assert_eq!(p.sample(0, Coordinates::Integer(-50, 7)).unwrap(), 177.0);
        assert_eq!(p.sample(0, Coordinates::Integer(31, 7)).unwrap(), 43.0);
        assert_eq!(p.sample(1, Coordinates::Integer(0, 0)).unwrap(), 255.0);
        assert_eq!(p.sample(1, Coordinates::Integer(3, 0)).unwrap(), 1.0);
    }
    #[test]
    fn smooth_band_records_reject_without_corrupting_the_cached_program() {
        let mut record = vec![0_u8; STRIDE];
        record[..4].copy_from_slice(&12_i32.to_le_bytes());
        record[16..24].copy_from_slice(&43_f64.to_le_bytes());
        record[24..32].copy_from_slice(&177_f64.to_le_bytes());
        record[40..44].copy_from_slice(&31_i32.to_le_bytes());
        record[44..48].copy_from_slice(&19_i32.to_le_bytes());
        let mut p = Program::default();
        p.configure(&record).unwrap();
        let valid = record.clone();
        record[48..52].copy_from_slice(&1_i32.to_le_bytes());
        assert!(p.configure(&record).is_err());
        assert_eq!(p.bytes, valid);
    }
    #[test]
    fn translate_keeps_integer_wrap_and_float_cast() {
        let p = Program {
            bytes: vec![],
            nodes: vec![Node::Constant(7.0), Node::Translate(0, i32::MIN, 17)],
        };
        assert_eq!(p.sample(1, Coordinates::Integer(i32::MIN, 0)).unwrap(), 7.0);
        let c = Coordinates::Integer(i32::MIN, 3);
        if let Coordinates::Integer(x, y) = c {
            assert_eq!(x.wrapping_sub(i32::MIN), 0);
            assert_eq!(y.wrapping_sub(17), -14);
        }
    }
    #[test]
    fn java_zero_nan_and_product_short_circuit() {
        assert_eq!(java_min(0.0, -0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(java_max(0.0, -0.0).to_bits(), 0.0f64.to_bits());
        assert!(java_min(f64::NAN, 3.0).is_nan());
        let p = Program {
            bytes: vec![],
            nodes: vec![
                Node::Constant(-0.0),
                Node::Constant(f64::NAN),
                Node::Binary(4, 0, 1),
            ],
        };
        assert_eq!(
            p.sample(2, Coordinates::Integer(0, 0)).unwrap().to_bits(),
            0.0f64.to_bits()
        );
    }
    #[test]
    fn specialised_affine_preserves_identity_and_shear() {
        assert_eq!(
            affine([1.0, 0.0, 0.0, 1.0, 0.0, 0.0], -0.0, 2.0)
                .0
                .to_bits(),
            (-0.0f32).to_bits()
        );
        assert_eq!(
            affine([0.0, 2.0, 3.0, 0.0, 4.0, 5.0], 7.0, 11.0),
            (37.0, 19.0)
        );
    }
}

#[cfg(test)]
mod bicubic_lattice_tests {
    use super::*;
    #[test]
    fn lattice_checks_collisions_preserves_bits_and_expires_on_tile_change() {
        let mut cache = LatticeCache::default();
        assert!(cache.entries.is_empty());
        assert_eq!(cache.get(-7, 11), None);
        cache.insert(-7, 11, -0.0);
        assert_eq!(cache.get(-7, 11).unwrap().to_bits(), (-0.0_f64).to_bits());
        cache.insert(-7 + 8192, 11, 19.0);
        assert_eq!(cache.get(-7, 11), None);
        assert_eq!(cache.get(-7 + 8192, 11), Some(19.0));
        for i in 0..50_000 {
            cache.insert(i, -i, i as f64);
        }
        assert_eq!(cache.entries.len(), 8192);
        cache.next_tile();
        assert_eq!(cache.get(49_999, -49_999), None);
        cache.epoch = u32::MAX;
        cache.insert(1, 2, 3.0);
        cache.next_tile();
        assert_eq!(cache.epoch, 1);
        assert_eq!(cache.get(1, 2), None);
    }
    fn program(bounds: [i32; 7]) -> Program {
        Program {
            bytes: vec![],
            nodes: vec![
                Node::Banded(31, 43.0, 19, 177.0),
                Node::Bicubic(0, bounds, std::cell::RefCell::new(LatticeCache::default())),
            ],
        }
    }
    #[test]
    fn integer_entry_bypasses_interpolation_and_repeating_coordinates_wrap() {
        let p = program([0, -1, -1, 0, 0, 0, 0]);
        for x in [i32::MIN, -1, 0, 1, 16_777_217, i32::MAX] {
            assert_eq!(
                p.sample(1, Coordinates::Integer(x, 7)).unwrap().to_bits(),
                p.sample(0, Coordinates::Integer(x, 7)).unwrap().to_bits()
            );
        }
        let p = program([3, 7, 5, 0, 0, 7, 5]);
        assert_eq!(
            p.sample(1, Coordinates::Integer(-1, -1)).unwrap().to_bits(),
            p.sample(0, Coordinates::Integer(6, 4)).unwrap().to_bits()
        );
        assert_eq!(
            p.sample_extended(0, &[3, 7, 5, 0, 0, 7, 5], -1.0, -1.0)
                .unwrap()
                .to_bits(),
            p.sample(0, Coordinates::Float(6.0, 4.0)).unwrap().to_bits()
        );
    }
    #[test]
    fn finite_float_edges_extend_and_cold_cache_does_not_change_results() {
        let bounds = [2, 7, 5, 0, 0, 7, 5];
        let p = program(bounds);
        for (x, expected) in [(-100.0, 0.0), (2.0, 2.0), (100.0, 6.0)] {
            assert_eq!(
                p.sample_extended(0, &bounds, x, -100.0).unwrap().to_bits(),
                p.sample(0, Coordinates::Float(expected, 0.0))
                    .unwrap()
                    .to_bits()
            );
        }
        for (x, y) in [(-1.25, -0.0), (0.0, 0.0), (3.75, 1.25), (100.0, -100.0)] {
            let before = p.sample(1, Coordinates::Float(x, y)).unwrap();
            if let Node::Bicubic(_, _, cache) = &p.nodes[1] {
                cache.borrow_mut().next_tile();
            }
            assert_eq!(
                before.to_bits(),
                p.sample(1, Coordinates::Float(x, y)).unwrap().to_bits()
            );
        }
    }
    #[test]
    fn malformed_bicubic_metadata_keeps_the_previously_configured_program() {
        let mut records = vec![0_u8; STRIDE * 2];
        records[..4].copy_from_slice(&0_i32.to_le_bytes());
        records[16..24].copy_from_slice(&7_f64.to_le_bytes());
        records[STRIDE..STRIDE + 4].copy_from_slice(&14_i32.to_le_bytes());
        records[STRIDE + 44..STRIDE + 48].copy_from_slice(&(-1_i32).to_le_bytes());
        records[STRIDE + 48..STRIDE + 52].copy_from_slice(&(-1_i32).to_le_bytes());
        let mut p = Program::default();
        p.configure(&records).unwrap();
        let valid = records.clone();
        for (offset, value) in [(40, 4_i32), (40, 1), (4, 1)] {
            records.clone_from(&valid);
            records[STRIDE + offset..STRIDE + offset + 4].copy_from_slice(&value.to_le_bytes());
            assert!(p.configure(&records).is_err());
            assert_eq!(p.bytes, valid);
        }
    }
}
