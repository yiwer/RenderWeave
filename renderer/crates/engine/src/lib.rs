//! RenderWeave RenderEngine execution kernel.
//!
//! The module deliberately exposes one deep interface. Callers provide an already-admitted
//! RenderDocument and an effective PNG DPI; document traversal, layout, surface construction,
//! definite container scene preparation, canonical pixels, encoding, and output identity remain
//! implementation details.

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
    FramePaint,
    RectPaint,
    NonOpaqueRectAlpha,
    NonPixelAlignedClip,
    NonPixelAlignedRect,
    PartialBackgroundAlpha,
}

impl EnginePngUnsupported {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::ResourceManifest => "RESOURCE_MANIFEST",
            Self::SceneStructure => "SCENE_STRUCTURE",
            Self::FramePaint => "FRAME_PAINT",
            Self::RectPaint => "RECT_PAINT",
            Self::NonOpaqueRectAlpha => "NON_OPAQUE_RECT_ALPHA",
            Self::NonPixelAlignedClip => "NON_PIXEL_ALIGNED_CLIP",
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

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PixelClip {
    left: u32,
    top: u32,
    right: u32,
    bottom: u32,
}

impl PixelClip {
    const fn surface(width: u32, height: u32) -> Self {
        Self {
            left: 0,
            top: 0,
            right: width,
            bottom: height,
        }
    }

    fn intersect(self, other: Self) -> Self {
        Self {
            left: self.left.max(other.left),
            top: self.top.max(other.top),
            right: self.right.min(other.right),
            bottom: self.bottom.min(other.bottom),
        }
        .normalized_empty()
    }

    fn apply(self, rect: PixelRect) -> PixelRect {
        PixelRect {
            left: rect.left.max(self.left),
            top: rect.top.max(self.top),
            right: rect.right.min(self.right),
            bottom: rect.bottom.min(self.bottom),
            color: rect.color,
        }
        .normalized_empty()
    }

