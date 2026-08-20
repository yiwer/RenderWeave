//! Exact `renderweave-output-png/1.0` kernel.
//!
//! This crate accepts only already-rasterized canonical straight RGBA8 pixels. It does not perform
//! layout, resource decoding, color conversion, premultiplication, painting, daemon dispatch, or
//! Profile registration.

use std::fmt::{Display, Formatter};

pub const OUTPUT_PROFILE: &str = "renderweave-output-png/1.0";
const DECIMAL_SCALE: i128 = 1_000_000;
const POINTS_PER_INCH: i128 = 72;
const MAX_DPI: u32 = 600;
const MAX_SURFACE_EDGE_PIXELS: u64 = 16_384;
const MAX_SURFACE_PIXELS: u64 = 50_000_000;
const MAX_RGBA8_SURFACE_BYTES: u64 = 200_000_000;
const MAX_ENCODER_SCRATCH_BYTES: u64 = 67_108_864;
const MAX_ENCODED_IMAGE_BYTES: u64 = 536_870_912;
const STORED_DEFLATE_BLOCK_BYTES: usize = 65_535;
const IDAT_PAYLOAD_BYTES: usize = 1_048_576;

const OUTPUT_BUDGET_EXCEEDED: &str = "OUTPUT_BUDGET_EXCEEDED";
const RASTER_BUDGET_EXCEEDED: &str = "RASTER_BUDGET_EXCEEDED";
const OUTPUT_PREFLIGHT: &str = "OUTPUT_PREFLIGHT";
const ENCODING: &str = "ENCODING";
const DPI_LIMIT: &str = "rendererSurfaceAndOutput.dpi";
const EDGE_LIMIT: &str = "rendererSurfaceAndOutput.surfaceEdgePixels";
const PIXEL_LIMIT: &str = "rendererSurfaceAndOutput.surfacePixels";
const RGBA8_LIMIT: &str = "rendererSurfaceAndOutput.rgba8SurfaceBytes";
const ENCODED_LIMIT: &str = "rendererSurfaceAndOutput.encodedImageBytes";
const EPHEMERAL_LIMIT: &str = "rendererSurfaceAndOutput.requestEphemeralBytes";

#[derive(Clone, Copy, Debug)]
pub struct BleedPt<'a> {
    pub top: &'a str,
    pub right: &'a str,
    pub bottom: &'a str,
    pub left: &'a str,
}

impl BleedPt<'static> {
    pub const ZERO: Self = Self {
        top: "0",
        right: "0",
        bottom: "0",
        left: "0",
    };
}

#[derive(Clone, Copy, Debug)]
pub struct SurfaceSpec<'a> {
    pub width_pt: &'a str,
    pub height_pt: &'a str,
    pub bleed_pt: BleedPt<'a>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SurfaceDimensions {
    width_px: u32,
    height_px: u32,
    rgba8_bytes: u64,
    raw_scanline_bytes: u64,
    zlib_bytes: u64,
    png_encoded_bytes: u64,
}

impl SurfaceDimensions {
    pub fn width_px(self) -> u32 {
        self.width_px
    }

    pub fn height_px(self) -> u32 {
        self.height_px
    }

    pub fn rgba8_bytes(self) -> u64 {
        self.rgba8_bytes
    }

    pub fn png_encoded_bytes(self) -> u64 {
        self.png_encoded_bytes
    }
}

#[derive(Debug, Eq, PartialEq)]
pub enum OutputPngError {
    Contract(&'static str),
    Budget {
        code: &'static str,
        stage: &'static str,
        limit_id: &'static str,
    },
}

impl OutputPngError {
    pub fn code(&self) -> Option<&'static str> {
        match self {
            Self::Contract(_) => None,
            Self::Budget { code, .. } => Some(code),
        }
    }

    pub fn stage(&self) -> Option<&'static str> {
        match self {
            Self::Contract(_) => None,
            Self::Budget { stage, .. } => Some(stage),
        }
    }

    pub fn limit_id(&self) -> Option<&'static str> {
        match self {
            Self::Contract(_) => None,
            Self::Budget { limit_id, .. } => Some(limit_id),
        }
    }
}

