//! Borrow the source once and return one owned compact BO3 transaction.
use super::*;

/// # Safety
/// The input byte array remains alive throughout the synchronous JNI call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeParseBo3(
    env: *mut JNIEnv,
    _class: jclass,
    input: jobject,
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
            if length < 1 || length as usize > welt_objects::bo3::MAX_BYTES {
                return WeltError::IllegalArgument as jint;
            }
            let Some(lease) = schematic::borrow(env, input, false) else {
                return WeltError::IllegalArgument as jint;
            };
            let bytes = slice::from_raw_parts(lease.pointer.cast::<u8>(), length as usize);
            let output = welt_objects::bo3::frame(bytes);
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
