//! Bit-exact port of the 48-bit LCG used by `java.util.Random`.
//!
//! The externally visible sequence matches Java for every seed. `next_gaussian`
//! uses the polar Box–Muller algorithm and caches its second sample, as does the
//! JDK. Java 17+ calls `StrictMath.log` and `StrictMath.sqrt`; this port uses
//! platform IEEE-754 math, with Gaussian sequences checked against Java goldens
//! using a small ULP tolerance for native transcendental implementations.

const MULTIPLIER: u64 = 0x5DEECE66D;
const ADDEND: u64 = 0xB;
const MASK: u64 = (1_u64 << 48) - 1;

/// Reproducible Java-compatible pseudo-random number generator.
#[derive(Clone, Debug)]
pub struct JavaRandom {
    seed: u64,
    have_next_gaussian: bool,
    next_gaussian: f64,
}

impl JavaRandom {
    /// Creates a generator with the same seed scrambling as `new Random(seed)`.
    pub fn new(seed: i64) -> Self {
        Self {
            seed: ((seed as u64) ^ MULTIPLIER) & MASK,
            have_next_gaussian: false,
            next_gaussian: 0.0,
        }
    }

    /// Resume a raw Java LCG state for a float-only native transaction.
    /// Gaussian cache ownership remains with the Java Random instance.
    pub fn from_lcg_state(state: u64) -> Option<Self> {
        if state > MASK {
            return None;
        }
        Some(Self {
            seed: state,
            have_next_gaussian: false,
            next_gaussian: 0.0,
        })
    }

    /// Return the exact raw state to commit after a successful native transaction.
    pub fn lcg_state(&self) -> u64 {
        self.seed
    }

    #[inline]
    fn next(&mut self, bits: u32) -> u32 {
        debug_assert!((1..=32).contains(&bits));
        self.seed = self.seed.wrapping_mul(MULTIPLIER).wrapping_add(ADDEND) & MASK;
        (self.seed >> (48 - bits)) as u32
    }

    /// Returns the next 32 random bits as a signed Java `int`.
    #[inline]
    pub fn next_int(&mut self) -> i32 {
        self.next(32) as i32
    }

