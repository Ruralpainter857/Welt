//! WLFH v1 : propagation des hauteurs quantifiées sur une tuile et ses entrées.
use crate::error::WeltError;
use crate::flood_frontier::{collect_many, Frontier};

const AREA: usize = 16_384;
pub const MAX_BYTES: usize = 64 + AREA * 5 + AREA / 8;
fn word(d: &[u8], at: usize) -> i32 {
    i32::from_le_bytes(d[at..at + 4].try_into().unwrap())
}

/// Le niveau est global ; les tuiles manquantes restent traversables comme les getters Java.
pub fn edit(d: &mut [u8], queue: &mut Vec<usize>) -> Result<(), WeltError> {
    if d.len() != MAX_BYTES
        || word(d, 0) != 0x48464c57
        || word(d, 4) != 1
        || word(d, 8) != 128
        || word(d, 12) != 128
        || d[20..64].iter().any(|&v| v != 0)
    {
        return Err(WeltError::IllegalArgument);
    }
    let level = word(d, 16);
    let (planes, seeds) = d[64..].split_at_mut(AREA * 5);
    let (heights, flags) = planes.split_at_mut(AREA * 4);
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
    fn reentry_and_missing_cells_preserve_global_level() {
        let mut d = vec![0; MAX_BYTES];
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
