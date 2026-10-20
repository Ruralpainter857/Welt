//! One synchronous transaction per snapshot; block access stays in Java.
use super::*;
/// # Safety
/// Java arrays remain alive for the call. The input is read-only and output is exclusively owned.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativePackMaterialVolume(
    env: *mut JNIEnv,
    _class: jclass,
    indices: jobject,
    palette: jint,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if indices.is_null() || output.is_null() || !(1..=65536).contains(&palette) {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let count = len(env, indices);
            let bytes = len(env, output);
            if count < 1
                || count > 16 * 1024 * 1024
                || bytes != count * if palette <= 256 { 1 } else { 2 }
            {
                return error;
            }
            let Some(source) = schematic::borrow(env, indices, true) else {
                return error;
            };
            let Some(mut destination) = schematic::borrow(env, output, false) else {
                return error;
            };
            let ok = welt_objects::material_volume::pack(
                slice::from_raw_parts(source.pointer.cast::<i32>(), count as usize),
                palette as usize,
                slice::from_raw_parts_mut(destination.pointer.cast::<u8>(), bytes as usize),
            );
            destination.commit = ok;
            if ok {
                WeltError::Ok as jint
            } else {
                error
            }
        })
    }
}

/// # Safety
/// Arrays stay alive; source and writable output byte arrays must not alias.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeRemapMaterialVolume(
    env: *mut JNIEnv,
    _class: jclass,
    packed: jobject,
    integers: jobject,
    width: jint,
    mapping: jobject,
    palette: jint,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if packed.is_null() == integers.is_null()
                || mapping.is_null()
                || output.is_null()
                || !(1..=65536).contains(&palette)
            {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            let source = if packed.is_null() { integers } else { packed };
            let n = len(env, source);
            let maps = len(env, mapping);
            let bytes = len(env, output);
            if n < 1
                || n > 32 * 1024 * 1024
                || maps < 1
                || maps > 65536
                || bytes < 1
                || bytes > 32 * 1024 * 1024
                || !packed.is_null() && same(env, packed, output) != 0
            {
                return error;
            }
            let Some(input) = schematic::borrow(env, source, packed.is_null()) else {
                return error;
            };
            let Some(map) = schematic::borrow(env, mapping, true) else {
                return error;
            };
            let Some(mut out) = schematic::borrow(env, output, false) else {
                return error;
            };
            let packed_source = if packed.is_null() {
                None
            } else {
                Some((
                    slice::from_raw_parts(input.pointer.cast::<u8>(), n as usize),
                    width as usize,
                ))
            };
            let int_source = if packed.is_null() {
                Some(slice::from_raw_parts(
                    input.pointer.cast::<i32>(),
                    n as usize,
                ))
            } else {
                None
            };
            let ok = welt_objects::material_volume::remap(
                packed_source,
                int_source,
                slice::from_raw_parts(map.pointer.cast::<i32>(), maps as usize),
                palette as usize,
                slice::from_raw_parts_mut(out.pointer.cast::<u8>(), bytes as usize),
            );
            out.commit = ok;
            if ok {
                WeltError::Ok as jint
            } else {
                error
            }
        })
    }
}

/// # Safety
/// Input arrays stay alive; output is exclusively owned and does not alias the input.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeTransformMaterialVolume(
    env: *mut JNIEnv,
    _class: jclass,
    source: jobject,
    parameters: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if source.is_null() || parameters.is_null() || output.is_null() {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            let bytes = len(env, source);
            let n = len(env, parameters);
            if bytes < 1
                || bytes > 32 * 1024 * 1024
                || len(env, output) != bytes
                || !(4..=68).contains(&n)
                || same(env, source, output) != 0
            {
                return error;
            }
            let Some(input) = schematic::borrow(env, source, false) else {
                return error;
            };
            let Some(p) = schematic::borrow(env, parameters, true) else {
                return error;
            };
            let Some(mut out) = schematic::borrow(env, output, false) else {
                return error;
            };
            let params = slice::from_raw_parts(p.pointer.cast::<i32>(), n as usize);
            if params[..4].iter().any(|&v| v <= 0) {
                return error;
            }
            let ok = welt_objects::material_volume::transform(
                slice::from_raw_parts(input.pointer.cast::<u8>(), bytes as usize),
                params[0] as usize,
                params[1] as usize,
                params[2] as usize,
                params[3] as usize,
                &params[4..],
                slice::from_raw_parts_mut(out.pointer.cast::<u8>(), bytes as usize),
            );
            out.commit = ok;
            if ok {
                WeltError::Ok as jint
            } else {
                error
            }
        })
    }
}