impl Display for OutputPngError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Contract(message) => formatter.write_str(message),
            Self::Budget {
                code,
                stage,
                limit_id,
            } => write!(formatter, "{code} at {stage} ({limit_id})"),
        }
    }
}

impl std::error::Error for OutputPngError {}

pub fn preflight_surface(
    spec: SurfaceSpec<'_>,
    dpi: u32,
) -> Result<SurfaceDimensions, OutputPngError> {
    validate_dpi(dpi)?;
    let width = positive_decimal6(spec.width_pt)?;
    let height = positive_decimal6(spec.height_pt)?;
    let top = nonnegative_decimal6(spec.bleed_pt.top)?;
    let right = nonnegative_decimal6(spec.bleed_pt.right)?;
    let bottom = nonnegative_decimal6(spec.bleed_pt.bottom)?;
    let left = nonnegative_decimal6(spec.bleed_pt.left)?;
    let surface_width = width
        .checked_add(left)
        .and_then(|value| value.checked_add(right))
        .ok_or(OutputPngError::Contract(
            "surface width arithmetic overflow",
        ))?;
    let surface_height = height
        .checked_add(top)
        .and_then(|value| value.checked_add(bottom))
        .ok_or(OutputPngError::Contract(
            "surface height arithmetic overflow",
        ))?;
    let width_px = round_surface_pixels(surface_width, dpi)?;
    let height_px = round_surface_pixels(surface_height, dpi)?;
    preflight_pixel_dimensions(width_px, height_px, dpi)
}

pub fn encode_straight_rgba8(
    width_px: u32,
    height_px: u32,
    dpi: u32,
    pixels: &[u8],
) -> Result<Vec<u8>, OutputPngError> {
    let surface = preflight_pixel_dimensions(u64::from(width_px), u64::from(height_px), dpi)?;
    let expected_length =
        usize::try_from(surface.rgba8_bytes).map_err(|_| output_budget(EPHEMERAL_LIMIT))?;
    if pixels.len() != expected_length {
        return Err(OutputPngError::Contract(
            "straight RGBA8 byte length does not match surface",
        ));
    }
    if pixels
        .chunks_exact(4)
        .any(|pixel| pixel[3] == 0 && pixel[..3] != [0, 0, 0])
    {
        return Err(OutputPngError::Contract(
            "fully transparent straight RGBA8 pixels must have zero RGB",
        ));
    }

    let raw_scanline_bytes = surface.raw_scanline_bytes;
    let zlib_bytes = surface.zlib_bytes;
    let capacity =
        usize::try_from(surface.png_encoded_bytes).map_err(|_| output_budget(ENCODED_LIMIT))?;
    let mut encoded = Vec::new();
    encoded
        .try_reserve_exact(capacity)
        .map_err(|_| output_budget(EPHEMERAL_LIMIT))?;
    encoded.extend_from_slice(b"\x89PNG\r\n\x1a\n");

    let mut ihdr = [0_u8; 13];
    ihdr[..4].copy_from_slice(&width_px.to_be_bytes());
    ihdr[4..8].copy_from_slice(&height_px.to_be_bytes());
    ihdr[8..].copy_from_slice(&[8, 6, 0, 0, 0]);
    write_chunk(&mut encoded, b"IHDR", &ihdr);
    write_chunk(&mut encoded, b"sRGB", &[0]);
    let pixels_per_meter = pixels_per_meter(dpi);
    let mut physical = [0_u8; 9];
    physical[..4].copy_from_slice(&pixels_per_meter.to_be_bytes());
    physical[4..8].copy_from_slice(&pixels_per_meter.to_be_bytes());
    physical[8] = 1;
    write_chunk(&mut encoded, b"pHYs", &physical);

    {
        let mut idat = IdatWriter::new(&mut encoded, zlib_bytes)?;
        idat.write(&[0x78, 0x01])?;
        let mut remaining_raw = raw_scanline_bytes;
        let mut block_remaining = 0_usize;
        let mut adler = Adler32::new();
        let row_bytes =
            usize::try_from(u64::from(width_px) * 4).map_err(|_| output_budget(ENCODED_LIMIT))?;
        for row in 0..height_px as usize {
            write_stored_bytes(
                &[0],
                &mut remaining_raw,
                &mut block_remaining,
                &mut idat,
                &mut adler,
            )?;
            let start = row * row_bytes;
            write_stored_bytes(
                &pixels[start..start + row_bytes],
                &mut remaining_raw,
                &mut block_remaining,
                &mut idat,
                &mut adler,
            )?;
        }
        if remaining_raw != 0 || block_remaining != 0 {
            return Err(OutputPngError::Contract(
                "stored DEFLATE source accounting drifted",
            ));
        }
        idat.write(&adler.finish().to_be_bytes())?;
        idat.finish()?;
    }
    write_chunk(&mut encoded, b"IEND", &[]);
    if encoded.len() != capacity {
        return Err(OutputPngError::Contract(
            "PNG encoded size preflight drifted",
        ));
    }
    Ok(encoded)
}

