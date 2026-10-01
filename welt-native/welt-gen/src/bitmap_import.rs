//! WBSM v1: bounded raster window sampled inside a WHIM v2 import transaction.
//! The 112-byte little-endian header precedes row-major f64 source samples.
//! Flags select integer translation, bicubic interpolation and repeated edges.
//! Coordinates reproduce Java float affine transforms and Catmull-Rom order.

use welt_core::error::WeltError;
const HEADER: usize = 112;
fn word(d: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn number(d: &[u8], p: usize) -> f64 {
    f64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn shifted(x: f32) -> f32 {
    x - if x > 0.0 {
        0.5
    } else if x < 0.0 {
        -0.5
    } else {
        x
    }
}
fn cubic(a: f64, b: f64, c: f64, d: f64, t: f32) -> f64 {
    let t = f64::from(t);
    b + 0.5 * t * (c - a + t * (2.0 * a - 5.0 * b + 4.0 * c - d + t * (3.0 * (b - c) + d - a)))
}
fn minimum(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        f64::from_bits(a.to_bits() | b.to_bits())
    } else if a <= b {
        a
    } else {
        b
    }
}
fn maximum(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        f64::from_bits(a.to_bits() & b.to_bits())
    } else if a >= b {
        a
    } else {
        b
    }
}

