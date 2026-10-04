//! JNI transport for one complete chunk surface projection over borrowed section palettes.
use super::*;
thread_local! { static SURFACE_SCRATCH: RefCell<welt_core::map_surface::Scratch> = RefCell::new(welt_core::map_surface::Scratch::default()); }
struct ReadOnlyIndices {
    env: *mut JNIEnv,
    array: jobject,
    pointer: *mut i32,
}
impl Drop for ReadOnlyIndices {
    fn drop(&mut self) {
        unsafe {
            type Release = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut i32, jint);
            let release: Release =
                std::mem::transmute(function(self.env, RELEASE_INT_ARRAY_ELEMENTS));
            release(self.env, self.array, self.pointer, 2); // JNI_ABORT: projection never changes block data.
            type Delete = unsafe extern "system" fn(*mut JNIEnv, jobject);
            let delete: Delete = std::mem::transmute(function(self.env, 23));
            delete(self.env, self.array);
        }
    }
}
/// # Safety
/// The JVM owns all arrays and the direct frame for the duration of this synchronous call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_minecraft_ChunkSurfaceAccess_nativeAnalyze(
    env: *mut JNIEnv,
    _class: jclass,
    arrays: jobject,
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
            type Borrow = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i32;
            type Ensure = unsafe extern "system" fn(*mut JNIEnv, jint) -> jint;
            let address: Address = std::mem::transmute(function(env, GET_DIRECT_BUFFER_ADDRESS));
            let capacity: Capacity = std::mem::transmute(function(env, GET_DIRECT_BUFFER_CAPACITY));
            let array_length: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let element: Element = std::mem::transmute(function(env, GET_OBJECT_ARRAY_ELEMENT));
            let borrow: Borrow = std::mem::transmute(function(env, GET_INT_ARRAY_ELEMENTS));
            let ensure: Ensure = std::mem::transmute(function(env, 26));
            let pointer = address(env, frame);
            if pointer.is_null() || capacity(env, frame) < length as jlong {
                return WeltError::IllegalArgument as jint;
            }
            let data = slice::from_raw_parts_mut(pointer.cast::<u8>(), length as usize);
            let count = u32::from_le_bytes(data[12..16].try_into().unwrap()) as usize;
            if !(1..=256).contains(&count)
                || array_length(env, arrays) < count as jint
                || ensure(env, count as jint + 16) != 0
            {
                return WeltError::IllegalArgument as jint;
            }
            let mut owned = Vec::with_capacity(count);
            for s in 0..count {
                let array = element(env, arrays, s as jint);
                if array.is_null() {
                    owned.push(None);
                    continue;
                }
                if array_length(env, array) != 4096 {
                    return WeltError::IllegalArgument as jint;
                }
                let pointer = borrow(env, array, std::ptr::null_mut());
                if pointer.is_null() {
                    return WeltError::Internal as jint;
                }
                owned.push(Some(ReadOnlyIndices {
                    env,
                    array,
                    pointer,
                }));
            }
            let sections: Vec<_> = owned
                .iter()
                .map(|s| s.as_ref().map(|a| slice::from_raw_parts(a.pointer, 4096)))
                .collect();
            match SURFACE_SCRATCH.with(|scratch| {
                welt_core::map_surface::analyze_with_scratch(
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
