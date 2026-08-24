//! RenderWeave RenderEngine execution kernel.
//!
//! The module deliberately exposes one deep interface. Callers provide an already-admitted
//! RenderDocument and an effective PNG DPI; document traversal, layout, surface construction,
//! canonical transparent pixels, encoding, and output identity remain implementation details.

use std::fmt::{Display, Formatter};

use renderweave_renderer_document::AdmittedRenderDocument;
use renderweave_renderer_layout::{
    DefiniteLayoutEntry, LocalLayoutBox, layout_definite_resource_free,
};
use renderweave_renderer_output_png::{
    BleedPt, OutputPngError, SurfaceSpec, encode_straight_rgba8, preflight_surface,
};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

const OUTPUT_PROFILE: &str = "renderweave-output-png/1.0";
const MEDIA_TYPE: &str = "image/png";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EnginePngUnsupported {
    ResourceManifest,
    SceneStructure,
    RectPaint,
    NonOpaqueRectAlpha,
    NonPixelAlignedRect,
    PartialBackgroundAlpha,
}

impl EnginePngUnsupported {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::ResourceManifest => "RESOURCE_MANIFEST",
            Self::SceneStructure => "SCENE_STRUCTURE",
            Self::RectPaint => "RECT_PAINT",
            Self::NonOpaqueRectAlpha => "NON_OPAQUE_RECT_ALPHA",
            Self::NonPixelAlignedRect => "NON_PIXEL_ALIGNED_RECT",
            Self::PartialBackgroundAlpha => "PARTIAL_BACKGROUND_ALPHA",
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub enum EnginePngError {
    Contract(&'static str),
    Unsupported(EnginePngUnsupported),
    Layout,
    RasterAllocation,
    Output(OutputPngError),
}

impl EnginePngError {
    pub const fn unsupported_feature(&self) -> Option<&'static str> {
        match self {
            Self::Unsupported(feature) => Some(feature.as_str()),
            _ => None,
        }
    }

    pub fn code(&self) -> Option<&'static str> {
        match self {
            Self::Output(error) => error.code(),
            _ => None,
        }
    }

    pub fn stage(&self) -> Option<&'static str> {
        match self {
            Self::Output(error) => error.stage(),
            _ => None,
        }
    }

    pub fn limit_id(&self) -> Option<&'static str> {
        match self {
            Self::Output(error) => error.limit_id(),
            _ => None,
        }
    }
}

impl Display for EnginePngError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Contract(message) => formatter.write_str(message),
            Self::Unsupported(feature) => {
                write!(
                    formatter,
                    "Engine PNG subset does not support {}",
                    feature.as_str()
                )
            }
            Self::Layout => formatter.write_str("Engine PNG layout failed"),
            Self::RasterAllocation => formatter.write_str("Engine PNG raster allocation failed"),
            Self::Output(error) => write!(formatter, "Engine PNG output failed: {error}"),
        }
    }
}

impl std::error::Error for EnginePngError {}

