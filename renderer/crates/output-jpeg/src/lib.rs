//! Exact `renderweave-output-jpeg/1.0` kernel.

#[cfg(feature = "native-jpeg-turbo")]
use std::ffi::c_void;
use std::fmt::{Display, Formatter};
#[cfg(feature = "native-jpeg-turbo")]
use std::panic::{AssertUnwindSafe, catch_unwind};
#[cfg(feature = "native-jpeg-turbo")]
use std::ptr;

#[cfg(feature = "native-jpeg-turbo")]
use sha2::{Digest, Sha256};

pub const OUTPUT_PROFILE: &str = "renderweave-output-jpeg/1.0";

const MAX_DPI: u32 = 600;
const MAX_SURFACE_EDGE_PIXELS: u32 = 16_384;
const MAX_SURFACE_PIXELS: u64 = 50_000_000;
const MAX_RGBA8_SURFACE_BYTES: u64 = 200_000_000;
#[cfg(feature = "native-jpeg-turbo")]
const MAX_ENCODER_SCRATCH_BYTES: usize = 67_108_864;
const MAX_ENCODED_IMAGE_BYTES: usize = 536_870_912;
const OUTPUT_BUDGET_EXCEEDED: &str = "OUTPUT_BUDGET_EXCEEDED";
const RASTER_BUDGET_EXCEEDED: &str = "RASTER_BUDGET_EXCEEDED";
const OUTPUT_PREFLIGHT: &str = "OUTPUT_PREFLIGHT";
const ENCODING: &str = "ENCODING";

const LUMA_BASE: [u8; 64] = [
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113,
    92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
];
const CHROMA_BASE: [u8; 64] = [
    17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
];

#[repr(C)]
struct AnnexKHuffmanTables {
    dc_luma_bits: [u8; 17],
    dc_luma_values: [u8; 12],
    ac_luma_bits: [u8; 17],
    ac_luma_values: [u8; 162],
    dc_chroma_bits: [u8; 17],
    dc_chroma_values: [u8; 12],
    ac_chroma_bits: [u8; 17],
    ac_chroma_values: [u8; 162],
}

const ANNEX_K_HUFFMAN_TABLES: AnnexKHuffmanTables = AnnexKHuffmanTables {
    dc_luma_bits: [0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0],
    dc_luma_values: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
    ac_luma_bits: [0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d],
    ac_luma_values: [
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61,
        0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52,
        0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25,
        0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45,
        0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64,
        0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83,
        0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99,
        0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
        0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3,
        0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8,
        0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa,
    ],
    dc_chroma_bits: [0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0],
    dc_chroma_values: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
    ac_chroma_bits: [0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77],
    ac_chroma_values: [
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61,
        0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33,
        0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18,
        0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44,
        0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63,
        0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a,
        0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
        0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4,
        0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca,
        0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7,
        0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa,
    ],
};

#[derive(Debug, Eq, PartialEq)]
pub enum OutputJpegError {
    Contract(&'static str),
    Interrupted(JpegEncodeInterruption),
    Budget {
        code: &'static str,
        stage: &'static str,
        limit_id: &'static str,
    },
    Native,
}

impl OutputJpegError {
    pub const fn code(&self) -> Option<&'static str> {
        match self {
            Self::Contract(_) | Self::Native => None,
            Self::Interrupted(JpegEncodeInterruption::Cancelled) => Some("RENDER_CANCELLED"),
            Self::Interrupted(JpegEncodeInterruption::DeadlineExceeded) => {
                Some("RENDER_DEADLINE_EXCEEDED")
            }
            Self::Budget { code, .. } => Some(code),
        }
    }

    pub const fn stage(&self) -> Option<&'static str> {
        match self {
            Self::Contract(_) | Self::Native => None,
            Self::Interrupted(_) => Some(ENCODING),
            Self::Budget { stage, .. } => Some(stage),
        }
    }

    pub const fn limit_id(&self) -> Option<&'static str> {
        match self {
            Self::Budget { limit_id, .. } => Some(limit_id),
            _ => None,
        }
    }
}

impl Display for OutputJpegError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Contract(message) => formatter.write_str(message),
            Self::Interrupted(interruption) => {
                write!(formatter, "JPEG encoding was {interruption:?}")
            }
            Self::Budget {
                code,
                stage,
                limit_id,
            } => write!(formatter, "{code} at {stage} ({limit_id})"),
            Self::Native => formatter.write_str("exact native JPEG encoder failed"),
        }
    }
}

impl std::error::Error for OutputJpegError {}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum JpegEncodeInterruption {
    Cancelled,
    DeadlineExceeded,
}

pub trait JpegEncodeControl: Sync {
    fn checkpoint(&self) -> Result<(), JpegEncodeInterruption>;
}

struct UnrestrictedJpegEncode;

impl JpegEncodeControl for UnrestrictedJpegEncode {
    fn checkpoint(&self) -> Result<(), JpegEncodeInterruption> {
        Ok(())
    }
}

static UNRESTRICTED_JPEG_ENCODE: UnrestrictedJpegEncode = UnrestrictedJpegEncode;

struct QuantizationTables {
    luma: [u8; 64],
    chroma: [u8; 64],
}

