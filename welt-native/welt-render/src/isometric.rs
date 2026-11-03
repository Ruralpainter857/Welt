//! Integer-half-coordinate rasterisation used by the isometric tile renderer.
//! Pixels use Java2D's straight ARGB source-over arithmetic, including its
//! separate source-alpha and antialias coverage multiplication.

pub const MAX_BYTES: usize = 32 * 1024 * 1024;
const AREA: usize = 128 * 128;
const HEADER: usize = 64;
const RECORD: usize = 32;

fn int(data: &[u8], at: usize) -> i32 {
    i32::from_le_bytes(data[at..at + 4].try_into().unwrap())
}

/// WI3D version 1. All offsets, column spans and the complete output are checked
/// before pixels are touched, allowing the caller to fall back to Java.
pub fn render(data: &mut [u8]) -> Result<(), &'static str> {
    let bad = "invalid isometric frame";
    if data.len() < HEADER + AREA * RECORD
        || data.len() > MAX_BYTES
        || !cfg!(target_endian = "little")
    {
        return Err(bad);
    }
    let height = int(data, 16);
    let rotation = int(data, 20);
    let minimum = int(data, 24);
    let maximum = int(data, 28);
    let output = int(data, 52) as usize;
    let tops = HEADER + AREA * RECORD;
    let colours = int(data, 56) as usize;
    if int(data, 0) != 0x44334957
        || int(data, 4) != 1
        || int(data, 8) != AREA as i32
        || int(data, 12) != 256
        || !(0..=3).contains(&rotation)
        || !(128..=8192).contains(&height)
        || minimum < -1_000_000
        || maximum > 1_000_000
        || maximum < minimum
        || maximum - minimum + 128 != height
        || int(data, 48) != HEADER as i32
        || int(data, 60) != 0
        || colours > 2_097_152
        || output != tops + colours * 4
        || output + 256 * height as usize * 4 != data.len()
    {
        return Err(bad);
    }
    for cell in 0..AREA {
        let at = HEADER + cell * RECORD;
        let terrain = int(data, at);
        let fluid = int(data, at + 4);
        let subsurface = int(data, at + 8);
        let flags = int(data, at + 12);
        let offset = int(data, at + 16) as usize;
        if !(0..=15).contains(&flags) || int(data, at + 28) != 0 {
            return Err(bad);
        }
        if flags & 1 == 0 {
            continue;
        }
        if terrain < minimum
            || terrain > maximum
            || fluid < minimum
            || fluid > maximum
            || subsurface < -8192
            || subsurface > maximum - minimum
        {
            return Err(bad);
        }
        let count = (terrain - minimum - subsurface - 1).max(0) as usize;
        if flags & 8 == 0
            && (offset < tops
                || offset > output
                || count > (output - offset) / 4
                || offset % 4 != 0)
        {
            return Err(bad);
        }
    }
    let stone = int(data, 32) as u32;
    let water = int(data, 36) as u32;
    let lava = int(data, 40) as u32;
    let ice = int(data, 44) as u32;
    let (inputs, pixels) = data.split_at_mut(output);
    // JNI direct storage is aligned; refuse other callers without writing anything.
    let (prefix, words, suffix) = unsafe { pixels.align_to_mut::<u32>() };
    if !prefix.is_empty() || !suffix.is_empty() {
        return Err(bad);
    }
    words.fill(0);
    let mut raster = Raster {
        pixels: words,
        width: 256,
        height: height as usize,
    };
    for x in 0..128 {
        for y in 0..128 {
            let (tx, ty) = match rotation {
                0 => (x, y),
                1 => (y, 127 - x),
                2 => (127 - x, 127 - y),
                _ => (127 - y, x),
            };
            let at = HEADER + (tx + ty * 128) as usize * RECORD;
            let flags = int(inputs, at + 12);
            if flags & 1 == 0 {
                continue;
            }
            let terrain = int(inputs, at);
            let fluid = int(inputs, at + 4);
            let subsurface = int(inputs, at + 8);
            let ix = 127 + x - y;
            let iy = x + y + 2 * (maximum - minimum) + 1;
            raster.rectangle(ix, iy - 2 * subsurface, subsurface, stone);
            let count = (terrain - minimum - subsurface - 1).max(0);
            if flags & 8 != 0 {
                raster.rectangle(
                    ix,
                    iy - 2 * (terrain - minimum) + 2,
                    count,
                    int(inputs, at + 24) as u32,
                );
            } else {
                let offset = int(inputs, at + 16) as usize;
                for z in 0..count {
                    raster.line(
                        ix,
                        iy - 2 * (subsurface + 1 + z),
                        int(inputs, offset + z as usize * 4) as u32,
                        255,
                    );
                }
            }
            raster.line(
                ix,
                iy - 2 * (terrain - minimum),
                int(inputs, at + 20) as u32,
                255,
            );
            if fluid > terrain {
                for z in terrain + 1..=fluid {
                    let frozen = z == fluid && flags & 4 != 0;
                    raster.line(
                        ix,
                        iy - 2 * (z - minimum),
                        if frozen {
                            ice
                        } else if flags & 2 != 0 {
                            lava
                        } else {
                            water
                        },
                        if frozen { 255 } else { 96 },
                    );
                }
            }
        }
    }
    Ok(())
}

