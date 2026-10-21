//! Source-order BO3 events and distinct material spans in one bounded transaction.
use std::collections::HashMap;
pub const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_BLOCKS: usize = 262_144;
const MAX_CHOICES: usize = 524_288;
const MAGIC: u32 = 0x57424f33;
type Span = [u32; 2];
fn trim(bytes: &[u8], mut start: usize, mut end: usize) -> Span {
    while start < end && bytes[start] <= 32 {
        start += 1;
    }
    while end > start && bytes[end - 1] <= 32 {
        end -= 1;
    }
    [start as u32, (end - start) as u32]
}
fn view(bytes: &[u8], span: Span) -> &[u8] {
    &bytes[span[0] as usize..(span[0] + span[1]) as usize]
}
fn integer(bytes: &[u8]) -> Option<i32> {
    std::str::from_utf8(bytes).ok()?.parse().ok()
}
fn material<'a>(
    bytes: &'a [u8],
    span: Span,
    table: &mut HashMap<&'a [u8], u32>,
    spans: &mut Vec<Span>,
) -> Option<u32> {
    let text = view(bytes, span);
    if let Some(&index) = table.get(text) {
        return Some(index);
    }
    if spans.len() >= 65_536 {
        return None;
    }
    let index = spans.len() as u32;
    spans.push(span);
    table.insert(text, index);
    Some(index)
}
/// WBO3 v1: 64-byte header, 40-byte ordered events, 20-byte alternatives, 8-byte material spans.
pub fn frame(bytes: &[u8]) -> Option<Vec<u8>> {
    if bytes.is_empty() || bytes.len() > MAX_BYTES {
        return None;
    }
    let mut events: Vec<[u32; 10]> = Vec::new();
    let mut choices: Vec<[u32; 5]> = Vec::new();
    let mut materials = Vec::new();
    let mut table = HashMap::new();
    let mut min = [i32::MAX; 3];
    let mut max = [i32::MIN; 3];
    let mut position = 0;
    let mut blocks = 0;
    while position < bytes.len() {
        let start = position;
        while position < bytes.len() && bytes[position] != b'\r' && bytes[position] != b'\n' {
            position += 1;
        }
        let end = position;
        if position < bytes.len() {
            let cr = bytes[position] == b'\r';
            position += 1;
            if cr && bytes.get(position) == Some(&b'\n') {
                position += 1;
            }
        }
        let span = trim(bytes, start, end);
        let line = view(bytes, span);
        let base = span[0] as usize;
        if line.is_empty() || line.starts_with(b"#") {
            continue;
        }
        if events.len() >= 300_000 {
            return None;
        }
        // Preserve the original prefix precedence: BlockCheck enters the Block branch too.
        if line.starts_with(b"Block") || line.starts_with(b"RandomBlock") {
            blocks += 1;
            if blocks > MAX_BLOCKS {
                return None;
            }
            let random = line.starts_with(b"RandomBlock");
            let open = line.iter().position(|&b| b == b'(')?;
            let close = line.iter().rposition(|&b| b == b')')?;
            if close < open {
                return None;
            }
            let mut args = Vec::new();
            let mut at = open + 1;
            for i in open + 1..close {
                if line[i] == b',' {
                    args.push([(base + at) as u32, (i - at) as u32]);
                    at = i + 1;
                }
            }
            args.push([(base + at) as u32, (close - at) as u32]);
            // Java String.split discards trailing empty arguments for a nonempty argument string.
            if close > open + 1 {
                while args.last().is_some_and(|s| s[1] == 0) {
                    args.pop();
                }
            }
            let xyz = [
                integer(view(bytes, *args.first()?))?,
                integer(view(bytes, *args.get(2)?))?,
                integer(view(bytes, *args.get(1)?))?,
            ];
            for axis in 0..3 {
                min[axis] = min[axis].min(xyz[axis]);
                max[axis] = max[axis].max(xyz[axis]);
            }
            let first = choices.len();
            let mut cursor = 3;
            loop {
                if choices.len() >= MAX_CHOICES {
                    return None;
                }
                let id = material(bytes, *args.get(cursor)?, &mut table, &mut materials)?;
                cursor += 1;
                let (nbt, chance) = if random {
                    let next = *args.get(cursor)?;
                    cursor += 1;
                    if let Some(chance) = integer(view(bytes, next)) {
                        ([u32::MAX, 0], chance)
                    } else {
                        let chance = integer(view(bytes, *args.get(cursor)?))?;
                        cursor += 1;
                        (next, chance)
                    }
                } else {
                    (args.get(cursor).copied().unwrap_or([u32::MAX, 0]), 100)
                };
                choices.push([id, nbt[0], nbt[1], chance as u32, 0]);
                if !random || cursor == args.len() {
                    break;
                }
                if cursor > args.len() {
                    return None;
                }
            }
            events.push([
                if random { 4 } else { 3 },
                xyz[0] as u32,
                xyz[1] as u32,
                xyz[2] as u32,
                first as u32,
                (choices.len() - first) as u32,
                0,
                0,
                0,
                0,
            ]);
        } else if line.starts_with(b"LightCheck")
            || line.starts_with(b"Branch")
            || line.starts_with(b"WeightedBranch")
        {
            events.push([1, 0, 0, 0, 0, 0, span[0], span[1], 0, 0]);
        } else if let Some(colon) = line.iter().position(|&b| b == b':') {
            let name = trim(bytes, base, base + colon);
            let value = trim(bytes, base + colon + 1, base + line.len());
            events.push([0, 0, 0, 0, 0, 0, name[0], name[1], value[0], value[1]]);
        } else {
            events.push([2, 0, 0, 0, 0, 0, span[0], span[1], 0, 0]);
        }
    }
    if blocks == 0 {
        return None;
    }
    let choices_at = 64 + events.len() * 40;
    let materials_at = choices_at + choices.len() * 20;
    let size = materials_at + materials.len() * 8;
    let header = [
        MAGIC,
        1,
        events.len() as u32,
        choices.len() as u32,
        materials.len() as u32,
        min[0] as u32,
        min[1] as u32,
        min[2] as u32,
        max[0] as u32,
        max[1] as u32,
        max[2] as u32,
        64,
        choices_at as u32,
        materials_at as u32,
        size as u32,
        bytes.len() as u32,
    ];
    let mut out = Vec::with_capacity(size);
    for word in header
        .into_iter()
        .chain(events.into_iter().flatten())
        .chain(choices.into_iter().flatten())
        .chain(materials.into_iter().flatten())
    {
        out.extend_from_slice(&word.to_le_bytes());
    }
    Some(out)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn word(frame: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(frame[offset..offset + 4].try_into().unwrap())
    }
    #[test]
    fn preserves_axes_duplicates_and_distinct_materials() {
        let f=frame(b"RotateRandomly: false\nBlock(-2,7,3,STONE)\nRandomBlock(1,5,-4,STONE,0,35:4,100)\nBlock(-2,7,3,STONE)\n").unwrap();
        assert_eq!(word(&f, 8), 4);
        assert_eq!(word(&f, 12), 4);
        assert_eq!(word(&f, 16), 2);
        assert_eq!(word(&f, 20) as i32, -2);
        assert_eq!(word(&f, 24) as i32, -4);
        assert_eq!(word(&f, 28), 5);
        assert_eq!(word(&f, 64 + 40), 3);
        assert_eq!(word(&f, 64 + 44) as i32, -2);
        assert_eq!(word(&f, 64 + 48), 3);
        assert_eq!(word(&f, 64 + 52), 7);
    }
    #[test]
    fn keeps_external_nbt_chances_and_source_order() {
        let f=frame(b"# comment\r\n unknown \rBranch(ignored)\nRandomBlock(+1,-2,3,1,entity.nbt,-3,STONE,101)\n").unwrap();
        assert_eq!(word(&f, 8), 3);
        assert_eq!(word(&f, 64), 2);
        assert_eq!(word(&f, 104), 1);
        let at = word(&f, 48) as usize;
        assert_eq!(word(&f, at + 12) as i32, -3);
        assert_eq!(word(&f, at + 20 + 12), 101);
        assert_ne!(word(&f, at + 4), u32::MAX);
        assert_eq!(word(&f, at + 24), u32::MAX);
    }
    #[test]
    fn malformed_sources_request_original_reader() {
        for source in [
            b"".as_slice(),
            b"Block(0, 0,0,1)",
            b"RandomBlock(0,0,0,1,entity.nbt)",
            b"Block(2147483648,0,0,1)",
            b"BlockCheck(0,0,0)",
        ] {
            assert!(frame(source).is_none());
        }
    }
    #[test]
    fn matches_java_trailing_args_and_trim() {
        let f = frame(b" \tkey : value : other\r\n Block(0,0,0,STONE,,, ) \n").unwrap();
        let at = word(&f, 48) as usize;
        assert_eq!(word(&f, at + 8), 0); // Empty optional filename is retained before a nonempty trailing argument.
        assert!(frame(b"Block(0,0,0,STONE,,,,)\n").is_some());
    }
}
