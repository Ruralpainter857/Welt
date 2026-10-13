//! WTGP v3 coordinate-domain expressions. Heights, quantisation and theme remain in WHIM.
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
    Noise(NoiseHeightMapBulk),
    FastNoise(fastnoise_lite::FastNoiseLite, f64),
    Binary(i32, usize, usize),
    Translate(usize, i32, i32),
    Affine(usize, [f64; 6]),
    Displacement(usize, usize, usize),
    FixedDisplacement(usize, usize, f64, f64),
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
        for (index, r) in records.chunks_exact(STRIDE).enumerate() {
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
                    Node::Noise(
                        NoiseHeightMapBulk::new(
                            height,
                            scale,
                            word(r, 40),
                            i64::from_le_bytes(r[32..40].try_into().unwrap()),
                        )
                        .map_err(|_| WeltError::IllegalArgument)?,
                    )
                }
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
    fn sample(&self, index: usize, coords: Coordinates) -> Result<f64, WeltError> {
        Ok(match &self.nodes[index] {
            Node::Constant(value) => *value,
            Node::Noise(noise) => {
                let (x, y) = coords.doubles();
                noise.get_value(x, y)
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
    if word(d, 0) != 0x50475457
        || word(d, 4) != 3
        || word(d, 16) != 6
        || !(1..=MAX as i32).contains(&count)
        || word(d, 8) as usize != d.len()
        || d.len() != HEADER + count as usize * STRIDE
        || !(1..=256).contains(&width)
        || !(1..=256).contains(&height)
        || width as usize * height as usize != output.len()
        || d[36..HEADER].iter().any(|v| *v != 0)
    {
        return Err(bad);
    }
    let ox = word(d, 20);
    let oy = word(d, 24);
    PROGRAM.with(|worker| {
        let mut p = worker.borrow_mut();
        p.configure(&d[HEADER..])?;
        for y in 0..height as usize {
            for x in 0..width as usize {
                output[y * width as usize + x] = p.sample(
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
                Node::Noise(NoiseHeightMapBulk::new(130.0, 0.7, 3, 17).unwrap()),
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
