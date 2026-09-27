//! In-place application of batched Resources decisions to canonical chunk palettes.

use super::*;

struct PaletteSection {
    indexes_array: jobject,
    flags: Vec<i8>,
    output_indexes: Vec<i32>,
}

/// Computes Resources and applies its decisions to the live palette indexes in one JNI entry.
///
/// # Safety
/// All JNI values must be valid references supplied by the active JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillResourceMaterialsIntoPalette(
    env: *mut JNIEnv,
    class: jclass,
    min_z: jint,
    max_z: jint,
    tiny_x: jobject,
    tiny_y: jobject,
    dirt_x: jobject,
    dirt_y: jobject,
    column_min_z: jobject,
    column_max_z: jobject,
    resource_values: jobject,
    seeds: jobject,
    material_min_z: jobject,
    material_max_z: jobject,
    dirt_materials: jobject,
    chances: jobject,
    output: jobject,
    profile_nanos: jobject,
    section_min_y: jint,
    section_count: jint,
    section_indexes: jobject,
    palette_flags: jobject,
    output_palette_indexes: jobject,
    apply_nanos: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [
                tiny_x,
                tiny_y,
                dirt_x,
                dirt_y,
                column_min_z,
                column_max_z,
                resource_values,
                seeds,
                material_min_z,
                material_max_z,
                dirt_materials,
                chances,
                output,
                section_indexes,
                palette_flags,
                output_palette_indexes,
            ]
            .iter()
            .any(|array| array.is_null())
                || min_z > max_z
                || (section_min_y & 15) != 0
                || section_count <= 0
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(super::function(env, super::GET_ARRAY_LENGTH));
            let columns = get_array_length(env, tiny_x);
            let materials = get_array_length(env, seeds);
            let section_array_length = get_array_length(env, section_indexes);
            if columns != 256
                || materials < 0
                || materials > 64
                || section_count > 256
                || section_count > section_array_length
                || section_count > get_array_length(env, palette_flags)
                || section_count > get_array_length(env, output_palette_indexes)
                || (!profile_nanos.is_null() && get_array_length(env, profile_nanos) != 2)
                || (!apply_nanos.is_null() && get_array_length(env, apply_nanos) != 1)
                || i64::from(min_z) < i64::from(section_min_y)
                || i64::from(max_z) >= i64::from(section_min_y) + i64::from(section_count) * 16
            {
                return WeltError::IllegalArgument as jint;
            }

            let status = super::Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillResourceMaterials(
                env,
                class,
                min_z,
                max_z,
                tiny_x,
                tiny_y,
                dirt_x,
                dirt_y,
                column_min_z,
                column_max_z,
                resource_values,
                seeds,
                material_min_z,
                material_max_z,
                dirt_materials,
                chances,
                output,
                profile_nanos,
            );
            if status != WeltError::Ok as jint {
                return status;
            }

            let apply_start = std::time::Instant::now();
            let status = apply_palette_results(
                env,
                min_z,
                max_z,
                section_min_y,
                section_count,
                seeds,
                output,
                section_indexes,
                palette_flags,
                output_palette_indexes,
            );
            if status != WeltError::Ok as jint {
                return status;
            }
            if !apply_nanos.is_null() {
                type SetLongArrayRegion =
                    unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i64);
                let set_long: SetLongArrayRegion =
                    std::mem::transmute(super::function(env, super::SET_LONG_ARRAY_REGION));
                let timing = [apply_start.elapsed().as_nanos().min(i64::MAX as u128) as i64];
                set_long(env, apply_nanos, 0, 1, timing.as_ptr());
            }
            WeltError::Ok as jint
        })
    }
}

