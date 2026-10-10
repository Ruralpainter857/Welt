//! BO2 ASCII line parsing, source-order records, metadata spans and bounds in one pass.
pub const MAX_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_BLOCKS: usize = 262_144;
pub const MAGIC: u32 = 0x57424f32;
pub const HEADER: usize = 64;
#[derive(Debug, PartialEq)]
pub struct Block {
    pub xyz: [i32; 3],
    pub id: i32,
    pub data: i32,
    pub branch: Option<[i32; 2]>,
}
#[derive(Debug, PartialEq)]
pub struct Property {
    pub name: [usize; 2],
    pub value: [usize; 2],
}
#[derive(Debug, PartialEq)]
pub struct Parsed {
    pub blocks: Vec<Block>,
    pub properties: Vec<Property>,
    pub min: [i32; 3],
    pub max: [i32; 3],
}
fn integer(bytes: &[u8]) -> Option<i32> {
    std::str::from_utf8(bytes).ok()?.parse().ok()
}
fn trim(bytes: &[u8], mut start: usize, mut end: usize) -> [usize; 2] {
    // String.trim() removes all characters <= U+0020, not Rust's Unicode whitespace set.
    while start < end && bytes[start] <= 32 {
        start += 1;
    }
    while end > start && bytes[end - 1] <= 32 {
        end -= 1;
    }
    [start, end - start]
}
pub fn parse(bytes: &[u8]) -> Option<Parsed> {
    if bytes.is_empty() || bytes.len() > MAX_BYTES {
        return None;
    }
    let mut result = Parsed {
        blocks: Vec::new(),
        properties: Vec::new(),
        min: [i32::MAX; 3],
        max: [i32::MIN; 3],
    };
    let mut position = 0;
    let mut metadata = false;
    let mut data = false;
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
        if trim(bytes, start, end)[1] == 0 {
            continue;
        }
        let line = &bytes[start..end];
        if metadata {
            if line == b"[DATA]" {
                metadata = false;
                data = true;
            } else {
                let split = line.iter().position(|&byte| byte == b'=')? + start;
                if result.properties.len() >= 65_536 {
                    return None;
                }
                result.properties.push(Property {
                    name: trim(bytes, start, split),
                    value: trim(bytes, split + 1, end),
                });
            }
        } else if data {
            if result.blocks.len() >= MAX_BLOCKS {
                return None;
            }
            let colon = line.iter().position(|&byte| byte == b':')?;
            let coords = &line[..colon];
            let spec = &line[colon + 1..];
            let first = coords.iter().position(|&byte| byte == b',')?;
            let second = first + 1 + coords[first + 1..].iter().position(|&byte| byte == b',')?;
            let xyz = [
                integer(&coords[..first])?,
                integer(&coords[first + 1..second])?,
                integer(&coords[second + 1..])?,
            ];
            let (id, value, branch) = if let Some(dot) = spec.iter().position(|&byte| byte == b'.')
            {
                let id = integer(&spec[..dot])?;
                let suffix = &spec[dot + 1..];
                if let Some(hash) = suffix.iter().position(|&byte| byte == b'#') {
                    let tail = &suffix[hash + 1..];
                    let at = tail.iter().position(|&byte| byte == b'@')?;
                    (
                        id,
                        integer(&suffix[..hash])?,
                        Some([integer(&tail[..at])?, integer(&tail[at + 1..])?]),
                    )
                } else {
                    (id, integer(suffix)?, None)
                }
            } else {
                (integer(spec)?, 0, None)
            };
            for (axis, &coordinate) in xyz.iter().enumerate() {
                result.min[axis] = result.min[axis].min(coordinate);
                result.max[axis] = result.max[axis].max(coordinate);
            }
            result.blocks.push(Block {
                xyz,
                id,
                data: value,
                branch,
            });
        } else if line == b"[META]" {
            metadata = true;
        }
    }
    (!result.blocks.is_empty()).then_some(result)
}
/// Frame v1 keeps every original record, including duplicates, in source order.
/// Java may preserve its historical HashMap insertion and visitor behaviour.
pub fn frame(bytes: &[u8]) -> Option<Vec<u8>> {
    let parsed = parse(bytes)?;
    let meta = HEADER + parsed.blocks.len() * 32;
    let size = meta + parsed.properties.len() * 16;
    let mut out = Vec::with_capacity(size);
    let words = [
        MAGIC,
        1,
        parsed.blocks.len() as u32,
        parsed.properties.len() as u32,
        parsed.min[0] as u32,
        parsed.min[1] as u32,
        parsed.min[2] as u32,
        parsed.max[0] as u32,
        parsed.max[1] as u32,
        parsed.max[2] as u32,
        HEADER as u32,
        meta as u32,
        size as u32,
        bytes.len() as u32,
        0,
        0,
    ];
    for word in words {
        out.extend_from_slice(&word.to_le_bytes());
    }
    for block in parsed.blocks {
        let branch = block.branch.unwrap_or([0, 0]);
        for value in [
            block.xyz[0],
            block.xyz[1],
            block.xyz[2],
            block.id,
            block.data,
            i32::from(block.branch.is_some()),
            branch[0],
            branch[1],
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    for property in parsed.properties {
        for value in [
            property.name[0],
            property.name[1],
            property.value[0],
            property.value[1],
        ] {
            out.extend_from_slice(&(value as u32).to_le_bytes());
        }
    }
    Some(out)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn signed_records_branches_duplicates_and_bounds() {
        let p = parse(b"Ignored\n[DATA]\n[META]\n k = v = rest \n[DATA]\n-2,+3,4:20.0#-5@+6\n-2,3,4:1\n7,-8,9:35.4").unwrap();
        assert_eq!(p.min, [-2, -8, 4]);
        assert_eq!(p.max, [7, 3, 9]);
        assert_eq!(p.blocks.len(), 3);
        assert_eq!(p.blocks[0].branch, Some([-5, 6]));
        assert_eq!(p.blocks[1].id, 1);
        assert_eq!(p.properties.len(), 1);
        assert_eq!(p.blocks[2].data, 4);
    }
    #[test]
    fn java_line_endings_trim_and_header_matching() {
        let source =
            b" [META] \rignored\r\n[META]\r\n\0\t \r\n key\t=\x80 value \r[DATA]\n0,0,0:1\r";
        let p = parse(source).unwrap();
        let field = &p.properties[0];
        assert_eq!(
            &source[field.name[0]..field.name[0] + field.name[1]],
            b"key"
        );
        assert_eq!(
            &source[field.value[0]..field.value[0] + field.value[1]],
            b"\x80 value"
        );
    }
    #[test]
    fn malformed_and_overflowing_numbers_request_java_fallback() {
        for line in [
            "0,0,0:1#2@3",
            "0,0,0:1.0#2",
            "0,0,0:1.0#2@3@4",
            "0, 0,0:1",
            "2147483648,0,0:1",
            "0,0,0:-2147483649",
            "0,0,0:1. 0",
            "broken",
        ] {
            assert!(
                parse(format!("[META]\n[DATA]\n{line}\n").as_bytes()).is_none(),
                "accepted {line}"
            );
        }
        assert!(parse(b"[META]\ninvalid\n[DATA]\n0,0,0:1").is_none());
        assert!(parse(b"[META]\n[DATA]\n").is_none());
    }
    #[test]
    fn frame_has_bounded_spans_and_original_order() {
        let source = b"[META]\na=1\na=2\n[DATA]\n-2147483648,0,0:1\n2147483647,0,0:2.3\n";
        let out = frame(source).unwrap();
        assert_eq!(out.len(), 64 + 64 + 32);
        let word = |i: usize| u32::from_le_bytes(out[i * 4..i * 4 + 4].try_into().unwrap());
        assert_eq!(word(0), MAGIC);
        assert_eq!(word(2), 2);
        assert_eq!(word(3), 2);
        assert_eq!(word(4), i32::MIN as u32);
        assert_eq!(word(7), i32::MAX as u32);
        assert_eq!(word(16), i32::MIN as u32);
        assert_eq!(word(24), i32::MAX as u32);
    }
}
