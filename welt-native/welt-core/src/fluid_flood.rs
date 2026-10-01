//! WLFD v1 : hauteurs, fluides et masque d'une zone bornée, traités ensemble.
use crate::error::WeltError;
use crate::flood_frontier::{collect, Frontier};

pub const MAX_CELLS: usize = 65_536;
pub const MAX_BYTES: usize = 64 + MAX_CELLS * 10;
fn word(data: &[u8], offset: usize) -> i32 {
    i32::from_le_bytes(data[offset..offset + 4].try_into().unwrap())
}

/// Les validations précèdent toute mutation ; la file appartient au worker.
pub fn edit(data: &mut [u8], queue: &mut Vec<usize>) -> Result<(), WeltError> {
    if data.len() < 74
        || word(data, 0) != 0x44464c57
        || word(data, 4) != 1
        || data[40..64].iter().any(|&v| v != 0)
    {
        return Err(WeltError::IllegalArgument);
    }
    let (width, height, sx, sy, mode, level, lava) = (
        word(data, 8),
        word(data, 12),
        word(data, 16),
        word(data, 20),
        word(data, 24),
        word(data, 28),
        word(data, 32),
    );
    if width <= 0
        || height <= 0
        || sx < 0
        || sy < 0
        || sx >= width
        || sy >= height
        || !(0..=2).contains(&mode)
        || !(0..=1).contains(&lava)
    {
        return Err(WeltError::IllegalArgument);
    }
    let area = (width as usize)
        .checked_mul(height as usize)
        .ok_or(WeltError::IllegalArgument)?;
    if area > MAX_CELLS || data.len() != 64 + area * 10 {
        return Err(WeltError::IllegalArgument);
    }
    let width = width as usize;
    let seed = sy as usize * width + sx as usize;
    let types = 64 + area * 8;
    let mask = types + area;
    if word(data, 64 + seed * 4) == i32::MIN || data[types..mask].iter().any(|&v| v > 1) {
        return Err(WeltError::IllegalArgument);
    }
    let (payload, flags) = data[64..].split_at_mut(area * 9);
    collect(
        Frontier {
            width,
            seed,
            visited_bit: 1,
            present_bit: 0,
        },
        flags,
        queue,
        |cell| {
            let h = word(payload, cell * 4);
            let w = word(payload, area * 4 + cell * 4);
            h != i32::MIN
                && match mode {
                    0 => h < level && w < level,
                    1 => w > h && w > level,
                    _ => w > h && payload[area * 8 + cell] != lava as u8,
                }
        },
    );
    // La graine est remplie même si elle est une frontière, comme le parcours Java.
    for &cell in queue.iter() {
        if mode != 2 {
            payload[area * 4 + cell * 4..area * 4 + cell * 4 + 4]
                .copy_from_slice(&level.to_le_bytes());
        }
        payload[area * 8 + cell] = lava as u8;
    }
    data[36..40].copy_from_slice(&(queue.len() as u32).to_le_bytes());
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let mut d = vec![0; 64 + 9 * 10];
        for (offset, value) in [
            (0, 0x44464c57),
            (4, 1),
            (8, 3),
            (12, 3),
            (16, 0),
            (20, 1),
            (24, 0),
            (28, 51),
            (32, 1),
        ] {
            d[offset..offset + 4].copy_from_slice(&i32::to_le_bytes(value));
        }
        for i in 0..9 {
            d[64 + i * 4..68 + i * 4]
                .copy_from_slice(&(if i % 3 == 1 { 100i32 } else { 50 }).to_le_bytes());
        }
        d
    }
    #[test]
    fn barriers_and_reused_queue_preserve_disconnected_cells() {
        let mut queue = Vec::new();
        for _ in 0..3 {
            let mut d = fixture();
            edit(&mut d, &mut queue).unwrap();
            assert_eq!(word(&d, 36), 3);
            for i in 0..9 {
                assert_eq!(d[64 + 9 * 9 + i], u8::from(i % 3 == 0));
            }
        }
    }
    #[test]
    fn invalid_input_does_not_mutate() {
        let mut d = fixture();
        d[32] = 2;
        let old = d.clone();
        assert!(edit(&mut d, &mut Vec::new()).is_err());
        assert_eq!(d, old);
    }
    #[test]
    fn missing_cells_and_forced_seed_match_java() {
        let mut d = fixture();
        d[64 + 3 * 4..68 + 3 * 4].copy_from_slice(&100i32.to_le_bytes());
        d[64..68].copy_from_slice(&i32::MIN.to_le_bytes());
        edit(&mut d, &mut Vec::new()).unwrap();
        assert_eq!(word(&d, 36), 2);
    }
}
