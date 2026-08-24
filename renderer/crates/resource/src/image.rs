use crate::media::{
    PreparedRawResource, ResourcePreparationProblem, ResourcePreparationProblemCode,
    ResourcePreparationProfile, ensure_lease_active, verify_resource_media,
};
use image_webp::{UpsamplingMethod, WebPDecodeOptions, WebPDecoder};
use jpeg_decoder::PixelFormat;
use renderweave_renderer_document::{
    AdmittedRenderResource, ImageOrientation, RenderResourceKind, RenderResourceMediaType,
};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fmt::{Debug, Formatter};
use std::io::Cursor;
use std::sync::Arc;

pub const MAX_REQUEST_DECODED_CACHE_BYTES: u64 = 536_870_912;
pub const REQUEST_DECODED_CACHE_BYTES_LIMIT_ID: &str = "assetsAndFetch.requestDecodedCacheBytes";
pub const MAX_DECODER_SCRATCH_BYTES: usize = 134_217_728;
pub const DECODER_SCRATCH_BYTES_LIMIT_ID: &str = "rendererSurfaceAndOutput.decoderScratchBytes";

const CANONICAL_SRGB_ICC: &[u8] = include_bytes!(
    "../../../../renderweave-asset/src/main/resources/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc"
);

#[derive(Eq, Ord, PartialEq, PartialOrd)]
struct DecodedImageCacheKey {
    profile: ResourcePreparationProfile,
    kind: RenderResourceKind,
    sha256: Box<str>,
    byte_length: u64,
    media_type: RenderResourceMediaType,
}

impl DecodedImageCacheKey {
    fn new(resource: &AdmittedRenderResource, profile: ResourcePreparationProfile) -> Self {
        Self {
            profile,
            kind: resource.kind(),
            sha256: resource.sha256().into(),
            byte_length: resource.byte_length(),
            media_type: resource.media_type(),
        }
    }
}

struct DecodedImageContent {
    width_px: u32,
    height_px: u32,
    rgba8: Arc<[u8]>,
    pixel_sha256: [u8; 32],
}

impl DecodedImageContent {
    fn is_intact(&self, expected_width: u32, expected_height: u32) -> bool {
        if self.width_px != expected_width || self.height_px != expected_height {
            return false;
        }
        let Some(expected_len) = rgba8_len(expected_width, expected_height) else {
            return false;
        };
        self.rgba8.len() == expected_len
            && <[u8; 32]>::from(Sha256::digest(&self.rgba8)) == self.pixel_sha256
    }
}

#[derive(Clone)]
pub struct PreparedDecodedImage {
    resource_id: Box<str>,
    content: Arc<DecodedImageContent>,
    cache_hit: bool,
}

impl PreparedDecodedImage {
    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn width_px(&self) -> u32 {
        self.content.width_px
    }

    pub fn height_px(&self) -> u32 {
        self.content.height_px
    }

    pub fn straight_rgba8(&self) -> &[u8] {
        &self.content.rgba8
    }

    pub fn cache_hit(&self) -> bool {
        self.cache_hit
    }
}

impl Debug for PreparedDecodedImage {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PreparedDecodedImage")
            .field("resource_id", &self.resource_id)
            .field("width_px", &self.content.width_px)
            .field("height_px", &self.content.height_px)
            .field("byte_length", &self.content.rgba8.len())
            .field("cache_hit", &self.cache_hit)
            .finish()
    }
}

#[derive(Debug, Default)]
struct DecodedCacheBudget {
    retained_bytes: u64,
}

impl DecodedCacheBudget {
    fn ensure_can_reserve(
        &self,
        resource: &AdmittedRenderResource,
        byte_length: u64,
    ) -> Result<(), ResourcePreparationProblem> {
        let Some(next) = self.retained_bytes.checked_add(byte_length) else {
            return Err(ResourcePreparationProblem::budget_for_limit(
                resource.resource_id(),
                REQUEST_DECODED_CACHE_BYTES_LIMIT_ID,
            ));
        };
        if next > MAX_REQUEST_DECODED_CACHE_BYTES {
            return Err(ResourcePreparationProblem::budget_for_limit(
                resource.resource_id(),
                REQUEST_DECODED_CACHE_BYTES_LIMIT_ID,
            ));
        }
        Ok(())
    }