fn quantization_tables(quality: u8) -> Result<QuantizationTables, OutputJpegError> {
    if !(1..=100).contains(&quality) {
        return Err(OutputJpegError::Contract(
            "JPEG quality must be an integer from 1 through 100",
        ));
    }
    let quality = u32::from(quality);
    let scale = if quality < 50 {
        5_000 / quality
    } else {
        200 - 2 * quality
    };
    Ok(QuantizationTables {
        luma: scaled_quantization_table(&LUMA_BASE, scale),
        chroma: scaled_quantization_table(&CHROMA_BASE, scale),
    })
}

fn scaled_quantization_table(base: &[u8; 64], scale: u32) -> [u8; 64] {
    std::array::from_fn(|index| ((u32::from(base[index]) * scale + 50) / 100).clamp(1, 255) as u8)
}

fn matte_straight_rgba8_on_white(rgba: &[u8]) -> Result<Vec<u8>, OutputJpegError> {
    let chunks = rgba.chunks_exact(4);
    if !chunks.remainder().is_empty() {
        return Err(OutputJpegError::Contract(
            "straight RGBA8 input length must be divisible by four",
        ));
    }
    let mut rgb = Vec::new();
    rgb.try_reserve_exact(rgba.len() / 4 * 3)
        .map_err(|_| OutputJpegError::Contract("JPEG matte allocation failed"))?;
    for pixel in chunks {
        let alpha = u16::from(pixel[3]);
        if alpha == 0 && pixel[..3] != [0, 0, 0] {
            return Err(OutputJpegError::Contract(
                "fully transparent straight RGBA8 pixels must have zero RGB",
            ));
        }
        for channel in &pixel[..3] {
            let premultiplied = (u16::from(*channel) * alpha + 127) / 255;
            rgb.push((premultiplied + 255 - alpha) as u8);
        }
    }
    Ok(rgb)
}

pub fn encode_straight_rgba8(
    width_px: u32,
    height_px: u32,
    dpi: u32,
    quality: u8,
    pixels: &[u8],
) -> Result<Vec<u8>, OutputJpegError> {
    encode_straight_rgba8_controlled(
        width_px,
        height_px,
        dpi,
        quality,
        pixels,
        &UNRESTRICTED_JPEG_ENCODE,
    )
}

pub fn encode_straight_rgba8_controlled(
    width_px: u32,
    height_px: u32,
    dpi: u32,
    quality: u8,
    pixels: &[u8],
    control: &dyn JpegEncodeControl,
) -> Result<Vec<u8>, OutputJpegError> {
    control.checkpoint().map_err(OutputJpegError::Interrupted)?;
    validate_surface(width_px, height_px, dpi, pixels)?;
    let tables = quantization_tables(quality)?;
    let rgb = matte_controlled(width_px, height_px, pixels, control)?;
    let icc = canonical_icc()?;
    let encoded = native_encode(width_px, height_px, dpi, &rgb, &tables, icc, control)?;
    validate_encoded_jpeg(&encoded, width_px, height_px, dpi, &tables, icc)?;
    control.checkpoint().map_err(OutputJpegError::Interrupted)?;
    Ok(encoded)
}

fn validate_surface(
    width_px: u32,
    height_px: u32,
    dpi: u32,
    pixels: &[u8],
) -> Result<(), OutputJpegError> {
    if dpi == 0 || dpi > MAX_DPI {
        return Err(output_budget("rendererSurfaceAndOutput.dpi"));
    }
    if width_px == 0 || height_px == 0 {
        return Err(output_budget("rendererSurfaceAndOutput.surfacePixels"));
    }
    if width_px > MAX_SURFACE_EDGE_PIXELS || height_px > MAX_SURFACE_EDGE_PIXELS {
        return Err(output_budget("rendererSurfaceAndOutput.surfaceEdgePixels"));
    }
    let pixel_count = u64::from(width_px)
        .checked_mul(u64::from(height_px))
        .ok_or_else(|| output_budget("rendererSurfaceAndOutput.surfacePixels"))?;
    if pixel_count > MAX_SURFACE_PIXELS {
        return Err(output_budget("rendererSurfaceAndOutput.surfacePixels"));
    }
    let rgba_length = pixel_count
        .checked_mul(4)
        .ok_or_else(|| raster_budget("rendererSurfaceAndOutput.rgba8SurfaceBytes"))?;
    if rgba_length > MAX_RGBA8_SURFACE_BYTES {
        return Err(raster_budget("rendererSurfaceAndOutput.rgba8SurfaceBytes"));
    }
    if usize::try_from(rgba_length).ok() != Some(pixels.len()) {
        return Err(OutputJpegError::Contract(
            "straight RGBA8 byte length does not match surface",
        ));
    }
    Ok(())
}

fn matte_controlled(
    width_px: u32,
    height_px: u32,
    rgba: &[u8],
    control: &dyn JpegEncodeControl,
) -> Result<Vec<u8>, OutputJpegError> {
    let row_rgba = usize::try_from(width_px)
        .ok()
        .and_then(|width| width.checked_mul(4))
        .ok_or(OutputJpegError::Contract("JPEG row length overflow"))?;
    let mut rgb = Vec::new();
    rgb.try_reserve_exact(rgba.len() / 4 * 3)
        .map_err(|_| output_budget("rendererSurfaceAndOutput.requestEphemeralBytes"))?;
    for row in 0..usize::try_from(height_px)
        .map_err(|_| OutputJpegError::Contract("JPEG row count overflow"))?
    {
        control.checkpoint().map_err(OutputJpegError::Interrupted)?;
        let start = row
            .checked_mul(row_rgba)
            .ok_or(OutputJpegError::Contract("JPEG row offset overflow"))?;
        let end = start
            .checked_add(row_rgba)
            .ok_or(OutputJpegError::Contract("JPEG row offset overflow"))?;
        rgb.extend_from_slice(&matte_straight_rgba8_on_white(&rgba[start..end])?);
    }
    Ok(rgb)
}

