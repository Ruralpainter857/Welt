//! WLCP v2 adds a selection halo, Java blend weights and explicit layer map order.
//! Extension after tile records: hashes[64] u32, roles[64] u8, initial/copy orders
//! [64] u8 each, source COW flags u32, reserved u32, 160x160 selection bits,
//! blend weights[513] f32. Header: copy-layers flag at 36, raw RNG state at 40,
//! consumed float draws at 48, reserved bytes at 56. Packed tile planes stay v1.
//! Roles: height 0, water 1, terrain 2, lava 3, reserved 4, biome 5,
//! annotations 6, ordinary layer 7, read-only layer 8.

use super::{get, index, length, mask, put_mask, set, word, MAX_BYTES};
use crate::error::WeltError;
use crate::rng::java_random::JavaRandom;
const EXT: usize = 456 + 3200 + 513 * 4;

fn selected(d: &[u8], halo: usize, x: i32, y: i32) -> bool {
    let bit = (x + 16 + (y + 16) * 160) as usize;
    d[halo + bit / 8] & (1 << (bit % 8)) != 0
}
fn distance_squared(d: &[u8], halo: usize, x: i32, y: i32) -> usize {
    if !selected(d, halo, x, y) {
        return 0;
    }
    let mut distance = 256;
    for i in 1..=16 {
        if (!selected(d, halo, x - i, y)
            || !selected(d, halo, x + i, y)
            || !selected(d, halo, x, y - i)
            || !selected(d, halo, x, y + i))
            && i * i < distance
        {
            return (i * i) as usize;
        }
        for dy in 1..=i {
            if !selected(d, halo, x - i, y - dy)
                || !selected(d, halo, x + dy, y - i)
                || !selected(d, halo, x + i, y + dy)
                || !selected(d, halo, x - dy, y + i)
                || dy < i
                    && (!selected(d, halo, x - i, y + dy)
                        || !selected(d, halo, x - dy, y - i)
                        || !selected(d, halo, x + i, y - dy)
                        || !selected(d, halo, x + dy, y + i))
            {
                distance = distance.min(i * i + dy * dy);
                break;
            }
        }
    }
    distance as usize
}
fn draw(rng: &mut JavaRandom, count: &mut u64, blend: f32) -> bool {
    *count += 1;
    rng.next_float() <= blend
}
fn mixed(src: u32, dst: u32, weight: f32, signed: bool) -> u32 {
    let a = if signed {
        src as i32 as f32
    } else {
        src as f32
    };
    let b = if signed {
        dst as i32 as f32
    } else {
        dst as f32
    };
    // Widen only the final rounding step; Java does both products and the sum in f32.
    let v = weight * a + (1.0 - weight) * b;
    ((f64::from(v) + 0.5).floor() as i32) as u32
}
fn write(
    d: &mut [u8],
    locations: (usize, usize),
    desc: usize,
    plane: usize,
    point: (usize, usize),
    value: u32,
    cow: &mut u32,
) {
    let (head, meta) = locations;
    let (x, y) = point;
    let kind = word(d, desc);
    let op = word(d, desc + 4);
    let def = word(d, desc + 8);
    let empty = if kind >= 3 { value == 0 } else { value == def };
    if op != 3 && mask(d, meta + 8) & (1u64 << plane) == 0 && empty {
        return;
    }
    set(
        d,
        meta + 32 + word(d, desc + 12) as usize,
        kind,
        index(kind, x, y),
        value,
    );
    put_mask(d, meta + 8, mask(d, meta + 8) | (1u64 << plane));
    put_mask(d, meta + 16, mask(d, meta + 16) | (1u64 << plane));
    if meta == head && op != 3 {
        *cow &= if kind >= 3 { !2 } else { !1 };
    }
}

