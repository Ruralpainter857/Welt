//! Bulk terrain/fluid shading used by `TileRenderer`.

/// Applies the two WorldPainter `ColourUtils.multiply` passes to each ARGB pixel.
/// `packed_amounts` stores the terrain amount in the low 32 bits and the fluid
/// amount in the high 32 bits. Pixels whose amounts are both 256 are preserved
/// byte-for-byte, including transparent special colors.
pub fn shade_pixels(colours: &mut [i32], packed_amounts: &[i64]) -> Result<(), ShadeError> {
    if colours.len() != packed_amounts.len() {
        return Err(ShadeError::LengthMismatch {
            colours: colours.len(),
            amounts: packed_amounts.len(),
        });
    }
    for (colour, packed) in colours.iter_mut().zip(packed_amounts) {
        let terrain_amount = *packed as i32;
        let fluid_amount = (*packed >> 32) as i32;
        if terrain_amount == 256 && fluid_amount == 256 {
            continue;
        }
        let alpha = *colour & (0xff00_0000_u32 as i32);
        let shaded = java_multiply(java_multiply(*colour, terrain_amount), fluid_amount);
        *colour = shaded | alpha;
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShadeError {
    LengthMismatch { colours: usize, amounts: usize },
}

/// Exact integer behavior of `org.pepsoft.util.ColourUtils.multiply`.
#[inline]
fn java_multiply(colour: i32, amount: i32) -> i32 {
    if amount == 256 {
        return colour;
    }
    let mut red = ((colour & 0x00ff_0000) >> 16).wrapping_mul(amount);
    if red > 65_535 {
        red = 65_535;
    }
    let mut green = ((colour & 0x0000_ff00) >> 8).wrapping_mul(amount);
    if green > 65_535 {
        green = 65_535;
    }
    let mut blue = (colour & 0x0000_00ff).wrapping_mul(amount);
    if blue > 65_535 {
        blue = 65_535;
    }
    ((red << 8) & 0x00ff_0000) | (green & 0x0000_ff00) | ((blue >> 8) & 0x0000_00ff)
}

#[cfg(test)]
mod tests {
    use super::{shade_pixels, ShadeError};

    #[test]
    fn preserves_identity_and_alpha_and_applies_both_amounts_in_order() {
        let mut colours = [0x7f12_3456_u32 as i32, 0xff12_3456_u32 as i32];
        let amounts = [((256_i64) << 32) | 256, ((512_i64) << 32) | 128];
        shade_pixels(&mut colours, &amounts).unwrap();
        assert_eq!(colours[0], 0x7f12_3456_u32 as i32);
        assert_eq!(
            colours[1] & (0xff00_0000_u32 as i32),
            0xff00_0000_u32 as i32
        );
        assert_eq!(colours[1] & 0x00ff_ffff, 0x0012_3456);
    }

    #[test]
    fn rejects_mismatched_buffers_without_mutating_pixels() {
        let mut colours = [0xff12_3456_u32 as i32];
        assert_eq!(
            shade_pixels(&mut colours, &[]),
            Err(ShadeError::LengthMismatch {
                colours: 1,
                amounts: 0
            })
        );
        assert_eq!(colours[0], 0xff12_3456_u32 as i32);
    }
}
