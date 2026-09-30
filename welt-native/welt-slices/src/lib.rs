//! JNI entry points for bulk Welt kernels. The Java application loads this
//! library only when a native feature flag is enabled.

use std::cell::RefCell;
use std::ffi::c_void;
use std::slice;
use welt_core::erosion::erode_raw_height_region;
use welt_core::error::WeltError;
use welt_core::flood_fill::linear_flood_fill;
use welt_core::height_edit::{apply_flatten_brush, apply_height_brush, FlattenMode};
use welt_core::jni::{jclass, jint, jlong, jni_catch, jobject, JNIEnv};
use welt_core::mountain::raise_mountain;
use welt_core::nibble_paint::{apply_nibble_layer_brush, NibblePaintMode};
use welt_core::paint_mask::paint_threshold_mask;
use welt_core::raise_pyramid::{raise_rotated_pyramid, raise_square_pyramid};
use welt_core::river_paint::apply_river_paint;
use welt_core::smooth_height::smooth_height_region;
use welt_core::sponge::apply_sponge_brush;
use welt_export::edge_distance::bake_edge_distances;
use welt_export::edge_height::bake_edge_heights;
use welt_export::frost::{
    apply_frost_column, apply_frost_packed_columns, FrostCell, FrostMode, FrostSettings,
    FrostUpdate,
};
use welt_gen::fancy_theme::fill_fancy_theme_tile;
use welt_gen::height_map_tree::{
    fill_height_map_tree, fill_height_map_tree_points, fill_slope_samples, HeightMapNode,
    MAX_PROGRAM_NODES,
};
use welt_gen::noise_height_map::NoiseHeightMapBulk;
use welt_gen::resource_noise::fill_resource_materials_into;
use welt_gen::theme_layers::{fill_simple_theme_layers, fill_simple_theme_random_bit_layers};
use welt_gen::theme_terrain::{SimpleThemeTerrainBulk, SimpleThemeTerrainScratch};
use welt_nbt::packed_array::{pack_indices, unpack_indices};
use welt_render::shade::{shade_pixels, shade_pixels_compact};

mod chunk_buffer;
mod fluid_flow;
mod resource_palette;

#[cfg(windows)]
#[repr(C)]
struct ProcessMemoryCounters {
    cb: u32,
    page_fault_count: u32,
    peak_working_set_size: usize,
    working_set_size: usize,
    quota_peak_paged_pool_usage: usize,
    quota_paged_pool_usage: usize,
    quota_peak_non_paged_pool_usage: usize,
    quota_non_paged_pool_usage: usize,
    pagefile_usage: usize,
    peak_pagefile_usage: usize,
}

#[cfg(windows)]
#[link(name = "psapi")]
unsafe extern "system" {
    fn GetProcessMemoryInfo(
        process: *mut c_void,
        counters: *mut ProcessMemoryCounters,
        size: u32,
    ) -> i32;
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentProcess() -> *mut c_void;
}

// JNI function-table indices, checked against the JDK's jni.h. The first four
// entries are reserved; GetArrayLength is 171, GetDoubleArrayElements is 190,
// and ReleaseDoubleArrayElements is 198. Keeping calls here avoids a JNI crate.
const GET_ARRAY_LENGTH: usize = 171;
const GET_OBJECT_ARRAY_ELEMENT: usize = 173;
const GET_DIRECT_BUFFER_ADDRESS: usize = 230;
const GET_DIRECT_BUFFER_CAPACITY: usize = 231;
const GET_INT_ARRAY_REGION: usize = 203;
const GET_LONG_ARRAY_REGION: usize = 204;
const GET_FLOAT_ARRAY_REGION: usize = 205;
const GET_DOUBLE_ARRAY_REGION: usize = 206;
const GET_DOUBLE_ARRAY_ELEMENTS: usize = 190;
const RELEASE_DOUBLE_ARRAY_ELEMENTS: usize = 198;
const GET_FLOAT_ARRAY_ELEMENTS: usize = 189;
const RELEASE_FLOAT_ARRAY_ELEMENTS: usize = 197;
const JNI_ABORT: jint = 2;
const GET_BYTE_ARRAY_ELEMENTS: usize = 184;
const RELEASE_BYTE_ARRAY_ELEMENTS: usize = 192;
const GET_INT_ARRAY_ELEMENTS: usize = 187;
const RELEASE_INT_ARRAY_ELEMENTS: usize = 195;
const GET_LONG_ARRAY_ELEMENTS: usize = 188;
const RELEASE_LONG_ARRAY_ELEMENTS: usize = 196;
const SET_INT_ARRAY_REGION: usize = 211;
const SET_LONG_ARRAY_REGION: usize = 212;
const GET_BYTE_ARRAY_REGION: usize = 200;
const SET_BYTE_ARRAY_REGION: usize = 208;
const SET_FLOAT_ARRAY_REGION: usize = 213;

const SLICES_ABI_VERSION: jint = 1;

/// Applies one erosion round to a brush-sized height window and returns Java's
/// ordered setter calls as index/value pairs.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeErodeRawHeightRegion(
    env: *mut JNIEnv,
    _class: jclass,
    radius: jint,
    heights: jobject,
    controls: jobject,
    write_log: jobject,
    write_count_array: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if radius < 0
                || heights.is_null()
                || controls.is_null()
                || write_log.is_null()
                || write_count_array.is_null()
            {
                return WeltError::IllegalArgument as jint;
            }
            let diameter = match (radius as usize)
                .checked_mul(2)
                .and_then(|value| value.checked_add(1))
            {
                Some(value) => value,
                None => return WeltError::IllegalArgument as jint,
            };
            let window_width = diameter + 2;
            let window_area = match window_width.checked_mul(window_width) {
                Some(value) if value <= 1_048_576 => value,
                _ => return WeltError::IllegalArgument as jint,
            };
            let operation_area = match diameter.checked_mul(diameter) {
                Some(value) => value,
                None => return WeltError::IllegalArgument as jint,
            };
            let control_length = match operation_area.checked_mul(3) {
                Some(value) if value <= 1_048_576 => value,
                _ => return WeltError::IllegalArgument as jint,
            };
            let write_log_length = match operation_area.checked_mul(4) {
                Some(value) if value <= 1_048_576 => value,
                _ => return WeltError::IllegalArgument as jint,
            };

            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != window_area as jint
                || get_array_length(env, controls) != control_length as jint
                || get_array_length(env, write_log) != write_log_length as jint
                || get_array_length(env, write_count_array) != 1
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            let get_int: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_byte: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0_i32; window_area];
            let mut control_values = vec![0_i8; control_length];
            let mut ordered_writes = vec![0_i32; write_log_length];
            get_int(
                env,
                heights,
                0,
                window_area as jint,
                height_values.as_mut_ptr(),
            );
            get_byte(
                env,
                controls,
                0,
                control_length as jint,
                control_values.as_mut_ptr(),
            );

            let write_count = match erode_raw_height_region(
                radius,
                &mut height_values,
                &control_values,
                &mut ordered_writes,
            ) {
                Ok(count) => count,
                Err(_) => return WeltError::IllegalArgument as jint,
            };

            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let set_int: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            set_int(
                env,
                write_log,
                0,
                write_count as jint,
                ordered_writes.as_ptr(),
            );
            let count = write_count as i32;
            set_int(env, write_count_array, 0, 1, &count);
            WeltError::Ok as jint
        })
    }
}

