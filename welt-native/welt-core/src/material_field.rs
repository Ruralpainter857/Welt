//! WMF1 deterministic material selection shared by previews and the fused viewport.
use crate::noise::PerlinNoise;

pub const HEADER: usize = 96;
fn int(data: &[u8], p: usize) -> i32 {
    i32::from_le_bytes(data[p..p + 4].try_into().unwrap())
}
fn float(data: &[u8], p: usize) -> f32 {
    f32::from_bits(int(data, p) as u32)
}
fn double(data: &[u8], p: usize) -> f64 {
    f64::from_le_bytes(data[p..p + 8].try_into().unwrap())
}

/// Evaluate every threshold in the historical reverse order; lower rows have final priority.
pub fn blob_choice(rows: usize, mut noise_and_threshold: impl FnMut(usize) -> (f32, f32)) -> usize {
    let mut selected = rows - 1;
    for row in (0..rows - 1).rev() {
        let (noise, threshold) = noise_and_threshold(row);
        if noise >= threshold {
            selected = row;
        }
    }
    selected
}

/// Exact for the f32 input domain, including Java saturation and negative half ties.
pub fn round(value: f32) -> i32 {
    (value as f64 + 0.5).floor() as i32
}
/// Match Java's double intermediate followed by a float store after each mutation.
pub fn layered_index(
    mut z: f32,
    variation: Option<(f64, i32)>,
    repeat: bool,
    slope_x: f64,
    slope_y: f64,
    x: i32,
    y: i32,
    count: i32,
) -> usize {
    if let Some((value, offset)) = variation {
        z = (z as f64 + (value - offset as f64)) as f32;
    }
    if repeat && slope_x != 0.0 {
        z = (z as f64 + slope_x * x as f64) as f32;
    }
    if repeat && slope_y != 0.0 {
        z = (z as f64 + slope_y * y as f64) as f32;
    }
    let rounded = round(z);
    if repeat {
        rounded.rem_euclid(count) as usize
    } else {
        rounded.clamp(0, count - 1) as usize
    }
}