    fn commit(&mut self, byte_length: u64) {
        self.retained_bytes += byte_length;
    }
}

#[derive(Default)]
pub struct RequestDecodedImageCache {
    entries: BTreeMap<DecodedImageCacheKey, Arc<DecodedImageContent>>,
    budget: DecodedCacheBudget,
}

impl RequestDecodedImageCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn retained_bytes(&self) -> u64 {
        self.budget.retained_bytes
    }

    pub fn unique_content_count(&self) -> usize {
        self.entries.len()
    }

    pub fn decode_or_lookup(
        &mut self,
        resource: &AdmittedRenderResource,
        profile: ResourcePreparationProfile,
        raw: &PreparedRawResource,
        now_epoch_millis: i64,
    ) -> Result<PreparedDecodedImage, ResourcePreparationProblem> {
        ensure_lease_active(resource, now_epoch_millis)?;
        if raw.resource_id() != resource.resource_id() {
            return Err(internal_problem(resource));
        }
        let media = verify_resource_media(resource, raw.bytes())?;
        if raw.media() != &media || resource.kind() != RenderResourceKind::Image {
            return Err(internal_problem(resource));
        }
        let Some((_, _, orientation, logical_width, logical_height)) =
            resource.technical_descriptor().image_dimensions()
        else {
            return Err(internal_problem(resource));
        };
        let key = DecodedImageCacheKey::new(resource, profile);
        if let Some(content) = self.entries.get(&key).cloned() {
            if content.is_intact(logical_width, logical_height) {
                return Ok(PreparedDecodedImage {
                    resource_id: resource.resource_id().into(),
                    content,
                    cache_hit: true,
                });
            }
            self.entries.remove(&key);
            return Err(internal_problem(resource));
        }

        let decoded_bytes = u64::from(logical_width)
            .checked_mul(u64::from(logical_height))
            .and_then(|pixels| pixels.checked_mul(4))
            .ok_or_else(|| decoded_budget_problem(resource))?;
        self.budget.ensure_can_reserve(resource, decoded_bytes)?;

        let decoded = decode_image(
            resource,
            raw.bytes(),
            media.media_type(),
            media.has_embedded_icc().unwrap_or(false),
            orientation,
        )?;
        if decoded.width_px != logical_width
            || decoded.height_px != logical_height
            || u64::try_from(decoded.rgba8.len()).ok() != Some(decoded_bytes)
        {
            return Err(internal_problem(resource));
        }

        let content = Arc::new(DecodedImageContent {
            width_px: decoded.width_px,
            height_px: decoded.height_px,
            pixel_sha256: Sha256::digest(&decoded.rgba8).into(),
            rgba8: Arc::from(decoded.rgba8),
        });
        self.budget.commit(decoded_bytes);
        self.entries.insert(key, Arc::clone(&content));
        Ok(PreparedDecodedImage {
            resource_id: resource.resource_id().into(),
            content,
            cache_hit: false,
        })
    }
}

impl Debug for RequestDecodedImageCache {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RequestDecodedImageCache")
            .field("unique_content_count", &self.entries.len())
            .field("retained_bytes", &self.budget.retained_bytes)
            .finish()
    }
}

struct DecodedPixels {
    width_px: u32,
    height_px: u32,
    rgba8: Vec<u8>,
}

#[derive(Clone, Copy, Debug)]
enum DecodeFailure {
    Invalid,
    Internal,
}

fn decode_image(
    resource: &AdmittedRenderResource,
    bytes: &[u8],
    media_type: RenderResourceMediaType,
    expects_embedded_icc: bool,
    orientation: ImageOrientation,
) -> Result<DecodedPixels, ResourcePreparationProblem> {
    let result = match media_type {
        RenderResourceMediaType::ImagePng => decode_png(bytes, expects_embedded_icc),
        RenderResourceMediaType::ImageJpeg => decode_jpeg(bytes, expects_embedded_icc),
        RenderResourceMediaType::ImageWebp => decode_webp(bytes, expects_embedded_icc),
        RenderResourceMediaType::FontTtf | RenderResourceMediaType::FontOtf => {
            return Err(internal_problem(resource));
        }
    };
    let encoded = result.map_err(|failure| match failure {
        DecodeFailure::Invalid => ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::DecodeFailed,
            resource.resource_id(),
        ),
        DecodeFailure::Internal => internal_problem(resource),
    })?;

    let Some((encoded_width, encoded_height, _, _, _)) =
        resource.technical_descriptor().image_dimensions()
    else {
        return Err(internal_problem(resource));
    };
    if encoded.width_px != encoded_width || encoded.height_px != encoded_height {
        return Err(internal_problem(resource));
    }
    orient_rgba8(encoded, orientation).map_err(|failure| match failure {
        DecodeFailure::Invalid => ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::DecodeFailed,
            resource.resource_id(),
        ),
        DecodeFailure::Internal => internal_problem(resource),
    })
}

