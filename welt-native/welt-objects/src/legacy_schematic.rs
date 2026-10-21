//! Fused legacy schematic offset and connectivity analysis over the existing compact arrays.
pub const MAGIC: u32 = 0x574c5343;
pub const BUFFER_BYTES: usize = 64 + 65_536 * 8;
const CONNECTING: u32 = 1;
const EXPLICIT: u32 = 2;
const INSUBSTANTIAL: u32 = 4;
const SOLID_OPAQUE: u32 = 8;
fn word(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
}
fn put(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
}
fn state(blocks: &[u8], data: &[u8], add: Option<&[u8]>, at: usize) -> usize {
    let high = add.map_or(0, |a| (a[at / 2] >> ((at & 1) * 4)) & 15);
    ((blocks[at] as usize | (high as usize) << 8) << 4) | (data[at] as usize & 15)
}
fn connects(buffer: &[u8], a: usize, b: usize) -> bool {
    let x = 64 + a * 8;
    let y = 64 + b * 8;
    let flags = word(buffer, x);
    let other = word(buffer, y);
    flags & INSUBSTANTIAL == 0
        && other & INSUBSTANTIAL == 0
        && (word(buffer, x + 4) == word(buffer, y + 4)
            || flags & SOLID_OPAQUE != 0
            || other & SOLID_OPAQUE != 0)
}
/// WLSC v1: 64-byte header/result followed by 65,536 (flags, Java name-identity group) entries.
/// Flags are resolved by Java once per distinct material; no registry assumptions are embedded here.
pub fn analyze(blocks: &[u8], data: &[u8], add: Option<&[u8]>, buffer: &mut [u8]) -> bool {
    if buffer.len() != BUFFER_BYTES || word(buffer, 0) != MAGIC || word(buffer, 4) != 1 {
        return false;
    }
    let width = word(buffer, 8) as usize;
    let length = word(buffer, 12) as usize;
    let height = word(buffer, 16) as usize;
    if width == 0 || length == 0 || height == 0 || width > 32767 || length > 32767 || height > 32767
    {
        return false;
    }
    let Some(volume) = width
        .checked_mul(length)
        .and_then(|v| v.checked_mul(height))
    else {
        return false;
    };
    if volume > 16 * 1024 * 1024
        || blocks.len() < volume
        || data.len() < volume
        || add.is_some_and(|a| a.len() < volume.div_ceil(2))
    {
        return false;
    }
    let plane = width * length;
    let mut bottom = None;
    let (mut min_x, mut max_x, mut min_y, mut max_y) = (0, 0, 0, 0);
    let mut connected = false;
    let mut explicit = false;
    for z in 0..height {
        for x in 0..width {
            for y in 0..length {
                let at = x + width * y + plane * z;
                // The original mask uses only Blocks, even for a nonzero AddBlocks nibble.
                if blocks[at] == 0 {
                    continue;
                }
                if bottom.is_none() {
                    bottom = Some(z);
                    min_x = x;
                    max_x = x;
                    min_y = y;
                    max_y = y;
                }
                if bottom == Some(z) {
                    min_x = min_x.min(x);
                    max_x = max_x.max(x);
                    min_y = min_y.min(y);
                    max_y = max_y.max(y);
                }
                let current = state(blocks, data, add, at);
                let flags = word(buffer, 64 + current * 8);
                if flags & 16 != 0 {
                    return false;
                }
                if flags & CONNECTING == 0 {
                    continue;
                }
                if flags & EXPLICIT != 0 {
                    explicit = true;
                    continue;
                }
                if connected || explicit {
                    continue;
                }
                let mut neighbour = |index: usize| {
                    if blocks[index] != 0
                        && connects(buffer, current, state(blocks, data, add, index))
                    {
                        connected = true;
                    }
                };
                if x > 0 {
                    neighbour(at - 1);
                }
                if y > 0 {
                    neighbour(at - width);
                }
                if x + 1 < width {
                    neighbour(at + 1);
                }
                if y + 1 < length {
                    neighbour(at + width);
                }
            }
        }
    }
    put(buffer, 24, u32::from(bottom.is_some()));
    if let Some(z) = bottom {
        put(buffer, 28, (-((min_x + max_x) as i32) / 2) as u32);
        put(buffer, 32, (-((min_y + max_y) as i32) / 2) as u32);
        put(buffer, 36, (-(z as i32)) as u32);
    }
    put(buffer, 40, u32::from(connected && !explicit));
    put(buffer, 44, 1);
    true
}
#[cfg(test)]
mod tests {
    use super::*;
    fn buffer(width: u32, length: u32, height: u32) -> Vec<u8> {
        let mut b = vec![0; BUFFER_BYTES];
        put(&mut b, 0, MAGIC);
        put(&mut b, 4, 1);
        put(&mut b, 8, width);
        put(&mut b, 12, length);
        put(&mut b, 16, height);
        b
    }
    fn semantic(b: &mut [u8], id: usize, flags: u32, name: u32) {
        put(b, 64 + (id << 4) * 8, flags);
        put(b, 68 + (id << 4) * 8, name);
    }
    #[test]
    fn preserves_bottom_plane_bounds_axes_and_high_nibble_mask() {
        let mut b = buffer(3, 2, 2);
        let mut blocks = vec![0; 12];
        let data = vec![0; 12];
        let mut add = vec![0; 6];
        add[0] = 1;
        blocks[6] = 1;
        blocks[11] = 1;
        assert!(analyze(&blocks, &data, Some(&add), &mut b));
        assert_eq!(word(&b, 28) as i32, -1);
        assert_eq!(word(&b, 32) as i32, 0);
        assert_eq!(word(&b, 36) as i32, -1);
    }
    #[test]
    fn explicit_connections_override_inference_and_neighbours_are_horizontal() {
        let mut b = buffer(3, 1, 1);
        semantic(&mut b, 85, CONNECTING, 1);
        semantic(&mut b, 1, SOLID_OPAQUE, 2);
        semantic(&mut b, 102, CONNECTING | EXPLICIT, 3);
        assert!(analyze(&[85, 1, 0], &[0; 3], None, &mut b));
        assert_eq!(word(&b, 40), 1);
        assert!(analyze(&[85, 1, 102], &[0; 3], None, &mut b));
        assert_eq!(word(&b, 40), 0);
        let mut b = buffer(1, 1, 2);
        semantic(&mut b, 85, CONNECTING, 1);
        semantic(&mut b, 1, SOLID_OPAQUE, 2);
        assert!(analyze(&[85, 1], &[0; 2], None, &mut b));
        assert_eq!(word(&b, 40), 0);
    }
    #[test]
    fn malformed_layouts_are_rejected_without_result_commit() {
        let mut b = buffer(3, 1, 1);
        assert!(!analyze(&[1; 3], &[0; 2], None, &mut b));
        assert_eq!(word(&b, 44), 0);
        assert!(!analyze(&[1; 3], &[0; 3], Some(&[0]), &mut b));
        put(&mut b, 4, 2);
        assert!(!analyze(&[1; 3], &[0; 3], None, &mut b));
    }
}