/// Applies the interactive raise/lower brush to a prepared row-major height area.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeApplyHeightBrush(
    env: *mut JNIEnv,
    _class: jclass,
    inverse: jint,
    min_height: f32,
    max_height: f32,
    adjustment: f32,
    heights: jobject,
    strengths: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if heights.is_null() || strengths.is_null() || modified.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, heights);
            if !(1..=65_536).contains(&length)
                || get_array_length(env, strengths) != length
                || get_array_length(env, modified) != length
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; length as usize];
            let mut strength_values = vec![0.0_f32; length as usize];
            let mut modified_values = vec![0_i8; length as usize];
            get_float(env, heights, 0, length, height_values.as_mut_ptr());
            get_float(env, strengths, 0, length, strength_values.as_mut_ptr());
            if apply_height_brush(
                inverse != 0,
                min_height,
                max_height,
                adjustment,
                &mut height_values,
                &strength_values,
                &mut modified_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_float(env, heights, 0, length, height_values.as_ptr());
            set_byte(env, modified, 0, length, modified_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Applies one flatten, raise-only, or lower-only brush pass to caller buffers.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeApplyFlattenBrush(
    env: *mut JNIEnv,
    _class: jclass,
    mode: jint,
    target_height: f32,
    heights: jobject,
    strengths: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if heights.is_null() || strengths.is_null() || modified.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let mode = match mode {
                0 => FlattenMode::Flatten,
                1 => FlattenMode::Raise,
                2 => FlattenMode::Lower,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, heights);
            if !(1..=65_536).contains(&length)
                || get_array_length(env, strengths) != length
                || get_array_length(env, modified) != length
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; length as usize];
            let mut strength_values = vec![0.0_f32; length as usize];
            let mut modified_values = vec![0_i8; length as usize];
            get_float(env, heights, 0, length, height_values.as_mut_ptr());
            get_float(env, strengths, 0, length, strength_values.as_mut_ptr());
            if apply_flatten_brush(
                mode,
                target_height,
                &mut height_values,
                &strength_values,
                &mut modified_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_float(env, heights, 0, length, height_values.as_ptr());
            set_byte(env, modified, 0, length, modified_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Computes one Raise Mountain height plane from Java-prepared heights and brush strengths.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeApplyRaiseMountain(
    env: *mut JNIEnv,
    _class: jclass,
    origin_x: jint,
    origin_y: jint,
    width: jint,
    height: jint,
    min_z: jint,
    max_range: jint,
    peak_height: f32,
    peak_factor: f32,
    inverse: jint,
    noise_scale: f32,
    noise_seed: jlong,
    heights: jobject,
    strengths: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if width <= 0
                || height <= 0
                || heights.is_null()
                || strengths.is_null()
                || modified.is_null()
            {
                return WeltError::IllegalArgument as jint;
            }
            let area = match (width as usize).checked_mul(height as usize) {
                Some(area) if area <= 65_536 => area,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != area as jint
                || get_array_length(env, strengths) != area as jint
                || get_array_length(env, modified) != area as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; area];
            let mut strength_values = vec![0.0_f32; area];
            let mut modified_values = vec![0_i8; area];
            get_float(env, heights, 0, area as jint, height_values.as_mut_ptr());
            get_float(
                env,
                strengths,
                0,
                area as jint,
                strength_values.as_mut_ptr(),
            );
            if raise_mountain(
                origin_x,
                origin_y,
                width as usize,
                height as usize,
                min_z as f32,
                max_range as f32,
                peak_height,
                peak_factor,
                inverse != 0,
                noise_scale,
                noise_seed as i64,
                &mut height_values,
                &strength_values,
                &mut modified_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_float(env, heights, 0, area as jint, height_values.as_ptr());
            set_byte(env, modified, 0, area as jint, modified_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Prepares one Sponge stroke's per-cell water and lava actions.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeApplySpongeBrush(
    env: *mut JNIEnv,
    _class: jclass,
    inverse: jint,
    water_height: jint,
    strengths: jobject,
    actions: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if strengths.is_null() || actions.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, strengths);
            if !(1..=65_536).contains(&length) || get_array_length(env, actions) != length {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut strength_values = vec![0.0_f32; length as usize];
            let mut action_values = vec![0_i8; length as usize];
            get_float(env, strengths, 0, length, strength_values.as_mut_ptr());
            if apply_sponge_brush(
                inverse != 0,
                water_height,
                &strength_values,
                &mut action_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_byte(env, actions, 0, length, action_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Computes one River Paint level and the ordered per-cell edits for a brush area.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeApplyRiverPaint(
    env: *mut JNIEnv,
    _class: jclass,
    radius: jint,
    previous_water_level: jint,
    depth: f32,
    lava: jint,
    heights: jobject,
    terrain_heights: jobject,
    water_levels: jobject,
    strengths: jobject,
    slope_offsets: jobject,
    height_modified: jobject,
    flooded: jobject,
    beaches: jobject,
    water_level_output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if radius < 0
                || heights.is_null()
                || terrain_heights.is_null()
                || water_levels.is_null()
                || strengths.is_null()
                || slope_offsets.is_null()
                || height_modified.is_null()
                || flooded.is_null()
                || beaches.is_null()
                || water_level_output.is_null()
            {
                return WeltError::IllegalArgument as jint;
            }
            let side = match (radius as usize)
                .checked_mul(2)
                .and_then(|value| value.checked_add(1))
            {
                Some(value) => value,
                None => return WeltError::IllegalArgument as jint,
            };
            let area = match side.checked_mul(side) {
                Some(value) if value <= 65_536 => value,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != area as jint
                || get_array_length(env, terrain_heights) != area as jint
                || get_array_length(env, water_levels) != area as jint
                || get_array_length(env, strengths) != area as jint
                || get_array_length(env, slope_offsets) != area as jint
                || get_array_length(env, height_modified) != area as jint
                || get_array_length(env, flooded) != area as jint
                || get_array_length(env, beaches) != area as jint
                || get_array_length(env, water_level_output) != 1
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let get_int: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_int: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; area];
            let mut terrain_height_values = vec![0_i32; area];
            let mut water_level_values = vec![0_i32; area];
            let mut strength_values = vec![0.0_f32; area];
            let mut slope_offset_values = vec![0.0_f32; area];
            let mut height_modified_values = vec![0_i8; area];
            let mut flooded_values = vec![0_i8; area];
            let mut beach_values = vec![0_i8; area];
            get_float(env, heights, 0, area as jint, height_values.as_mut_ptr());
            get_int(
                env,
                terrain_heights,
                0,
                area as jint,
                terrain_height_values.as_mut_ptr(),
            );
            get_int(
                env,
                water_levels,
                0,
                area as jint,
                water_level_values.as_mut_ptr(),
            );
            get_float(
                env,
                strengths,
                0,
                area as jint,
                strength_values.as_mut_ptr(),
            );
            get_float(
                env,
                slope_offsets,
                0,
                area as jint,
                slope_offset_values.as_mut_ptr(),
            );
            let water_level = match apply_river_paint(
                radius as usize,
                previous_water_level,
                depth,
                lava != 0,
                &mut height_values,
                &terrain_height_values,
                &water_level_values,
                &strength_values,
                &slope_offset_values,
                &mut height_modified_values,
                &mut flooded_values,
                &mut beach_values,
            ) {
                Ok(value) => value,
                Err(_) => return WeltError::IllegalArgument as jint,
            };
            set_float(env, heights, 0, area as jint, height_values.as_ptr());
            set_byte(
                env,
                height_modified,
                0,
                area as jint,
                height_modified_values.as_ptr(),
            );
            set_byte(env, flooded, 0, area as jint, flooded_values.as_ptr());
            set_byte(env, beaches, 0, area as jint, beach_values.as_ptr());
            set_int(env, water_level_output, 0, 1, &water_level);
            WeltError::Ok as jint
        })
    }
}

/// Computes the ordered fill indices for a bounded row-major boundary mask.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeLinearFloodFill(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    seed_x: jint,
    seed_y: jint,
    boundary: jobject,
    fill_indices: jobject,
    fill_count: jobject,
    bounds_hit: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if width <= 0
                || height <= 0
                || seed_x < 0
                || seed_y < 0
                || boundary.is_null()
                || fill_indices.is_null()
                || fill_count.is_null()
                || bounds_hit.is_null()
            {
                return WeltError::IllegalArgument as jint;
            }
            let area = match (width as usize).checked_mul(height as usize) {
                Some(area) if area <= 65_536 => area,
                _ => return WeltError::IllegalArgument as jint,
            };
            let output_capacity = match area.checked_mul(2) {
                Some(capacity) => capacity,
                None => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, boundary) != area as jint
                || get_array_length(env, fill_indices) < output_capacity as jint
                || get_array_length(env, fill_count) != 1
                || get_array_length(env, bounds_hit) != 1
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let get_byte: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let set_int: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            let mut boundary_values = vec![0_i8; area];
            get_byte(env, boundary, 0, area as jint, boundary_values.as_mut_ptr());
            let result = match linear_flood_fill(
                width as usize,
                height as usize,
                seed_x as usize,
                seed_y as usize,
                &boundary_values,
            ) {
                Ok(result) => result,
                Err(_) => return WeltError::IllegalArgument as jint,
            };
            let fill_count_value = result.fill_indices.len() as i32;
            let bounds_hit_value = i32::from(result.bounds_hit);
            set_int(
                env,
                fill_indices,
                0,
                fill_count_value,
                result.fill_indices.as_ptr(),
            );
            set_int(env, fill_count, 0, 1, &fill_count_value);
            set_int(env, bounds_hit, 0, 1, &bounds_hit_value);
            WeltError::Ok as jint
        })
    }
}

/// Computes nibble-layer brush values for Java's normal tile setters.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeApplyNibbleLayerBrush(
    env: *mut JNIEnv,
    _class: jclass,
    mode: jint,
    values: jobject,
    strengths: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if values.is_null() || strengths.is_null() || modified.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            let mode = match mode {
                0 => NibblePaintMode::Apply,
                1 => NibblePaintMode::RemoveRounded,
                2 => NibblePaintMode::RemoveTruncated,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, values);
            if !(1..=65_536).contains(&length)
                || get_array_length(env, strengths) != length
                || get_array_length(env, modified) != length
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_int: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_int: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut value_buffer = vec![0_i32; length as usize];
            let mut strength_buffer = vec![0.0_f32; length as usize];
            let mut modified_buffer = vec![0_i8; length as usize];
            get_int(env, values, 0, length, value_buffer.as_mut_ptr());
            get_float(env, strengths, 0, length, strength_buffer.as_mut_ptr());
            if apply_nibble_layer_brush(
                mode,
                &mut value_buffer,
                &strength_buffer,
                &mut modified_buffer,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_int(env, values, 0, length, value_buffer.as_ptr());
            set_byte(env, modified, 0, length, modified_buffer.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Computes the non-dithered layer-brush threshold mask.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativePaintThresholdMask(
    env: *mut JNIEnv,
    _class: jclass,
    strengths: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if strengths.is_null() || modified.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, strengths);
            if !(1..=65_536).contains(&length) || get_array_length(env, modified) != length {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut strength_buffer = vec![0.0_f32; length as usize];
            let mut modified_buffer = vec![0_i8; length as usize];
            get_float(env, strengths, 0, length, strength_buffer.as_mut_ptr());
            if paint_threshold_mask(&strength_buffer, &mut modified_buffer).is_err() {
                return WeltError::IllegalArgument as jint;
            }
            set_byte(env, modified, 0, length, modified_buffer.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Smooths one prepared height area with the Java operation's 11 by 11 window.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeSmoothHeightRegion(
    env: *mut JNIEnv,
    _class: jclass,
    input_width: jint,
    input_height: jint,
    heights: jobject,
    strengths: jobject,
    output: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if input_width < 11
                || input_height < 11
                || heights.is_null()
                || strengths.is_null()
                || output.is_null()
                || modified.is_null()
            {
                return WeltError::IllegalArgument as jint;
            }
            let input_area = match (input_width as usize).checked_mul(input_height as usize) {
                Some(area) if area <= 65_536 => area,
                _ => return WeltError::IllegalArgument as jint,
            };
            let output_area =
                match ((input_width - 10) as usize).checked_mul((input_height - 10) as usize) {
                    Some(area) if area <= 65_536 => area,
                    _ => return WeltError::IllegalArgument as jint,
                };

            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != input_area as jint
                || get_array_length(env, strengths) != output_area as jint
                || get_array_length(env, output) != output_area as jint
                || get_array_length(env, modified) != output_area as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; input_area];
            let mut strength_values = vec![0.0_f32; output_area];
            let mut output_values = vec![0.0_f32; output_area];
            let mut modified_values = vec![0_i8; output_area];
            get_float(
                env,
                heights,
                0,
                input_area as jint,
                height_values.as_mut_ptr(),
            );
            get_float(
                env,
                strengths,
                0,
                output_area as jint,
                strength_values.as_mut_ptr(),
            );
            if smooth_height_region(
                input_width as usize,
                input_height as usize,
                &height_values,
                &strength_values,
                &mut output_values,
                &mut modified_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_float(env, output, 0, output_area as jint, output_values.as_ptr());
            set_byte(
                env,
                modified,
                0,
                output_area as jint,
                modified_values.as_ptr(),
            );
            WeltError::Ok as jint
        })
    }
}

/// Computes one square sandstone pyramid's ordered height updates.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeRaiseSquarePyramid(
    env: *mut JNIEnv,
    _class: jclass,
    max_ring: jint,
    center_height: f32,
    max_height: f32,
    heights: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if heights.is_null() || modified.is_null() || max_ring > 128 {
                return WeltError::IllegalArgument as jint;
            }
            let radius = if max_ring > 1 {
                (max_ring - 1) as usize
            } else {
                0
            };
            let side = match radius.checked_mul(2).and_then(|value| value.checked_add(1)) {
                Some(value) => value,
                None => return WeltError::IllegalArgument as jint,
            };
            let area = match side.checked_mul(side) {
                Some(value) if value <= 65_536 => value,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != area as jint
                || get_array_length(env, modified) != area as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; area];
            let mut modified_values = vec![0_i8; area];
            get_float(env, heights, 0, area as jint, height_values.as_mut_ptr());
            if raise_square_pyramid(
                max_ring,
                center_height,
                max_height,
                &mut height_values,
                &mut modified_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_float(env, heights, 0, area as jint, height_values.as_ptr());
            set_byte(env, modified, 0, area as jint, modified_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Computes one 45-degree rotated sandstone pyramid's ordered height updates.
///
/// # Safety
/// `env`, arrays, and their lengths must be valid references from the JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeRaiseRotatedPyramid(
    env: *mut JNIEnv,
    _class: jclass,
    max_ring: jint,
    center_height: f32,
    max_height: f32,
    heights: jobject,
    modified: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if heights.is_null() || modified.is_null() || max_ring > 128 {
                return WeltError::IllegalArgument as jint;
            }
            let radius = if max_ring > 1 {
                (max_ring - 1) as usize
            } else {
                0
            };
            let side = match radius.checked_mul(2).and_then(|value| value.checked_add(1)) {
                Some(value) => value,
                None => return WeltError::IllegalArgument as jint,
            };
            let area = match side.checked_mul(side) {
                Some(value) if value <= 65_536 => value,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != area as jint
                || get_array_length(env, modified) != area as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let set_float: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            let mut height_values = vec![0.0_f32; area];
            let mut modified_values = vec![0_i8; area];
            get_float(env, heights, 0, area as jint, height_values.as_mut_ptr());
            if raise_rotated_pyramid(
                max_ring,
                center_height,
                max_height,
                &mut height_values,
                &mut modified_values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            set_float(env, heights, 0, area as jint, height_values.as_ptr());
            set_byte(env, modified, 0, area as jint, modified_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

fn unpack_fast_noise_lite_settings(packed: i32) -> (i32, i32, i32) {
    if packed & 0x4000_0000 == 0 {
        (packed, 0, 1)
    } else {
        (packed & 0xff, (packed >> 8) & 0xff, (packed >> 16) & 0xff)
    }
}

fn decode_height_map_node(
    opcode: i32,
    value: f64,
    scale: f64,
    octaves: i32,
    seed: i64,
) -> Option<HeightMapNode> {
    Some(match opcode {
        0 => HeightMapNode::Constant(value),
        1 => HeightMapNode::Noise {
            d_height: value,
            scale,
            octaves,
            effective_seed: seed,
        },
        13 => {
            let (octaves, noise_type, fractal_type) = unpack_fast_noise_lite_settings(octaves);
            HeightMapNode::FastNoiseLite {
                height: value,
                frequency: scale,
                octaves,
                effective_seed: seed,
                noise_type,
                fractal_type,
            }
        }
        8 => HeightMapNode::Mandelbrot,
        9 | 10 => HeightMapNode::Banded {
            segment1_length: octaves,
            segment1_end_height: value,
            segment2_length: seed as i32,
            segment2_end_height: scale,
            smooth: opcode == 10,
        },
        11 => HeightMapNode::Shelving {
            shelve_height: octaves,
            shelve_strength: seed as i32,
        },
        12 => HeightMapNode::NinePatch {
            inner_size: octaves,
            border_size: seed as i32,
            coast_size: scale as i32,
            height: value,
        },
        2 => HeightMapNode::Add,
        3 => HeightMapNode::Subtract,
        4 => HeightMapNode::Multiply,
        5 => HeightMapNode::Minimum,
        6 => HeightMapNode::Maximum,
        _ => return None,
    })
}

#[derive(Default)]
struct ResourceNoiseInputs {
    tiny_x: Vec<f64>,
    tiny_y: Vec<f64>,
    dirt_x: Vec<f64>,
    dirt_y: Vec<f64>,
    column_min_z: Vec<i32>,
    column_max_z: Vec<i32>,
    resource_values: Vec<i32>,
    seeds: Vec<i64>,
    material_min_z: Vec<i32>,
    material_max_z: Vec<i32>,
    raw_dirt_materials: Vec<i8>,
    dirt_materials: Vec<u8>,
    chances: Vec<f32>,
}

#[derive(Default)]
struct SimpleThemeLayerInputs {
    quantised_heights: Vec<i32>,
    layer_tables: Vec<i32>,
    bit_layer_tables: Vec<i32>,
}

#[derive(Default)]
struct SimpleThemeTerrainInputs {
    quantised_heights: Vec<i32>,
    terrain_range_ordinals: Vec<i32>,
    output_ordinals: Vec<i32>,
    theme: Option<SimpleThemeTerrainBulk>,
    scratch: SimpleThemeTerrainScratch,
}

thread_local! {
    static RESOURCE_NOISE_INPUTS: RefCell<ResourceNoiseInputs> =
        RefCell::new(ResourceNoiseInputs::default());
    static SIMPLE_THEME_LAYER_INPUTS: RefCell<SimpleThemeLayerInputs> =
        RefCell::new(SimpleThemeLayerInputs::default());
    static SIMPLE_THEME_TERRAIN_INPUTS: RefCell<SimpleThemeTerrainInputs> =
        RefCell::new(SimpleThemeTerrainInputs::default());
}

/// # Safety
/// `env` must be supplied by the JVM for the current native call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeAbiVersion(
    env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    unsafe { jni_catch(env, || SLICES_ABI_VERSION) }
}

/// Validates a direct chunk palette buffer in place, without copying to a Rust Vec.
///
/// # Safety
/// `env` and `buffer` must be supplied by the JVM for the current native call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeValidateChunkPaletteBuffer(
    env: *mut JNIEnv,
    _class: jclass,
    buffer: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if buffer.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type GetDirectBufferAddress =
                unsafe extern "system" fn(*mut JNIEnv, jobject) -> *mut c_void;
            type GetDirectBufferCapacity = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jlong;
            let address: GetDirectBufferAddress =
                std::mem::transmute(function(env, GET_DIRECT_BUFFER_ADDRESS));
            let capacity: GetDirectBufferCapacity =
                std::mem::transmute(function(env, GET_DIRECT_BUFFER_CAPACITY));
            let pointer = address(env, buffer).cast::<u8>();
            let length = capacity(env, buffer);
            if pointer.is_null() || !(0..=9_000_000).contains(&length) {
                return WeltError::IllegalArgument as jint;
            }
            let bytes = slice::from_raw_parts(pointer, length as usize);
            if chunk_buffer::validate(bytes) {
                WeltError::Ok as jint
            } else {
                WeltError::IllegalArgument as jint
            }
        })
    }
}

/// Returns the current process working set in bytes, or -1 on unsupported platforms.
///
/// # Safety
/// The JNI environment and class are provided by the active JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeCurrentProcessResidentBytes(
    _env: *mut JNIEnv,
    _class: jclass,
) -> jlong {
    #[cfg(windows)]
    {
        let mut counters = ProcessMemoryCounters {
            cb: std::mem::size_of::<ProcessMemoryCounters>() as u32,
            page_fault_count: 0,
            peak_working_set_size: 0,
            working_set_size: 0,
            quota_peak_paged_pool_usage: 0,
            quota_paged_pool_usage: 0,
            quota_peak_non_paged_pool_usage: 0,
            quota_non_paged_pool_usage: 0,
            pagefile_usage: 0,
            peak_pagefile_usage: 0,
        };
        let process = unsafe { GetCurrentProcess() };
        if unsafe {
            GetProcessMemoryInfo(
                process,
                &mut counters,
                std::mem::size_of::<ProcessMemoryCounters>() as u32,
            )
        } != 0
        {
            return counters.working_set_size.min(i64::MAX as usize) as jlong;
        }
    }
    -1
}

/// Applies WorldPainter's two integer RGB brightness multiplications to one
/// tile's ARGB pixels. Packed `jlong` amounts contain terrain in the low 32
/// bits and fluid in the high 32 bits.
///
/// # Safety
/// All references and `env` must be supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeShadeColours(
    env: *mut JNIEnv,
    _class: jclass,
    colours: jobject,
    packed_amounts: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if colours.is_null() || packed_amounts.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, colours);
            if !(1..=1_048_576).contains(&length) || get_array_length(env, packed_amounts) != length
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i64);
            let get_int: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_long: GetLongArrayRegion =
                std::mem::transmute(function(env, GET_LONG_ARRAY_REGION));
            let mut colour_values = vec![0_i32; length as usize];
            let mut amount_values = vec![0_i64; length as usize];
            get_int(env, colours, 0, length, colour_values.as_mut_ptr());
            get_long(env, packed_amounts, 0, length, amount_values.as_mut_ptr());
            if shade_pixels(&mut colour_values, &amount_values).is_err() {
                return WeltError::IllegalArgument as jint;
            }
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let set_int: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            set_int(env, colours, 0, length, colour_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Applies compact terrain and fluid brightness amounts to one ARGB tile.
///
/// # Safety
/// `env` and both arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeShadeColoursCompact(
    env: *mut JNIEnv,
    _class: jclass,
    colours: jobject,
    packed_amounts: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if colours.is_null() || packed_amounts.is_null() {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let length = get_array_length(env, colours);
            if !(1..=1_048_576).contains(&length) || get_array_length(env, packed_amounts) != length
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            let get_int: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let mut colour_values = vec![0_i32; length as usize];
            let mut amount_values = vec![0_i32; length as usize];
            get_int(env, colours, 0, length, colour_values.as_mut_ptr());
            get_int(env, packed_amounts, 0, length, amount_values.as_mut_ptr());
            if shade_pixels_compact(&mut colour_values, &amount_values).is_err() {
                return WeltError::IllegalArgument as jint;
            }
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let set_int: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            set_int(env, colours, 0, length, colour_values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// # Safety
/// `env` must be the JNI environment supplied to this native method.
unsafe fn function(env: *mut JNIEnv, index: usize) -> *const c_void {
    unsafe { *(*env).functions.cast::<*const c_void>().add(index) }
}

struct DoubleArrayOutput {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut f64,
    length: usize,
}

impl DoubleArrayOutput {
    fn as_mut_slice(&mut self) -> &mut [f64] {
        unsafe { slice::from_raw_parts_mut(self.values, self.length) }
    }
}

impl Drop for DoubleArrayOutput {
    fn drop(&mut self) {
        type ReleaseDoubleArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut f64, jint);
        let release: ReleaseDoubleArrayElements =
            unsafe { std::mem::transmute(function(self.env, RELEASE_DOUBLE_ARRAY_ELEMENTS)) };
        unsafe { release(self.env, self.array, self.values, 0) };
    }
}

struct DoubleArrayInput {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut f64,
    length: usize,
}

impl DoubleArrayInput {
    fn as_slice(&self) -> &[f64] {
        unsafe { slice::from_raw_parts(self.values, self.length) }
    }
}

impl Drop for DoubleArrayInput {
    fn drop(&mut self) {
        unsafe {
            type ReleaseDoubleArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut f64, jint);
            let release: ReleaseDoubleArrayElements =
                std::mem::transmute(function(self.env, RELEASE_DOUBLE_ARRAY_ELEMENTS));
            release(self.env, self.array, self.values, JNI_ABORT);
        }
    }
}

struct FloatArrayInput {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut f32,
    length: usize,
}

impl FloatArrayInput {
    fn as_slice(&self) -> &[f32] {
        unsafe { slice::from_raw_parts(self.values, self.length) }
    }
}

impl Drop for FloatArrayInput {
    fn drop(&mut self) {
        type ReleaseFloatArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut f32, jint);
        let release: ReleaseFloatArrayElements =
            unsafe { std::mem::transmute(function(self.env, RELEASE_FLOAT_ARRAY_ELEMENTS)) };
        unsafe { release(self.env, self.array, self.values, JNI_ABORT) };
    }
}

