//! One borrowed legacy schematic transaction; the worker-owned direct buffer carries semantics/results.
use super::*;
#[no_mangle]
/// # Safety
/// Java source arrays and direct buffer remain alive for the synchronous call and do not alias.
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeAnalyzeLegacySchematic(
    env: *mut JNIEnv,
    _class: jclass,
    blocks: jobject,
    data: jobject,
    add: jobject,
    buffer: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if blocks.is_null() || data.is_null() || buffer.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type Address = unsafe extern "system" fn(*mut JNIEnv, jobject) -> *mut c_void;
            type Capacity = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jlong;
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let address: Address = std::mem::transmute(function(env, GET_DIRECT_BUFFER_ADDRESS));
            let capacity: Capacity = std::mem::transmute(function(env, GET_DIRECT_BUFFER_CAPACITY));
            let length: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let pointer = address(env, buffer);
            if pointer.is_null()
                || capacity(env, buffer) != welt_objects::legacy_schematic::BUFFER_BYTES as jlong
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(b) = schematic::borrow(env, blocks, false) else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(d) = schematic::borrow(env, data, false) else {
                return WeltError::IllegalArgument as jint;
            };
            let a = if add.is_null() {
                None
            } else {
                let Some(lease) = schematic::borrow(env, add, false) else {
                    return WeltError::IllegalArgument as jint;
                };
                Some(lease)
            };
            let blocks =
                slice::from_raw_parts(b.pointer.cast::<u8>(), length(env, blocks) as usize);
            let data = slice::from_raw_parts(d.pointer.cast::<u8>(), length(env, data) as usize);
            let add = a.as_ref().map(|lease| {
                slice::from_raw_parts(lease.pointer.cast::<u8>(), length(env, add) as usize)
            });
            let buffer = slice::from_raw_parts_mut(
                pointer.cast::<u8>(),
                welt_objects::legacy_schematic::BUFFER_BYTES,
            );
            if welt_objects::legacy_schematic::analyze(blocks, data, add, buffer) {
                WeltError::Ok as jint
            } else {
                WeltError::IllegalArgument as jint
            }
        })
    }
}
