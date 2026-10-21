use criterion::{black_box, criterion_group, criterion_main, Criterion, Throughput};
use welt_export::frost::{apply_frost_column, material_flags, FrostCell, FrostMode, FrostSettings};

const COLUMNS: usize = 128 * 128;
const MIN_Z: i32 = 0;
const MAX_Z: i32 = 255;
const COLUMN_LEN: usize = (MAX_Z - MIN_Z + 1) as usize;

fn bench_frost_tile(c: &mut Criterion) {
    let settings = FrostSettings {
        frost_everywhere: false,
        frost_layer_present: true,
        snow_under_trees: true,
        mode: FrostMode::Smooth,
        random_snow_layers: 2,
        height_float: 80.75,
        height_int: 80,
        frost_bit_count: 3,
    };
    let mut tile = vec![FrostCell::air(); COLUMNS * COLUMN_LEN];
    for column in tile.as_chunks_mut::<COLUMN_LEN>().0 {
        column[80] = FrostCell::new(material_flags::CAN_SUPPORT_SNOW, 0);
    }
    let mut group = c.benchmark_group("frost_export_bulk");
    group.throughput(Throughput::Elements(COLUMNS as u64));
    group.bench_function("columns_128x128", |b| {
        b.iter(|| {
            for column in tile.as_chunks_mut::<COLUMN_LEN>().0 {
                apply_frost_column(black_box(column), MIN_Z, MAX_Z, 80, settings)
                    .expect("valid frost column");
            }
            black_box(&tile);
        })
    });
    group.finish();
}

criterion_group!(benches, bench_frost_tile);
criterion_main!(benches);
