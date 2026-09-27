//! JNI entry points for bulk Welt kernels. The Java application loads this
//! library only when a native feature flag is enabled.

use std::ffi::c_void;
use std::slice;
use welt_core::error::WeltError;
use welt_core::jni::{jclass, jint, jlong, jni_catch, jobject, JNIEnv};
use welt_export::edge_distance::bake_edge_distances;
use welt_export::edge_height::bake_edge_heights;
use welt_export::frost::{
    apply_frost_column, apply_frost_packed_columns, FrostCell, FrostMode, FrostSettings,
    FrostUpdate,
};
use welt_gen::height_map_tree::{fill_height_map_tree, HeightMapNode, MAX_PROGRAM_NODES};
use welt_gen::noise_height_map::NoiseHeightMapBulk;
use welt_gen::resource_noise::fill_resource_materials;
use welt_gen::theme_terrain::SimpleThemeTerrainBulk;
use welt_nbt::packed_array::{pack_indices, unpack_indices};
use welt_render::shade::shade_pixels;

// JNI function-table indices, checked against the JDK's jni.h. The first four
// entries are reserved; GetArrayLength is 171, GetDoubleArrayElements is 190,
// and ReleaseDoubleArrayElements is 198. Keeping calls here avoids a JNI crate.
const GET_ARRAY_LENGTH: usize = 171;
const GET_INT_ARRAY_REGION: usize = 203;
const GET_LONG_ARRAY_REGION: usize = 204;
const GET_FLOAT_ARRAY_REGION: usize = 205;
const GET_DOUBLE_ARRAY_REGION: usize = 206;
const GET_DOUBLE_ARRAY_ELEMENTS: usize = 190;
const RELEASE_DOUBLE_ARRAY_ELEMENTS: usize = 198;
const SET_INT_ARRAY_REGION: usize = 211;
const GET_BYTE_ARRAY_REGION: usize = 200;
const SET_BYTE_ARRAY_REGION: usize = 208;
const SET_FLOAT_ARRAY_REGION: usize = 213;

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
                    8 => HeightMapNode::Mandelbrot,
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
            let mut tiny_x_values = vec![0.0; columns as usize];
            let mut tiny_y_values = vec![0.0; columns as usize];
            let mut dirt_x_values = vec![0.0; columns as usize];
            let mut dirt_y_values = vec![0.0; columns as usize];
            let mut column_min_values = vec![0; columns as usize];
            let mut column_max_values = vec![0; columns as usize];
            let mut resource_value_values = vec![0; columns as usize];
            let mut seed_values = vec![0_i64; materials as usize];
            let mut material_min_values = vec![0; materials as usize];
            let mut material_max_values = vec![0; materials as usize];
            let mut raw_dirt_values = vec![0_i8; materials as usize];
            let chance_count = materials as usize * 16;
            let mut chance_values = vec![0.0_f32; chance_count];
            for (array, values) in [
                (tiny_x, &mut tiny_x_values),
                (tiny_y, &mut tiny_y_values),
                (dirt_x, &mut dirt_x_values),
                (dirt_y, &mut dirt_y_values),
            ] {
                get_double(env, array, 0, columns, values.as_mut_ptr());
            }
            for (array, values) in [
                (column_min_z, &mut column_min_values),
                (column_max_z, &mut column_max_values),
                (resource_values, &mut resource_value_values),
                (material_min_z, &mut material_min_values),
                (material_max_z, &mut material_max_values),
            ] {
                get_int(env, array, 0, values.len() as jint, values.as_mut_ptr());
            }
            get_long(env, seeds, 0, materials, seed_values.as_mut_ptr());
            get_byte(
                env,
                dirt_materials,
                0,
                materials,
                raw_dirt_values.as_mut_ptr(),
            );
            get_float(
                env,
                chances,
                0,
                chance_count as jint,
                chance_values.as_mut_ptr(),
            );
            let dirt_values: Vec<u8> = raw_dirt_values
                .into_iter()
                .map(|value| value as u8)
                .collect();
            let Ok(values) = fill_resource_materials(
                min_z,
                max_z,
                &tiny_x_values,
                &tiny_y_values,
                &dirt_x_values,
                &dirt_y_values,
                &column_min_values,
                &column_max_values,
                &resource_value_values,
                &seed_values,
                &material_min_values,
                &material_max_values,
                &dirt_values,
                &chance_values,
            ) else {
                return WeltError::IllegalArgument as jint;
            };
            type SetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *const i8);
            let set_byte: SetByteArrayRegion =
                std::mem::transmute(function(env, SET_BYTE_ARRAY_REGION));
            set_byte(env, output, 0, values.len() as jint, values.as_ptr());
            WeltError::Ok as jint
        })
    }
}