fn decode_png(bytes: &[u8], expects_embedded_icc: bool) -> Result<DecodedPixels, DecodeFailure> {
    let limits = png::Limits {
        bytes: MAX_DECODER_SCRATCH_BYTES,
    };
    let mut decoder = png::Decoder::new_with_limits(Cursor::new(bytes), limits);
    decoder.set_ignore_text_chunk(true);
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = decoder.read_info().map_err(|_| DecodeFailure::Invalid)?;
    if !profile_matches(expects_embedded_icc, reader.info().icc_profile.as_deref()) {
        return Err(DecodeFailure::Invalid);
    }
    let output_len = reader.output_buffer_size().ok_or(DecodeFailure::Internal)?;
    let mut decoded = allocate_zeroed(output_len)?;
    let info = reader
        .next_frame(&mut decoded)
        .map_err(|_| DecodeFailure::Invalid)?;
    decoded.truncate(info.buffer_size());
    let rgba8 = match info.color_type {
        png::ColorType::Grayscale => expand_channels(&decoded, 1)?,
        png::ColorType::GrayscaleAlpha => expand_channels(&decoded, 2)?,
        png::ColorType::Rgb => expand_channels(&decoded, 3)?,
        png::ColorType::Rgba => decoded,
        png::ColorType::Indexed => return Err(DecodeFailure::Internal),
    };
    ensure_rgba_length(info.width, info.height, &rgba8)?;
    Ok(DecodedPixels {
        width_px: info.width,
        height_px: info.height,
        rgba8,
    })
}

fn decode_jpeg(bytes: &[u8], expects_embedded_icc: bool) -> Result<DecodedPixels, DecodeFailure> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(bytes));
    decoder.set_max_decoding_buffer_size(MAX_REQUEST_DECODED_CACHE_BYTES as usize);
    let decoded = decoder.decode().map_err(|_| DecodeFailure::Invalid)?;
    let info = decoder.info().ok_or(DecodeFailure::Internal)?;
    let embedded_icc = decoder.icc_profile();
    if !profile_matches(expects_embedded_icc, embedded_icc.as_deref()) {
        return Err(DecodeFailure::Invalid);
    }
    let rgba8 = match info.pixel_format {
        PixelFormat::L8 => expand_channels(&decoded, 1)?,
        PixelFormat::RGB24 => expand_channels(&decoded, 3)?,
        PixelFormat::L16 | PixelFormat::CMYK32 => return Err(DecodeFailure::Invalid),
    };
    let width = u32::from(info.width);
    let height = u32::from(info.height);
    ensure_rgba_length(width, height, &rgba8)?;
    Ok(DecodedPixels {
        width_px: width,
        height_px: height,
        rgba8,
    })
}

fn decode_webp(bytes: &[u8], expects_embedded_icc: bool) -> Result<DecodedPixels, DecodeFailure> {
    let mut options = WebPDecodeOptions::default();
    options.lossy_upsampling = UpsamplingMethod::Bilinear;
    let mut decoder = WebPDecoder::new_with_options(Cursor::new(bytes), options)
        .map_err(|_| DecodeFailure::Invalid)?;
    decoder.set_memory_limit(MAX_DECODER_SCRATCH_BYTES);
    if decoder.is_animated() || decoder.num_frames() != 0 {
        return Err(DecodeFailure::Invalid);
    }
    let embedded_icc = decoder.icc_profile().map_err(|_| DecodeFailure::Invalid)?;
    if !profile_matches(expects_embedded_icc, embedded_icc.as_deref()) {
        return Err(DecodeFailure::Invalid);
    }
    let (width, height) = decoder.dimensions();
    let output_len = decoder
        .output_buffer_size()
        .ok_or(DecodeFailure::Internal)?;
    let mut decoded = allocate_zeroed(output_len)?;
    decoder
        .read_image(&mut decoded)
        .map_err(|_| DecodeFailure::Invalid)?;
    let rgba8 = if decoder.has_alpha() {
        decoded
    } else {
        expand_channels(&decoded, 3)?
    };
    ensure_rgba_length(width, height, &rgba8)?;
    Ok(DecodedPixels {
        width_px: width,
        height_px: height,
        rgba8,
    })
}

