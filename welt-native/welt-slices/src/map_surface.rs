//! Read-only JNI transport for complete projections over index arrays or original NBT longs.
use super::*;
use welt_core::map_surface::{Scratch, SectionSource};
thread_local! { static SURFACE_SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default()); }
struct ReadOnlyArray {
    env: *mut JNIEnv,
    array: jobject,
    pointer: *mut c_void,
    length: usize,
    bits: u32,
    packed: bool,
}
impl Drop for ReadOnlyArray {
    fn drop(&mut self) {
        unsafe {
            type Release = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut c_void, jint);
            let release: Release =
                std::mem::transmute(function(self.env, if self.packed { 196 } else { 195 }));
            release(self.env, self.array, self.pointer, 2); // JNI_ABORT: sources never change.
            type Delete = unsafe extern "system" fn(*mut JNIEnv, jobject);
            let delete: Delete = std::mem::transmute(function(self.env, 23));
            delete(self.env, self.array);
        }
    }
}
/// # Safety
/// All Java arrays and the direct frame remain owned by the JVM throughout the call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_minecraft_ChunkSurfaceAccess_nativeAnalyze(
    env: *mut JNIEnv,
    _class: jclass,
    arrays: jobject,
    frame: jobject,
    length: jint,
) -> jint {
    unsafe { transport(env, arrays, std::ptr::null_mut(), frame, length) }
}
/// # Safety
/// Packed and unpacked section arrays must stay alive until this synchronous call returns.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_minecraft_ChunkSurfaceAccess_nativeAnalyzeSections(
    env: *mut JNIEnv,
    _class: jclass,
    arrays: jobject,
    packed: jobject,
    frame: jobject,
    length: jint,
) -> jint {
    unsafe { transport(env, arrays, packed, frame, length) }
}
unsafe fn transport(
    env: *mut JNIEnv,
    arrays: jobject,
    packed: jobject,
    frame: jobject,
    length: jint,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if arrays.is_null()
                || frame.is_null()
                || length < 64
                || length as usize > welt_core::map_surface::MAX_BYTES
            {
                return WeltError::IllegalArgument as jint;
            }
            type Address = unsafe extern "system" fn(*mut JNIEnv, jobject) -> *mut c_void;
            type Capacity = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jlong;
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Element = unsafe extern "system" fn(*mut JNIEnv, jobject, jint) -> jobject;
            type Borrow = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut c_void;
            type Ensure = unsafe extern "system" fn(*mut JNIEnv, jint) -> jint;
            let address: Address = std::mem::transmute(function(env, GET_DIRECT_BUFFER_ADDRESS));
            let capacity: Capacity = std::mem::transmute(function(env, GET_DIRECT_BUFFER_CAPACITY));
            let array_length: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let element: Element = std::mem::transmute(function(env, GET_OBJECT_ARRAY_ELEMENT));
            let ensure: Ensure = std::mem::transmute(function(env, 26));
            let pointer = address(env, frame);
            if pointer.is_null() || capacity(env, frame) < length as jlong {
                return WeltError::IllegalArgument as jint;
            }
            let data = slice::from_raw_parts_mut(pointer.cast::<u8>(), length as usize);
            let count = u32::from_le_bytes(data[12..16].try_into().unwrap()) as usize;
            if !(1..=256).contains(&count)
                || 64 + count * 16 > data.len()
                || array_length(env, arrays) < count as jint
                || (!packed.is_null() && array_length(env, packed) < count as jint)
                || ensure(env, count as jint + 16) != 0
            {
                return WeltError::IllegalArgument as jint;
            }
            let mut owned = Vec::with_capacity(count);
            for s in 0..count {
                let ints = element(env, arrays, s as jint);
                let longs = if packed.is_null() {
                    std::ptr::null_mut()
                } else {
                    element(env, packed, s as jint)
                };
                if !ints.is_null() && !longs.is_null() {
                    return WeltError::IllegalArgument as jint;
                }
                let is_packed = !longs.is_null();
                let array = if is_packed { longs } else { ints };
                if array.is_null() {
                    owned.push(None);
                    continue;
                }
                let palette =
                    u32::from_le_bytes(data[68 + s * 16..72 + s * 16].try_into().unwrap()) as usize;
                if !(1..=65536).contains(&palette) {
                    return WeltError::IllegalArgument as jint;
                }
                let bits = (usize::BITS - (palette - 1).leading_zeros()).max(4);
                let expected = if is_packed {
                    4096usize.div_ceil(64 / bits as usize)
                } else {
                    4096
                };
                if array_length(env, array) != expected as jint {
                    return WeltError::IllegalArgument as jint;
                }
                let borrow: Borrow =
                    std::mem::transmute(function(env, if is_packed { 188 } else { 187 }));
                let pointer = borrow(env, array, std::ptr::null_mut());
                if pointer.is_null() {
                    return WeltError::Internal as jint;
                }
                owned.push(Some(ReadOnlyArray {
                    env,
                    array,
                    pointer,
                    length: expected,
                    bits,
                    packed: is_packed,
                }));
            }
            let sections: Vec<_> = owned
                .iter()
                .map(|a| match a {
                    None => SectionSource::Uniform,
                    Some(a) if a.packed => SectionSource::Packed {
                        words: slice::from_raw_parts(a.pointer.cast::<i64>(), a.length),
                        bits: a.bits,
                    },
                    Some(a) => SectionSource::Indices(slice::from_raw_parts(
                        a.pointer.cast::<i32>(),
                        a.length,
                    )),
                })
                .collect();
            match SURFACE_SCRATCH.with(|scratch| {
                welt_core::map_surface::analyze_sources_with_scratch(
                    data,
                    &sections,
                    &mut scratch.borrow_mut(),
                )
            }) {
                Ok(()) => WeltError::Ok as jint,
                Err(e) => e as jint,
            }
        })
    }
}