struct ByteArrayOutput {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut i8,
    length: usize,
}

struct LongArrayOutput {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut i64,
    length: usize,
}

impl LongArrayOutput {
    fn as_mut_slice(&mut self) -> &mut [i64] {
        unsafe { slice::from_raw_parts_mut(self.values, self.length) }
    }
}

impl Drop for LongArrayOutput {
    fn drop(&mut self) {
        type ReleaseLongArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut i64, jint);
        let release: ReleaseLongArrayElements =
            unsafe { std::mem::transmute(function(self.env, RELEASE_LONG_ARRAY_ELEMENTS)) };
        unsafe { release(self.env, self.array, self.values, 0) };
    }
}

struct WritableIntArray {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut i32,
    commit: bool,
}

impl WritableIntArray {
    fn as_slice(&self) -> &[i32] {
        unsafe { slice::from_raw_parts(self.values, 4096) }
    }

    fn as_mut_slice(&mut self) -> &mut [i32] {
        unsafe { slice::from_raw_parts_mut(self.values, 4096) }
    }
}

impl Drop for WritableIntArray {
    fn drop(&mut self) {
        type ReleaseIntArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut i32, jint);
        let release: ReleaseIntArrayElements =
            unsafe { std::mem::transmute(function(self.env, RELEASE_INT_ARRAY_ELEMENTS)) };
        unsafe {
            release(
                self.env,
                self.array,
                self.values,
                if self.commit { 0 } else { JNI_ABORT },
            )
        };
    }
}

