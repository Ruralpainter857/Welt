//! Bounded vanilla structure extraction without a Java NBT object per block.
//! Output v1: a 64-byte little-endian header, compact indices, then a filtered
//! big-endian NBT root. Palette, entities and metadata are unchanged; the root
//! block list retains only entries carrying block-entity NBT, in input order.

pub const MAX_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_BLOCKS: usize = 262_144;
pub const MAGIC: u32 = 0x57535452;
pub const HEADER: usize = 64;

#[derive(Clone, Copy)]
struct Field<'a> {
    kind: u8,
    name: &'a [u8],
    start: usize,
    payload: usize,
    end: usize,
}
struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Option<&'a [u8]> {
        let end = self.at.checked_add(count)?;
        let value = self.bytes.get(self.at..end)?;
        self.at = end;
        Some(value)
    }
    fn byte(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }
    fn short(&mut self) -> Option<usize> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?) as usize)
    }
    fn int(&mut self) -> Option<i32> {
        Some(i32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }
    fn count(&mut self) -> Option<usize> {
        usize::try_from(self.int()?).ok()
    }
    fn field(&mut self, depth: usize) -> Option<Field<'a>> {
        let start = self.at;
        let kind = self.byte()?;
        if kind == 0 {
            return None;
        }
        let length = self.short()?;
        let name = self.take(length)?;
        let payload = self.at;
        self.skip(kind, depth)?;
        Some(Field {
            kind,
            name,
            start,
            payload,
            end: self.at,
        })
    }
    fn skip(&mut self, kind: u8, depth: usize) -> Option<()> {
        if depth > 128 {
            return None;
        }
        match kind {
            1..=6 => {
                self.take([0, 1, 2, 4, 8, 4, 8][kind as usize])?;
            }
            7 | 11 | 12 => {
                let count = self.count()?;
                let width = match kind {
                    7 => 1,
                    11 => 4,
                    _ => 8,
                };
                self.take(count.checked_mul(width)?)?;
            }
            8 => {
                let length = self.short()?;
                // Match the JNBT signed-short string payload limit.
                if length > i16::MAX as usize {
                    return None;
                }
                self.take(length)?;
            }
            9 => {
                let child = self.byte()?;
                let count = self.count()?;
                if child > 12 || child == 0 && count != 0 || count > MAX_BYTES {
                    return None;
                }
                for _ in 0..count {
                    self.skip(child, depth + 1)?;
                }
            }
            10 => {
                while *self.bytes.get(self.at)? != 0 {
                    self.field(depth + 1)?;
                }
                self.at += 1;
            }
            _ => return None,
        }
        Some(())
    }
    fn fields(&mut self) -> Option<Vec<Field<'a>>> {
        let mut fields = Vec::new();
        while *self.bytes.get(self.at)? != 0 {
            if fields.len() >= 65_536 {
                return None;
            }
            fields.push(self.field(1)?);
        }
        self.at += 1;
        Some(fields)
    }
}
fn last<'a>(fields: &[Field<'a>], name: &[u8]) -> Option<Field<'a>> {
    fields
        .iter()
        .rev()
        .find(|field| field.name == name)
        .copied()
}
fn triplet(bytes: &[u8], field: Field<'_>) -> Option<[i32; 3]> {
    if field.kind != 9 {
        return None;
    }
    let mut reader = Reader {
        bytes,
        at: field.payload,
    };
    if reader.byte()? != 3 || reader.count()? < 3 {
        return None;
    }
    Some([reader.int()?, reader.int()?, reader.int()?])
}
#[derive(Clone, Copy)]
struct Block {
    xyz: [i32; 3],
    state: u32,
    ordinal: usize,
}

fn coordinate_hash(xyz: [i32; 3]) -> u32 {
    let mut hash = (xyz[0] as u32).wrapping_mul(0x9e3779b9)
        ^ (xyz[1] as u32).wrapping_mul(0x85ebca6b)
        ^ (xyz[2] as u32).wrapping_mul(0xc2b2ae35);
    hash ^= hash >> 16;
    hash = hash.wrapping_mul(0x7feb352d);
    hash ^ (hash >> 15)
}