unsafe fn apply_palette_results(
    env: *mut JNIEnv,
    min_z: jint,
    max_z: jint,
    section_min_y: jint,
    section_count: jint,
    seeds: jobject,
    output: jobject,
    section_indexes: jobject,
    palette_flags: jobject,
    output_palette_indexes: jobject,
) -> jint {
    unsafe {
        type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
        type GetObjectArrayElement =
            unsafe extern "system" fn(*mut JNIEnv, jobject, jint) -> jobject;
        type GetByteArrayRegion =
            unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
        type GetIntArrayRegion =
            unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
        type GetByteArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
        type GetIntArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i32;
        let get_array_length: GetArrayLength =
            std::mem::transmute(super::function(env, super::GET_ARRAY_LENGTH));
        let get_object: GetObjectArrayElement =
            std::mem::transmute(super::function(env, super::GET_OBJECT_ARRAY_ELEMENT));
        let get_byte_region: GetByteArrayRegion =
            std::mem::transmute(super::function(env, super::GET_BYTE_ARRAY_REGION));
        let get_int_region: GetIntArrayRegion =
            std::mem::transmute(super::function(env, super::GET_INT_ARRAY_REGION));
        let get_byte_elements: GetByteArrayElements =
            std::mem::transmute(super::function(env, super::GET_BYTE_ARRAY_ELEMENTS));
        let get_int_elements: GetIntArrayElements =
            std::mem::transmute(super::function(env, super::GET_INT_ARRAY_ELEMENTS));

        if (section_min_y & 15) != 0 || min_z > max_z || min_z < section_min_y {
            return WeltError::IllegalArgument as jint;
        }
        let height_i64 = i64::from(max_z) - i64::from(min_z) + 1;
        if height_i64 <= 0 || height_i64 > 4096 {
            return WeltError::IllegalArgument as jint;
        }
        let height = height_i64 as usize;
        let materials = get_array_length(env, seeds);
        let section_array_length = get_array_length(env, section_indexes);
        if materials < 0
            || section_count <= 0
            || section_count > 256
            || section_count > section_array_length
            || section_count > get_array_length(env, palette_flags)
            || section_count > get_array_length(env, output_palette_indexes)
            || i64::from(max_z) >= i64::from(section_min_y) + i64::from(section_count) * 16
        {
            return WeltError::IllegalArgument as jint;
        }

        let mut sections = Vec::with_capacity(section_count as usize);
        for section in 0..section_count {
            let indexes_array = get_object(env, section_indexes, section);
            let flags_array = get_object(env, palette_flags, section);
            let outputs_array = get_object(env, output_palette_indexes, section);
            if indexes_array.is_null() || flags_array.is_null() || outputs_array.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let flags_length = get_array_length(env, flags_array);
            if get_array_length(env, indexes_array) != 4096
                || flags_length <= 0
                || flags_length > 1_048_576
                || get_array_length(env, outputs_array) != materials * 2
            {
                return WeltError::IllegalArgument as jint;
            }
            let mut flags = vec![0_i8; flags_length as usize];
            let mut output_indexes = vec![0_i32; (materials * 2) as usize];
            get_byte_region(env, flags_array, 0, flags_length, flags.as_mut_ptr());
            get_int_region(
                env,
                outputs_array,
                0,
                materials * 2,
                output_indexes.as_mut_ptr(),
            );
            if output_indexes
                .iter()
                .any(|&index| index < 0 || index >= flags_length)
            {
                return WeltError::IllegalArgument as jint;
            }
            sections.push(PaletteSection {
                indexes_array,
                flags,
                output_indexes,
            });
        }

        let output_pointer = get_byte_elements(env, output, std::ptr::null_mut());
        if output_pointer.is_null() {
            return WeltError::Internal as jint;
        }
        let output_values = ReadOnlyByteArray {
            env,
            array: output,
            values: output_pointer,
            length: 256 * height,
        };
        let mut section_values = Vec::with_capacity(section_count as usize);
        for section in &sections {
            let pointer = get_int_elements(env, section.indexes_array, std::ptr::null_mut());
            if pointer.is_null() {
                return WeltError::Internal as jint;
            }
            section_values.push(WritableIntArray {
                env,
                array: section.indexes_array,
                values: pointer,
                commit: false,
            });
        }

        let result_bytes = output_values.as_slice();
        for (section_index, section) in sections.iter().enumerate() {
            let section_y = i64::from(section_min_y) + section_index as i64 * 16;
            let low_y = i64::from(min_z).max(section_y);
            let high_y = i64::from(max_z).min(section_y + 15);
            if low_y > high_y {
                continue;
            }
            let palette_indexes = section_values[section_index].as_slice();
            for column in 0..256 {
                let x = column >> 4;
                let z = column & 15;
                for y in (low_y..=high_y).rev() {
                    let result_offset = column * height + (y - i64::from(min_z)) as usize;
                    let code = result_bytes[result_offset] as u8;
                    if code == 0 {
                        continue;
                    }
                    let material = usize::from(code - 1);
                    if material >= section.output_indexes.len() / 2 {
                        return WeltError::IllegalArgument as jint;
                    }
                    let local_y = (y - section_y) as usize;
                    let block_offset = x | ((z | (local_y << 4)) << 4);
                    let old_palette_index = palette_indexes[block_offset];
                    if old_palette_index < 0 || old_palette_index as usize >= section.flags.len() {
                        return WeltError::IllegalArgument as jint;
                    }
                    let deepslate = (section.flags[old_palette_index as usize] & 1) != 0;
                    let output_offset = material * 2 + usize::from(deepslate);
                    let new_palette_index = section.output_indexes[output_offset];
                    if new_palette_index < 0 || new_palette_index as usize >= section.flags.len() {
                        return WeltError::IllegalArgument as jint;
                    }
                }
            }
        }

        for (section_index, section) in sections.iter().enumerate() {
            let section_y = i64::from(section_min_y) + section_index as i64 * 16;
            let low_y = i64::from(min_z).max(section_y);
            let high_y = i64::from(max_z).min(section_y + 15);
            if low_y > high_y {
                continue;
            }
            let palette_indexes = section_values[section_index].as_mut_slice();
            for column in 0..256 {
                let x = column >> 4;
                let z = column & 15;
                for y in (low_y..=high_y).rev() {
                    let result_offset = column * height + (y - i64::from(min_z)) as usize;
                    let code = result_bytes[result_offset] as u8;
                    if code == 0 {
                        continue;
                    }
                    let material = usize::from(code - 1);
                    let local_y = (y - section_y) as usize;
                    let block_offset = x | ((z | (local_y << 4)) << 4);
                    let old_palette_index = palette_indexes[block_offset] as usize;
                    let deepslate = (section.flags[old_palette_index] & 1) != 0;
                    palette_indexes[block_offset] =
                        section.output_indexes[material * 2 + usize::from(deepslate)];
                }
            }
        }
        for section in &mut section_values {
            section.commit = true;
        }
        WeltError::Ok as jint
    }
}
