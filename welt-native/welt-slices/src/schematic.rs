//! Array borrowing for one complete schematic, with no per-block JNI.
use super::*;
pub(super) struct Lease {
    env: *mut JNIEnv,
    array: jobject,
    pub(super) pointer: *mut c_void,
    integers: bool,
    commit: bool,
}
impl Drop for Lease {
    fn drop(&mut self) {
        unsafe {
            type Release = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut c_void, jint);
            let release: Release = std::mem::transmute(function(
                self.env,
                if self.integers {
                    RELEASE_INT_ARRAY_ELEMENTS
                } else {
                    RELEASE_BYTE_ARRAY_ELEMENTS
                },
            ));
            release(
                self.env,
                self.array,
                self.pointer,
                if self.commit { 0 } else { JNI_ABORT },
            );
        }
    }
}
pub(super) unsafe fn borrow(env: *mut JNIEnv, array: jobject, integers: bool) -> Option<Lease> {
    unsafe {
        type Get = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut c_void;
        let get: Get = std::mem::transmute(function(
            env,
            if integers {
                GET_INT_ARRAY_ELEMENTS
            } else {
                GET_BYTE_ARRAY_ELEMENTS
            },
        ));
        let pointer = get(env, array, std::ptr::null_mut());
        if pointer.is_null() {
            None
        } else {
            Some(Lease {
                env,
                array,
                pointer,
                integers,
                commit: false,
            })
        }
    }
}
/// # Safety
/// Java arrays stay alive for this synchronous call; source and writable output arrays must not alias.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeDecodeSchematic(
    env: *mut JNIEnv,
    _class: jclass,
    bytes: jobject,
    indices: jobject,
    flags: jobject,
    output: jobject,
    summary: jobject,
    width: jint,
    length: jint,
    height: jint,
) -> jint {
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if bytes.is_null() == indices.is_null()
                || flags.is_null()
                || output.is_null()
                || summary.is_null()
                || width <= 0
                || length <= 0
                || height <= 0
            {
                return error;
            }
            let Some(count) = (width as usize)
                .checked_mul(length as usize)
                .and_then(|n| n.checked_mul(height as usize))
            else {
                return error;
            };
            if count > welt_nbt::schematic::MAX_CELLS {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            let palette = len(env, flags);
            if palette < 1
                || palette > 65536
                || len(env, summary) != 8
                || same(env, flags, output) != 0
                || !bytes.is_null() && same(env, bytes, output) != 0
                || !indices.is_null() && same(env, indices, summary) != 0
            {
                return error;
            }
            let stride = if palette <= 256 { 1 } else { 2 };
            if len(env, output) as usize != count * stride {
                return error;
            }
            let source_array = if bytes.is_null() { indices } else { bytes };
            let source_length = len(env, source_array);
            if source_length < 0
                || !bytes.is_null() && source_length as usize > 5 * welt_nbt::schematic::MAX_CELLS
                || !indices.is_null() && source_length as usize != count
            {
                return error;
            }
            let Some(source_lease) = borrow(env, source_array, bytes.is_null()) else {
                return error;
            };
            let Some(flags_lease) = borrow(env, flags, false) else {
                return error;
            };
            let Some(mut output_lease) = borrow(env, output, false) else {
                return error;
            };
            let source = if bytes.is_null() {
                welt_nbt::schematic::Source::Indices(slice::from_raw_parts(
                    source_lease.pointer.cast::<i32>(),
                    source_length as usize,
                ))
            } else {
                welt_nbt::schematic::Source::VarInts(slice::from_raw_parts(
                    source_lease.pointer.cast::<u8>(),
                    source_length as usize,
                ))
            };
            let flags_slice =
                slice::from_raw_parts(flags_lease.pointer.cast::<u8>(), palette as usize);
            let target =
                slice::from_raw_parts_mut(output_lease.pointer.cast::<u8>(), count * stride);
            let result = welt_nbt::schematic::pack(
                source,
                flags_slice,
                [width as usize, length as usize, height as usize],
                target,
            );
            match result {
                Ok(values) => {
                    output_lease.commit = true;
                    drop(output_lease);
                    drop(flags_lease);
                    drop(source_lease);
                    type Set =
                        unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const jint);
                    let set: Set = std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
                    set(env, summary, 0, 8, values.as_ptr());
                    WeltError::Ok as jint
                }
                Err(_) => error,
            }
        })
    }
}
