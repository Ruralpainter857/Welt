//! Bounded, lossless NBT indexing. Payloads stay in the original big-endian byte stream.
//! The flat preorder directory is also usable by future native chunk consumers.
//!
//! JNI directory v1 starts with [magic, version, node count, consumed input bytes].
//! Each eight-word node stores [tag kind, name offset, name byte length, payload offset,
//! array element/string byte count, direct child count, exclusive subtree end, list kind].
//! Offsets address the unmodified input. Compound terminators are omitted; an End root
//! is represented explicitly. Duplicate compound names remain ordered for Java's last-wins map.
//! Raw UTF-8 and floating-point payloads are never normalised by the indexer.

pub const MAX_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_NODES: usize = 8192;
pub const HEADER_WORDS: usize = 4;
pub const NODE_WORDS: usize = 8;
pub const OUTPUT_WORDS: usize = HEADER_WORDS + MAX_NODES * NODE_WORDS;
pub const MAGIC: i32 = 0x574e4254;

struct Parser<'a> {
    bytes: &'a [u8],
    position: usize,
    nodes: &'a mut Vec<i32>,
}

impl Parser<'_> {
    fn take(&mut self, length: usize) -> Option<usize> {
        let start = self.position;
        self.position = start.checked_add(length)?;
        (self.position <= self.bytes.len()).then_some(start)
    }

    fn byte(&mut self) -> Option<u8> {
        let offset = self.take(1)?;
        Some(self.bytes[offset])
    }

    fn short(&mut self) -> Option<u16> {
        let offset = self.take(2)?;
        Some(u16::from_be_bytes(
            self.bytes[offset..offset + 2].try_into().ok()?,
        ))
    }

    fn count(&mut self) -> Option<usize> {
        let offset = self.take(4)?;
        let value = i32::from_be_bytes(self.bytes[offset..offset + 4].try_into().ok()?);
        usize::try_from(value).ok()
    }

    fn named(&mut self, depth: usize) -> Option<usize> {
        let kind = self.byte()?;
        let (name, length) = if kind == 0 {
            (0, 0)
        } else {
            let length = usize::from(self.short()?);
            (self.take(length)?, length)
        };
        self.payload(kind, name, length, depth)
    }

    fn payload(
        &mut self,
        kind: u8,
        name: usize,
        name_length: usize,
        depth: usize,
    ) -> Option<usize> {
        if depth > 128 || self.nodes.len() / NODE_WORDS >= MAX_NODES || kind > 12 {
            return None;
        }
        let node = self.nodes.len();
        self.nodes.extend_from_slice(&[
            i32::from(kind),
            name as i32,
            name_length as i32,
            self.position as i32,
            0,
            0,
            0,
            0,
        ]);
        match kind {
            0 => {}
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
                self.nodes[node + 3] = self.take(count.checked_mul(width)?)? as i32;
                self.nodes[node + 4] = count as i32;
            }
            8 => {
                // JNBT uses a signed short for string payloads, unlike unsigned tag-name lengths.
                let count = usize::from(self.short()?);
                if count > i16::MAX as usize {
                    return None;
                }
                self.nodes[node + 3] = self.take(count)? as i32;
                self.nodes[node + 4] = count as i32;
            }
            9 => {
                let child_kind = self.byte()?;
                let count = self.count()?;
                if child_kind > 12 || (child_kind == 0 && count != 0) || count > MAX_NODES {
                    return None;
                }
                self.nodes[node + 5] = count as i32;
                self.nodes[node + 7] = i32::from(child_kind);
                for _ in 0..count {
                    self.payload(child_kind, 0, 0, depth + 1)?;
                }
            }
            10 => {
                let mut count = 0;
                loop {
                    if *self.bytes.get(self.position)? == 0 {
                        self.position += 1;
                        break;
                    }
                    self.named(depth + 1)?;
                    count += 1;
                }
                self.nodes[node + 5] = count;
            }
            _ => return None,
        }
        self.nodes[node + 6] = (self.nodes.len() / NODE_WORDS) as i32;
        Some(node / NODE_WORDS)
    }
}

