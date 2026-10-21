//! WHIM v5: complete fresh FancyTheme tile; all five source programs stay in Rust.
use welt_core::error::WeltError;
const AREA: usize = 16384;
const NEIGHBOR: usize = 138;
const KINDS: [u32; 9] = [0, 0, 1, 2, 2, 2, 2, 3, 3];
#[derive(Default)]
pub(crate) struct Scratch {
    source: Vec<f64>,
    neighborhood: Vec<f32>,
    heights: Vec<f32>,
    temperature: Vec<f64>,
    humidity: Vec<f64>,
    forest: Vec<f64>,
    output: Vec<u8>,
}
fn word(d: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(d[p..p + 4].try_into().unwrap())
}
fn long(d: &[u8], p: usize) -> u64 {
    u64::from_le_bytes(d[p..p + 8].try_into().unwrap())
}
fn put(d: &mut [u8], p: usize, v: u32) {
    d[p..p + 4].copy_from_slice(&v.to_le_bytes());
}
fn put_long(d: &mut [u8], p: usize, v: u64) {
    d[p..p + 8].copy_from_slice(&v.to_le_bytes());
}
fn length(kind: u32) -> usize {
    match kind {
        0 => AREA * 4,
        1 => AREA,
        2 => AREA / 2,
        _ => AREA / 8,
    }
}
/// Reject every malformed section before writing final planes.
pub(crate) fn fill(d: &mut [u8], s: &mut Scratch) -> Result<(), WeltError> {
    let bad = WeltError::IllegalArgument;
    if d.len() < 432
        || d.len() > 4 * 1024 * 1024
        || word(d, 0) != 0x4d494857
        || word(d, 4) != 5
        || word(d, 8) as usize != d.len()
        || word(d, 12) != 9
        || word(d, 24) != 0
        || word(d, 120) != 400
        || word(d, 124) != 256
        || d[76..120]
            .iter()
            .chain(d[128..208].iter())
            .chain(d[228..256].iter())
            .any(|v| *v != 0)
    {
        return Err(bad);
    }
    let min = word(d, 16) as i32;
    let max = word(d, 20) as i32;
    if !(1..=65536).contains(&(i64::from(max) - i64::from(min))) {
        return Err(bad);
    }
    let tall = i64::from(max) - i64::from(min) > 256;
    let x = word(d, 40) as i32;
    let y = word(d, 44) as i32;
    if i64::from(word(d, 400) as i32) * 128 != i64::from(x)
        || i64::from(word(d, 404) as i32) * 128 != i64::from(y)
        || long(d, 408) != 7
        || long(d, 416) != 0
        || long(d, 424) != 0
    {
        return Err(bad);
    }
    for p in [28, 32, 36, 56, 60, 64, 68] {
        if word(d, p) > 255 {
            return Err(bad);
        }
    }
    let mut planes = [0usize; 9];
    let mut bytes = 0;
    for (p, kind) in KINDS.iter().enumerate() {
        let desc = 256 + p * 16;
        if word(d, desc) != *kind
            || word(d, desc + 4) != (if p < 3 { p as u32 } else { 3 })
            || word(d, desc + 8) != 0
            || word(d, desc + 12) as usize != bytes
        {
            return Err(bad);
        }
        planes[p] = 432 + bytes;
        bytes += length(*kind);
    }
    let mut ranges = [(0usize, 0usize); 5];
    let mut end = 432 + bytes;
    for (i, range) in ranges.iter_mut().enumerate() {
        let start = word(d, 208 + i * 4) as usize;
        if start != end || start > d.len() || d.len() - start < 128 {
            return Err(bad);
        }
        let size = word(d, start + 8) as usize;
        end = start.checked_add(size).ok_or(bad)?;
        if end > d.len()
            || word(d, start + 28) != (if i == 0 { 138 } else { 128 })
            || word(d, start + 32) != (if i == 0 { 138 } else { 128 })
        {
            return Err(bad);
        }
        *range = (start, end);
    }
    if end != d.len() {
        return Err(bad);
    }
    s.source.resize(NEIGHBOR * NEIGHBOR, 0.0);
    crate::height_map_program::fill_source(&d[..ranges[0].1], ranges[0].0, &mut s.source)?;
    s.neighborhood.resize(NEIGHBOR * NEIGHBOR, 0.0);
    for (out, value) in s.neighborhood.iter_mut().zip(&s.source) {
        *out = *value as f32;
    }
    s.heights.resize(AREA, 0.0);
    for y in 0..128 {
        for x in 0..128 {
            let h = s.neighborhood[(y + 5) * NEIGHBOR + x + 5].clamp(min as f32, (max - 1) as f32);
            let raw = ((h - min as f32) * 256.0) as i32;
            let raw = if tall { raw } else { (raw as u16) as i32 };
            s.heights[y * 128 + x] = raw as f32 / 256.0 + min as f32;
        }
    }
    s.temperature.resize(AREA, 0.0);
    s.humidity.resize(AREA, 0.0);
    s.forest.resize(AREA, 0.0);
    crate::height_map_program::fill_source(&d[..ranges[1].1], ranges[1].0, &mut s.temperature)?;
    crate::height_map_program::fill_source(&d[..ranges[2].1], ranges[2].0, &mut s.humidity)?;
    crate::height_map_program::fill_source(&d[..ranges[3].1], ranges[3].0, &mut s.forest)?;
    s.source.resize(AREA, 0.0);
    crate::height_map_program::fill_source(&d[..ranges[4].1], ranges[4].0, &mut s.source)?;
    let water = word(d, 48) as i32;
    for i in 0..AREA {
        let delta = s.heights[i] - water as f32;
        let lapse = if delta.is_nan() {
            f32::NAN
        } else {
            delta.max(0.0)
        } / 2.0;
        s.temperature[i] = (s.temperature[i] - f64::from(lapse)) + s.source[i];
        s.humidity[i] += s.source[i];
    }
    s.output.resize(AREA * 7, 0);
    crate::fancy_theme::fill_fancy_theme_tile(
        128,
        128,
        water,
        word(d, 52) as i32,
        word(d, 28) as u8,
        word(d, 32) as u8,
        word(d, 36) as u8,
        word(d, 56) as u8,
        word(d, 60) as u8,
        word(d, 64) as u8,
        word(d, 68) as u8,
        &s.heights,
        &s.neighborhood,
        &s.temperature,
        &s.humidity,
        &s.forest,
        &mut s.output,
    )
    .map_err(|_| bad)?;
    // All validation/calculation is complete; fresh final planes can now be committed.
    d[432..432 + bytes].fill(0);
    let factory_water = word(d, 72);
    let mut present = 7u64;
    let mut count = 3;
    d[140..143].copy_from_slice(&[0, 1, 2]);
    for x in 0..128 {
        for y in 0..128 {
            let i = y * 128 + x;
            let h = s.neighborhood[(y + 5) * NEIGHBOR + x + 5].clamp(min as f32, (max - 1) as f32);
            let raw = ((h - min as f32) * 256.0) as i32;
            put(
                d,
                planes[0] + i * 4,
                if tall {
                    raw as u32
                } else {
                    (raw as u16) as u32
                },
            );
            put(d, planes[1] + i * 4, factory_water);
            d[planes[2] + i] = s.output[i];
            for p in 3..9 {
                let value = s.output[(p - 2) * AREA + i];
                if value == 0 {
                    continue;
                }
                if present & (1 << p) == 0 {
                    d[140 + count] = p as u8;
                    count += 1;
                    present |= 1 << p;
                }
                if KINDS[p] == 2 {
                    d[planes[p] + i / 2] |= value << ((i & 1) * 4);
                } else {
                    d[planes[p] + i / 8] |= 1 << (i & 7);
                }
            }
        }
    }
    put_long(d, 408, present);
    put_long(d, 416, present);
    put(d, 204, count as u32);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame() -> Vec<u8> {
        let bytes: usize = KINDS.iter().map(|k| length(*k)).sum();
        let source = 432 + bytes;
        let mut d = vec![0; source + 5 * 160];
        let size = d.len();
        for (p, v) in [
            (0, 0x4d494857),
            (4, 5),
            (8, size as u32),
            (12, 9),
            (16, (-64i32) as u32),
            (20, 320),
            (28, 1),
            (32, 2),
            (36, 3),
            (48, 62),
            (52, 82),
            (56, 4),
            (60, 5),
            (64, 6),
            (68, 7),
            (72, 60),
            (120, 400),
            (124, 256),
        ] {
            put(&mut d, p, v);
        }
        put_long(&mut d, 408, 7);
        let mut offset = 0;
        for (i, k) in KINDS.iter().enumerate() {
            put(&mut d, 256 + i * 16, *k);
            put(&mut d, 260 + i * 16, if i < 3 { i as u32 } else { 3 });
            put(&mut d, 268 + i * 16, offset as u32);
            offset += length(*k);
        }
        for (i, value) in [80.0f64, 40.0, 70.0, 0.5, 0.0].iter().enumerate() {
            let p = source + i * 160;
            put(&mut d, 208 + i * 4, p as u32);
            for (off, v) in [
                (0, 0x50475457),
                (4, 1),
                (8, 160),
                (12, 1),
                (20, if i == 0 { (-5i32) as u32 } else { 0 }),
                (24, if i == 0 { (-5i32) as u32 } else { 0 }),
                (28, if i == 0 { 138 } else { 128 }),
                (32, if i == 0 { 138 } else { 128 }),
            ] {
                put(&mut d, p + off, v);
            }
            put_long(&mut d, p + 136, value.to_bits());
        }
        d
    }
    #[test]
    fn complete_factory_commits_quantised_storage_and_only_present_layers() {
        let mut d = frame();
        crate::height_map_import::import(&mut d, &mut Default::default()).unwrap();
        assert_eq!(long(&d, 408), 15);
        assert_eq!(long(&d, 416), 15);
        assert_eq!(word(&d, 204), 4);
        assert_eq!(&d[140..144], &[0, 1, 2, 3]);
        for i in 0..AREA {
            assert_eq!(word(&d, 432 + i * 4), 144 * 256);
            assert_eq!(word(&d, 432 + AREA * 4 + i * 4), 60);
            assert_eq!(d[432 + AREA * 8 + i], 1);
        }
        assert!(d[432 + AREA * 9..432 + AREA * 9 + AREA / 2]
            .iter()
            .all(|v| *v == 0x88));
    }
    #[test]
    fn malformed_fancy_frames_leave_all_output_and_metadata_unchanged() {
        let valid = frame();
        let source = word(&valid, 208) as usize;
        for (p, v) in [
            (0, 0),
            (8, 0),
            (12, 8),
            (20, (-64i32) as u32),
            (28, 256),
            (40, 128),
            (120, 0),
            (128, 1),
            (256, 2),
            (408, 0),
            (416, 1),
            (208, 0),
            (212, 0),
            (source + 8, 0),
            (source + 12, 0),
            (source + 16, 4),
            (source + 28, 128),
            (source + 128, 99),
        ] {
            let mut d = valid.clone();
            put(&mut d, p, v);
            let before = d.clone();
            assert_eq!(
                crate::height_map_import::import(&mut d, &mut Default::default()),
                Err(WeltError::IllegalArgument)
            );
            assert_eq!(d, before);
        }
    }
}
