use super::critical_array::CriticalArray;
use super::*;
use welt_core::image_range::{scan, scan_integer, Layout};

/// # Safety
/// Arrays have the declared primitive type and are exclusively borrowed for at
/// most 65,536 sample reads. Metadata is copied before critical arrays are held.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeScanImageRange(
    env: *mut JNIEnv,
    _class: jclass,
    source: jobject,
    parameters: jobject,
    stop_low: f64,
    stop_high: f64,
    result: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if source.is_null() || parameters.is_null() || result.is_null() {
                return -1;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Region = unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut jint);
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            let length: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            if length(env, parameters) != 11
                || length(env, result) != 2
                || same(env, source, result) != 0
            {
                return -1;
            }
            let mut p = [0; 11];
            let read: Region = std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            read(env, parameters, 0, 11, p.as_mut_ptr());
            if p.iter().any(|v| *v < 0) || p[0] > 5 || p[1] > 1 || p[9] > 1 || p[10] > 1 {
                return -1;
            }
            // Object is a Java convenience argument; verify its primitive array type before borrowing.
            type Find = unsafe extern "system" fn(*mut JNIEnv, *const i8) -> jclass;
            type Instance = unsafe extern "system" fn(*mut JNIEnv, jobject, jclass) -> u8;
            type Delete = unsafe extern "system" fn(*mut JNIEnv, jobject);
            let find: Find = std::mem::transmute(function(env, 6));
            let instance: Instance = std::mem::transmute(function(env, 32));
            let delete: Delete = std::mem::transmute(function(env, 23));
            let descriptor: &[u8] = match p[0] {
                0 => b"[B\0",
                1 | 2 => b"[S\0",
                3 => b"[I\0",
                4 => b"[F\0",
                5 => b"[D\0",
                _ => return -1,
            };
            let class = find(env, descriptor.as_ptr().cast());
            if class.is_null() {
                return -1;
            }
            let matches = instance(env, source, class) != 0;
            delete(env, class);
            if !matches {
                return -1;
            }
            let count = length(env, source);
            if count < 0 {
                return -1;
            }
            let layout = Layout {
                offset: p[2] as usize,
                pixel: p[3] as usize,
                row: p[4] as usize,
                x: p[5] as usize,
                y: p[6] as usize,
                width: p[7] as usize,
                height: p[8] as usize,
                rows: p[9] != 0,
                exact_stop: p[10] != 0,
            };
            if !layout.valid(count as usize) {
                return -1;
            }
            let Some(input) = CriticalArray::acquire(env, source, JNI_ABORT) else {
                return -1;
            };
            let Some(output) = CriticalArray::acquire(env, result, 0) else {
                return -1;
            };
            let output = slice::from_raw_parts_mut(output.pointer.cast::<f64>(), 2);
            let n = count as usize;
            let unsigned = p[1] != 0;
            let value = match p[0] {
                0 => scan_integer(
                    slice::from_raw_parts(input.pointer.cast::<u8>(), n),
                    layout,
                    |v| v as f64,
                    0.0,
                    255.0,
                    stop_low,
                    stop_high,
                    output,
                ),
                1 => scan_integer(
                    slice::from_raw_parts(input.pointer.cast::<u16>(), n),
                    layout,
                    |v| v as f64,
                    0.0,
                    65535.0,
                    stop_low,
                    stop_high,
                    output,
                ),
                2 if unsigned => scan_integer(
                    slice::from_raw_parts(input.pointer.cast::<u16>(), n),
                    layout,
                    |v| v as i16 as i32 as u32 as f64,
                    0.0,
                    u32::MAX as f64,
                    stop_low,
                    stop_high,
                    output,
                ),
                2 => scan_integer(
                    slice::from_raw_parts(input.pointer.cast::<i16>(), n),
                    layout,
                    |v| v as f64,
                    i16::MIN as f64,
                    i16::MAX as f64,
                    stop_low,
                    stop_high,
                    output,
                ),
                3 if unsigned => scan_integer(
                    slice::from_raw_parts(input.pointer.cast::<u32>(), n),
                    layout,
                    |v| v as f64,
                    0.0,
                    u32::MAX as f64,
                    stop_low,
                    stop_high,
                    output,
                ),
                3 => scan_integer(
                    slice::from_raw_parts(input.pointer.cast::<i32>(), n),
                    layout,
                    |v| v as f64,
                    i32::MIN as f64,
                    i32::MAX as f64,
                    stop_low,
                    stop_high,
                    output,
                ),
                4 => scan(
                    slice::from_raw_parts(input.pointer.cast::<f32>(), n),
                    layout,
                    |v| v as f64,
                    stop_low,
                    stop_high,
                    output,
                ),
                5 => scan(
                    slice::from_raw_parts(input.pointer.cast::<f64>(), n),
                    layout,
                    |v| v,
                    stop_low,
                    stop_high,
                    output,
                ),
                _ => unreachable!(),
            };
            match value {
                Ok(true) => 1,
                Ok(false) => 0,
                Err(_) => -1,
            }
        })
    }
}