#[derive(Default)]
pub struct Workspace {
    noises: Box<[PerlinNoise]>,
}
#[derive(Clone, Copy)]
pub struct Field<'a> {
    data: &'a [u8],
    rows: usize,
    mode: i32,
    count: i32,
    octaves: usize,
}
impl<'a> Field<'a> {
    /// Borrow selector settings only until the owning JNI frame returns.
    pub fn read(data: &'a [u8]) -> Option<Self> {
        if data.len() < HEADER
            || int(data, 0) != 0x31464d57
            || int(data, 4) != 1
            || int(data, 72) as usize != data.len()
            || data[76..80].iter().any(|v| *v != 0)
            || data[88..96].iter().any(|v| *v != 0)
        {
            return None;
        }
        let (mode, rows, count, repeat, octaves) = (
            int(data, 8),
            int(data, 12),
            int(data, 16),
            int(data, 20),
            int(data, 28),
        );
        if !matches!(mode, 2 | 3)
            || !(2..=256).contains(&rows)
            || !(0..=1).contains(&repeat)
            || !(0..=10).contains(&octaves)
            || data.len() != HEADER + rows as usize * 16
        {
            return None;
        }
        if mode == 2 {
            if count != 0 || !float(data, 24).is_finite() || float(data, 24) <= 0.0 {
                return None;
            }
            for row in 0..rows as usize - 1 {
                let scale = float(data, HEADER + row * 16) * float(data, 24);
                if !scale.is_finite()
                    || scale <= 0.0
                    || !float(data, HEADER + row * 16 + 4).is_finite()
                {
                    return None;
                }
            }
        } else {
            if !(1..=1_000_000).contains(&count)
                || !double(data, 48).is_finite()
                || !double(data, 56).is_finite()
            {
                return None;
            }
            let mut total = 0_i64;
            for row in 0..rows as usize {
                let occurrence = int(data, HEADER + row * 16 + 12);
                if occurrence <= 0 {
                    return None;
                }
                total += occurrence as i64;
            }
            if total != count as i64 {
                return None;
            }
            if octaves > 0
                && (!double(data, 32).is_finite()
                    || double(data, 32) <= 0.0
                    || !double(data, 40).is_finite())
            {
                return None;
            }
        }
        Some(Self {
            data,
            rows: rows as usize,
            mode,
            count,
            octaves: octaves as usize,
        })
    }
    /// Validate all possible Perlin inputs before any viewport output is written.
    pub fn supports_domain(&self, maximum_coordinate: f64) -> bool {
        if !maximum_coordinate.is_finite() || maximum_coordinate < 0.0 {
            return false;
        }
        if self.mode == 2 {
            (0..self.rows - 1).all(|row| {
                maximum_coordinate
                    / 4.099_f32 as f64
                    / (float(self.data, HEADER + row * 16) * float(self.data, 24)) as f64
                    <= (1_u64 << 30) as f64
            })
        } else {
            self.octaves == 0
                || maximum_coordinate.max(self.count as f64)
                    / 65.537_f32 as f64
                    / double(self.data, 32)
                    * (1_u64 << (self.octaves - 1)) as f64
                    <= (1_u64 << 30) as f64
        }
    }
    pub fn noise_count(&self) -> usize {
        if self.mode == 2 {
            self.rows - 1
        } else if self.octaves > 0 {
            1
        } else {
            0
        }
    }
    pub fn prepare(&self, seed: i64, worker: &mut Workspace) {
        let count = if self.mode == 2 {
            self.rows - 1
        } else if self.octaves > 0 {
            1
        } else {
            0
        };
        // Keep exact-sized storage: historical capacities across changing model slots
        // must not accumulate beyond the frame's total generator budget.
        if worker.noises.len() != count {
            worker.noises = (0..count).map(|_| PerlinNoise::new(0)).collect();
        }
        let base = if self.mode == 2 {
            seed.wrapping_add(1)
        } else {
            seed.wrapping_add(i64::from_le_bytes(self.data[80..88].try_into().unwrap()))
        };
        for (index, noise) in worker.noises.iter_mut().enumerate() {
            noise.set_seed(base.wrapping_add(index as i64));
        }
    }
    pub fn colour(
        &self,
        worker: &Workspace,
        x: i32,
        y: i32,
        surface: f32,
        height: i32,
        relative_layers: bool,
    ) -> i32 {
        if self.mode == 2 {
            let xx = (x as f32 / 4.099_f32) as f64;
            let yy = (y as f32 / 4.099_f32) as f64;
            let zz = (surface / 4.099_f32) as f64;
            let selected = blob_choice(self.rows, |row| {
                let p = HEADER + row * 16;
                let scale = (float(self.data, p) * float(self.data, 24)) as f64;
                (
                    worker.noises[row].get_perlin_noise_3d(xx / scale, yy / scale, zz / scale),
                    float(self.data, p + 4),
                )
            });
            int(self.data, HEADER + selected * 16 + 8)
        } else {
            let z = if relative_layers {
                if self.count - 1 > height {
                    return int(self.data, 68);
                }
                (self.count - 1) as f32
            } else {
                surface
            };
            let variation = if self.octaves == 0 {
                None
            } else {
                let scale = double(self.data, 32);
                let xx = x as f64 / 65.537_f32 as f64 / scale;
                let yy = y as f64 / 65.537_f32 as f64 / scale;
                let zz = z as f64 / 65.537_f32 as f64 / scale;
                let mut noise = 0.0_f64;
                for octave in 0..self.octaves {
                    let factor = (1_u64 << octave) as f64;
                    noise +=
                        worker.noises[0].get_perlin_noise_3d(xx * factor, yy * factor, zz * factor)
                            as f64;
                }
                Some((
                    (noise / self.octaves as f64 + 0.5) * double(self.data, 40),
                    int(self.data, 64),
                ))
            };
            let selected = layered_index(
                z,
                variation,
                int(self.data, 20) != 0,
                double(self.data, 48),
                double(self.data, 56),
                x,
                y,
                self.count,
            );
            let mut from_top = self.count - 1 - selected as i32;
            for row in 0..self.rows {
                let at = HEADER + row * 16;
                let count = int(self.data, at + 12);
                if from_top < count {
                    return int(self.data, at + 8);
                }
                from_top -= count;
            }
            unreachable!()
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn layers() -> Vec<u8> {
        let mut data = vec![0; HEADER + 32];
        for (p, v) in [
            (0, 0x31464d57),
            (4, 1),
            (8, 3),
            (12, 2),
            (16, 5),
            (20, 1),
            (68, 0x123456),
            (72, (HEADER + 32) as i32),
            (HEADER + 8, 0xaa0011),
            (HEADER + 12, 2),
            (HEADER + 24, 0x33bb44),
            (HEADER + 28, 3),
        ] {
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
        }
        data
    }
    #[test]
    fn layers_select_reversed_runs_repeat_and_respect_surface_anchor_air() {
        let data = layers();
        let field = Field::read(&data).unwrap();
        let mut w = Workspace::default();
        field.prepare(-17, &mut w);
        assert!(field.supports_domain(1.0e20));
        for (z, expected) in [
            (-1.0, 0xaa0011),
            (0.0, 0x33bb44),
            (2.0, 0x33bb44),
            (3.0, 0xaa0011),
            (5.0, 0x33bb44),
        ] {
            assert_eq!(field.colour(&w, -17, 11, z, 80, false), expected);
        }
        assert_eq!(field.colour(&w, 0, 0, 80.25, 3, true), 0x123456);
        assert_eq!(field.colour(&w, 0, 0, 80.25, 80, true), 0xaa0011);
    }
    #[test]
    fn mutation_order_and_row_priority_are_explicit() {
        let mut order = Vec::new();
        assert_eq!(
            blob_choice(4, |row| {
                order.push(row);
                (1.0, 0.0)
            }),
            0
        );
        assert_eq!(order, [2, 1, 0]);
        assert_eq!(blob_choice(4, |_| (-1.0, 0.0)), 3);
        assert_eq!(layered_index(-0.5, None, true, 0.0, 0.0, 0, 0, 5), 0);
        assert_eq!(
            layered_index(0.0, Some((1.0, 0)), true, 1.0, -1.0, 2, 3, 5),
            0
        );
    }
    #[test]
    fn workers_reuse_equal_shapes_and_release_historical_noise_storage() {
        let mut data = vec![0_u8; HEADER + 256 * 16];
        let size = data.len() as i32;
        for (p, value) in [
            (0, 0x31464d57_i32),
            (4, 1),
            (8, 2),
            (12, 256),
            (24, 1_f32.to_bits() as i32),
            (72, size),
        ] {
            data[p..p + 4].copy_from_slice(&value.to_le_bytes());
        }
        for row in 0..256 {
            data[HEADER + row * 16..HEADER + row * 16 + 4].copy_from_slice(&1_f32.to_le_bytes());
        }
        let large = Field::read(&data).unwrap();
        let mut worker = Workspace::default();
        large.prepare(1, &mut worker);
        assert_eq!(worker.noises.len(), 255);
        let pointer = worker.noises.as_ptr();
        large.prepare(2, &mut worker);
        assert_eq!(worker.noises.as_ptr(), pointer);
        data.truncate(HEADER + 32);
        let size = data.len() as i32;
        data[12..16].copy_from_slice(&2_i32.to_le_bytes());
        data[72..76].copy_from_slice(&size.to_le_bytes());
        Field::read(&data).unwrap().prepare(3, &mut worker);
        assert_eq!(worker.noises.len(), 1);
        Field::read(&layers()).unwrap().prepare(4, &mut worker);
        assert!(worker.noises.is_empty());
    }

    #[test]
    fn malformed_palette_and_selector_metadata_are_rejected() {
        let valid = layers();
        for (p, v) in [
            (16, 6_i32),
            (20, 2),
            (HEADER + 12, 0),
            (72, 1),
            (76, 1),
            (88, 1),
        ] {
            let mut data = valid.clone();
            data[p..p + 4].copy_from_slice(&v.to_le_bytes());
            assert!(Field::read(&data).is_none());
        }
    }
}
