use criterion::{black_box, criterion_group, criterion_main, Criterion, Throughput};
use welt_gen::noise_height_map::NoiseHeightMapBulk;

fn bench_noise_height_map_bulk(c: &mut Criterion) {
    let map = NoiseHeightMapBulk::new(512.0, 1.25, 6, 0x1234_5678).expect("valid slice parameters");
    let mut values = vec![0.0; 128 * 128];
    let mut group = c.benchmark_group("noise_height_map_bulk");
    group.throughput(Throughput::Elements(values.len() as u64));
    group.bench_function("tile_128x128_octaves_6", |b| {
        b.iter(|| {
            map.fill_bulk(
                black_box(-512),
                black_box(1024),
                128,
                128,
                black_box(&mut values),
            )
            .expect("buffer size remains valid");
            black_box(&values);
        })
    });
    group.finish();
}

criterion_group!(benches, bench_noise_height_map_bulk);
criterion_main!(benches);
