use crate::{FetchedResource, RESOURCE_PREPARATION_STAGE, VerifiedResourceBody};
use renderweave_renderer_document::{
    AdmittedRenderResource, FontFlavor, ImageOrientation, RenderResourceKind,
    RenderResourceMediaType,
};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fmt::{Debug, Formatter};
use std::sync::Arc;

pub const MAX_REQUEST_RAW_CACHE_BYTES: u64 = 268_435_456;
pub const REQUEST_RAW_CACHE_BYTES_LIMIT_ID: &str = "assetsAndFetch.requestRawCacheBytes";

const MAX_IMAGE_EDGE_PIXELS: u32 = 20_000;
const MAX_IMAGE_PIXELS: u64 = 100_000_000;
const MAX_FONT_TABLES: usize = 256;
const SFNT_CHECKSUM_MAGIC: u32 = 0xB1B0_AFBA;
const HEAD_MAGIC: u32 = 0x5F0F_3CF5;

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub enum ResourcePreparationProfile {
    RendererV1,
}

impl ResourcePreparationProfile {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::RendererV1 => "renderweave-renderer/1.0",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ResourcePreparationProblemCode {
    ResourceLeaseExpired,
    ResourceBudgetExceeded,
    MediaMismatch,
    DecodeFailed,
    RenderInternalError,
}

impl ResourcePreparationProblemCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ResourceLeaseExpired => "RESOURCE_LEASE_EXPIRED",
            Self::ResourceBudgetExceeded => "RESOURCE_BUDGET_EXCEEDED",
            Self::MediaMismatch => "MEDIA_MISMATCH",
            Self::DecodeFailed => "DECODE_FAILED",
            Self::RenderInternalError => "RENDER_INTERNAL_ERROR",
        }
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct ResourcePreparationProblem {
    code: ResourcePreparationProblemCode,
    resource_id: Box<str>,
    limit_id: Option<&'static str>,
}

impl ResourcePreparationProblem {
    pub fn code(&self) -> ResourcePreparationProblemCode {
        self.code
    }

    pub fn engine_stage(&self) -> &'static str {
        RESOURCE_PREPARATION_STAGE
    }

    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn limit_id(&self) -> Option<&'static str> {
        self.limit_id
    }

    pub(super) fn for_resource(code: ResourcePreparationProblemCode, resource_id: &str) -> Self {
        Self {
            code,
            resource_id: resource_id.into(),
            limit_id: None,
        }
    }

    fn budget(resource_id: &str) -> Self {
        Self::budget_for_limit(resource_id, REQUEST_RAW_CACHE_BYTES_LIMIT_ID)
    }

    pub(super) fn budget_for_limit(resource_id: &str, limit_id: &'static str) -> Self {
        Self {
            code: ResourcePreparationProblemCode::ResourceBudgetExceeded,
            resource_id: resource_id.into(),
            limit_id: Some(limit_id),
        }
    }
}

impl Debug for ResourcePreparationProblem {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ResourcePreparationProblem")
            .field("code", &self.code)
            .field("resource_id", &self.resource_id)
            .field("limit_id", &self.limit_id)
            .finish()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ParsedDescriptor {
    Image {
        encoded_width_px: u32,
        encoded_height_px: u32,
        orientation: ImageOrientation,
        logical_width_px: u32,
        logical_height_px: u32,
        embedded_icc: bool,
    },
    Font {
        flavor: FontFlavor,
        units_per_em: u16,
    },
}

#[derive(Clone, Eq, PartialEq)]
pub struct VerifiedResourceMedia {
    resource_id: Box<str>,
    media_type: RenderResourceMediaType,
    byte_length: u64,
    descriptor: ParsedDescriptor,
}

impl VerifiedResourceMedia {
    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn media_type(&self) -> RenderResourceMediaType {
        self.media_type
    }

    pub fn byte_length(&self) -> u64 {
        self.byte_length
    }

    pub(crate) fn has_embedded_icc(&self) -> Option<bool> {
        match self.descriptor {
            ParsedDescriptor::Image { embedded_icc, .. } => Some(embedded_icc),
            ParsedDescriptor::Font { .. } => None,
        }
    }
}

impl Debug for VerifiedResourceMedia {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("VerifiedResourceMedia")
            .field("resource_id", &self.resource_id)
            .field("media_type", &self.media_type)
            .field("byte_length", &self.byte_length)
            .finish_non_exhaustive()
    }
}

pub fn verify_resource_media(
    resource: &AdmittedRenderResource,
    bytes: &[u8],
) -> Result<VerifiedResourceMedia, ResourcePreparationProblem> {
    verify_integrity_again(resource, bytes)?;
    let actual_media = detect_media(bytes).ok_or_else(|| {
        ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::MediaMismatch,
            resource.resource_id(),
        )
    })?;
    if actual_media != resource.media_type() {
        return Err(ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::MediaMismatch,
            resource.resource_id(),
        ));
    }

    let descriptor = match actual_media {
        RenderResourceMediaType::ImagePng => parse_png(bytes),
        RenderResourceMediaType::ImageJpeg => parse_jpeg(bytes),
        RenderResourceMediaType::ImageWebp => parse_webp(bytes),
        RenderResourceMediaType::FontTtf => parse_font(bytes, FontFlavor::TrueTypeGlyf),
        RenderResourceMediaType::FontOtf => parse_font(bytes, FontFlavor::Cff),
    }
    .map_err(|()| {
        ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::DecodeFailed,
            resource.resource_id(),
        )
    })?;

    if !descriptor_matches(resource, descriptor) {
        return Err(ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::RenderInternalError,
            resource.resource_id(),
        ));
    }

    Ok(VerifiedResourceMedia {
        resource_id: resource.resource_id().into(),
        media_type: resource.media_type(),
        byte_length: resource.byte_length(),
        descriptor,
    })
}

fn verify_integrity_again(
    resource: &AdmittedRenderResource,
    bytes: &[u8],
) -> Result<(), ResourcePreparationProblem> {
    let actual_length = u64::try_from(bytes.len()).expect("usize must fit in u64");
    let actual_sha256 = format!("sha256:{}", hex::encode(Sha256::digest(bytes)));
    if actual_length != resource.byte_length() || actual_sha256 != resource.sha256() {
        return Err(ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::RenderInternalError,
            resource.resource_id(),
        ));
    }
    Ok(())
}

fn detect_media(bytes: &[u8]) -> Option<RenderResourceMediaType> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(RenderResourceMediaType::ImagePng)
    } else if bytes.starts_with(b"\xff\xd8") {
        Some(RenderResourceMediaType::ImageJpeg)
    } else if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some(RenderResourceMediaType::ImageWebp)
    } else if bytes.starts_with(&[0x00, 0x01, 0x00, 0x00]) {
        Some(RenderResourceMediaType::FontTtf)
    } else if bytes.starts_with(b"OTTO") {
        Some(RenderResourceMediaType::FontOtf)
    } else {
        None
    }
}

