//! Portage de `FastPerlin` et `PerlinNoise` de Utils 2.2.0.
//!
//! Les opérations flottantes et les `Math.fma` Java sont reproduits en `f32`
//! avec `mul_add`; les échantillons d'or Java sont comparés sur leurs bits.

use std::collections::HashMap;
use std::sync::OnceLock;

const PERLIN_LEVELS: usize = 10_001;
const FACTOR_3D: f64 = 0.482_460_714_276_095_2;

/// Perlin Noise Java-compatible (période 256), pour 1D, 2D et 3D.
#[derive(Clone, Debug)]
pub struct PerlinNoise {
    seed: i64,
    fast: FastPerlin,
}

/// One coordinate of a two-dimensional Perlin sample. A grid can reuse this
/// preparation across every point sharing the same X or Y coordinate.
#[derive(Clone, Copy, Debug, Default)]
pub struct PerlinAxis2D {
    lattice: i32,
    fraction: f32,
    fade: f32,
}

/// Precomputed lattice and interpolation data for one 3D Perlin coordinate.
#[derive(Clone, Copy, Debug, Default)]
pub struct PerlinAxis3D {
    lattice: i32,
    fraction: f32,
    fade: f32,
}

/// Reusable hashes for samples along one vertical column of 3D Perlin noise.
/// The horizontal hash chain is fixed; gradient indices are refreshed only
/// when the vertical lattice cell changes.
#[derive(Clone, Copy, Debug, Default)]
pub struct PerlinColumn3D {
    x: PerlinAxis3D,
    y: PerlinAxis3D,
    x0y_hash: i32,
    x1y_hash: i32,
    z_lattice: i32,
    gradients: [u8; 8],
    has_z: bool,
}

