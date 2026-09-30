//! Welt core : bruit bit-exact (PerlinNoise/FastPerlin/UnsafeRandom/RandomField),
//! LCG java.util.Random, ABI WpTileView, mapping d'erreurs et exports JNI du fork Welt.
//! Voir docs/welt/CHARTE-ORCHESTRATION.md (charte) et docs/plan-decoupage-java-rust.md.

pub mod abi;
pub mod erosion;
pub mod error;
pub mod height_edit;
pub mod jni;
pub mod jni_export;
pub mod jni_gen;
pub mod noise;
pub mod rng;