fn descriptor_matches(resource: &AdmittedRenderResource, parsed: ParsedDescriptor) -> bool {
    match parsed {
        ParsedDescriptor::Image {
            encoded_width_px,
            encoded_height_px,
            orientation,
            logical_width_px,
            logical_height_px,
            embedded_icc: _,
        } => {
            resource.kind() == RenderResourceKind::Image
                && resource.technical_descriptor().image_dimensions()
                    == Some((
                        encoded_width_px,
                        encoded_height_px,
                        orientation,
                        logical_width_px,
                        logical_height_px,
                    ))
        }
        ParsedDescriptor::Font {
            flavor,
            units_per_em,
        } => {
            resource.kind() == RenderResourceKind::Font
                && resource.technical_descriptor().font_metrics() == Some((flavor, units_per_em))
        }
    }
}

fn image_descriptor(
    width: u32,
    height: u32,
    orientation: ImageOrientation,
    embedded_icc: bool,
) -> Result<ParsedDescriptor, ()> {
    if width == 0
        || height == 0
        || width > MAX_IMAGE_EDGE_PIXELS
        || height > MAX_IMAGE_EDGE_PIXELS
        || u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS
    {
        return Err(());
    }
    let swaps_dimensions = matches!(
        orientation,
        ImageOrientation::Transpose
            | ImageOrientation::Rotate90Clockwise
            | ImageOrientation::Transverse
            | ImageOrientation::Rotate270Clockwise
    );
    let (logical_width_px, logical_height_px) = if swaps_dimensions {
        (height, width)
    } else {
        (width, height)
    };
    Ok(ParsedDescriptor::Image {
        encoded_width_px: width,
        encoded_height_px: height,
        orientation,
        logical_width_px,
        logical_height_px,
        embedded_icc,
    })
}

fn parse_png(bytes: &[u8]) -> Result<ParsedDescriptor, ()> {
    if bytes.len() < 8 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" {
        return Err(());
    }
    let mut position = 8usize;
    let mut width = 0;
    let mut height = 0;
    let mut bit_depth = 0;
    let mut color_type = 0;
    let mut saw_ihdr = false;
    let mut saw_plte = false;
    let mut palette_entries = 0usize;
    let mut saw_trns = false;
    let mut saw_idat = false;
    let mut ended_idat = false;
    let mut saw_iend = false;
    let mut saw_srgb = false;
    let mut saw_iccp = false;
    let mut saw_exif = false;
    let mut orientation = ImageOrientation::Identity;

    while position < bytes.len() {
        let header_end = position.checked_add(8).ok_or(())?;
        if header_end > bytes.len() {
            return Err(());
        }
        let length = usize::try_from(be_u32(bytes, position).ok_or(())?).map_err(|_| ())?;
        let payload_start = header_end;
        let payload_end = payload_start.checked_add(length).ok_or(())?;
        let chunk_end = payload_end.checked_add(4).ok_or(())?;
        if chunk_end > bytes.len() {
            return Err(());
        }
        let chunk_type: [u8; 4] = bytes[position + 4..position + 8]
            .try_into()
            .map_err(|_| ())?;
        let expected_crc = be_u32(bytes, payload_end).ok_or(())?;
        if png_crc32(&bytes[position + 4..payload_end]) != expected_crc {
            return Err(());
        }
        let payload = &bytes[payload_start..payload_end];

        if !saw_ihdr && chunk_type != *b"IHDR" {
            return Err(());
        }
        match &chunk_type {
            b"IHDR" => {
                if saw_ihdr || length != 13 {
                    return Err(());
                }
                saw_ihdr = true;
                width = be_u32(payload, 0).ok_or(())?;
                height = be_u32(payload, 4).ok_or(())?;
                bit_depth = payload[8];
                color_type = payload[9];
                let valid_depth = match color_type {
                    0 | 3 => matches!(bit_depth, 1 | 2 | 4 | 8),
                    2 | 4 | 6 => bit_depth == 8,
                    _ => false,
                };
                if !valid_depth
                    || payload[10] != 0
                    || payload[11] != 0
                    || !matches!(payload[12], 0 | 1)
                {
                    return Err(());
                }
                image_descriptor(width, height, ImageOrientation::Identity, false)?;
            }
            b"PLTE" => {
                if !saw_ihdr
                    || saw_plte
                    || saw_idat
                    || matches!(color_type, 0 | 4)
                    || length == 0
                    || length % 3 != 0
                    || length > 768
                {
                    return Err(());
                }
                palette_entries = length / 3;
                if color_type == 3 && palette_entries > (1usize << bit_depth) {
                    return Err(());
                }
                saw_plte = true;
            }
            b"tRNS" => {
                if saw_trns || saw_idat {
                    return Err(());
                }
                let valid = match color_type {
                    0 => length == 2,
                    2 => length == 6,
                    3 => saw_plte && length > 0 && length <= palette_entries,
                    _ => false,
                };
                if !valid {
                    return Err(());
                }
                saw_trns = true;
            }
            b"sRGB" => {
                if saw_srgb || saw_iccp || length != 1 || payload[0] > 3 {
                    return Err(());
                }
                saw_srgb = true;
            }
            b"iCCP" => {
                let separator = payload.iter().position(|byte| *byte == 0).ok_or(())?;
                if saw_iccp
                    || saw_srgb
                    || saw_plte
                    || saw_idat
                    || separator == 0
                    || separator > 79
                    || payload.get(separator + 1) != Some(&0)
                    || payload.len() <= separator + 2
                {
                    return Err(());
                }
                saw_iccp = true;
            }
            b"eXIf" => {
                if saw_exif {
                    return Err(());
                }
                saw_exif = true;
                orientation = parse_exif_orientation(payload)?;
            }
            b"acTL" | b"fcTL" | b"fdAT" => return Err(()),
            b"IDAT" => {
                if ended_idat || length == 0 {
                    return Err(());
                }
                saw_idat = true;
            }
            b"IEND" => {
                if length != 0 || !saw_idat || saw_iend {
                    return Err(());
                }
                saw_iend = true;
                position = chunk_end;
                break;
            }
            _ => {
                if chunk_type[0] & 0x20 == 0 {
                    return Err(());
                }
            }
        }
        if saw_idat && chunk_type != *b"IDAT" {
            ended_idat = true;
        }
        position = chunk_end;
    }

    if !saw_ihdr
        || !saw_idat
        || !saw_iend
        || position != bytes.len()
        || (color_type == 3 && !saw_plte)
    {
        return Err(());
    }
    image_descriptor(width, height, orientation, saw_iccp)
}

