//! Brush-sized height smoothing using WorldPainter's 11 by 11 neighborhood.

const MAX_SMOOTH_CELLS: usize = 65_536;
const EMPTY_HEIGHT: f32 = -f32::MAX;
const NEIGHBORHOOD_RADIUS: usize = 5;
const LEGACY_COMPACT_MAX_BYTES: usize = 48 + 4 * 65536 + 9 * 246 * 246;
pub const COMPACT_MAX_BYTES: usize = 48 + 4 * 1024 * 1024 + 9 * 1014 * 1014;

/// Streaming scatter sums retain the scalar sample order with only eleven output columns live.
#[derive(Default)]
pub struct SmoothColumns {
    totals: Vec<f32>,
    counts: Vec<u32>,
    width: usize,
    height: usize,
    next_input_x: usize,
}

impl SmoothColumns {
    pub fn reset(&mut self, width: usize, height: usize) {
        self.width = width;
        self.height = height;
        self.next_input_x = 0;
        self.totals.resize(11 * height, 0.0);
        self.counts.resize(11 * height, 0);
        self.totals.fill(0.0);
        self.counts.fill(0);
    }

    pub fn prepare_column(&mut self, x: usize, mut sample: impl FnMut(usize, usize) -> f32) {
        if x > 0 {
            let start = ((x - 1) % 11) * self.height;
            self.totals[start..start + self.height].fill(0.0);
            self.counts[start..start + self.height].fill(0);
        }
        for sx in self.next_input_x..=x + 10 {
            for sy in 0..self.height + 10 {
                let value = sample(sx, sy);
                if value == EMPTY_HEIGHT {
                    continue;
                }
                let first_y = sy.saturating_sub(10);
                let last_y = sy.min(self.height - 1);
                for dx in sx.saturating_sub(10)..=sx.min(self.width - 1) {
                    let start = (dx % 11) * self.height + first_y;
                    let end = (dx % 11) * self.height + last_y + 1;
                    for (total, count) in self.totals[start..end]
                        .iter_mut()
                        .zip(&mut self.counts[start..end])
                    {
                        *total += value;
                        *count += 1;
                    }
                }
            }
        }
        self.next_input_x = x + 11;
    }

    pub fn mean(&self, x: usize, y: usize) -> f32 {
        let index = (x % 11) * self.height + y;
        self.totals[index] / self.counts[index] as f32
    }
}

std::thread_local! {
    static COMPACT_COLUMNS: std::cell::RefCell<SmoothColumns> = std::cell::RefCell::new(SmoothColumns::default());
}

// WLSM v1/v2: immutable bordered f32 input, forces, raw i32 output and a write mask.
pub fn edit_compact(data: &mut [u8]) -> Result<(), crate::error::WeltError> {
    COMPACT_COLUMNS.with_borrow_mut(|columns| edit_compact_with_columns(data, columns))
}

