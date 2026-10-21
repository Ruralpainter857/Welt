//! Benches criterion du bruit Perlin — harnais Welt (G7, plan §0.6).
//!
//! API ciblée (contrat imposé, charte §3/§5) :
//! `welt_core::noise::perlin::PerlinNoise::new(seed: i64)`
//!   → `get_perlin_noise_1d(f64) -> f32`, `get_perlin_noise_2d(f64, f64) -> f32`,
//!     `get_perlin_noise_3d(f64, f64, f64) -> f32` ;
//! `welt_core::noise::perlin::get_level_for_promillage(f32) -> f32` (fonction libre).
//!
//! ⚠️ Tant que G2 n'a pas livré `src/noise/perlin.rs`, ce bench ne compile pas — c'est
//! attendu (placeholders pendant le portage parallèle) ; voir
//! `docs/welt/rapports/G7-parity-harness.md` (« activation après intégration G2/G3 »).
//!
//! Lancement :
//! `cargo bench --manifest-path welt-native/Cargo.toml -p welt-core --bench perlin`
//!
//! Les tailles 128 et 1024 correspondent volontairement aux tuiles du protocole de
//! surcoût JNI (`docs/welt/jni-overhead-protocole.md`) afin de comparer Java/Rust sur
//! les mêmes volumes. Le bench 1024×1024 (~10⁶ échantillons/itération) est long :
//! criterion ajuste automatiquement le nombre d'échantillons mesurés.

use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use welt_core::noise::perlin::{get_level_for_promillage, PerlinNoise};

/// Seed commun aux goldens (`perlin-golden.txt`) : bench et parité partagent les mêmes
/// entrées, pour pouvoir croiser vitesse et exactitude sur un jeu de données connu.
const SEED: i64 = 0;

/// Coût de construction : en Java la table `LEVEL_FOR_PROMILLAGE` (10001 floats) est
/// chargée une fois (initialiseur statique) ; ce bench situe le coût côté Rust et
/// valide qu'il n'est PAS payé à chaque échantillon.
fn bench_perlin_new(c: &mut Criterion) {
    c.bench_function("perlin_new", |b| {
        b.iter(|| black_box(PerlinNoise::new(black_box(SEED))));
    });
}

/// 256 échantillons 1D — le bruit se répète au-delà de [0, 256) (doc PerlinNoise Java).
fn bench_perlin_1d(c: &mut Criterion) {
    let noise = PerlinNoise::new(SEED);
    let coords: Vec<f64> = (0..256).map(|i| i as f64).collect();
    let mut group = c.benchmark_group("perlin_1d");
    group.throughput(Throughput::Elements(coords.len() as u64));
    group.bench_function("samples_256", |b| {
        b.iter(|| {
            let mut acc = 0.0f32;
            for &x in black_box(&coords) {
                acc += noise.get_perlin_noise_1d(x);
            }
            black_box(acc)
        })
    });
    group.finish();
}

/// Grille n×n couvrant [0, 256)² (période utile du bruit).
fn grid_2d(n: usize) -> Vec<(f64, f64)> {
    let step = 256.0 / n as f64;
    let mut coords = Vec::with_capacity(n * n);
    for i in 0..n {
        for j in 0..n {
            coords.push((i as f64 * step, j as f64 * step));
        }
    }
    coords
}

/// Échantillonnage 2D par tuile — le flux chaud des volets 1/2 (une valeur par cellule).
fn bench_perlin_2d_tile(c: &mut Criterion) {
    let noise = PerlinNoise::new(SEED);
    let mut group = c.benchmark_group("perlin_2d_tile");
    for n in [128usize, 1024] {
        let coords = grid_2d(n);
        group.throughput(Throughput::Elements(coords.len() as u64));
        group.bench_with_input(
            BenchmarkId::new("grid", format!("{n}x{n}")),
            &coords,
            |b, coords| {
                b.iter(|| {
                    let mut acc = 0.0f32;
                    for &(x, y) in coords {
                        acc += noise.get_perlin_noise_2d(x, y);
                    }
                    black_box(acc)
                })
            },
        );
    }
    group.finish();
}

/// Échantillonnage 3D : 128×128 positions × 4 couches z (représentatif d'un
/// échantillonnage volumique par tuile, ex. cavernes).
fn bench_perlin_3d(c: &mut Criterion) {
    let noise = PerlinNoise::new(SEED);
    let base = grid_2d(128);
    let mut coords = Vec::with_capacity(base.len() * 4);
    for &(x, y) in &base {
        for z in 0..4u32 {
            coords.push((x, y, z as f64));
        }
    }
    let mut group = c.benchmark_group("perlin_3d");
    group.throughput(Throughput::Elements(coords.len() as u64));
    group.bench_function("layers_128x128x4", |b| {
        b.iter(|| {
            let mut acc = 0.0f32;
            for &(x, y, z) in black_box(&coords) {
                acc += noise.get_perlin_noise_3d(x, y, z);
            }
            black_box(acc)
        })
    });
    group.finish();
}

/// `get_level_for_promillage` : les deux chemins de la table LEVEL_FOR_PROMILLAGE —
/// index entier exact (1001 valeurs 0..=1000) et interpolation linéaire (pas 0,5).
fn bench_promillage(c: &mut Criterion) {
    let integers: Vec<f32> = (0..=1000).map(|p| p as f32).collect();
    let fractional: Vec<f32> = (0..=2000).map(|i| i as f32 * 0.5).collect();
    let mut group = c.benchmark_group("promillage");
    group.throughput(Throughput::Elements(integers.len() as u64));
    group.bench_function("integer_1001", |b| {
        b.iter(|| {
            let mut acc = 0.0f32;
            for &p in black_box(&integers) {
                acc += get_level_for_promillage(p);
            }
            black_box(acc)
        })
    });
    group.throughput(Throughput::Elements(fractional.len() as u64));
    group.bench_function("interpolated_2001", |b| {
        b.iter(|| {
            let mut acc = 0.0f32;
            for &p in black_box(&fractional) {
                acc += get_level_for_promillage(p);
            }
            black_box(acc)
        })
    });
    group.finish();
}

criterion_group!(
    benches,
    bench_perlin_new,
    bench_perlin_1d,
    bench_perlin_2d_tile,
    bench_perlin_3d,
    bench_promillage,
);
criterion_main!(benches);
