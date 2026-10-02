//! Prepared editor filters for fused painting operations.
//!
//! The caller supplies compact worker-owned planes through `CellData`; evaluation
//! performs no allocation or JNI calls. A painting kernel must evaluate and mutate
//! each cell in Java's original order when any predicate reads mutable paint data.

use crate::error::WeltError;

#[cfg(test)]
#[path = "editor_filter_java_tests.rs"]
mod java_oracle;

pub trait CellData {
    fn height(&self) -> i32;
    fn water(&self) -> i32;
    fn slope(&self) -> f32;
    fn terrain(&self) -> i32;
    fn biome(&self) -> i32;
    fn auto_biome(&self) -> i32;
    fn lava(&self) -> bool;
    fn selected(&self) -> bool;
    fn annotations(&self) -> i32;
    fn layer(&self, plane: usize) -> i32;
}

#[derive(Clone, Copy, Debug)]
pub enum Predicate {
    Biome(i32),
    AutoBiome(i32),
    BitLayer(usize),
    LayerAny(usize),
    LayerEqual(usize, i32),
    LayerAtLeast(usize, i32),
    LayerAtMost(usize, i32),
    Terrain(i32),
    Water,
    Lava,
    Land,
    AnnotationAny,
    Annotation(i32),
}

#[derive(Clone, Copy, Debug)]
pub enum Levels {
    Above(i32),
    Below(i32),
    Between(i32, i32),
    Outside(i32, i32),
}

#[derive(Clone, Debug)]
pub enum Node {
    Predicate {
        predicate: Predicate,
        except: bool,
    },
    Combined(Vec<usize>),
    Default {
        selection: i8,
        except: Option<usize>,
        only: Option<usize>,
        levels: Option<Levels>,
        feather: bool,
        // Compute the tangent in Java once, rather than changing its rounding.
        slope: Option<(f32, bool)>,
    },
}

/// Children precede their parent. The bound prevents untrusted recursive programs
/// from overflowing the stack; the program is checked once, outside the cell loop.
pub struct Program {
    nodes: Vec<Node>,
}

impl Program {
    pub fn new(nodes: Vec<Node>, planes: usize) -> Result<Self, WeltError> {
        if nodes.is_empty() || nodes.len() > 128 {
            return Err(WeltError::IllegalArgument);
        }
        let mut work = [0usize; 128];
        for (index, node) in nodes.iter().enumerate() {
            let valid = match node {
                Node::Combined(children) => {
                    children.len() <= 128 && children.iter().all(|&c| c < index)
                }
                Node::Default {
                    selection,
                    except,
                    only,
                    ..
                } => {
                    (-1..=1).contains(selection)
                        && except.is_none_or(|c| c < index)
                        && only.is_none_or(|c| c < index)
                }
                Node::Predicate { predicate, .. } => match predicate {
                    Predicate::BitLayer(p)
                    | Predicate::LayerAny(p)
                    | Predicate::LayerEqual(p, _)
                    | Predicate::LayerAtLeast(p, _)
                    | Predicate::LayerAtMost(p, _) => *p < planes,
                    _ => true,
                },
            };
            if !valid {
                return Err(WeltError::IllegalArgument);
            }
            // A small acyclic DAG can still expand exponentially at evaluation.
            // Bound visits as well as depth, including repeated child references.
            let cost = match node {
                Node::Combined(children) => 1 + children.iter().map(|&c| work[c]).sum::<usize>(),
                Node::Default { except, only, .. } => {
                    1 + except.map_or(0, |c| work[c]) + only.map_or(0, |c| work[c])
                }
                Node::Predicate { .. } => 1,
            };
            if cost > 4096 {
                return Err(WeltError::IllegalArgument);
            }
            work[index] = cost;
        }
        Ok(Self { nodes })
    }

    pub fn modify_strength(&self, cell: &impl CellData, strength: f32) -> f32 {
        self.evaluate(self.nodes.len() - 1, cell, strength)
    }

