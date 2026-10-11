//! Bulk intensity updates over an immutable full-strength quadrant.
//! Multiplication must stay separate: Java float arithmetic does not use FMA.

pub fn scale(source: &[f32], level: f32, output: &mut [f32]) -> Result<(), &'static str> {
    if source.len() != output.len() || source.len() > 4_194_304 {
        return Err("Invalid brush plane length");
    }
    for (result, full) in output.iter_mut().zip(source) {
        *result = *full * level;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_float_order_and_source() {
        let source = [
            0.0,
            -0.0,
            0.125,
            0.99999,
            f32::MIN_POSITIVE,
            f32::INFINITY,
            f32::NAN,
        ];
        for level in [0.0, -0.0, 0.375, 1.0, -2.0, f32::INFINITY, f32::NAN] {
            let mut output = [42.0; 7];
            scale(&source, level, &mut output).unwrap();
            for (value, full) in output.iter().zip(source) {
                let expected = full * level;
                assert!(if expected.is_nan() {
                    value.is_nan()
                } else {
                    value.to_bits() == expected.to_bits()
                });
            }
        }
    }

    #[test]
    fn invalid_lengths_leave_output_unchanged() {
        let mut output = [42.0; 2];
        assert!(scale(&[1.0], 0.5, &mut output).is_err());
        assert_eq!(output, [42.0; 2]);
    }
}

/// Match Java `(byte) (fullStrength * 255)` including saturating float-to-int
/// conversion followed by low-byte truncation. Validate before any output write.
pub fn expand_raster(
    source: &[f32],
    stride: usize,
    radius: usize,
    output: &mut [u8],
) -> Result<(), &'static str> {
    if radius > 1023
        || stride < radius + 1
        || stride > 2048
        || source.len() != stride * stride
        || output.len() != (2 * radius + 1).pow(2)
    {
        return Err("Invalid symmetric raster dimensions");
    }
    let side = 2 * radius + 1;
    for y in 0..=radius {
        let positive = (radius + y) * side;
        let negative = (radius - y) * side;
        for x in 0..=radius {
            let value = ((source[x * stride + y] * 255.0) as i32) as u8;
            output[positive + radius + x] = value;
            output[positive + radius - x] = value;
            output[negative + radius + x] = value;
            output[negative + radius - x] = value;
        }
    }
    Ok(())
}

#[cfg(test)]
mod raster_tests {
    use super::*;
    #[test]
    fn expands_asymmetric_quadrants_and_retained_stride() {
        let source: Vec<f32> = (0..49).map(|i| (i as f32 - 12.0) / 17.0).collect();
        let mut output = vec![42; 25];
        expand_raster(&source, 7, 2, &mut output).unwrap();
        for y in 0..5usize {
            for x in 0..5usize {
                let expected = ((source[x.abs_diff(2) * 7 + y.abs_diff(2)] * 255.0) as i32) as u8;
                assert_eq!(output[y * 5 + x], expected);
            }
        }
    }
    #[test]
    fn invalid_raster_does_not_mutate_output() {
        let mut output = [42; 9];
        assert!(expand_raster(&[0.0; 4], 1, 1, &mut output).is_err());
        assert_eq!(output, [42; 9]);
    }
    #[test]
    fn follows_java_non_finite_quantization() {
        for (value, expected) in [
            (f32::NAN, 0),
            (f32::INFINITY, 255),
            (f32::NEG_INFINITY, 0),
            (-0.0, 0),
            (-1.0, 1),
        ] {
            let mut output = [42];
            expand_raster(&[value], 1, 0, &mut output).unwrap();
            assert_eq!(output[0], expected);
        }
    }
}
