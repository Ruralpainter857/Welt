//! Borrowed, typed bitmap windows for WTGP v4. Nothing here retains Java image data after JNI.
use welt_core::error::WeltError;

#[derive(Clone, Copy)]
struct Window<'a> {
    x: i32,
    y: i32,
    width: usize,
    height: usize,
    kind: i32,
    stride: usize,
    pixels: &'a [u8],
}
impl Window<'_> {
    const EMPTY: Self = Self {
        x: 0,
        y: 0,
        width: 0,
        height: 0,
        kind: 0,
        stride: 0,
        pixels: &[],
    };
    fn sample(&self, x: i32, y: i32) -> Result<f64, WeltError> {
        let x = x as i64 - self.x as i64;
        let y = y as i64 - self.y as i64;
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return Err(WeltError::IllegalArgument);
        }
        let at = (x as usize + y as usize * self.width) * self.stride;
        let bytes = &self.pixels[at..at + self.stride];
        Ok(match self.kind {
            1 => bytes[0] as f64,
            2 => u16::from_le_bytes(bytes.try_into().unwrap()) as f64,
            3 => i16::from_le_bytes(bytes.try_into().unwrap()) as f64,
            4 => u32::from_le_bytes(bytes.try_into().unwrap()) as f64,
            5 => f32::from_le_bytes(bytes.try_into().unwrap()) as f64,
            6 => f64::from_le_bytes(bytes.try_into().unwrap()),
            _ => return Err(WeltError::IllegalArgument),
        })
    }
}
pub(crate) struct BitmapWindows<'a> {
    windows: [Window<'a>; 64],
    count: usize,
}
impl<'a> BitmapWindows<'a> {
    pub(crate) fn empty() -> Self {
        Self {
            windows: [Window::EMPTY; 64],
            count: 0,
        }
    }
    pub(crate) fn read(
        data: &'a [u8],
        table: usize,
        count: usize,
        pixels: usize,
    ) -> Result<Self, WeltError> {
        let bad = WeltError::IllegalArgument;
        if !(1..=64).contains(&count)
            || table > data.len()
            || pixels > data.len()
            || table.checked_add(count * 32) != Some(pixels)
        {
            return Err(bad);
        }
        let mut result = Self::empty();
        for slot in 0..count {
            let d = &data[table + slot * 32..table + slot * 32 + 32];
            let word = |p: usize| i32::from_le_bytes(d[p..p + 4].try_into().unwrap());
            let (x, y, width, height, kind) = (word(0), word(4), word(8), word(12), word(16));
            let (offset, length) = (word(20), word(24));
            let stride = match kind {
                1 => 1,
                2 | 3 => 2,
                4 | 5 => 4,
                6 => 8,
                _ => return Err(bad),
            };
            if !(1..=4096).contains(&width)
                || !(1..=4096).contains(&height)
                || width as i64 * height as i64 > 262144
                || x as i64 + width as i64 - 1 > i32::MAX as i64
                || y as i64 + height as i64 - 1 > i32::MAX as i64
                || offset < 0
                || length < 0
                || word(28) != 0
                || width as usize * height as usize * stride != length as usize
            {
                return Err(bad);
            }
            let start = pixels.checked_add(offset as usize).ok_or(bad)?;
            let end = start.checked_add(length as usize).ok_or(bad)?;
            let borrowed = data.get(start..end).ok_or(bad)?;
            result.windows[slot] = Window {
                x,
                y,
                width: width as usize,
                height: height as usize,
                kind,
                stride,
                pixels: borrowed,
            };
        }
        result.count = count;
        Ok(result)
    }
    pub(crate) fn contains(&self, slot: usize) -> bool {
        slot < self.count
    }
    pub(crate) fn sample(&self, slot: usize, x: i32, y: i32) -> Result<f64, WeltError> {
        if !self.contains(slot) {
            return Err(WeltError::IllegalArgument);
        }
        self.windows[slot].sample(x, y)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn frame(kind: i32, pixel: &[u8]) -> Vec<u8> {
        let mut data = vec![0; 32];
        for (at, value) in [
            (0, -7_i32),
            (4, 11),
            (8, 1),
            (12, 1),
            (16, kind),
            (24, pixel.len() as i32),
        ] {
            data[at..at + 4].copy_from_slice(&value.to_le_bytes());
        }
        data.extend_from_slice(pixel);
        data
    }
    #[test]
    fn every_precision_preserves_value_sign_and_window_origin() {
        for (kind, bytes, expected) in [
            (1, vec![255], 255.0_f64),
            (2, u16::MAX.to_le_bytes().to_vec(), 65535.0),
            (3, (-17_i16).to_le_bytes().to_vec(), -17.0),
            (4, u32::MAX.to_le_bytes().to_vec(), 4294967295.0),
            (5, (-0_f32).to_le_bytes().to_vec(), -0.0),
            (6, 17.25_f64.to_le_bytes().to_vec(), 17.25),
        ] {
            let data = frame(kind, &bytes);
            let views = BitmapWindows::read(&data, 0, 1, 32).unwrap();
            assert_eq!(
                views.sample(0, -7, 11).unwrap().to_bits(),
                expected.to_bits()
            );
            assert!(views.sample(0, -8, 11).is_err());
            assert!(views.sample(1, -7, 11).is_err());
        }
    }
    #[test]
    fn malformed_ranges_and_reserved_fields_are_rejected() {
        let good = frame(1, &[17]);
        for (at, value) in [(8, 0_i32), (12, 4097), (16, 7), (20, -1), (24, 2), (28, 1)] {
            let mut data = good.clone();
            data[at..at + 4].copy_from_slice(&value.to_le_bytes());
            assert!(BitmapWindows::read(&data, 0, 1, 32).is_err());
        }
        assert!(BitmapWindows::read(&good[..32], 0, 1, 32).is_err());
        assert!(BitmapWindows::read(&good, 0, 65, 32).is_err());
    }
}