    fn evaluate(&self, index: usize, cell: &impl CellData, mut strength: f32) -> f32 {
        match &self.nodes[index] {
            Node::Predicate { predicate, except } => {
                if rejects(*predicate, *except, cell) {
                    0.0
                } else {
                    strength
                }
            }
            Node::Combined(children) => {
                for &child in children {
                    if strength <= 0.0 {
                        return 0.0;
                    }
                    strength = self.evaluate(child, cell, strength);
                }
                strength
            }
            Node::Default {
                selection,
                except,
                only,
                levels,
                feather,
                slope,
            } => {
                if !(strength > 0.0)
                    || (*selection == 1 && !cell.selected())
                    || (*selection == -1 && cell.selected())
                {
                    return 0.0;
                }
                if except.is_some_and(|c| self.evaluate(c, cell, strength) <= 0.0)
                    || only.is_some_and(|c| self.evaluate(c, cell, strength) <= 0.0)
                {
                    return 0.0;
                }
                if let Some(levels) = levels {
                    if let Some(factor) = level_factor(*levels, cell.height()) {
                        // Java returns immediately here, including when slope
                        // would reject the cell. Do not merge the two stages.
                        return if *feather {
                            java_max(factor * strength, 0.0)
                        } else {
                            0.0
                        };
                    }
                }
                if let Some((threshold, above)) = slope {
                    let value = cell.slope();
                    if if *above {
                        value < *threshold
                    } else {
                        value > *threshold
                    } {
                        return 0.0;
                    }
                }
                strength
            }
        }
    }
}

fn rejects(predicate: Predicate, except: bool, cell: &impl CellData) -> bool {
    use Predicate::*;
    // Preserve OnlyOn's existing inverted numeric equality/range comparisons.
    // AutoBiome is deliberately asymmetric: ExceptOn ignores an explicit biome.
    match predicate {
        Biome(value) => (cell.biome() == value) == except,
        AutoBiome(value) => {
            if except {
                cell.auto_biome() == value
            } else {
                cell.biome() != 255 || cell.auto_biome() != value
            }
        }
        BitLayer(plane) => (cell.layer(plane) != 0) == except,
        LayerAny(plane) => (cell.layer(plane) != 0) == except,
        LayerEqual(plane, value) => (cell.layer(plane) != value) == except,
        LayerAtLeast(plane, value) => (cell.layer(plane) < value) == except,
        LayerAtMost(plane, value) => (cell.layer(plane) > value) == except,
        Terrain(value) => (cell.terrain() == value) == except,
        Water => (cell.water() > cell.height() && !cell.lava()) == except,
        Lava => (cell.water() > cell.height() && cell.lava()) == except,
        Land => (cell.water() <= cell.height()) == except,
        AnnotationAny => {
            if except {
                cell.annotations() > 0
            } else {
                cell.annotations() == 0
            }
        }
        Annotation(value) => (cell.annotations() == value) == except,
    }
}

fn level_factor(levels: Levels, height: i32) -> Option<f32> {
    let fraction = |a: i32, b: i32| 1.0 - a.wrapping_sub(b) as f32 / 4.0;
    match levels {
        Levels::Above(a) if height < a => Some(fraction(a, height)),
        Levels::Below(b) if height > b => Some(fraction(height, b)),
        Levels::Between(a, b) if height < a || height > b => {
            Some(java_min(fraction(a, height), fraction(height, b)))
        }
        Levels::Outside(a, b) if height > b && height < a => {
            Some(java_max(fraction(height, b), fraction(a, height)))
        }
        _ => None,
    }
}

