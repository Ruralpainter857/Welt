//! Raw height bounds over borrowed tile storage. No Rust copies, allocation or floating arithmetic.
//! Bounded reductions keep Java's clamp and early-stop results, including corrupt raw heights.

pub const AREA: usize = 16384;
pub const UNAVAILABLE: i64 = i64::MIN;
fn pack(low: i32, high: i32) -> i64 {
    ((high as i64) << 32) | i64::from(low as u32)
}
fn reduce<T: Copy>(
    values: &[T],
    max_raw: i32,
    mode: u32,
    convert: impl Fn(T) -> i32,
) -> Option<i64> {
    if values.len() != AREA || mode > 2 {
        return None;
    }
    let mut low = i32::MAX;
    let mut high = i32::MIN;
    for chunk in values.chunks(128) {
        let bounds = chunk
            .iter()
            .copied()
            .map(&convert)
            .fold((i32::MAX, i32::MIN), |(lo, hi), v| (lo.min(v), hi.max(v)));
        low = low.min(bounds.0);
        high = high.max(bounds.1);
        match mode {
            0 if low <= 0 && high >= max_raw => return Some(pack(0, max_raw)),
            1 if low <= 0 => return Some(pack(0, 0)),
            2 if high >= max_raw => return Some(pack(max_raw, max_raw)),
            _ => {}
        }
    }
    Some(match mode {
        0 => pack(low, high),
        1 => pack(low, low),
        _ => pack(high, high),
    })
}
pub fn short_bounds(values: &[u16], max_raw: i32, mode: u32) -> Option<i64> {
    reduce(values, max_raw, mode, i32::from)
}
pub fn tall_bounds(values: &[i32], max_raw: i32, mode: u32) -> Option<i64> {
    reduce(values, max_raw, mode, |v| v)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn scalar(v: &[i32], cap: i32, mode: u32) -> i64 {
        let (mut lo, mut hi) = (i32::MAX, i32::MIN);
        for &h in v {
            lo = lo.min(h);
            hi = hi.max(h);
            if mode == 0 && lo <= 0 && hi >= cap {
                return pack(0, cap);
            }
            if mode == 1 && lo <= 0 {
                return pack(0, 0);
            }
            if mode == 2 && hi >= cap {
                return pack(cap, cap);
            }
        }
        match mode {
            0 => pack(lo, hi),
            1 => pack(lo, lo),
            _ => pack(hi, hi),
        }
    }
    #[test]
    fn bounds_match_java_clamps_extremes_and_signed_storage() {
        let mut v = vec![0; AREA];
        let mut seed = 9u32;
        for cap in [0, 65280, 98048, i32::MIN, i32::MAX] {
            for variant in 0..5 {
                for value in &mut v {
                    seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                    *value = match variant {
                        0 => seed as i32,
                        1 => (seed & 65535) as i32,
                        2 => i32::MIN,
                        3 => i32::MAX,
                        _ => 19,
                    };
                }
                for mode in 0..3 {
                    assert_eq!(tall_bounds(&v, cap, mode), Some(scalar(&v, cap, mode)));
                }
            }
        }
        let u: Vec<_> = (0..AREA).map(|i| (i * 971) as u16).collect();
        let signed: Vec<_> = u.iter().map(|&v| i32::from(v)).collect();
        for mode in 0..3 {
            assert_eq!(
                short_bounds(&u, 65280, mode),
                Some(scalar(&signed, 65280, mode))
            );
        }
    }
    #[test]
    fn rejects_wrong_lengths_and_modes() {
        assert_eq!(tall_bounds(&[], 1, 0), None);
        assert_eq!(short_bounds(&vec![0; AREA], 1, 3), None);
    }
}
