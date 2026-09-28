//! Batched detection of fluid update ticks for a modern chunk palette.

use super::*;

const ANY_WATER: u8 = 1;
const CONTAINS_WATER: u8 = 2;
const LAVA: u8 = 4;
const SOLID: u8 = 8;
const MAX_SECTIONS: usize = 256;

struct PinnedIndexes {
    env: *mut JNIEnv,
    array: jobject,
    values: *mut i32,
}

impl PinnedIndexes {
    fn as_slice(&self) -> &[i32] {
        unsafe { slice::from_raw_parts(self.values, 4096) }
    }
}

impl Drop for PinnedIndexes {
    fn drop(&mut self) {
        unsafe {
            type ReleaseIntArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut i32, jint);
            type DeleteLocalRef = unsafe extern "system" fn(*mut JNIEnv, jobject);
            let release: ReleaseIntArrayElements =
                std::mem::transmute(function(self.env, RELEASE_INT_ARRAY_ELEMENTS));
            let delete: DeleteLocalRef = std::mem::transmute(function(self.env, 23));
            release(self.env, self.array, self.values, JNI_ABORT);
            delete(self.env, self.array);
        }
    }
}

struct PaletteSection {
    indexes: PinnedIndexes,
    flags: Vec<u8>,
}

fn palette_state(
    sections: &[PaletteSection],
    section_min_y: i32,
    x: usize,
    z: usize,
    y: i32,
) -> Result<u8, ()> {
    let relative_y = y.checked_sub(section_min_y).ok_or(())?;
    if relative_y < 0 {
        return Err(());
    }
    let section_index = (relative_y as usize) >> 4;
    let section = sections.get(section_index).ok_or(())?;
    let local_y = (relative_y as usize) & 15;
    let offset = x | (z << 4) | (local_y << 8);
    let palette_index = *section.indexes.as_slice().get(offset).ok_or(())?;
    if palette_index < 0 {
        return Err(());
    }
    section.flags.get(palette_index as usize).copied().ok_or(())
}

fn water_contained(material_below: u8, north: u8, east: u8, south: u8, west: u8) -> bool {
    if material_below & ANY_WATER != 0 {
        true
    } else if material_below & (CONTAINS_WATER | SOLID) == 0 {
        false
    } else {
        [north, east, south, west]
            .iter()
            .all(|state| state & (ANY_WATER | SOLID) != 0)
    }
}

fn lava_contained(material_below: u8, north: u8, east: u8, south: u8, west: u8) -> bool {
    if material_below & LAVA != 0 {
        true
    } else if material_below & SOLID == 0 {
        false
    } else {
        [north, east, south, west]
            .iter()
            .all(|state| state & (LAVA | SOLID) != 0)
    }
}