pub struct BitmapSource {
    base: usize,
    x: i32,
    y: i32,
    width: usize,
    height: usize,
    image_width: i32,
    image_height: i32,
    flags: i32,
    offset_x: i32,
    offset_y: i32,
    factor_x: f64,
    factor_y: f64,
    translate_x: f64,
    translate_y: f64,
    missing: f64,
    cross_x: f64,
    cross_y: f64,
}
impl BitmapSource {
    pub fn read(d: &[u8], base: usize, world_x: i32, world_y: i32) -> Result<Self, WeltError> {
        let bad = WeltError::IllegalArgument;
        if base > d.len()
            || d.len() - base < HEADER
            || word(d, base) != 0x4d53_4257
            || word(d, base + 4) != 1
            || d[base + 104..base + 112].iter().any(|b| *b != 0)
        {
            return Err(bad);
        }
        let width = word(d, base + 24);
        let height = word(d, base + 28);
        if width <= 0 || height <= 0 || i64::from(width) * i64::from(height) > 262144 {
            return Err(bad);
        }
        let end = base + HEADER + width as usize * height as usize * 8;
        if end != d.len() || word(d, base + 8) as usize != end - base {
            return Err(bad);
        }
        let s = Self {
            base: base + HEADER,
            x: word(d, base + 16),
            y: word(d, base + 20),
            width: width as usize,
            height: height as usize,
            image_width: word(d, base + 32),
            image_height: word(d, base + 36),
            flags: word(d, base + 12),
            offset_x: word(d, base + 40),
            offset_y: word(d, base + 44),
            factor_x: number(d, base + 48),
            factor_y: number(d, base + 56),
            translate_x: number(d, base + 64),
            translate_y: number(d, base + 72),
            missing: number(d, base + 80),
            cross_x: number(d, base + 88),
            cross_y: number(d, base + 96),
        };
        if !(0..=7).contains(&s.flags)
            || !(1..=4194304).contains(&s.image_width)
            || !(1..=4194304).contains(&s.image_height)
            || !s.factor_x.is_finite()
            || !s.factor_y.is_finite()
            || !s.cross_x.is_finite()
            || !s.cross_y.is_finite()
            || !s.translate_x.is_finite()
            || !s.translate_y.is_finite()
            || (s.factor_x == 0.0 && s.cross_x == 0.0)
            || (s.factor_y == 0.0 && s.cross_y == 0.0)
        {
            return Err(bad);
        }
        let bx = s.bounds(world_x, world_y, true).ok_or(bad)?;
        let by = s.bounds(world_x, world_y, false).ok_or(bad)?;
        let (x1, x2) = if s.flags & 4 == 0 {
            (
                bx.0.clamp(0, s.image_width - 1),
                bx.1.clamp(0, s.image_width - 1),
            )
        } else {
            bx
        };
        let (y1, y2) = if s.flags & 4 == 0 {
            (
                by.0.clamp(0, s.image_height - 1),
                by.1.clamp(0, s.image_height - 1),
            )
        } else {
            by
        };
        if i64::from(x1) < i64::from(s.x)
            || i64::from(x2) >= i64::from(s.x) + s.width as i64
            || i64::from(y1) < i64::from(s.y)
            || i64::from(y2) >= i64::from(s.y) + s.height as i64
        {
            return Err(bad);
        }
        Ok(s)
    }
    fn coordinate(&self, wx: i32, wy: i32, axis_x: bool) -> f32 {
        let (a, b, scale, cross, translation) = if axis_x {
            (
                wx as f32,
                wy as f32,
                self.factor_x,
                self.cross_x,
                self.translate_x,
            )
        } else {
            (
                wy as f32,
                wx as f32,
                self.factor_y,
                self.cross_y,
                self.translate_y,
            )
        };
        let shear = self.cross_x != 0.0 || self.cross_y != 0.0;
        let diagonal = self.factor_x != 0.0 || self.factor_y != 0.0;
        let mut value = if shear {
            if diagonal {
                f64::from(a) * scale + f64::from(b) * cross
            } else {
                f64::from(b) * cross
            }
        } else {
            f64::from(a) * scale
        };
        if self.translate_x != 0.0 || self.translate_y != 0.0 {
            value += translation;
        }
        value as f32
    }
    fn bounds(&self, wx: i32, wy: i32, axis_x: bool) -> Option<(i32, i32)> {
        if self.flags & 1 != 0 {
            let a = if axis_x {
                i64::from(wx) - i64::from(self.offset_x)
            } else {
                i64::from(wy) - i64::from(self.offset_y)
            };
            let b = a + 127;
            if a < -8388608 || b > 8388608 {
                return None;
            }
            return Some((a as i32, b as i32));
        }
        let mut lo = f32::INFINITY;
        let mut hi = f32::NEG_INFINITY;
        for dx in [0, 127] {
            for dy in [0, 127] {
                let value = self.coordinate(wx.checked_add(dx)?, wy.checked_add(dy)?, axis_x);
                if !value.is_finite() || !(-8388600.0..=8388600.0).contains(&value) {
                    return None;
                }
                lo = lo.min(value);
                hi = hi.max(value);
            }
        }
        if self.flags & 2 == 0 {
            return Some((lo as i32, hi as i32));
        }
        let a = shifted(lo);
        let b = shifted(hi);
        let mut min = a.min(b);
        let mut max = a.max(b);
        if lo <= 0.0 && hi >= 0.0 {
            min = min.min(-0.5);
            max = max.max(0.5);
        }
        Some((min.floor() as i32 - 1, max.floor() as i32 + 2))
    }
    fn get(&self, d: &[u8], x: i32, y: i32, extend: bool) -> f64 {
        let (x, y) = if self.flags & 4 == 0 {
            if extend {
                (
                    x.clamp(0, self.image_width - 1),
                    y.clamp(0, self.image_height - 1),
                )
            } else if x < 0 || y < 0 || x >= self.image_width || y >= self.image_height {
                return self.missing;
            } else {
                (x, y)
            }
        } else {
            (x, y)
        };
        number(
            d,
            self.base + ((x - self.x) as usize + (y - self.y) as usize * self.width) * 8,
        )
    }
    pub fn sample(&self, d: &[u8], wx: i32, wy: i32) -> f64 {
        if self.flags & 1 != 0 {
            return self.get(
                d,
                wx.wrapping_sub(self.offset_x),
                wy.wrapping_sub(self.offset_y),
                false,
            );
        }
        let fx = self.coordinate(wx, wy, true);
        let fy = self.coordinate(wx, wy, false);
        if self.flags & 2 == 0 {
            return self.get(d, fx as i32, fy as i32, false);
        }
        let fx = shifted(fx);
        let fy = shifted(fy);
        let x = fx.floor() as i32;
        let y = fy.floor() as i32;
        let dx = fx - x as f32;
        let dy = fy - y as f32;
        let a = self.get(d, x, y, true);
        let b = self.get(d, x, y + 1, true);
        let c = self.get(d, x + 1, y, true);
        let e = self.get(d, x + 1, y + 1, true);
        let lo = minimum(minimum(a, b), minimum(c, e));
        let hi = maximum(maximum(a, b), maximum(c, e));
        let values = [
            cubic(
                self.get(d, x - 1, y - 1, true),
                self.get(d, x - 1, y, true),
                self.get(d, x - 1, y + 1, true),
                self.get(d, x - 1, y + 2, true),
                dy,
            ),
            cubic(
                self.get(d, x, y - 1, true),
                a,
                b,
                self.get(d, x, y + 2, true),
                dy,
            ),
            cubic(
                self.get(d, x + 1, y - 1, true),
                c,
                e,
                self.get(d, x + 1, y + 2, true),
                dy,
            ),
            cubic(
                self.get(d, x + 2, y - 1, true),
                self.get(d, x + 2, y, true),
                self.get(d, x + 2, y + 1, true),
                self.get(d, x + 2, y + 2, true),
                dy,
            ),
        ];
        let value = cubic(values[0], values[1], values[2], values[3], dx);
        if value < lo {
            lo
        } else if value > hi {
            hi
        } else {
            value
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame(flags: i32) -> Vec<u8> {
        let side = 132usize;
        let mut d = vec![0; HEADER + side * side * 8];
        let size = d.len() as i32;
        for (p, v) in [
            (0, 0x4d53_4257),
            (4, 1),
            (8, size),
            (12, flags),
            (16, -2),
            (20, -2),
            (24, side as i32),
            (28, side as i32),
            (32, 128),
            (36, 128),
        ] {
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        for p in [48, 56] {
            d[p..p + 8].copy_from_slice(&1f64.to_le_bytes());
        }
        for y in 0..side {
            for x in 0..side {
                let value = (x as i32 - 2).clamp(0, 127) + (y as i32 - 2).clamp(0, 127) * 128;
                let p = HEADER + (x + y * side) * 8;
                d[p..p + 8].copy_from_slice(&f64::from(value).to_le_bytes());
            }
        }
        d
    }
    #[test]
    fn integer_samples_and_clamped_interpolation_preserve_a_linear_image() {
        let d = frame(1);
        let s = BitmapSource::read(&d, 0, 0, 0).unwrap();
        assert_eq!(s.sample(&d, 7, 11), 1415.0);
        let d = frame(2);
        let s = BitmapSource::read(&d, 0, 0, 0).unwrap();
        assert_eq!(s.sample(&d, 7, 11), 1350.5);
        assert_eq!(s.sample(&d, 0, 0), 0.0);
    }
    #[test]
    fn malformed_source_windows_are_rejected_before_sampling() {
        let original = frame(2);
        for (p, v) in [
            (8, 4i32),
            (12, 8),
            (24, i32::MAX),
            (32, 0),
            (16, 100),
            (104, 1),
        ] {
            let mut d = original.clone();
            d[p..p + 4].copy_from_slice(&v.to_le_bytes());
            assert!(BitmapSource::read(&d, 0, 0, 0).is_err());
        }
        assert!(BitmapSource::read(&original, original.len(), 0, 0).is_err());
    }
}
