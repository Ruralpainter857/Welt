//! Borrow the source once and return one owned compact BO2 transaction.
use super::*;

/// # Safety
/// The input byte array remains alive throughout the synchronous JNI call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeParseBo2(
    env: *mut JNIEnv,
    _class: jclass,
    input: jobject,
) -> jobject {
    unsafe { bo2_transaction(env, input, welt_objects::bo2::frame) }
}

/// # Safety
/// The source array remains alive throughout the synchronous call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeParseCompactBo2(
    env: *mut JNIEnv,
    _class: jclass,
    input: jobject,
) -> jobject {
    unsafe { bo2_transaction(env, input, compact_frame) }
}

unsafe fn bo2_transaction(
    env: *mut JNIEnv,
    input: jobject,
    parse: fn(&[u8]) -> Option<Vec<u8>>,
) -> jobject {
    let mut result = std::ptr::null_mut();
    unsafe {
        jni_catch(env, || {
            if input.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = len(env, input);
            if length < 1 || length as usize > welt_objects::bo2::MAX_BYTES {
                return WeltError::IllegalArgument as jint;
            }
            let Some(lease) = schematic::borrow(env, input, false) else {
                return WeltError::IllegalArgument as jint;
            };
            let bytes = slice::from_raw_parts(lease.pointer.cast::<u8>(), length as usize);
            let output = parse(bytes);
            drop(lease);
            let Some(output) = output else {
                return WeltError::IllegalArgument as jint;
            };
            type New = unsafe extern "system" fn(*mut JNIEnv, jint) -> jobject;
            type Set = unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const u8);
            let new: New = std::mem::transmute(function(env, 176));
            let set: Set = std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            result = new(env, output.len() as jint);
            if result.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            set(env, result, 0, output.len() as jint, output.as_ptr());
            WeltError::Ok as jint
        });
    }
    result
}

/// WBO2 v2 retains source records and metadata, then appends palette pairs and WSTR indices.
fn compact_frame(source: &[u8]) -> Option<Vec<u8>> {
    let original = welt_objects::bo2::frame(source)?;
    let word = |at: usize| i32::from_le_bytes(original[at..at + 4].try_into().unwrap());
    let count = word(8) as usize;
    let min = [word(16), word(20), word(24)];
    let dims = std::array::from_fn::<_, 3, _>(|axis| {
        word(28 + axis * 4).wrapping_sub(min[axis]).wrapping_add(1)
    });
    let mut pairs = Vec::new();
    let mut ids = std::collections::HashMap::new();
    let mut snapshot = vec![0_u8; 32 + count * 16];
    for (i, value) in [0x52505357, 2, count as i32, dims[0], dims[1], dims[2], 0, 0]
        .iter()
        .enumerate()
    {
        snapshot[i * 4..i * 4 + 4].copy_from_slice(&value.to_le_bytes());
    }
    for ordinal in 0..count {
        let at = 64 + ordinal * 32;
        let pair = (word(at + 12), word(at + 16));
        let next = pairs.len() as i32;
        let id = *ids.entry(pair).or_insert_with(|| {
            pairs.push(pair);
            next
        });
        let p = 32 + ordinal * 16;
        for axis in 0..3 {
            snapshot[p + axis * 4..p + axis * 4 + 4]
                .copy_from_slice(&word(at + axis * 4).wrapping_sub(min[axis]).to_le_bytes());
        }
        snapshot[p + 12..p + 16].copy_from_slice(&id.to_le_bytes());
    }
    snapshot[24..28].copy_from_slice(&(pairs.len() as i32).to_le_bytes());
    let indices = welt_nbt::structure::pack_records(&snapshot)?;
    let palette_offset = original.len() + 16;
    let index_offset = palette_offset + pairs.len() * 8;
    let mut output = Vec::with_capacity(index_offset + indices.len());
    output.extend_from_slice(&original[..64]);
    output.extend_from_slice(&[0; 16]);
    output.extend_from_slice(&original[64..]);
    for (id, data) in pairs {
        output.extend_from_slice(&id.to_le_bytes());
        output.extend_from_slice(&data.to_le_bytes());
    }
    output.extend_from_slice(&indices);
    let size = output.len();
    for (at, value) in [
        (4, 2),
        (40, 80),
        (44, word(44) + 16),
        (48, size as i32),
        (64, palette_offset as i32),
        (68, ids.len() as i32),
        (72, index_offset as i32),
        (76, indices.len() as i32),
    ] {
        output[at..at + 4].copy_from_slice(&value.to_le_bytes());
    }
    Some(output)
}

#[cfg(test)]
mod compact_tests {
    use super::compact_frame;
    fn word(data: &[u8], at: usize) -> i32 {
        i32::from_le_bytes(data[at..at + 4].try_into().unwrap())
    }
    #[test]
    fn combined_source_keeps_bounds_branches_and_last_material() {
        let source =
            b"[META]\nkey=first\nkey=last\n[DATA]\n-2,3,4:1.0#-5@6\n7,-8,9:35.4\n-2,3,4:20.0#3@7\n";
        let frame = compact_frame(source).unwrap();
        assert_eq!(word(&frame, 4), 2);
        assert_eq!(word(&frame, 8), 3);
        assert_eq!(word(&frame, 40), 80);
        assert_eq!(
            [word(&frame, 16), word(&frame, 20), word(&frame, 24)],
            [-2, -8, 4]
        );
        assert_eq!(
            [word(&frame, 28), word(&frame, 32), word(&frame, 36)],
            [7, 3, 9]
        );
        assert_eq!(word(&frame, 68), 3);
        assert_eq!(word(&frame, 80 + 2 * 32 + 24), 3);
        assert_eq!(word(&frame, 80 + 2 * 32 + 28), 7);
        let index = word(&frame, 72) as usize;
        assert_eq!(word(&frame, index + 16), 2); // Two unique positions survive.
        assert_eq!(word(&frame, index + 32), 3); // Palette retains all source material pairs.
        assert_eq!(word(&frame, 48) as usize, frame.len());
    }
    #[test]
    fn unsupported_sources_do_not_produce_partial_transactions() {
        for source in [
            b"[META]\n[DATA]\nbad:1\n".as_slice(),
            b"[META]\n[DATA]\n0,0,0:bad\n".as_slice(),
            b"".as_slice(),
        ] {
            assert!(compact_frame(source).is_none());
        }
    }
}