fn parse_jpeg(bytes: &[u8]) -> Result<ParsedDescriptor, ()> {
    if bytes.len() < 4 || &bytes[..2] != b"\xff\xd8" {
        return Err(());
    }
    let mut position = 2usize;
    let mut width = 0u32;
    let mut height = 0u32;
    let mut component_ids = Vec::new();
    let mut saw_sof = false;
    let mut saw_sos = false;
    let mut saw_eoi = false;
    let mut saw_dqt = false;
    let mut saw_dht = false;
    let mut saw_exif = false;
    let mut saw_icc = false;
    let mut saw_adobe = false;
    let mut orientation = ImageOrientation::Identity;

    while position < bytes.len() {
        if bytes[position] != 0xff {
            return Err(());
        }
        let marker_start = position;
        while position < bytes.len() && bytes[position] == 0xff {
            position += 1;
        }
        if position >= bytes.len() {
            return Err(());
        }
        let marker = bytes[position];
        position += 1;
        if marker == 0xd9 {
            saw_eoi = true;
            break;
        }
        if marker == 0x00 || marker == 0xd8 || (0xd0..=0xd7).contains(&marker) {
            return Err(());
        }
        let segment_length = usize::from(be_u16(bytes, position).ok_or(())?);
        if segment_length < 2 {
            return Err(());
        }
        let segment_end = position.checked_add(segment_length).ok_or(())?;
        if segment_end > bytes.len() {
            return Err(());
        }
        let payload = &bytes[position + 2..segment_end];

        if marker == 0xda {
            if !saw_sof || payload.is_empty() {
                return Err(());
            }
            saw_sos = true;
            position = segment_end;
            loop {
                if position >= bytes.len() {
                    return Err(());
                }
                if bytes[position] != 0xff {
                    position += 1;
                    continue;
                }
                let next_marker_start = position;
                position += 1;
                while position < bytes.len() && bytes[position] == 0xff {
                    position += 1;
                }
                if position >= bytes.len() {
                    return Err(());
                }
                match bytes[position] {
                    0x00 => position += 1,
                    0xd0..=0xd7 => position += 1,
                    _ => {
                        position = next_marker_start;
                        break;
                    }
                }
            }
            continue;
        }

        match marker {
            0xc0 | 0xc2 => {
                if saw_sof || payload.len() < 6 || payload[0] != 8 {
                    return Err(());
                }
                saw_sof = true;
                height = u32::from(be_u16(payload, 1).ok_or(())?);
                width = u32::from(be_u16(payload, 3).ok_or(())?);
                let components = usize::from(payload[5]);
                if !matches!(components, 1 | 3) || payload.len() != 6 + components * 3 {
                    return Err(());
                }
                component_ids = (0..components)
                    .map(|index| payload[6 + index * 3])
                    .collect();
                if components == 3 && component_ids != [1, 2, 3] {
                    return Err(());
                }
                image_descriptor(width, height, ImageOrientation::Identity, false)?;
            }
            0xc1 | 0xc3 | 0xc5 | 0xc6 | 0xc7 | 0xc9 | 0xca | 0xcb | 0xcd | 0xce | 0xcf | 0xcc
            | 0xdc | 0xde | 0xdf => return Err(()),
            0xdb => saw_dqt = true,
            0xc4 => saw_dht = true,
            0xe1 if payload.starts_with(b"Exif\0\0") => {
                if saw_exif {
                    return Err(());
                }
                saw_exif = true;
                orientation = parse_exif_orientation(&payload[6..])?;
            }
            0xe2 if payload.starts_with(b"ICC_PROFILE\0") => {
                if payload.len() <= 14 || payload[12] == 0 || payload[13] == 0 {
                    return Err(());
                }
                saw_icc = true;
            }
            0xee if payload.starts_with(b"Adobe") => {
                if saw_adobe || payload.len() < 12 {
                    return Err(());
                }
                saw_adobe = true;
                if matches!(payload[11], 0 | 2) {
                    return Err(());
                }
            }
            _ => {}
        }
        position = segment_end;
        if position <= marker_start {
            return Err(());
        }
    }

    if !saw_sof
        || !saw_sos
        || !saw_eoi
        || !saw_dqt
        || !saw_dht
        || position != bytes.len()
        || component_ids.is_empty()
    {
        return Err(());
    }
    image_descriptor(width, height, orientation, saw_icc)
}

fn parse_webp(bytes: &[u8]) -> Result<ParsedDescriptor, ()> {
    if bytes.len() < 20 || &bytes[..4] != b"RIFF" || &bytes[8..12] != b"WEBP" {
        return Err(());
    }
    let riff_size = usize::try_from(le_u32(bytes, 4).ok_or(())?).map_err(|_| ())?;
    if riff_size.checked_add(8) != Some(bytes.len()) {
        return Err(());
    }
    let mut position = 12usize;
    let mut canvas_dimensions = None;
    let mut image_dimensions = None;
    let mut saw_vp8x = false;
    let mut saw_image = false;
    let mut saw_alpha = false;
    let mut saw_exif = false;
    let mut saw_iccp = false;
    let mut expects_iccp = false;
    let mut orientation = ImageOrientation::Identity;

    while position < bytes.len() {
        let header_end = position.checked_add(8).ok_or(())?;
        if header_end > bytes.len() {
            return Err(());
        }
        let fourcc: [u8; 4] = bytes[position..position + 4].try_into().map_err(|_| ())?;
        let size = usize::try_from(le_u32(bytes, position + 4).ok_or(())?).map_err(|_| ())?;
        let payload_end = header_end.checked_add(size).ok_or(())?;
        let chunk_end = payload_end.checked_add(size & 1).ok_or(())?;
        if chunk_end > bytes.len() {
            return Err(());
        }
        let payload = &bytes[header_end..payload_end];
        match &fourcc {
            b"VP8X" => {
                if saw_vp8x || saw_image || size != 10 {
                    return Err(());
                }
                saw_vp8x = true;
                let flags = payload[0];
                if flags & 0xc3 != 0 {
                    return Err(());
                }
                expects_iccp = flags & 0x20 != 0;
                let width = little_u24(payload, 4).ok_or(())?.checked_add(1).ok_or(())?;
                let height = little_u24(payload, 7).ok_or(())?.checked_add(1).ok_or(())?;
                canvas_dimensions = Some((width, height));
            }
            b"ICCP" => {
                if saw_iccp || !saw_vp8x || saw_image || payload.is_empty() {
                    return Err(());
                }
                saw_iccp = true;
            }
            b"ANIM" | b"ANMF" => return Err(()),
            b"EXIF" => {
                if saw_exif || saw_image {
                    return Err(());
                }
                saw_exif = true;
                orientation = parse_exif_orientation(payload)?;
            }
            b"ALPH" => {
                if saw_alpha || saw_image || payload.is_empty() {
                    return Err(());
                }
                saw_alpha = true;
            }
            b"VP8 " => {
                if saw_image || payload.len() < 10 || payload[3..6] != [0x9d, 0x01, 0x2a] {
                    return Err(());
                }
                saw_image = true;
                let width = u32::from(le_u16(payload, 6).ok_or(())? & 0x3fff);
                let height = u32::from(le_u16(payload, 8).ok_or(())? & 0x3fff);
                image_dimensions = Some((width, height));
            }
            b"VP8L" => {
                if saw_image || payload.len() < 5 || payload[0] != 0x2f {
                    return Err(());
                }
                saw_image = true;
                let b1 = u32::from(payload[1]);
                let b2 = u32::from(payload[2]);
                let b3 = u32::from(payload[3]);
                let b4 = u32::from(payload[4]);
                let width = (b1 | ((b2 & 0x3f) << 8)) + 1;
                let height = ((b2 >> 6) | (b3 << 2) | ((b4 & 0x0f) << 10)) + 1;
                image_dimensions = Some((width, height));
            }
            b"XMP " => {
                if saw_image {
                    return Err(());
                }
            }
            _ => return Err(()),
        }
        position = chunk_end;
    }
    let (width, height) = image_dimensions.ok_or(())?;
    if !saw_image
        || position != bytes.len()
        || canvas_dimensions.is_some_and(|v| v != (width, height))
        || saw_iccp != expects_iccp
    {
        return Err(());
    }
    image_descriptor(width, height, orientation, saw_iccp)
}