fn profile_matches(expects_embedded_icc: bool, actual: Option<&[u8]>) -> bool {
    match (expects_embedded_icc, actual) {
        (false, None) => true,
        (true, Some(profile)) => profile == CANONICAL_SRGB_ICC,
        _ => false,
    }
}

fn expand_channels(decoded: &[u8], channels: usize) -> Result<Vec<u8>, DecodeFailure> {
    if !matches!(channels, 1..=3) || decoded.len() % channels != 0 {
        return Err(DecodeFailure::Internal);
    }
    let pixel_count = decoded.len() / channels;
    let output_len = pixel_count.checked_mul(4).ok_or(DecodeFailure::Internal)?;
    let mut output = allocate_zeroed(output_len)?;
    for (source, target) in decoded
        .chunks_exact(channels)
        .zip(output.chunks_exact_mut(4))
    {
        match channels {
            1 => target.copy_from_slice(&[source[0], source[0], source[0], 255]),
            2 => target.copy_from_slice(&[source[0], source[0], source[0], source[1]]),
            3 => target.copy_from_slice(&[source[0], source[1], source[2], 255]),
            _ => return Err(DecodeFailure::Internal),
        }
    }
    Ok(output)
}

fn orient_rgba8(
    encoded: DecodedPixels,
    orientation: ImageOrientation,
) -> Result<DecodedPixels, DecodeFailure> {
    ensure_rgba_length(encoded.width_px, encoded.height_px, &encoded.rgba8)?;
    if orientation == ImageOrientation::Identity {
        return Ok(encoded);
    }
    let swaps = matches!(
        orientation,
        ImageOrientation::Transpose
            | ImageOrientation::Rotate90Clockwise
            | ImageOrientation::Transverse
            | ImageOrientation::Rotate270Clockwise
    );
    let (target_width, target_height) = if swaps {
        (encoded.height_px, encoded.width_px)
    } else {
        (encoded.width_px, encoded.height_px)
    };
    let output_len = rgba8_len(target_width, target_height).ok_or(DecodeFailure::Internal)?;
    let mut output = allocate_zeroed(output_len)?;
    for target_y in 0..target_height {
        for target_x in 0..target_width {
            let (source_x, source_y) = match orientation {
                ImageOrientation::Identity => (target_x, target_y),
                ImageOrientation::MirrorHorizontal => (encoded.width_px - 1 - target_x, target_y),
                ImageOrientation::Rotate180 => (
                    encoded.width_px - 1 - target_x,
                    encoded.height_px - 1 - target_y,
                ),
                ImageOrientation::MirrorVertical => (target_x, encoded.height_px - 1 - target_y),
                ImageOrientation::Transpose => (target_y, target_x),
                ImageOrientation::Rotate90Clockwise => (target_y, encoded.height_px - 1 - target_x),
                ImageOrientation::Transverse => (
                    encoded.width_px - 1 - target_y,
                    encoded.height_px - 1 - target_x,
                ),
                ImageOrientation::Rotate270Clockwise => (encoded.width_px - 1 - target_y, target_x),
            };
            let source = pixel_offset(encoded.width_px, source_x, source_y)?;
            let target = pixel_offset(target_width, target_x, target_y)?;
            output[target..target + 4].copy_from_slice(&encoded.rgba8[source..source + 4]);
        }
    }
    Ok(DecodedPixels {
        width_px: target_width,
        height_px: target_height,
        rgba8: output,
    })
}

fn ensure_rgba_length(width: u32, height: u32, rgba8: &[u8]) -> Result<(), DecodeFailure> {
    if rgba8_len(width, height) == Some(rgba8.len()) {
        Ok(())
    } else {
        Err(DecodeFailure::Internal)
    }
}