impl From<OutputPngError> for EnginePngError {
    fn from(error: OutputPngError) -> Self {
        Self::Output(error)
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct EnginePngOutput {
    width_px: u32,
    height_px: u32,
    dpi: u32,
    pixel_sha256: String,
    content_sha256: String,
    bytes: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PixelRect {
    left: u32,
    top: u32,
    right: u32,
    bottom: u32,
    color: [u8; 4],
}

impl EnginePngOutput {
    pub const fn width_px(&self) -> u32 {
        self.width_px
    }

    pub const fn height_px(&self) -> u32 {
        self.height_px
    }

    pub const fn dpi(&self) -> u32 {
        self.dpi
    }

    pub const fn media_type(&self) -> &'static str {
        MEDIA_TYPE
    }

    pub const fn output_profile(&self) -> &'static str {
        OUTPUT_PROFILE
    }

    pub fn byte_length(&self) -> usize {
        self.bytes.len()
    }

    pub fn pixel_sha256(&self) -> &str {
        &self.pixel_sha256
    }

    pub fn content_sha256(&self) -> &str {
        &self.content_sha256
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

pub fn render_png(
    document: &AdmittedRenderDocument,
    dpi: u32,
) -> Result<EnginePngOutput, EnginePngError> {
    if document.resource_count() != 0 {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::ResourceManifest,
        ));
    }

    let root: Value = serde_json::from_str(document.canonical_document())
        .map_err(|_| EnginePngError::Contract("admitted RenderDocument could not be parsed"))?;
    let canvas = object_member(root.as_object(), "canvas")?;
    let children = array_member(canvas, "children")?;
    if children.len() > 1 {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }

    let background = color_member(canvas, "backgroundColor")?;
    let pixel = match background[3] {
        0 => [0, 0, 0, 0],
        255 => background,
        _ => {
            return Err(EnginePngError::Unsupported(
                EnginePngUnsupported::PartialBackgroundAlpha,
            ));
        }
    };

    let width_pt = decimal_member(canvas, "widthPt")?;
    let height_pt = decimal_member(canvas, "heightPt")?;
    let bleed = object_member(Some(canvas), "bleed")?;
    let bleed_top = decimal_member(bleed, "topPt")?;
    let bleed_right = decimal_member(bleed, "rightPt")?;
    let bleed_bottom = decimal_member(bleed, "bottomPt")?;
    let bleed_left = decimal_member(bleed, "leftPt")?;
    let surface = preflight_surface(
        SurfaceSpec {
            width_pt: &width_pt,
            height_pt: &height_pt,
            bleed_pt: BleedPt {
                top: &bleed_top,
                right: &bleed_right,
                bottom: &bleed_bottom,
                left: &bleed_left,
            },
        },
        dpi,
    )?;

    let layout = layout_definite_resource_free(document).map_err(|_| EnginePngError::Layout)?;
    if layout.entries().len() != children.len() + 1 || layout.entries()[0].kind() != "canvas" {
        return Err(EnginePngError::Contract(
            "Engine PNG layout entry shape diverged from the admitted scene",
        ));
    }

    let raster_length =
        usize::try_from(surface.rgba8_bytes()).map_err(|_| EnginePngError::RasterAllocation)?;
    let mut pixels = Vec::new();
    pixels
        .try_reserve_exact(raster_length)
        .map_err(|_| EnginePngError::RasterAllocation)?;
    pixels.resize(raster_length, 0);
    for target in pixels.chunks_exact_mut(4) {
        target.copy_from_slice(&pixel);
    }
    if let Some(child) = children.first() {
        let rect = prepare_rect_paint(
            child
                .as_object()
                .ok_or(EnginePngError::Contract("Canvas child is not an object"))?,
            &layout.entries()[1],
            &bleed_left,
            &bleed_top,
            dpi,
            surface.width_px(),
            surface.height_px(),
        )?;
        paint_rect(&mut pixels, surface.width_px(), rect)?;
    }
    let pixel_sha256 = raw_sha256_prefixed(&pixels);
    let bytes = encode_straight_rgba8(surface.width_px(), surface.height_px(), dpi, &pixels)?;
    let content_sha256 = raw_sha256_prefixed(&bytes);

    Ok(EnginePngOutput {
        width_px: surface.width_px(),
        height_px: surface.height_px(),
        dpi,
        pixel_sha256,
        content_sha256,
        bytes,
    })
}

fn prepare_rect_paint(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    bleed_left: &str,
    bleed_top: &str,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<PixelRect, EnginePngError> {
    if text_member(node, "kind")? != "rect" {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }
    if !boolean_member(node, "visible")?
        || !number_equals(node, "opacity", 1.0)?
        || node.contains_key("stroke")
    {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
    }

    let transform = object_member(Some(node), "transform")?;
    if !has_exact_members(
        transform,
        &["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
    ) || !number_equals(transform, "originX", 0.5)?
        || !number_equals(transform, "originY", 0.5)?
        || !number_equals(transform, "rotationDeg", 0.0)?
        || !number_equals(transform, "scaleX", 1.0)?
        || !number_equals(transform, "scaleY", 1.0)?
    {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
    }

    let radii = object_member(Some(node), "cornerRadii")?;
    if !has_exact_members(
        radii,
        &["bottomLeftPt", "bottomRightPt", "topLeftPt", "topRightPt"],
    ) {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
    }
    for member in ["bottomLeftPt", "bottomRightPt", "topLeftPt", "topRightPt"] {
        if !number_equals(radii, member, 0.0)? {
            return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
        }
    }

    let fill = object_member(Some(node), "fill")?;
    if !has_exact_members(fill, &["color"]) {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
    }
    let color = color_member(fill, "color")?;
    if color[3] != 255 {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::NonOpaqueRectAlpha,
        ));
    }

    let placement = object_member(Some(node), "placement")?;
    if !has_exact_members(
        placement,
        &[
            "heightMode",
            "heightPt",
            "type",
            "widthMode",
            "widthPt",
            "xPt",
            "yPt",
        ],
    ) || text_member(placement, "type")? != "ABSOLUTE"
        || text_member(placement, "widthMode")? != "FIXED"
        || text_member(placement, "heightMode")? != "FIXED"
    {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
    }

    let occurrence_id = text_member(node, "occurrenceId")?;
    if layout.kind() != "rect" || layout.occurrence_id() != occurrence_id {
        return Err(EnginePngError::Contract(
            "Rect layout entry identity diverged from the admitted scene",
        ));
    }
    require_authored_fixed_box(layout.layout_box(), placement)?;

    let left_bleed = parse_decimal6(bleed_left)?;
    let top_bleed = parse_decimal6(bleed_top)?;
    let x = decimal6_member(placement, "xPt")?;
    let y = decimal6_member(placement, "yPt")?;
    let width = decimal6_member(placement, "widthPt")?;
    let height = decimal6_member(placement, "heightPt")?;
    let device_left = exact_device_edge(&[left_bleed, x], dpi)?;
    let device_top = exact_device_edge(&[top_bleed, y], dpi)?;
    let device_right = exact_device_edge(&[left_bleed, x, width], dpi)?;
    let device_bottom = exact_device_edge(&[top_bleed, y, height], dpi)?;
    if device_right < device_left || device_bottom < device_top {
        return Err(EnginePngError::Contract("Rect device box is not monotonic"));
    }

    Ok(PixelRect {
        left: clip_device_edge(device_left, surface_width),
        top: clip_device_edge(device_top, surface_height),
        right: clip_device_edge(device_right, surface_width),
        bottom: clip_device_edge(device_bottom, surface_height),
        color,
    })
}

fn require_authored_fixed_box(
    actual: &LocalLayoutBox,
    placement: &Map<String, Value>,
) -> Result<(), EnginePngError> {
    let expected = [
        number_member(placement, "xPt")?,
        number_member(placement, "yPt")?,
        number_member(placement, "widthPt")?,
        number_member(placement, "heightPt")?,
    ];
    let actual = [actual.x(), actual.y(), actual.width(), actual.height()];
    if actual
        .into_iter()
        .zip(expected)
        .any(|(actual, expected)| actual.to_bits() != expected.to_bits())
    {
        return Err(EnginePngError::Contract(
            "Rect layout box diverged from authored FIXED geometry",
        ));
    }
    Ok(())
}

fn exact_device_edge(parts: &[i128], dpi: u32) -> Result<i128, EnginePngError> {
    const POINT_DENOMINATOR: i128 = 72_000_000;
    let point = parts.iter().try_fold(0_i128, |sum, part| {
        sum.checked_add(*part).ok_or(EnginePngError::Contract(
            "Rect device coordinate overflowed",
        ))
    })?;
    let numerator = point
        .checked_mul(i128::from(dpi))
        .ok_or(EnginePngError::Contract(
            "Rect device coordinate overflowed",
        ))?;
    if numerator % POINT_DENOMINATOR != 0 {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::NonPixelAlignedRect,
        ));
    }
    Ok(numerator / POINT_DENOMINATOR)
}