impl ByteArrayOutput {
    fn as_mut_slice(&mut self) -> &mut [i8] {
        unsafe { slice::from_raw_parts_mut(self.values, self.length) }
    }
}

impl Drop for ByteArrayOutput {
    fn drop(&mut self) {
        type ReleaseByteArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut i8, jint);
        let release: ReleaseByteArrayElements =
            unsafe { std::mem::transmute(function(self.env, RELEASE_BYTE_ARRAY_ELEMENTS)) };
        unsafe { release(self.env, self.array, self.values, 0) };
    }
}

struct ReadOnlyByteArray {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut i8,
    length: usize,
}

impl ReadOnlyByteArray {
    fn as_slice(&self) -> &[i8] {
        unsafe { slice::from_raw_parts(self.values, self.length) }
    }
}

impl Drop for ReadOnlyByteArray {
    fn drop(&mut self) {
        type ReleaseByteArrayElements =
            unsafe extern "system" fn(*mut JNIEnv, jobject, *mut i8, jint);
        let release: ReleaseByteArrayElements =
            unsafe { std::mem::transmute(function(self.env, RELEASE_BYTE_ARRAY_ELEMENTS)) };
        unsafe { release(self.env, self.array, self.values, JNI_ABORT) };
    }
}

/// Applies the FrostExporter decision to a whole vertical column. For random
/// mode Java replaces the provisional one-layer result after drawing from its
/// own Random instance, preserving the call sequence across columns.
/// `snow_layers` uses bits 0..6 for layer count and bit 7 for `material == SNOW`.
/// Output codes: 0 unchanged, 1 air, 2 ice, 3..10 snow with 1..8 layers.
///
/// # Safety
/// All references and `env` must be supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFrostColumn(
    env: *mut JNIEnv,
    _class: jclass,
    min_z: jint,
    max_z: jint,
    highest_non_air: jint,
    frost_everywhere: jint,
    frost_layer_present: jint,
    snow_under_trees: jint,
    mode: jint,
    height_float: f32,
    height_int: jint,
    frost_bit_count: jint,
    flags: jobject,
    snow_layers: jobject,
    updates: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if flags.is_null() || snow_layers.is_null() || updates.is_null() || min_z > max_z {
                return WeltError::IllegalArgument as jint;
            }
            let length = i64::from(max_z) - i64::from(min_z) + 1;
            if !(1..=4096).contains(&length) || highest_non_air < min_z || highest_non_air > max_z {
                return WeltError::IllegalArgument as jint;
            }
            let mode = match mode {
                0 => FrostMode::Flat,
                1 => FrostMode::Random,
                2 => FrostMode::Smooth,
                3 => FrostMode::SmoothAtAllElevations,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if [flags, snow_layers, updates]
                .iter()
                .any(|&array| get_array_length(env, array) != length as jint)
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            let get_byte_array_region: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let mut raw_flags = [0_i8; 4096];
            let mut raw_snow = [0_i8; 4096];
            get_byte_array_region(env, flags, 0, length as jint, raw_flags.as_mut_ptr());
            get_byte_array_region(env, snow_layers, 0, length as jint, raw_snow.as_mut_ptr());
            let mut cells = [FrostCell::default(); 4096];
            for index in 0..length as usize {
                let snow = raw_snow[index] as u8;
                cells[index] = FrostCell::with_canonical_snow(
                    raw_flags[index] as u8,
                    snow & 0x7f,
                    snow & 0x80 != 0,
                );
            }
            let settings = FrostSettings {
                frost_everywhere: frost_everywhere != 0,
                frost_layer_present: frost_layer_present != 0,
                snow_under_trees: snow_under_trees != 0,
                mode,
                random_snow_layers: 1,
                height_float,
                height_int,
                frost_bit_count,
            };
            if apply_frost_column(
                &mut cells[..length as usize],
                min_z,
                max_z,
                highest_non_air,
                settings,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            let mut output = [0_i8; 4096];
            for index in 0..length as usize {
                output[index] = match cells[index].update {
                    FrostUpdate::Unchanged => 0,
                    FrostUpdate::Air => 1,
                    FrostUpdate::Ice => 2,
                    FrostUpdate::Snow(layers) => (layers + 2) as i8,
                };
            }
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let set_byte_array_region: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            set_byte_array_region(env, updates, 0, length as jint, output.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Applies one common FrostExporter mode to a packed batch of columns.
///
/// # Safety
/// All references and `env` must be supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFrostColumns(
    env: *mut JNIEnv,
    _class: jclass,
    min_z: jint,
    max_z: jint,
    column_count: jint,
    column_max_z: jobject,
    frost_everywhere: jint,
    snow_under_trees: jint,
    mode: jint,
    flags: jobject,
    snow_layers: jobject,
    highest_non_air: jobject,
    height_floats: jobject,
    height_ints: jobject,
    frost_bit_counts: jobject,
    updates: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [
                flags,
                snow_layers,
                column_max_z,
                highest_non_air,
                height_floats,
                height_ints,
                frost_bit_counts,
                updates,
            ]
            .iter()
            .any(|array| array.is_null())
                || column_count <= 0
                || min_z > max_z
            {
                return WeltError::IllegalArgument as jint;
            }
            let max_column_length = i64::from(max_z) - i64::from(min_z) + 1;
            if !(1..=4096).contains(&max_column_length)
                || column_count as i64 > 1_048_576 / max_column_length
            {
                return WeltError::IllegalArgument as jint;
            }
            let mode = match mode {
                0 => FrostMode::Flat,
                1 => FrostMode::Random,
                2 => FrostMode::Smooth,
                3 => FrostMode::SmoothAtAllElevations,
                _ => return WeltError::IllegalArgument as jint,
            };
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let per_column_arrays = [
                column_max_z,
                highest_non_air,
                height_floats,
                height_ints,
                frost_bit_counts,
            ];
            if per_column_arrays
                .iter()
                .any(|&array| get_array_length(env, array) < column_count)
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            let get_int_array_region: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let mut packed_max_z = vec![0_i32; column_count as usize];
            let mut highest = vec![0_i32; column_count as usize];
            let mut heights_int = vec![0_i32; column_count as usize];
            let mut bit_counts = vec![0_i32; column_count as usize];
            get_int_array_region(
                env,
                column_max_z,
                0,
                column_count,
                packed_max_z.as_mut_ptr(),
            );
            get_int_array_region(env, highest_non_air, 0, column_count, highest.as_mut_ptr());
            get_int_array_region(env, height_ints, 0, column_count, heights_int.as_mut_ptr());
            get_int_array_region(
                env,
                frost_bit_counts,
                0,
                column_count,
                bit_counts.as_mut_ptr(),
            );
            let mut expected = 0_usize;
            for (&segment_max_z, &surface_z) in packed_max_z.iter().zip(&highest) {
                if segment_max_z < min_z
                    || segment_max_z > max_z
                    || surface_z < min_z
                    || surface_z > segment_max_z
                {
                    return WeltError::IllegalArgument as jint;
                }
                let segment_length = (i64::from(segment_max_z) - i64::from(min_z) + 1) as usize;
                let Some(new_total) = expected.checked_add(segment_length) else {
                    return WeltError::IllegalArgument as jint;
                };
                expected = new_total;
            }
            if expected > 1_048_576
                || [flags, snow_layers, updates]
                    .iter()
                    .any(|&array| get_array_length(env, array) < expected as jint)
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            let get_byte_array_region: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let mut raw_flags = vec![0_i8; expected];
            let mut raw_snow = vec![0_i8; expected];
            get_byte_array_region(env, flags, 0, expected as jint, raw_flags.as_mut_ptr());
            get_byte_array_region(env, snow_layers, 0, expected as jint, raw_snow.as_mut_ptr());

            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            let get_float_array_region: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let mut heights_float = vec![0.0_f32; column_count as usize];
            get_float_array_region(
                env,
                height_floats,
                0,
                column_count,
                heights_float.as_mut_ptr(),
            );

            let mut cells = vec![FrostCell::default(); expected];
            for index in 0..expected {
                let snow = raw_snow[index] as u8;
                cells[index] = FrostCell::with_canonical_snow(
                    raw_flags[index] as u8,
                    snow & 0x7f,
                    snow & 0x80 != 0,
                );
            }
            let settings: Vec<_> = (0..column_count as usize)
                .map(|index| FrostSettings {
                    frost_everywhere: frost_everywhere != 0,
                    frost_layer_present: true,
                    snow_under_trees: snow_under_trees != 0,
                    mode,
                    random_snow_layers: 1,
                    height_float: heights_float[index],
                    height_int: heights_int[index],
                    frost_bit_count: bit_counts[index],
                })
                .collect();
            if apply_frost_packed_columns(&mut cells, min_z, &packed_max_z, &highest, &settings)
                .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            let values: Vec<i8> = cells
                .iter()
                .map(|cell| match cell.update {
                    FrostUpdate::Unchanged => 0,
                    FrostUpdate::Air => 1,
                    FrostUpdate::Ice => 2,
                    FrostUpdate::Snow(layers) => (layers + 2) as i8,
                })
                .collect();
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let set_byte_array_region: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            set_byte_array_region(env, updates, 0, expected as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Fill an entire rectangular NoiseHeightMap sample in one JNI transition.
/// The output uses row-major order (y * width + x). A nonzero return value
/// leaves Java on its original implementation path.
///
/// # Safety
/// `env` and `output` must be valid JNI references from the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillNoiseHeights(
    env: *mut JNIEnv,
    _class: jclass,
    origin_x: jint,
    origin_y: jint,
    width: jint,
    height: jint,
    d_height: f64,
    scale: f64,
    octaves: jint,
    effective_seed: jlong,
    output: jobject,
) -> jint {
    // SAFETY: the JVM supplied the environment and array reference.
    unsafe {
        jni_catch(env, || {
            if output.is_null() || width <= 0 || height <= 0 {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            // Bound native allocation even if this entry point is called directly.
            if expected > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, output) != expected as jint {
                return WeltError::IllegalArgument as jint;
            }
            let Ok(map) = NoiseHeightMapBulk::new(d_height, scale, octaves, effective_seed) else {
                return WeltError::IllegalArgument as jint;
            };
            type GetDoubleArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f64;
            let get_elements: GetDoubleArrayElements =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_ELEMENTS));
            let values = get_elements(env, output, std::ptr::null_mut());
            if values.is_null() {
                return WeltError::Internal as jint;
            }
            let mut output_values = DoubleArrayOutput {
                env,
                array: output,
                values,
                length: expected,
            };
            let fill_result = map.fill_bulk(
                origin_x,
                origin_y,
                width as usize,
                height as usize,
                output_values.as_mut_slice(),
            );
            drop(output_values);
            if fill_result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Evaluate a post-order tree of constants, noise maps and additions in bulk.
///
/// # Safety
/// All array references must be valid JNI references from this JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillHeightMapTree(
    env: *mut JNIEnv,
    _class: jclass,
    origin_x: jint,
    origin_y: jint,
    width: jint,
    height: jint,
    node_count: jint,
    opcodes: jobject,
    values: jobject,
    scales: jobject,
    octaves: jobject,
    seeds: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [opcodes, values, scales, octaves, seeds, output]
                .iter()
                .any(|array| array.is_null())
                || width <= 0
                || height <= 0
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(area) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            if area > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if node_count <= 0
                || node_count as usize > MAX_PROGRAM_NODES
                || get_array_length(env, opcodes) < node_count
                || get_array_length(env, values) < node_count
                || get_array_length(env, scales) < node_count
                || get_array_length(env, octaves) < node_count
                || get_array_length(env, seeds) < node_count
                || get_array_length(env, output) != area as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetDoubleArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f64);
            type GetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i64);
            let get_ints: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_doubles: GetDoubleArrayRegion =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_REGION));
            let get_longs: GetLongArrayRegion =
                std::mem::transmute(function(env, GET_LONG_ARRAY_REGION));
            let count = node_count as usize;
            let mut raw_opcodes = vec![0_i32; count];
            let mut raw_values = vec![0.0_f64; count];
            let mut raw_scales = vec![0.0_f64; count];
            let mut raw_octaves = vec![0_i32; count];
            let mut raw_seeds = vec![0_i64; count];
            get_ints(env, opcodes, 0, node_count, raw_opcodes.as_mut_ptr());
            get_doubles(env, values, 0, node_count, raw_values.as_mut_ptr());
            get_doubles(env, scales, 0, node_count, raw_scales.as_mut_ptr());
            get_ints(env, octaves, 0, node_count, raw_octaves.as_mut_ptr());
            get_longs(env, seeds, 0, node_count, raw_seeds.as_mut_ptr());
            let mut nodes = Vec::with_capacity(count);
            for index in 0..count {
                nodes.push(match raw_opcodes[index] {
                    0 => HeightMapNode::Constant(raw_values[index]),
                    1 => HeightMapNode::Noise {
                        d_height: raw_values[index],
                        scale: raw_scales[index],
                        octaves: raw_octaves[index],
                        effective_seed: raw_seeds[index],
                    },
                    13 => {
                        let (octaves, noise_type, fractal_type) =
                            unpack_fast_noise_lite_settings(raw_octaves[index]);
                        HeightMapNode::FastNoiseLite {
                            height: raw_values[index],
                            frequency: raw_scales[index],
                            octaves,
                            effective_seed: raw_seeds[index],
                            noise_type,
                            fractal_type,
                        }
                    }
                    8 => HeightMapNode::Mandelbrot,
                    9 | 10 => HeightMapNode::Banded {
                        segment1_length: raw_octaves[index],
                        segment1_end_height: raw_values[index],
                        segment2_length: raw_seeds[index] as i32,
                        segment2_end_height: raw_scales[index],
                        smooth: raw_opcodes[index] == 10,
                    },
                    11 => HeightMapNode::Shelving {
                        shelve_height: raw_octaves[index],
                        shelve_strength: raw_seeds[index] as i32,
                    },
                    12 => HeightMapNode::NinePatch {
                        inner_size: raw_octaves[index],
                        border_size: raw_seeds[index] as i32,
                        coast_size: raw_scales[index] as i32,
                        height: raw_values[index],
                    },
                    2 => HeightMapNode::Add,
                    3 => HeightMapNode::Subtract,
                    4 => HeightMapNode::Multiply,
                    5 => HeightMapNode::Minimum,
                    6 => HeightMapNode::Maximum,
                    _ => return WeltError::IllegalArgument as jint,
                });
            }

            type GetDoubleArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f64;
            let get_elements: GetDoubleArrayElements =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_ELEMENTS));
            let output_ptr = get_elements(env, output, std::ptr::null_mut());
            if output_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let mut output_values = DoubleArrayOutput {
                env,
                array: output,
                values: output_ptr,
                length: area,
            };
            let result = fill_height_map_tree(
                &nodes,
                origin_x,
                origin_y,
                width as usize,
                height as usize,
                output_values.as_mut_slice(),
            );
            drop(output_values);
            if result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Evaluate two post-order height-map trees in one JNI transition.