fn edit_compact_with_columns(
    data: &mut [u8],
    columns: &mut SmoothColumns,
) -> Result<(), crate::error::WeltError> {
    use crate::error::WeltError::IllegalArgument;
    fn get(data: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
    }
    fn float(data: &[u8], offset: usize) -> f32 {
        f32::from_bits(get(data, offset) as u32)
    }
    if data.len() < 48
        || data.len() > COMPACT_MAX_BYTES
        || get(data, 0) != 0x4d534c57
        || !matches!(get(data, 4), 1 | 2)
        || get(data, 4) == 1 && data.len() > LEGACY_COMPACT_MAX_BYTES
    {
        return Err(IllegalArgument);
    }
    let width = get(data, 8);
    let height = get(data, 12);
    let maximum_side = if get(data, 4) == 2 { 1024 } else { 256 };
    if !(11..=maximum_side).contains(&width)
        || !(11..=maximum_side).contains(&height)
        || get(data, 36) != 0
        || get(data, 44) != 0
    {
        return Err(IllegalArgument);
    }
    let width = width as usize;
    let height = height as usize;
    let out_width = width - 10;
    let out_height = height - 10;
    let area = out_width * out_height;
    let forces = 48 + width * height * 4;
    let output = forces + area * 4;
    let mask = output + area * 4;
    if get(data, 20) != 48
        || get(data, 24) != forces as i32
        || get(data, 28) != output as i32
        || get(data, 32) != mask as i32
        || data.len() != mask + area
    {
        return Err(IllegalArgument);
    }
    data[mask..].fill(0);
    let mut writes = 0u32;
    let min_z = get(data, 16) as f32;
    let streaming = get(data, 4) == 2;
    if streaming {
        columns.reset(out_width, out_height);
    }
    for x in 0..out_width {
        if streaming {
            columns.prepare_column(x, |sx, sy| float(data, 48 + (sx * height + sy) * 4));
        }
        for y in 0..out_height {
            let i = x * out_height + y;
            let strength = float(data, forces + i * 4);
            if strength.partial_cmp(&0.0) != Some(std::cmp::Ordering::Greater) {
                continue;
            }
            let current = float(data, 48 + ((x + 5) * height + y + 5) * 4);
            if current == EMPTY_HEIGHT {
                continue;
            }
            let mean = if streaming {
                columns.mean(x, y)
            } else {
                let mut total = 0.0f32;
                let mut count = 0u32;
                for sx in x..=x + 10 {
                    for sy in y..=y + 10 {
                        let value = float(data, 48 + (sx * height + sy) * 4);
                        if value != EMPTY_HEIGHT {
                            total += value;
                            count += 1;
                        }
                    }
                }
                total / count as f32
            };
            let edited = strength * mean + (1.0 - strength) * current;
            let raw = ((edited - min_z) * 256.0) as i32;
            data[output + i * 4..output + i * 4 + 4].copy_from_slice(&raw.to_le_bytes());
            data[mask + i] = 1;
            writes += 1;
        }
    }
    data[40..44].copy_from_slice(&writes.to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod compact_tests {
    #[test]
    fn streaming_columns_match_scalar_addition_bits_and_read_each_input_once() {
        let mut columns = super::SmoothColumns::default();
        for (width, height) in [(1, 1), (2, 19), (19, 2), (127, 35), (511, 73)] {
            let ih = height + 10;
            let mut input = Vec::new();
            for x in 0..width + 10 {
                for y in 0..ih {
                    input.push(if (x + y) % 13 == 0 {
                        -f32::MAX
                    } else if (x + y) % 31 == 0 {
                        -0.0
                    } else {
                        ((x * 193 + y * 79) % 1_000_000) as f32 / 137.0 - 3000.0
                    });
                }
            }
            columns.reset(width, height);
            let mut reads = 0;
            for x in 0..width {
                columns.prepare_column(x, |sx, sy| {
                    reads += 1;
                    input[sx * ih + sy]
                });
                for y in 0..height {
                    let mut total = 0.0f32;
                    let mut count = 0;
                    for sx in x..=x + 10 {
                        for sy in y..=y + 10 {
                            let value = input[sx * ih + sy];
                            if value != -f32::MAX {
                                total += value;
                                count += 1;
                            }
                        }
                    }
                    let expected = total / count as f32;
                    assert_eq!(
                        columns.mean(x, y).to_bits(),
                        expected.to_bits(),
                        "{width}x{height} at {x},{y}"
                    );
                }
            }
            assert_eq!(reads, (width + 10) * ih);
            assert_eq!(columns.totals.len(), 11 * height);
        }
    }

    #[test]
    fn wide_frames_preserve_samples_and_reject_legacy_versions_atomically() {
        let width = 521usize;
        let height = 523usize;
        let area = (width - 10) * (height - 10);
        let forces = 48 + width * height * 4;
        let output = forces + area * 4;
        let mask = output + area * 4;
        let mut data = vec![0u8; mask + area];
        for (at, value) in [
            (0, 0x4d534c57i32),
            (4, 2),
            (8, width as i32),
            (12, height as i32),
            (16, -64),
            (20, 48),
            (24, forces as i32),
            (28, output as i32),
            (32, mask as i32),
        ] {
            data[at..at + 4].copy_from_slice(&value.to_le_bytes());
        }
        for i in 0..width * height {
            data[48 + i * 4..52 + i * 4].copy_from_slice(&10.25f32.to_le_bytes());
        }
        for i in [0, area / 2, area - 1] {
            data[forces + i * 4..forces + i * 4 + 4].copy_from_slice(&1f32.to_le_bytes());
        }
        let original = data.clone();
        super::edit_compact(&mut data).unwrap();
        assert_eq!(&data[..40], &original[..40]);
        assert_eq!(&data[44..output], &original[44..output]);
        assert_eq!(i32::from_le_bytes(data[40..44].try_into().unwrap()), 3);
        for i in [0, area / 2, area - 1] {
            assert_eq!(
                i32::from_le_bytes(data[output + i * 4..output + i * 4 + 4].try_into().unwrap()),
                74 * 256 + 64
            );
            assert_eq!(data[mask + i], 1);
        }
        for (at, value) in [(4, 1), (4, 3), (8, 1025), (24, forces as i32 + 4), (44, 1)] {
            let mut rejected = original.clone();
            rejected[at..at + 4].copy_from_slice(&value.to_le_bytes());
            let before = rejected.clone();
            assert!(super::edit_compact(&mut rejected).is_err());
            assert_eq!(rejected, before);
        }
    }

    #[test]
    fn rectangular_windows_preserve_empty_centers_and_validate_before_mutation() {
        let mut data = vec![0; 594];
        for (offset, value) in [
            (0, 0x4d534c57i32),
            (4, 1),
            (8, 11),
            (12, 12),
            (16, -64),
            (20, 48),
            (24, 576),
            (28, 584),
            (32, 592),
        ] {
            data[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        }
        for i in 0..132 {
            data[48 + i * 4..52 + i * 4].copy_from_slice(&10.25f32.to_le_bytes());
        }
        data[48 + 66 * 4..52 + 66 * 4].copy_from_slice(&(-f32::MAX).to_le_bytes());
        data[576..580].copy_from_slice(&1f32.to_le_bytes());
        data[580..584].copy_from_slice(&1f32.to_le_bytes());
        super::edit_compact(&mut data).unwrap();
        assert_eq!(
            i32::from_le_bytes(data[584..588].try_into().unwrap()),
            74 * 256 + 64
        );
        assert_eq!(&data[592..594], &[1, 0]);
        assert_eq!(i32::from_le_bytes(data[40..44].try_into().unwrap()), 1);
        data[28..32].copy_from_slice(&588i32.to_le_bytes());
        let before = data.clone();
        assert!(super::edit_compact(&mut data).is_err());
        assert_eq!(data, before);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SmoothHeightError {
    InvalidDimensions,
    AreaOverflow,
    TooManyCells,
    InputLength { expected: usize, actual: usize },
    StrengthLength { expected: usize, actual: usize },
    OutputLength { expected: usize, actual: usize },
    ModifiedLength { expected: usize, actual: usize },
}

/// Computes one Java-compatible smoothing pass over a row-major height view.
///
/// The input view includes a five-cell halo on every side of the output area.
/// Java applies output heights through its normal setters after this calculation.
pub fn smooth_height_region(
    input_width: usize,
    input_height: usize,
    heights: &[f32],
    strengths: &[f32],
    output: &mut [f32],
    modified: &mut [i8],
) -> Result<(), SmoothHeightError> {
    if input_width < 11 || input_height < 11 {
        return Err(SmoothHeightError::InvalidDimensions);
    }
    let input_area = input_width
        .checked_mul(input_height)
        .ok_or(SmoothHeightError::AreaOverflow)?;
    let output_width = input_width - 10;
    let output_height = input_height - 10;
    let output_area = output_width
        .checked_mul(output_height)
        .ok_or(SmoothHeightError::AreaOverflow)?;
    if input_area > MAX_SMOOTH_CELLS || output_area > MAX_SMOOTH_CELLS {
        return Err(SmoothHeightError::TooManyCells);
    }
    if heights.len() != input_area {
        return Err(SmoothHeightError::InputLength {
            expected: input_area,
            actual: heights.len(),
        });
    }
    if strengths.len() != output_area {
        return Err(SmoothHeightError::StrengthLength {
            expected: output_area,
            actual: strengths.len(),
        });
    }
    if output.len() != output_area {
        return Err(SmoothHeightError::OutputLength {
            expected: output_area,
            actual: output.len(),
        });
    }
    if modified.len() != output_area {
        return Err(SmoothHeightError::ModifiedLength {
            expected: output_area,
            actual: modified.len(),
        });
    }

    modified.fill(0);
    for x in 0..output_width {
        for y in 0..output_height {
            let strength = strengths[x * output_height + y];
            if strength.is_nan() || strength <= 0.0_f32 {
                continue;
            }
            let mut total = 0.0_f32;
            let mut sample_count = 0_u32;
            for sample_x in x..=x + NEIGHBORHOOD_RADIUS * 2 {
                for sample_y in y..=y + NEIGHBORHOOD_RADIUS * 2 {
                    let height = heights[sample_x * input_width + sample_y];
                    if height != EMPTY_HEIGHT {
                        total += height;
                        sample_count += 1;
                    }
                }
            }
            let center = heights[(x + NEIGHBORHOOD_RADIUS) * input_width + y + NEIGHBORHOOD_RADIUS];
            let current_height = if center == EMPTY_HEIGHT {
                0.0_f32
            } else {
                center
            };
            let average = total / sample_count as f32;
            output[x * output_height + y] =
                strength * average + (1.0_f32 - strength) * current_height;
            modified[x * output_height + y] = 1;
        }
    }
    Ok(())
}