fn parse_font(bytes: &[u8], expected_flavor: FontFlavor) -> Result<ParsedDescriptor, ()> {
    if bytes.len() < 12 {
        return Err(());
    }
    let actual_flavor = match &bytes[..4] {
        [0, 1, 0, 0] => FontFlavor::TrueTypeGlyf,
        b"OTTO" => FontFlavor::Cff,
        _ => return Err(()),
    };
    if actual_flavor != expected_flavor {
        return Err(());
    }
    let table_count = usize::from(be_u16(bytes, 4).ok_or(())?);
    let directory_end = 12usize
        .checked_add(table_count.checked_mul(16).ok_or(())?)
        .ok_or(())?;
    if table_count == 0 || table_count > MAX_FONT_TABLES || directory_end > bytes.len() {
        return Err(());
    }

    let mut tables = BTreeMap::<[u8; 4], FontTable>::new();
    let mut occupied = Vec::with_capacity(table_count);
    for index in 0..table_count {
        let base = 12 + index * 16;
        let tag: [u8; 4] = bytes[base..base + 4].try_into().map_err(|_| ())?;
        let checksum = be_u32(bytes, base + 4).ok_or(())?;
        let offset = usize::try_from(be_u32(bytes, base + 8).ok_or(())?).map_err(|_| ())?;
        let length = usize::try_from(be_u32(bytes, base + 12).ok_or(())?).map_err(|_| ())?;
        let end = offset.checked_add(length).ok_or(())?;
        if length == 0 || offset < directory_end || offset % 4 != 0 || end > bytes.len() {
            return Err(());
        }
        if tables
            .insert(
                tag,
                FontTable {
                    checksum,
                    offset,
                    length,
                },
            )
            .is_some()
        {
            return Err(());
        }
        occupied.push((offset, end));
    }
    occupied.sort_unstable();
    if occupied.windows(2).any(|pair| pair[0].1 > pair[1].0) {
        return Err(());
    }

    let banned = [
        *b"COLR", *b"CPAL", *b"CBDT", *b"CBLC", *b"sbix", *b"SVG ", *b"EBDT", *b"EBLC", *b"EBSC",
        *b"bdat", *b"bloc", *b"fvar", *b"gvar", *b"CFF2", *b"Silf", *b"Glat", *b"Gloc", *b"morx",
        *b"mort", *b"feat",
    ];
    if banned.iter().any(|tag| tables.contains_key(tag)) {
        return Err(());
    }
    let common_required = [
        *b"cmap", *b"head", *b"hhea", *b"hmtx", *b"maxp", *b"name", *b"OS/2", *b"post",
    ];
    if common_required.iter().any(|tag| !tables.contains_key(tag)) {
        return Err(());
    }
    match actual_flavor {
        FontFlavor::TrueTypeGlyf => {
            if !tables.contains_key(b"glyf") || !tables.contains_key(b"loca") {
                return Err(());
            }
        }
        FontFlavor::Cff => {
            if !tables.contains_key(b"CFF ") {
                return Err(());
            }
        }
    }

    for (tag, table) in &tables {
        let actual = sfnt_table_checksum(bytes, *tag, table.offset, table.length)?;
        if actual != table.checksum {
            return Err(());
        }
    }
    if sfnt_checksum(bytes) != SFNT_CHECKSUM_MAGIC {
        return Err(());
    }

    let head = tables.get(b"head").ok_or(())?;
    if head.length < 54 || be_u32(bytes, head.offset + 12) != Some(HEAD_MAGIC) {
        return Err(());
    }
    let units_per_em = be_u16(bytes, head.offset + 18).ok_or(())?;
    if !(16..=16_384).contains(&units_per_em) {
        return Err(());
    }
    let index_to_loc_format = be_u16(bytes, head.offset + 50).ok_or(())?;

    let maxp = tables.get(b"maxp").ok_or(())?;
    if maxp.length < 6 {
        return Err(());
    }
    let maxp_version = be_u32(bytes, maxp.offset).ok_or(())?;
    let glyph_count = usize::from(be_u16(bytes, maxp.offset + 4).ok_or(())?);
    if glyph_count == 0 {
        return Err(());
    }
    match actual_flavor {
        FontFlavor::TrueTypeGlyf => {
            if maxp_version != 0x0001_0000 {
                return Err(());
            }
            validate_loca(bytes, &tables, glyph_count, index_to_loc_format)?;
        }
        FontFlavor::Cff => {
            if maxp_version != 0x0000_5000 {
                return Err(());
            }
            let cff = tables.get(b"CFF ").ok_or(())?;
            if cff.length < 4 || bytes[cff.offset] != 1 || bytes[cff.offset + 2] < 4 {
                return Err(());
            }
        }
    }

    Ok(ParsedDescriptor::Font {
        flavor: actual_flavor,
        units_per_em,
    })
}

#[derive(Clone, Copy)]
struct FontTable {
    checksum: u32,
    offset: usize,
    length: usize,
}

fn validate_loca(
    bytes: &[u8],
    tables: &BTreeMap<[u8; 4], FontTable>,
    glyph_count: usize,
    index_to_loc_format: u16,
) -> Result<(), ()> {
    let loca = tables.get(b"loca").ok_or(())?;
    let glyf = tables.get(b"glyf").ok_or(())?;
    let entry_count = glyph_count.checked_add(1).ok_or(())?;
    let entry_size = match index_to_loc_format {
        0 => 2,
        1 => 4,
        _ => return Err(()),
    };
    if loca.length != entry_count.checked_mul(entry_size).ok_or(())? {
        return Err(());
    }
    let mut previous = 0usize;
    for index in 0..entry_count {
        let position = loca.offset + index * entry_size;
        let current = if entry_size == 2 {
            usize::from(be_u16(bytes, position).ok_or(())?) * 2
        } else {
            usize::try_from(be_u32(bytes, position).ok_or(())?).map_err(|_| ())?
        };
        if current < previous || current > glyf.length {
            return Err(());
        }
        previous = current;
    }
    Ok(())
}