fn validate_dpi(dpi: u32) -> Result<(), OutputPngError> {
    if dpi == 0 || dpi > MAX_DPI {
        return Err(output_budget(DPI_LIMIT));
    }
    Ok(())
}

fn positive_decimal6(raw: &str) -> Result<i128, OutputPngError> {
    let value = parse_decimal6(raw)?;
    if value == 0 {
        return Err(OutputPngError::Contract(
            "trim dimensions must be strictly positive",
        ));
    }
    Ok(value)
}

fn nonnegative_decimal6(raw: &str) -> Result<i128, OutputPngError> {
    parse_decimal6(raw)
}

fn parse_decimal6(raw: &str) -> Result<i128, OutputPngError> {
    if raw.is_empty()
        || raw.starts_with(['+', '-'])
        || raw.ends_with('.')
        || raw.starts_with('.')
        || !raw
            .bytes()
            .all(|byte| byte.is_ascii_digit() || byte == b'.')
        || raw.bytes().filter(|byte| *byte == b'.').count() > 1
    {
        return Err(OutputPngError::Contract(
            "surface pt value must be a canonical nonnegative decimal",
        ));
    }
    let (integer, fraction) = raw.split_once('.').unwrap_or((raw, ""));
    if integer.len() > 1 && integer.starts_with('0') {
        return Err(OutputPngError::Contract(
            "surface pt decimal has a leading zero",
        ));
    }
    if fraction.len() > 6 || fraction.ends_with('0') {
        return Err(OutputPngError::Contract(
            "surface pt decimal exceeds canonical six-place precision",
        ));
    }
    let integer = parse_digits(integer)?;
    let fraction = if fraction.is_empty() {
        0
    } else {
        let parsed = parse_digits(fraction)?;
        parsed
            .checked_mul(10_i128.pow(6 - fraction.len() as u32))
            .ok_or(OutputPngError::Contract("surface pt decimal overflow"))?
    };
    integer
        .checked_mul(DECIMAL_SCALE)
        .and_then(|value| value.checked_add(fraction))
        .ok_or(OutputPngError::Contract("surface pt decimal overflow"))
}

fn parse_digits(raw: &str) -> Result<i128, OutputPngError> {
    if raw.is_empty() || !raw.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(OutputPngError::Contract(
            "surface pt decimal contains a non-digit",
        ));
    }
    raw.bytes().try_fold(0_i128, |value, byte| {
        value
            .checked_mul(10)
            .and_then(|value| value.checked_add(i128::from(byte - b'0')))
            .ok_or(OutputPngError::Contract("surface pt decimal overflow"))
    })
}

fn round_surface_pixels(micro_points: i128, dpi: u32) -> Result<u64, OutputPngError> {
    let denominator = POINTS_PER_INCH * DECIMAL_SCALE;
    let numerator = micro_points
        .checked_mul(i128::from(dpi))
        .ok_or(OutputPngError::Contract(
            "surface pixel arithmetic overflow",
        ))?;
    let rounded = numerator
        .checked_add(denominator / 2)
        .ok_or(OutputPngError::Contract(
            "surface pixel arithmetic overflow",
        ))?
        / denominator;
    u64::try_from(rounded)
        .map_err(|_| OutputPngError::Contract("surface pixel arithmetic overflow"))
}

