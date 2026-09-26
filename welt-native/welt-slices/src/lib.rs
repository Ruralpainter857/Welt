//! JNI entry points for bulk Welt kernels. The Java application loads this
//! library only when a native feature flag is enabled.

use std::ffi::c_void;
use welt_core::error::WeltError;
use welt_core::jni::{jclass, jint, jlong, jni_catch, jobject, JNIEnv};
use welt_export::frost::{apply_frost_column, FrostCell, FrostMode, FrostSettings, FrostUpdate};
use welt_gen::noise_height_map::NoiseHeightMapBulk;
use welt_gen::theme_terrain::SimpleThemeTerrainBulk;

// JNI 17 function-table indices, checked against the JDK's jni.h. The first
// four entries are reserved; GetArrayLength is 171 and SetDoubleArrayRegion
// is 214. Keeping these calls here avoids a third-party JNI dependency.
const GET_ARRAY_LENGTH: usize = 171;
const SET_DOUBLE_ARRAY_REGION: usize = 214;
const GET_INT_ARRAY_REGION: usize = 203;
const SET_INT_ARRAY_REGION: usize = 211;
const GET_BYTE_ARRAY_REGION: usize = 200;
const SET_BYTE_ARRAY_REGION: usize = 208;

const SLICES_ABI_VERSION: jint = 1;

/// # Safety
/// `env` must be supplied by the JVM for the current native call.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeAbiVersion(
    env: *mut JNIEnv,
    _class: jclass,
) -> jint {
    unsafe { jni_catch(env, || SLICES_ABI_VERSION) }
}

/// # Safety
/// `env` must be the JNI environment supplied to this native method.
unsafe fn function(env: *mut JNIEnv, index: usize) -> *const c_void {
    unsafe { *(*env).functions.cast::<*const c_void>().add(index) }
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
            let mut values = vec![0.0; expected];
            if map
                .fill_bulk(
                    origin_x,
                    origin_y,
                    width as usize,
                    height as usize,
                    &mut values,
                )
                .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            type SetDoubleArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const f64);
            let set_double_array_region: SetDoubleArrayRegion =
                std::mem::transmute(function(env, SET_DOUBLE_ARRAY_REGION));
            set_double_array_region(env, output, 0, expected as jint, values.as_ptr());
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
            let mut input_heights = vec![0_i32; expected];
            let mut input_ranges = vec![0_i32; range_length as usize];
            get_int_array_region(
                env,
                heights,
                0,
                expected as jint,
                input_heights.as_mut_ptr(),
            );
            get_int_array_region(
                env,
                terrain_ranges,
                0,
                range_length as jint,
                input_ranges.as_mut_ptr(),
            );
            let Ok(theme) = SimpleThemeTerrainBulk::new(
                min_height,
                max_height,
                water_height,
                randomise != 0,
                beaches != 0,
                beach_ordinal,
                seed,
                &input_ranges,
            ) else {
                return WeltError::IllegalArgument as jint;
            };
            let mut values = vec![0_i32; expected];
            if theme
                .fill_bulk(
                    origin_x,
                    origin_y,
                    width as usize,
                    height as usize,
                    &input_heights,
                    &mut values,
                )
                .is_err()
            {
                return WeltError::IllegalArgument as jint;
            }
            type SetIntArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i32);
            let set_int_array_region: SetIntArrayRegion =
                std::mem::transmute(function(env, SET_INT_ARRAY_REGION));
            set_int_array_region(env, output, 0, expected as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}