pub(super) fn copy_blended(d: &mut [u8]) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 64 || d.len() > MAX_BYTES || word(d, 0) != 0x50434c57 || word(d, 4) != 2 {
        return Err(bad);
    }
    let tiles = word(d, 8) as usize;
    let planes = word(d, 12) as usize;
    let chunks = word(d, 24) as usize;
    let blocks = word(d, 28) as usize;
    if !(1..=5).contains(&tiles)
        || !(2..=64).contains(&planes)
        || chunks >= planes
        || blocks >= planes
        || chunks == blocks
        || word(d, 32) > 1
        || word(d, 36) > 1
        || mask(d, 40) >> 48 != 0
        || mask(d, 48) != 0
        || d[56..64].iter().any(|&v| v != 0)
        || 64 + planes * 16 > d.len()
    {
        return Err(bad);
    }
    let mut bytes = 0;
    for p in 0..planes {
        let at = 64 + p * 16;
        let kind = word(d, at);
        let op = word(d, at + 4);
        if length(kind) == 0
            || op > 3
            || kind == 0 && op != 3
            || word(d, at + 12) as usize != bytes
            || op != 3
                && word(d, at + 8)
                    > match kind {
                        1 => 255,
                        2 => 15,
                        _ => 1,
                    }
        {
            return Err(bad);
        }
        bytes += length(kind);
    }
    let head = 64 + planes * 16;
    let stride = 32 + bytes;
    let e = head + tiles * stride;
    if e + EXT != d.len() || word(d, e + 448) > 3 || word(d, e + 452) != 0 {
        return Err(bad);
    }
    if word(d, 64 + chunks * 16) != 4
        || word(d, 68 + chunks * 16) != 0
        || word(d, 64 + blocks * 16) != 3
        || word(d, 68 + blocks * 16) != 0
    {
        return Err(bad);
    }
    let mut original = 0u64;
    let mut cloned = 0u64;
    let mut bins = [0u8; 16];
    for p in 0..planes {
        let role = d[e + 256 + p];
        let kind = word(d, 64 + p * 16);
        let op = word(d, 68 + p * 16);
        if role > 8
            || role == 4
            || role <= 1 && kind != 0
            || role == 2 && (kind != 1 || op != 3)
            || (3..=6).contains(&role) && op != 1
            || role == 7 && (op != 2 || word(d, 36) == 0)
            || role == 8 && op != 0
        {
            return Err(bad);
        }
        if role >= 3 {
            bins[(word(d, e + p * 4) & 15) as usize] += 1;
            if bins[(word(d, e + p * 4) & 15) as usize] >= 9 && word(d, 36) == 1 {
                return Err(bad);
            }
            for (base, seen) in [(e + 320, &mut original), (e + 384, &mut cloned)] {
                let rank = d[base + p];
                if rank != 255 {
                    if rank as usize >= planes || *seen & (1u64 << rank) != 0 {
                        return Err(bad);
                    }
                    *seen |= 1u64 << rank;
                } else if mask(d, head + 8) & (1u64 << p) != 0 {
                    return Err(bad);
                }
            }
        }
    }
    for i in 0..513 {
        let v = f32::from_bits(word(d, e + 3656 + i * 4));
        if !v.is_finite() || !(0.0..=1.0).contains(&v) {
            return Err(bad);
        }
    }
    for t in 0..tiles {
        let meta = head + t * stride;
        if mask(d, meta + 16) != 0
            || mask(d, meta + 24) != 0
            || planes < 64 && mask(d, meta + 8) >> planes != 0
        {
            return Err(bad);
        }
        for prev in 0..t {
            let other = head + prev * stride;
            if word(d, meta) == word(d, other) && word(d, meta + 4) == word(d, other + 4) {
                return Err(bad);
            }
        }
    }
    let source = head + 32;
    let dx = word(d, 16) as i32;
    let dy = word(d, 20) as i32;
    let sx = word(d, head) as i32 as i64 * 128;
    let sy = word(d, head + 4) as i32 as i64 * 128;
    let chunk_base = source + word(d, 76 + chunks * 16) as usize;
    let block_base = source + word(d, 76 + blocks * 16) as usize;
    let mut rng = JavaRandom::from_lcg_state(mask(d, 40)).unwrap();
    let mut draws = 0;
    let initial_cow = word(d, e + 448);
    let mut pending_cow = initial_cow;
    for xi in 0..128 {
        for yi in 0..128 {
            let x = if dx > 0 { 127 - xi } else { xi };
            let y = if dy > 0 { 127 - yi } else { yi };
            if get(d, chunk_base, 4, index(4, x, y)) == 0
                && get(d, block_base, 3, index(3, x, y)) == 0
            {
                continue;
            }
            let square = distance_squared(d, e + 456, x as i32, y as i32);
            let blended = square < 256;
            let weight = f32::from_bits(word(d, e + 3656 + square * 4));
            let wx = sx + x as i64 + dx as i64;
            let wy = sy + y as i64 + dy as i64;
            let target = (0..tiles).find(|&t| {
                let m = head + t * stride;
                word(d, m) as i32 as i64 == wx.div_euclid(128)
                    && word(d, m + 4) as i32 as i64 == wy.div_euclid(128)
            });
            let tx = wx.rem_euclid(128) as usize;
            let ty = wy.rem_euclid(128) as usize;
            // Heights, terrain and fluid are evaluated before the layer map is captured.
            for role in [0, 2, 1, 3] {
                for p in 0..planes {
                    if d[e + 256 + p] != role {
                        continue;
                    }
                    let desc = 64 + p * 16;
                    let kind = word(d, desc);
                    let base = source + word(d, desc + 12) as usize;
                    let mut value = get(d, base, kind, index(kind, x, y));
                    if blended && role >= 2 && !draw(&mut rng, &mut draws, weight) {
                        continue;
                    }
                    if let Some(t) = target {
                        let meta = head + t * stride;
                        if blended && role < 2 {
                            value = mixed(
                                value,
                                get(
                                    d,
                                    meta + 32 + word(d, desc + 12) as usize,
                                    kind,
                                    index(kind, tx, ty),
                                ),
                                weight,
                                true,
                            );
                        }
                        write(d, (head, meta), desc, p, (tx, ty), value, &mut pending_cow);
                    }
                }
            }
            if word(d, 36) == 1 {
                let clear = word(d, 32) == 1 && (!blended || draw(&mut rng, &mut draws, weight));
                if clear {
                    if let Some(t) = target {
                        let meta = head + t * stride;
                        for p in 0..planes {
                            let desc = 64 + p * 16;
                            if word(d, desc + 4) != 2 || mask(d, meta + 8) & (1u64 << p) == 0 {
                                continue;
                            }
                            write(
                                d,
                                (head, meta),
                                desc,
                                p,
                                (tx, ty),
                                word(d, desc + 8),
                                &mut pending_cow,
                            );
                        }
                    }
                }
                let mut active = [0usize; 64];
                let mut count = 0;
                let mut values = [0u32; 64];
                for p in 0..planes {
                    let desc = 64 + p * 16;
                    let kind = word(d, desc);
                    if d[e + 256 + p] < 3 || mask(d, head + 8) & (1u64 << p) == 0 {
                        continue;
                    }
                    values[p] = get(
                        d,
                        source + word(d, desc + 12) as usize,
                        kind,
                        index(kind, x, y),
                    );
                    if values[p] != word(d, desc + 8) {
                        active[count] = p;
                        count += 1;
                    }
                }
                let capacity = if count <= 12 {
                    16
                } else if count <= 24 {
                    32
                } else if count <= 48 {
                    64
                } else {
                    128
                };
                active[..count].sort_unstable_by_key(|&p| {
                    let flag = if word(d, 64 + p * 16) >= 3 { 2 } else { 1 };
                    let order = if initial_cow & flag != 0 && pending_cow & flag == 0 {
                        e + 384
                    } else {
                        e + 320
                    };
                    (word(d, e + p * 4) & (capacity - 1), d[order + p])
                });
                for &p in &active[..count] {
                    if d[e + 256 + p] != 7 {
                        continue;
                    }
                    let desc = 64 + p * 16;
                    let kind = word(d, desc);
                    let mut value = values[p];
                    if blended && kind >= 3 && !draw(&mut rng, &mut draws, weight) {
                        continue;
                    }
                    if let Some(t) = target {
                        let meta = head + t * stride;
                        if blended && kind < 3 {
                            value = mixed(
                                value,
                                get(
                                    d,
                                    meta + 32 + word(d, desc + 12) as usize,
                                    kind,
                                    index(kind, tx, ty),
                                ),
                                weight,
                                false,
                            );
                        }
                        write(d, (head, meta), desc, p, (tx, ty), value, &mut pending_cow);
                    }
                }
            }
            // Annotations precede biomes in the scalar implementation.
            for role in [6, 5] {
                for p in 0..planes {
                    if d[e + 256 + p] != role || blended && !draw(&mut rng, &mut draws, weight) {
                        continue;
                    }
                    if let Some(t) = target {
                        let desc = 64 + p * 16;
                        let kind = word(d, desc);
                        let value = get(
                            d,
                            source + word(d, desc + 12) as usize,
                            kind,
                            index(kind, x, y),
                        );
                        write(
                            d,
                            (head, head + t * stride),
                            desc,
                            p,
                            (tx, ty),
                            value,
                            &mut pending_cow,
                        );
                    }
                }
            }
        }
    }
    put_mask(d, 40, rng.lcg_state());
    put_mask(d, 48, draws);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn edge_distance_matches_a_full_euclidean_search() {
        let mut d = vec![0u8; 3200];
        let halo = 0;
        for y in 12..137 {
            for x in 12..137 {
                let i = x + y * 160;
                d[i / 8] |= 1 << (i % 8);
            }
        }
        for y in (0..128).step_by(7) {
            for x in (0..128).step_by(5) {
                let mut expected = 256;
                if !selected(&d, halo, x, y) {
                    expected = 0;
                } else {
                    for dy in -16..=16 {
                        for dx in -16..=16 {
                            if !selected(&d, halo, x + dx, y + dy) {
                                expected = expected.min(dx * dx + dy * dy);
                            }
                        }
                    }
                }
                assert_eq!(distance_squared(&d, halo, x, y), expected as usize);
            }
        }
    }
    #[test]
    fn interpolation_keeps_java_float_rounding() {
        assert_eq!(mixed(0, 1, f32::from_bits(0x3f000001), false), 0);
        assert_eq!(mixed(1, 0, 0.5, false), 1);
        assert_eq!(mixed((-1i32) as u32, 0, 0.5, true), 0);
    }
    fn frame() -> Vec<u8> {
        let specs = [(4, 0, 8), (3, 0, 8), (0, 3, 0), (3, 1, 3)];
        let bytes = 8 + 2048 + 65536 + 2048;
        let head = 128;
        let e = head + 32 + bytes;
        let mut d = vec![0; e + EXT];
        for (p, v) in [
            (0, 0x50434c57),
            (4, 2),
            (8, 1),
            (12, 4),
            (16, 1),
            (20, 0),
            (24, 0),
            (28, 1),
        ] {
            d[p..p + 4].copy_from_slice(&u32::to_le_bytes(v));
        }
        put_mask(&mut d, 40, JavaRandom::new(0).lcg_state());
        put_mask(&mut d, head + 8, 15);
        let mut offset = 0;
        for (p, (kind, op, role)) in specs.into_iter().enumerate() {
            let desc = 64 + p * 16;
            for (at, v) in [
                (desc, kind),
                (desc + 4, op),
                (desc + 12, offset as u32),
                (e + p * 4, p as u32),
            ] {
                d[at..at + 4].copy_from_slice(&v.to_le_bytes());
            }
            d[e + 256 + p] = role;
            offset += length(kind);
        }
        for i in 0..64 {
            d[e + 320 + i] = 255;
            d[e + 384 + i] = 255;
        }
        for (p, rank) in [(0, 0), (1, 1), (3, 2)] {
            d[e + 320 + p] = rank;
            d[e + 384 + p] = rank;
        }
        d[head + 32 + 8] = 1;
        let bit = 16 + 16 * 160;
        d[e + 456 + bit / 8] |= 1 << (bit % 8);
        for i in 0..513 {
            d[e + 3656 + i * 4..e + 3660 + i * 4].copy_from_slice(&0.5f32.to_le_bytes());
        }
        for i in 0..16384 {
            d[head + 32 + 8 + 2048 + i * 4..head + 32 + 8 + 2048 + i * 4 + 4]
                .copy_from_slice(&10u32.to_le_bytes());
        }
        d[head + 32 + 8 + 2048..head + 32 + 8 + 2048 + 4].copy_from_slice(&20u32.to_le_bytes());
        d
    }
    #[test]
    fn blended_frame_commits_the_exact_rng_state_and_height() {
        let mut d = frame();
        let mut rng = JavaRandom::new(0);
        rng.next_float();
        copy_blended(&mut d).unwrap();
        assert_eq!(mask(&d, 40), rng.lcg_state());
        assert_eq!(mask(&d, 48), 1);
        assert_eq!(word(&d, 128 + 32 + 8 + 2048 + 4), 15);
    }
    #[test]
    fn missing_destinations_still_consume_the_java_draws() {
        let mut d = frame();
        d[16..20].copy_from_slice(&1000u32.to_le_bytes());
        copy_blended(&mut d).unwrap();
        assert_eq!(mask(&d, 48), 1);
        assert_eq!(mask(&d, 128 + 16), 0);
    }
    #[test]
    fn malformed_blend_frames_do_not_mutate_data_or_rng() {
        for at in [36, 48, 128 + 16, 128 + 32 + 8 + 2048 + 65536 + 2048 + 3656] {
            let mut d = frame();
            d[at..at + 4].copy_from_slice(&u32::MAX.to_le_bytes());
            let before = d.clone();
            assert!(copy_blended(&mut d).is_err());
            assert_eq!(d, before);
        }
    }
}