fn output_budget(limit_id: &'static str) -> OutputJpegError {
    OutputJpegError::Budget {
        code: OUTPUT_BUDGET_EXCEEDED,
        stage: OUTPUT_PREFLIGHT,
        limit_id,
    }
}

fn raster_budget(limit_id: &'static str) -> OutputJpegError {
    OutputJpegError::Budget {
        code: RASTER_BUDGET_EXCEEDED,
        stage: OUTPUT_PREFLIGHT,
        limit_id,
    }
}

#[cfg(feature = "native-jpeg-turbo")]
fn canonical_icc() -> Result<&'static [u8], OutputJpegError> {
    static ICC: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/canonical-srgb.icc"));
    const EXPECTED: [u8; 32] = [
        0x2b, 0x3a, 0xa1, 0x64, 0x57, 0x79, 0xa9, 0xe6, 0x34, 0x74, 0x4f, 0xaf, 0x9b, 0x01, 0xe9,
        0x10, 0x2b, 0x0c, 0x9b, 0x88, 0xfd, 0x6d, 0xec, 0xed, 0x79, 0x34, 0xdf, 0x86, 0xb9, 0x49,
        0xaf, 0x7e,
    ];
    if ICC.len() != 3_144 || Sha256::digest(ICC).as_slice() != EXPECTED {
        return Err(OutputJpegError::Contract(
            "canonical sRGB ICC identity drifted",
        ));
    }
    Ok(ICC)
}

#[cfg(not(feature = "native-jpeg-turbo"))]
fn canonical_icc() -> Result<&'static [u8], OutputJpegError> {
    Err(OutputJpegError::Contract(
        "native JPEG target is not available in this build",
    ))
}

#[cfg(feature = "native-jpeg-turbo")]
struct NativeControlContext<'a> {
    control: &'a dyn JpegEncodeControl,
    interruption: Option<JpegEncodeInterruption>,
    panicked: bool,
}

#[cfg(feature = "native-jpeg-turbo")]
unsafe extern "C" {
    fn renderweave_encode_jpeg(
        rgb: *const u8,
        width: u32,
        height: u32,
        dpi: u16,
        luma_table: *const u8,
        chroma_table: *const u8,
        huffman_tables: *const AnnexKHuffmanTables,
        icc: *const u8,
        icc_length: usize,
        encoder_scratch_bytes: usize,
        checkpoint: unsafe extern "C" fn(*mut c_void) -> i32,
        checkpoint_context: *mut c_void,
        output: *mut *mut u8,
        output_length: *mut usize,
    ) -> i32;
    fn renderweave_free_jpeg(output: *mut u8);
}

