//! Portage de `org.pepsoft.util.RandomField` (Utils 2.2.0).

use super::PerlinNoise;

/// A deterministic integer field composed of `bits` independent Perlin channels.
#[derive(Clone, Debug)]
pub struct RandomField {
    generators: Vec<PerlinNoise>,
    bits: usize,
    scale: f64,
    seed: i64,
}

impl RandomField {
    /// Constructs a field. `bits` must be non-negative, as in Java array sizing.
    pub fn new(bits: usize, scale: f64, seed: i64) -> Self {
        let generators = (0..bits)
            .map(|index| PerlinNoise::new(seed.wrapping_add(index as i64)))
            .collect();
        Self {
            generators,
            bits,
            scale,
            seed,
        }
    }

    pub fn bits(&self) -> usize {
        self.bits
    }
    pub fn scale(&self) -> f64 {
        self.scale
    }
    pub fn seed(&self) -> i64 {
        self.seed
    }

    pub fn set_seed(&mut self, seed: i64) {
        if seed != self.seed {
            self.seed = seed;
            for (index, generator) in self.generators.iter_mut().enumerate() {
                generator.set_seed(seed.wrapping_add(index as i64));
            }
        }
    }

    #[inline]
    pub fn get_value_1d(&self, x: i32) -> i32 {
        self.get_value_3d(x, 0, 0)
    }
    #[inline]
    pub fn get_value_2d(&self, x: i32, y: i32) -> i32 {
        self.get_value_3d(x, y, 0)
    }

    pub fn get_value_3d(&self, x: i32, y: i32, z: i32) -> i32 {
        let (x, y, z) = (
            f64::from(x) / self.scale,
            f64::from(y) / self.scale,
            f64::from(z) / self.scale,
        );
        self.generators.iter().fold(0_i32, |value, noise| {
            value.wrapping_shl(1) | i32::from(noise.get_perlin_noise_3d(x, y, z) > 0.0)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::RandomField;

    #[test]
    fn zero_bits_produces_zero_and_seed_can_be_reset() {
        let mut field = RandomField::new(0, 8.0, 42);
        assert_eq!(field.get_value_3d(1, 2, 3), 0);
        field.set_seed(43);
        assert_eq!(field.seed(), 43);
    }

    #[test]
    fn output_fits_the_requested_bit_width() {
        for bits in 1..=16 {
            let field = RandomField::new(bits, 32.0, -99);
            let value = field.get_value_3d(100, -200, 300);
            assert!(value >= 0 && (value as u32) < (1_u32 << bits));
        }
    }
}