/// Finds the same ordered update list as Java's fluid pass, reading current and neighbouring chunk palettes.
///
/// # Safety
/// JNI references and `env` must belong to the active JVM frame.
#[no_mangle]
pub unsafe extern "system" fn Java_org_pepsoft_worldpainter_nativeapi_NativeSlices_nativeFindFluidUpdatesForChunk(
    env: *mut JNIEnv,
    _class: jclass,
    min_y: jint,
    max_y: jint,
    world_min_y: jint,
    world_max_y: jint,
    section_min_y: jint,
    section_count: jint,
    flow_water: jint,
    flow_lava: jint,
    section_indexes: jobject,
    palette_flags: jobject,
    west_edge: jobject,
    east_edge: jobject,
    north_edge: jobject,
    south_edge: jobject,
    updates: jobject,
) -> jint {
    unsafe {
        jni_catch(env, || {
            if [
                section_indexes,
                palette_flags,
                west_edge,
                east_edge,
                north_edge,
                south_edge,
                updates,
            ]
            .iter()
            .any(|value| value.is_null())
                || min_y > max_y
                || (section_min_y & 15) != 0
                || section_count <= 0
                || section_count as usize > MAX_SECTIONS
                || (flow_water == 0 && flow_lava == 0)
                || world_min_y > world_max_y
                || min_y < world_min_y
                || max_y > world_max_y
                || min_y < section_min_y
                || i64::from(max_y) >= i64::from(section_min_y) + i64::from(section_count) * 16
            {
                return WeltError::IllegalArgument as jint;
            }
            let height_i64 = i64::from(max_y) - i64::from(min_y) + 1;
            if !(1..=4096).contains(&height_i64) {
                return WeltError::IllegalArgument as jint;
            }
            let height = height_i64 as usize;
            let edge_length = height.checked_mul(16).unwrap_or(usize::MAX);
            let output_length = height.checked_mul(256).unwrap_or(usize::MAX);

            type GetArrayLength = unsafe extern "system" fn(*mut JNIEnv, jobject) -> jint;
            type GetObjectArrayElement =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint) -> jobject;
            type GetIntArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i32;
            type GetByteArrayRegion =
                unsafe extern "system" fn(*mut JNIEnv, jobject, jint, jint, *mut i8);
            type GetByteArrayElements =
                unsafe extern "system" fn(*mut JNIEnv, jobject, *mut u8) -> *mut i8;
            type DeleteLocalRef = unsafe extern "system" fn(*mut JNIEnv, jobject);

            let get_length: GetArrayLength = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
            let get_object: GetObjectArrayElement =
                std::mem::transmute(function(env, GET_OBJECT_ARRAY_ELEMENT));
            let get_int_elements: GetIntArrayElements =
                std::mem::transmute(function(env, GET_INT_ARRAY_ELEMENTS));
            let get_byte_region: GetByteArrayRegion =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_REGION));
            let get_byte_elements: GetByteArrayElements =
                std::mem::transmute(function(env, GET_BYTE_ARRAY_ELEMENTS));
            let delete_local_ref: DeleteLocalRef = std::mem::transmute(function(env, 23));

            if get_length(env, section_indexes) < section_count
                || get_length(env, palette_flags) < section_count
                || [west_edge, east_edge, north_edge, south_edge]
                    .iter()
                    .any(|&array| get_length(env, array) < edge_length as jint)
                || get_length(env, updates) < output_length as jint
            {
                return WeltError::IllegalArgument as jint;
            }

            let mut sections = Vec::with_capacity(section_count as usize);
            for section in 0..section_count {
                let indexes_array = get_object(env, section_indexes, section);
                let flags_array = get_object(env, palette_flags, section);
                if indexes_array.is_null() || flags_array.is_null() {
                    if !indexes_array.is_null() {
                        delete_local_ref(env, indexes_array);
                    }
                    if !flags_array.is_null() {
                        delete_local_ref(env, flags_array);
                    }
                    return WeltError::IllegalArgument as jint;
                }
                let flags_length = get_length(env, flags_array);
                if get_length(env, indexes_array) != 4096
                    || flags_length <= 0
                    || flags_length > 65_536
                {
                    delete_local_ref(env, indexes_array);
                    delete_local_ref(env, flags_array);
                    return WeltError::IllegalArgument as jint;
                }
                let mut flags = vec![0_i8; flags_length as usize];
                get_byte_region(env, flags_array, 0, flags_length, flags.as_mut_ptr());
                delete_local_ref(env, flags_array);
                let indexes = get_int_elements(env, indexes_array, std::ptr::null_mut());
                if indexes.is_null() {
                    delete_local_ref(env, indexes_array);
                    return WeltError::Internal as jint;
                }
                sections.push(PaletteSection {
                    indexes: PinnedIndexes {
                        env,
                        array: indexes_array,
                        values: indexes,
                    },
                    flags: flags.into_iter().map(|flag| flag as u8).collect(),
                });
            }

            let mut edges = Vec::with_capacity(4);
            for edge in [west_edge, east_edge, north_edge, south_edge] {
                let pointer = get_byte_elements(env, edge, std::ptr::null_mut());
                if pointer.is_null() {
                    return WeltError::Internal as jint;
                }
                edges.push(ReadOnlyByteArray {
                    env,
                    array: edge,
                    values: pointer,
                    length: edge_length,
                });
            }
            let output_values = get_byte_elements(env, updates, std::ptr::null_mut());
            if output_values.is_null() {
                return WeltError::Internal as jint;
            }
            let mut output = ByteArrayOutput {
                env,
                array: updates,
                values: output_values,
                length: output_length,
            };

            let edge_values: [&[i8]; 4] = [
                edges[0].as_slice(),
                edges[1].as_slice(),
                edges[2].as_slice(),
                edges[3].as_slice(),
            ];
            let mut marks = vec![0_i8; output_length];
            for x in 0..16_usize {
                for z in 0..16_usize {
                    let mut below = match palette_state(&sections, section_min_y, x, z, min_y) {
                        Ok(state) => state,
                        Err(()) => return WeltError::IllegalArgument as jint,
                    };
                    for y in (min_y + 1)..=max_y {
                        let material = match palette_state(&sections, section_min_y, x, z, y) {
                            Ok(state) => state,
                            Err(()) => return WeltError::IllegalArgument as jint,
                        };
                        let edge_offset = (y - min_y) as usize * 16;
                        let west = if x == 0 {
                            edge_values[0][edge_offset + z] as u8
                        } else {
                            match palette_state(&sections, section_min_y, x - 1, z, y) {
                                Ok(state) => state,
                                Err(()) => return WeltError::IllegalArgument as jint,
                            }
                        };
                        let east = if x == 15 {
                            edge_values[1][edge_offset + z] as u8
                        } else {
                            match palette_state(&sections, section_min_y, x + 1, z, y) {
                                Ok(state) => state,
                                Err(()) => return WeltError::IllegalArgument as jint,
                            }
                        };
                        let north = if z == 0 {
                            edge_values[2][edge_offset + x] as u8
                        } else {
                            match palette_state(&sections, section_min_y, x, z - 1, y) {
                                Ok(state) => state,
                                Err(()) => return WeltError::IllegalArgument as jint,
                            }
                        };
                        let south = if z == 15 {
                            edge_values[3][edge_offset + x] as u8
                        } else {
                            match palette_state(&sections, section_min_y, x, z + 1, y) {
                                Ok(state) => state,
                                Err(()) => return WeltError::IllegalArgument as jint,
                            }
                        };
                        let update = (flow_water != 0
                            && material & ANY_WATER != 0
                            && !water_contained(below, north, east, south, west))
                            || (flow_lava != 0
                                && material & LAVA != 0
                                && !lava_contained(below, north, east, south, west));
                        if update {
                            let column = x * 16 + z;
                            marks[column * height + (y - min_y) as usize] = 1;
                        }
                        below = material;
                    }
                }
            }
            output.as_mut_slice().copy_from_slice(&marks);
            WeltError::Ok as jint
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fluid_containment_keeps_java_precedence() {
        assert!(water_contained(ANY_WATER, 0, 0, 0, 0));
        assert!(!water_contained(0, SOLID, 0, SOLID, SOLID));
        assert!(water_contained(SOLID, SOLID, ANY_WATER, SOLID, SOLID));
        assert!(lava_contained(LAVA, 0, 0, 0, 0));
        assert!(!lava_contained(0, SOLID, 0, SOLID, SOLID));
        assert!(lava_contained(SOLID, SOLID, LAVA, SOLID, SOLID));
    }
}
