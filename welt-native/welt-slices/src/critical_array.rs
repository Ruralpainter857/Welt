//! Scoped primitive-array borrows for bounded, arithmetic-only kernels.
use super::*;
pub(super) struct CriticalArray {
    env: *mut JNIEnv,
    array: jobject,
    pub(super) pointer: *mut c_void,
    release: unsafe extern "system" fn(*mut JNIEnv, jobject, *mut c_void, jint),
    mode: jint,
}
impl CriticalArray {
    /// # Safety
    /// Only acquire/release critical arrays until all guards have been dropped.
    pub(super) unsafe fn acquire(env: *mut JNIEnv, array: jobject, mode: jint) -> Option<Self> {
        type Get = unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut c_void;
        let get: Get = unsafe { std::mem::transmute(function(env, 222)) };
        let release = unsafe { std::mem::transmute(function(env, 223)) };
        let pointer = unsafe { get(env, array, std::ptr::null_mut()) };
        if pointer.is_null() {
            None
        } else {
            Some(Self {
                env,
                array,
                pointer,
                release,
                mode,
            })
        }
    }
}
impl Drop for CriticalArray {
    fn drop(&mut self) {
        unsafe { (self.release)(self.env, self.array, self.pointer, self.mode) }
    }
}