fn preflight_pixel_dimensions(
    width_px: u64,
    height_px: u64,
    dpi: u32,
) -> Result<SurfaceDimensions, OutputPngError> {
    validate_dpi(dpi)?;
    if width_px == 0 || height_px == 0 {
        return Err(output_budget(PIXEL_LIMIT));
    }
    if width_px > MAX_SURFACE_EDGE_PIXELS || height_px > MAX_SURFACE_EDGE_PIXELS {
        return Err(output_budget(EDGE_LIMIT));
    }
    let pixels = width_px
        .checked_mul(height_px)
        .ok_or_else(|| output_budget(PIXEL_LIMIT))?;
    if pixels > MAX_SURFACE_PIXELS {
        return Err(output_budget(PIXEL_LIMIT));
    }
    let rgba8_bytes = pixels
        .checked_mul(4)
        .ok_or_else(|| raster_budget(RGBA8_LIMIT))?;
    if rgba8_bytes > MAX_RGBA8_SURFACE_BYTES {
        return Err(raster_budget(RGBA8_LIMIT));
    }
    let (raw_scanline_bytes, zlib_bytes, png_encoded_bytes) =
        png_encoded_size(width_px, height_px)?;
    Ok(SurfaceDimensions {
        width_px: u32::try_from(width_px).map_err(|_| output_budget(EDGE_LIMIT))?,
        height_px: u32::try_from(height_px).map_err(|_| output_budget(EDGE_LIMIT))?,
        rgba8_bytes,
        raw_scanline_bytes,
        zlib_bytes,
        png_encoded_bytes,
    })
}

fn png_encoded_size(width_px: u64, height_px: u64) -> Result<(u64, u64, u64), OutputPngError> {
    let raw_scanline_bytes = height_px
        .checked_mul(
            width_px
                .checked_mul(4)
                .and_then(|value| value.checked_add(1))
                .ok_or_else(|| output_budget(ENCODED_LIMIT))?,
        )
        .ok_or_else(|| output_budget(ENCODED_LIMIT))?;
    let stored_blocks = ceiling_div(raw_scanline_bytes, STORED_DEFLATE_BLOCK_BYTES as u64);
    let zlib_bytes = 2_u64
        .checked_add(raw_scanline_bytes)
        .and_then(|value| value.checked_add(stored_blocks.checked_mul(5)?))
        .and_then(|value| value.checked_add(4))
        .ok_or_else(|| output_budget(ENCODED_LIMIT))?;
    let idat_chunks = ceiling_div(zlib_bytes, IDAT_PAYLOAD_BYTES as u64);
    let encoded_bytes = 79_u64
        .checked_add(zlib_bytes)
        .and_then(|value| value.checked_add(idat_chunks.checked_mul(12)?))
        .ok_or_else(|| output_budget(ENCODED_LIMIT))?;
    if encoded_bytes > MAX_ENCODED_IMAGE_BYTES {
        return Err(output_budget(ENCODED_LIMIT));
    }
    if IDAT_PAYLOAD_BYTES as u64 > MAX_ENCODER_SCRATCH_BYTES {
        return Err(OutputPngError::Budget {
            code: OUTPUT_BUDGET_EXCEEDED,
            stage: ENCODING,
            limit_id: "rendererSurfaceAndOutput.encoderScratchBytes",
        });
    }
    Ok((raw_scanline_bytes, zlib_bytes, encoded_bytes))
}

fn output_budget(limit_id: &'static str) -> OutputPngError {
    OutputPngError::Budget {
        code: OUTPUT_BUDGET_EXCEEDED,
        stage: OUTPUT_PREFLIGHT,
        limit_id,
    }
}

fn raster_budget(limit_id: &'static str) -> OutputPngError {
    OutputPngError::Budget {
        code: RASTER_BUDGET_EXCEEDED,
        stage: OUTPUT_PREFLIGHT,
        limit_id,
    }
}

fn ceiling_div(value: u64, divisor: u64) -> u64 {
    value / divisor + u64::from(value % divisor != 0)
}

