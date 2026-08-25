//! RenderWeave RenderEngine execution kernel.
//!
//! The module deliberately exposes narrow resource-free and prepared-resource entry points into
//! one deep kernel. Callers provide an already-admitted RenderDocument and an effective PNG DPI;
//! document traversal, layout, surface construction, definite scene preparation, canonical
//! pixels, encoding, and output identity remain implementation details.

use std::fmt::{Display, Formatter};

use renderweave_renderer_document::AdmittedRenderDocument;
use renderweave_renderer_layout::{
    DefiniteLayoutEntry, LocalLayoutBox, layout_definite_resource_free,
    layout_definite_with_prepared_resources,
};
use renderweave_renderer_output_png::{
    BleedPt, OutputPngError, SurfaceSpec, encode_straight_rgba8, preflight_surface,
};
use renderweave_renderer_resource::{PreparedRenderResource, PreparedResourceManifest};
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
    ImagePaint,
    ImageResampling,
    NonOpaqueRectAlpha,
    NonOpaqueImageAlpha,
    NonPixelAlignedClip,
    NonPixelAlignedImage,
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
            Self::ImagePaint => "IMAGE_PAINT",
            Self::ImageResampling => "IMAGE_RESAMPLING",
            Self::NonOpaqueRectAlpha => "NON_OPAQUE_RECT_ALPHA",
            Self::NonOpaqueImageAlpha => "NON_OPAQUE_IMAGE_ALPHA",
            Self::NonPixelAlignedClip => "NON_PIXEL_ALIGNED_CLIP",
            Self::NonPixelAlignedImage => "NON_PIXEL_ALIGNED_IMAGE",
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
struct PixelImage<'resource> {
    left: u32,
    top: u32,
    right: u32,
    bottom: u32,
    source_left: u32,
    source_top: u32,
    source_width: u32,
    source_height: u32,
    straight_rgba8: &'resource [u8],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PixelPaint<'resource> {
    Rect(PixelRect),
    Image(PixelImage<'resource>),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PaintCommand<'resource> {
    BeginOpacity,
    Paint(PixelPaint<'resource>),
    EndOpacity(u8),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum NodeDrawState {
    Suppressed,
    FullOpacity,
    PartialOpacity(u8),
}

impl NodeDrawState {
    const fn enabled(self) -> bool {
        !matches!(self, Self::Suppressed)
    }

    const fn partial_opacity(self) -> Option<u8> {
        match self {
            Self::PartialOpacity(opacity) => Some(opacity),
            Self::Suppressed | Self::FullOpacity => None,
        }
    }
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
    descendant_draw_enabled: bool,
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

    pub fn into_bytes(self) -> Vec<u8> {
        self.bytes
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
    render_png_internal(document, None, dpi)
}

pub fn render_png_with_prepared_resources(
    document: &AdmittedRenderDocument,
    prepared_resources: &PreparedResourceManifest,
    dpi: u32,
) -> Result<EnginePngOutput, EnginePngError> {
    render_png_internal(document, Some(prepared_resources), dpi)
}

fn render_png_internal(
    document: &AdmittedRenderDocument,
    prepared_resources: Option<&PreparedResourceManifest>,
    dpi: u32,
) -> Result<EnginePngOutput, EnginePngError> {
    let root: Value = serde_json::from_str(document.canonical_document())
        .map_err(|_| EnginePngError::Contract("admitted RenderDocument could not be parsed"))?;
    let canvas = object_member(root.as_object(), "canvas")?;
    let children = array_member(canvas, "children")?;
    require_scene_kinds(children, prepared_resources.is_some())?;

    let background = color_member(canvas, "backgroundColor")?;
    let pixel = premultiply_straight_rgba8(background);

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

    let layout = match prepared_resources {
        Some(resources) => layout_definite_with_prepared_resources(document, resources),
        None => layout_definite_resource_free(document),
    }
    .map_err(|_| EnginePngError::Layout)?;
    if layout.entries().len() != document.occurrence_count()
        || layout.entries()[0].kind() != "canvas"
    {
        return Err(EnginePngError::Contract(
            "Engine PNG layout entry shape diverged from the admitted scene",
        ));
    }

    let bleed_left = parse_decimal6(&bleed_left)?;
    let bleed_top = parse_decimal6(&bleed_top)?;
    let command_capacity = layout
        .entries()
        .len()
        .saturating_sub(1)
        .checked_mul(3)
        .ok_or(EnginePngError::RasterAllocation)?;
    let mut commands = Vec::new();
    commands
        .try_reserve_exact(command_capacity)
        .map_err(|_| EnginePngError::RasterAllocation)?;
    let mut layout_cursor = 1;
    prepare_scene(
        children,
        layout.entries(),
        &mut layout_cursor,
        true,
        PixelClip::surface(surface.width_px(), surface.height_px()),
        bleed_left,
        bleed_top,
        dpi,
        surface.width_px(),
        surface.height_px(),
        prepared_resources,
        &mut commands,
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
    rasterize_commands(
        &mut pixels,
        surface.width_px(),
        surface.height_px(),
        &commands,
    )?;
    unpremultiply_rgba8_surface(&mut pixels)?;
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

fn require_scene_kinds(nodes: &[Value], prepared_images: bool) -> Result<(), EnginePngError> {
    for node in nodes {
        let node = node
            .as_object()
            .ok_or(EnginePngError::Contract("Scene child is not an object"))?;
        match text_member(node, "kind")? {
            "rect" => {}
            "image" if prepared_images => {}
            "group" | "frame" | "stack" | "grid" => {
                require_scene_kinds(array_member(node, "children")?, prepared_images)?
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
fn prepare_scene<'resource>(
    nodes: &[Value],
    layout_entries: &[DefiniteLayoutEntry],
    layout_cursor: &mut usize,
    ancestor_draw_enabled: bool,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
    prepared_resources: Option<&'resource PreparedResourceManifest>,
    commands: &mut Vec<PaintCommand<'resource>>,
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

        let draw_state = node_draw_state(node, ancestor_draw_enabled)?;
        if draw_state.partial_opacity().is_some() {
            commands.push(PaintCommand::BeginOpacity);
        }

        match text_member(node, "kind")? {
            "rect" => {
                if let Some(paint) = prepare_rect_paint(
                    node,
                    layout,
                    draw_state.enabled(),
                    active_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                )? {
                    commands.push(PaintCommand::Paint(PixelPaint::Rect(paint)));
                }
            }
            "image" => {
                let resources = prepared_resources.ok_or(EnginePngError::Unsupported(
                    EnginePngUnsupported::SceneStructure,
                ))?;
                if let Some(paint) = prepare_image_paint(
                    node,
                    layout,
                    resources,
                    draw_state.enabled(),
                    active_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                )? {
                    commands.push(PaintCommand::Paint(PixelPaint::Image(paint)));
                }
            }
            "group" => {
                let descendant_draw_enabled = prepare_group(node, layout, draw_state.enabled())?;
                prepare_scene(
                    array_member(node, "children")?,
                    layout_entries,
                    layout_cursor,
                    descendant_draw_enabled,
                    active_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                    prepared_resources,
                    commands,
                )?;
            }
            "frame" | "stack" | "grid" => {
                let prepared = prepare_container(
                    node,
                    layout,
                    draw_state.enabled(),
                    active_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                )?;
                if let Some(paint) = prepared.paint {
                    commands.push(PaintCommand::Paint(PixelPaint::Rect(paint)));
                }
                prepare_scene(
                    array_member(node, "children")?,
                    layout_entries,
                    layout_cursor,
                    prepared.descendant_draw_enabled,
                    prepared.descendant_clip,
                    bleed_left,
                    bleed_top,
                    dpi,
                    surface_width,
                    surface_height,
                    prepared_resources,
                    commands,
                )?;
            }
            _ => {
                return Err(EnginePngError::Unsupported(
                    EnginePngUnsupported::SceneStructure,
                ));
            }
        }
        if let Some(opacity) = draw_state.partial_opacity() {
            commands.push(PaintCommand::EndOpacity(opacity));
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn prepare_rect_paint(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    draw_enabled: bool,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<Option<PixelRect>, EnginePngError> {
    if text_member(node, "kind")? != "rect" {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }
    require_layout_entry(node, layout, "rect", false)?;
    if !draw_enabled {
        return Ok(None);
    }
    if node.contains_key("stroke") {
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

    Ok(Some(active_clip.apply(prepare_layout_rect(
        layout.layout_box(),
        color,
        bleed_left,
        bleed_top,
        dpi,
        surface_width,
        surface_height,
    )?)))
}

#[allow(clippy::too_many_arguments)]
fn prepare_image_paint<'resource>(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    prepared_resources: &'resource PreparedResourceManifest,
    draw_enabled: bool,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<Option<PixelImage<'resource>>, EnginePngError> {
    if text_member(node, "kind")? != "image" {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }
    require_layout_entry(node, layout, "image", false)?;
    if !draw_enabled {
        return Ok(None);
    }
    if !identity_transform(node)? {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::ImagePaint,
        ));
    }
    if !matches!(text_member(node, "fit")?, "CONTAIN" | "COVER" | "FILL")
        || !matches!(text_member(node, "sampling")?, "LINEAR" | "NEAREST")
    {
        return Err(EnginePngError::Contract(
            "admitted Image fit or sampling token is invalid",
        ));
    }

    let resource_id = text_member(node, "imageResourceId")?;
    let Some(PreparedRenderResource::Image { image, .. }) = prepared_resources.get(resource_id)
    else {
        return Err(EnginePngError::Contract(
            "prepared IMAGE resource identity diverged from the admitted scene",
        ));
    };
    if image.resource_id() != resource_id {
        return Err(EnginePngError::Contract(
            "prepared IMAGE resource identity diverged from the admitted scene",
        ));
    }
    let source_length = u64::from(image.width_px())
        .checked_mul(u64::from(image.height_px()))
        .and_then(|pixels| pixels.checked_mul(4))
        .and_then(|bytes| usize::try_from(bytes).ok())
        .ok_or(EnginePngError::Contract(
            "prepared IMAGE pixel length overflowed",
        ))?;
    let source = image.straight_rgba8();
    if source.len() != source_length {
        return Err(EnginePngError::Contract(
            "prepared IMAGE pixel length diverged from its dimensions",
        ));
    }
    let layout_box = layout.layout_box();
    require_valid_layout_box(layout_box)?;
    let right = layout_box.x() + layout_box.width();
    let bottom = layout_box.y() + layout_box.height();
    if !right.is_finite()
        || !bottom.is_finite()
        || right < layout_box.x()
        || bottom < layout_box.y()
    {
        return Err(EnginePngError::Contract(
            "Image layout box is not finite and monotonic",
        ));
    }
    let device_left = exact_layout_device_edge(
        layout_box.x(),
        bleed_left,
        dpi,
        EnginePngUnsupported::NonPixelAlignedImage,
    )?;
    let device_top = exact_layout_device_edge(
        layout_box.y(),
        bleed_top,
        dpi,
        EnginePngUnsupported::NonPixelAlignedImage,
    )?;
    let device_right = exact_layout_device_edge(
        right,
        bleed_left,
        dpi,
        EnginePngUnsupported::NonPixelAlignedImage,
    )?;
    let device_bottom = exact_layout_device_edge(
        bottom,
        bleed_top,
        dpi,
        EnginePngUnsupported::NonPixelAlignedImage,
    )?;
    if device_right < device_left || device_bottom < device_top {
        return Err(EnginePngError::Contract(
            "Image device box is not monotonic",
        ));
    }
    if device_right - device_left != i128::from(image.width_px())
        || device_bottom - device_top != i128::from(image.height_px())
    {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::ImageResampling,
        ));
    }

    let self_clip = PixelClip {
        left: clip_device_edge(device_left, surface_width),
        top: clip_device_edge(device_top, surface_height),
        right: clip_device_edge(device_right, surface_width),
        bottom: clip_device_edge(device_bottom, surface_height),
    };
    let destination = active_clip.intersect(self_clip);
    if destination.left == destination.right || destination.top == destination.bottom {
        return Ok(None);
    }
    let source_left = u32::try_from(i128::from(destination.left) - device_left)
        .map_err(|_| EnginePngError::Contract("Image source clip offset is invalid"))?;
    let source_top = u32::try_from(i128::from(destination.top) - device_top)
        .map_err(|_| EnginePngError::Contract("Image source clip offset is invalid"))?;
    let copied_width = destination.right - destination.left;
    let copied_height = destination.bottom - destination.top;
    if source_left
        .checked_add(copied_width)
        .is_none_or(|right| right > image.width_px())
        || source_top
            .checked_add(copied_height)
            .is_none_or(|bottom| bottom > image.height_px())
    {
        return Err(EnginePngError::Contract(
            "Image source clip exceeds prepared pixels",
        ));
    }
    Ok(Some(PixelImage {
        left: destination.left,
        top: destination.top,
        right: destination.right,
        bottom: destination.bottom,
        source_left,
        source_top,
        source_width: image.width_px(),
        source_height: image.height_px(),
        straight_rgba8: source,
    }))
}

fn prepare_group(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    draw_enabled: bool,
) -> Result<bool, EnginePngError> {
    if text_member(node, "kind")? != "group" {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }
    require_layout_entry(node, layout, "group", false)?;
    if draw_enabled && !identity_transform(node)? {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::SceneStructure,
        ));
    }
    Ok(draw_enabled)
}

#[allow(clippy::too_many_arguments)]
fn prepare_container(
    node: &Map<String, Value>,
    layout: &DefiniteLayoutEntry,
    draw_enabled: bool,
    active_clip: PixelClip,
    bleed_left: i128,
    bleed_top: i128,
    dpi: u32,
    surface_width: u32,
    surface_height: u32,
) -> Result<PreparedContainer, EnginePngError> {
    let kind = text_member(node, "kind")?;
    if !matches!(kind, "frame" | "stack" | "grid") {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
    require_layout_entry(node, layout, kind, true)?;
    if !draw_enabled {
        return Ok(PreparedContainer {
            paint: None,
            descendant_clip: active_clip,
            descendant_draw_enabled: false,
        });
    }
    if node.contains_key("stroke") || !identity_transform(node)? || !zero_corner_radii(node)? {
        return Err(EnginePngError::Unsupported(
            EnginePngUnsupported::FramePaint,
        ));
    }
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
        descendant_draw_enabled: true,
    })
}

fn node_draw_state(
    node: &Map<String, Value>,
    ancestor_draw_enabled: bool,
) -> Result<NodeDrawState, EnginePngError> {
    let visible = boolean_member(node, "visible")?;
    let opacity = parse_decimal6(&decimal_member(node, "opacity")?)?;
    if !(0..=1_000_000).contains(&opacity) {
        return Err(EnginePngError::Contract("Node opacity is outside 0..1"));
    }
    if !ancestor_draw_enabled || !visible || opacity == 0 {
        return Ok(NodeDrawState::Suppressed);
    }
    if opacity == 1_000_000 {
        return Ok(NodeDrawState::FullOpacity);
    }
    let opacity = opacity
        .checked_mul(255)
        .and_then(|value| value.checked_add(500_000))
        .map(|value| value / 1_000_000)
        .and_then(|value| u8::try_from(value).ok())
        .ok_or(EnginePngError::Contract(
            "Node opacity could not be lowered",
        ))?;
    Ok(NodeDrawState::PartialOpacity(opacity))
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

fn rasterize_commands(
    pixels: &mut [u8],
    surface_width: u32,
    surface_height: u32,
    commands: &[PaintCommand<'_>],
) -> Result<(), EnginePngError> {
    let row_bytes = usize::try_from(surface_width)
        .ok()
        .and_then(|width| width.checked_mul(4))
        .ok_or(EnginePngError::RasterAllocation)?;
    let expected_surface_bytes = row_bytes
        .checked_mul(usize::try_from(surface_height).map_err(|_| EnginePngError::RasterAllocation)?)
        .ok_or(EnginePngError::RasterAllocation)?;
    if pixels.len() != expected_surface_bytes {
        return Err(EnginePngError::Contract(
            "Raster surface length diverged from its dimensions",
        ));
    }

    let maximum_depth = maximum_opacity_depth(commands)?;
    let scratch_bytes = row_bytes
        .checked_mul(maximum_depth)
        .ok_or(EnginePngError::RasterAllocation)?;
    let mut layer_pixels = Vec::new();
    layer_pixels
        .try_reserve_exact(scratch_bytes)
        .map_err(|_| EnginePngError::RasterAllocation)?;
    layer_pixels.resize(scratch_bytes, 0);
    let mut layer_dirty = Vec::new();
    layer_dirty
        .try_reserve_exact(maximum_depth)
        .map_err(|_| EnginePngError::RasterAllocation)?;
    layer_dirty.resize(maximum_depth, None);

    for row_index in 0..surface_height {
        let row_start = usize::try_from(row_index)
            .ok()
            .and_then(|row| row.checked_mul(row_bytes))
            .ok_or(EnginePngError::RasterAllocation)?;
        let surface_row = pixels
            .get_mut(row_start..row_start + row_bytes)
            .ok_or(EnginePngError::RasterAllocation)?;
        let mut active_depth = 0_usize;

        for command in commands {
            match *command {
                PaintCommand::BeginOpacity => {
                    if active_depth >= maximum_depth {
                        return Err(EnginePngError::Contract(
                            "Opacity command depth exceeded prepared scratch",
                        ));
                    }
                    let layer_start = active_depth
                        .checked_mul(row_bytes)
                        .ok_or(EnginePngError::RasterAllocation)?;
                    let layer = layer_pixels
                        .get_mut(layer_start..layer_start + row_bytes)
                        .ok_or(EnginePngError::RasterAllocation)?;
                    if let Some((left, right)) = layer_dirty[active_depth].take() {
                        let clear_start = usize::try_from(left)
                            .ok()
                            .and_then(|value| value.checked_mul(4))
                            .ok_or(EnginePngError::RasterAllocation)?;
                        let clear_end = usize::try_from(right)
                            .ok()
                            .and_then(|value| value.checked_mul(4))
                            .ok_or(EnginePngError::RasterAllocation)?;
                        layer
                            .get_mut(clear_start..clear_end)
                            .ok_or(EnginePngError::RasterAllocation)?
                            .fill(0);
                    }
                    active_depth += 1;
                }
                PaintCommand::Paint(paint) => {
                    if active_depth == 0 {
                        paint_command_row(surface_row, surface_width, row_index, paint)?;
                    } else {
                        let layer_index = active_depth - 1;
                        let layer_start = layer_index
                            .checked_mul(row_bytes)
                            .ok_or(EnginePngError::RasterAllocation)?;
                        let layer = layer_pixels
                            .get_mut(layer_start..layer_start + row_bytes)
                            .ok_or(EnginePngError::RasterAllocation)?;
                        if let Some(range) =
                            paint_command_row(layer, surface_width, row_index, paint)?
                        {
                            layer_dirty[layer_index] =
                                union_dirty_range(layer_dirty[layer_index], range);
                        }
                    }
                }
                PaintCommand::EndOpacity(opacity) => {
                    active_depth = active_depth.checked_sub(1).ok_or(EnginePngError::Contract(
                        "Opacity command stack underflowed",
                    ))?;
                    let source_index = active_depth;
                    let Some(range) = layer_dirty[source_index] else {
                        continue;
                    };
                    let source_start = source_index
                        .checked_mul(row_bytes)
                        .ok_or(EnginePngError::RasterAllocation)?;
                    if active_depth == 0 {
                        let source = layer_pixels
                            .get(source_start..source_start + row_bytes)
                            .ok_or(EnginePngError::RasterAllocation)?;
                        composite_opacity_row(surface_row, source, range, opacity)?;
                    } else {
                        let destination_index = active_depth - 1;
                        let destination_start = destination_index
                            .checked_mul(row_bytes)
                            .ok_or(EnginePngError::RasterAllocation)?;
                        let (destination_layers, source_layers) =
                            layer_pixels.split_at_mut(source_start);
                        let destination = destination_layers
                            .get_mut(destination_start..destination_start + row_bytes)
                            .ok_or(EnginePngError::RasterAllocation)?;
                        let source = source_layers
                            .get(..row_bytes)
                            .ok_or(EnginePngError::RasterAllocation)?;
                        composite_opacity_row(destination, source, range, opacity)?;
                        layer_dirty[destination_index] =
                            union_dirty_range(layer_dirty[destination_index], range);
                    }
                }
            }
        }
        if active_depth != 0 {
            return Err(EnginePngError::Contract(
                "Opacity command stack remained open",
            ));
        }
    }
    Ok(())
}

fn maximum_opacity_depth(commands: &[PaintCommand<'_>]) -> Result<usize, EnginePngError> {
    let mut depth = 0_usize;
    let mut maximum = 0_usize;
    for command in commands {
        match command {
            PaintCommand::BeginOpacity => {
                depth = depth
                    .checked_add(1)
                    .ok_or(EnginePngError::RasterAllocation)?;
                maximum = maximum.max(depth);
            }
            PaintCommand::EndOpacity(_) => {
                depth = depth.checked_sub(1).ok_or(EnginePngError::Contract(
                    "Opacity command stack underflowed",
                ))?;
            }
            PaintCommand::Paint(_) => {}
        }
    }
    if depth != 0 {
        return Err(EnginePngError::Contract(
            "Opacity command stack remained open",
        ));
    }
    Ok(maximum)
}

fn union_dirty_range(current: Option<(u32, u32)>, added: (u32, u32)) -> Option<(u32, u32)> {
    if added.0 >= added.1 {
        return current;
    }
    Some(match current {
        Some((left, right)) => (left.min(added.0), right.max(added.1)),
        None => added,
    })
}

fn paint_command_row(
    row: &mut [u8],
    surface_width: u32,
    row_index: u32,
    paint: PixelPaint<'_>,
) -> Result<Option<(u32, u32)>, EnginePngError> {
    match paint {
        PixelPaint::Rect(rect) => paint_rect_row(row, row_index, rect),
        PixelPaint::Image(image) => paint_image_row(row, surface_width, row_index, image),
    }
}

fn paint_rect_row(
    row: &mut [u8],
    row_index: u32,
    rect: PixelRect,
) -> Result<Option<(u32, u32)>, EnginePngError> {
    if row_index < rect.top || row_index >= rect.bottom || rect.left >= rect.right {
        return Ok(None);
    }
    let start = usize::try_from(rect.left)
        .ok()
        .and_then(|value| value.checked_mul(4))
        .ok_or(EnginePngError::RasterAllocation)?;
    let end = usize::try_from(rect.right)
        .ok()
        .and_then(|value| value.checked_mul(4))
        .ok_or(EnginePngError::RasterAllocation)?;
    for target in row
        .get_mut(start..end)
        .ok_or(EnginePngError::RasterAllocation)?
        .chunks_exact_mut(4)
    {
        source_over_straight_rgba8(target, &rect.color)?;
    }
    Ok(Some((rect.left, rect.right)))
}

fn paint_image_row(
    row: &mut [u8],
    surface_width: u32,
    destination_y: u32,
    image: PixelImage<'_>,
) -> Result<Option<(u32, u32)>, EnginePngError> {
    if destination_y < image.top || destination_y >= image.bottom || image.left >= image.right {
        return Ok(None);
    }
    let expected_row_bytes = usize::try_from(surface_width)
        .ok()
        .and_then(|width| width.checked_mul(4))
        .ok_or(EnginePngError::RasterAllocation)?;
    if row.len() != expected_row_bytes {
        return Err(EnginePngError::Contract(
            "Raster row length diverged from surface width",
        ));
    }
    let source_y = image
        .source_top
        .checked_add(destination_y - image.top)
        .filter(|source_row| *source_row < image.source_height)
        .ok_or(EnginePngError::Contract(
            "Image source row exceeds prepared pixels",
        ))?;
    for destination_x in image.left..image.right {
        let source_x = image
            .source_left
            .checked_add(destination_x - image.left)
            .filter(|column| *column < image.source_width)
            .ok_or(EnginePngError::Contract(
                "Image source column exceeds prepared pixels",
            ))?;
        let source_offset = (u64::from(source_y) * u64::from(image.source_width)
            + u64::from(source_x))
        .checked_mul(4)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or(EnginePngError::RasterAllocation)?;
        let source = image
            .straight_rgba8
            .get(source_offset..source_offset + 4)
            .ok_or(EnginePngError::Contract(
                "Image source pixel exceeds prepared pixels",
            ))?;
        let destination_offset = usize::try_from(destination_x)
            .ok()
            .and_then(|value| value.checked_mul(4))
            .ok_or(EnginePngError::RasterAllocation)?;
        let destination = row
            .get_mut(destination_offset..destination_offset + 4)
            .ok_or(EnginePngError::RasterAllocation)?;
        source_over_straight_rgba8(destination, source)?;
    }
    Ok(Some((image.left, image.right)))
}

fn composite_opacity_row(
    destination: &mut [u8],
    source: &[u8],
    range: (u32, u32),
    opacity: u8,
) -> Result<(), EnginePngError> {
    let start = usize::try_from(range.0)
        .ok()
        .and_then(|value| value.checked_mul(4))
        .ok_or(EnginePngError::RasterAllocation)?;
    let end = usize::try_from(range.1)
        .ok()
        .and_then(|value| value.checked_mul(4))
        .ok_or(EnginePngError::RasterAllocation)?;
    let source = source
        .get(start..end)
        .ok_or(EnginePngError::RasterAllocation)?;
    let destination = destination
        .get_mut(start..end)
        .ok_or(EnginePngError::RasterAllocation)?;
    for (source_pixel, destination_pixel) in
        source.chunks_exact(4).zip(destination.chunks_exact_mut(4))
    {
        let source = [
            multiply_divide_255_round_half_up(source_pixel[0], opacity),
            multiply_divide_255_round_half_up(source_pixel[1], opacity),
            multiply_divide_255_round_half_up(source_pixel[2], opacity),
            multiply_divide_255_round_half_up(source_pixel[3], opacity),
        ];
        source_over_premultiplied_rgba8(destination_pixel, &source)?;
    }
    Ok(())
}

fn multiply_divide_255_round_half_up(value: u8, alpha: u8) -> u8 {
    ((u16::from(value) * u16::from(alpha) + 127) / 255) as u8
}

fn premultiply_straight_rgba8(straight: [u8; 4]) -> [u8; 4] {
    let alpha = straight[3];
    if alpha == 0 {
        return [0, 0, 0, 0];
    }
    [
        multiply_divide_255_round_half_up(straight[0], alpha),
        multiply_divide_255_round_half_up(straight[1], alpha),
        multiply_divide_255_round_half_up(straight[2], alpha),
        alpha,
    ]
}

fn source_over_straight_rgba8(
    destination_premultiplied: &mut [u8],
    source_straight: &[u8],
) -> Result<(), EnginePngError> {
    let source = premultiply_straight_rgba8(
        source_straight
            .try_into()
            .map_err(|_| EnginePngError::Contract("Paint source pixel is not RGBA8"))?,
    );
    source_over_premultiplied_rgba8(destination_premultiplied, &source)
}

fn source_over_premultiplied_rgba8(
    destination_premultiplied: &mut [u8],
    source_premultiplied: &[u8],
) -> Result<(), EnginePngError> {
    let source: &[u8; 4] = source_premultiplied
        .try_into()
        .map_err(|_| EnginePngError::Contract("Paint source pixel is not RGBA8"))?;
    let destination: &mut [u8; 4] = destination_premultiplied
        .try_into()
        .map_err(|_| EnginePngError::Contract("Raster destination pixel is not RGBA8"))?;
    let inverse_source_alpha = 255 - source[3];
    for channel in 0..4 {
        let value = u16::from(source[channel])
            + u16::from(multiply_divide_255_round_half_up(
                destination[channel],
                inverse_source_alpha,
            ));
        destination[channel] = value.min(255) as u8;
    }
    Ok(())
}

fn unpremultiply_rgba8_surface(pixels: &mut [u8]) -> Result<(), EnginePngError> {
    if pixels.len() % 4 != 0 {
        return Err(EnginePngError::Contract(
            "Raster surface length is not RGBA8 aligned",
        ));
    }
    for pixel in pixels.chunks_exact_mut(4) {
        let alpha = pixel[3];
        if alpha == 0 {
            pixel.copy_from_slice(&[0, 0, 0, 0]);
            continue;
        }
        for channel in &mut pixel[..3] {
            let straight = (u32::from(*channel) * 255 + u32::from(alpha / 2)) / u32::from(alpha);
            *channel = straight.min(255) as u8;
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

#[cfg(test)]
mod tests {
    use super::{NodeDrawState, node_draw_state};
    use serde_json::Value;

    #[test]
    fn authored_partial_opacity_keeps_isolation_at_u8_quantization_edges() {
        assert_eq!(
            NodeDrawState::PartialOpacity(0),
            draw_state(r#"{"visible":true,"opacity":0.001}"#)
        );
        assert_eq!(
            NodeDrawState::PartialOpacity(255),
            draw_state(r#"{"visible":true,"opacity":0.999}"#)
        );
        assert_eq!(
            NodeDrawState::Suppressed,
            draw_state(r#"{"visible":true,"opacity":0}"#)
        );
        assert_eq!(
            NodeDrawState::FullOpacity,
            draw_state(r#"{"visible":true,"opacity":1}"#)
        );
    }

    fn draw_state(json: &str) -> NodeDrawState {
        let node: Value = serde_json::from_str(json).expect("test node must be valid JSON");
        node_draw_state(node.as_object().expect("test node must be an object"), true)
            .expect("test node opacity must lower")
    }
}
