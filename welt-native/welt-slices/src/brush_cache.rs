//! Borrow float planes through a short arithmetic-only critical section.
use super::*;

struct CriticalPlane {
    env: *mut JNIEnv,
    array: jobject,
    pointer: *mut c_void,
    release: unsafe extern "system" fn(*mut JNIEnv, jobject, *mut c_void, jint),
    mode: jint,
}
impl Drop for CriticalPlane {
    fn drop(&mut self) {
        unsafe { (self.release)(self.env, self.array, self.pointer, self.mode) };
    }
}

/// # Safety
/// The source is immutable and output is exclusively owned for the call. No JVM
/// calls other than critical-array acquisition/release, allocation, locks or blocking
/// work occur while critical arrays are held.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeScaleBrushCache(
    env: *mut JNIEnv,
    _class: jclass,
    source: jobject,
    level: f32,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if source.is_null() || output.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            type Get = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut c_void;
            type Release = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut c_void, jint);
            let length: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            let count = length(env, source);
            if !(4096..=4_194_304).contains(&count)
                || length(env, output) != count
                || same(env, source, output) != 0
            {
                return WeltError::IllegalArgument as jint;
            }
            let get: Get = std::mem::transmute(function(env, 222));
            let release: Release = std::mem::transmute(function(env, 223));
            let input = get(env, source, std::ptr::null_mut());
            if input.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let input = CriticalPlane {
                env,
                array: source,
                pointer: input,
                release,
                mode: JNI_ABORT,
            };
            let result = get(env, output, std::ptr::null_mut());
            if result.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let result = CriticalPlane {
                env,
                array: output,
                pointer: result,
                release,
                mode: 0,
            };
            let source = slice::from_raw_parts(input.pointer.cast::<f32>(), count as usize);
            let target = slice::from_raw_parts_mut(result.pointer.cast::<f32>(), count as usize);
            let status = match welt_core::brush_cache::scale(source, level, target) {
                Ok(()) => WeltError::Ok as jint,
                Err(_) => WeltError::IllegalArgument as jint,
            };
            // Both guards release the critical arrays before jni_catch can touch the JVM.
            status
        })
    }
}

/// # Safety
/// Borrow two distinct primitive arrays for a bounded arithmetic-only operation.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeExpandBrushRaster(
    env: *mut JNIEnv,
    _class: jclass,
    source: jobject,
    stride: jint,
    radius: jint,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if source.is_null()
                || output.is_null()
                || !(0..=1023).contains(&radius)
                || stride < radius + 1
                || stride > 2048
            {
                return WeltError::IllegalArgument as jint;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Get = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut c_void;
            type Release = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut c_void, jint);
            let length: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let side = 2 * radius + 1;
            if length(env, source) != stride * stride || length(env, output) != side * side {
                return WeltError::IllegalArgument as jint;
            }
            let get: Get = std::mem::transmute(function(env, 222));
            let release: Release = std::mem::transmute(function(env, 223));
            let input = get(env, source, std::ptr::null_mut());
            if input.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let input = CriticalPlane {
                env,
                array: source,
                pointer: input,
                release,
                mode: JNI_ABORT,
            };
            let result = get(env, output, std::ptr::null_mut());
            if result.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let result = CriticalPlane {
                env,
                array: output,
                pointer: result,
                release,
                mode: 0,
            };
            let source =
                slice::from_raw_parts(input.pointer.cast::<f32>(), (stride * stride) as usize);
            let target =
                slice::from_raw_parts_mut(result.pointer.cast::<u8>(), (side * side) as usize);
            match welt_core::brush_cache::expand_raster(
                source,
                stride as usize,
                radius as usize,
                target,
            ) {
                Ok(()) => WeltError::Ok as jint,
                Err(_) => WeltError::IllegalArgument as jint,
            }
        })
    }
}