impl PerlinAxis3D {
    #[inline]
    fn new(position: f64) -> Self {
        let floor = position.floor();
        let fraction = (position - floor) as f32;
        Self {
            lattice: floor as i32,
            fraction,
            fade: FastPerlin::fade(fraction),
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct PerlinCorners2D {
    h00: i32,
    h01: i32,
    h10: i32,
    h11: i32,
}

/// Prepared lattice hashes and axis fractions for a rectangular 2D sample.
#[derive(Debug)]
pub struct PerlinGrid2D {
    xs: Vec<PerlinAxis2D>,
    ys: Vec<PerlinAxis2D>,
    x_cells: Vec<usize>,
    y_cells: Vec<usize>,
    corners: Vec<PerlinCorners2D>,
    x_cell_count: usize,
}

impl PerlinGrid2D {
    #[inline]
    pub fn get(&self, col: usize, row: usize) -> f32 {
        let corners = self.corners[self.y_cells[row] * self.x_cell_count + self.x_cells[col]];
        (f64::from(FastPerlin::sample_2d_corners(
            self.xs[col],
            self.ys[row],
            corners,
        )) * 0.5) as f32
    }
}

impl PerlinAxis2D {
    #[inline]
    fn new(position: f64) -> Self {
        let floor = position.floor();
        let fraction = (position - floor) as f32;
        Self {
            lattice: floor as i32,
            fraction,
            fade: FastPerlin::fade(fraction),
        }
    }
}

impl PerlinNoise {
    pub fn new(seed: i64) -> Self {
        Self {
            seed,
            fast: FastPerlin::new(seed),
        }
    }

    pub fn seed(&self) -> i64 {
        self.seed
    }

    pub fn set_seed(&mut self, seed: i64) {
        if self.seed != seed {
            self.seed = seed;
            self.fast = FastPerlin::new(seed);
        }
    }

    #[inline]
    pub fn get_perlin_noise_1d(&self, x: f64) -> f32 {
        self.fast.sample_1d(x)
    }

    #[inline]
    pub fn get_perlin_noise_2d(&self, x: f64, y: f64) -> f32 {
        (f64::from(self.fast.sample_2d(x, y)) * 0.5) as f32
    }

    #[inline]
    pub fn prepare_axis_2d(position: f64) -> PerlinAxis2D {
        PerlinAxis2D::new(position)
    }

    #[inline]
    pub fn get_perlin_noise_2d_prepared(&self, x: PerlinAxis2D, y: PerlinAxis2D) -> f32 {
        (f64::from(self.fast.sample_2d_prepared(x, y)) * 0.5) as f32
    }

    pub fn prepare_grid_2d(&self, x_positions: &[f64], y_positions: &[f64]) -> PerlinGrid2D {
        let xs: Vec<_> = x_positions.iter().copied().map(PerlinAxis2D::new).collect();
        let ys: Vec<_> = y_positions.iter().copied().map(PerlinAxis2D::new).collect();
        let (unique_x, x_cells) = unique_lattices(&xs);
        let (unique_y, y_cells) = unique_lattices(&ys);
        let mut corners = Vec::with_capacity(unique_x.len() * unique_y.len());
        for &y in &unique_y {
            for &x in &unique_x {
                corners.push(self.fast.corners_2d(x, y));
            }
        }
        PerlinGrid2D {
            xs,
            ys,
            x_cells,
            y_cells,
            corners,
            x_cell_count: unique_x.len(),
        }
    }

    #[inline]
    pub fn get_perlin_noise_3d(&self, x: f64, y: f64, z: f64) -> f32 {
        (f64::from(self.fast.sample_3d(x, y, z)) * FACTOR_3D) as f32
    }

    #[inline]
    pub fn prepare_axis_3d(position: f64) -> PerlinAxis3D {
        PerlinAxis3D::new(position)
    }

    #[inline]
    pub fn get_perlin_noise_3d_prepared(
        &self,
        x: PerlinAxis3D,
        y: PerlinAxis3D,
        z: PerlinAxis3D,
    ) -> f32 {
        (f64::from(self.fast.sample_3d_prepared(x, y, z)) * FACTOR_3D) as f32
    }

    /// Prepare the material- and column-specific part of a vertical 3D scan.
    #[inline]
    pub fn prepare_column_3d(&self, x: PerlinAxis3D, y: PerlinAxis3D) -> PerlinColumn3D {
        self.fast.prepare_column_3d(x, y)
    }

    /// Sample a vertical point while retaining horizontal hashes and the
    /// current cell's eight gradient indices in `column`.
    #[inline]
    pub fn get_perlin_noise_3d_column_prepared(
        &self,
        column: &mut PerlinColumn3D,
        z: PerlinAxis3D,
    ) -> f32 {
        (f64::from(self.fast.sample_3d_column_prepared(column, z)) * FACTOR_3D) as f32
    }

    /// Raw `FastPerlin.sampleResult` value, exposed for cross-language parity tests.
    #[doc(hidden)]
    pub fn sample_fast_1d(&self, x: f64) -> f32 {
        self.fast.sample_1d(x)
    }

    /// Raw `FastPerlin.sampleResult` value, exposed for cross-language parity tests.
    #[doc(hidden)]
    pub fn sample_fast_2d(&self, x: f64, y: f64) -> f32 {
        self.fast.sample_2d(x, y)
    }

    /// Raw `FastPerlin.sampleResult` value, exposed for cross-language parity tests.
    #[doc(hidden)]
    pub fn sample_fast_3d(&self, x: f64, y: f64, z: f64) -> f32 {
        self.fast.sample_3d(x, y, z)
    }
}

fn unique_lattices(axes: &[PerlinAxis2D]) -> (Vec<i32>, Vec<usize>) {
    let mut index = HashMap::new();
    let mut unique = Vec::new();
    let mut cells = Vec::with_capacity(axes.len());
    for axis in axes {
        let cell = *index.entry(axis.lattice).or_insert_with(|| {
            let next = unique.len();
            unique.push(axis.lattice);
            next
        });
        cells.push(cell);
    }
    (unique, cells)
}

#[derive(Clone, Debug)]
struct FastPerlin {
    pairs: [u16; 256],
    #[cfg(target_arch = "x86_64")]
    hardware_fma: bool,
}

impl FastPerlin {
    fn new(seed: i64) -> Self {
        let mut rng = crate::rng::java_random::JavaRandom::new(seed);
        let mut permutation = [0_u8; 256];
        for (i, item) in permutation.iter_mut().enumerate() {
            *item = i as u8;
        }
        for i in 0..256 {
            let j = rng.next_int_bound((256 - i) as i32) as usize;
            permutation.swap(255 - i, j);
        }
        let mut pairs = [0_u16; 256];
        for i in 0..256 {
            pairs[i] = u16::from(permutation[i]) | (u16::from(permutation[(i + 1) & 255]) << 8);
        }
        Self {
            pairs,
            #[cfg(target_arch = "x86_64")]
            hardware_fma: supports_hardware_fma(),
        }
    }

    #[inline]
    fn pair(&self, index: i32) -> i32 {
        i32::from(self.pairs[(index as u32 & 255) as usize])
    }

    fn sample_1d(&self, x: f64) -> f32 {
        let floor_x = x.floor();
        let lx = (x - floor_x) as f32;
        let px = self.pair(floor_x as i32);
        Self::lerp(
            Self::fade(lx),
            Self::grad_1d(self.pair(self.pair(px)), lx),
            Self::grad_1d(self.pair(self.pair(px >> 8)), lx - 1.0),
        )
    }

    fn sample_2d(&self, x: f64, y: f64) -> f32 {
        self.sample_2d_prepared(PerlinAxis2D::new(x), PerlinAxis2D::new(y))
    }

    #[inline]
    fn sample_2d_prepared(&self, x: PerlinAxis2D, y: PerlinAxis2D) -> f32 {
        Self::sample_2d_corners(x, y, self.corners_2d(x.lattice, y.lattice))
    }

    #[inline]
    fn corners_2d(&self, x: i32, y: i32) -> PerlinCorners2D {
        let px = self.pair(x);
        let x0y = self.pair(px.wrapping_add(y));
        let x1y = self.pair((px >> 8).wrapping_add(y));
        PerlinCorners2D {
            h00: self.pair(x0y),
            h01: self.pair(x0y >> 8),
            h10: self.pair(x1y),
            h11: self.pair(x1y >> 8),
        }
    }

    #[inline]
    fn sample_2d_corners(x: PerlinAxis2D, y: PerlinAxis2D, corners: PerlinCorners2D) -> f32 {
        Self::lerp(
            x.fade,
            Self::lerp(
                y.fade,
                Self::grad_2d(corners.h00, x.fraction, y.fraction),
                Self::grad_2d(corners.h01, x.fraction, y.fraction - 1.0),
            ),
            Self::lerp(
                y.fade,
                Self::grad_2d(corners.h10, x.fraction - 1.0, y.fraction),
                Self::grad_2d(corners.h11, x.fraction - 1.0, y.fraction - 1.0),
            ),
        )
    }

    fn sample_3d(&self, x: f64, y: f64, z: f64) -> f32 {
        self.sample_3d_prepared(
            PerlinAxis3D::new(x),
            PerlinAxis3D::new(y),
            PerlinAxis3D::new(z),
        )
    }

    fn sample_3d_prepared(&self, x: PerlinAxis3D, y: PerlinAxis3D, z: PerlinAxis3D) -> f32 {
        let x_hash = self.pair(x.lattice);
        let x0y_hash = self.pair(x_hash.wrapping_add(y.lattice));
        let x1y_hash = self.pair((x_hash >> 8).wrapping_add(y.lattice));
        let gradients = Self::gradient_indices(x0y_hash, x1y_hash, z.lattice, self);
        self.sample_3d_with_gradients(x, y, z, gradients)
    }

    #[inline]
    fn prepare_column_3d(&self, x: PerlinAxis3D, y: PerlinAxis3D) -> PerlinColumn3D {
        let x_hash = self.pair(x.lattice);
        PerlinColumn3D {
            x,
            y,
            x0y_hash: self.pair(x_hash.wrapping_add(y.lattice)),
            x1y_hash: self.pair((x_hash >> 8).wrapping_add(y.lattice)),
            z_lattice: 0,
            gradients: [0; 8],
            has_z: false,
        }
    }

    #[inline]
    fn sample_3d_column_prepared(&self, column: &mut PerlinColumn3D, z: PerlinAxis3D) -> f32 {
        if !column.has_z || column.z_lattice != z.lattice {
            column.gradients =
                Self::gradient_indices(column.x0y_hash, column.x1y_hash, z.lattice, self);
            column.z_lattice = z.lattice;
            column.has_z = true;
        }
        self.sample_3d_with_gradients(column.x, column.y, z, column.gradients)
    }

    #[inline]
    fn gradient_indices(x0y: i32, x1y: i32, bz: i32, perlin: &Self) -> [u8; 8] {
        let x0y0z = perlin.pair(x0y.wrapping_add(bz));
        let x0y1z = perlin.pair((x0y >> 8).wrapping_add(bz));
        let x1y0z = perlin.pair(x1y.wrapping_add(bz));
        let x1y1z = perlin.pair((x1y >> 8).wrapping_add(bz));
        [
            (x0y0z & 15) as u8,
            ((x0y0z >> 8) & 15) as u8,
            (x0y1z & 15) as u8,
            ((x0y1z >> 8) & 15) as u8,
            (x1y0z & 15) as u8,
            ((x1y0z >> 8) & 15) as u8,
            (x1y1z & 15) as u8,
            ((x1y1z >> 8) & 15) as u8,
        ]
    }

    fn sample_3d_with_gradients(
        &self,
        x: PerlinAxis3D,
        y: PerlinAxis3D,
        z: PerlinAxis3D,
        gradients: [u8; 8],
    ) -> f32 {
        #[cfg(target_arch = "x86_64")]
        if self.hardware_fma {
            // SAFETY: the flag is set only after runtime detection of FMA support.
            return unsafe { self.sample_3d_prepared_hardware_fma(x, y, z, gradients) };
        }
        self.sample_3d_prepared_portable(x, y, z, gradients)
    }

    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "fma")]
    unsafe fn sample_3d_prepared_hardware_fma(
        &self,
        x: PerlinAxis3D,
        y: PerlinAxis3D,
        z: PerlinAxis3D,
        gradients: [u8; 8],
    ) -> f32 {
        use std::arch::x86_64::{_mm_cvtss_f32, _mm_fmadd_ss, _mm_set_ss};
        macro_rules! fma {
            ($a:expr, $b:expr, $c:expr) => {{
                _mm_cvtss_f32(_mm_fmadd_ss(_mm_set_ss($a), _mm_set_ss($b), _mm_set_ss($c)))
            }};
        }
        let lerp = |progress: f32, a: f32, b: f32| fma!(b - a, progress, a);
        let grad = |hash: u8, gx: f32, gy: f32, gz: f32| {
            let index = hash as usize * 3;
            fma!(
                gx,
                GRADIENTS[index],
                fma!(gy, GRADIENTS[index + 1], gz * GRADIENTS[index + 2])
            )
        };

        let lx = x.fraction;
        let ly = y.fraction;
        let lz = z.fraction;
        let py = y.fade;
        let pz = z.fade;
        lerp(
            x.fade,
            lerp(
                py,
                lerp(
                    pz,
                    grad(gradients[0], lx, ly, lz),
                    grad(gradients[1], lx, ly, lz - 1.0),
                ),
                lerp(
                    pz,
                    grad(gradients[2], lx, ly - 1.0, lz),
                    grad(gradients[3], lx, ly - 1.0, lz - 1.0),
                ),
            ),
            lerp(
                py,
                lerp(
                    pz,
                    grad(gradients[4], lx - 1.0, ly, lz),
                    grad(gradients[5], lx - 1.0, ly, lz - 1.0),
                ),
                lerp(
                    pz,
                    grad(gradients[6], lx - 1.0, ly - 1.0, lz),
                    grad(gradients[7], lx - 1.0, ly - 1.0, lz - 1.0),
                ),
            ),
        )
    }

    #[inline]
    fn sample_3d_prepared_portable(
        &self,
        x: PerlinAxis3D,
        y: PerlinAxis3D,
        z: PerlinAxis3D,
        gradients: [u8; 8],
    ) -> f32 {
        let lx = x.fraction;
        let ly = y.fraction;
        let lz = z.fraction;
        let py = y.fade;
        let pz = z.fade;
        Self::lerp(
            x.fade,
            Self::lerp(
                py,
                Self::lerp(
                    pz,
                    Self::grad_3d(i32::from(gradients[0]), lx, ly, lz),
                    Self::grad_3d(i32::from(gradients[1]), lx, ly, lz - 1.0),
                ),
                Self::lerp(
                    pz,
                    Self::grad_3d(i32::from(gradients[2]), lx, ly - 1.0, lz),
                    Self::grad_3d(i32::from(gradients[3]), lx, ly - 1.0, lz - 1.0),
                ),
            ),
            Self::lerp(
                py,
                Self::lerp(
                    pz,
                    Self::grad_3d(i32::from(gradients[4]), lx - 1.0, ly, lz),
                    Self::grad_3d(i32::from(gradients[5]), lx - 1.0, ly, lz - 1.0),
                ),
                Self::lerp(
                    pz,
                    Self::grad_3d(i32::from(gradients[6]), lx - 1.0, ly - 1.0, lz),
                    Self::grad_3d(i32::from(gradients[7]), lx - 1.0, ly - 1.0, lz - 1.0),
                ),
            ),
        )
    }

    #[inline]
    fn fade(v: f32) -> f32 {
        v * v * v * java_fma_f32(v, java_fma_f32(v, 6.0, -15.0), 10.0)
    }

    #[inline]
    fn lerp(progress: f32, a: f32, b: f32) -> f32 {
        java_fma_f32(b - a, progress, a)
    }

    #[inline]
    fn grad_1d(hash: i32, x: f32) -> f32 {
        x * GRADIENTS[(hash as usize & 15) * 3]
    }

    #[inline]
    fn grad_2d(hash: i32, x: f32, y: f32) -> f32 {
        let i = (hash as usize & 15) * 3;
        java_fma_f32(x, GRADIENTS[i], y * GRADIENTS[i + 1])
    }

    #[inline]
    fn grad_3d(hash: i32, x: f32, y: f32, z: f32) -> f32 {
        let i = (hash as usize & 15) * 3;
        java_fma_f32(
            x,
            GRADIENTS[i],
            java_fma_f32(y, GRADIENTS[i + 1], z * GRADIENTS[i + 2]),
        )
    }
}

fn supports_hardware_fma() -> bool {
    #[cfg(target_arch = "x86_64")]
    {
        std::arch::is_x86_feature_detected!("fma")
    }
    #[cfg(not(target_arch = "x86_64"))]
    {
        false
    }
}

/// Emulates Java's `Math.fma(float, float, float)` with a single final f32 rounding.
///
/// Some Windows GNU libm/toolchain combinations produce a different f32 result from
/// Java's implementation for `f32::mul_add`; f64 exactly carries the f32 product and
/// the addend through the relevant rounding range before the final cast.
#[inline]
fn java_fma_f32(a: f32, b: f32, c: f32) -> f32 {
    (f64::from(a) * f64::from(b) + f64::from(c)) as f32
}

const GRADIENTS: [f32; 48] = [
    1., 1., 0., -1., 1., 0., 1., -1., 0., -1., -1., 0., 1., 0., 1., -1., 0., 1., 1., 0., -1., -1.,
    0., -1., 0., 1., 1., 0., -1., 1., 0., 1., -1., 0., -1., -1., 1., 1., 0., 0., -1., 1., -1., 1.,
    0., 0., -1., -1.,
];

fn levels() -> &'static [f32] {
    static LEVELS: OnceLock<Vec<f32>> = OnceLock::new();
    LEVELS.get_or_init(|| {
        let values: Vec<f32> = include_str!("../../../../docs/welt/reference/noiselevels.txt")
            .split(|c: char| c == ',' || c.is_ascii_whitespace())
            .filter(|token| !token.is_empty())
            .map(|token| {
                token
                    .trim_end_matches('f')
                    .parse::<f32>()
                    .expect("invalid reference noiselevels token")
            })
            .collect();
        assert_eq!(
            values.len(),
            PERLIN_LEVELS,
            "unexpected number of Perlin noise levels"
        );
        values
    })
}