fn rgba8_len(width: u32, height: u32) -> Option<usize> {
    usize::try_from(width)
        .ok()?
        .checked_mul(usize::try_from(height).ok()?)?
        .checked_mul(4)
}

fn pixel_offset(width: u32, x: u32, y: u32) -> Result<usize, DecodeFailure> {
    usize::try_from(y)
        .ok()
        .and_then(|row| row.checked_mul(usize::try_from(width).ok()?))
        .and_then(|row_start| row_start.checked_add(usize::try_from(x).ok()?))
        .and_then(|pixel| pixel.checked_mul(4))
        .ok_or(DecodeFailure::Internal)
}

fn allocate_zeroed(length: usize) -> Result<Vec<u8>, DecodeFailure> {
    let mut output = Vec::new();
    output
        .try_reserve_exact(length)
        .map_err(|_| DecodeFailure::Internal)?;
    output.resize(length, 0);
    Ok(output)
}

fn internal_problem(resource: &AdmittedRenderResource) -> ResourcePreparationProblem {
    ResourcePreparationProblem::for_resource(
        ResourcePreparationProblemCode::RenderInternalError,
        resource.resource_id(),
    )
}

fn decoded_budget_problem(resource: &AdmittedRenderResource) -> ResourcePreparationProblem {
    ResourcePreparationProblem::budget_for_limit(
        resource.resource_id(),
        REQUEST_DECODED_CACHE_BYTES_LIMIT_ID,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        FetchedResource, PhysicalFetchBudget, RequestRawResourceCache, verify_resource_body,
    };
    use renderweave_renderer_document::validate_render_document;
    use serde_json::{Value, json};

    const IMAGE_VECTORS: &str = include_str!("../../../image-decode-cache-vectors-v1.json");
    const ASSET_VECTORS: &str = include_str!(
        "../../../../renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    );
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");

    #[test]
    fn shared_vector_identity_limits_dependencies_and_boundary_are_frozen() {
        let vectors: Value = serde_json::from_str(IMAGE_VECTORS).unwrap();
        assert_eq!(vectors["profile"], "renderweave-image-decode-cache-v1");
        assert_eq!(
            vectors["rendererProfileIdentity"],
            ResourcePreparationProfile::RendererV1.as_str()
        );
        assert_eq!(
            vectors["assetKernelVectorSha256"],
            format!(
                "sha256:{}",
                hex::encode(Sha256::digest(ASSET_VECTORS.as_bytes()))
            )
        );
        assert_eq!(
            vectors["canonicalSrgbIcc"]["byteLength"],
            CANONICAL_SRGB_ICC.len()
        );
        assert_eq!(
            vectors["canonicalSrgbIcc"]["sha256"],
            format!("sha256:{}", hex::encode(Sha256::digest(CANONICAL_SRGB_ICC)))
        );
        assert_eq!(
            vectors["limits"]["requestDecodedCacheBytes"],
            MAX_REQUEST_DECODED_CACHE_BYTES
        );
        assert_eq!(
            vectors["limits"]["decoderScratchBytes"],
            MAX_DECODER_SCRATCH_BYTES
        );
        assert_eq!(vectors["decodeCases"].as_array().unwrap().len(), 14);
        assert_eq!(vectors["failureCases"].as_array().unwrap().len(), 4);
        assert_eq!(vectors["orientationCases"].as_array().unwrap().len(), 8);
        assert_eq!(vectors["cacheCases"].as_array().unwrap().len(), 7);
        assert_eq!(vectors["boundary"]["profileAvailability"], "NOT_REGISTERED");
        assert_eq!(vectors["boundary"]["certificationStatus"], "NOT_CERTIFIED");
        assert_eq!(vectors["boundary"]["daemonOutputPath"], "UNWIRED");
        assert_eq!(vectors["boundary"]["productRoute"], "CLOSED");
    }

    #[test]
    fn admitted_png_jpeg_and_webp_decode_to_frozen_oriented_straight_rgba8() {
        let vectors: Value = serde_json::from_str(IMAGE_VECTORS).unwrap();
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        for case in vectors["decodeCases"].as_array().unwrap() {
            let asset = asset_case(&assets, case["assetCaseId"].as_str().unwrap());
            let bytes = asset_bytes(asset);
            let resource = admitted_resource(
                &bytes,
                case["declaredMediaType"].as_str().unwrap(),
                &asset["expected"]["descriptor"],
                None,
            );
            let raw = prepared_raw_for_case(&resource, &bytes, case["id"].as_str().unwrap());
            let mut cache = RequestDecodedImageCache::new();
            let decoded = cache
                .decode_or_lookup(
                    &resource,
                    ResourcePreparationProfile::RendererV1,
                    &raw,
                    1_000_000,
                )
                .unwrap_or_else(|problem| panic!("{} failed: {problem:?}", case["id"]));
            assert!(!decoded.cache_hit());
            assert_eq!(
                decoded.width_px(),
                case["logicalWidthPx"].as_u64().unwrap() as u32
            );
            assert_eq!(
                decoded.height_px(),
                case["logicalHeightPx"].as_u64().unwrap() as u32
            );
            assert_eq!(
                decoded.straight_rgba8(),
                hex::decode(case["rgba8Hex"].as_str().unwrap()).unwrap(),
                "{}",
                case["id"]
            );
        }
    }

    #[test]
    fn corrupt_entropy_and_noncanonical_profiles_fail_without_pixels() {
        let vectors: Value = serde_json::from_str(IMAGE_VECTORS).unwrap();
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        for case in vectors["failureCases"].as_array().unwrap() {
            let asset = asset_case(&assets, case["assetCaseId"].as_str().unwrap());
            let descriptor = asset_case(&assets, case["descriptorCaseId"].as_str().unwrap());
            let bytes = asset_bytes(asset);
            let resource = admitted_resource(
                &bytes,
                case["declaredMediaType"].as_str().unwrap(),
                &descriptor["expected"]["descriptor"],
                None,
            );
            let mut cache = RequestDecodedImageCache::new();
            let problem = match prepared_raw_result(&resource, &bytes) {
                Ok(raw) => cache
                    .decode_or_lookup(
                        &resource,
                        ResourcePreparationProfile::RendererV1,
                        &raw,
                        1_000_000,
                    )
                    .unwrap_err(),
                Err(problem) => problem,
            };
            assert_eq!(
                problem.code().as_str(),
                case["expectedCode"].as_str().unwrap(),
                "{}",
                case["id"]
            );
            assert_eq!(cache.unique_content_count(), 0);
            assert_eq!(cache.retained_bytes(), 0);
        }
    }

    #[test]
    fn all_eight_orientations_match_the_frozen_pixel_mapping() {
        let vectors: Value = serde_json::from_str(IMAGE_VECTORS).unwrap();
        let source = &vectors["orientationSource"];
        let width = source["encodedWidthPx"].as_u64().unwrap() as u32;
        let height = source["encodedHeightPx"].as_u64().unwrap() as u32;
        let rgba8 = hex::decode(source["straightRgba8Hex"].as_str().unwrap()).unwrap();
        for case in vectors["orientationCases"].as_array().unwrap() {
            let oriented = orient_rgba8(
                DecodedPixels {
                    width_px: width,
                    height_px: height,
                    rgba8: rgba8.clone(),
                },
                orientation(case["orientation"].as_str().unwrap()),
            )
            .unwrap();
            assert_eq!(
                oriented.width_px,
                case["logicalWidthPx"].as_u64().unwrap() as u32
            );
            assert_eq!(
                oriented.height_px,
                case["logicalHeightPx"].as_u64().unwrap() as u32
            );
            assert_eq!(
                oriented.rgba8,
                hex::decode(case["rgba8Hex"].as_str().unwrap()).unwrap(),
                "{}",
                case["orientation"]
            );
        }
    }

    #[test]
    fn decoded_cache_reuses_content_across_occurrences_and_rechecks_lease() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let first = admitted_resource(&bytes, "image/png", &asset["expected"]["descriptor"], None);
        let second = admitted_resource(
            &bytes,
            "image/png",
            &asset["expected"]["descriptor"],
            Some("rwres_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
        );
        let first_raw = prepared_raw(&first, &bytes);
        let second_raw = prepared_raw(&second, &bytes);
        let mut cache = RequestDecodedImageCache::new();
        let inserted = cache
            .decode_or_lookup(
                &first,
                ResourcePreparationProfile::RendererV1,
                &first_raw,
                1_000_000,
            )
            .unwrap();
        assert!(!inserted.cache_hit());
        let hit = cache
            .decode_or_lookup(
                &second,
                ResourcePreparationProfile::RendererV1,
                &second_raw,
                1_500_000,
            )
            .unwrap();
        assert!(hit.cache_hit());
        assert_eq!(hit.resource_id(), second.resource_id());
        assert_eq!(cache.retained_bytes(), 4);
        assert_eq!(cache.unique_content_count(), 1);

        let expired = cache
            .decode_or_lookup(
                &second,
                ResourcePreparationProfile::RendererV1,
                &second_raw,
                2_000_000,
            )
            .unwrap_err();
        assert_eq!(
            expired.code(),
            ResourcePreparationProblemCode::ResourceLeaseExpired
        );
        assert_eq!(cache.unique_content_count(), 1);
    }

    #[test]
    fn decoded_cache_corruption_evicts_without_refund_or_redecode() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let resource =
            admitted_resource(&bytes, "image/png", &asset["expected"]["descriptor"], None);
        let raw = prepared_raw(&resource, &bytes);
        let mut cache = RequestDecodedImageCache::new();
        cache
            .decode_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap();
        let key = DecodedImageCacheKey::new(&resource, ResourcePreparationProfile::RendererV1);
        let original = cache.entries.get(&key).unwrap();
        let mut corrupted = original.rgba8.to_vec();
        corrupted[0] ^= 0xff;
        cache.entries.insert(
            key,
            Arc::new(DecodedImageContent {
                width_px: original.width_px,
                height_px: original.height_px,
                rgba8: Arc::from(corrupted),
                pixel_sha256: original.pixel_sha256,
            }),
        );

        let problem = cache
            .decode_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap_err();
        assert_eq!(
            problem.code(),
            ResourcePreparationProblemCode::RenderInternalError
        );
        assert_eq!(cache.unique_content_count(), 0);
        assert_eq!(cache.retained_bytes(), 4);
    }

    #[test]
    fn decoded_cache_budget_is_inclusive_and_atomic() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let resource =
            admitted_resource(&bytes, "image/png", &asset["expected"]["descriptor"], None);
        let mut budget = DecodedCacheBudget::default();
        budget
            .ensure_can_reserve(&resource, MAX_REQUEST_DECODED_CACHE_BYTES)
            .unwrap();
        budget.commit(MAX_REQUEST_DECODED_CACHE_BYTES);
        let problem = budget.ensure_can_reserve(&resource, 1).unwrap_err();
        assert_eq!(
            problem.code(),
            ResourcePreparationProblemCode::ResourceBudgetExceeded
        );
        assert_eq!(
            problem.limit_id(),
            Some(REQUEST_DECODED_CACHE_BYTES_LIMIT_ID)
        );
        assert_eq!(budget.retained_bytes, MAX_REQUEST_DECODED_CACHE_BYTES);
    }

    #[test]
    fn decoded_debug_surfaces_do_not_expose_pixels_or_content_identity() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let resource =
            admitted_resource(&bytes, "image/png", &asset["expected"]["descriptor"], None);
        let raw = prepared_raw(&resource, &bytes);
        let mut cache = RequestDecodedImageCache::new();
        let decoded = cache
            .decode_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap();
        let debug = format!("{cache:?} {decoded:?}");
        assert!(!debug.contains("12345678"));
        assert!(!debug.contains("sha256:"));
        assert!(!debug.contains("assets.internal"));
    }

    fn asset_case<'a>(vectors: &'a Value, id: &str) -> &'a Value {
        vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == id)
            .unwrap_or_else(|| panic!("missing Asset vector {id}"))
    }

    fn asset_bytes(case: &Value) -> Vec<u8> {
        decode_base64(case["input"]["data"].as_str().unwrap())
    }

    fn admitted_resource(
        bytes: &[u8],
        declared_media_type: &str,
        source_descriptor: &Value,
        replacement_resource_id: Option<&str>,
    ) -> AdmittedRenderResource {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let resource = &mut document["resources"].as_array_mut().unwrap()[1];
        let old_resource_id = resource["resourceId"].as_str().unwrap().to_owned();
        resource["mediaType"] = json!(declared_media_type);
        resource["byteLength"] = json!(bytes.len());
        resource["sha256"] = json!(format!("sha256:{}", hex::encode(Sha256::digest(bytes))));
        resource["technicalDescriptor"] = json!({
            "colorEncoding": source_descriptor["colorEncoding"],
            "encodedHeightPx": source_descriptor["encodedHeightPx"],
            "encodedWidthPx": source_descriptor["encodedWidthPx"],
            "frameCount": source_descriptor["frameCount"],
            "kind": "image",
            "logicalHeightPx": source_descriptor["logicalHeightPx"],
            "logicalWidthPx": source_descriptor["logicalWidthPx"],
            "orientation": source_descriptor["orientation"]
        });
        if let Some(replacement) = replacement_resource_id {
            resource["resourceId"] = json!(replacement);
            replace_string(&mut document, &old_resource_id, replacement);
        }
        let canonical = serde_json::to_string(&document).unwrap();
        validate_render_document(&canonical).unwrap().resources()[1].clone()
    }

    fn prepared_raw(resource: &AdmittedRenderResource, bytes: &[u8]) -> PreparedRawResource {
        prepared_raw_result(resource, bytes).unwrap()
    }

    fn prepared_raw_for_case(
        resource: &AdmittedRenderResource,
        bytes: &[u8],
        case_id: &str,
    ) -> PreparedRawResource {
        prepared_raw_result(resource, bytes)
            .unwrap_or_else(|problem| panic!("raw preflight failed for {case_id}: {problem:?}"))
    }

    fn prepared_raw_result(
        resource: &AdmittedRenderResource,
        bytes: &[u8],
    ) -> Result<PreparedRawResource, ResourcePreparationProblem> {
        let mut physical_budget = PhysicalFetchBudget::new();
        let verified = verify_resource_body(resource, &mut physical_budget, [bytes]).unwrap();
        let fetched = FetchedResource::from_verified_parts_for_test(verified, bytes.into());
        RequestRawResourceCache::new().insert_fetched(
            resource,
            ResourcePreparationProfile::RendererV1,
            fetched,
            1_000_000,
        )
    }

    fn orientation(value: &str) -> ImageOrientation {
        match value {
            "IDENTITY" => ImageOrientation::Identity,
            "MIRROR_HORIZONTAL" => ImageOrientation::MirrorHorizontal,
            "ROTATE_180" => ImageOrientation::Rotate180,
            "MIRROR_VERTICAL" => ImageOrientation::MirrorVertical,
            "TRANSPOSE" => ImageOrientation::Transpose,
            "ROTATE_90_CW" => ImageOrientation::Rotate90Clockwise,
            "TRANSVERSE" => ImageOrientation::Transverse,
            "ROTATE_270_CW" => ImageOrientation::Rotate270Clockwise,
            other => panic!("unknown orientation {other}"),
        }
    }

    fn replace_string(value: &mut Value, old: &str, replacement: &str) {
        match value {
            Value::String(text) if text == old => *text = replacement.to_owned(),
            Value::Array(values) => {
                for value in values {
                    replace_string(value, old, replacement);
                }
            }
            Value::Object(values) => {
                for value in values.values_mut() {
                    replace_string(value, old, replacement);
                }
            }
            _ => {}
        }
    }

    fn decode_base64(value: &str) -> Vec<u8> {
        assert_eq!(value.len() % 4, 0);
        let mut output = Vec::with_capacity(value.len() / 4 * 3);
        for block in value.as_bytes().chunks_exact(4) {
            let a = base64_value(block[0]).unwrap();
            let b = base64_value(block[1]).unwrap();
            let c = base64_value(block[2]);
            let d = base64_value(block[3]);
            output.push((a << 2) | (b >> 4));
            if let Some(c) = c {
                output.push((b << 4) | (c >> 2));
                if let Some(d) = d {
                    output.push((c << 6) | d);
                }
            }
        }
        output
    }

    fn base64_value(byte: u8) -> Option<u8> {
        match byte {
            b'A'..=b'Z' => Some(byte - b'A'),
            b'a'..=b'z' => Some(byte - b'a' + 26),
            b'0'..=b'9' => Some(byte - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            b'=' => None,
            _ => panic!("invalid fixture base64"),
        }
    }
}