// Rust's min/max ignore a single NaN; java.lang.Math propagates it and handles
// signed zero. Avoid fast math and FMA contraction in the strength calculations.
fn java_min(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == 0.0 && b == 0.0 {
        f32::from_bits(a.to_bits() | b.to_bits())
    } else if a <= b {
        a
    } else {
        b
    }
}
fn java_max(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == 0.0 && b == 0.0 {
        f32::from_bits(a.to_bits() & b.to_bits())
    } else if a >= b {
        a
    } else {
        b
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Cell {
        height: i32,
        water: i32,
        slope: f32,
        biome: i32,
        lava: bool,
        layer: i32,
    }
    impl CellData for Cell {
        fn height(&self) -> i32 {
            self.height
        }
        fn water(&self) -> i32 {
            self.water
        }
        fn slope(&self) -> f32 {
            self.slope
        }
        fn terrain(&self) -> i32 {
            7
        }
        fn biome(&self) -> i32 {
            self.biome
        }
        fn auto_biome(&self) -> i32 {
            12
        }
        fn lava(&self) -> bool {
            self.lava
        }
        fn selected(&self) -> bool {
            true
        }
        fn annotations(&self) -> i32 {
            self.layer
        }
        fn layer(&self, _: usize) -> i32 {
            self.layer
        }
    }
    fn cell() -> Cell {
        Cell {
            height: 61,
            water: 62,
            slope: 1.0,
            biome: 255,
            lava: false,
            layer: 5,
        }
    }
    fn default(levels: Option<Levels>, feather: bool) -> Node {
        Node::Default {
            selection: 0,
            except: None,
            only: None,
            levels,
            feather,
            slope: Some((0.5, false)),
        }
    }
    #[test]
    fn feather_returns_before_slope_and_preserves_boundary() {
        let p = Program::new(vec![default(Some(Levels::Above(62)), true)], 0).unwrap();
        assert_eq!(p.modify_strength(&cell(), 0.8).to_bits(), 0.6f32.to_bits());
        let mut c = cell();
        c.height = 62;
        assert_eq!(p.modify_strength(&c, 0.8), 0.0);
    }
    #[test]
    fn preserves_inverted_numeric_only_on_checks() {
        for (predicate, rejected) in [
            (Predicate::LayerEqual(0, 5), true),
            (Predicate::LayerAtLeast(0, 4), true),
            (Predicate::LayerAtMost(0, 4), false),
        ] {
            for except in [false, true] {
                let p = Program::new(vec![Node::Predicate { predicate, except }], 1).unwrap();
                assert_eq!(p.modify_strength(&cell(), 0.8) == 0.0, rejected != except);
            }
        }
    }
    #[test]
    fn auto_biome_except_ignores_explicit_biome() {
        let mut c = cell();
        c.biome = 1;
        for except in [false, true] {
            let p = Program::new(
                vec![Node::Predicate {
                    predicate: Predicate::AutoBiome(12),
                    except,
                }],
                0,
            )
            .unwrap();
            assert_eq!(p.modify_strength(&c, 0.8), 0.0);
        }
    }
    #[test]
    fn combined_filters_apply_sequentially_including_empty_and_nan() {
        let p = Program::new(
            vec![
                default(Some(Levels::Above(62)), true),
                default(Some(Levels::Below(60)), true),
                Node::Combined(vec![0, 1]),
            ],
            0,
        )
        .unwrap();
        assert_eq!(
            p.modify_strength(&cell(), 0.8).to_bits(),
            0.45000002f32.to_bits()
        );
        assert_eq!(p.modify_strength(&cell(), f32::NAN), 0.0);
        let p = Program::new(vec![Node::Combined(vec![])], 0).unwrap();
        assert_eq!(
            p.modify_strength(&cell(), -0.0).to_bits(),
            (-0.0f32).to_bits()
        );
        assert!(p.modify_strength(&cell(), f32::NAN).is_nan());
    }
    #[test]
    fn program_rejects_cycles_forward_references_and_missing_planes() {
        assert!(Program::new(vec![Node::Combined(vec![0])], 0).is_err());
        assert!(Program::new(vec![], 0).is_err());
        assert!(Program::new(
            vec![Node::Predicate {
                predicate: Predicate::BitLayer(0),
                except: false
            }],
            0
        )
        .is_err());
        assert!(Program::new(vec![Node::Combined(vec![]); 129], 0).is_err());
        let mut dag = vec![Node::Combined(vec![])];
        for i in 0..16 {
            dag.push(Node::Combined(vec![i, i]));
        }
        assert!(Program::new(dag, 0).is_err());
    }
    #[test]
    fn water_lava_and_land_partition_matches_java() {
        for height in [61, 62, 63] {
            for lava in [false, true] {
                for except in [false, true] {
                    let mut c = cell();
                    c.height = height;
                    c.lava = lava;
                    for (predicate, matches) in [
                        (Predicate::Water, height < 62 && !lava),
                        (Predicate::Lava, height < 62 && lava),
                        (Predicate::Land, height >= 62),
                    ] {
                        let p =
                            Program::new(vec![Node::Predicate { predicate, except }], 0).unwrap();
                        assert_eq!(p.modify_strength(&c, 0.8) == 0.0, matches == except);
                    }
                }
            }
        }
    }
    #[test]
    fn wrapped_height_subtraction_and_java_signed_zero() {
        assert_eq!(level_factor(Levels::Above(i32::MAX), i32::MIN), Some(1.25));
        assert_eq!(java_max(-0.0, 0.0).to_bits(), 0);
        assert_eq!(java_min(-0.0, 0.0).to_bits(), 0x80000000);
        assert!(java_max(f32::NAN, 0.0).is_nan());
    }
}
