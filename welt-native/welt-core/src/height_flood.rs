//! WLFH v1/v2 : relief et peinture traités ensemble sur une tuile et ses entrées.
use crate::error::WeltError;
use crate::flood_frontier::{collect_many, Frontier};

const AREA: usize = 16_384;
pub const HEIGHT_ONLY_BYTES: usize = 64 + AREA * 5 + AREA / 8;
pub const MAX_BYTES: usize = 64 + AREA * 6 + AREA / 8;
fn word(d: &[u8], at: usize) -> i32 {
    i32::from_le_bytes(d[at..at + 4].try_into().unwrap())
}

/// Le niveau est global ; les tuiles manquantes restent traversables comme les getters Java.
pub fn edit(d: &mut [u8], queue: &mut Vec<usize>) -> Result<(), WeltError> {
    if d.len() < 64 {
        return Err(WeltError::IllegalArgument);
    }
    let painted = word(d, 4) == 2;
    let target = word(d, 20);
    let mode = word(d, 24);
    let bits = word(d, 28);
    if word(d, 0) != 0x48464c57
        || word(d, 8) != 128
        || word(d, 12) != 128
        || if painted {
            d.len() != MAX_BYTES
                || ![1, 4, 8].contains(&bits)
                || target < 0
                || target >= 1 << bits
                || !(0..=2).contains(&mode)
                || d[32..64].iter().any(|&v| v != 0)
                || d[64 + AREA * 4..64 + AREA * 5]
                    .iter()
                    .any(|&v| v as i32 >= 1 << bits)
        } else {
            d.len() != HEIGHT_ONLY_BYTES || word(d, 4) != 1 || d[20..64].iter().any(|&v| v != 0)
        }
    {
        return Err(WeltError::IllegalArgument);
    }
    let level = word(d, 16);
    let (planes, seeds) = d[64..].split_at_mut(AREA * if painted { 6 } else { 5 });
    let (heights, rest) = planes.split_at_mut(AREA * 4);
    let (values, flags) = rest.split_at_mut(if painted { AREA } else { 0 });
    collect_many(
        Frontier {
            width: 128,
            seed: 0,
            visited_bit: 1,
            present_bit: 0,
        },
        flags,
        queue,
        (0..AREA).filter(|&i| seeds[i / 8] & (1 << (i & 7)) != 0),
        false,
        |i| word(heights, i * 4) < level,
    );
    for &i in queue.iter() {
        heights[i * 4..i * 4 + 4].copy_from_slice(&level.to_le_bytes());
        if painted && (mode == 0 || mode == 2 || values[i] < target as u8) {
            if mode != 2 {
                values[i] = target as u8;
            }
            flags[i] |= 2;
        }
    }
    let remaining = (0..AREA).filter(|&i| word(heights, i * 4) < level).count();
    d[36..40].copy_from_slice(&(queue.len() as u32).to_le_bytes());
    d[40..44].copy_from_slice(&(remaining as u32).to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prepared_theme_keeps_its_local_terrain_palette() {
        let mut d = vec![0; MAX_BYTES];
        for (at, v) in [
            (0, 0x48464c57i32),
            (4, 2),
            (8, 128),
            (12, 128),
            (16, 51),
            (24, 2),
            (28, 8),
        ] {
            d[at..at + 4].copy_from_slice(&v.to_le_bytes());
        }
        for i in 0..AREA {
            d[64 + i * 4..68 + i * 4].copy_from_slice(&50i32.to_le_bytes());
            d[64 + AREA * 4 + i] = (i % 251) as u8;
        }
        let before = d[64 + AREA * 4..64 + AREA * 5].to_vec();
        d[64 + AREA * 6] = 1;
        edit(&mut d, &mut Vec::new()).unwrap();
        assert_eq!(&d[64 + AREA * 4..64 + AREA * 5], before.as_slice());
        assert_eq!(word(&d, 36), AREA as i32);
        assert!(d[64 + AREA * 5..64 + AREA * 6].iter().all(|&v| v == 3));
    }

    #[test]
    fn paint_and_height_share_the_selected_cells_and_validate_atomically() {
        for mode in 0..=1 {
            let mut d = vec![0; MAX_BYTES];
            for (at, v) in [
                (0, 0x48464c57i32),
                (4, 2),
                (8, 128),
                (12, 128),
                (16, 51),
                (20, 9),
                (24, mode),
                (28, 4),
            ] {
                d[at..at + 4].copy_from_slice(&v.to_le_bytes());
            }
            for i in 0..AREA {
                let h = if i % 128 == 64 { 100i32 } else { 50 };
                d[64 + i * 4..68 + i * 4].copy_from_slice(&h.to_le_bytes());
                d[64 + AREA * 4 + i] = if i % 128 < 32 { 5 } else { 15 };
            }
            d[64 + AREA * 6] = 1;
            let mut invalid = d.clone();
            invalid[64 + AREA * 4 + 127] = 16;
            let before = invalid.clone();
            assert!(edit(&mut invalid, &mut Vec::new()).is_err());
            assert_eq!(invalid, before);
            edit(&mut d, &mut Vec::new()).unwrap();
            assert_eq!(word(&d, 36), 64 * 128);
            for i in 0..AREA {
                assert_eq!(d[64 + AREA * 5 + i] & 1 != 0, i % 128 < 64);
                assert_eq!(
                    d[64 + AREA * 5 + i] & 2 != 0,
                    i % 128 < (if mode == 0 { 64 } else { 32 })
                );
            }
        }
    }
    #[test]
    fn reentry_and_missing_cells_preserve_global_level() {
        let mut d = vec![0; HEIGHT_ONLY_BYTES];
        for (at, v) in [(0, 0x48464c57i32), (4, 1), (8, 128), (12, 128), (16, 51)] {
            d[at..at + 4].copy_from_slice(&v.to_le_bytes());
        }
        for i in 0..AREA {
            let h = if i % 128 == 64 {
                100i32
            } else if i % 128 == 0 {
                i32::MIN
            } else {
                50
            };
            d[64 + i * 4..68 + i * 4].copy_from_slice(&h.to_le_bytes());
        }
        d[64 + AREA * 5] = 1;
        let mut queue = Vec::new();
        let mut bad = d.clone();
        bad[24] = 1;
        let before = bad.clone();
        assert!(edit(&mut bad, &mut queue).is_err());
        assert_eq!(bad, before);
        edit(&mut d, &mut queue).unwrap();
        assert_eq!(word(&d, 36), 64 * 128);
        assert_eq!(word(&d, 40), 63 * 128);
        d[20..64].fill(0);
        d[64 + AREA * 5..].fill(0);
        d[64 + AREA * 5 + 127 / 8] = 1 << (127 & 7);
        edit(&mut d, &mut queue).unwrap();
        assert_eq!(word(&d, 36), 63 * 128);
        assert_eq!(word(&d, 40), 0);
    }
}
