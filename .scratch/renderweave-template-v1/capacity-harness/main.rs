use std::env;
use std::fs;
use std::hint::black_box;
use std::time::{Duration, Instant};

const PAGE_BYTES: usize = 4096;

fn argument(index: usize, name: &str) -> usize {
    env::args()
        .nth(index)
        .unwrap_or_else(|| panic!("missing argument: {}", name))
        .parse::<usize>()
        .unwrap_or_else(|_| panic!("invalid integer argument: {}", name))
}

fn checked_bytes(items: usize, bytes_per_item: usize, name: &str) -> usize {
    items
        .checked_mul(bytes_per_item)
        .unwrap_or_else(|| panic!("byte calculation overflow: {}", name))
}

fn allocate_and_commit(bytes: usize, seed: u8) -> Vec<u8> {
    let mut buffer = Vec::new();
    buffer
        .try_reserve_exact(bytes)
        .unwrap_or_else(|_| panic!("allocation failed for {} bytes", bytes));
    buffer.resize(bytes, 0);
    for offset in (0..bytes).step_by(PAGE_BYTES) {
        buffer[offset] = seed.wrapping_add((offset / PAGE_BYTES) as u8);
    }
    if let Some(last) = buffer.last_mut() {
        *last = seed.wrapping_add(1);
    }
    buffer
}

fn vm_hwm_kib() -> u64 {
    fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|status| {
            status.lines().find_map(|line| {
                line.strip_prefix("VmHWM:")
                    .and_then(|value| value.split_whitespace().next())
                    .and_then(|value| value.parse::<u64>().ok())
            })
        })
        .unwrap_or(0)
}

fn cgroup_value(path: &str) -> String {
    fs::read_to_string(path)
        .map(|value| value.trim().to_owned())
        .unwrap_or_else(|_| "unavailable".to_owned())
}

fn elapsed_millis(duration: Duration) -> u128 {
    duration.as_millis()
}

fn elapsed_micros(duration: Duration) -> u128 {
    duration.as_micros()
}

fn main() {
    let surface_pixels = argument(1, "surface_pixels");
    let decoded_bytes = argument(2, "decoded_bytes");
    let raw_bytes = argument(3, "raw_bytes");
    let encoded_bytes = argument(4, "encoded_bytes");
    let paint_passes = argument(5, "paint_passes");
    let layout_operations = argument(6, "layout_operations");
    let glyph_operations = argument(7, "glyph_operations");
    let surface_bytes = checked_bytes(surface_pixels, 4, "surface");

    let allocation_started = Instant::now();
    let raw = allocate_and_commit(raw_bytes, 11);
    let mut decoded = allocate_and_commit(decoded_bytes, 29);
    let mut surface = allocate_and_commit(surface_bytes, 47);
    let mut encoded = allocate_and_commit(encoded_bytes, 71);
    let allocation_elapsed = allocation_started.elapsed();
    let hwm_after_allocation = vm_hwm_kib();

    let decode_started = Instant::now();
    for pixel in decoded.chunks_exact_mut(4) {
        let alpha = pixel[3] as u16;
        pixel[0] = ((pixel[0] as u16 * alpha + 127) / 255) as u8;
        pixel[1] = ((pixel[1] as u16 * alpha + 127) / 255) as u8;
        pixel[2] = ((pixel[2] as u16 * alpha + 127) / 255) as u8;
    }
    let decode_elapsed = decode_started.elapsed();

    let paint_started = Instant::now();
    for pass in 0..paint_passes {
        let source_alpha = 64_u16 + ((pass as u16 * 37) % 192);
        for pixel in surface.chunks_exact_mut(4) {
            let inverse = 255_u16 - source_alpha;
            pixel[0] = (31_u16 + (pixel[0] as u16 * inverse + 127) / 255).min(255) as u8;
            pixel[1] = (47_u16 + (pixel[1] as u16 * inverse + 127) / 255).min(255) as u8;
            pixel[2] = (59_u16 + (pixel[2] as u16 * inverse + 127) / 255).min(255) as u8;
            pixel[3] = (source_alpha + (pixel[3] as u16 * inverse + 127) / 255).min(255) as u8;
        }
    }
    let paint_elapsed = paint_started.elapsed();

    let operation_started = Instant::now();
    let mut operation_digest = 0x9e37_79b9_7f4a_7c15_u64;
    for ordinal in 0..layout_operations {
        operation_digest = operation_digest
            .rotate_left(7)
            .wrapping_add((ordinal as u64).wrapping_mul(0x1000_0000_01b3));
    }
    for ordinal in 0..glyph_operations {
        operation_digest ^= (ordinal as u64)
            .wrapping_mul(0x9e37_79b9)
            .rotate_right((ordinal % 63) as u32);
    }
    let operation_elapsed = operation_started.elapsed();

    let encode_started = Instant::now();
    if !surface.is_empty() {
        for (index, byte) in encoded.iter_mut().enumerate() {
            *byte = surface[index % surface.len()].wrapping_add((index >> 20) as u8);
        }
    }
    let encode_elapsed = encode_started.elapsed();
    let final_hwm_kib = vm_hwm_kib();

    let checksum = raw
        .iter()
        .step_by(PAGE_BYTES)
        .fold(operation_digest, |accumulator, byte| {
            accumulator.rotate_left(3) ^ (*byte as u64)
        });
    black_box((&raw, &decoded, &surface, &encoded, checksum));

    println!(
        concat!(
            "{{",
            "\"surfacePixels\":{},",
            "\"surfaceBytes\":{},",
            "\"decodedBytes\":{},",
            "\"rawBytes\":{},",
            "\"encodedBytes\":{},",
            "\"paintPasses\":{},",
            "\"layoutOperations\":{},",
            "\"glyphOperations\":{},",
            "\"allocationMillis\":{},",
            "\"decodeMillis\":{},",
            "\"paintMillis\":{},",
            "\"operationMillis\":{},",
            "\"encodeCopyMillis\":{},",
            "\"allocationMicros\":{},",
            "\"decodeMicros\":{},",
            "\"paintMicros\":{},",
            "\"operationMicros\":{},",
            "\"encodeCopyMicros\":{},",
            "\"hwmAfterAllocationKiB\":{},",
            "\"finalHwmKiB\":{},",
            "\"cgroupCpuMax\":\"{}\",",
            "\"cgroupMemoryMax\":\"{}\",",
            "\"checksum\":\"{:016x}\"",
            "}}"
        ),
        surface_pixels,
        surface_bytes,
        decoded_bytes,
        raw_bytes,
        encoded_bytes,
        paint_passes,
        layout_operations,
        glyph_operations,
        elapsed_millis(allocation_elapsed),
        elapsed_millis(decode_elapsed),
        elapsed_millis(paint_elapsed),
        elapsed_millis(operation_elapsed),
        elapsed_millis(encode_elapsed),
        elapsed_micros(allocation_elapsed),
        elapsed_micros(decode_elapsed),
        elapsed_micros(paint_elapsed),
        elapsed_micros(operation_elapsed),
        elapsed_micros(encode_elapsed),
        hwm_after_allocation,
        final_hwm_kib,
        cgroup_value("/sys/fs/cgroup/cpu.max"),
        cgroup_value("/sys/fs/cgroup/memory.max"),
        checksum
    );
}