fn mul(a: u32, b: u32) -> u32 {
    (a * b + 127) / 255
}

fn blend(destination: u32, colour: u32, alpha: u32, coverage: u32) -> u32 {
    if alpha == 255 && coverage == 255 {
        return 0xff000000 | (colour & 0xffffff);
    }
    let source_alpha = mul(alpha, coverage);
    let destination_alpha = destination >> 24;
    let remainder = 255 - source_alpha;
    let destination_factor = mul(destination_alpha, remainder);
    let result_alpha = source_alpha + destination_factor;
    if result_alpha == 0 {
        return 0;
    }
    let mut result = result_alpha << 24;
    for shift in [0, 8, 16] {
        let source = mul(mul((colour >> shift) & 255, alpha), coverage);
        let previous = mul((destination >> shift) & 255, destination_factor);
        // Match the 24-bit reciprocal used by OpenJDK 17 AlphaMath.c, including
        // its rounding at ties; exact integer division rounds some channels differently.
        // https://github.com/openjdk/jdk17u/blob/master/src/java.desktop/share/native/libawt/java2d/loops/AlphaMath.c
        let reciprocal = ((255_u64 << 24) + u64::from(result_alpha / 2)) / u64::from(result_alpha);
        let value = (((1_u64 << 23) + u64::from(source + previous) * reciprocal) >> 24) as u32;
        result |= value.min(255) << shift;
    }
    result
}

struct Raster<'a> {
    pixels: &'a mut [u32],
    width: usize,
    height: usize,
}