///
/// # Safety
/// All array references must be valid JNI references from this JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillHeightMapTreePair(
    env: *mut JNIEnv,
    _class: jclass,
    origin_x: jint,
    origin_y: jint,
    width: jint,
    height: jint,
    first_node_count: jint,
    second_node_count: jint,
    opcodes: jobject,
    values: jobject,
    scales: jobject,
    octaves: jobject,
    seeds: jobject,
    first_output: jobject,
    second_output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [
                opcodes,
                values,
                scales,
                octaves,
                seeds,
                first_output,
                second_output,
            ]
            .iter()
            .any(|array| array.is_null())
                || width <= 0
                || height <= 0
                || first_node_count <= 0
                || first_node_count as usize > MAX_PROGRAM_NODES
                || second_node_count <= 0
                || second_node_count as usize > MAX_PROGRAM_NODES
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(area) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            if area > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            let first_count = first_node_count as usize;
            let second_count = second_node_count as usize;
            let total_count = first_count + second_count;

            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if [opcodes, values, scales, octaves, seeds]
                .iter()
                .any(|&array| get_array_length(env, array) < total_count as jint)
                || get_array_length(env, first_output) != area as jint
                || get_array_length(env, second_output) != area as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetDoubleArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f64);
            type GetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i64);
            let get_ints: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_doubles: GetDoubleArrayRegion =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_REGION));
            let get_longs: GetLongArrayRegion =
                std::mem::transmute(function(env, GET_LONG_ARRAY_REGION));
            let mut raw_opcodes = vec![0_i32; total_count];
            let mut raw_values = vec![0.0_f64; total_count];
            let mut raw_scales = vec![0.0_f64; total_count];
            let mut raw_octaves = vec![0_i32; total_count];
            let mut raw_seeds = vec![0_i64; total_count];
            get_ints(
                env,
                opcodes,
                0,
                total_count as jint,
                raw_opcodes.as_mut_ptr(),
            );
            get_doubles(env, values, 0, total_count as jint, raw_values.as_mut_ptr());
            get_doubles(env, scales, 0, total_count as jint, raw_scales.as_mut_ptr());
            get_ints(
                env,
                octaves,
                0,
                total_count as jint,
                raw_octaves.as_mut_ptr(),
            );
            get_longs(env, seeds, 0, total_count as jint, raw_seeds.as_mut_ptr());

            let mut first_nodes = Vec::with_capacity(first_count);
            let mut second_nodes = Vec::with_capacity(second_count);
            for index in 0..total_count {
                let Some(node) = decode_height_map_node(
                    raw_opcodes[index],
                    raw_values[index],
                    raw_scales[index],
                    raw_octaves[index],
                    raw_seeds[index],
                ) else {
                    return WeltError::IllegalArgument as jint;
                };
                if index < first_count {
                    first_nodes.push(node);
                } else {
                    second_nodes.push(node);
                }
            }

            type GetDoubleArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f64;
            let get_elements: GetDoubleArrayElements =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_ELEMENTS));
            let first_output_ptr = get_elements(env, first_output, std::ptr::null_mut());
            if first_output_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let mut first_output_values = DoubleArrayOutput {
                env,
                array: first_output,
                values: first_output_ptr,
                length: area,
            };
            let second_output_ptr = get_elements(env, second_output, std::ptr::null_mut());
            if second_output_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let mut second_output_values = DoubleArrayOutput {
                env,
                array: second_output,
                values: second_output_ptr,
                length: area,
            };
            let first_result = fill_height_map_tree(
                &first_nodes,
                origin_x,
                origin_y,
                width as usize,
                height as usize,
                first_output_values.as_mut_slice(),
            );
            let second_result = fill_height_map_tree(
                &second_nodes,
                origin_x,
                origin_y,
                width as usize,
                height as usize,
                second_output_values.as_mut_slice(),
            );
            drop(second_output_values);
            drop(first_output_values);
            if first_result.is_err() || second_result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Evaluate a post-order height-map tree at explicit float coordinates.
///
/// # Safety
/// All arrays must be valid JNI references from this JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillHeightMapTreePoints(
    env: *mut JNIEnv,
    _class: jclass,
    node_count: jint,
    opcodes: jobject,
    values: jobject,
    scales: jobject,
    octaves: jobject,
    seeds: jobject,
    x_coordinates: jobject,
    y_coordinates: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [
                opcodes,
                values,
                scales,
                octaves,
                seeds,
                x_coordinates,
                y_coordinates,
                output,
            ]
            .iter()
            .any(|array| array.is_null())
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let area = get_array_length(env, output);
            if area <= 0
                || area > 1_048_576
                || node_count <= 0
                || node_count as usize > MAX_PROGRAM_NODES
                || get_array_length(env, opcodes) < node_count
                || get_array_length(env, values) < node_count
                || get_array_length(env, scales) < node_count
                || get_array_length(env, octaves) < node_count
                || get_array_length(env, seeds) < node_count
                || get_array_length(env, x_coordinates) != area
                || get_array_length(env, y_coordinates) != area
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetDoubleArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f64);
            type GetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i64);
            let get_ints: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_doubles: GetDoubleArrayRegion =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_REGION));
            let get_longs: GetLongArrayRegion =
                std::mem::transmute(function(env, GET_LONG_ARRAY_REGION));
            let count = node_count as usize;
            let mut raw_opcodes = vec![0_i32; count];
            let mut raw_values = vec![0.0_f64; count];
            let mut raw_scales = vec![0.0_f64; count];
            let mut raw_octaves = vec![0_i32; count];
            let mut raw_seeds = vec![0_i64; count];
            get_ints(env, opcodes, 0, node_count, raw_opcodes.as_mut_ptr());
            get_doubles(env, values, 0, node_count, raw_values.as_mut_ptr());
            get_doubles(env, scales, 0, node_count, raw_scales.as_mut_ptr());
            get_ints(env, octaves, 0, node_count, raw_octaves.as_mut_ptr());
            get_longs(env, seeds, 0, node_count, raw_seeds.as_mut_ptr());
            let mut nodes = Vec::with_capacity(count);
            for index in 0..count {
                nodes.push(match raw_opcodes[index] {
                    0 => HeightMapNode::Constant(raw_values[index]),
                    1 => HeightMapNode::Noise {
                        d_height: raw_values[index],
                        scale: raw_scales[index],
                        octaves: raw_octaves[index],
                        effective_seed: raw_seeds[index],
                    },
                    13 => {
                        let (octaves, noise_type, fractal_type) =
                            unpack_fast_noise_lite_settings(raw_octaves[index]);
                        HeightMapNode::FastNoiseLite {
                            height: raw_values[index],
                            frequency: raw_scales[index],
                            octaves,
                            effective_seed: raw_seeds[index],
                            noise_type,
                            fractal_type,
                        }
                    }
                    8 => HeightMapNode::Mandelbrot,
                    9 | 10 => HeightMapNode::Banded {
                        segment1_length: raw_octaves[index],
                        segment1_end_height: raw_values[index],
                        segment2_length: raw_seeds[index] as i32,
                        segment2_end_height: raw_scales[index],
                        smooth: raw_opcodes[index] == 10,
                    },
                    11 => HeightMapNode::Shelving {
                        shelve_height: raw_octaves[index],
                        shelve_strength: raw_seeds[index] as i32,
                    },
                    12 => HeightMapNode::NinePatch {
                        inner_size: raw_octaves[index],
                        border_size: raw_seeds[index] as i32,
                        coast_size: raw_scales[index] as i32,
                        height: raw_values[index],
                    },
                    2 => HeightMapNode::Add,
                    3 => HeightMapNode::Subtract,
                    4 => HeightMapNode::Multiply,
                    5 => HeightMapNode::Minimum,
                    6 => HeightMapNode::Maximum,
                    _ => return WeltError::IllegalArgument as jint,
                });
            }

            type GetFloatArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f32;
            let get_float_elements: GetFloatArrayElements =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_ELEMENTS));
            let x_ptr = get_float_elements(env, x_coordinates, std::ptr::null_mut());
            if x_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let x_values = FloatArrayInput {
                env,
                array: x_coordinates,
                values: x_ptr,
                length: area as usize,
            };
            let y_ptr = get_float_elements(env, y_coordinates, std::ptr::null_mut());
            if y_ptr.is_null() {
                drop(x_values);
                return WeltError::Internal as jint;
            }
            let y_values = FloatArrayInput {
                env,
                array: y_coordinates,
                values: y_ptr,
                length: area as usize,
            };
            type GetDoubleArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f64;
            let get_elements: GetDoubleArrayElements =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_ELEMENTS));
            let output_ptr = get_elements(env, output, std::ptr::null_mut());
            if output_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let mut output_values = DoubleArrayOutput {
                env,
                array: output,
                values: output_ptr,
                length: area as usize,
            };
            let result = fill_height_map_tree_points(
                &nodes,
                x_values.as_slice(),
                y_values.as_slice(),
                output_values.as_mut_slice(),
            );
            drop(output_values);
            drop(y_values);
            drop(x_values);
            if result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Computes the slope operator from a sampled base grid with a one-cell halo.