/// Index one root tag, allowing trailing bytes exactly as JNBT.readTag does.
/// Unsupported or malformed streams clear the directory and request Java fallback.
pub fn index(bytes: &[u8], nodes: &mut Vec<i32>) -> Option<usize> {
    nodes.clear();
    if bytes.is_empty() || bytes.len() > MAX_BYTES {
        return None;
    }
    let mut parser = Parser {
        bytes,
        position: 0,
        nodes,
    };
    if parser.named(0).is_none() {
        parser.nodes.clear();
        return None;
    }
    Some(parser.position)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_payloads_and_subtree_links() {
        let mut input = vec![10, 0, 0];
        for (kind, payload) in [
            (1, vec![255]),
            (2, vec![128, 0]),
            (3, vec![0, 0, 0, 7]),
            (4, vec![255; 8]),
            (5, vec![127, 192, 0, 3]),
            (6, vec![255; 8]),
            (7, vec![0, 0, 0, 2, 42, 128]),
            (8, vec![0, 2, 0xc3, 0xa9]),
            (9, vec![10, 0, 0, 0, 2, 0, 0]),
            (10, vec![0]),
            (11, vec![0, 0, 0, 1, 0, 0, 0, 42]),
            (12, vec![0, 0, 0, 1, 255, 255, 255, 255, 255, 255, 255, 255]),
        ] {
            input.extend([kind, 0, 1, kind]);
            input.extend(payload);
        }
        input.push(0);
        input.extend([255, 17]);
        let mut nodes = Vec::new();
        assert_eq!(index(&input, &mut nodes), Some(input.len() - 2));
        assert_eq!(nodes.len() / NODE_WORDS, 15);
        assert_eq!(nodes[5], 12);
        assert_eq!(nodes[6], 15);
        let list = 9 * NODE_WORDS;
        assert_eq!(nodes[list], 9);
        assert_eq!(nodes[list + 5], 2);
        assert_eq!(nodes[list + 6], 12);
        assert_eq!(nodes[list + 7], 10);
    }

    #[test]
    fn every_truncation_rejects_without_partial_directory() {
        let bytes = [
            10, 0, 0, 12, 0, 1, b'a', 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0,
        ];
        let mut nodes = Vec::new();
        for length in 0..bytes.len() {
            assert_eq!(index(&bytes[..length], &mut nodes), None);
            assert!(nodes.is_empty());
        }
        assert!(index(&bytes, &mut nodes).is_some());
    }

    #[test]
    fn unusual_java_lengths_and_invalid_lists_fall_back() {
        let mut nodes = Vec::new();
        for bytes in [
            vec![8, 0, 0, 128, 0],
            vec![9, 0, 0, 1, 255, 255, 255, 255],
            vec![9, 0, 0, 0, 0, 0, 0, 1],
            vec![13, 0, 0],
            vec![7, 0, 0, 255, 255, 255, 255],
        ] {
            assert_eq!(index(&bytes, &mut nodes), None);
            assert!(nodes.is_empty());
        }
        assert_eq!(index(&[9, 0, 0, 0, 0, 0, 0, 0], &mut nodes), Some(8));
        assert_eq!(index(&[0], &mut nodes), Some(1));
    }

    #[test]
    fn worker_reuse_and_limits() {
        let mut nodes = Vec::new();
        let mut input = vec![9, 0, 0, 1];
        input.extend((MAX_NODES as i32).to_be_bytes());
        input.resize(input.len() + MAX_NODES, 0);
        assert_eq!(index(&input, &mut nodes), None);
        assert!(nodes.is_empty());
        assert_eq!(index(&[1, 0, 0, 42], &mut nodes), Some(4));
        assert_eq!(nodes[0], 1);
        let oversized = vec![0; MAX_BYTES + 1];
        assert_eq!(index(&oversized, &mut nodes), None);
    }
}