/// Maps a 0..=1000 promillage to the Java Utils 2.2.0 noise quantile table.
pub fn get_level_for_promillage(mut promillage: f32) -> f32 {
    assert!(
        !(promillage < 0.0 || promillage > 1000.0),
        "promillage outside [0, 1000]"
    );
    promillage *= 10.0;
    let integer = promillage as i32;
    let table = levels();
    if promillage == integer as f32 {
        table[integer as usize]
    } else {
        let lower = table[integer as usize];
        lower + (table[(integer + 1) as usize] - lower) * (promillage - integer as f32)
    }
}

#[cfg(test)]
mod tests {
    use super::{get_level_for_promillage, PerlinNoise};
    use crate::noise::RandomField;
    use std::collections::HashMap;

    #[test]
    fn noiselevels_has_expected_domain_and_range() {
        assert!(get_level_for_promillage(0.0).is_finite());
        assert!(get_level_for_promillage(1000.0).is_finite());
        assert!(std::panic::catch_unwind(|| get_level_for_promillage(-0.1)).is_err());
        assert!(std::panic::catch_unwind(|| get_level_for_promillage(1000.1)).is_err());
    }

    #[test]
    fn seed_reset_is_idempotent_and_changes_noise() {
        let mut noise = PerlinNoise::new(17);
        let first = noise.get_perlin_noise_2d(12.25, -91.75).to_bits();
        noise.set_seed(17);
        assert_eq!(first, noise.get_perlin_noise_2d(12.25, -91.75).to_bits());
        noise.set_seed(18);
        assert_ne!(first, noise.get_perlin_noise_2d(12.25, -91.75).to_bits());
    }

