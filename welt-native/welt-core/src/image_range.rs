//! Live image extrema with Java comparison, initial values and early-stop semantics.
#[derive(Clone, Copy)]
pub struct Layout {
    pub offset: usize,
    pub pixel: usize,
    pub row: usize,
    pub x: usize,
    pub y: usize,
    pub width: usize,
    pub height: usize,
    pub rows: bool,
    pub exact_stop: bool,
}
impl Layout {
    fn stopped(&self, result: &[f64], low: f64, high: f64) -> bool {
        if self.exact_stop {
            result[0] == low && result[1] == high
        } else {
            result[0] <= low && result[1] >= high
        }
    }
    pub fn valid(&self, length: usize) -> bool {
        if self.width == 0
            || self.height == 0
            || self.pixel == 0
            || self.row == 0
            || self
                .width
                .checked_mul(self.height)
                .is_none_or(|area| area > 65536)
        {
            return false;
        }
        self.x
            .checked_add(self.width - 1)
            .and_then(|x| x.checked_mul(self.pixel))
            .and_then(|x| {
                self.y
                    .checked_add(self.height - 1)
                    .and_then(|y| y.checked_mul(self.row))
                    .and_then(|y| x.checked_add(y))
            })
            .and_then(|end| end.checked_add(self.offset))
            .is_some_and(|end| end < length)
    }
}
pub fn scan<T: Copy>(
    source: &[T],
    layout: Layout,
    convert: impl Fn(T) -> f64,
    stop_low: f64,
    stop_high: f64,
    result: &mut [f64],
) -> Result<bool, &'static str> {
    if result.len() != 2 || !layout.valid(source.len()) {
        return Err("Invalid image stripe");
    }
    let (outer, inner) = if layout.rows {
        (layout.height, layout.width)
    } else {
        (layout.width, layout.height)
    };
    for a in 0..outer {
        for b in 0..inner {
            let (x, y) = if layout.rows { (b, a) } else { (a, b) };
            let value = convert(
                source[layout.offset + (layout.x + x) * layout.pixel + (layout.y + y) * layout.row],
            );
            if value < result[0] {
                result[0] = value;
            }
            if value > result[1] {
                result[1] = value;
            }
            if layout.stopped(result, stop_low, stop_high) {
                return Ok(true);
            }
        }
    }
    Ok(false)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn layout(rows: bool) -> Layout {
        Layout {
            offset: 1,
            pixel: 2,
            row: 8,
            x: 0,
            y: 0,
            width: 3,
            height: 2,
            rows,
            exact_stop: false,
        }
    }
    #[test]
    fn padded_strides_preserve_unsigned_values() {
        let mut data = [0i32; 32];
        for (i, v) in [(1, 5), (3, -1), (5, 9), (9, 7), (11, 3), (13, 8)] {
            data[i] = v;
        }
        for rows in [false, true] {
            let mut result = [f64::MAX, -f64::MAX];
            scan(
                &data,
                layout(rows),
                |v| v as u32 as f64,
                0.0,
                u32::MAX as f64,
                &mut result,
            )
            .unwrap();
            assert_eq!(result, [3.0, u32::MAX as f64]);
        }
    }
    #[test]
    fn signed_zero_nan_and_early_stop_follow_column_order() {
        let data = [0.0, f64::NEG_INFINITY, -0.0, f64::NAN, f64::INFINITY, -2.0];
        let l = Layout {
            offset: 0,
            pixel: 1,
            row: 2,
            x: 0,
            y: 0,
            width: 2,
            height: 3,
            rows: false,
            exact_stop: false,
        };
        let mut result = [f64::MAX, -f64::MAX];
        assert!(scan(&data, l, |v| v, 0.0, f64::MAX, &mut result).unwrap());
        assert_eq!(result[0].to_bits(), 0.0f64.to_bits());
        assert_eq!(result[1], f64::INFINITY);
    }
    #[test]
    fn invalid_layout_does_not_change_result() {
        let mut result = [42.0, 43.0];
        assert!(scan(&[0u8], layout(true), |v| v as f64, 0.0, 255.0, &mut result).is_err());
        assert_eq!(result, [42.0, 43.0]);
    }
}

/// Integer row stripes may reduce extrema in their native type when stop bounds
/// cover that type. The final seed comparisons keep the caller's historical seeds.
/// The conversion must be monotonic in T; the integer JNI dispatch guarantees this.
pub fn scan_integer<T: Copy + Ord>(
    source: &[T],
    layout: Layout,
    convert: impl Fn(T) -> f64,
    theoretical_low: f64,
    theoretical_high: f64,
    stop_low: f64,
    stop_high: f64,
    result: &mut [f64],
) -> Result<bool, &'static str> {
    if !layout.rows || stop_low > theoretical_low || stop_high < theoretical_high {
        return scan(source, layout, convert, stop_low, stop_high, result);
    }
    if result.len() != 2 || !layout.valid(source.len()) {
        return Err("Invalid integer stripe");
    }
    let first = layout.offset + layout.x * layout.pixel + layout.y * layout.row;
    let mut low = source[first];
    let mut high = low;
    for y in 0..layout.height {
        let base = layout.offset + layout.x * layout.pixel + (layout.y + y) * layout.row;
        if layout.pixel == 1 {
            for value in &source[base..base + layout.width] {
                low = low.min(*value);
                high = high.max(*value);
            }
        } else {
            for x in 0..layout.width {
                let value = source[base + x * layout.pixel];
                low = low.min(value);
                high = high.max(value);
            }
        }
    }
    let low = convert(low);
    let high = convert(high);
    if low < result[0] {
        result[0] = low;
    }
    if high > result[1] {
        result[1] = high;
    }
    Ok(layout.stopped(result, stop_low, stop_high))
}

#[cfg(test)]
mod integer_tests {
    use super::*;
    #[test]
    fn integer_reduction_keeps_seeds_and_partial_stop_rules() {
        let source: Vec<u16> = (0..1024).map(|i| ((i * 31) % 1024) as u16).collect();
        let layout = Layout {
            offset: 0,
            pixel: 1,
            row: 32,
            x: 0,
            y: 0,
            width: 32,
            height: 32,
            rows: true,
            exact_stop: false,
        };
        for seed in [[f64::MAX, -f64::MAX], [42.5, 800.5]] {
            for limits in [[0.0, 65535.0], [100.0, 900.0]] {
                let mut expected = seed;
                let mut actual = seed;
                let a = scan(
                    &source,
                    layout,
                    |v| v as f64,
                    limits[0],
                    limits[1],
                    &mut expected,
                )
                .unwrap();
                let b = scan_integer(
                    &source,
                    layout,
                    |v| v as f64,
                    0.0,
                    65535.0,
                    limits[0],
                    limits[1],
                    &mut actual,
                )
                .unwrap();
                assert_eq!(expected, actual);
                assert_eq!(a, b);
            }
        }
    }
}

#[cfg(test)]
mod exact_stop_tests {
    use super::*;
    #[test]
    fn unsigned_short_mask_requires_equality_not_overshoot() {
        let data = [0i16, -1, -5, -6];
        let layout = Layout {
            offset: 0,
            pixel: 1,
            row: 2,
            x: 0,
            y: 0,
            width: 2,
            height: 2,
            rows: false,
            exact_stop: true,
        };
        let mut result = [i32::MAX as f64, i32::MIN as f64];
        assert!(!scan(
            &data,
            layout,
            |v| v as i32 as u32 as f64,
            0.0,
            65535.0,
            &mut result
        )
        .unwrap());
        assert_eq!(result, [0.0, u32::MAX as f64]);
    }
}