fn clip_device_edge(edge: i128, surface_edge: u32) -> u32 {
    edge.clamp(0, i128::from(surface_edge)) as u32
}

fn paint_rect(
    pixels: &mut [u8],
    surface_width: u32,
    rect: PixelRect,
) -> Result<(), EnginePngError> {
    for y in rect.top..rect.bottom {
        let start = (u64::from(y) * u64::from(surface_width) + u64::from(rect.left))
            .checked_mul(4)
            .and_then(|value| usize::try_from(value).ok())
            .ok_or(EnginePngError::RasterAllocation)?;
        let end = (u64::from(y) * u64::from(surface_width) + u64::from(rect.right))
            .checked_mul(4)
            .and_then(|value| usize::try_from(value).ok())
            .ok_or(EnginePngError::RasterAllocation)?;
        let row = pixels
            .get_mut(start..end)
            .ok_or(EnginePngError::RasterAllocation)?;
        for target in row.chunks_exact_mut(4) {
            target.copy_from_slice(&rect.color);
        }
    }
    Ok(())
}

fn raw_sha256_prefixed(bytes: &[u8]) -> String {
    format!("sha256:{}", hex::encode(Sha256::digest(bytes)))
}

fn color_member(
    object: &Map<String, Value>,
    member: &'static str,
) -> Result<[u8; 4], EnginePngError> {
    let value = object
        .get(member)
        .and_then(Value::as_str)
        .ok_or(EnginePngError::Contract("Canvas color member is absent"))?;
    if value.len() != 9 || !value.starts_with('#') {
        return Err(EnginePngError::Contract("Canvas color member is invalid"));
    }
    let mut color = [0_u8; 4];
    for (index, target) in color.iter_mut().enumerate() {
        *target = u8::from_str_radix(&value[1 + index * 2..3 + index * 2], 16)
            .map_err(|_| EnginePngError::Contract("Canvas color member is invalid"))?;
    }
    Ok(color)
}

