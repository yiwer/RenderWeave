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

#[derive(Clone, Copy, Debug, PartialEq)]
struct SceneOrigin {
    scaled_x: i128,
    scaled_y: i128,
    layout_x: f64,
    layout_y: f64,
}

impl SceneOrigin {
    const ROOT: Self = Self {
        scaled_x: 0,
        scaled_y: 0,
        layout_x: 0.0,
        layout_y: 0.0,
    };
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct PreparedFrame {
    paint: Option<PixelRect>,
    content_origin: SceneOrigin,
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
        SceneOrigin::ROOT,
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
            "frame" => require_scene_kinds(array_member(node, "children")?)?,
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
    parent_content_origin: SceneOrigin,
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
                parent_content_origin,
                active_clip,
                bleed_left,
                bleed_top,
                dpi,
                surface_width,
                surface_height,
            )?),
            "frame" => {
                let prepared = prepare_frame(
                    node,
                    layout,
                    parent_content_origin,
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
                    prepared.content_origin,
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
    parent_content_origin: SceneOrigin,
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
    let origin = child_origin(parent_content_origin, placement)?;
    require_fixed_box(layout.layout_box(), origin, placement, "Rect")?;

    let width = decimal6_member(placement, "widthPt")?;
    let height = decimal6_member(placement, "heightPt")?;
    Ok(active_clip.apply(prepare_pixel_rect(
        origin,
        width,
        height,
        color,
        bleed_left,
        bleed_top,
        dpi,
        surface_width,
        surface_height,
    )?))
}

#[allow(clippy::too_many_arguments)]
fn prepare_frame(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    parent_content_origin: SceneOrigin,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<PreparedFrame, EnginePngError> {
    const REQUIRED_MEMBERS: [&str; 10] = [
        "children",
        "clipContent",
        "cornerRadii",
        "kind",
        "occurrenceId",
        "opacity",
        "padding",
        "placement",
        "transform",
        "visible",
    ];
    if node.len() != REQUIRED_MEMBERS.len() + usize::from(node.contains_key("fill"))
        || REQUIRED_MEMBERS
            .iter()
            .any(|member| !node.contains_key(*member))
        || node
            .keys()
            .any(|member| !REQUIRED_MEMBERS.contains(&member.as_str()) && member.as_str() != "fill")
        || !boolean_member(node, "visible")?
        || !number_equals(node, "opacity", 1.0)?
    {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
    if !identity_transform(node)? || !zero_corner_radii(node)? {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }

    let placement = object_member(Some(node), "placement")?;
    if !fixed_absolute_placement(placement)? {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
    let occurrence_id = text_member(node, "occurrenceId")?;
    if layout.kind() != "frame" || layout.occurrence_id() != occurrence_id {
        return Err(EnginePngError::Contract(
            "Frame layout entry identity diverged from the admitted scene",
        ));
    }
    let origin = child_origin(parent_content_origin, placement)?;
    require_fixed_box(layout.layout_box(), origin, placement, "Frame")?;
    let clip_content = boolean_member(node, "clipContent")?;
    let frame_width = decimal6_member(placement, "widthPt")?;
    let frame_height = decimal6_member(placement, "heightPt")?;

    let padding = object_member(Some(node), "padding")?;
    if !has_exact_members(padding, &["bottomPt", "leftPt", "rightPt", "topPt"]) {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
    let padding_bottom = decimal6_member(padding, "bottomPt")?;
    let padding_left = decimal6_member(padding, "leftPt")?;
    let padding_right = decimal6_member(padding, "rightPt")?;
    let padding_top = decimal6_member(padding, "topPt")?;
    if [padding_bottom, padding_left, padding_right, padding_top]
        .into_iter()
        .any(|value| value < 0)
    {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
    let content_origin = SceneOrigin {
        scaled_x: checked_scaled_add(origin.scaled_x, padding_left)?,
        scaled_y: checked_scaled_add(origin.scaled_y, padding_top)?,
        layout_x: finite_layout_sum(origin.layout_x, number_member(padding, "leftPt")?)?,
        layout_y: finite_layout_sum(origin.layout_y, number_member(padding, "topPt")?)?,
    };
    require_frame_content_box(layout, content_origin, placement, padding)?;

    let frame_bounds = if clip_content {
        Some(prepare_pixel_clip(
            origin,
            frame_width,
            frame_height,
            bleed_left,
            bleed_top,
            dpi,
            surface_width,
            surface_height,
            EnginePngUnsupported::NonPixelAlignedClip,
        )?)
    } else if node.contains_key("fill") {
        Some(prepare_pixel_clip(
            origin,
            frame_width,
            frame_height,
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
            .ok_or(EnginePngError::Contract("Frame fill is not an object"))?;
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
        let bounds = frame_bounds.ok_or(EnginePngError::Contract(
            "Frame fill is missing prepared device bounds",
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
        active_clip.intersect(frame_bounds.ok_or(EnginePngError::Contract(
            "Frame clip is missing prepared device bounds",
        ))?)
    } else {
        active_clip
    };

    Ok(PreparedFrame {
        paint,
        content_origin,
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

fn fixed_absolute_placement(placement: &Map<String, Value>) -> Result<bool, EnginePngError> {
    Ok(has_exact_members(
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
    ) && text_member(placement, "type")? == "ABSOLUTE"
        && text_member(placement, "widthMode")? == "FIXED"
        && text_member(placement, "heightMode")? == "FIXED")
}

fn child_origin(
    parent_content_origin: SceneOrigin,
    placement: &Map<String, Value>,
) -> Result<SceneOrigin, EnginePngError> {
    Ok(SceneOrigin {
        scaled_x: checked_scaled_add(
            parent_content_origin.scaled_x,
            decimal6_member(placement, "xPt")?,
        )?,
        scaled_y: checked_scaled_add(
            parent_content_origin.scaled_y,
            decimal6_member(placement, "yPt")?,
        )?,
        layout_x: finite_layout_sum(
            parent_content_origin.layout_x,
            number_member(placement, "xPt")?,
        )?,
        layout_y: finite_layout_sum(
            parent_content_origin.layout_y,
            number_member(placement, "yPt")?,
        )?,
    })
}

fn checked_scaled_add(left: i128, right: i128) -> Result<i128, EnginePngError> {
    left.checked_add(right)
        .ok_or(EnginePngError::Contract("Scene coordinate overflowed"))
}

fn finite_layout_sum(left: f64, right: f64) -> Result<f64, EnginePngError> {
    let sum = left + right;
    sum.is_finite()
        .then_some(sum)
        .ok_or(EnginePngError::Contract(
            "Scene layout coordinate overflowed",
        ))
}

fn require_fixed_box(
    actual: &LocalLayoutBox,
    origin: SceneOrigin,
    placement: &Map<String, Value>,
    label: &'static str,
) -> Result<(), EnginePngError> {
    let expected = [
        origin.layout_x,
        origin.layout_y,
        number_member(placement, "widthPt")?,
        number_member(placement, "heightPt")?,
    ];
    let actual = [actual.x(), actual.y(), actual.width(), actual.height()];
    if actual
        .into_iter()
        .zip(expected)
        .any(|(actual, expected)| actual.to_bits() != expected.to_bits())
    {
        return Err(EnginePngError::Contract(match label {
            "Rect" => "Rect layout box diverged from authored FIXED geometry",
            "Frame" => "Frame layout box diverged from authored FIXED geometry",
            _ => "Scene layout box diverged from authored FIXED geometry",
        }));
    }
    Ok(())
}

fn require_frame_content_box(
    layout: &DefiniteLayoutEntry,
    content_origin: SceneOrigin,
    placement: &Map<String, Value>,
    padding: &Map<String, Value>,
) -> Result<(), EnginePngError> {
    let actual = layout.content_box().ok_or(EnginePngError::Contract(
        "Frame layout entry is missing its content box",
    ))?;
    let width = subtract_content_inset(
        subtract_content_inset(
            number_member(placement, "widthPt")?,
            number_member(padding, "leftPt")?,
        ),
        number_member(padding, "rightPt")?,
    );
    let height = subtract_content_inset(
        subtract_content_inset(
            number_member(placement, "heightPt")?,
            number_member(padding, "topPt")?,
        ),
        number_member(padding, "bottomPt")?,
    );
    let expected = [
        content_origin.layout_x,
        content_origin.layout_y,
        width,
        height,
    ];
    let actual = [actual.x(), actual.y(), actual.width(), actual.height()];
    if actual
        .into_iter()
        .zip(expected)
        .any(|(actual, expected)| actual.to_bits() != expected.to_bits())
    {
        return Err(EnginePngError::Contract(
            "Frame content box diverged from fixed padding geometry",
        ));
    }
    Ok(())
}

fn subtract_content_inset(size: f64, inset: f64) -> f64 {
    let remaining = size - inset;
    if remaining > 0.0 { remaining } else { 0.0 }
}

#[allow(clippy::too_many_arguments)]
fn prepare_pixel_rect(
    origin: SceneOrigin,
    width: i128,
    height: i128,
    color: [u8; 4],
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<PixelRect, EnginePngError> {
    let bounds = prepare_pixel_clip(
        origin,
        width,
        height,
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
fn prepare_pixel_clip(
    origin: SceneOrigin,
    width: i128,
    height: i128,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
    misaligned: EnginePngUnsupported,
) -> Result<PixelClip, EnginePngError> {
    let device_left = exact_device_edge(&[bleed_left, origin.scaled_x], dpi, misaligned)?;
    let device_top = exact_device_edge(&[bleed_top, origin.scaled_y], dpi, misaligned)?;
    let device_right = exact_device_edge(&[bleed_left, origin.scaled_x, width], dpi, misaligned)?;
    let device_bottom = exact_device_edge(&[bleed_top, origin.scaled_y, height], dpi, misaligned)?;
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

fn exact_device_edge(
    parts: &[i128],
    dpi: u32,
    misaligned: EnginePngUnsupported,
) -> Result<i128, EnginePngError> {
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
        return Err(EnginePngError::Unsupported(misaligned));
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
