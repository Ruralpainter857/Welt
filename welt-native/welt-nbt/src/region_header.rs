//! Bulk decoding for the two big-endian MCA region-header sectors.
//! The caller owns the complete 4/8 KiB buffer; no address is retained.

/// Converts complete offset/timestamp tables into little-endian words in place.
/// An invalid length leaves the entire input unchanged.
pub fn decode(data: &mut [u8]) -> bool {
    if !matches!(data.len(), 4096 | 8192) {
        return false;
    }
    for word in data.as_chunks_mut::<4>().0 {
        word.reverse();
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn both_complete_table_sizes_preserve_every_signed_word() {
        for count in [1024i32, 2048] {
            let words: Vec<i32> = (0..count)
                .map(|i| {
                    if i % 2 == 0 {
                        i.wrapping_mul(982451653i32.wrapping_neg())
                    } else {
                        i.wrapping_mul(982451653)
                    }
                })
                .collect();
            let mut data: Vec<u8> = words.iter().flat_map(|v| v.to_be_bytes()).collect();
            assert!(decode(&mut data));
            for (bytes, word) in data.chunks_exact(4).zip(words) {
                assert_eq!(i32::from_le_bytes(bytes.try_into().unwrap()), word);
            }
        }
    }
    #[test]
    fn malformed_sizes_are_atomic() {
        for size in [0, 1, 4095, 4097, 8191, 8193] {
            let mut data = vec![71; size];
            let before = data.clone();
            assert!(!decode(&mut data));
            assert_eq!(data, before);
        }
    }
}
