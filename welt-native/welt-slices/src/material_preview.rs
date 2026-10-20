//! One JNI transaction for a complete custom-material editor cross-section.
use super::*;
thread_local! { static MATERIAL_PREVIEW: RefCell<welt_core::material_preview::Workspace> = RefCell::new(Default::default()); }
/// # Safety
/// The direct frame is exclusively borrowed until this call returns.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillMaterialPreview(
    env: *mut JNIEnv,
    _class: jclass,
    buffer: jobject,
    length: jint,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if buffer.is_null()
                || length < 128
                || length as usize > welt_core::material_preview::MAX_BYTES
            {
                return WeltError::IllegalArgument as jint;
            }
            type Address = unsafe extern "system" fn(*mut JNIEnv, jobject) -> *mut c_void;
            type Capacity = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jlong;
            let address: Address = std::mem::transmute(function(env, GET_DIRECT_BUFFER_ADDRESS));
            let capacity: Capacity = std::mem::transmute(function(env, GET_DIRECT_BUFFER_CAPACITY));
            let pointer = address(env, buffer);
            if pointer.is_null() || capacity(env, buffer) < length as jlong {
                return WeltError::IllegalArgument as jint;
            }
            let frame = slice::from_raw_parts_mut(pointer.cast::<u8>(), length as usize);
            MATERIAL_PREVIEW.with(|worker| {
                if welt_core::material_preview::fill(frame, &mut worker.borrow_mut()) {
                    WeltError::Ok as jint
                } else {
                    WeltError::IllegalArgument as jint
                }
            })
        })
    }
}
