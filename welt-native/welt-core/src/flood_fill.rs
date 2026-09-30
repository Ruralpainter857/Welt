//! Bounded span-based flood fill used by the Java painting adapters.

use std::collections::VecDeque;

const MAX_FLOOD_CELLS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FloodFillError {
    TooManyCells,
    BoundaryLength,
    SeedOutsideBounds,
}

#[derive(Debug, Clone, Copy)]
struct Range {
    start_x: usize,
    end_x: usize,
    y: usize,
}

#[derive(Debug)]
pub struct FloodFillResult {
    /// Linear indices in the order Java's queue-based filler calls `fill`.
    /// The seed of each horizontal span appears twice, matching that filler.
    pub fill_indices: Vec<i32>,
    pub bounds_hit: bool,
}

/// Runs the same queued horizontal-span traversal as the Java implementation.
/// `boundary` is a row-major snapshot of the fill method's per-cell predicate.
pub fn linear_flood_fill(
    width: usize,
    height: usize,
    seed_x: usize,
    seed_y: usize,
    boundary: &[i8],
) -> Result<FloodFillResult, FloodFillError> {
    let area = width
        .checked_mul(height)
        .filter(|&value| value <= MAX_FLOOD_CELLS)
        .ok_or(FloodFillError::TooManyCells)?;
    if boundary.len() != area {
        return Err(FloodFillError::BoundaryLength);
    }
    if seed_x >= width || seed_y >= height {
        return Err(FloodFillError::SeedOutsideBounds);
    }

    let mut checked = vec![false; area];
    let mut ranges = VecDeque::new();
    let mut fill_indices = Vec::with_capacity(area.saturating_mul(2));
    let mut bounds_hit = false;
    linear_fill(
        seed_x,
        seed_y,
        width,
        boundary,
        &mut checked,
        &mut ranges,
        &mut fill_indices,
        &mut bounds_hit,
    );

    while let Some(range) = ranges.pop_front() {
        for x in range.start_x..=range.end_x {
            if range.y > 0 {
                let up = (range.y - 1) * width + x;
                if !checked[up] && boundary[up] == 0 {
                    linear_fill(
                        x,
                        range.y - 1,
                        width,
                        boundary,
                        &mut checked,
                        &mut ranges,
                        &mut fill_indices,
                        &mut bounds_hit,
                    );
                }
            } else {
                bounds_hit = true;
            }

            if range.y + 1 < height {
                let down = (range.y + 1) * width + x;
                if !checked[down] && boundary[down] == 0 {
                    linear_fill(
                        x,
                        range.y + 1,
                        width,
                        boundary,
                        &mut checked,
                        &mut ranges,
                        &mut fill_indices,
                        &mut bounds_hit,
                    );
                }
            } else {
                bounds_hit = true;
            }
        }
    }

    Ok(FloodFillResult {
        fill_indices,
        bounds_hit,
    })
}

#[allow(clippy::too_many_arguments)]
fn linear_fill(
    x: usize,
    y: usize,
    width: usize,
    boundary: &[i8],
    checked: &mut [bool],
    ranges: &mut VecDeque<Range>,
    fill_indices: &mut Vec<i32>,
    bounds_hit: &mut bool,
) {
    let mut left = x as isize;
    let mut pixel = (width * y + x) as isize;
    loop {
        fill_indices.push(pixel as i32);
        checked[pixel as usize] = true;
        left -= 1;
        pixel -= 1;
        if !(left >= 0 && !checked[pixel as usize] && boundary[pixel as usize] == 0) {
            break;
        }
    }
    if left == 0 {
        *bounds_hit = true;
    }
    left += 1;

    let mut right = x as isize;
    pixel = (width * y + x) as isize;
    loop {
        fill_indices.push(pixel as i32);
        checked[pixel as usize] = true;
        right += 1;
        pixel += 1;
        if !(right < width as isize && !checked[pixel as usize] && boundary[pixel as usize] == 0) {
            break;
        }
    }
    if right == width as isize - 1 {
        *bounds_hit = true;
    }
    right -= 1;

    ranges.push_back(Range {
        start_x: left as usize,
        end_x: right as usize,
        y,
    });
}