fn sfnt_table_checksum(
    bytes: &[u8],
    tag: [u8; 4],
    offset: usize,
    length: usize,
) -> Result<u32, ()> {
    let end = offset.checked_add(length).ok_or(())?;
    if end > bytes.len() {
        return Err(());
    }
    let mut sum = 0u32;
    for relative in (0..length).step_by(4) {
        let mut word = [0u8; 4];
        let available = (length - relative).min(4);
        word[..available].copy_from_slice(&bytes[offset + relative..offset + relative + available]);
        if tag == *b"head" && relative == 8 {
            word = [0; 4];
        }
        sum = sum.wrapping_add(u32::from_be_bytes(word));
    }
    Ok(sum)
}

fn sfnt_checksum(bytes: &[u8]) -> u32 {
    let mut sum = 0u32;
    for chunk in bytes.chunks(4) {
        let mut word = [0u8; 4];
        word[..chunk.len()].copy_from_slice(chunk);
        sum = sum.wrapping_add(u32::from_be_bytes(word));
    }
    sum
}

fn parse_exif_orientation(bytes: &[u8]) -> Result<ImageOrientation, ()> {
    let tiff = bytes.strip_prefix(b"Exif\0\0").unwrap_or(bytes);
    if tiff.len() < 8 {
        return Err(());
    }
    let little_endian = match &tiff[..2] {
        b"II" => true,
        b"MM" => false,
        _ => return Err(()),
    };
    if endian_u16(tiff, 2, little_endian) != Some(42) {
        return Err(());
    }
    let ifd_offset =
        usize::try_from(endian_u32(tiff, 4, little_endian).ok_or(())?).map_err(|_| ())?;
    let count = usize::from(endian_u16(tiff, ifd_offset, little_endian).ok_or(())?);
    let mut position = ifd_offset.checked_add(2).ok_or(())?;
    let mut orientation = None;
    for _ in 0..count {
        let end = position.checked_add(12).ok_or(())?;
        if end > tiff.len() {
            return Err(());
        }
        let tag = endian_u16(tiff, position, little_endian).ok_or(())?;
        if tag == 0x0112 {
            if orientation.is_some()
                || endian_u16(tiff, position + 2, little_endian) != Some(3)
                || endian_u32(tiff, position + 4, little_endian) != Some(1)
            {
                return Err(());
            }
            let value = endian_u16(tiff, position + 8, little_endian).ok_or(())?;
            orientation = Some(match value {
                1 => ImageOrientation::Identity,
                2 => ImageOrientation::MirrorHorizontal,
                3 => ImageOrientation::Rotate180,
                4 => ImageOrientation::MirrorVertical,
                5 => ImageOrientation::Transpose,
                6 => ImageOrientation::Rotate90Clockwise,
                7 => ImageOrientation::Transverse,
                8 => ImageOrientation::Rotate270Clockwise,
                _ => return Err(()),
            });
        }
        position = end;
    }
    Ok(orientation.unwrap_or(ImageOrientation::Identity))
}

fn png_crc32(bytes: &[u8]) -> u32 {
    let mut value = 0xffff_ffffu32;
    for byte in bytes {
        value ^= u32::from(*byte);
        for _ in 0..8 {
            value = (value >> 1) ^ (0xedb8_8320u32 & 0u32.wrapping_sub(value & 1));
        }
    }
    !value
}

fn be_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes(
        bytes.get(offset..offset.checked_add(2)?)?.try_into().ok()?,
    ))
}

fn be_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_be_bytes(
        bytes.get(offset..offset.checked_add(4)?)?.try_into().ok()?,
    ))
}

fn le_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_le_bytes(
        bytes.get(offset..offset.checked_add(2)?)?.try_into().ok()?,
    ))
}

fn le_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..offset.checked_add(4)?)?.try_into().ok()?,
    ))
}

fn little_u24(bytes: &[u8], offset: usize) -> Option<u32> {
    let value = bytes.get(offset..offset.checked_add(3)?)?;
    Some(u32::from(value[0]) | (u32::from(value[1]) << 8) | (u32::from(value[2]) << 16))
}

fn endian_u16(bytes: &[u8], offset: usize, little_endian: bool) -> Option<u16> {
    let raw: [u8; 2] = bytes.get(offset..offset.checked_add(2)?)?.try_into().ok()?;
    Some(if little_endian {
        u16::from_le_bytes(raw)
    } else {
        u16::from_be_bytes(raw)
    })
}

fn endian_u32(bytes: &[u8], offset: usize, little_endian: bool) -> Option<u32> {
    let raw: [u8; 4] = bytes.get(offset..offset.checked_add(4)?)?.try_into().ok()?;
    Some(if little_endian {
        u32::from_le_bytes(raw)
    } else {
        u32::from_be_bytes(raw)
    })
}

#[derive(Eq, Ord, PartialEq, PartialOrd)]
struct RawCacheKey {
    profile: ResourcePreparationProfile,
    kind: RenderResourceKind,
    sha256: Box<str>,
    byte_length: u64,
    media_type: RenderResourceMediaType,
}

impl RawCacheKey {
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

struct VerifiedRawContent {
    media_type: RenderResourceMediaType,
    byte_length: u64,
    descriptor: ParsedDescriptor,
    bytes: Arc<[u8]>,
}

impl VerifiedRawContent {
    fn matches(&self, media: &VerifiedResourceMedia) -> bool {
        self.media_type == media.media_type
            && self.byte_length == media.byte_length
            && self.descriptor == media.descriptor
    }
}

#[derive(Clone)]
pub struct PreparedRawResource {
    resource_id: Box<str>,
    profile: ResourcePreparationProfile,
    media: VerifiedResourceMedia,
    content: Arc<VerifiedRawContent>,
    cache_hit: bool,
}

impl PreparedRawResource {
    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn bytes(&self) -> &[u8] {
        &self.content.bytes
    }

    pub fn media(&self) -> &VerifiedResourceMedia {
        &self.media
    }

    pub(crate) fn profile(&self) -> ResourcePreparationProfile {
        self.profile
    }

    pub(super) fn shared_bytes(&self) -> Arc<[u8]> {
        Arc::clone(&self.content.bytes)
    }

    pub fn cache_hit(&self) -> bool {
        self.cache_hit
    }
}

impl Debug for PreparedRawResource {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PreparedRawResource")
            .field("resource_id", &self.resource_id)
            .field("media_type", &self.media.media_type)
            .field("byte_length", &self.media.byte_length)
            .field("cache_hit", &self.cache_hit)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Default)]