    fn normalized_empty(mut self) -> Self {
        if self.right < self.left {
            self.right = self.left;
        }
        if self.bottom < self.top {
            self.bottom = self.top;
        }
        self
    }
}

impl PixelRect {
    fn normalized_empty(mut self) -> Self {
        if self.right < self.left {
            self.right = self.left;
        }
        if self.bottom < self.top {
            self.bottom = self.top;
        }
        self
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PreparedContainer {
    paint: Option<PixelRect>,
    descendant_clip: PixelClip,
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
    require_scene_kinds(children)?;

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
    if layout.entries().len() != document.occurrence_count()
        || layout.entries()[0].kind() != "canvas"
    {
        return Err(EnginePngError::Contract(
            "Engine PNG layout entry shape diverged from the admitted scene",
        ));
    }

    let bleed_left = parse_decimal6(&bleed_left)?;
    let bleed_top = parse_decimal6(&bleed_top)?;
    let mut paints = Vec::new();
    paints
        .try_reserve_exact(layout.entries().len().saturating_sub(1))
        .map_err(|_| EnginePngError::RasterAllocation)?;
    let mut layout_cursor = 1;
    prepare_scene(
        children,
        layout.entries(),
        &mut layout_cursor,
        PixelClip::surface(surface.width_px(), surface.height_px()),
        bleed_left,
        bleed_top,
        dpi,
        surface.width_px(),
        surface.height_px(),
        &mut paints,
    )?;
    if layout_cursor != layout.entries().len() {
        return Err(EnginePngError::Contract(
            "Engine PNG layout preorder diverged from the admitted scene",
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
    for rect in paints {
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

fn require_scene_kinds(nodes: &[Value]) -> Result<(), EnginePngError> {
    for node in nodes {
        let node = node
            .as_object()
            .ok_or(EnginePngError::Contract("Scene child is not an object"))?;
        match text_member(node, "kind")? {
            "rect" => {}
            "group" | "frame" | "stack" | "grid" => {
                require_scene_kinds(array_member(node, "children")?)?
            }
            _ => {
                return Err(EnginePngError::Unsupported(
                    EnginePngUnsupported::SceneStructure,
                ));
            }
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn prepare_scene(
    nodes: &[Value],
    layout_entries: &[DefiniteLayoutEntry],
    layout_cursor: &mut usize,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
    paints: &mut Vec<PixelRect>,
) -> Result<(), EnginePngError> {
    for node in nodes {
        let node = node
            .as_object()
            .ok_or(EnginePngError::Contract("Scene child is not an object"))?;
        let layout = layout_entries
            .get(*layout_cursor)
            .ok_or(EnginePngError::Contract(
                "Engine PNG layout preorder ended before the admitted scene",
            ))?;
        *layout_cursor = layout_cursor
            .checked_add(1)
            .ok_or(EnginePngError::Contract(
                "Engine PNG layout cursor overflowed",
            ))?;

        match text_member(node, "kind")? {
            "rect" => paints.push(prepare_rect_paint(
                node,
                layout,
                active_clip,
                bleed_left,
                bleed_top,
                dpi,
                surface_width,
                surface_height,
            )?),
            "group" => {
                prepare_group(node, layout)?;
                prepare_scene(
                    array_member(node, "children")?,
                    layout_entries,
                    layout_cursor,
                    active_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                    paints,
                )?;
            }
            "frame" | "stack" | "grid" => {
                let prepared = prepare_container(
                    node,
                    layout,
                    active_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                )?;
                if let Some(paint) = prepared.paint {
                    paints.push(paint);
                }
                prepare_scene(
                    array_member(node, "children")?,
                    layout_entries,
                    layout_cursor,
                    prepared.descendant_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                    paints,
                )?;
            }
            _ => {
                return Err(EnginePngError::Unsupported(
                    EnginePngUnsupported::SceneStructure,
                ));
            }
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn prepare_rect_paint(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
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

    if !identity_transform(node)? {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
    }
    if !zero_corner_radii(node)? {
        return Err(EnginePngError::Unsupported(EnginePngUnsupported::RectPaint));
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

    require_layout_entry(node, layout, "rect", false)?;
    Ok(active_clip.apply(prepare_layout_rect(
        layout.layout_box(),
        color,
        bleed_left,
        bleed_top,
        dpi,
        surface_width,
        surface_height,
    )?))
}

fn prepare_group(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
) -> Result<(), EnginePngError> {
    if text_member(node, "kind")? != "group"
        || !boolean_member(node, "visible")?
        || !number_equals(node, "opacity", 1.0)?
        || !identity_transform(node)?
    {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }
    require_layout_entry(node, layout, "group", false)
}

#[allow(clippy::too_many_arguments)]
fn prepare_container(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<PreparedContainer, EnginePngError> {
    let kind = text_member(node, "kind")?;
    if !matches!(kind, "frame" | "stack" | "grid")
        || !boolean_member(node, "visible")?
        || !number_equals(node, "opacity", 1.0)?
        || node.contains_key("stroke")
        || !identity_transform(node)?
        || !zero_corner_radii(node)?
    {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
    require_layout_entry(node, layout, kind, true)?;
    let clip_content = boolean_member(node, "clipContent")?;

    let bounds = if clip_content {
        Some(prepare_layout_clip(
            layout.layout_box(),
            bleed_left,
            bleed_top,
            dpi,
            surface_width,
            surface_height,
            EnginePngUnsupported::NonPixelAlignedClip,
        )?)
    } else if node.contains_key("fill") {
        Some(prepare_layout_clip(
            layout.layout_box(),
            bleed_left,
            bleed_top,
            dpi,
            surface_width,
            surface_height,
            EnginePngUnsupported::NonPixelAlignedRect,
        )?)
    } else {
        None
    };

    let paint = if let Some(fill) = node.get("fill") {
        let fill = fill
            .as_object()
            .ok_or(EnginePngError::Contract("Container fill is not an object"))?;
        if !has_exact_members(fill, &["color"]) {
            return Err(EnginePngError::Unsupported(
                EnginePngUnsupported::FramePaint,
            ));
        }
        let color = color_member(fill, "color")?;
        if color[3] != 255 {
            return Err(EnginePngError::Unsupported(
                EnginePngUnsupported::FramePaint,
            ));
        }
        let bounds = bounds.ok_or(EnginePngError::Contract(
            "Container fill is missing prepared device bounds",
        ))?;
        Some(active_clip.apply(PixelRect {
            left: bounds.left,
            top: bounds.top,
            right: bounds.right,
            bottom: bounds.bottom,
            color,
        }))
    } else {
        None
    };

    let descendant_clip = if clip_content {
        active_clip.intersect(bounds.ok_or(EnginePngError::Contract(
            "Container clip is missing prepared device bounds",
        ))?)
    } else {
        active_clip
    };
    Ok(PreparedContainer {
        paint,
        descendant_clip,
    })
}

fn identity_transform(node: &Map<String, Value>) -> Result<bool, EnginePngError> {
    let transform = object_member(Some(node), "transform")?;
    Ok(has_exact_members(
        transform,
        &["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
    ) && number_equals(transform, "originX", 0.5)?
        && number_equals(transform, "originY", 0.5)?
        && number_equals(transform, "rotationDeg", 0.0)?
        && number_equals(transform, "scaleX", 1.0)?
        && number_equals(transform, "scaleY", 1.0)?)
}

fn zero_corner_radii(node: &Map<String, Value>) -> Result<bool, EnginePngError> {
    let radii = object_member(Some(node), "cornerRadii")?;
    let members = ["bottomLeftPt", "bottomRightPt", "topLeftPt", "topRightPt"];
    if !has_exact_members(radii, &members) {
        return Ok(false);
    }
    for member in members {
        if !number_equals(radii, member, 0.0)? {
            return Ok(false);
        }
    }
    Ok(true)
}

fn require_layout_entry(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    kind: &str,
    has_content_box: bool,
) -> Result<(), EnginePngError> {
    if layout.kind() != kind || layout.occurrence_id() != text_member(node, "occurrenceId")? {
        return Err(EnginePngError::Contract(
            "Engine PNG layout entry identity diverged from the admitted scene",
        ));
    }
    require_valid_layout_box(layout.layout_box())?;
    match (has_content_box, layout.content_box()) {
        (true, Some(content_box)) => require_valid_layout_box(content_box),
        (false, None) => Ok(()),
        _ => Err(EnginePngError::Contract(
            "Engine PNG layout entry content shape diverged from the admitted scene",
        )),
    }
}

fn require_valid_layout_box(layout_box: &LocalLayoutBox) -> Result<(), EnginePngError> {
    if layout_box.x().is_finite()
        && layout_box.y().is_finite()
        && layout_box.width().is_finite()
        && layout_box.height().is_finite()
        && layout_box.width() >= 0.0
        && layout_box.height() >= 0.0
    {
        Ok(())
    } else {
        Err(EnginePngError::Contract(
            "Engine PNG layout entry contains an invalid box",
        ))
    }
}

#[allow(clippy::too_many_arguments)]
fn prepare_layout_rect(
    layout_box: &LocalLayoutBox,
    color: [u8; 4],
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<PixelRect, EnginePngError> {
    let bounds = prepare_layout_clip(
        layout_box,
        bleed_left,
        bleed_top,
        dpi,
        surface_width,
        surface_height,
        EnginePngUnsupported::NonPixelAlignedRect,
    )?;
    Ok(PixelRect {
        left: bounds.left,
        top: bounds.top,
        right: bounds.right,
        bottom: bounds.bottom,
        color,
    })
}

#[allow(clippy::too_many_arguments)]
fn prepare_layout_clip(
    layout_box: &LocalLayoutBox,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
    misaligned: EnginePngUnsupported,
) -> Result<PixelClip, EnginePngError> {
    require_valid_layout_box(layout_box)?;
    let right = layout_box.x() + layout_box.width();
    let bottom = layout_box.y() + layout_box.height();
    if !right.is_finite()
        || !bottom.is_finite()
        || right < layout_box.x()
        || bottom < layout_box.y()
    {
        return Err(EnginePngError::Contract(
            "Paint layout box is not finite and monotonic",
        ));
    }

    let device_left = exact_layout_device_edge(layout_box.x(), bleed_left, dpi, misaligned)?;
    let device_top = exact_layout_device_edge(layout_box.y(), bleed_top, dpi, misaligned)?;
    let device_right = exact_layout_device_edge(right, bleed_left, dpi, misaligned)?;
    let device_bottom = exact_layout_device_edge(bottom, bleed_top, dpi, misaligned)?;
    if device_right < device_left || device_bottom < device_top {
        return Err(EnginePngError::Contract(
            "Paint device box is not monotonic",
        ));
    }
    Ok(PixelClip {
        left: clip_device_edge(device_left, surface_width),
        top: clip_device_edge(device_top, surface_height),
        right: clip_device_edge(device_right, surface_width),
        bottom: clip_device_edge(device_bottom, surface_height),
    })
}

fn exact_layout_device_edge(
    coordinate: f64,
    bleed_scaled: i128,
    dpi: u32,
    misaligned: EnginePngUnsupported,
) -> Result<i128, EnginePngError> {
    const DECIMAL_DENOMINATOR: i128 = 1_000_000;
    const POINTS_PER_INCH: i128 = 72;

    let (coordinate_numerator, coordinate_denominator) = exact_binary64_ratio(coordinate)?;
    let point_numerator = coordinate_numerator
        .checked_mul(DECIMAL_DENOMINATOR)
        .and_then(|value| {
            bleed_scaled
                .checked_mul(coordinate_denominator)
                .and_then(|bleed| value.checked_add(bleed))
        })
        .ok_or(EnginePngError::Contract(
            "Paint device coordinate overflowed",
        ))?;
    let point_denominator = coordinate_denominator
        .checked_mul(DECIMAL_DENOMINATOR)
        .ok_or(EnginePngError::Contract(
            "Paint device coordinate overflowed",
        ))?;
    let device_numerator =
        point_numerator
            .checked_mul(i128::from(dpi))
            .ok_or(EnginePngError::Contract(
                "Paint device coordinate overflowed",
            ))?;
    let device_denominator =
        point_denominator
            .checked_mul(POINTS_PER_INCH)
            .ok_or(EnginePngError::Contract(
                "Paint device coordinate overflowed",
            ))?;
    if device_numerator % device_denominator != 0 {
        return Err(EnginePngError::Unsupported(misaligned));
    }
    Ok(device_numerator / device_denominator)
}

fn exact_binary64_ratio(value: f64) -> Result<(i128, i128), EnginePngError> {
    if !value.is_finite() {
        return Err(EnginePngError::Contract(
            "Paint layout coordinate is not finite",
        ));
    }
    if value == 0.0 {
        return Ok((0, 1));
    }

    let bits = value.to_bits();
    let negative = bits >> 63 != 0;
    let exponent_bits = ((bits >> 52) & 0x7ff) as i32;
    let fraction = bits & ((1_u64 << 52) - 1);
    let (mut significand, mut exponent) = if exponent_bits == 0 {
        (fraction, -1074)
    } else {
        ((1_u64 << 52) | fraction, exponent_bits - 1023 - 52)
    };
    if significand == 0 {
        return Ok((0, 1));
    }
    if exponent < 0 {
        let cancellation = significand.trailing_zeros().min((-exponent) as u32);
        significand >>= cancellation;
        exponent += cancellation as i32;
    }

    let mut numerator = i128::from(significand);
    let denominator;
    if exponent >= 0 {
        let factor = 2_i128
            .checked_pow(exponent as u32)
            .ok_or(EnginePngError::Contract(
                "Paint layout coordinate exceeds exact engine bounds",
            ))?;
        numerator = numerator
            .checked_mul(factor)
            .ok_or(EnginePngError::Contract(
                "Paint layout coordinate exceeds exact engine bounds",
            ))?;
        denominator = 1;
    } else {
        denominator = 2_i128
            .checked_pow((-exponent) as u32)
            .ok_or(EnginePngError::Contract(
                "Paint layout coordinate exceeds exact engine bounds",
            ))?;
    }
    if negative {
        numerator = numerator.checked_neg().ok_or(EnginePngError::Contract(
            "Paint layout coordinate exceeds exact engine bounds",
        ))?;
    }
    Ok((numerator, denominator))
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