    /// Returns a value in `[0, bound)`, matching `Random.nextInt(bound)`.
    ///
    /// Like Java, a non-positive bound is a programmer error and panics here;
    /// JNI callers should validate it and map it to `IllegalArgumentException`.
    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "bound must be positive");
        let mask = bound - 1;
        if (bound & mask) == 0 {
            return ((i64::from(bound) * i64::from(self.next(31))) >> 31) as i32;
        }
        loop {
            let bits = self.next(31) as i32;
            let value = bits % bound;
            // Java evaluates this predicate in wrapping signed 32-bit arithmetic.
            if bits.wrapping_sub(value).wrapping_add(mask) >= 0 {
                return value;
            }
        }
    }

    #[inline]
    pub fn next_boolean(&mut self) -> bool {
        self.next(1) != 0
    }

    #[inline]
    pub fn next_float(&mut self) -> f32 {
        (self.next(24) as f32) / ((1_u32 << 24) as f32)
    }

    #[inline]
    pub fn next_double(&mut self) -> f64 {
        let high = u64::from(self.next(26));
        let low = u64::from(self.next(27));
        ((high << 27) | low) as f64 / ((1_u64 << 53) as f64)
    }

    #[inline]
    pub fn next_long(&mut self) -> i64 {
        let high = i64::from(self.next(32) as i32);
        let low = i64::from(self.next(32) as i32);
        high.wrapping_shl(32).wrapping_add(low)
    }

    /// Returns the next Gaussian sample, caching the paired sample.
    ///
    /// The sequence of underlying LCG draws and cache behavior match Java.
    /// Reproduces the JDK polar algorithm and its cached second sample.
    pub fn next_gaussian(&mut self) -> f64 {
        if self.have_next_gaussian {
            self.have_next_gaussian = false;
            return self.next_gaussian;
        }
        loop {
            let x = 2.0 * self.next_double() - 1.0;
            let y = 2.0 * self.next_double() - 1.0;
            let radius_squared = x * x + y * y;
            if radius_squared < 1.0 && radius_squared != 0.0 {
                let multiplier = (-2.0 * radius_squared.ln() / radius_squared).sqrt();
                self.next_gaussian = y * multiplier;
                self.have_next_gaussian = true;
                return x * multiplier;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::JavaRandom;

    fn parse_hex32(value: &str) -> u32 {
        u32::from_str_radix(value.trim_start_matches("0x"), 16).unwrap()
    }

    fn verify_file(contents: &str) {
        let mut run: Option<(String, i64, i64, JavaRandom)> = None;
        let mut checked = 0usize;
        for (line_index, raw) in contents.lines().enumerate() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let fields: Vec<_> = line.split_whitespace().collect();
            assert!(
                fields.len() >= 4,
                "line {}: malformed: {line}",
                line_index + 1
            );
            let kind = fields[0];
            let seed: i64 = fields[1].parse().unwrap();
            let index: i64 = fields[2].parse().unwrap();
            let same_run = run
                .as_ref()
                .is_some_and(|(old_kind, old_seed, old_index, _)| {
                    old_kind == kind && *old_seed == seed && index == old_index + 1
                });
            if !same_run {
                run = Some((kind.to_owned(), seed, -1, JavaRandom::new(seed)));
                if kind != "wpPattern" {
                    assert_eq!(
                        index,
                        0,
                        "line {}: run must start at index 0",
                        line_index + 1
                    );
                }
            }
            let (run_kind, run_seed, run_index, rng) = run.as_mut().unwrap();
            assert_eq!((run_kind.as_str(), *run_seed), (kind, seed));
            *run_index = index;
            let actual = match kind {
                "nextInt" => {
                    assert_eq!(fields.len(), 4);
                    rng.next_int() as u32
                }
                "nextInt-bound-1"
                | "nextInt-bound-2"
                | "nextInt-bound-3"
                | "nextInt-bound-16"
                | "nextInt-bound-256"
                | "nextInt-bound-1000"
                | "nextInt-bound-2147483647"
                | "nextInt-bound-1073741824" => {
                    assert_eq!(fields.len(), 4);
                    let bound: i32 = kind.rsplit('-').next().unwrap().parse().unwrap();
                    rng.next_int_bound(bound) as u32
                }
                "nextLong" => {
                    assert_eq!(fields.len(), 4);
                    let expected = u64::from_str_radix(fields[3], 16).unwrap();
                    assert_eq!(rng.next_long() as u64, expected, "line {}", line_index + 1);
                    checked += 1;
                    continue;
                }
                "nextDouble" => {
                    assert_eq!(fields.len(), 4);
                    let expected = u64::from_str_radix(fields[3], 16).unwrap();
                    assert_eq!(
                        rng.next_double().to_bits(),
                        expected,
                        "line {}",
                        line_index + 1
                    );
                    checked += 1;
                    continue;
                }
                "nextFloat" => {
                    assert_eq!(fields.len(), 4);
                    let expected = parse_hex32(fields[3]);
                    assert_eq!(
                        rng.next_float().to_bits(),
                        expected,
                        "line {}",
                        line_index + 1
                    );
                    checked += 1;
                    continue;
                }
                "nextBoolean" => {
                    assert_eq!(fields.len(), 4);
                    u32::from(rng.next_boolean())
                }
                "nextGaussian" => {
                    assert_eq!(fields.len(), 4);
                    let expected = u64::from_str_radix(fields[3], 16).unwrap();
                    let actual = rng.next_gaussian().to_bits();
                    let ordered = |bits: u64| -> i128 {
                        if bits & (1 << 63) != 0 {
                            i128::from(!bits)
                        } else {
                            i128::from(bits ^ (1 << 63))
                        }
                    };
                    let ulps = (ordered(actual) - ordered(expected)).abs();
                    assert!(ulps <= 4, "line {}: {ulps} ULP", line_index + 1);
                    checked += 1;
                    continue;
                }
                "wpPattern" => {
                    assert_eq!(fields.len(), 4);
                    rng.next_int_bound(256) as u32
                }
                _ => panic!("line {}: unsupported golden kind {kind}", line_index + 1),
            };
            assert_eq!(
                actual,
                parse_hex32(fields[3]),
                "line {}: {line}",
                line_index + 1
            );
            checked += 1;
        }
        assert!(checked > 10_000, "only verified {checked} rows");
    }

    #[test]
    fn java_random_golden_sequences() {
        verify_file(include_str!("../../../golden/java-random-golden.txt"));
    }

    #[test]
    fn java_random_edge_sequences() {
        verify_file(include_str!("../../../golden/java-random-edge.txt"));
    }

    #[test]
    fn bound_rejects_non_positive_values() {
        let mut rng = JavaRandom::new(0);
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            rng.next_int_bound(0)
        }))
        .is_err());
    }
}

#[cfg(test)]
mod state_tests {
    use super::*;
    #[test]
    fn float_transactions_resume_the_exact_lcg_state() {
        assert!(JavaRandom::from_lcg_state(MASK + 1).is_none());
        for seed in [0, 42, -1, i64::MIN, i64::MAX] {
            let mut java = JavaRandom::new(seed);
            for _ in 0..37 {
                java.next_float();
            }
            let mut native = JavaRandom::from_lcg_state(java.lcg_state()).unwrap();
            for _ in 0..10003 {
                assert_eq!(java.next_float().to_bits(), native.next_float().to_bits());
            }
            assert_eq!(java.lcg_state(), native.lcg_state());
            assert_eq!(java.next_long(), native.next_long());
        }
    }
}