/// # Safety
/// Source arrays remain alive; the output is exclusive and must not alias packed input.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeRemapTransformedMaterialVolume(
    env: *mut JNIEnv,
    _class: jclass,
    packed: jobject,
    integers: jobject,
    width: jint,
    mapping: jobject,
    palette: jint,
    parameters: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if packed.is_null() == integers.is_null()
                || parameters.is_null()
                || mapping.is_null()
                || output.is_null()
                || !(1..=65536).contains(&palette)
            {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            let source = if packed.is_null() { integers } else { packed };
            let n = len(env, source);
            let maps = len(env, mapping);
            let bytes = len(env, output);
            if n < 1
                || n > 32 * 1024 * 1024
                || maps < 1
                || maps > 65536
                || bytes < 1
                || bytes > 32 * 1024 * 1024
                || !packed.is_null() && same(env, packed, output) != 0
            {
                return error;
            }
            let Some(input) = schematic::borrow(env, source, packed.is_null()) else {
                return error;
            };
            let Some(map) = schematic::borrow(env, mapping, true) else {
                return error;
            };
            let Some(mut out) = schematic::borrow(env, output, false) else {
                return error;
            };
            let parameter_count = len(env, parameters);
            if !(4..=68).contains(&parameter_count) {
                return error;
            }
            let Some(parameter_lease) = schematic::borrow(env, parameters, true) else {
                return error;
            };
            let params = slice::from_raw_parts(
                parameter_lease.pointer.cast::<i32>(),
                parameter_count as usize,
            );
            if params[..3].iter().any(|&n| n <= 0)
                || params[3] != if palette <= 256 { 1 } else { 2 }
            {
                return error;
            }
            let packed_source = if packed.is_null() {
                None
            } else {
                Some((
                    slice::from_raw_parts(input.pointer.cast::<u8>(), n as usize),
                    width as usize,
                ))
            };
            let int_source = if packed.is_null() {
                Some(slice::from_raw_parts(
                    input.pointer.cast::<i32>(),
                    n as usize,
                ))
            } else {
                None
            };
            let ok = welt_objects::material_volume::remap_transform(
                packed_source,
                int_source,
                slice::from_raw_parts(map.pointer.cast::<i32>(), maps as usize),
                palette as usize,
                [params[0] as usize, params[1] as usize, params[2] as usize],
                &params[4..],
                slice::from_raw_parts_mut(out.pointer.cast::<u8>(), bytes as usize),
            );
            out.commit = ok;
            if ok {
                WeltError::Ok as jint
            } else {
                error
            }
        })
    }
}

/// # Safety
/// Source arrays remain alive during this call; output cannot alias any source byte plane.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeTransformLegacyMaterialVolume(
    env: *mut JNIEnv,
    _class: jclass,
    blocks: jobject,
    data: jobject,
    add: jobject,
    mapping: jobject,
    palette: jint,
    parameters: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if blocks.is_null()
                || data.is_null()
                || mapping.is_null()
                || parameters.is_null()
                || output.is_null()
                || !(1..=65536).contains(&palette)
            {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type Same = unsafe extern "system" fn(*mut JNIEnv, jobject, jobject) -> u8;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let same: Same = std::mem::transmute(function(env, 24));
            let n = len(env, blocks);
            let d = len(env, data);
            let a = if add.is_null() { 0 } else { len(env, add) };
            let p = len(env, parameters);
            let bytes = len(env, output);
            if !(1..=32 * 1024 * 1024).contains(&n)
                || !(1..=32 * 1024 * 1024).contains(&d)
                || !(0..=32 * 1024 * 1024).contains(&a)
                || !(4..=68).contains(&p)
                || !(1..=32 * 1024 * 1024).contains(&bytes)
                || len(env, mapping) != 65536
                || same(env, blocks, output) != 0
                || same(env, data, output) != 0
                || !add.is_null() && same(env, add, output) != 0
            {
                return error;
            }
            let Some(b) = schematic::borrow(env, blocks, false) else {
                return error;
            };
            let Some(datum) = schematic::borrow(env, data, false) else {
                return error;
            };
            let extra = if add.is_null() {
                None
            } else {
                schematic::borrow(env, add, false)
            };
            if !add.is_null() && extra.is_none() {
                return error;
            }
            let Some(map) = schematic::borrow(env, mapping, true) else {
                return error;
            };
            let Some(params) = schematic::borrow(env, parameters, true) else {
                return error;
            };
            let Some(mut out) = schematic::borrow(env, output, false) else {
                return error;
            };
            let params = slice::from_raw_parts(params.pointer.cast::<i32>(), p as usize);
            if params[..3].iter().any(|&value| value <= 0)
                || params[3] != if palette <= 256 { 1 } else { 2 }
            {
                return error;
            }
            let ok = welt_objects::material_volume::legacy_transform(
                slice::from_raw_parts(b.pointer.cast::<u8>(), n as usize),
                slice::from_raw_parts(datum.pointer.cast::<u8>(), d as usize),
                extra
                    .as_ref()
                    .map(|value| slice::from_raw_parts(value.pointer.cast::<u8>(), a as usize)),
                slice::from_raw_parts(map.pointer.cast::<i32>(), 65536),
                palette as usize,
                [params[0] as usize, params[1] as usize, params[2] as usize],
                &params[4..],
                slice::from_raw_parts_mut(out.pointer.cast::<u8>(), bytes as usize),
            );
            out.commit = ok;
            if ok {
                WeltError::Ok as jint
            } else {
                error
            }
        })
    }
}