///
/// # Safety
/// Both arrays must be valid JNI references from the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillSlopeSamples(
    env: *mut JNIEnv,
    _class: jclass,
    input_width: jint,
    input_height: jint,
    vertical_scaling: f32,
    base_samples: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if base_samples.is_null() || output.is_null() || input_width < 3 || input_height < 3 {
                return WeltError::IllegalArgument as jint;
            }
            let input_area = (input_width as usize).checked_mul(input_height as usize);
            let output_area = ((input_width - 2) as usize).checked_mul((input_height - 2) as usize);
            let (Some(input_area), Some(output_area)) = (input_area, output_area) else {
                return WeltError::IllegalArgument as jint;
            };
            if input_area > 1_048_576 || output_area > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, base_samples) != input_area as jint
                || get_array_length(env, output) != output_area as jint
            {
                return WeltError::IllegalArgument as jint;
            }
            let get_elements: unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f64 =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_ELEMENTS));
            let input_ptr = get_elements(env, base_samples, std::ptr::null_mut());
            if input_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let input_values = DoubleArrayInput {
                env,
                array: base_samples,
                values: input_ptr,
                length: input_area,
            };
            let output_ptr = get_elements(env, output, std::ptr::null_mut());
            if output_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let mut output_values = DoubleArrayOutput {
                env,
                array: output,
                values: output_ptr,
                length: output_area,
            };
            let result = fill_slope_samples(
                input_values.as_slice(),
                input_width as usize,
                input_height as usize,
                vertical_scaling,
                output_values.as_mut_slice(),
            );
            drop(output_values);
            drop(input_values);
            if !result {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Bake capped edge distances for a row-major bit-layer mask.
///
/// Java includes a one-pixel empty halo so world boundaries are represented as
/// ordinary exterior pixels. A nonzero return keeps the Java implementation.
///
/// # Safety
/// `env`, `mask`, and `output` must be valid references for this JVM call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeBakeEdgeDistances(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    max_distance: f32,
    mask: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if mask.is_null() || output.is_null() || width <= 0 || height <= 0 {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            if expected > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, mask) != expected as jint
                || get_array_length(env, output) != expected as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            let get_byte_array_region: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let mut raw_mask = vec![0_i8; expected];
            get_byte_array_region(env, mask, 0, expected as jint, raw_mask.as_mut_ptr());
            let mask: Vec<u8> = raw_mask.into_iter().map(|value| value as u8).collect();
            let mut values = vec![0.0_f32; expected];
            if bake_edge_distances(
                &mask,
                width as usize,
                height as usize,
                max_distance,
                &mut values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }

            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            let set_float_array_region: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            set_float_array_region(env, output, 0, expected as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// # Safety
/// `env`, arrays, and output must be valid references for this JVM call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeBakeEdgeHeights(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    radius: jint,
    min_height: f32,
    sources: jobject,
    source_heights: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if sources.is_null()
                || source_heights.is_null()
                || output.is_null()
                || width <= 0
                || height <= 0
                || !(0..=512).contains(&radius)
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            if expected > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if [sources, source_heights, output]
                .iter()
                .any(|array| get_array_length(env, *array) != expected as jint)
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            let get_byte_array_region: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let get_float_array_region: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let mut raw_sources = vec![0_i8; expected];
            let mut heights = vec![0.0_f32; expected];
            get_byte_array_region(env, sources, 0, expected as jint, raw_sources.as_mut_ptr());
            get_float_array_region(
                env,
                source_heights,
                0,
                expected as jint,
                heights.as_mut_ptr(),
            );
            let source_mask: Vec<u8> = raw_sources.into_iter().map(|value| value as u8).collect();
            let mut values = vec![0.0_f32; expected];
            if bake_edge_heights(
                &source_mask,
                &heights,
                width as usize,
                height as usize,
                radius as usize,
                min_height,
                &mut values,
            )
            .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            type SetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f32);
            let set_float_array_region: SetFloatArrayRegion =
                std::mem::transmute(function(env, SET_FLOAT_ARRAY_REGION));
            set_float_array_region(env, output, 0, expected as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// # Safety
/// `env`, arrays, and output must be valid references for this JVM call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativePackArrayCube(
    env: *mut JNIEnv,
    _class: jclass,
    palette_indices: jobject,
    bits_per_index: jint,
    straddle_longs: jint,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if palette_indices.is_null() || output.is_null() || !(1..=32).contains(&bits_per_index)
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let input_length = get_array_length(env, palette_indices);
            if input_length < 0 || input_length as usize > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            let get_int_array_region: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let mut raw_indices = vec![0_i32; input_length as usize];
            get_int_array_region(
                env,
                palette_indices,
                0,
                input_length,
                raw_indices.as_mut_ptr(),
            );
            let mut indices = Vec::with_capacity(raw_indices.len());
            for value in raw_indices {
                let Ok(value) = u32::try_from(value) else {
                    return WeltError::IllegalArgument as jint;
                };
                indices.push(value);
            }
            let Ok(packed) = pack_indices(&indices, bits_per_index as u32, straddle_longs != 0)
            else {
                return WeltError::IllegalArgument as jint;
            };
            if get_array_length(env, output) != packed.len() as jint {
                return WeltError::IllegalArgument as jint;
            }
            type SetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i64);
            let set_long_array_region: SetLongArrayRegion = std::mem::transmute(function(env, 212));
            let values: Vec<i64> = packed.into_iter().map(|value| value as i64).collect();
            set_long_array_region(env, output, 0, values.len() as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// # Safety
/// `env`, arrays, and output must be valid references for this JVM call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeUnpackArrayCube(
    env: *mut JNIEnv,
    _class: jclass,
    data: jobject,
    array_size: jint,
    bits_per_index: jint,
    palette_size: jint,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if data.is_null()
                || output.is_null()
                || array_size < 0
                || array_size as usize > 1_048_576
                || !(1..=32).contains(&bits_per_index)
                || palette_size <= 0
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let input_length = get_array_length(env, data);
            if input_length < 0 || input_length as usize > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i64);
            let get_long_array_region: GetLongArrayRegion =
                std::mem::transmute(function(env, GET_LONG_ARRAY_REGION));
            let mut raw_data = vec![0_i64; input_length as usize];
            get_long_array_region(env, data, 0, input_length, raw_data.as_mut_ptr());
            let packed: Vec<u64> = raw_data.into_iter().map(|value| value as u64).collect();
            let Ok(indexes) = unpack_indices(
                &packed,
                array_size as usize,
                bits_per_index as u32,
                palette_size as usize,
            ) else {
                return WeltError::IllegalArgument as jint;
            };
            if get_array_length(env, output) != indexes.len() as jint {
                return WeltError::IllegalArgument as jint;
            }
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let set_int_array_region: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            let values: Vec<i32> = indexes.into_iter().map(|value| value as i32).collect();
            set_int_array_region(env, output, 0, values.len() as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}

/// Fill the `SimpleTheme.getTerrain` results for an already quantised height tile.
///
/// # Safety
/// `env` and all arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillThemeTerrains(
    env: *mut JNIEnv,
    _class: jclass,
    origin_x: jint,
    origin_y: jint,
    width: jint,
    height: jint,
    min_height: jint,
    max_height: jint,
    water_height: jint,
    randomise: jint,
    beaches: jint,
    beach_ordinal: jint,
    seed: jlong,
    heights: jobject,
    terrain_ranges: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if heights.is_null()
                || terrain_ranges.is_null()
                || output.is_null()
                || width <= 0
                || height <= 0
                || min_height >= max_height
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let range_length = i64::from(max_height) - i64::from(min_height);
            if expected > 1_048_576 || range_length <= 0 || range_length > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != expected as jint
                || get_array_length(env, terrain_ranges) != range_length as jint
                || get_array_length(env, output) != expected as jint
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            let get_int_array_region: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let set_int_array_region: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            let filled = SIMPLE_THEME_TERRAIN_INPUTS.with(|workspace| {
                let mut workspace = workspace.borrow_mut();
                let SimpleThemeTerrainInputs {
                    quantised_heights,
                    terrain_range_ordinals,
                    output_ordinals,
                    theme,
                    scratch,
                } = &mut *workspace;
                quantised_heights.resize(expected, 0);
                terrain_range_ordinals.resize(range_length as usize, 0);
                get_int_array_region(
                    env,
                    heights,
                    0,
                    expected as jint,
                    quantised_heights.as_mut_ptr(),
                );
                get_int_array_region(
                    env,
                    terrain_ranges,
                    0,
                    range_length as jint,
                    terrain_range_ordinals.as_mut_ptr(),
                );
                let configured = if let Some(theme) = theme.as_mut() {
                    theme.configure(
                        min_height,
                        max_height,
                        water_height,
                        randomise != 0,
                        beaches != 0,
                        beach_ordinal,
                        seed,
                        terrain_range_ordinals,
                    )
                } else {
                    SimpleThemeTerrainBulk::new(
                        min_height,
                        max_height,
                        water_height,
                        randomise != 0,
                        beaches != 0,
                        beach_ordinal,
                        seed,
                        terrain_range_ordinals,
                    )
                    .map(|terrain| *theme = Some(terrain))
                };
                if configured.is_err() {
                    return false;
                }
                output_ordinals.resize(expected, 0);
                let Some(theme) = theme.as_ref() else {
                    return false;
                };
                if theme
                    .fill_bulk_with_scratch(
                        origin_x,
                        origin_y,
                        width as usize,
                        height as usize,
                        quantised_heights,
                        output_ordinals,
                        scratch,
                    )
                    .is_err()
                {
                    return false;
                }
                set_int_array_region(env, output, 0, expected as jint, output_ordinals.as_ptr());
                true
            });
            if filled {
                WeltError::Ok as jint
            } else {
                WeltError::IllegalArgument as jint
            }
        })
    }
}