impl Raster<'_> {
    fn pixel(&mut self, x: i32, y: i32, colour: u32, alpha: u32, coverage: u32) {
        if x < 0 || y < 0 || x as usize >= self.width || y as usize >= self.height {
            return;
        }
        let index = x as usize + y as usize * self.width;
        self.pixels[index] = blend(self.pixels[index], colour, alpha, coverage);
    }

    /// A unit-width square-capped horizontal line from (x+.5,y/2) to (x+1.5,y/2).
    fn line(&mut self, x: i32, doubled_y: i32, colour: u32, alpha: u32) {
        let row = doubled_y.div_euclid(2);
        if doubled_y & 1 != 0 {
            for column in x..x + 2 {
                self.pixel(column, row, colour, alpha, 255);
            }
        } else {
            for column in x..x + 2 {
                self.pixel(column, row - 1, colour, alpha, 127);
                self.pixel(column, row, colour, alpha, 127);
            }
        }
    }

    /// An opaque rectangle from (x+.5,y/2), width two and integral height.
    fn rectangle(&mut self, x: i32, doubled_y: i32, height: i32, colour: u32) {
        if height <= 0 {
            return;
        }
        let first = doubled_y.div_euclid(2);
        let half = doubled_y & 1 != 0;
        let end = first + height + i32::from(half);
        for row in first.max(0)..end.min(self.height as i32) {
            let vertical = if half && (row == first || row == end - 1) {
                127
            } else {
                255
            };
            for (offset, horizontal) in [127, 255, 127].into_iter().enumerate() {
                self.pixel(
                    x + offset as i32,
                    row,
                    colour,
                    255,
                    mul(vertical, horizontal),
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn single_draw_matches_java2d_half_coordinate_samples() {
        assert_eq!(blend(0, 0x123456, 255, 127), 0x7f123456);
        assert_eq!(blend(0, 0x123456, 255, 63), 0x3f103555);
        assert_eq!(blend(0, 0x123456, 96, 255), 0x60133555);
        assert_eq!(blend(0, 0x123456, 96, 127), 0x30103555);
        // Java2D oracle, seed 197, TYPE_INT_ARGB, arbitrary straight-alpha destinations.
        for (destination, colour, alpha, coverage, expected) in [
            (0x25b0d2ae, 0x655aab, 96, 63, 0x3a8d9eab),
            (0xd20934f3, 0x2fe612, 96, 63, 0xd60d47da),
            (0x8a11d3c5, 0x725d2a, 255, 63, 0xa735a689),
            (0x3729b85c, 0x42be49, 96, 255, 0x823bbe4c),
            (0xc640636a, 0xb9bc72, 96, 63, 0xcb4e6d6b),
            (0x92e13c9f, 0x7a5e1d, 255, 127, 0xc89f524c),
        ] {
            assert_eq!(blend(destination, colour, alpha, coverage), expected);
        }
    }
    #[test]
    fn half_coordinate_line_is_square_capped_and_clipped() {
        let mut pixels = [0; 9];
        Raster {
            pixels: &mut pixels,
            width: 3,
            height: 3,
        }
        .line(-1, 3, 0x123456, 255);
        assert_eq!(pixels, [0, 0, 0, 0xff123456, 0, 0, 0, 0, 0]);
    }

    fn empty_frame() -> Vec<u8> {
        let output = HEADER + AREA * RECORD;
        let mut frame = vec![0; output + 256 * 128 * 4];
        for (at, value) in [
            (0, 0x44334957),
            (4, 1),
            (8, AREA as i32),
            (12, 256),
            (16, 128),
            (48, HEADER as i32),
            (52, output as i32),
        ] {
            frame[at..at + 4].copy_from_slice(&value.to_le_bytes());
        }
        frame[output..].fill(0xa5);
        frame
    }

    #[test]
    fn invalid_frames_leave_inputs_and_pixels_untouched() {
        for (at, value) in [
            (0, 0),
            (4, 2),
            (16, -1),
            (20, 4),
            (52, -1),
            (56, i32::MAX),
            (60, 1),
            (HEADER + 12, 16),
        ] {
            let mut frame = empty_frame();
            frame[at..at + 4].copy_from_slice(&value.to_le_bytes());
            let before = frame.clone();
            assert!(render(&mut frame).is_err());
            assert_eq!(frame, before);
        }
        let mut frame = empty_frame();
        frame[HEADER + 12..HEADER + 16].copy_from_slice(&1_i32.to_le_bytes());
        let before = frame.clone();
        assert!(render(&mut frame).is_err());
        assert_eq!(frame, before);
    }

    #[test]
    fn empty_columns_produce_a_complete_transparent_image() {
        let mut frame = empty_frame();
        let output = int(&frame, 52) as usize;
        let input = frame[..output].to_vec();
        render(&mut frame).unwrap();
        assert_eq!(frame[..output], input);
        assert!(frame[output..].iter().all(|&value| value == 0));
    }
}