    #[test]
    fn coordinates_repeat_after_256() {
        let noise = PerlinNoise::new(123);
        assert_eq!(
            noise.get_perlin_noise_2d(-3.25, 7.5).to_bits(),
            noise.get_perlin_noise_2d(252.75, 263.5).to_bits()
        );
    }

    #[test]
    fn prepared_column_matches_individual_samples_across_vertical_cells() {
        let noise = PerlinNoise::new(0x1234_5678);
        let x = PerlinNoise::prepare_axis_3d(-17.375);
        let y = PerlinNoise::prepare_axis_3d(42.625);
        let mut column = noise.prepare_column_3d(x, y);
        for z in [
            9.75, 9.5, 9.25, 8.99, 8.75, 8.5, 8.25, 8.01, 7.75, 7.5, 7.25, 9.25, -0.125, -0.25,
            -0.375, -1.125, -1.25,
        ] {
            let z = PerlinNoise::prepare_axis_3d(z);
            assert_eq!(
                noise
                    .get_perlin_noise_3d_column_prepared(&mut column, z)
                    .to_bits(),
                noise.get_perlin_noise_3d_prepared(x, y, z).to_bits(),
                "column context differs at z={}",
                z.lattice as f32 + z.fraction,
            );
        }
    }

    #[test]
    fn prepared_3d_axes_match_scalar_noise_bit_for_bit() {
        for seed in [-0x1234_5678, 0, 0x3141_5926] {
            let noise = PerlinNoise::new(seed);
            for x in [-131_073.25, -16.411, -0.125, 0.0, 19.875, 131_072.5] {
                for y in [-257.75, -4.099, 0.0, 13.25, 4097.125] {
                    for z in [-64.5, -1.0, 0.0, 62.25, 255.875] {
                        let prepared = noise.get_perlin_noise_3d_prepared(
                            PerlinNoise::prepare_axis_3d(x),
                            PerlinNoise::prepare_axis_3d(y),
                            PerlinNoise::prepare_axis_3d(z),
                        );
                        assert_eq!(
                            prepared.to_bits(),
                            noise.get_perlin_noise_3d(x, y, z).to_bits(),
                            "seed={seed} x={x} y={y} z={z}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn java_noise_golden_is_bit_exact() {
        let mut perlin = HashMap::<i64, PerlinNoise>::new();
        let mut fields = HashMap::<(i64, usize, u64), RandomField>::new();
        let mut checked = 0usize;
        for (line_index, raw) in include_str!("../../../golden/perlin-golden.txt")
            .lines()
            .enumerate()
        {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let t: Vec<_> = line.split_whitespace().collect();
            let kind = t[0];
            let seed: i64 = t[1].parse().unwrap();
            let value_token = t.last().unwrap();
            let expected = if value_token.starts_with("EX:") {
                0
            } else {
                u32::from_str_radix(value_token.trim_start_matches("0x"), 16).unwrap()
            };
            let noise = perlin.entry(seed).or_insert_with(|| PerlinNoise::new(seed));
            let actual = match kind {
                "perlin1d" | "fastperlin1d" => {
                    assert_eq!(t.len(), 4);
                    let x: f64 = t[2].parse().unwrap();
                    if kind == "perlin1d" {
                        noise.get_perlin_noise_1d(x)
                    } else {
                        noise.sample_fast_1d(x)
                    }
                }
                "perlin2d" | "fastperlin2d" => {
                    assert_eq!(t.len(), 5);
                    let (x, y): (f64, f64) = (t[2].parse().unwrap(), t[3].parse().unwrap());
                    if kind == "perlin2d" {
                        noise.get_perlin_noise_2d(x, y)
                    } else {
                        noise.sample_fast_2d(x, y)
                    }
                }
                "perlin3d" | "fastperlin3d" => {
                    assert_eq!(t.len(), 6);
                    let (x, y, z): (f64, f64, f64) = (
                        t[2].parse().unwrap(),
                        t[3].parse().unwrap(),
                        t[4].parse().unwrap(),
                    );
                    if kind == "perlin3d" {
                        noise.get_perlin_noise_3d(x, y, z)
                    } else {
                        noise.sample_fast_3d(x, y, z)
                    }
                }
                "promillage" => {
                    assert_eq!(t.len(), 4);
                    let p: f32 = t[2].parse().unwrap();
                    if t[3].starts_with("EX:") {
                        assert!(
                            std::panic::catch_unwind(|| get_level_for_promillage(p)).is_err(),
                            "line {}: expected an out-of-range panic",
                            line_index + 1
                        );
                        checked += 1;
                        continue;
                    }
                    get_level_for_promillage(p)
                }
                "randomfield2d" | "randomfield3d" => {
                    let is_3d = kind.ends_with("3d");
                    assert_eq!(t.len(), if is_3d { 8 } else { 7 });
                    let bits: usize = t[2].parse().unwrap();
                    let scale: f64 = t[3].parse().unwrap();
                    let field = fields
                        .entry((seed, bits, scale.to_bits()))
                        .or_insert_with(|| RandomField::new(bits, scale, seed));
                    let x: i32 = t[4].parse().unwrap();
                    let y: i32 = t[5].parse().unwrap();
                    let value = if is_3d {
                        field.get_value_3d(x, y, t[6].parse().unwrap())
                    } else {
                        field.get_value_2d(x, y)
                    };
                    assert_eq!(value as u32, expected, "line {}: {line}", line_index + 1);
                    checked += 1;
                    continue;
                }
                _ => panic!("line {}: unknown noise golden kind {kind}", line_index + 1),
            };
            assert_eq!(
                actual.to_bits(),
                expected,
                "line {}: {line}",
                line_index + 1
            );
            checked += 1;
        }
        assert!(checked > 1000, "only verified {checked} noise samples");
    }
}