struct RawCacheBudget {
    retained_bytes: u64,
}

impl RawCacheBudget {
    fn reserve(
        &mut self,
        resource: &AdmittedRenderResource,
        byte_length: u64,
    ) -> Result<(), ResourcePreparationProblem> {
        let Some(next) = self.retained_bytes.checked_add(byte_length) else {
            return Err(ResourcePreparationProblem::budget(resource.resource_id()));
        };
        if next > MAX_REQUEST_RAW_CACHE_BYTES {
            return Err(ResourcePreparationProblem::budget(resource.resource_id()));
        }
        self.retained_bytes = next;
        Ok(())
    }
}

#[derive(Default)]
pub struct RequestRawResourceCache {
    entries: BTreeMap<RawCacheKey, Arc<VerifiedRawContent>>,
    budget: RawCacheBudget,
}

impl RequestRawResourceCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn retained_bytes(&self) -> u64 {
        self.budget.retained_bytes
    }

    pub fn unique_content_count(&self) -> usize {
        self.entries.len()
    }

    pub fn lookup(
        &mut self,
        resource: &AdmittedRenderResource,
        profile: ResourcePreparationProfile,
        now_epoch_millis: i64,
    ) -> Result<Option<PreparedRawResource>, ResourcePreparationProblem> {
        ensure_lease_active(resource, now_epoch_millis)?;
        let key = RawCacheKey::new(resource, profile);
        let Some(content) = self.entries.get(&key).cloned() else {
            return Ok(None);
        };
        match verify_resource_media(resource, &content.bytes) {
            Ok(media) if content.matches(&media) => Ok(Some(PreparedRawResource {
                resource_id: resource.resource_id().into(),
                profile,
                media,
                content,
                cache_hit: true,
            })),
            _ => {
                self.entries.remove(&key);
                Err(ResourcePreparationProblem::for_resource(
                    ResourcePreparationProblemCode::RenderInternalError,
                    resource.resource_id(),
                ))
            }
        }
    }

    pub fn insert_fetched(
        &mut self,
        resource: &AdmittedRenderResource,
        profile: ResourcePreparationProfile,
        fetched: FetchedResource,
        now_epoch_millis: i64,
    ) -> Result<PreparedRawResource, ResourcePreparationProblem> {
        ensure_lease_active(resource, now_epoch_millis)?;
        let (verified_body, bytes) = fetched.into_verified_parts();
        ensure_verified_body_matches(resource, &verified_body)?;
        if let Some(hit) = self.lookup(resource, profile, now_epoch_millis)? {
            return Ok(hit);
        }
        let media = verify_resource_media(resource, &bytes)?;
        self.budget.reserve(resource, resource.byte_length())?;
        let content = Arc::new(VerifiedRawContent {
            media_type: media.media_type,
            byte_length: media.byte_length,
            descriptor: media.descriptor,
            bytes: Arc::from(bytes),
        });
        self.entries
            .insert(RawCacheKey::new(resource, profile), Arc::clone(&content));
        Ok(PreparedRawResource {
            resource_id: resource.resource_id().into(),
            profile,
            media,
            content,
            cache_hit: false,
        })
    }
}

impl Debug for RequestRawResourceCache {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RequestRawResourceCache")
            .field("unique_content_count", &self.entries.len())
            .field("retained_bytes", &self.budget.retained_bytes)
            .finish()
    }
}

pub(super) fn ensure_lease_active(
    resource: &AdmittedRenderResource,
    now_epoch_millis: i64,
) -> Result<(), ResourcePreparationProblem> {
    let expires_at_millis = i128::from(resource.expires_at_epoch_second()) * 1_000;
    if i128::from(now_epoch_millis) >= expires_at_millis {
        return Err(ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::ResourceLeaseExpired,
            resource.resource_id(),
        ));
    }
    Ok(())
}