thread_local! {
    static LEGACY_CLASSIFICATION: std::cell::RefCell<(Box<[u64;1024]>,Vec<i32>)> =
        std::cell::RefCell::new((Box::new([0;1024]),Vec::new()));
}
/// # Safety
/// Java source arrays remain alive and read-only throughout this transaction.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeCollectLegacyMaterialStates(
    env: *mut JNIEnv,
    _class: jclass,
    blocks: jobject,
    data: jobject,
    add: jobject,
    dimensions: jobject,
) -> jobject {
    let mut result = std::ptr::null_mut();
    unsafe {
        jni_catch(env, || {
            let error = WeltError::IllegalArgument as jint;
            if blocks.is_null() || data.is_null() || dimensions.is_null() {
                return error;
            }
            type Length = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let len: Length = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let n = len(env, blocks);
            let d = len(env, data);
            let a = if add.is_null() { 0 } else { len(env, add) };
            if !(1..=32 * 1024 * 1024).contains(&n)
                || !(1..=32 * 1024 * 1024).contains(&d)
                || !(0..=32 * 1024 * 1024).contains(&a)
                || len(env, dimensions) != 3
            {
                return error;
            }
            let Some(b) = schematic::borrow(env, blocks, false) else {
                return error;
            };
            let Some(datum) = schematic::borrow(env, data, false) else {
                return error;
            };
            let extra = if add.is_null() {
                None
            } else {
                schematic::borrow(env, add, false)
            };
            if !add.is_null() && extra.is_none() {
                return error;
            }
            let Some(size) = schematic::borrow(env, dimensions, true) else {
                return error;
            };
            let dims = slice::from_raw_parts(size.pointer.cast::<i32>(), 3);
            if dims.iter().any(|&v| v <= 0) {
                return error;
            }
            LEGACY_CLASSIFICATION.with(|worker| {
                let mut scratch = worker.borrow_mut();
                let (seen, states) = &mut *scratch;
                if !welt_objects::material_volume::legacy_states(
                    slice::from_raw_parts(b.pointer.cast::<u8>(), n as usize),
                    slice::from_raw_parts(datum.pointer.cast::<u8>(), d as usize),
                    extra
                        .as_ref()
                        .map(|v| slice::from_raw_parts(v.pointer.cast::<u8>(), a as usize)),
                    [dims[0] as usize, dims[1] as usize, dims[2] as usize],
                    seen,
                    states,
                ) {
                    return error;
                }
                type New = unsafe extern "system" fn(*mut JNIEnv, jint) -> jobject;
                type Set = unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
                let new: New = std::mem::transmute(function(env, 179));
                let set: Set = std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
                result = new(env, states.len() as jint);
                if result.is_null() {
                    return error;
                }
                set(env, result, 0, states.len() as jint, states.as_ptr());
                WeltError::Ok as jint
            })
        });
    }
    result
}