#[cfg(feature = "native-jpeg-turbo")]
unsafe extern "C" fn native_checkpoint(context: *mut c_void) -> i32 {
    // SAFETY: `native_encode` supplies this exact context for the duration of the native call.
    let context = unsafe { &mut *(context.cast::<NativeControlContext<'_>>()) };
    match catch_unwind(AssertUnwindSafe(|| context.control.checkpoint())) {
        Ok(Ok(())) => 0,
        Ok(Err(interruption)) => {
            context.interruption = Some(interruption);
            1
        }
        Err(_) => {
            context.panicked = true;
            1
        }
    }
}

#[cfg(feature = "native-jpeg-turbo")]
fn native_encode(
    width_px: u32,
    height_px: u32,
    dpi: u32,
    rgb: &[u8],
    tables: &QuantizationTables,
    icc: &[u8],
    control: &dyn JpegEncodeControl,
) -> Result<Vec<u8>, OutputJpegError> {
    let dpi = u16::try_from(dpi).map_err(|_| output_budget("rendererSurfaceAndOutput.dpi"))?;
    let mut context = NativeControlContext {
        control,
        interruption: None,
        panicked: false,
    };
    let mut output = ptr::null_mut();
    let mut output_length = 0_usize;
    // SAFETY: every pointer remains valid for the synchronous call; native output is copied and
    // released with the matching allocator before this function returns.
    let status = unsafe {
        renderweave_encode_jpeg(
            rgb.as_ptr(),
            width_px,
            height_px,
            dpi,
            tables.luma.as_ptr(),
            tables.chroma.as_ptr(),
            &ANNEX_K_HUFFMAN_TABLES,
            icc.as_ptr(),
            icc.len(),
            MAX_ENCODER_SCRATCH_BYTES,
            native_checkpoint,
            (&mut context as *mut NativeControlContext<'_>).cast(),
            &mut output,
            &mut output_length,
        )
    };
    if let Some(interruption) = context.interruption {
        return Err(OutputJpegError::Interrupted(interruption));
    }
    if context.panicked || status != 0 || output.is_null() || output_length == 0 {
        if !output.is_null() {
            // SAFETY: the pointer came from the native encoder allocator.
            unsafe { renderweave_free_jpeg(output) };
        }
        return Err(OutputJpegError::Native);
    }
    if output_length > MAX_ENCODED_IMAGE_BYTES {
        // SAFETY: the pointer came from the native encoder allocator.
        unsafe { renderweave_free_jpeg(output) };
        return Err(OutputJpegError::Budget {
            code: OUTPUT_BUDGET_EXCEEDED,
            stage: ENCODING,
            limit_id: "rendererSurfaceAndOutput.encodedImageBytes",
        });
    }
    // SAFETY: successful native output guarantees `output_length` initialized bytes.
    let encoded = unsafe { std::slice::from_raw_parts(output, output_length) }.to_vec();
    // SAFETY: the pointer came from the native encoder allocator and is no longer borrowed.
    unsafe { renderweave_free_jpeg(output) };
    Ok(encoded)
}

#[cfg(not(feature = "native-jpeg-turbo"))]
fn native_encode(
    _width_px: u32,
    _height_px: u32,
    _dpi: u32,
    _rgb: &[u8],
    _tables: &QuantizationTables,
    _icc: &[u8],
    _control: &dyn JpegEncodeControl,
) -> Result<Vec<u8>, OutputJpegError> {
    Err(OutputJpegError::Contract(
        "native JPEG target is not available in this build",
    ))
}

const ZIGZAG_TO_NATURAL: [usize; 64] = [
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20,
    13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59,
    52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
];

fn validate_encoded_jpeg(
    encoded: &[u8],
    width_px: u32,
    height_px: u32,
    dpi: u32,
    tables: &QuantizationTables,
    icc: &[u8],
) -> Result<(), OutputJpegError> {
    if encoded.len() > MAX_ENCODED_IMAGE_BYTES || !encoded.starts_with(&[0xff, 0xd8]) {
        return Err(OutputJpegError::Contract("JPEG SOI is absent"));
    }
    let mut offset = 2_usize;
    let mut markers: Vec<(u8, &[u8])> = Vec::new();
    while offset < encoded.len() {
        if encoded.get(offset) != Some(&0xff) {
            return Err(OutputJpegError::Contract("JPEG marker framing drifted"));
        }
        while encoded.get(offset) == Some(&0xff) {
            offset += 1;
        }
        let marker = *encoded
            .get(offset)
            .ok_or(OutputJpegError::Contract("JPEG marker is truncated"))?;
        offset += 1;
        if marker == 0xd9 {
            return Err(OutputJpegError::Contract("JPEG EOI preceded SOS"));
        }
        let length = encoded
            .get(offset..offset + 2)
            .and_then(|bytes| bytes.try_into().ok())
            .map(u16::from_be_bytes)
            .filter(|length| *length >= 2)
            .ok_or(OutputJpegError::Contract("JPEG segment length is invalid"))?
            as usize;
        let data_start = offset + 2;
        let data_end = offset
            .checked_add(length)
            .filter(|end| *end <= encoded.len())
            .ok_or(OutputJpegError::Contract("JPEG segment is truncated"))?;
        markers.push((marker, &encoded[data_start..data_end]));
        offset = data_end;
        if marker == 0xda {
            break;
        }
    }
    let expected = [0xe0, 0xe2, 0xdb, 0xdb, 0xc0, 0xc4, 0xc4, 0xc4, 0xc4, 0xda];
    if markers.iter().map(|entry| entry.0).collect::<Vec<_>>() != expected {
        return Err(OutputJpegError::Contract("JPEG marker order drifted"));
    }
    validate_app0(markers[0].1, dpi)?;
    let mut expected_icc = Vec::with_capacity(14 + icc.len());
    expected_icc.extend_from_slice(b"ICC_PROFILE\0\x01\x01");
    expected_icc.extend_from_slice(icc);
    if markers[1].1 != expected_icc {
        return Err(OutputJpegError::Contract("JPEG ICC marker drifted"));
    }
    validate_dqt(markers[2].1, 0, &tables.luma)?;
    validate_dqt(markers[3].1, 1, &tables.chroma)?;
    validate_sof0(markers[4].1, width_px, height_px)?;
    let huffman_tables: [(u8, &[u8], &[u8]); 4] = [
        (
            0x00,
            &ANNEX_K_HUFFMAN_TABLES.dc_luma_bits,
            &ANNEX_K_HUFFMAN_TABLES.dc_luma_values,
        ),
        (
            0x10,
            &ANNEX_K_HUFFMAN_TABLES.ac_luma_bits,
            &ANNEX_K_HUFFMAN_TABLES.ac_luma_values,
        ),
        (
            0x01,
            &ANNEX_K_HUFFMAN_TABLES.dc_chroma_bits,
            &ANNEX_K_HUFFMAN_TABLES.dc_chroma_values,
        ),
        (
            0x11,
            &ANNEX_K_HUFFMAN_TABLES.ac_chroma_bits,
            &ANNEX_K_HUFFMAN_TABLES.ac_chroma_values,
        ),
    ];
    for (index, (table_id, bits, values)) in huffman_tables.into_iter().enumerate() {
        validate_dht(markers[5 + index].1, table_id, bits, values)?;
    }
    if markers[9].1 != [3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0] {
        return Err(OutputJpegError::Contract("JPEG SOS drifted"));
    }
    validate_entropy_tail(encoded, offset)
}

fn validate_app0(data: &[u8], dpi: u32) -> Result<(), OutputJpegError> {
    let dpi = u16::try_from(dpi).map_err(|_| OutputJpegError::Contract("JPEG DPI overflow"))?;
    let mut expected = Vec::from(&b"JFIF\0\x01\x02\x01"[..]);
    expected.extend_from_slice(&dpi.to_be_bytes());
    expected.extend_from_slice(&dpi.to_be_bytes());
    expected.extend_from_slice(&[0, 0]);
    if data != expected {
        return Err(OutputJpegError::Contract("JPEG JFIF marker drifted"));
    }
    Ok(())
}

fn validate_dqt(data: &[u8], table_id: u8, table: &[u8; 64]) -> Result<(), OutputJpegError> {
    if data.len() != 65 || data[0] != table_id {
        return Err(OutputJpegError::Contract("JPEG DQT framing drifted"));
    }
    let expected: [u8; 64] = std::array::from_fn(|index| table[ZIGZAG_TO_NATURAL[index]]);
    if data[1..] != expected {
        return Err(OutputJpegError::Contract("JPEG quantization table drifted"));
    }
    Ok(())
}

fn validate_sof0(data: &[u8], width_px: u32, height_px: u32) -> Result<(), OutputJpegError> {
    let width = u16::try_from(width_px)
        .map_err(|_| OutputJpegError::Contract("JPEG width exceeds SOF0"))?;
    let height = u16::try_from(height_px)
        .map_err(|_| OutputJpegError::Contract("JPEG height exceeds SOF0"))?;
    let mut expected = vec![8];
    expected.extend_from_slice(&height.to_be_bytes());
    expected.extend_from_slice(&width.to_be_bytes());
    expected.extend_from_slice(&[3, 1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1]);
    if data != expected {
        return Err(OutputJpegError::Contract("JPEG SOF0 drifted"));
    }
    Ok(())
}

fn validate_dht(
    data: &[u8],
    table_id: u8,
    bits: &[u8],
    values: &[u8],
) -> Result<(), OutputJpegError> {
    if bits.len() != 17 || bits[0] != 0 || data.len() < 17 || data[0] != table_id {
        return Err(OutputJpegError::Contract("JPEG DHT framing drifted"));
    }
    let symbol_count: usize = data[1..17].iter().map(|value| usize::from(*value)).sum();
    if symbol_count != values.len()
        || data.len() != 17 + symbol_count
        || data[1..17] != bits[1..17]
        || data[17..] != *values
    {
        return Err(OutputJpegError::Contract("JPEG Annex K table drifted"));
    }
    Ok(())
}

fn validate_entropy_tail(encoded: &[u8], mut offset: usize) -> Result<(), OutputJpegError> {
    while offset < encoded.len() {
        if encoded[offset] != 0xff {
            offset += 1;
            continue;
        }
        offset += 1;
        while encoded.get(offset) == Some(&0xff) {
            offset += 1;
        }
        match encoded.get(offset).copied() {
            Some(0x00) => offset += 1,
            Some(0xd9) if offset + 1 == encoded.len() => return Ok(()),
            Some(0xd0..=0xd7) => {
                return Err(OutputJpegError::Contract(
                    "JPEG restart marker is forbidden",
                ));
            }
            _ => return Err(OutputJpegError::Contract("JPEG entropy marker drifted")),
        }
    }
    Err(OutputJpegError::Contract("JPEG EOI is absent"))
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};

    use serde::Deserialize;
    use sha2::{Digest, Sha256};

    use super::{
        ANNEX_K_HUFFMAN_TABLES, CHROMA_BASE, JpegEncodeControl, JpegEncodeInterruption, LUMA_BASE,
        OutputJpegError, encode_straight_rgba8, encode_straight_rgba8_controlled,
    };

    const VECTORS: &str = include_str!("../../../output-jpeg-vectors-v1.json");

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Vectors {
        manifest_version: String,
        output_profile: String,
        input_contract: String,
        codec: Codec,
        quantization_base: QuantizationBase,
        limits: Limits,
        jpeg_cases: Vec<JpegCase>,
        boundary: Boundary,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Codec {
        source_version: String,
        source_sha256: String,
        cmake_archive_sha256: String,
        simd: bool,
        dct_method: String,
        coding: String,
        scans: u8,
        subsampling: String,
        optimized_huffman: bool,
        restart_interval: u32,
        smoothing: u8,
        adobe_app14: bool,
        icc_byte_length: usize,
        icc_sha256: String,
        annex_k_tables_sha256: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct QuantizationBase {
        luma: Vec<u8>,
        chroma: Vec<u8>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Limits {
        dpi: u32,
        surface_edge_pixels: u32,
        surface_pixels: u64,
        rgba8_surface_bytes: u64,
        encoder_scratch_bytes: usize,
        encoded_image_bytes: usize,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct JpegCase {
        id: String,
        width_px: u32,
        height_px: u32,
        dpi: u32,
        quality: u8,
        pixels: PixelSource,
        expected: JpegExpected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct PixelSource {
        kind: String,
        hex: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct JpegExpected {
        byte_length: usize,
        sha256: String,
        entropy_hex: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Boundary {
        profile_availability: String,
        certification_status: String,
        physical_host_certification: bool,
        provider_attempts: u32,
    }

    #[test]
    fn machine_manifest_pins_every_codec_authority_without_claiming_availability() {
        let vectors = vectors();
        assert_eq!(
            "renderweave-output-jpeg-vectors/1.0",
            vectors.manifest_version
        );
        assert_eq!("renderweave-output-jpeg/1.0", vectors.output_profile);
        assert_eq!("canonical-straight-rgba8-row-major", vectors.input_contract);
        assert_eq!("libjpeg-turbo-3.2.0", vectors.codec.source_version);
        assert_eq!(
            "sha256:6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e",
            vectors.codec.source_sha256
        );
        assert_eq!(
            "sha256:3ada9a3f5d8a85413579bdd0ea6aa8e8da86efdd6d15c91a1afa517f2021956c",
            vectors.codec.cmake_archive_sha256
        );
        assert!(!vectors.codec.simd);
        assert_eq!("JDCT_ISLOW", vectors.codec.dct_method);
        assert_eq!("BASELINE_SOF0_NON_ARITHMETIC", vectors.codec.coding);
        assert_eq!(1, vectors.codec.scans);
        assert_eq!("4:4:4", vectors.codec.subsampling);
        assert!(!vectors.codec.optimized_huffman);
        assert_eq!(0, vectors.codec.restart_interval);
        assert_eq!(0, vectors.codec.smoothing);
        assert!(!vectors.codec.adobe_app14);
        assert_eq!(3_144, vectors.codec.icc_byte_length);
        assert_eq!(
            "sha256:2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e",
            vectors.codec.icc_sha256
        );
        assert_eq!(LUMA_BASE, vectors.quantization_base.luma.as_slice());
        assert_eq!(CHROMA_BASE, vectors.quantization_base.chroma.as_slice());
        assert_eq!(super::MAX_DPI, vectors.limits.dpi);
        assert_eq!(
            super::MAX_SURFACE_EDGE_PIXELS,
            vectors.limits.surface_edge_pixels
        );
        assert_eq!(super::MAX_SURFACE_PIXELS, vectors.limits.surface_pixels);
        assert_eq!(
            super::MAX_RGBA8_SURFACE_BYTES,
            vectors.limits.rgba8_surface_bytes
        );
        assert_eq!(67_108_864, vectors.limits.encoder_scratch_bytes);
        assert_eq!(
            super::MAX_ENCODED_IMAGE_BYTES,
            vectors.limits.encoded_image_bytes
        );

        let huffman_sha256 = format!(
            "sha256:{}",
            hex::encode(Sha256::digest(packed_huffman_tables()))
        );
        if vectors.codec.annex_k_tables_sha256.is_empty() {
            eprintln!("T217_ANNEX_K_TABLES_SHA256={huffman_sha256}");
        }
        assert_eq!(vectors.codec.annex_k_tables_sha256, huffman_sha256);

        assert_eq!(8, vectors.jpeg_cases.len());
        assert_eq!(
            [90, 1, 24, 25, 49, 50, 99, 100],
            vectors
                .jpeg_cases
                .iter()
                .map(|case| case.quality)
                .collect::<Vec<_>>()
                .as_slice()
        );
        for case in &vectors.jpeg_cases {
            assert!(!case.id.is_empty());
            assert!(case.width_px > 0 && case.height_px > 0 && case.dpi > 0);
            assert!(matches!(
                case.pixels.kind.as_str(),
                "EXACT_HEX" | "RGBA_PATTERN"
            ));
            if case.pixels.kind == "EXACT_HEX" {
                assert_eq!(
                    u64::from(case.width_px) * u64::from(case.height_px) * 8,
                    case.pixels.hex.len() as u64
                );
            } else {
                assert!(case.pixels.hex.is_empty());
            }
            if case.expected.entropy_hex.is_empty() {
                assert_eq!(0, case.expected.byte_length);
                assert!(case.expected.sha256.is_empty());
            } else {
                assert_eq!(0, case.expected.entropy_hex.len() % 2);
                assert!(case.expected.sha256.starts_with("sha256:"));
            }
        }

        assert_eq!("NOT_REGISTERED", vectors.boundary.profile_availability);
        assert_eq!("NOT_CERTIFIED", vectors.boundary.certification_status);
        assert!(!vectors.boundary.physical_host_certification);
        assert_eq!(0, vectors.boundary.provider_attempts);
    }

    #[test]
    fn frozen_quality_formula_covers_every_public_boundary() {
        let base_luma = [16_u32, 11, 10, 16, 24, 40, 51, 61];
        for quality in [1_u8, 24, 25, 49, 50, 90, 99, 100] {
            let tables = super::quantization_tables(quality).expect("valid public quality");
            let scale = if quality < 50 {
                5_000 / u32::from(quality)
            } else {
                200 - 2 * u32::from(quality)
            };
            for (index, base) in base_luma.into_iter().enumerate() {
                let expected = ((base * scale + 50) / 100).clamp(1, 255) as u8;
                assert_eq!(
                    expected, tables.luma[index],
                    "quality={quality} index={index}"
                );
            }
        }
        assert!(super::quantization_tables(0).is_err());
        assert!(super::quantization_tables(101).is_err());
    }

    #[test]
    fn white_matte_uses_frozen_integer_source_over() {
        let rgba = [0_u8, 0, 0, 0, 200, 100, 0, 128, 10, 20, 30, 255];
        assert_eq!(
            vec![255, 255, 255, 227, 177, 127, 10, 20, 30],
            super::matte_straight_rgba8_on_white(&rgba).expect("canonical RGBA8")
        );
        assert!(super::matte_straight_rgba8_on_white(&[1, 2, 3, 0]).is_err());
        assert!(super::matte_straight_rgba8_on_white(&[0, 0, 0]).is_err());
    }

    #[test]
    fn malformed_surface_quality_and_pixels_fail_closed_before_native_encoding() {
        assert_budget(
            encode_straight_rgba8(0, 1, 96, 90, &[]),
            "rendererSurfaceAndOutput.surfacePixels",
        );
        assert_budget(
            encode_straight_rgba8(1, 1, 601, 90, &[0, 0, 0, 0]),
            "rendererSurfaceAndOutput.dpi",
        );
        assert_budget(
            encode_straight_rgba8(16_385, 1, 96, 90, &[]),
            "rendererSurfaceAndOutput.surfaceEdgePixels",
        );
        assert!(matches!(
            encode_straight_rgba8(1, 1, 96, 0, &[0, 0, 0, 0]),
            Err(OutputJpegError::Contract(_))
        ));
        assert!(matches!(
            encode_straight_rgba8(1, 1, 96, 90, &[0, 0, 0]),
            Err(OutputJpegError::Contract(_))
        ));
        assert!(matches!(
            encode_straight_rgba8(1, 1, 96, 90, &[1, 0, 0, 0]),
            Err(OutputJpegError::Contract(_))
        ));
    }

    #[test]
    fn cancellation_and_deadline_interrupt_white_matte_before_native_encoding() {
        for interruption in [
            JpegEncodeInterruption::Cancelled,
            JpegEncodeInterruption::DeadlineExceeded,
        ] {
            let control = InterruptAt {
                calls: AtomicUsize::new(0),
                target: 2,
                interruption,
            };
            let result = encode_straight_rgba8_controlled(2, 2, 96, 90, &[0; 16], &control);
            assert_eq!(Err(OutputJpegError::Interrupted(interruption)), result);
            assert_eq!(2, control.calls.load(Ordering::SeqCst));
        }
    }

    #[cfg(feature = "native-jpeg-turbo")]
    #[test]
    fn exact_native_corpus_is_byte_stable_and_exercises_padding_stuffing_and_white_matte() {
        let vectors = vectors();
        let qualities: Vec<u8> = vectors.jpeg_cases.iter().map(|case| case.quality).collect();
        assert_eq!([90, 1, 24, 25, 49, 50, 99, 100], qualities.as_slice());
        assert!(
            vectors
                .jpeg_cases
                .iter()
                .any(|case| case.width_px == 1 && case.height_px == 1)
        );
        assert!(
            vectors
                .jpeg_cases
                .iter()
                .any(|case| case.width_px % 8 != 0 || case.height_px % 8 != 0)
        );

        let mut missing_golden = false;
        let mut observed_entropy_stuffing = false;
        for case in &vectors.jpeg_cases {
            let pixels = pixels(case);
            let encoded = encode_straight_rgba8(
                case.width_px,
                case.height_px,
                case.dpi,
                case.quality,
                &pixels,
            )
            .unwrap_or_else(|error| panic!("{} unexpectedly rejected: {error}", case.id));
            let replay = encode_straight_rgba8(
                case.width_px,
                case.height_px,
                case.dpi,
                case.quality,
                &pixels,
            )
            .unwrap_or_else(|error| panic!("{} did not replay: {error}", case.id));
            assert_eq!(encoded, replay, "{}", case.id);

            let sha256 = format!("sha256:{}", hex::encode(Sha256::digest(&encoded)));
            let entropy_hex = hex::encode(entropy_bytes(&encoded));
            if case.expected.entropy_hex.is_empty() {
                eprintln!(
                    "T217_JPEG_GOLDEN id={} byteLength={} sha256={} entropyHex={}",
                    case.id,
                    encoded.len(),
                    sha256,
                    entropy_hex
                );
                missing_golden = true;
            } else {
                assert_eq!(case.expected.byte_length, encoded.len(), "{}", case.id);
                assert_eq!(case.expected.sha256, sha256, "{}", case.id);
                assert_eq!(case.expected.entropy_hex, entropy_hex, "{}", case.id);
            }
            observed_entropy_stuffing |= entropy_has_stuffed_ff(&encoded);

            if case.id == "transparent-white-matte-1x1-q90" {
                let opaque_white = encode_straight_rgba8(1, 1, case.dpi, case.quality, &[255; 4])
                    .expect("opaque white JPEG");
                assert_eq!(
                    encoded, opaque_white,
                    "transparent black must matte to white"
                );
            }
        }
        assert!(
            observed_entropy_stuffing,
            "corpus did not exercise entropy byte stuffing"
        );
        assert!(
            !missing_golden,
            "exact JPEG golden bytes have not been frozen"
        );
    }

    #[cfg(feature = "native-jpeg-turbo")]
    #[test]
    fn native_scanlines_are_cooperative_and_structural_drift_is_rejected() {
        let vectors = vectors();
        let case = vectors
            .jpeg_cases
            .iter()
            .find(|case| case.id == "pattern-9x7-q100")
            .expect("non-MCU-aligned exact JPEG vector");
        let pixels = pixels(case);

        for (target, interruption) in [
            (11, JpegEncodeInterruption::Cancelled),
            (17, JpegEncodeInterruption::DeadlineExceeded),
        ] {
            let control = InterruptAt {
                calls: AtomicUsize::new(0),
                target,
                interruption,
            };
            let result = encode_straight_rgba8_controlled(
                case.width_px,
                case.height_px,
                case.dpi,
                case.quality,
                &pixels,
                &control,
            );
            assert_eq!(Err(OutputJpegError::Interrupted(interruption)), result);
            assert_eq!(target, control.calls.load(Ordering::SeqCst));
        }

        let encoded = encode_straight_rgba8(
            case.width_px,
            case.height_px,
            case.dpi,
            case.quality,
            &pixels,
        )
        .expect("valid exact JPEG");
        let tables = super::quantization_tables(case.quality).expect("valid quality");
        let icc = super::canonical_icc().expect("canonical ICC profile");

        let mut marker_drift = encoded.clone();
        marker_drift[3] = 0xe1;
        assert!(
            super::validate_encoded_jpeg(
                &marker_drift,
                case.width_px,
                case.height_px,
                case.dpi,
                &tables,
                icc,
            )
            .is_err()
        );

        let mut huffman_drift = encoded.clone();
        let dht_offset = huffman_drift
            .windows(2)
            .position(|window| window == [0xff, 0xc4])
            .expect("DHT marker");
        huffman_drift[dht_offset + 22] ^= 1;
        assert!(
            super::validate_encoded_jpeg(
                &huffman_drift,
                case.width_px,
                case.height_px,
                case.dpi,
                &tables,
                icc,
            )
            .is_err()
        );

        let mut trailing_bytes = encoded;
        trailing_bytes.push(0);
        assert!(
            super::validate_encoded_jpeg(
                &trailing_bytes,
                case.width_px,
                case.height_px,
                case.dpi,
                &tables,
                icc,
            )
            .is_err()
        );
    }

    struct InterruptAt {
        calls: AtomicUsize,
        target: usize,
        interruption: JpegEncodeInterruption,
    }

    impl JpegEncodeControl for InterruptAt {
        fn checkpoint(&self) -> Result<(), JpegEncodeInterruption> {
            let observed = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
            if observed >= self.target {
                Err(self.interruption)
            } else {
                Ok(())
            }
        }
    }

    fn assert_budget(result: Result<Vec<u8>, OutputJpegError>, limit_id: &'static str) {
        assert!(matches!(
            result,
            Err(OutputJpegError::Budget {
                code: "OUTPUT_BUDGET_EXCEEDED",
                stage: "OUTPUT_PREFLIGHT",
                limit_id: actual,
            }) if actual == limit_id
        ));
    }

    fn vectors() -> Vectors {
        serde_json::from_str(VECTORS).expect("exact JPEG vectors")
    }

    fn packed_huffman_tables() -> Vec<u8> {
        let mut packed = Vec::new();
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.dc_luma_bits);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.dc_luma_values);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.ac_luma_bits);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.ac_luma_values);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.dc_chroma_bits);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.dc_chroma_values);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.ac_chroma_bits);
        packed.extend_from_slice(&ANNEX_K_HUFFMAN_TABLES.ac_chroma_values);
        packed
    }

    #[cfg(feature = "native-jpeg-turbo")]
    fn pixels(case: &JpegCase) -> Vec<u8> {
        match case.pixels.kind.as_str() {
            "EXACT_HEX" => hex::decode(&case.pixels.hex).expect("exact pixel hex"),
            "RGBA_PATTERN" => {
                let mut pixels = Vec::with_capacity((case.width_px * case.height_px * 4) as usize);
                for y in 0..case.height_px {
                    for x in 0..case.width_px {
                        let alpha = [0_u8, 64, 128, 255][((x + 2 * y) % 4) as usize];
                        if alpha == 0 {
                            pixels.extend_from_slice(&[0, 0, 0, 0]);
                        } else {
                            pixels.extend_from_slice(&[
                                (31 * x + 17 * y) as u8,
                                (7 * x + 47 * y) as u8,
                                (61 * x + 3 * y) as u8,
                                alpha,
                            ]);
                        }
                    }
                }
                pixels
            }
            other => panic!("unknown pixel source {other}"),
        }
    }

    #[cfg(feature = "native-jpeg-turbo")]
    fn entropy_has_stuffed_ff(encoded: &[u8]) -> bool {
        entropy_bytes(encoded)
            .windows(2)
            .any(|window| window == [0xff, 0x00])
    }

    #[cfg(feature = "native-jpeg-turbo")]
    fn entropy_bytes(encoded: &[u8]) -> &[u8] {
        let mut offset = 2_usize;
        while offset + 4 <= encoded.len() {
            assert_eq!(0xff, encoded[offset]);
            let marker = encoded[offset + 1];
            let length = u16::from_be_bytes([encoded[offset + 2], encoded[offset + 3]]) as usize;
            if marker == 0xda {
                return &encoded[offset + 2 + length..encoded.len() - 2];
            }
            offset += 2 + length;
        }
        panic!("JPEG SOS is absent")
    }
}