fn pixels_per_meter(dpi: u32) -> u32 {
    (dpi * 5_000 + 63) / 127
}

fn write_chunk(encoded: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    encoded.extend_from_slice(&(data.len() as u32).to_be_bytes());
    encoded.extend_from_slice(kind);
    encoded.extend_from_slice(data);
    let mut crc = Crc32::new();
    crc.update(kind);
    crc.update(data);
    encoded.extend_from_slice(&crc.finish().to_be_bytes());
}

fn write_stored_bytes(
    mut source: &[u8],
    remaining_raw: &mut u64,
    block_remaining: &mut usize,
    idat: &mut IdatWriter<'_>,
    adler: &mut Adler32,
) -> Result<(), OutputPngError> {
    while !source.is_empty() {
        if *block_remaining == 0 {
            let length = (*remaining_raw).min(STORED_DEFLATE_BLOCK_BYTES as u64) as u16;
            if length == 0 {
                return Err(OutputPngError::Contract(
                    "stored DEFLATE source exceeded preflight length",
                ));
            }
            let final_block = u64::from(length) == *remaining_raw;
            let complement = !length;
            let header = [
                u8::from(final_block),
                length as u8,
                (length >> 8) as u8,
                complement as u8,
                (complement >> 8) as u8,
            ];
            idat.write(&header)?;
            *block_remaining = usize::from(length);
        }
        let amount = source.len().min(*block_remaining);
        let part = &source[..amount];
        idat.write(part)?;
        adler.update(part);
        source = &source[amount..];
        *block_remaining -= amount;
        *remaining_raw -= amount as u64;
    }
    Ok(())
}

struct IdatWriter<'a> {
    encoded: &'a mut Vec<u8>,
    remaining: u64,
    chunk_remaining: usize,
    crc: Option<Crc32>,
}

impl<'a> IdatWriter<'a> {
    fn new(encoded: &'a mut Vec<u8>, total_payload: u64) -> Result<Self, OutputPngError> {
        if total_payload == 0 {
            return Err(OutputPngError::Contract(
                "PNG requires a nonempty IDAT payload",
            ));
        }
        Ok(Self {
            encoded,
            remaining: total_payload,
            chunk_remaining: 0,
            crc: None,
        })
    }

    fn write(&mut self, mut bytes: &[u8]) -> Result<(), OutputPngError> {
        if bytes.len() as u64 > self.remaining {
            return Err(OutputPngError::Contract(
                "IDAT payload exceeded preflight length",
            ));
        }
        while !bytes.is_empty() {
            if self.chunk_remaining == 0 {
                self.start_chunk();
            }
            let amount = bytes.len().min(self.chunk_remaining);
            let part = &bytes[..amount];
            self.encoded.extend_from_slice(part);
            self.crc.as_mut().expect("active IDAT chunk").update(part);
            bytes = &bytes[amount..];
            self.chunk_remaining -= amount;
            self.remaining -= amount as u64;
            if self.chunk_remaining == 0 {
                self.finish_chunk();
            }
        }
        Ok(())
    }

    fn finish(self) -> Result<(), OutputPngError> {
        if self.remaining != 0 || self.chunk_remaining != 0 || self.crc.is_some() {
            return Err(OutputPngError::Contract(
                "IDAT payload ended before preflight length",
            ));
        }
        Ok(())
    }

    fn start_chunk(&mut self) {
        let length = self.remaining.min(IDAT_PAYLOAD_BYTES as u64) as u32;
        self.encoded.extend_from_slice(&length.to_be_bytes());
        self.encoded.extend_from_slice(b"IDAT");
        let mut crc = Crc32::new();
        crc.update(b"IDAT");
        self.crc = Some(crc);
        self.chunk_remaining = length as usize;
    }

    fn finish_chunk(&mut self) {
        let crc = self.crc.take().expect("active IDAT chunk").finish();
        self.encoded.extend_from_slice(&crc.to_be_bytes());
    }
}

struct Adler32 {
    a: u32,
    b: u32,
}

impl Adler32 {
    fn new() -> Self {
        Self { a: 1, b: 0 }
    }