/// Fill compact SimpleTheme terrain ordinals directly into the tile's byte plane.
///
/// # Safety
/// `env` and all arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillThemeTerrainsCompact(
    env: *mut JNIEnv,
    _class: jclass,
    origin_x: jint,
    origin_y: jint,
    width: jint,
    height: jint,
    min_height: jint,
    max_height: jint,
    water_height: jint,
    randomise: jint,
    beaches: jint,
    beach_ordinal: jint,
    seed: jlong,
    heights: jobject,
    terrain_ranges: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if heights.is_null()
                || terrain_ranges.is_null()
                || output.is_null()
                || width <= 0
                || height <= 0
                || min_height >= max_height
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let range_length = i64::from(max_height) - i64::from(min_height);
            if expected > 1_048_576 || range_length <= 0 || range_length > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetByteArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, heights) != expected as jint
                || get_array_length(env, terrain_ranges) != range_length as jint
                || get_array_length(env, output) != expected as jint
            {
                return WeltError::IllegalArgument as jint;
            }
            let get_int_array_region: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_byte_array_elements: GetByteArrayElements =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_ELEMENTS));
            let filled = SIMPLE_THEME_TERRAIN_INPUTS.with(|workspace| {
                let mut workspace = workspace.borrow_mut();
                let SimpleThemeTerrainInputs {
                    quantised_heights,
                    terrain_range_ordinals,
                    theme,
                    scratch,
                    ..
                } = &mut *workspace;
                quantised_heights.resize(expected, 0);
                terrain_range_ordinals.resize(range_length as usize, 0);
                get_int_array_region(
                    env,
                    heights,
                    0,
                    expected as jint,
                    quantised_heights.as_mut_ptr(),
                );
                get_int_array_region(
                    env,
                    terrain_ranges,
                    0,
                    range_length as jint,
                    terrain_range_ordinals.as_mut_ptr(),
                );
                let configured = if let Some(theme) = theme.as_mut() {
                    theme.configure(
                        min_height,
                        max_height,
                        water_height,
                        randomise != 0,
                        beaches != 0,
                        beach_ordinal,
                        seed,
                        terrain_range_ordinals,
                    )
                } else {
                    SimpleThemeTerrainBulk::new(
                        min_height,
                        max_height,
                        water_height,
                        randomise != 0,
                        beaches != 0,
                        beach_ordinal,
                        seed,
                        terrain_range_ordinals,
                    )
                    .map(|terrain| *theme = Some(terrain))
                };
                if configured.is_err() {
                    return false;
                }
                let Some(theme) = theme.as_ref() else {
                    return false;
                };
                let output_pointer = get_byte_array_elements(env, output, std::ptr::null_mut());
                if output_pointer.is_null() {
                    return false;
                }
                let output_values = ByteArrayOutput {
                    env,
                    array: output,
                    values: output_pointer,
                    length: expected,
                };
                let output_slice = slice::from_raw_parts_mut(
                    output_values.values.cast::<u8>(),
                    output_values.length,
                );
                theme
                    .fill_bulk_compact_with_scratch(
                        origin_x,
                        origin_y,
                        width as usize,
                        height as usize,
                        quantised_heights,
                        output_slice,
                        scratch,
                    )
                    .is_ok()
            });
            if filled {
                WeltError::Ok as jint
            } else {
                WeltError::IllegalArgument as jint
            }
        })
    }
}

/// Apply the built-in FancyTheme's terrain and layer rules to one complete tile.
///
/// # Safety
/// `env` and all arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillFancyThemeTile(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    water_height: jint,
    desert_max_height: jint,
    terrain_base: jint,
    terrain_desert: jint,
    terrain_sandstone: jint,
    terrain_bare_grass: jint,
    terrain_beaches: jint,
    terrain_dirt_and_gravel: jint,
    terrain_stone_and_gravel: jint,
    tile_heights: jobject,
    height_neighborhood: jobject,
    temperatures: jobject,
    humidities: jobject,
    forest_values: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [
                tile_heights,
                height_neighborhood,
                temperatures,
                humidities,
                forest_values,
                output,
            ]
            .iter()
            .any(|array| array.is_null())
                || width <= 0
                || height <= 0
                || width > 256
                || height > 256
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(area) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(neighborhood_width) = (width as usize).checked_add(10) else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(neighborhood_height) = (height as usize).checked_add(10) else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(neighborhood_area) = neighborhood_width.checked_mul(neighborhood_height)
            else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(output_length) = area.checked_mul(7) else {
                return WeltError::IllegalArgument as jint;
            };
            if area > 65_536 || neighborhood_area > 80_000 || output_length > 1_000_000 {
                return WeltError::IllegalArgument as jint;
            }

            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            if get_array_length(env, tile_heights) != area as jint
                || get_array_length(env, height_neighborhood) != neighborhood_area as jint
                || get_array_length(env, temperatures) != area as jint
                || get_array_length(env, humidities) != area as jint
                || get_array_length(env, forest_values) != area as jint
                || get_array_length(env, output) != output_length as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            type GetFloatArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f32;
            type GetDoubleArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut f64;
            type GetByteArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
            let get_floats: GetFloatArrayElements =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_ELEMENTS));
            let get_doubles: GetDoubleArrayElements =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_ELEMENTS));
            let get_bytes: GetByteArrayElements =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_ELEMENTS));

            let tile_pointer = get_floats(env, tile_heights, std::ptr::null_mut());
            if tile_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let tile_values = FloatArrayInput {
                env,
                array: tile_heights,
                values: tile_pointer,
                length: area,
            };
            let neighborhood_pointer = get_floats(env, height_neighborhood, std::ptr::null_mut());
            if neighborhood_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let neighborhood_values = FloatArrayInput {
                env,
                array: height_neighborhood,
                values: neighborhood_pointer,
                length: neighborhood_area,
            };
            let temperature_pointer = get_doubles(env, temperatures, std::ptr::null_mut());
            if temperature_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let temperature_values = DoubleArrayInput {
                env,
                array: temperatures,
                values: temperature_pointer,
                length: area,
            };
            let humidity_pointer = get_doubles(env, humidities, std::ptr::null_mut());
            if humidity_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let humidity_values = DoubleArrayInput {
                env,
                array: humidities,
                values: humidity_pointer,
                length: area,
            };
            let forest_pointer = get_doubles(env, forest_values, std::ptr::null_mut());
            if forest_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let forest_inputs = DoubleArrayInput {
                env,
                array: forest_values,
                values: forest_pointer,
                length: area,
            };
            let output_pointer = get_bytes(env, output, std::ptr::null_mut());
            if output_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let output_values = ByteArrayOutput {
                env,
                array: output,
                values: output_pointer,
                length: output_length,
            };
            let output_slice =
                slice::from_raw_parts_mut(output_values.values.cast::<u8>(), output_values.length);
            match fill_fancy_theme_tile(
                width as usize,
                height as usize,
                water_height,
                desert_max_height,
                terrain_base as u8,
                terrain_desert as u8,
                terrain_sandstone as u8,
                terrain_bare_grass as u8,
                terrain_beaches as u8,
                terrain_dirt_and_gravel as u8,
                terrain_stone_and_gravel as u8,
                tile_values.as_slice(),
                neighborhood_values.as_slice(),
                temperature_values.as_slice(),
                humidity_values.as_slice(),
                forest_inputs.as_slice(),
                output_slice,
            ) {
                Ok(()) => WeltError::Ok as jint,
                Err(_) => WeltError::IllegalArgument as jint,
            }
        })
    }
}

/// Fill deterministic SimpleTheme layer planes for a complete quantised tile.
///
/// # Safety
/// `env` and all arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillSimpleThemeLayerValues(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    min_height: jint,
    max_height: jint,
    first_height: jint,
    last_height: jint,
    quantised_heights: jobject,
    layer_tables: jobject,
    bit_layer_tables: jobject,
    output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if quantised_heights.is_null()
                || layer_tables.is_null()
                || bit_layer_tables.is_null()
                || output.is_null()
                || width <= 0
                || height <= 0
                || min_height >= max_height
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(area) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let height_range_i64 = i64::from(max_height) - i64::from(min_height);
            if area > 1_048_576 || height_range_i64 <= 0 || height_range_i64 > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            let height_range = height_range_i64 as usize;
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type GetObjectArrayElement =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint) -> jobject;
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type DeleteLocalRef = unsafe extern "system" fn(*mut JNIEnv, jobject);
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let get_object_array_element: GetObjectArrayElement =
                std::mem::transmute(function(env, GET_OBJECT_ARRAY_ELEMENT));
            let get_int_array_region: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let delete_local_ref: DeleteLocalRef = std::mem::transmute(function(env, 23));

            let layer_count = get_array_length(env, layer_tables);
            let bit_layer_count = get_array_length(env, bit_layer_tables);
            let total_layer_count = i64::from(layer_count) + i64::from(bit_layer_count);
            if layer_count < 0 || bit_layer_count < 0 || !(1..=64).contains(&total_layer_count) {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected_output) = area.checked_mul(total_layer_count as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(expected_layer_tables) = (layer_count as usize).checked_mul(height_range)
            else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(expected_bit_tables) = (bit_layer_count as usize).checked_mul(height_range)
            else {
                return WeltError::IllegalArgument as jint;
            };
            let output_length = get_array_length(env, output);
            if expected_output > 1_048_576
                || get_array_length(env, quantised_heights) != area as jint
                || output_length < expected_output as jint
                || output_length > 1_048_576
            {
                return WeltError::IllegalArgument as jint;
            }

            let inputs_valid = SIMPLE_THEME_LAYER_INPUTS.with(|workspace| {
                let mut inputs = workspace.borrow_mut();
                inputs.quantised_heights.resize(area, 0);
                inputs.layer_tables.resize(expected_layer_tables, 0);
                inputs.bit_layer_tables.resize(expected_bit_tables, 0);
                get_int_array_region(
                    env,
                    quantised_heights,
                    0,
                    area as jint,
                    inputs.quantised_heights.as_mut_ptr(),
                );
                for layer in 0..layer_count {
                    let row = get_object_array_element(env, layer_tables, layer);
                    if row.is_null() {
                        return false;
                    }
                    let valid_length = get_array_length(env, row) == height_range as jint;
                    if valid_length {
                        let offset = layer as usize * height_range;
                        get_int_array_region(
                            env,
                            row,
                            0,
                            height_range as jint,
                            inputs.layer_tables.as_mut_ptr().add(offset),
                        );
                    }
                    delete_local_ref(env, row);
                    if !valid_length {
                        return false;
                    }
                }
                for layer in 0..bit_layer_count {
                    let row = get_object_array_element(env, bit_layer_tables, layer);
                    if row.is_null() {
                        return false;
                    }
                    let valid_length = get_array_length(env, row) == height_range as jint;
                    if valid_length {
                        let offset = layer as usize * height_range;
                        get_int_array_region(
                            env,
                            row,
                            0,
                            height_range as jint,
                            inputs.bit_layer_tables.as_mut_ptr().add(offset),
                        );
                    }
                    delete_local_ref(env, row);
                    if !valid_length {
                        return false;
                    }
                }
                true
            });
            if !inputs_valid {
                return WeltError::IllegalArgument as jint;
            }

            type GetByteArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
            let get_byte_array_elements: GetByteArrayElements =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_ELEMENTS));
            let output_pointer = get_byte_array_elements(env, output, std::ptr::null_mut());
            if output_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let output_values = ByteArrayOutput {
                env,
                array: output,
                values: output_pointer,
                length: output_length as usize,
            };
            let result = SIMPLE_THEME_LAYER_INPUTS.with(|workspace| {
                let inputs = workspace.borrow();
                let output_slice = slice::from_raw_parts_mut(
                    output_values.values.cast::<u8>(),
                    output_values.length,
                );
                fill_simple_theme_layers(
                    min_height,
                    max_height,
                    first_height,
                    last_height,
                    width as usize,
                    height as usize,
                    &inputs.quantised_heights,
                    layer_count as usize,
                    &inputs.layer_tables,
                    bit_layer_count as usize,
                    &inputs.bit_layer_tables,
                    output_slice,
                )
            });
            if result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Convert precomputed Java random draws into SimpleTheme bit-layer planes in place.
