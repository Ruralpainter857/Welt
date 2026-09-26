//! RNG bit-exact du fork Welt : portage du LCG `java.util.Random` (48 bits).
//!
//! L'implémentation est dans [`java_random`] ; [`JavaRandom`] est réexporté pour
//! l'ergonomie (`welt_core::rng::JavaRandom`). Les tests d'or bit-à-bit rejouent les
//! fichiers `welt-native/golden/java-random-golden.txt` (+ `-edge.txt`) produits par
//! `docs/welt/golden-tools/DumpGoldenRandom.java` (G3, charte §5 : parité bit-exact).

pub mod java_random;

pub use java_random::JavaRandom;