fn boolean_member(
    object: &Map<String, Value>,
    member: &'static str,
) -> Result<bool, EnginePngError> {
    object
        .get(member)
        .and_then(Value::as_bool)
        .ok_or(EnginePngError::Contract("boolean member is absent"))
}

fn text_member<'a>(
    object: &'a Map<String, Value>,
    member: &'static str,
) -> Result<&'a str, EnginePngError> {
    object
        .get(member)
        .and_then(Value::as_str)
        .ok_or(EnginePngError::Contract("text member is absent"))
}

fn number_member(object: &Map<String, Value>, member: &'static str) -> Result<f64, EnginePngError> {
    object
        .get(member)
        .and_then(Value::as_number)
        .and_then(serde_json::Number::as_f64)
        .filter(|value| value.is_finite())
        .ok_or(EnginePngError::Contract("number member is absent"))
}

fn number_equals(
    object: &Map<String, Value>,
    member: &'static str,
    expected: f64,
) -> Result<bool, EnginePngError> {
    Ok(number_member(object, member)?.to_bits() == expected.to_bits())
}

fn has_exact_members(object: &Map<String, Value>, expected: &[&str]) -> bool {
    object.len() == expected.len() && expected.iter().all(|member| object.contains_key(*member))
}

fn decimal6_member(
    object: &Map<String, Value>,
    member: &'static str,
) -> Result<i128, EnginePngError> {
    parse_decimal6(&decimal_member(object, member)?)
}

fn parse_decimal6(raw: &str) -> Result<i128, EnginePngError> {
    let (negative, unsigned) = match raw.strip_prefix('-') {
        Some(unsigned) => (true, unsigned),
        None => (false, raw),
    };
    if unsigned.is_empty() || unsigned.contains(['e', 'E', '+']) {
        return Err(EnginePngError::Contract("decimal member is invalid"));
    }
    let mut parts = unsigned.split('.');
    let whole = parts.next().unwrap_or_default();
    let fraction = parts.next().unwrap_or_default();
    if parts.next().is_some()
        || whole.is_empty()
        || !whole.bytes().all(|byte| byte.is_ascii_digit())
        || fraction.len() > 6
        || !fraction.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(EnginePngError::Contract("decimal member is invalid"));
    }
    let whole = whole
        .parse::<i128>()
        .map_err(|_| EnginePngError::Contract("decimal member is invalid"))?;
    let fraction = if fraction.is_empty() {
        0
    } else {
        fraction
            .parse::<i128>()
            .map_err(|_| EnginePngError::Contract("decimal member is invalid"))?
            .checked_mul(10_i128.pow(6_u32 - fraction.len() as u32))
            .ok_or(EnginePngError::Contract("decimal member is invalid"))?
    };
    let scaled = whole
        .checked_mul(1_000_000)
        .and_then(|value| value.checked_add(fraction))
        .ok_or(EnginePngError::Contract("decimal member is invalid"))?;
    Ok(if negative { -scaled } else { scaled })
}

fn decimal_member(
    object: &Map<String, Value>,
    member: &'static str,
) -> Result<String, EnginePngError> {
    object
        .get(member)
        .and_then(Value::as_number)
        .map(ToString::to_string)
        .ok_or(EnginePngError::Contract("Canvas decimal member is absent"))
}

fn object_member<'a>(
    object: Option<&'a Map<String, Value>>,
    member: &'static str,
) -> Result<&'a Map<String, Value>, EnginePngError> {
    object
        .and_then(|value| value.get(member))
        .and_then(Value::as_object)
        .ok_or(EnginePngError::Contract("Canvas object member is absent"))
}

fn array_member<'a>(
    object: &'a Map<String, Value>,
    member: &'static str,
) -> Result<&'a [Value], EnginePngError> {
    object
        .get(member)
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .ok_or(EnginePngError::Contract("Canvas array member is absent"))
}