///
/// # Safety
/// `env` and all arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillSimpleThemeRandomBitLayers(
    env: *mut JNIEnv,
    _class: jclass,
    width: jint,
    height: jint,
    min_height: jint,
    max_height: jint,
    quantised_heights: jobject,
    bit_layer_tables: jobject,
    rolls_and_output: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if quantised_heights.is_null()
                || bit_layer_tables.is_null()
                || rolls_and_output.is_null()
                || width <= 0
                || height <= 0
                || min_height >= max_height
            {
                return WeltError::IllegalArgument as jint;
            }
            let Some(area) = (width as usize).checked_mul(height as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let height_range_i64 = i64::from(max_height) - i64::from(min_height);
            if area > 1_048_576 || height_range_i64 <= 0 || height_range_i64 > 1_048_576 {
                return WeltError::IllegalArgument as jint;
            }
            let height_range = height_range_i64 as usize;
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type GetObjectArrayElement =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint) -> jobject;
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type DeleteLocalRef = unsafe extern "system" fn(*mut JNIEnv, jobject);
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let get_object_array_element: GetObjectArrayElement =
                std::mem::transmute(function(env, GET_OBJECT_ARRAY_ELEMENT));
            let get_int_array_region: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let delete_local_ref: DeleteLocalRef = std::mem::transmute(function(env, 23));

            let bit_layer_count = get_array_length(env, bit_layer_tables);
            if !(1..=64).contains(&bit_layer_count) {
                return WeltError::IllegalArgument as jint;
            }
            let Some(expected_output) = area.checked_mul(bit_layer_count as usize) else {
                return WeltError::IllegalArgument as jint;
            };
            let Some(expected_tables) = (bit_layer_count as usize).checked_mul(height_range) else {
                return WeltError::IllegalArgument as jint;
            };
            if expected_output > 1_048_576
                || get_array_length(env, quantised_heights) != area as jint
                || get_array_length(env, rolls_and_output) < expected_output as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            let inputs_valid = SIMPLE_THEME_LAYER_INPUTS.with(|workspace| {
                let mut inputs = workspace.borrow_mut();
                inputs.quantised_heights.resize(area, 0);
                inputs.layer_tables.clear();
                inputs.bit_layer_tables.resize(expected_tables, 0);
                get_int_array_region(
                    env,
                    quantised_heights,
                    0,
                    area as jint,
                    inputs.quantised_heights.as_mut_ptr(),
                );
                for layer in 0..bit_layer_count {
                    let row = get_object_array_element(env, bit_layer_tables, layer);
                    if row.is_null() {
                        return false;
                    }
                    let valid_length = get_array_length(env, row) == height_range as jint;
                    if valid_length {
                        let offset = layer as usize * height_range;
                        get_int_array_region(
                            env,
                            row,
                            0,
                            height_range as jint,
                            inputs.bit_layer_tables.as_mut_ptr().add(offset),
                        );
                    }
                    delete_local_ref(env, row);
                    if !valid_length {
                        return false;
                    }
                }
                true
            });
            if !inputs_valid {
                return WeltError::IllegalArgument as jint;
            }

            type GetByteArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
            let get_byte_array_elements: GetByteArrayElements =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_ELEMENTS));
            let output_pointer =
                get_byte_array_elements(env, rolls_and_output, std::ptr::null_mut());
            if output_pointer.is_null() {
                return WeltError::Internal as jint;
            }
            let output_values = ByteArrayOutput {
                env,
                array: rolls_and_output,
                values: output_pointer,
                length: expected_output,
            };
            let result = SIMPLE_THEME_LAYER_INPUTS.with(|workspace| {
                let inputs = workspace.borrow();
                let output_slice = slice::from_raw_parts_mut(
                    output_values.values.cast::<u8>(),
                    output_values.length,
                );
                fill_simple_theme_random_bit_layers(
                    min_height,
                    max_height,
                    width as usize,
                    height as usize,
                    &inputs.quantised_heights,
                    bit_layer_count as usize,
                    &inputs.bit_layer_tables,
                    output_slice,
                )
            });
            if result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            WeltError::Ok as jint
        })
    }
}

/// Find the first matching Resources material per eligible block. Material
/// changes remain in Java so platform and deepslate behavior stays unchanged.
///
/// # Safety
/// `env` and all arrays must be valid references supplied by the current JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFillResourceMaterials(
    env: *mut JNIEnv,
    _class: jclass,
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
) -> jint {
    unsafe {
        jni_catch(env, || {
            let arrays = [
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
            ];
            if arrays.iter().any(|array| array.is_null()) || min_z > max_z {
                return WeltError::IllegalArgument as jint;
            }
            let height = i64::from(max_z) - i64::from(min_z) + 1;
            if height <= 0 || height > 4096 {
                return WeltError::IllegalArgument as jint;
            }
            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            let get_array_length: GetArrayLength =
                std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let columns = get_array_length(env, tiny_x);
            let materials = get_array_length(env, seeds);
            if !(1..=256).contains(&columns) || !(0..=64).contains(&materials) {
                return WeltError::IllegalArgument as jint;
            }
            if !profile_nanos.is_null() && get_array_length(env, profile_nanos) != 5 {
                return WeltError::IllegalArgument as jint;
            }
            let expected_output = i64::from(columns) * height;
            if expected_output > 1_048_576
                || [
                    tiny_y,
                    dirt_x,
                    dirt_y,
                    column_min_z,
                    column_max_z,
                    resource_values,
                ]
                .iter()
                .any(|&array| get_array_length(env, array) != columns)
                || get_array_length(env, material_min_z) != materials
                || get_array_length(env, material_max_z) != materials
                || get_array_length(env, dirt_materials) != materials
                || i64::from(get_array_length(env, chances)) != i64::from(materials) * 16
                || i64::from(get_array_length(env, output)) != expected_output
            {
                return WeltError::IllegalArgument as jint;
            }
            type GetDoubleArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f64);
            type GetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i32);
            type GetLongArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i64);
            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            type GetFloatArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut f32);
            let get_double: GetDoubleArrayRegion =
                std::mem::transmute(function(env, GET_DOUBLE_ARRAY_REGION));
            let get_int: GetIntArrayRegion =
                std::mem::transmute(function(env, GET_INT_ARRAY_REGION));
            let get_long: GetLongArrayRegion =
                std::mem::transmute(function(env, GET_LONG_ARRAY_REGION));
            let get_byte: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let get_float: GetFloatArrayRegion =
                std::mem::transmute(function(env, GET_FLOAT_ARRAY_REGION));
            let chance_count = materials as usize * 16;
            type GetByteArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
            let get_byte_elements: GetByteArrayElements =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_ELEMENTS));
            let copies_start = std::time::Instant::now();
            let output_ptr = get_byte_elements(env, output, std::ptr::null_mut());
            if output_ptr.is_null() {
                return WeltError::Internal as jint;
            }
            let mut output_values = ByteArrayOutput {
                env,
                array: output,
                values: output_ptr,
                length: expected_output as usize,
            };
            let (result, copy_nanos, kernel_nanos, kernel_detail) =
                RESOURCE_NOISE_INPUTS.with(|workspace| {
                    let mut values = workspace.borrow_mut();
                    let mut kernel_detail = [0_u64; 3];
                    values.tiny_x.resize(columns as usize, 0.0);
                    values.tiny_y.resize(columns as usize, 0.0);
                    values.dirt_x.resize(columns as usize, 0.0);
                    values.dirt_y.resize(columns as usize, 0.0);
                    values.column_min_z.resize(columns as usize, 0);
                    values.column_max_z.resize(columns as usize, 0);
                    values.resource_values.resize(columns as usize, 0);
                    values.seeds.resize(materials as usize, 0);
                    values.material_min_z.resize(materials as usize, 0);
                    values.material_max_z.resize(materials as usize, 0);
                    values.raw_dirt_materials.resize(materials as usize, 0);
                    values.dirt_materials.resize(materials as usize, 0);
                    values.chances.resize(chance_count, 0.0);
                    get_double(env, tiny_x, 0, columns, values.tiny_x.as_mut_ptr());
                    get_double(env, tiny_y, 0, columns, values.tiny_y.as_mut_ptr());
                    get_double(env, dirt_x, 0, columns, values.dirt_x.as_mut_ptr());
                    get_double(env, dirt_y, 0, columns, values.dirt_y.as_mut_ptr());
                    get_int(
                        env,
                        column_min_z,
                        0,
                        columns,
                        values.column_min_z.as_mut_ptr(),
                    );
                    get_int(
                        env,
                        column_max_z,
                        0,
                        columns,
                        values.column_max_z.as_mut_ptr(),
                    );
                    get_int(
                        env,
                        resource_values,
                        0,
                        columns,
                        values.resource_values.as_mut_ptr(),
                    );
                    get_int(
                        env,
                        material_min_z,
                        0,
                        materials,
                        values.material_min_z.as_mut_ptr(),
                    );
                    get_int(
                        env,
                        material_max_z,
                        0,
                        materials,
                        values.material_max_z.as_mut_ptr(),
                    );
                    get_long(env, seeds, 0, materials, values.seeds.as_mut_ptr());
                    get_byte(
                        env,
                        dirt_materials,
                        0,
                        materials,
                        values.raw_dirt_materials.as_mut_ptr(),
                    );
                    get_float(
                        env,
                        chances,
                        0,
                        chance_count as jint,
                        values.chances.as_mut_ptr(),
                    );
                    for index in 0..materials as usize {
                        values.dirt_materials[index] = values.raw_dirt_materials[index] as u8;
                    }
                    let copy_nanos = copies_start.elapsed().as_nanos().min(i64::MAX as u128) as i64;
                    let kernel_start = std::time::Instant::now();
                    let result = fill_resource_materials_into(
                        min_z,
                        max_z,
                        &values.tiny_x,
                        &values.tiny_y,
                        &values.dirt_x,
                        &values.dirt_y,
                        &values.column_min_z,
                        &values.column_max_z,
                        &values.resource_values,
                        &values.seeds,
                        &values.material_min_z,
                        &values.material_max_z,
                        &values.dirt_materials,
                        &values.chances,
                        output_values.as_mut_slice(),
                        if profile_nanos.is_null() {
                            None
                        } else {
                            Some(&mut kernel_detail)
                        },
                    );
                    let kernel_nanos =
                        kernel_start.elapsed().as_nanos().min(i64::MAX as u128) as i64;
                    (result, copy_nanos, kernel_nanos, kernel_detail)
                });
            if result.is_err() {
                return WeltError::IllegalArgument as jint;
            }
            drop(output_values);
            if !profile_nanos.is_null() {
                type SetLongArrayRegion =
                    unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i64);
                let set_long: SetLongArrayRegion =
                    std::mem::transmute(function(env, SET_LONG_ARRAY_REGION));
                let timings = [
                    copy_nanos,
                    kernel_nanos,
                    kernel_detail[0] as i64,
                    kernel_detail[1] as i64,
                    kernel_detail[2] as i64,
                ];
                set_long(
                    env,
                    profile_nanos,
                    0,
                    timings.len() as jint,
                    timings.as_ptr(),
                );
            }
            WeltError::Ok as jint
        })
    }
}