/// Extract one complete root; malformed or unsupported data requests Java fallback.
/// Duplicate positions use the last listed state, while block entities retain list order.
pub fn extract(bytes: &[u8]) -> Option<Vec<u8>> {
    if bytes.is_empty() || bytes.len() > MAX_BYTES {
        return None;
    }
    let mut reader = Reader { bytes, at: 0 };
    if reader.byte()? != 10 {
        return None;
    }
    let name_length = reader.short()?;
    reader.take(name_length)?;
    let root_prefix = reader.at;
    let fields = reader.fields()?;
    let size = triplet(bytes, last(&fields, b"size")?)?;
    // Java coordinates are X/Z/Y relative to Minecraft's X/Y/Z.
    let dims = [size[0], size[2], size[1]];
    let palette = last(&fields, b"palette")?;
    if palette.kind != 9 {
        return None;
    }
    let mut p = Reader {
        bytes,
        at: palette.payload,
    };
    if p.byte()? != 10 {
        return None;
    }
    let palette_count = p.count()?;
    if palette_count == 0 || palette_count > 65_535 {
        return None;
    }
    let blocks_field = last(&fields, b"blocks")?;
    if blocks_field.kind != 9 {
        return None;
    }
    let mut b = Reader {
        bytes,
        at: blocks_field.payload,
    };
    if b.byte()? != 10 {
        return None;
    }
    let count = b.count()?;
    if count > MAX_BLOCKS {
        return None;
    }
    let mut blocks = Vec::with_capacity(count);
    let mut retained = Vec::new();
    for ordinal in 0..count {
        let start = b.at;
        let block_fields = b.fields()?;
        let pos = triplet(bytes, last(&block_fields, b"pos")?)?;
        let state_field = last(&block_fields, b"state")?;
        if state_field.kind != 3 {
            return None;
        }
        let state = Reader {
            bytes,
            at: state_field.payload,
        }
        .count()?;
        if state >= palette_count {
            return None;
        }
        blocks.push(Block {
            xyz: [pos[0], pos[2], pos[1]],
            state: state as u32,
            ordinal,
        });
        if let Some(nbt) = last(&block_fields, b"nbt") {
            if nbt.kind != 10 {
                return None;
            }
            retained.push((start, b.at));
        }
    }
    // Ordering the ordinal after coordinates makes last-wins deduplication explicit.
    blocks.sort_unstable_by_key(|block| (block.xyz, block.ordinal));
    let mut unique: Vec<Block> = Vec::with_capacity(blocks.len());
    for block in blocks {
        if unique
            .last()
            .is_some_and(|previous| previous.xyz == block.xyz)
        {
            *unique.last_mut()? = block;
        } else {
            unique.push(block);
        }
    }
    let volume = dims
        .iter()
        .try_fold(1usize, |n, &d| n.checked_mul(usize::try_from(d).ok()?));
    let in_bounds = unique
        .iter()
        .all(|block| (0..3).all(|axis| block.xyz[axis] >= 0 && block.xyz[axis] < dims[axis]));
    let stride = if palette_count <= 255 { 1 } else { 2 };
    let sparse_slots = if unique.is_empty() {
        0
    } else {
        (unique.len() * 2).next_power_of_two()
    };
    let sparse_bytes = unique.len() * 16 + sparse_slots * 4;
    let dense =
        volume.is_some_and(|n| n > 0 && n <= MAX_BLOCKS && n * stride <= sparse_bytes) && in_bounds;
    let slots = if dense { 0 } else { sparse_slots };
    let storage_bytes = if dense {
        volume? * stride
    } else {
        sparse_bytes
    };
    let mut output = vec![0; HEADER + storage_bytes];
    if dense {
        for block in &unique {
            let index = (block.xyz[0] as usize
                + block.xyz[1] as usize * dims[0] as usize
                + block.xyz[2] as usize * dims[0] as usize * dims[1] as usize)
                * stride;
            let value = (block.state + 1) as u16;
            output[HEADER + index] = value as u8;
            if stride == 2 {
                output[HEADER + index + 1] = (value >> 8) as u8;
            }
        }
    } else {
        for (i, block) in unique.iter().enumerate() {
            let offset = HEADER + i * 16;
            for axis in 0..3 {
                output[offset + axis * 4..offset + axis * 4 + 4]
                    .copy_from_slice(&block.xyz[axis].to_le_bytes());
            }
            output[offset + 12..offset + 16].copy_from_slice(&block.state.to_le_bytes());
        }
    }
    if slots != 0 {
        let start = HEADER + unique.len() * 16;
        for (index, block) in unique.iter().enumerate() {
            let mut slot = coordinate_hash(block.xyz) as usize & (slots - 1);
            loop {
                let at = start + slot * 4;
                if output[at..at + 4] == [0; 4] {
                    output[at..at + 4].copy_from_slice(&((index + 1) as u32).to_le_bytes());
                    break;
                }
                slot = (slot + 1) & (slots - 1);
            }
        }
    }
    let nbt_offset = output.len();
    output.extend_from_slice(&bytes[..root_prefix]);
    for field in &fields {
        if field.name != b"blocks" {
            output.extend_from_slice(&bytes[field.start..field.end]);
        }
    }
    // Java only constructs block tags needed for block entities, preserving their order.
    output.extend_from_slice(&[9, 0, 6, b'b', b'l', b'o', b'c', b'k', b's', 10]);
    output.extend_from_slice(&(retained.len() as i32).to_be_bytes());
    for (start, end) in retained {
        output.extend_from_slice(&bytes[start..end]);
    }
    output.push(0);
    if output.len() > MAX_BYTES + HEADER {
        return None;
    }
    let words = [
        MAGIC,
        1,
        if dense { 1 } else { 2 },
        storage_bytes as u32,
        unique.len() as u32,
        dims[0] as u32,
        dims[1] as u32,
        dims[2] as u32,
        palette_count as u32,
        nbt_offset as u32,
        (output.len() - nbt_offset) as u32,
        output.len() as u32,
        slots as u32,
        if dense { stride as u32 } else { 0 },
    ];
    for (i, word) in words.iter().enumerate() {
        output[i * 4..i * 4 + 4].copy_from_slice(&word.to_le_bytes());
    }
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn named(out: &mut Vec<u8>, kind: u8, name: &[u8]) {
        out.push(kind);
        out.extend_from_slice(&(name.len() as u16).to_be_bytes());
        out.extend_from_slice(name);
    }
    fn ints(out: &mut Vec<u8>, name: &[u8], values: &[i32]) {
        named(out, 9, name);
        out.push(3);
        out.extend_from_slice(&(values.len() as i32).to_be_bytes());
        for value in values {
            out.extend_from_slice(&value.to_be_bytes());
        }
    }
    fn fixture(dimensions: [i32; 3], records: &[([i32; 3], i32, bool)]) -> Vec<u8> {
        let mut bytes = vec![10, 0, 0];
        ints(&mut bytes, b"size", &dimensions);
        named(&mut bytes, 9, b"palette");
        bytes.push(10);
        bytes.extend_from_slice(&2i32.to_be_bytes());
        bytes.extend_from_slice(&[0, 0]);
        named(&mut bytes, 9, b"blocks");
        bytes.push(10);
        bytes.extend_from_slice(&(records.len() as i32).to_be_bytes());
        for (pos, state, entity) in records {
            ints(&mut bytes, b"pos", pos);
            named(&mut bytes, 3, b"state");
            bytes.extend_from_slice(&state.to_be_bytes());
            if *entity {
                named(&mut bytes, 10, b"nbt");
                named(&mut bytes, 8, b"id");
                bytes.extend_from_slice(&[0, 5, b'c', b'h', b'e', b's', b't', 0]);
            }
            bytes.push(0);
        }
        named(&mut bytes, 8, b"unknown");
        bytes.extend_from_slice(&[0, 3, 0xe2, 0x98, 0x83]);
        bytes.push(0);
        bytes
    }
    fn word(output: &[u8], index: usize) -> u32 {
        u32::from_le_bytes(output[index * 4..index * 4 + 4].try_into().unwrap())
    }
    #[test]
    fn dense_indices_distinguish_air_from_absence_and_swap_axes() {
        let output = extract(&fixture(
            [2, 2, 3],
            &[([1, 1, 2], 0, false), ([0, 0, 0], 1, false)],
        ))
        .unwrap();
        assert_eq!(word(&output, 0), MAGIC);
        assert_eq!(word(&output, 2), 1);
        assert_eq!(
            [word(&output, 5), word(&output, 6), word(&output, 7)],
            [2, 3, 2]
        );
        assert_eq!(output[HEADER], 2);
        assert_eq!(output[HEADER + 11], 1);
        assert_eq!(output[HEADER + 1], 0);
    }
    #[test]
    fn sparse_preserves_negative_and_outside_positions_last_wins() {
        let output = extract(&fixture(
            [1000, 1000, 1000],
            &[
                ([7, -9, 5], 0, false),
                ([-1, 0, 0], 0, false),
                ([7, -9, 5], 1, false),
            ],
        ))
        .unwrap();
        assert_eq!(word(&output, 2), 2);
        assert_eq!(word(&output, 4), 2);
        assert_eq!(
            i32::from_le_bytes(output[HEADER..HEADER + 4].try_into().unwrap()),
            -1
        );
        assert_eq!(&output[HEADER + 16 + 12..HEADER + 32], &[1, 0, 0, 0]);
    }
    #[test]
    fn filtered_root_keeps_block_entities_and_unknown_metadata() {
        let input = fixture(
            [2, 2, 2],
            &[
                ([0, 0, 0], 0, true),
                ([0, 0, 0], 1, true),
                ([1, 1, 1], 1, false),
            ],
        );
        let output = extract(&input).unwrap();
        let start = word(&output, 9) as usize;
        let mut reader = Reader {
            bytes: &output[start..],
            at: 3,
        };
        let fields = reader.fields().unwrap();
        assert_eq!(last(&fields, b"unknown").unwrap().kind, 8);
        let block = last(&fields, b"blocks").unwrap();
        let mut blocks = Reader {
            bytes: &output[start..],
            at: block.payload,
        };
        assert_eq!(blocks.byte(), Some(10));
        assert_eq!(blocks.count(), Some(2));
        assert_eq!(word(&output, 4), 2);
    }
    #[test]
    fn all_truncations_fall_back_and_trailing_bytes_are_ignored() {
        let input = fixture([2, 2, 2], &[([0, 0, 0], 0, false)]);
        for end in 0..input.len() {
            assert!(
                extract(&input[..end]).is_none(),
                "accepted truncation {end}"
            );
        }
        let expected = extract(&input).unwrap();
        let mut trailing = input;
        trailing.extend_from_slice(&[99, 255]);
        assert_eq!(extract(&trailing), Some(expected));
    }
    #[test]
    fn invalid_states_and_empty_or_extreme_dimensions_are_bounded() {
        assert!(extract(&fixture([1, 1, 1], &[([0, 0, 0], -1, false)])).is_none());
        assert!(extract(&fixture([1, 1, 1], &[([0, 0, 0], 2, false)])).is_none());
        for dimensions in [[0, 0, 0], [-1, 2, 3], [i32::MAX; 3]] {
            let output = extract(&fixture(dimensions, &[])).unwrap();
            assert_eq!(word(&output, 2), 2);
            assert_eq!(word(&output, 3), 0);
        }
    }
}
