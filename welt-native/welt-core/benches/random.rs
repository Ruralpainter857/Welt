//! Benches criterion du LCG `java.util.Random` — harnais Welt (G7, plan §0.6).
//!
//! API ciblée (contrat imposé, charte §3/§5) :
//! `welt_core::rng::java_random::JavaRandom::new(seed: i64)`
//!   → `next_int() -> i32`, `next_int_bound(i32) -> i32`,
//!     `next_double() -> f64`, `next_gaussian() -> f64`.
//!
//! ⚠️ Tant que G3 n'a pas livré `src/rng/java_random.rs`, ce bench ne compile pas —
//! c'est attendu (placeholders pendant le portage parallèle) ; voir
//! `docs/welt/rapports/G7-parity-harness.md` (« activation après intégration G2/G3 »).
//!
//! Lancement :
//! `cargo bench --manifest-path welt-native/Cargo.toml -p welt-core --bench random`
//!
//! NOTE de méthode : le LCG est une fonction affine — sans barrière, LLVM pourrait
//! fusionner les pas déroulés en une forme close et fausser la mesure. Chaque tirage
//! passe donc par `black_box(&mut rng)` (valide que les méthodes prennent `&self`
//! avec mutabilité interne ou `&mut self`). Les bornes 2/256/100000 couvrent les
//! trois chemins de `nextInt(bound)` en Java : puissance de 2 triviale, masque
//! `(bound-1)`, et algorithme de rejet pour borne arbitraire.

use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use welt_core::rng::java_random::JavaRandom;

/// Seed commun aux goldens (`java-random-golden.txt`).
const SEED: i64 = 0;
/// Longueur des séquences élémentaires mesurées.
const SEQ: u32 = 1000;

fn bench_java_random_new(c: &mut Criterion) {
    c.bench_function("java_random_new", |b| {
        b.iter(|| black_box(JavaRandom::new(black_box(SEED))));
    });
}

/// Séquences élémentaires : next_int, next_double, next_gaussian (1000 tirages).
fn bench_sequences(c: &mut Criterion) {
    let mut group = c.benchmark_group("java_random_seq");
    group.throughput(Throughput::Elements(SEQ as u64));

    {
        let mut rng = JavaRandom::new(SEED);
        group.bench_function("next_int_1000", |b| {
            b.iter(|| {
                let mut acc = 0i64;
                for _ in 0..SEQ {
                    acc = acc.wrapping_add(black_box(&mut rng).next_int() as i64);
                }
                black_box(acc)
            })
        });
    }

    {
        let mut rng = JavaRandom::new(SEED);
        group.bench_function("next_double_1000", |b| {
            b.iter(|| {
                let mut acc = 0.0f64;
                for _ in 0..SEQ {
                    acc += black_box(&mut rng).next_double();
                }
                black_box(acc)
            })
        });
    }

    {
        let mut rng = JavaRandom::new(SEED);
        group.bench_function("next_gaussian_1000", |b| {
            b.iter(|| {
                let mut acc = 0.0f64;
                for _ in 0..SEQ {
                    acc += black_box(&mut rng).next_gaussian();
                }
                black_box(acc)
            })
        });
    }

    group.finish();
}

/// `next_int_bound(bound)` sur les trois chemins de l'implémentation Java :
/// bound = 2 (puissance de deux minimale), 256 (masque d'une table de 256 entrées),
/// 100000 (rejet — le chemin lent, fréquent en pratique pour les choix pondérés).
fn bench_next_int_bound(c: &mut Criterion) {
    let mut group = c.benchmark_group("java_random_next_int_bound");
    group.throughput(Throughput::Elements(SEQ as u64));
    for bound in [2i32, 256, 100_000] {
        let mut rng = JavaRandom::new(SEED);
        group.bench_with_input(BenchmarkId::new("seq1000", bound), &bound, |b, &bound| {
            b.iter(|| {
                let mut acc = 0i64;
                for _ in 0..SEQ {
                    acc = acc
                        .wrapping_add(black_box(&mut rng).next_int_bound(black_box(bound)) as i64);
                }
                black_box(acc)
            })
        });
    }
    group.finish();
}

/// Profil composite illustratif d'un semis par tuile (type végétation, volets 1/3) :
/// par cellule 128×128 — 1 `next_int_bound(16)` (choix d'espèce), 1 `next_double`
/// (probabilité de présence), 2 `next_gaussian` (offsets x/z) = 4 tirages/cellule.
fn bench_scatter_tile(c: &mut Criterion) {
    const CELLS: usize = 128 * 128;
    let mut rng = JavaRandom::new(SEED);
    let mut group = c.benchmark_group("java_random_scatter");
    group.throughput(Throughput::Elements((CELLS * 4) as u64));
    group.bench_function("tile_128x128", |b| {
        b.iter(|| {
            let mut acc = 0.0f64;
            for _ in 0..CELLS {
                acc += black_box(&mut rng).next_int_bound(16) as f64;
                acc *= black_box(&mut rng).next_double();
                acc += black_box(&mut rng).next_gaussian();
                acc += black_box(&mut rng).next_gaussian();
            }
            black_box(acc)
        })
    });
    group.finish();
}

criterion_group!(
    benches,
    bench_java_random_new,
    bench_sequences,
    bench_next_int_bound,
    bench_scatter_tile,
);
criterion_main!(benches);