fn ensure_verified_body_matches(
    resource: &AdmittedRenderResource,
    verified: &VerifiedResourceBody,
) -> Result<(), ResourcePreparationProblem> {
    if verified.resource_id() != resource.resource_id()
        || verified.byte_length() != resource.byte_length()
        || verified.sha256() != resource.sha256()
    {
        return Err(ResourcePreparationProblem::for_resource(
            ResourcePreparationProblemCode::RenderInternalError,
            resource.resource_id(),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};
    use std::collections::BTreeSet;

    use super::*;

    const MEDIA_VECTORS: &str = include_str!("../../../resource-media-raw-cache-vectors-v1.json");
    const ASSET_VECTORS: &str = include_str!(
        "../../../../renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    );
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");

    #[test]
    fn shared_corpus_freezes_scope_limits_boundary_and_asset_partition() {
        let media_vectors: Value = serde_json::from_str(MEDIA_VECTORS).unwrap();
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();

        assert_eq!(
            media_vectors["profile"],
            "renderweave-resource-media-raw-cache-v1"
        );
        assert_eq!(
            media_vectors["rendererProfileIdentity"],
            ResourcePreparationProfile::RendererV1.as_str()
        );
        assert_eq!(
            media_vectors["assetKernelVectorSha256"],
            format!(
                "sha256:{}",
                hex::encode(Sha256::digest(ASSET_VECTORS.as_bytes()))
            )
        );
        assert_eq!(
            media_vectors["limits"]["requestRawCacheBytes"],
            MAX_REQUEST_RAW_CACHE_BYTES
        );
        assert_eq!(
            media_vectors["limits"]["requestRawCacheLimitId"],
            REQUEST_RAW_CACHE_BYTES_LIMIT_ID
        );
        assert_eq!(media_vectors["limits"]["fontTablesPerContent"], 256);
        assert_eq!(
            media_vectors["boundary"]["resourceBytes"],
            "MEDIA_DESCRIPTOR_PREFLIGHT_AUTOMATED_VERIFIED"
        );
        assert_eq!(media_vectors["boundary"]["imageDecode"], "DEFERRED");
        assert_eq!(media_vectors["boundary"]["fontFullParse"], "DEFERRED");
        assert_eq!(media_vectors["boundary"]["decodedCache"], "ABSENT");
        assert_eq!(media_vectors["boundary"]["daemonOutputPath"], "UNWIRED");
        assert_eq!(
            media_vectors["boundary"]["profileAvailability"],
            "NOT_REGISTERED"
        );
        assert_eq!(
            media_vectors["boundary"]["certificationStatus"],
            "NOT_CERTIFIED"
        );
        assert_eq!(
            media_vectors["boundary"]["processRasterImplementation"],
            "ABSENT"
        );
        assert_eq!(media_vectors["boundary"]["productRoute"], "CLOSED");
        assert_eq!(media_vectors["boundary"]["providerAttempts"], 0);

        let all_asset_ids: BTreeSet<_> = asset_vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .map(|case| case["id"].as_str().unwrap())
            .collect();
        assert_eq!(all_asset_ids.len(), 41);
        let mut partition = BTreeSet::new();
        for category in ["supportedAssetCases", "defensiveAssetCases"] {
            for case in media_vectors[category].as_array().unwrap() {
                partition.insert(case["assetCaseId"].as_str().unwrap());
            }
        }
        for case in media_vectors["deferredAssetCases"].as_array().unwrap() {
            assert!(partition.insert(case["assetCaseId"].as_str().unwrap()));
        }
        assert_eq!(partition, all_asset_ids);
        assert_eq!(
            media_vectors["supportedAssetCases"]
                .as_array()
                .unwrap()
                .len(),
            13
        );
        assert_eq!(
            media_vectors["defensiveAssetCases"]
                .as_array()
                .unwrap()
                .len(),
            22
        );
        assert_eq!(
            media_vectors["descriptorCases"].as_array().unwrap().len(),
            5
        );
        assert_eq!(
            media_vectors["deferredAssetCases"]
                .as_array()
                .unwrap()
                .len(),
            7
        );
        assert_eq!(media_vectors["cacheCases"].as_array().unwrap().len(), 7);
    }

    #[test]
    fn supported_asset_headers_and_descriptors_replay_the_shared_corpus() {
        let media_vectors: Value = serde_json::from_str(MEDIA_VECTORS).unwrap();
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let cases = media_vectors["supportedAssetCases"].as_array().unwrap();
        assert_eq!(cases.len(), 13);

        for case in cases {
            let asset = asset_case(&asset_vectors, case["assetCaseId"].as_str().unwrap());
            assert_eq!(asset["expected"]["outcome"], "ADMITTED");
            let bytes = asset_bytes(asset);
            let resource = admitted_resource(
                &bytes,
                case["declaredMediaType"].as_str().unwrap(),
                &asset["expected"]["descriptor"],
                None,
                None,
            );

            let verified = verify_resource_media(&resource, &bytes).unwrap_or_else(|problem| {
                panic!("{} unexpectedly failed: {problem:?}", case["id"])
            });
            assert_eq!(verified.resource_id(), resource.resource_id());
            assert_eq!(verified.byte_length(), resource.byte_length());
            assert_eq!(verified.media_type(), resource.media_type());
        }
    }

    #[test]
    fn malformed_or_excluded_headers_fail_with_the_frozen_safe_code() {
        let media_vectors: Value = serde_json::from_str(MEDIA_VECTORS).unwrap();
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let cases = media_vectors["defensiveAssetCases"].as_array().unwrap();
        assert_eq!(cases.len(), 22);

        for case in cases {
            let asset = asset_case(&asset_vectors, case["assetCaseId"].as_str().unwrap());
            let descriptor_case =
                asset_case(&asset_vectors, case["descriptorCaseId"].as_str().unwrap());
            let bytes = asset_bytes(asset);
            let resource = admitted_resource(
                &bytes,
                case["declaredMediaType"].as_str().unwrap(),
                &descriptor_case["expected"]["descriptor"],
                None,
                None,
            );

            let problem = verify_resource_media(&resource, &bytes).unwrap_err();
            assert_eq!(
                problem.code().as_str(),
                case["expectedCode"].as_str().unwrap(),
                "{}",
                case["id"]
            );
            assert_eq!(problem.engine_stage(), RESOURCE_PREPARATION_STAGE);
            assert_eq!(problem.resource_id(), resource.resource_id());
            assert_eq!(problem.limit_id(), None);
        }
    }

    #[test]
    fn descriptor_drift_is_an_internal_invariant_and_never_echoes_facts() {
        let media_vectors: Value = serde_json::from_str(MEDIA_VECTORS).unwrap();
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let cases = media_vectors["descriptorCases"].as_array().unwrap();
        assert_eq!(cases.len(), 5);

        for case in cases {
            let asset = asset_case(&asset_vectors, case["assetCaseId"].as_str().unwrap());
            let bytes = asset_bytes(asset);
            let resource = admitted_resource(
                &bytes,
                case["declaredMediaType"].as_str().unwrap(),
                &asset["expected"]["descriptor"],
                case["mutation"].as_str(),
                None,
            );

            let problem = verify_resource_media(&resource, &bytes).unwrap_err();
            assert_eq!(
                problem.code().as_str(),
                case["expectedCode"].as_str().unwrap(),
                "{}",
                case["id"]
            );
            let debug = format!("{problem:?}");
            assert!(!debug.contains("sha256:"));
            assert!(!debug.contains("assets.internal"));
            assert!(!debug.contains("encodedWidthPx"));
        }
    }

    #[test]
    fn raw_cache_reuses_exact_content_across_occurrence_ids_and_rechecks_lease() {
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&asset_vectors, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let first = admitted_resource(
            &bytes,
            "image/png",
            &asset["expected"]["descriptor"],
            None,
            None,
        );
        let second_id = "rwres_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        let second = admitted_resource(
            &bytes,
            "image/png",
            &asset["expected"]["descriptor"],
            None,
            Some(second_id),
        );
        let fetched = fetched_resource(&first, &bytes);
        let mut cache = RequestRawResourceCache::new();

        let inserted = cache
            .insert_fetched(
                &first,
                ResourcePreparationProfile::RendererV1,
                fetched,
                1_000_000,
            )
            .unwrap();
        assert!(!inserted.cache_hit());
        assert_eq!(cache.retained_bytes(), 70);
        assert_eq!(cache.unique_content_count(), 1);

        let hit = cache
            .lookup(&second, ResourcePreparationProfile::RendererV1, 1_500_000)
            .unwrap()
            .unwrap();
        assert!(hit.cache_hit());
        assert_eq!(hit.resource_id(), second_id);
        assert_eq!(hit.bytes(), bytes);
        assert_eq!(cache.retained_bytes(), 70);
        assert_eq!(cache.unique_content_count(), 1);

        let expired = cache
            .lookup(&second, ResourcePreparationProfile::RendererV1, 2_000_000)
            .unwrap_err();
        assert_eq!(
            expired.code(),
            ResourcePreparationProblemCode::ResourceLeaseExpired
        );
        assert_eq!(cache.unique_content_count(), 1);
    }

    #[test]
    fn cache_corruption_is_evicted_without_refunding_budget_or_fallback() {
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&asset_vectors, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let resource = admitted_resource(
            &bytes,
            "image/png",
            &asset["expected"]["descriptor"],
            None,
            None,
        );
        let mut cache = RequestRawResourceCache::new();
        cache
            .insert_fetched(
                &resource,
                ResourcePreparationProfile::RendererV1,
                fetched_resource(&resource, &bytes),
                1_000_000,
            )
            .unwrap();
        let key = RawCacheKey::new(&resource, ResourcePreparationProfile::RendererV1);
        let original = cache.entries.get(&key).unwrap();
        let mut corrupted = original.bytes.to_vec();
        corrupted[0] ^= 0xff;
        cache.entries.insert(
            key,
            Arc::new(VerifiedRawContent {
                media_type: original.media_type,
                byte_length: original.byte_length,
                descriptor: original.descriptor,
                bytes: Arc::from(corrupted),
            }),
        );

        let problem = cache
            .lookup(&resource, ResourcePreparationProfile::RendererV1, 1_000_000)
            .unwrap_err();

        assert_eq!(
            problem.code(),
            ResourcePreparationProblemCode::RenderInternalError
        );
        assert_eq!(cache.unique_content_count(), 0);
        assert_eq!(cache.retained_bytes(), 70);
    }

    #[test]
    fn debug_surfaces_are_payload_free() {
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&asset_vectors, "png-rgba-admitted");
        let bytes = asset_bytes(asset);
        let resource = admitted_resource(
            &bytes,
            "image/png",
            &asset["expected"]["descriptor"],
            None,
            None,
        );
        let mut cache = RequestRawResourceCache::new();
        let prepared = cache
            .insert_fetched(
                &resource,
                ResourcePreparationProfile::RendererV1,
                fetched_resource(&resource, &bytes),
                1_000_000,
            )
            .unwrap();
        let debug = format!("{cache:?} {prepared:?}");
        assert!(!debug.contains("iVBOR"));
        assert!(!debug.contains("sha256:"));
        assert!(!debug.contains("assets.internal"));
    }

    #[test]
    fn raw_cache_budget_is_inclusive_and_atomic() {
        let mut budget = RawCacheBudget::default();
        let resource = admitted_png_resource();
        assert!(
            budget
                .reserve(&resource, MAX_REQUEST_RAW_CACHE_BYTES)
                .is_ok()
        );
        let problem = budget.reserve(&resource, 1).unwrap_err();
        assert_eq!(
            problem.code(),
            ResourcePreparationProblemCode::ResourceBudgetExceeded
        );
        assert_eq!(problem.limit_id(), Some(REQUEST_RAW_CACHE_BYTES_LIMIT_ID));
        assert_eq!(budget.retained_bytes, MAX_REQUEST_RAW_CACHE_BYTES);
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
        assert_eq!(case["input"]["kind"], "BASE64");
        decode_base64(case["input"]["data"].as_str().unwrap())
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
                } else {
                    assert_eq!(block[3], b'=');
                }
            } else {
                assert_eq!(&block[2..], b"==");
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

    fn admitted_png_resource() -> AdmittedRenderResource {
        let document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let canonical = serde_json::to_string(&document).unwrap();
        renderweave_renderer_document::validate_render_document(&canonical)
            .unwrap()
            .resources()[1]
            .clone()
    }

    fn admitted_resource(
        bytes: &[u8],
        declared_media_type: &str,
        source_descriptor: &Value,
        mutation: Option<&str>,
        replacement_resource_id: Option<&str>,
    ) -> AdmittedRenderResource {
        let is_font = declared_media_type.starts_with("font/");
        let resource_index = usize::from(!is_font);
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let resource = &mut document["resources"].as_array_mut().unwrap()[resource_index];
        let old_resource_id = resource["resourceId"].as_str().unwrap().to_owned();
        resource["mediaType"] = json!(declared_media_type);
        resource["byteLength"] = json!(bytes.len());
        resource["sha256"] = json!(format!("sha256:{}", hex::encode(Sha256::digest(bytes))));
        resource["technicalDescriptor"] = if is_font {
            json!({
                "faceIndex": source_descriptor["faceIndex"],
                "flavor": source_descriptor["flavor"],
                "kind": "font",
                "unitsPerEm": source_descriptor["unitsPerEm"]
            })
        } else {
            json!({
                "colorEncoding": source_descriptor["colorEncoding"],
                "encodedHeightPx": source_descriptor["encodedHeightPx"],
                "encodedWidthPx": source_descriptor["encodedWidthPx"],
                "frameCount": source_descriptor["frameCount"],
                "kind": "image",
                "logicalHeightPx": source_descriptor["logicalHeightPx"],
                "logicalWidthPx": source_descriptor["logicalWidthPx"],
                "orientation": source_descriptor["orientation"]
            })
        };
        apply_descriptor_mutation(resource, mutation);

        if let Some(replacement) = replacement_resource_id {
            resource["resourceId"] = json!(replacement);
            replace_string(&mut document, &old_resource_id, replacement);
        }
        let canonical = serde_json::to_string(&document).unwrap();
        renderweave_renderer_document::validate_render_document(&canonical)
            .unwrap_or_else(|error| panic!("test resource admission failed: {error:?}"))
            .resources()[resource_index]
            .clone()
    }

    fn apply_descriptor_mutation(resource: &mut Value, mutation: Option<&str>) {
        let descriptor = &mut resource["technicalDescriptor"];
        match mutation {
            None => {}
            Some("IMAGE_WIDTH_PLUS_ONE") => {
                let next = descriptor["encodedWidthPx"].as_u64().unwrap() + 1;
                descriptor["encodedWidthPx"] = json!(next);
                descriptor["logicalWidthPx"] = json!(next);
            }
            Some("IMAGE_HEIGHT_PLUS_ONE") => {
                let next = descriptor["encodedHeightPx"].as_u64().unwrap() + 1;
                descriptor["encodedHeightPx"] = json!(next);
                descriptor["logicalHeightPx"] = json!(next);
            }
            Some("IMAGE_ORIENTATION_IDENTITY") => {
                descriptor["orientation"] = json!("IDENTITY");
                descriptor["logicalWidthPx"] = descriptor["encodedWidthPx"].clone();
                descriptor["logicalHeightPx"] = descriptor["encodedHeightPx"].clone();
            }
            Some("IMAGE_ORIENTATION_ROTATE_90") => {
                descriptor["orientation"] = json!("ROTATE_90_CW");
                descriptor["logicalWidthPx"] = descriptor["encodedHeightPx"].clone();
                descriptor["logicalHeightPx"] = descriptor["encodedWidthPx"].clone();
            }
            Some("FONT_UNITS_PER_EM_PLUS_ONE") => {
                descriptor["unitsPerEm"] = json!(descriptor["unitsPerEm"].as_u64().unwrap() + 1);
            }
            Some(other) => panic!("unknown descriptor mutation {other}"),
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

    fn fetched_resource(resource: &AdmittedRenderResource, bytes: &[u8]) -> FetchedResource {
        let mut physical_budget = crate::PhysicalFetchBudget::new();
        let verified =
            crate::verify_resource_body(resource, &mut physical_budget, [bytes]).unwrap();
        FetchedResource::from_verified_parts_for_test(verified, bytes.into())
    }
}
