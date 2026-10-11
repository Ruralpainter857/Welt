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