    fn update(&mut self, bytes: &[u8]) {
        for chunk in bytes.chunks(5_552) {
            let mut a = u64::from(self.a);
            let mut b = u64::from(self.b);
            for byte in chunk {
                a += u64::from(*byte);
                b += a;
            }
            self.a = (a % 65_521) as u32;
            self.b = (b % 65_521) as u32;
        }
    }

    fn finish(self) -> u32 {
        (self.b << 16) | self.a
    }
}

struct Crc32(u32);

impl Crc32 {
    fn new() -> Self {
        Self(0xffff_ffff)
    }

    fn update(&mut self, bytes: &[u8]) {
        for byte in bytes {
            self.0 ^= u32::from(*byte);
            for _ in 0..8 {
                self.0 = (self.0 >> 1) ^ (0xedb8_8320_u32 & 0_u32.wrapping_sub(self.0 & 1));
            }
        }
    }

    fn finish(self) -> u32 {
        !self.0
    }
}

#[cfg(test)]
mod tests {
    use super::{BleedPt, OutputPngError, SurfaceSpec, encode_straight_rgba8, preflight_surface};
    use serde::Deserialize;
    use sha2::{Digest, Sha256};

    const VECTORS: &str = include_str!("../../../output-png-vectors-v1.json");

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Vectors {
        manifest_version: String,
        output_profile: String,
        input_contract: String,
        limits: Limits,
        surface_cases: Vec<SurfaceCase>,
        png_cases: Vec<PngCase>,
        boundary: Boundary,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Limits {
        dpi: u32,
        surface_edge_pixels: u32,
        surface_pixels: u64,
        rgba8_surface_bytes: u64,
        encoder_scratch_bytes: u64,
        encoded_image_bytes: u64,
        stored_deflate_block_bytes: usize,
        idat_payload_bytes: usize,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SurfaceCase {
        id: String,
        width_pt: String,
        height_pt: String,
        bleed_pt: BleedVector,
        dpi: u32,
        expected: SurfaceExpected,
    }

    #[derive(Deserialize)]
    struct BleedVector {
        top: String,
        right: String,
        bottom: String,
        left: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct SurfaceExpected {
        status: String,
        width_px: Option<u32>,
        height_px: Option<u32>,
        rgba8_bytes: Option<u64>,
        png_encoded_bytes: Option<u64>,
        code: Option<String>,
        stage: Option<String>,
        limit_id: Option<String>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct PngCase {
        id: String,
        width_px: u32,
        height_px: u32,
        dpi: u32,
        pixels: PixelFixture,
        expected: PngExpected,
    }

    #[derive(Deserialize)]
    struct PixelFixture {
        kind: String,
        hex: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct PngExpected {
        byte_length: usize,
        sha256: String,
        pixels_per_meter: u32,
        chunk_types: Vec<String>,
        stored_block_lengths: Vec<usize>,
        idat_payload_lengths: Vec<usize>,
        exact_hex: Option<String>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Boundary {
        profile_availability: String,
        certification_status: String,
        raster_implementation: String,
        daemon_output_path: String,
        physical_host_certification: bool,
        provider_attempts: u32,
    }

    #[test]
    fn surface_vectors_use_exact_half_up_and_frozen_limits() {
        let vectors = vectors();
        assert_manifest(&vectors);
        for case in vectors.surface_cases {
            let result = preflight_surface(
                SurfaceSpec {
                    width_pt: &case.width_pt,
                    height_pt: &case.height_pt,
                    bleed_pt: BleedPt {
                        top: &case.bleed_pt.top,
                        right: &case.bleed_pt.right,
                        bottom: &case.bleed_pt.bottom,
                        left: &case.bleed_pt.left,
                    },
                },
                case.dpi,
            );
            match case.expected.status.as_str() {
                "ADMITTED" => {
                    let surface = result.unwrap_or_else(|error| {
                        panic!("{} unexpectedly rejected: {error}", case.id)
                    });
                    assert_eq!(
                        Some(surface.width_px()),
                        case.expected.width_px,
                        "{}",
                        case.id
                    );
                    assert_eq!(
                        Some(surface.height_px()),
                        case.expected.height_px,
                        "{}",
                        case.id
                    );
                    assert_eq!(
                        Some(surface.rgba8_bytes()),
                        case.expected.rgba8_bytes,
                        "{}",
                        case.id
                    );
                    assert_eq!(
                        Some(surface.png_encoded_bytes()),
                        case.expected.png_encoded_bytes,
                        "{}",
                        case.id
                    );
                }
                "REJECTED" => assert_error(case.id.as_str(), result.unwrap_err(), &case.expected),
                other => panic!("{} has unknown expected status {other}", case.id),
            }
        }
    }

    #[test]
    fn png_vectors_are_byte_exact_and_stream_boundaries_are_visible() {
        let vectors = vectors();
        assert_manifest(&vectors);
        for case in vectors.png_cases {
            let pixels = pixels(&case);
            let encoded = encode_straight_rgba8(case.width_px, case.height_px, case.dpi, &pixels)
                .unwrap_or_else(|error| panic!("{} unexpectedly rejected: {error}", case.id));
            assert_eq!(case.expected.byte_length, encoded.len(), "{}", case.id);
            let digest = format!("sha256:{}", hex::encode(Sha256::digest(&encoded)));
            assert_eq!(case.expected.sha256, digest, "{}", case.id);
            if let Some(exact_hex) = case.expected.exact_hex {
                assert_eq!(exact_hex, hex::encode(&encoded), "{}", case.id);
            }
            let inspection = inspect_png(&encoded);
            assert_eq!(
                case.expected.chunk_types, inspection.chunk_types,
                "{}",
                case.id
            );
            assert_eq!(
                case.expected.pixels_per_meter, inspection.pixels_per_meter,
                "{}",
                case.id
            );
            assert_eq!(
                case.expected.idat_payload_lengths, inspection.idat_payload_lengths,
                "{}",
                case.id
            );
            assert_eq!(
                case.expected.stored_block_lengths, inspection.stored_block_lengths,
                "{}",
                case.id
            );
        }
    }

    #[test]
    fn malformed_decimal_and_pixel_contracts_fail_closed() {
        for decimal in ["", "+1", "01", "1.", ".1", "1.0", "1e0", "-1", "0.0000001"] {
            let result = preflight_surface(
                SurfaceSpec {
                    width_pt: decimal,
                    height_pt: "1",
                    bleed_pt: BleedPt::ZERO,
                },
                96,
            );
            assert!(
                matches!(result, Err(OutputPngError::Contract(_))),
                "{decimal}"
            );
        }
        assert!(matches!(
            encode_straight_rgba8(1, 1, 96, &[0, 0, 0]),
            Err(OutputPngError::Contract(_))
        ));
        assert!(matches!(
            encode_straight_rgba8(1, 1, 96, &[1, 0, 0, 0]),
            Err(OutputPngError::Contract(_))
        ));
    }

    fn vectors() -> Vectors {
        serde_json::from_str(VECTORS).expect("output PNG vectors must be strict test JSON")
    }

    fn assert_manifest(vectors: &Vectors) {
        assert_eq!(
            "renderweave-output-png-vectors/1.0",
            vectors.manifest_version
        );
        assert_eq!("renderweave-output-png/1.0", vectors.output_profile);
        assert_eq!("canonical-straight-rgba8-row-major", vectors.input_contract);
        assert_eq!(600, vectors.limits.dpi);
        assert_eq!(16_384, vectors.limits.surface_edge_pixels);
        assert_eq!(50_000_000, vectors.limits.surface_pixels);
        assert_eq!(200_000_000, vectors.limits.rgba8_surface_bytes);
        assert_eq!(67_108_864, vectors.limits.encoder_scratch_bytes);
        assert_eq!(536_870_912, vectors.limits.encoded_image_bytes);
        assert_eq!(65_535, vectors.limits.stored_deflate_block_bytes);
        assert_eq!(1_048_576, vectors.limits.idat_payload_bytes);
        assert_eq!("NOT_REGISTERED", vectors.boundary.profile_availability);
        assert_eq!("NOT_CERTIFIED", vectors.boundary.certification_status);
        assert_eq!("ABSENT", vectors.boundary.raster_implementation);
        assert_eq!("UNWIRED", vectors.boundary.daemon_output_path);
        assert!(!vectors.boundary.physical_host_certification);
        assert_eq!(0, vectors.boundary.provider_attempts);
    }

    fn assert_error(id: &str, error: OutputPngError, expected: &SurfaceExpected) {
        assert_eq!(expected.code.as_deref(), error.code(), "{id}");
        assert_eq!(expected.stage.as_deref(), error.stage(), "{id}");
        assert_eq!(expected.limit_id.as_deref(), error.limit_id(), "{id}");
    }

    fn pixels(case: &PngCase) -> Vec<u8> {
        let seed = hex::decode(&case.pixels.hex).expect("pixel fixture hex");
        match case.pixels.kind.as_str() {
            "EXACT_HEX" => seed,
            "SOLID_RGBA" => {
                assert_eq!(4, seed.len());
                seed.repeat((case.width_px as usize) * (case.height_px as usize))
            }
            other => panic!("unknown pixel fixture {other}"),
        }
    }

    struct Inspection {
        chunk_types: Vec<String>,
        pixels_per_meter: u32,
        idat_payload_lengths: Vec<usize>,
        stored_block_lengths: Vec<usize>,
    }

    fn inspect_png(encoded: &[u8]) -> Inspection {
        assert_eq!(b"\x89PNG\r\n\x1a\n", &encoded[..8]);
        let mut offset = 8;
        let mut chunk_types = Vec::new();
        let mut idat = Vec::new();
        let mut idat_payload_lengths = Vec::new();
        let mut pixels_per_meter = None;
        while offset < encoded.len() {
            let length =
                u32::from_be_bytes(encoded[offset..offset + 4].try_into().unwrap()) as usize;
            let kind = &encoded[offset + 4..offset + 8];
            let data = &encoded[offset + 8..offset + 8 + length];
            let crc = u32::from_be_bytes(
                encoded[offset + 8 + length..offset + 12 + length]
                    .try_into()
                    .unwrap(),
            );
            let mut crc_input = Vec::with_capacity(4 + length);
            crc_input.extend_from_slice(kind);
            crc_input.extend_from_slice(data);
            assert_eq!(crc, crc32(&crc_input));
            let name = std::str::from_utf8(kind).unwrap().to_owned();
            if name == "pHYs" {
                let x = u32::from_be_bytes(data[0..4].try_into().unwrap());
                let y = u32::from_be_bytes(data[4..8].try_into().unwrap());
                assert_eq!(x, y);
                assert_eq!(1, data[8]);
                pixels_per_meter = Some(x);
            } else if name == "IDAT" {
                idat_payload_lengths.push(length);
                idat.extend_from_slice(data);
            }
            chunk_types.push(name);
            offset += 12 + length;
        }
        assert_eq!(offset, encoded.len());
        assert_eq!([0x78, 0x01], idat[..2]);
        let mut cursor = 2;
        let mut stored_block_lengths = Vec::new();
        loop {
            let header = idat[cursor];
            cursor += 1;
            assert!(header == 0 || header == 1);
            let length = u16::from_le_bytes(idat[cursor..cursor + 2].try_into().unwrap());
            let complement = u16::from_le_bytes(idat[cursor + 2..cursor + 4].try_into().unwrap());
            assert_eq!(length ^ 0xffff, complement);
            cursor += 4 + usize::from(length);
            stored_block_lengths.push(usize::from(length));
            if header == 1 {
                break;
            }
        }
        assert_eq!(cursor + 4, idat.len());
        Inspection {
            chunk_types,
            pixels_per_meter: pixels_per_meter.unwrap(),
            idat_payload_lengths,
            stored_block_lengths,
        }
    }

    fn crc32(bytes: &[u8]) -> u32 {
        let mut crc = 0xffff_ffff_u32;
        for byte in bytes {
            crc ^= u32::from(*byte);
            for _ in 0..8 {
                crc = (crc >> 1) ^ (0xedb8_8320_u32 & 0_u32.wrapping_sub(crc & 1));
            }
        }
        !crc
    }
}
