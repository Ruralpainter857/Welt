//! WLPF v1 : valeurs et présence des cellules, puis exploration et modification sur les mêmes plans.
use crate::error::WeltError;
use crate::flood_frontier::{collect, Frontier};
pub const MAX_BYTES: usize = 64 + 65_536 * 2;
fn word(d: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(d[o..o + 4].try_into().unwrap())
}

/// Modes : valeur égale, augmentation jusqu'au niveau, suppression des valeurs non nulles.
pub fn edit(d: &mut [u8], queue: &mut Vec<usize>) -> Result<(), WeltError> {
    if d.len() < 66
        || word(d, 0) != 0x46504c57
        || word(d, 4) != 1
        || d[40..64].iter().any(|&v| v != 0)
    {
        return Err(WeltError::IllegalArgument);
    }
    let (w, h, sx, sy, mode, target, bits) = (
        word(d, 8) as usize,
        word(d, 12) as usize,
        word(d, 16) as usize,
        word(d, 20) as usize,
        word(d, 24),
        word(d, 28),
        word(d, 32),
    );
    let area = w.checked_mul(h).ok_or(WeltError::IllegalArgument)?;
    if w == 0
        || h == 0
        || sx >= w
        || sy >= h
        || area > 65_536
        || d.len() != 64 + area * 2
        || mode > 2
        || !matches!(bits, 1 | 4 | 8)
        || target >= 1 << bits
        || mode == 2 && target != 0
    {
        return Err(WeltError::IllegalArgument);
    }
    let seed = sx + sy * w;
    let (values, flags) = d[64..].split_at_mut(area);
    if flags.iter().any(|&v| v > 1)
        || values
            .iter()
            .zip(flags.iter())
            .any(|(&v, &f)| f != 0 && v as u32 >= 1 << bits)
    {
        return Err(WeltError::IllegalArgument);
    }
    let matching = values[seed];
    if flags[seed] == 0
        || matching as u32 == target
        || mode == 1 && matching as u32 >= target
        || mode == 2 && matching == 0
    {
        d[36..40].fill(0);
        return Ok(());
    }
    collect(
        Frontier {
            width: w,
            seed,
            visited_bit: 2,
            present_bit: 1,
        },
        flags,
        queue,
        |cell| match mode {
            0 => values[cell] == matching,
            1 => (values[cell] as u32) < target,
            _ => values[cell] != 0,
        },
    );
    for &cell in queue.iter() {
        values[cell] = target as u8;
    }
    d[36..40].copy_from_slice(&(queue.len() as u32).to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(mode: u32, target: u32) -> Vec<u8> {
        let mut d = vec![0; 64 + 6 * 2];
        for (o, v) in [
            (0, 0x46504c57),
            (4, 1),
            (8, 3),
            (12, 2),
            (24, mode),
            (28, target),
            (32, 8),
        ] {
            d[o..o + 4].copy_from_slice(&v.to_le_bytes());
        }
        d[64..70].copy_from_slice(&[42, 42, 254, 42, 0, 42]);
        d[70..].fill(1);
        d
    }
    #[test]
    fn modes_preserve_barriers_and_missing_cells() {
        for mode in 0..3 {
            let mut d = fixture(mode, if mode == 2 { 0 } else { 77 });
            d[74] = 0;
            edit(&mut d, &mut Vec::new()).unwrap();
            assert_eq!(word(&d, 36), if mode == 2 { 5 } else { 3 });
            assert_eq!(d[66], if mode == 2 { 0 } else { 254 });
            assert_eq!(d[69], if mode == 2 { 0 } else { 42 });
        }
    }
    #[test]
    fn already_filled_and_invalid_buffers_are_not_modified() {
        let mut d = fixture(0, 42);
        let original = d.clone();
        edit(&mut d, &mut Vec::new()).unwrap();
        assert_eq!(d, original);
        d[32] = 4;
        let original = d.clone();
        assert!(edit(&mut d, &mut Vec::new()).is_err());
        assert_eq!(d, original);
    }
}
