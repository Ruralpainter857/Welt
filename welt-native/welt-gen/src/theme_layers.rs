//! Bulk deterministic layer-value mapping for a freshly generated tile.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimpleThemeLayersError {
    InvalidHeightRange,
    HeightRangeTooLarge,
    HeightLength { expected: usize, actual: usize },
    LayerTableLength { expected: usize, actual: usize },
    BitLayerTableLength { expected: usize, actual: usize },
    OutputLength { expected: usize, actual: usize },
    InvalidLayerValue,
    RandomBitLayer,
    AreaOverflow,
}

/// Writes layer-major values for all cells, preserving the Java table lookup rules.
/// Bit layers are accepted only when their levels are deterministic (0 or 15).
#[allow(clippy::too_many_arguments)]
pub fn fill_simple_theme_layers(
    min_height: i32,
    max_height: i32,
    first_height: i32,
    last_height: i32,
    width: usize,
    height: usize,
    quantised_heights: &[i32],
    layer_count: usize,
    layer_tables: &[i32],
    bit_layer_count: usize,
    bit_layer_tables: &[i32],
    output: &mut [u8],
) -> Result<(), SimpleThemeLayersError> {
    if min_height >= max_height {
        return Err(SimpleThemeLayersError::InvalidHeightRange);
    }
    let height_range = usize::try_from(i64::from(max_height) - i64::from(min_height))
        .map_err(|_| SimpleThemeLayersError::HeightRangeTooLarge)?;
    if height_range > 1_048_576 {
        return Err(SimpleThemeLayersError::HeightRangeTooLarge);
    }
    let area = width
        .checked_mul(height)
        .ok_or(SimpleThemeLayersError::AreaOverflow)?;
    if quantised_heights.len() != area {
        return Err(SimpleThemeLayersError::HeightLength {
            expected: area,
            actual: quantised_heights.len(),
        });
    }
    let expected_layers = layer_count
        .checked_mul(height_range)
        .ok_or(SimpleThemeLayersError::AreaOverflow)?;
    if layer_tables.len() != expected_layers {
        return Err(SimpleThemeLayersError::LayerTableLength {
            expected: expected_layers,
            actual: layer_tables.len(),
        });
    }
    let expected_bit_layers = bit_layer_count
        .checked_mul(height_range)
        .ok_or(SimpleThemeLayersError::AreaOverflow)?;
    if bit_layer_tables.len() != expected_bit_layers {
        return Err(SimpleThemeLayersError::BitLayerTableLength {
            expected: expected_bit_layers,
            actual: bit_layer_tables.len(),
        });
    }
    let output_count = area
        .checked_mul(
            layer_count
                .checked_add(bit_layer_count)
                .ok_or(SimpleThemeLayersError::AreaOverflow)?,
        )
        .ok_or(SimpleThemeLayersError::AreaOverflow)?;
    if output.len() < output_count {
        return Err(SimpleThemeLayersError::OutputLength {
            expected: output_count,
            actual: output.len(),
        });
    }

    let first = first_height.clamp(min_height, max_height - 1);
    let last = last_height.clamp(min_height, max_height - 1);
    if first > last {
        return Err(SimpleThemeLayersError::InvalidHeightRange);
    }
    let first_index = (first - min_height) as usize;
    let last_index = (last - min_height) as usize;

    for layer in 0..layer_count {
        let table_start = layer * height_range;
        let table = &layer_tables[table_start..table_start + height_range];
        if table[first_index..=last_index]
            .iter()
            .any(|value| !(0..=255).contains(value))
        {
            return Err(SimpleThemeLayersError::InvalidLayerValue);
        }
    }
    for layer in 0..bit_layer_count {
        let table_start = layer * height_range;
        let table = &bit_layer_tables[table_start..table_start + height_range];
        if table[first_index..=last_index]
            .iter()
            .any(|value| !(0..=15).contains(value))
        {
            return Err(SimpleThemeLayersError::InvalidLayerValue);
        }
        if table[first_index..=last_index]
            .iter()
            .any(|value| *value != 0 && *value != 15)
        {
            return Err(SimpleThemeLayersError::RandomBitLayer);
        }
    }

    for layer in 0..layer_count {
        let table_start = layer * height_range;
        let table = &layer_tables[table_start..table_start + height_range];
        let output_start = layer * area;
        for (index, quantised_height) in quantised_heights.iter().copied().enumerate() {
            let clamped = quantised_height.clamp(min_height, max_height - 1);
            output[output_start + index] = table[(clamped - min_height) as usize] as u8;
        }
    }
    for layer in 0..bit_layer_count {
        let table_start = layer * height_range;
        let table = &bit_layer_tables[table_start..table_start + height_range];
        let output_start = (layer_count + layer) * area;
        for (index, quantised_height) in quantised_heights.iter().copied().enumerate() {
            let clamped = quantised_height.clamp(min_height, max_height - 1);
            output[output_start + index] = u8::from(table[(clamped - min_height) as usize] == 15);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{fill_simple_theme_layers, SimpleThemeLayersError};

    #[test]
    fn fills_value_and_deterministic_bit_layers_for_clamped_heights() {
        let heights = [-9, -2, -1, 0, 1, 2, 3, 99];
        let values = [1, 2, 3, 4, 5, 6, 7, 8];
        let bits = [0, 0, 15, 15];
        let mut output = [0xff; 24];
        fill_simple_theme_layers(
            -2,
            2,
            -9,
            99,
            4,
            2,
            &heights,
            2,
            &values,
            1,
            &bits,
            &mut output,
        )
        .unwrap();
        assert_eq!(&output[..8], &[1, 1, 2, 3, 4, 4, 4, 4]);
        assert_eq!(&output[8..16], &[5, 5, 6, 7, 8, 8, 8, 8]);
        assert_eq!(&output[16..], &[0, 0, 0, 1, 1, 1, 1, 1]);
    }

    #[test]
    fn rejects_random_bit_layers_without_mutating_output() {
        let mut output = [0xaa; 2];
        assert_eq!(
            fill_simple_theme_layers(0, 2, 0, 1, 2, 1, &[0, 1], 0, &[], 1, &[0, 7], &mut output),
            Err(SimpleThemeLayersError::RandomBitLayer)
        );
        assert_eq!(output, [0xaa; 2]);
    }

    #[test]
    fn validates_shapes_and_layer_values_before_writing() {
        let mut output = [0xaa; 2];
        assert_eq!(
            fill_simple_theme_layers(0, 2, 0, 1, 2, 1, &[0], 0, &[], 0, &[], &mut output),
            Err(SimpleThemeLayersError::HeightLength {
                expected: 2,
                actual: 1
            })
        );
        assert_eq!(
            fill_simple_theme_layers(0, 2, 0, 1, 2, 1, &[0, 1], 1, &[0, 300], 0, &[], &mut output),
            Err(SimpleThemeLayersError::InvalidLayerValue)
        );
        assert_eq!(output, [0xaa; 2]);
    }
}
