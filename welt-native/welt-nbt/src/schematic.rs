//! Schematic indices and construction-time properties share one bounded transaction.
pub const MAX_CELLS: usize = 4 * 1024 * 1024;
pub const MAGIC: i32 = 0x5753434d;
#[derive(Clone, Copy)]
pub enum Source<'a> {
    VarInts(&'a [u8]),
    Indices(&'a [i32]),
}
struct Reader<'a> {
    source: Source<'a>,
    position: usize,
}
impl Reader<'_> {
    fn next(&mut self) -> Result<usize, &'static str> {
        match self.source {
            Source::Indices(values) => {
                let value = *values.get(self.position).ok_or("Truncated indices")?;
                self.position += 1;
                usize::try_from(value).map_err(|_| "Negative palette index")
            }
            Source::VarInts(bytes) => {
                let mut value = 0u32;
                for shift in (0..35).step_by(7) {
                    let byte = *bytes.get(self.position).ok_or("Truncated varint")?;
                    self.position += 1;
                    // Java discards bits overflowing the fifth group; preserve that behaviour.
                    value |= u32::from(byte & 127).wrapping_shl(shift);
                    if byte & 128 == 0 {
                        return usize::try_from(value as i32).map_err(|_| "Negative palette index");
                    }
                }
                Err("Varint too long")
            }
        }
    }
}
pub fn pack(
    source: Source<'_>,
    flags: &[u8],
    dimensions: [usize; 3],
    output: &mut [u8],
) -> Result<[i32; 8], &'static str> {
    let [width, length, height] = dimensions;
    let count = width
        .checked_mul(length)
        .and_then(|n| n.checked_mul(height))
        .ok_or("Volume overflow")?;
    if count == 0
        || count > MAX_CELLS
        || flags.is_empty()
        || flags.len() > 65536
        || flags.iter().any(|flag| flag & !3 != 0)
    {
        return Err("Unsupported schematic");
    }
    let stride = if flags.len() <= 256 { 1 } else { 2 };
    if output.len() != count * stride {
        return Err("Wrong output size");
    }
    if let Source::Indices(values) = source {
        if values.len() != count {
            return Err("Wrong index count");
        }
    }
    let mut reader = Reader {
        source,
        position: 0,
    };
    let mut summary = [MAGIC, -1, 0, 0, 0, 0, 0, count as i32];
    // Validate the entire source before touching output; also derive offset and waterlogged state.
    for z in 0..height {
        for y in 0..length {
            for x in 0..width {
                let index = reader.next()?;
                let flag = *flags.get(index).ok_or("Palette index out of range")?;
                if flag & 1 != 0 {
                    if summary[1] == -1 {
                        summary[1] = z as i32;
                        summary[2] = x as i32;
                        summary[3] = x as i32;
                        summary[4] = y as i32;
                        summary[5] = y as i32;
                    } else if summary[1] == z as i32 {
                        summary[2] = summary[2].min(x as i32);
                        summary[3] = summary[3].max(x as i32);
                        summary[4] = summary[4].min(y as i32);
                        summary[5] = summary[5].max(y as i32);
                    }
                    if flag & 2 != 0 {
                        summary[6] = 1;
                    }
                }
            }
        }
    }
    let mut reader = Reader {
        source,
        position: 0,
    };
    for cell in output.chunks_exact_mut(stride) {
        let index = reader.next()?;
        cell[0] = index as u8;
        if stride == 2 {
            cell[1] = (index >> 8) as u8;
        }
    }
    Ok(summary)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn packs_and_summarises_first_occupied_plane() {
        let source = [0, 0, 0, 0, 0, 0, 0, 1, 0, 2, 1, 0];
        let mut output = [99; 12];
        let summary = pack(Source::VarInts(&source), &[0, 1, 3], [3, 2, 2], &mut output).unwrap();
        assert_eq!(output, source);
        assert_eq!(summary, [MAGIC, 1, 0, 1, 0, 1, 1, 12]);
    }
    #[test]
    fn supports_short_indices_and_java_fifth_group_overflow() {
        let mut output = [0; 4];
        let flags = vec![1; 257];
        pack(
            Source::VarInts(&[0x80, 2, 0xff, 1]),
            &flags,
            [2, 1, 1],
            &mut output,
        )
        .unwrap();
        assert_eq!(output, [0, 1, 255, 0]);
        pack(
            Source::VarInts(&[0x80, 0x80, 0x80, 0x80, 0x10]),
            &[1],
            [1, 1, 1],
            &mut [0],
        )
        .unwrap();
    }
    #[test]
    fn keeps_trailing_data_and_ignores_waterlogged_for_missing_blocks() {
        let mut output = [99; 2];
        let result = pack(
            Source::VarInts(&[1, 0, 255]),
            &[0, 2],
            [2, 1, 1],
            &mut output,
        )
        .unwrap();
        assert_eq!(result[1], -1);
        assert_eq!(result[6], 0);
        assert_eq!(output, [1, 0]);
    }
    #[test]
    fn repacks_legacy_indices_without_changing_values() {
        let mut output = [0; 4];
        let result = pack(
            Source::Indices(&[256, 1]),
            &vec![1; 257],
            [2, 1, 1],
            &mut output,
        )
        .unwrap();
        assert_eq!(output, [0, 1, 1, 0]);
        assert_eq!(result[2..6], [0, 1, 0, 0]);
    }
    #[test]
    fn malformed_input_does_not_mutate_output() {
        for bytes in [
            &[0, 128][..],
            &[0, 3],
            &[128, 128, 128, 128, 128, 0],
            &[255, 255, 255, 255, 15],
        ] {
            let mut output = [99; 2];
            assert!(pack(Source::VarInts(bytes), &[1, 1], [2, 1, 1], &mut output).is_err());
            assert_eq!(output, [99; 2]);
        }
        assert!(pack(Source::Indices(&[-1]), &[1], [1, 1, 1], &mut [99]).is_err());
    }
}
