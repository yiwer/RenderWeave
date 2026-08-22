//! Specialized admission for the immutable `renderweave-render/1.0` handoff.
//!
//! This crate is independent of layout, shaping, resource fetching, decoding, rasterization,
//! and encoding. It consumes the same machine-readable node catalog as the Java sealer.

use serde::de::{Error as DeError, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use serde_json::{Map, Number, Value};
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::fmt::{Display, Formatter};

const CATALOG_JSON: &str = include_str!(
    "../../../../renderweave-rendering/src/main/resources/cn/hbads/renderweave/rendering/render-node-contract-v1.json"
);
const CATALOG_VERSION: &str = "renderweave-render-node-contract-v1/2";
const ASSET_ACCEPTANCE_PROFILE: &str = "renderweave-asset-acceptance/1.0";
const MAX_RENDER_RESOURCE_ENTRIES: usize = 2_048;
const MAX_UNIQUE_EXACT_CONTENTS: usize = 128;
const MAX_OCCURRENCE_RAW_BYTES: u64 = 2_147_483_648;
const MAX_OCCURRENCE_IMAGE_PIXELS: u64 = 1_000_000_000;
const MAX_OCCURRENCE_FONT_BYTES: u64 = 536_870_912;
const MAX_UNIQUE_RAW_BYTES: u64 = 268_435_456;
const MAX_UNIQUE_IMAGE_PIXELS: u64 = 125_000_000;
const MAX_UNIQUE_FONT_BYTES: u64 = 67_108_864;
const MAX_RESOURCE_MANIFEST_BYTES: usize = 4_194_304;
const MAX_FETCH_URL_UTF8_BYTES_PER_ENTRY: usize = 2_048;
const MAX_FETCH_URL_UTF8_BYTES_TOTAL: u64 = 4_194_304;
const MAX_IMAGE_BYTES_PER_CONTENT: u64 = 67_108_864;
const MAX_IMAGE_EDGE_PIXELS_PER_CONTENT: u64 = 20_000;
const MAX_IMAGE_PIXELS_PER_CONTENT: u64 = 100_000_000;
const MAX_FONT_BYTES_PER_CONTENT: u64 = 33_554_432;
pub const RESOURCE_LEASE_SAFETY_MARGIN_MILLIS: i64 = 5_000;

#[derive(Debug)]
pub enum DocumentError {
    Invalid(&'static str),
    InvalidOwned(String),
}

impl Display for DocumentError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Invalid(message) => formatter.write_str(message),
            Self::InvalidOwned(message) => formatter.write_str(message),
        }
    }
}

impl std::error::Error for DocumentError {}

impl From<serde_json::Error> for DocumentError {
    fn from(error: serde_json::Error) -> Self {
        Self::InvalidOwned(format!("strict RenderDocument JSON rejected: {error}"))
    }
}

pub struct AdmittedRenderDocument {
    canonical_document: Box<str>,
    occurrence_count: usize,
    resources: Vec<AdmittedRenderResource>,
    static_kinds: BTreeSet<String>,
}

impl std::fmt::Debug for AdmittedRenderDocument {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("AdmittedRenderDocument")
            .field("occurrence_count", &self.occurrence_count)
            .field("resource_count", &self.resources.len())
            .field("static_kinds", &self.static_kinds)
            .finish_non_exhaustive()
    }
}

impl AdmittedRenderDocument {
    pub fn canonical_document(&self) -> &str {
        &self.canonical_document
    }

    pub fn occurrence_count(&self) -> usize {
        self.occurrence_count
    }

    pub fn resource_count(&self) -> usize {
        self.resources.len()
    }

    pub fn resources(&self) -> &[AdmittedRenderResource] {
        &self.resources
    }

    pub fn static_kinds(&self) -> &BTreeSet<String> {
        &self.static_kinds
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub enum RenderResourceKind {
    Image,
    Font,
}

impl RenderResourceKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Image => "image",
            Self::Font => "font",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub enum RenderResourceMediaType {
    ImagePng,
    ImageJpeg,
    ImageWebp,
    FontTtf,
    FontOtf,
}

impl RenderResourceMediaType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ImagePng => "image/png",
            Self::ImageJpeg => "image/jpeg",
            Self::ImageWebp => "image/webp",
            Self::FontTtf => "font/ttf",
            Self::FontOtf => "font/otf",
        }
    }

    fn kind(self) -> RenderResourceKind {
        match self {
            Self::ImagePng | Self::ImageJpeg | Self::ImageWebp => RenderResourceKind::Image,
            Self::FontTtf | Self::FontOtf => RenderResourceKind::Font,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ImageOrientation {
    Identity,
    MirrorHorizontal,
    Rotate180,
    MirrorVertical,
    Transpose,
    Rotate90Clockwise,
    Transverse,
    Rotate270Clockwise,
}

impl ImageOrientation {
    fn swaps_dimensions(self) -> bool {
        matches!(
            self,
            Self::Transpose | Self::Rotate90Clockwise | Self::Transverse | Self::Rotate270Clockwise
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FontFlavor {
    TrueTypeGlyf,
    Cff,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum AdmittedTechnicalDescriptor {
    Image {
        encoded_width_px: u32,
        encoded_height_px: u32,
        orientation: ImageOrientation,
        logical_width_px: u32,
        logical_height_px: u32,
    },
    Font {
        flavor: FontFlavor,
        units_per_em: u16,
    },
}

impl AdmittedTechnicalDescriptor {
    pub fn image_dimensions(&self) -> Option<(u32, u32, ImageOrientation, u32, u32)> {
        match self {
            Self::Image {
                encoded_width_px,
                encoded_height_px,
                orientation,
                logical_width_px,
                logical_height_px,
            } => Some((
                *encoded_width_px,
                *encoded_height_px,
                *orientation,
                *logical_width_px,
                *logical_height_px,
            )),
            Self::Font { .. } => None,
        }
    }

    pub fn font_metrics(&self) -> Option<(FontFlavor, u16)> {
        match self {
            Self::Font {
                flavor,
                units_per_em,
            } => Some((*flavor, *units_per_em)),
            Self::Image { .. } => None,
        }
    }

    fn image_pixels(&self) -> u64 {
        match self {
            Self::Image {
                logical_width_px,
                logical_height_px,
                ..
            } => u64::from(*logical_width_px) * u64::from(*logical_height_px),
            Self::Font { .. } => 0,
        }
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct AdmittedRenderResource {
    resource_id: Box<str>,
    kind: RenderResourceKind,
    fetch_url: Box<str>,
    expires_at_epoch_second: u64,
    sha256: Box<str>,
    media_type: RenderResourceMediaType,
    byte_length: u64,
    technical_descriptor: AdmittedTechnicalDescriptor,
}

impl std::fmt::Debug for AdmittedRenderResource {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("AdmittedRenderResource")
            .field("resource_id", &self.resource_id)
            .field("kind", &self.kind)
            .field("media_type", &self.media_type)
            .field("byte_length", &self.byte_length)
            .finish_non_exhaustive()
    }
}

impl AdmittedRenderResource {
    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn kind(&self) -> RenderResourceKind {
        self.kind
    }

    pub fn fetch_url(&self) -> &str {
        &self.fetch_url
    }

    pub fn expires_at_epoch_second(&self) -> u64 {
        self.expires_at_epoch_second
    }

    pub fn sha256(&self) -> &str {
        &self.sha256
    }

    pub fn media_type(&self) -> RenderResourceMediaType {
        self.media_type
    }

    pub fn byte_length(&self) -> u64 {
        self.byte_length
    }

    pub fn acceptance_profile_id(&self) -> &'static str {
        ASSET_ACCEPTANCE_PROFILE
    }

    pub fn technical_descriptor(&self) -> &AdmittedTechnicalDescriptor {
        &self.technical_descriptor
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ResourceLeaseViolation<'a> {
    resource_id: &'a str,
}

impl<'a> ResourceLeaseViolation<'a> {
    pub fn resource_id(self) -> &'a str {
        self.resource_id
    }
}

/// Proves that every request-local resource lease covers the absolute Command deadline plus the
/// frozen Renderer safety margin. The comparison uses `i128` so every admitted `u64` epoch-second
/// value remains exact and cannot wrap while being converted to milliseconds.
pub fn validate_resource_lease_coverage<'a>(
    document: &'a AdmittedRenderDocument,
    deadline_epoch_millis: i64,
) -> Result<(), ResourceLeaseViolation<'a>> {
    let required_expiry_millis =
        i128::from(deadline_epoch_millis) + i128::from(RESOURCE_LEASE_SAFETY_MARGIN_MILLIS);
    for resource in document.resources() {
        let expires_at_epoch_millis = i128::from(resource.expires_at_epoch_second()) * 1_000;
        if expires_at_epoch_millis < required_expiry_millis {
            return Err(ResourceLeaseViolation {
                resource_id: resource.resource_id(),
            });
        }
    }
    Ok(())
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Catalog {
    catalog_version: String,
    render_dsl_version: String,
    layout_profile: String,
    render_document_contract: MemberContract,
    source_canvas_contract: MemberContract,
    render_placement_contracts: BTreeMap<String, MemberContract>,
    render_resource_contract: MemberContract,
    dynamic_residue_members: Vec<String>,
    common_node_defaults: Map<String, Value>,
    object_defaults: BTreeMap<String, Map<String, Value>>,
    placement_defaults: BTreeMap<String, Map<String, Value>>,
    resource_lowering: BTreeMap<String, String>,
    kinds: BTreeMap<String, KindContract>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MemberContract {
    members: Vec<String>,
    required_members: Vec<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct KindContract {
    container: bool,
    #[serde(default)]
    root_only: bool,
    #[serde(default)]
    lowering_only: bool,
    properties: Vec<String>,
    required_properties: Vec<String>,
    defaults: Map<String, Value>,
    default_objects: Vec<String>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ResourceDemand {
    resource_id: String,
    kind: RenderResourceKind,
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct ExactContentKey {
    kind: RenderResourceKind,
    sha256: Box<str>,
    byte_length: u64,
    media_type: RenderResourceMediaType,
}

struct Admission<'a> {
    catalog: &'a Catalog,
    next_occurrence: usize,
    demands: Vec<ResourceDemand>,
    static_kinds: BTreeSet<String>,
}

pub fn validate_render_document(
    canonical_document: &str,
) -> Result<AdmittedRenderDocument, DocumentError> {
    let strict: StrictValue = serde_json::from_str(canonical_document)?;
    let canonical = serde_json::to_string(&strict.0)?;
    if canonical != canonical_document {
        return Err(DocumentError::Invalid(
            "RenderDocument bytes are not canonical",
        ));
    }
    let document = strict
        .0
        .as_object()
        .ok_or(DocumentError::Invalid("RenderDocument must be one object"))?;
    let catalog = load_catalog()?;
    exact_members(
        document,
        &catalog.render_document_contract,
        "RenderDocument",
    )?;
    require_text(document, "dslVersion", &catalog.render_dsl_version)?;
    require_text(document, "layoutProfile", &catalog.layout_profile)?;

    let canvas = require_object(document, "canvas")?;
    let mut admission = Admission {
        catalog: &catalog,
        next_occurrence: 0,
        demands: Vec::new(),
        static_kinds: BTreeSet::new(),
    };
    admission.validate_node(canvas, true, None, None)?;
    let resources = document
        .get("resources")
        .and_then(Value::as_array)
        .ok_or(DocumentError::Invalid("resources must be an array"))?;
    let resources = admission.validate_resources(resources)?;
    Ok(AdmittedRenderDocument {
        canonical_document: canonical.into_boxed_str(),
        occurrence_count: admission.next_occurrence,
        resources,
        static_kinds: admission.static_kinds,
    })
}

fn load_catalog() -> Result<Catalog, DocumentError> {
    let catalog: Catalog = serde_json::from_str(CATALOG_JSON)
        .map_err(|error| DocumentError::InvalidOwned(format!("node catalog rejected: {error}")))?;
    if catalog.catalog_version != CATALOG_VERSION
        || catalog.render_dsl_version != "renderweave-render/1.0"
        || catalog.layout_profile != "renderweave-layout/1.0"
        || catalog.kinds.len() != 16
        || !catalog
            .kinds
            .get("canvas")
            .is_some_and(|kind| kind.root_only)
        || !catalog
            .kinds
            .get("compositionViewport")
            .is_some_and(|kind| kind.lowering_only)
        || catalog.placement_defaults.keys().collect::<BTreeSet<_>>()
            != catalog
                .render_placement_contracts
                .keys()
                .collect::<BTreeSet<_>>()
    {
        return Err(DocumentError::Invalid(
            "RenderNodeContract identity drifted",
        ));
    }
    Ok(catalog)
}

impl Admission<'_> {
    fn validate_node(
        &mut self,
        node: &Map<String, Value>,
        root: bool,
        expected_placement: Option<&str>,
        parent_stack_direction: Option<&str>,
    ) -> Result<(), DocumentError> {
        let kind = node
            .get("kind")
            .and_then(Value::as_str)
            .ok_or(DocumentError::Invalid("RenderDSL node kind must be text"))?;
        let contract = self
            .catalog
            .kinds
            .get(kind)
            .ok_or(DocumentError::Invalid("unknown RenderDSL node kind"))?;
        self.static_kinds.insert(kind.to_owned());
        if root != (kind == "canvas") || (!root && contract.root_only) {
            return Err(DocumentError::Invalid("Canvas is root-only"));
        }
        self.validate_occurrence(node)?;
        exact_members(
            node,
            &self.node_member_contract(kind, contract),
            "RenderDSL node",
        )?;
        reject_dynamic_residue(node, &self.catalog.dynamic_residue_members)?;

        if root {
            if node.contains_key("placement") {
                return Err(DocumentError::Invalid("root Canvas cannot have placement"));
            }
        } else {
            self.validate_placement(
                require_object(node, "placement")?,
                expected_placement,
                parent_stack_direction,
            )?;
            self.validate_common_node_values(node)?;
        }
        self.validate_known_composites(node)?;
        self.collect_demands(node, kind)?;

        if matches!(kind, "rect" | "ellipse" | "polygon" | "path")
            && !node.contains_key("fill")
            && !node.contains_key("stroke")
        {
            return Err(DocumentError::Invalid(
                "shape must retain an explicit fill or stroke",
            ));
        }

        if kind == "compositionViewport" {
            if node.contains_key("children") {
                return Err(DocumentError::Invalid(
                    "compositionViewport uses sourceCanvas, not children",
                ));
            }
            self.validate_source_canvas(require_object(node, "sourceCanvas")?)?;
        } else if contract.container {
            let children =
                node.get("children")
                    .and_then(Value::as_array)
                    .ok_or(DocumentError::Invalid(
                        "container children must be an array",
                    ))?;
            let child_placement = match kind {
                "stack" => "STACK",
                "grid" => "GRID",
                _ => "ABSOLUTE",
            };
            let stack_direction = if kind == "stack" {
                node.get("direction").and_then(Value::as_str)
            } else {
                None
            };
            for child in children {
                self.validate_node(
                    child
                        .as_object()
                        .ok_or(DocumentError::Invalid("child must be an object"))?,
                    false,
                    Some(child_placement),
                    stack_direction,
                )?;
            }
        }
        Ok(())
    }

    fn node_member_contract(&self, kind: &str, contract: &KindContract) -> MemberContract {
        let mut allowed = BTreeSet::from(["kind".to_owned(), "occurrenceId".to_owned()]);
        let mut required = allowed.clone();
        for property in &contract.properties {
            if let Some(lowered) = self.lowered_property(property) {
                allowed.insert(lowered);
            }
        }
        for property in &contract.required_properties {
            if let Some(lowered) = self.lowered_property(property) {
                required.insert(lowered);
            }
        }
        if kind != "canvas" {
            for property in self.catalog.common_node_defaults.keys() {
                required.insert(lower_mm_name(property));
            }
        }
        for property in contract.defaults.keys() {
            required.insert(lower_mm_name(property));
        }
        for property in &contract.default_objects {
            required.insert(property.clone());
        }
        if contract.container && kind != "compositionViewport" {
            allowed.insert("children".to_owned());
            required.insert("children".to_owned());
        }
        MemberContract {
            members: allowed.into_iter().collect(),
            required_members: required.into_iter().collect(),
        }
    }

    fn lowered_property(&self, property: &str) -> Option<String> {
        if property == "render" {
            return None;
        }
        if let Some(lowered) = self.catalog.resource_lowering.get(property) {
            return Some(lowered.clone());
        }
        Some(lower_mm_name(property))
    }

    fn validate_source_canvas(&mut self, source: &Map<String, Value>) -> Result<(), DocumentError> {
        exact_members(source, &self.catalog.source_canvas_contract, "sourceCanvas")?;
        reject_dynamic_residue(source, &self.catalog.dynamic_residue_members)?;
        self.validate_occurrence(source)?;
        let children =
            source
                .get("children")
                .and_then(Value::as_array)
                .ok_or(DocumentError::Invalid(
                    "sourceCanvas children must be an array",
                ))?;
        for child in children {
            self.validate_node(
                child.as_object().ok_or(DocumentError::Invalid(
                    "sourceCanvas child must be an object",
                ))?,
                false,
                Some("ABSOLUTE"),
                None,
            )?;
        }
        Ok(())
    }

    fn validate_occurrence(&mut self, node: &Map<String, Value>) -> Result<(), DocumentError> {
        let actual = node
            .get("occurrenceId")
            .and_then(Value::as_str)
            .ok_or(DocumentError::Invalid("occurrenceId must be text"))?;
        let expected = format!("rwocc_{:016x}", self.next_occurrence);
        if actual != expected {
            return Err(DocumentError::Invalid(
                "occurrenceId preorder sequence mismatch",
            ));
        }
        self.next_occurrence = self
            .next_occurrence
            .checked_add(1)
            .ok_or(DocumentError::Invalid("occurrence count overflow"))?;
        Ok(())
    }

    fn validate_common_node_values(&self, node: &Map<String, Value>) -> Result<(), DocumentError> {
        if !node.get("visible").is_some_and(Value::is_boolean)
            || !node.get("opacity").is_some_and(Value::is_number)
        {
            return Err(DocumentError::Invalid(
                "visible and opacity must be concrete",
            ));
        }
        let transform = require_object(node, "transform")?;
        exact_named_members(
            transform,
            &["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
            &["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
            "transform",
        )?;
        require_numbers(
            transform,
            &["originX", "originY", "rotationDeg", "scaleX", "scaleY"],
        )
    }

    fn validate_placement(
        &self,
        placement: &Map<String, Value>,
        expected: Option<&str>,
        parent_stack_direction: Option<&str>,
    ) -> Result<(), DocumentError> {
        let placement_type = placement
            .get("type")
            .and_then(Value::as_str)
            .ok_or(DocumentError::Invalid("placement type must be text"))?;
        if placement_type == "PACK" || Some(placement_type) != expected {
            return Err(DocumentError::Invalid("placement variant mismatch"));
        }
        let contract = self
            .catalog
            .render_placement_contracts
            .get(placement_type)
            .ok_or(DocumentError::Invalid("unknown placement variant"))?;
        exact_members(placement, contract, "placement")?;
        let width_mode = require_enum(placement, "widthMode", &["FIXED", "HUG_CONTENT", "FILL"])?;
        let height_mode = require_enum(placement, "heightMode", &["FIXED", "HUG_CONTENT", "FILL"])?;
        validate_axis_size(placement, width_mode, "widthPt")?;
        validate_axis_size(placement, height_mode, "heightPt")?;
        if placement_type == "ABSOLUTE" {
            require_numbers(placement, &["xPt", "yPt"])?;
            validate_fill_inset(placement, width_mode, "rightInsetPt")?;
            validate_fill_inset(placement, height_mode, "bottomInsetPt")?;
        } else if placement_type == "STACK" {
            require_numbers(
                placement,
                &[
                    "marginTopPt",
                    "marginRightPt",
                    "marginBottomPt",
                    "marginLeftPt",
                ],
            )?;
            let main_fill = match parent_stack_direction {
                Some("ROW") => width_mode == "FILL",
                Some("COLUMN") => height_mode == "FILL",
                _ => return Err(DocumentError::Invalid("Stack direction is invalid")),
            };
            if main_fill != placement.contains_key("fillWeight")
                || (main_fill && !placement.get("fillWeight").is_some_and(Value::is_number))
            {
                return Err(DocumentError::Invalid(
                    "Stack main-axis fillWeight presence mismatch",
                ));
            }
        } else {
            require_nonnegative_integers(placement, &["row", "column"])?;
            require_positive_integers(placement, &["rowSpan", "columnSpan"])?;
            require_numbers(
                placement,
                &[
                    "marginTopPt",
                    "marginRightPt",
                    "marginBottomPt",
                    "marginLeftPt",
                ],
            )?;
        }
        Ok(())
    }

    fn validate_known_composites(&self, node: &Map<String, Value>) -> Result<(), DocumentError> {
        for (name, defaults) in &self.catalog.object_defaults {
            if let Some(value) = node.get(name) {
                let object = value
                    .as_object()
                    .ok_or(DocumentError::Invalid("default object must be an object"))?;
                let members = defaults
                    .keys()
                    .map(|member| lower_mm_name(member))
                    .collect::<Vec<_>>();
                let member_refs = members.iter().map(String::as_str).collect::<Vec<_>>();
                exact_named_members(object, &member_refs, &member_refs, name)?;
                require_numbers(object, &member_refs)?;
            }
        }
        if let Some(fill) = node.get("fill") {
            exact_named_members(
                fill.as_object()
                    .ok_or(DocumentError::Invalid("fill must be an object"))?,
                &["color"],
                &["color"],
                "fill",
            )?;
        }
        if let Some(stroke) = node.get("stroke") {
            let stroke = stroke
                .as_object()
                .ok_or(DocumentError::Invalid("stroke must be an object"))?;
            exact_named_members(
                stroke,
                &["cap", "color", "join", "widthPt"],
                &["cap", "color", "join", "widthPt"],
                "stroke",
            )?;
            require_numbers(stroke, &["widthPt"])?;
        }
        for name in ["start", "end"] {
            if let Some(point) = node.get(name) {
                validate_point(point, name)?;
            }
        }
        if let Some(points) = node.get("points") {
            for point in points
                .as_array()
                .ok_or(DocumentError::Invalid("points must be an array"))?
            {
                validate_point(point, "point")?;
            }
        }
        if let Some(tracks) = node.get("rows") {
            validate_tracks(tracks)?;
        }
        if let Some(tracks) = node.get("columns") {
            validate_tracks(tracks)?;
        }
        if let Some(line_height) = node.get("lineHeight") {
            validate_line_height(line_height)?;
        }
        if let Some(runs) = node.get("runs") {
            validate_runs(runs)?;
        }
        if let Some(commands) = node.get("commands") {
            validate_commands(commands)?;
        }
        Ok(())
    }

    fn collect_demands(
        &mut self,
        node: &Map<String, Value>,
        kind: &str,
    ) -> Result<(), DocumentError> {
        if kind == "image" {
            let resource_id = node
                .get("imageResourceId")
                .and_then(Value::as_str)
                .ok_or(DocumentError::Invalid("Image requires imageResourceId"))?;
            validate_resource_id(resource_id)?;
            self.demands.push(ResourceDemand {
                resource_id: resource_id.to_owned(),
                kind: RenderResourceKind::Image,
            });
        }
        if kind == "text" {
            let runs = node
                .get("runs")
                .and_then(Value::as_array)
                .ok_or(DocumentError::Invalid("Text requires runs"))?;
            for run in runs {
                let resource_id = run
                    .as_object()
                    .and_then(|value| value.get("fontResourceId"))
                    .and_then(Value::as_str)
                    .ok_or(DocumentError::Invalid("Text run requires fontResourceId"))?;
                validate_resource_id(resource_id)?;
                self.demands.push(ResourceDemand {
                    resource_id: resource_id.to_owned(),
                    kind: RenderResourceKind::Font,
                });
            }
        }
        Ok(())
    }

    fn validate_resources(
        &self,
        resources: &[Value],
    ) -> Result<Vec<AdmittedRenderResource>, DocumentError> {
        if resources.len() != self.demands.len() {
            return Err(DocumentError::Invalid(
                "resource manifest/reference cardinality mismatch",
            ));
        }
        if resources.len() > MAX_RENDER_RESOURCE_ENTRIES {
            return Err(DocumentError::Invalid("resource entry budget exceeded"));
        }
        if serde_json::to_vec(resources)?.len() > MAX_RESOURCE_MANIFEST_BYTES {
            return Err(DocumentError::Invalid(
                "resource manifest byte budget exceeded",
            ));
        }
        let mut seen = HashSet::new();
        let mut exact_contents = BTreeMap::new();
        let mut admitted = Vec::with_capacity(resources.len());
        let mut occurrence_raw_bytes = 0_u64;
        let mut occurrence_image_pixels = 0_u64;
        let mut occurrence_font_bytes = 0_u64;
        let mut unique_raw_bytes = 0_u64;
        let mut unique_image_pixels = 0_u64;
        let mut unique_font_bytes = 0_u64;
        let mut fetch_url_bytes = 0_u64;
        for (resource, demand) in resources.iter().zip(&self.demands) {
            let resource = resource
                .as_object()
                .ok_or(DocumentError::Invalid("resource must be an object"))?;
            exact_members(resource, &self.catalog.render_resource_contract, "resource")?;
            let resource_id = resource
                .get("resourceId")
                .and_then(Value::as_str)
                .ok_or(DocumentError::Invalid("resourceId must be text"))?;
            validate_resource_id(resource_id)?;
            if resource_id != demand.resource_id
                || resource.get("kind").and_then(Value::as_str) != Some(demand.kind.as_str())
                || !seen.insert(resource_id)
            {
                return Err(DocumentError::Invalid(
                    "resource manifest order, kind, or uniqueness mismatch",
                ));
            }
            let fetch_url = resource
                .get("fetchUrl")
                .and_then(Value::as_str)
                .ok_or(DocumentError::Invalid("fetchUrl must be text"))?;
            if fetch_url.is_empty() || fetch_url.len() > MAX_FETCH_URL_UTF8_BYTES_PER_ENTRY {
                return Err(DocumentError::Invalid("fetchUrl byte budget exceeded"));
            }
            fetch_url_bytes = checked_budget_add(
                fetch_url_bytes,
                fetch_url.len() as u64,
                MAX_FETCH_URL_UTF8_BYTES_TOTAL,
                "fetchUrl total byte budget exceeded",
            )?;
            let expires_at_epoch_second = positive_integer(resource, "expiresAt")?;
            let sha256 = resource
                .get("sha256")
                .and_then(Value::as_str)
                .ok_or(DocumentError::Invalid("resource sha256 must be text"))?;
            validate_sha256(sha256)?;
            require_text(resource, "acceptanceProfileId", ASSET_ACCEPTANCE_PROFILE)?;
            let media_type = parse_media_type(resource, demand.kind)?;
            let byte_length = positive_integer(resource, "byteLength")?;
            let per_content_byte_limit = match demand.kind {
                RenderResourceKind::Image => MAX_IMAGE_BYTES_PER_CONTENT,
                RenderResourceKind::Font => MAX_FONT_BYTES_PER_CONTENT,
            };
            if byte_length > per_content_byte_limit {
                return Err(DocumentError::Invalid(
                    "resource per-content byte budget exceeded",
                ));
            }
            let technical_descriptor = validate_technical_descriptor(resource, demand.kind)?;
            let image_pixels = technical_descriptor.image_pixels();

            occurrence_raw_bytes = checked_budget_add(
                occurrence_raw_bytes,
                byte_length,
                MAX_OCCURRENCE_RAW_BYTES,
                "resource occurrence raw-byte budget exceeded",
            )?;
            match demand.kind {
                RenderResourceKind::Image => {
                    occurrence_image_pixels = checked_budget_add(
                        occurrence_image_pixels,
                        image_pixels,
                        MAX_OCCURRENCE_IMAGE_PIXELS,
                        "resource occurrence image-pixel budget exceeded",
                    )?;
                }
                RenderResourceKind::Font => {
                    occurrence_font_bytes = checked_budget_add(
                        occurrence_font_bytes,
                        byte_length,
                        MAX_OCCURRENCE_FONT_BYTES,
                        "resource occurrence font-byte budget exceeded",
                    )?;
                }
            }

            let exact_content_key = ExactContentKey {
                kind: demand.kind,
                sha256: sha256.into(),
                byte_length,
                media_type,
            };
            if let Some(existing) = exact_contents.get(&exact_content_key) {
                if existing != &technical_descriptor {
                    return Err(DocumentError::Invalid(
                        "same exact content has inconsistent technical descriptor",
                    ));
                }
            } else {
                if exact_contents.len() >= MAX_UNIQUE_EXACT_CONTENTS {
                    return Err(DocumentError::Invalid(
                        "unique exact-content budget exceeded",
                    ));
                }
                unique_raw_bytes = checked_budget_add(
                    unique_raw_bytes,
                    byte_length,
                    MAX_UNIQUE_RAW_BYTES,
                    "unique resource raw-byte budget exceeded",
                )?;
                match demand.kind {
                    RenderResourceKind::Image => {
                        unique_image_pixels = checked_budget_add(
                            unique_image_pixels,
                            image_pixels,
                            MAX_UNIQUE_IMAGE_PIXELS,
                            "unique resource image-pixel budget exceeded",
                        )?;
                    }
                    RenderResourceKind::Font => {
                        unique_font_bytes = checked_budget_add(
                            unique_font_bytes,
                            byte_length,
                            MAX_UNIQUE_FONT_BYTES,
                            "unique resource font-byte budget exceeded",
                        )?;
                    }
                }
                exact_contents.insert(exact_content_key, technical_descriptor.clone());
            }

            admitted.push(AdmittedRenderResource {
                resource_id: resource_id.into(),
                kind: demand.kind,
                fetch_url: fetch_url.into(),
                expires_at_epoch_second,
                sha256: sha256.into(),
                media_type,
                byte_length,
                technical_descriptor,
            });
        }
        Ok(admitted)
    }
}

fn exact_members(
    object: &Map<String, Value>,
    contract: &MemberContract,
    label: &str,
) -> Result<(), DocumentError> {
    exact_named_members(
        object,
        &contract
            .members
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>(),
        &contract
            .required_members
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>(),
        label,
    )
}

fn exact_named_members(
    object: &Map<String, Value>,
    allowed: &[&str],
    required: &[&str],
    label: &str,
) -> Result<(), DocumentError> {
    let allowed = allowed.iter().copied().collect::<BTreeSet<_>>();
    if object.keys().any(|key| !allowed.contains(key.as_str()))
        || required.iter().any(|key| !object.contains_key(*key))
    {
        return Err(DocumentError::InvalidOwned(format!(
            "{label} member set mismatch"
        )));
    }
    Ok(())
}

fn require_object<'a>(
    object: &'a Map<String, Value>,
    member: &str,
) -> Result<&'a Map<String, Value>, DocumentError> {
    object
        .get(member)
        .and_then(Value::as_object)
        .ok_or(DocumentError::Invalid("required object member is absent"))
}

fn require_text(
    object: &Map<String, Value>,
    member: &str,
    expected: &str,
) -> Result<(), DocumentError> {
    if object.get(member).and_then(Value::as_str) != Some(expected) {
        return Err(DocumentError::Invalid("identity member mismatch"));
    }
    Ok(())
}

fn require_enum<'a>(
    object: &'a Map<String, Value>,
    member: &str,
    allowed: &[&str],
) -> Result<&'a str, DocumentError> {
    let value = object
        .get(member)
        .and_then(Value::as_str)
        .ok_or(DocumentError::Invalid("enum member must be text"))?;
    if !allowed.contains(&value) {
        return Err(DocumentError::Invalid("enum member is outside its catalog"));
    }
    Ok(value)
}

fn require_numbers(object: &Map<String, Value>, members: &[&str]) -> Result<(), DocumentError> {
    if members
        .iter()
        .any(|member| !object.get(*member).is_some_and(Value::is_number))
    {
        return Err(DocumentError::Invalid(
            "numeric member is absent or non-numeric",
        ));
    }
    Ok(())
}

fn require_nonnegative_integers(
    object: &Map<String, Value>,
    members: &[&str],
) -> Result<(), DocumentError> {
    if members
        .iter()
        .any(|member| object.get(*member).and_then(Value::as_u64).is_none())
    {
        return Err(DocumentError::Invalid(
            "nonnegative integer member is invalid",
        ));
    }
    Ok(())
}

fn require_positive_integers(
    object: &Map<String, Value>,
    members: &[&str],
) -> Result<(), DocumentError> {
    if members.iter().any(|member| {
        object
            .get(*member)
            .and_then(Value::as_u64)
            .is_none_or(|value| value == 0)
    }) {
        return Err(DocumentError::Invalid("positive integer member is invalid"));
    }
    Ok(())
}

fn validate_axis_size(
    placement: &Map<String, Value>,
    mode: &str,
    size: &str,
) -> Result<(), DocumentError> {
    if (mode == "FIXED") != placement.contains_key(size) {
        return Err(DocumentError::Invalid("fixed size presence mismatch"));
    }
    if placement.contains_key(size) && !placement.get(size).is_some_and(Value::is_number) {
        return Err(DocumentError::Invalid("fixed size must be numeric"));
    }
    Ok(())
}

fn validate_fill_inset(
    placement: &Map<String, Value>,
    mode: &str,
    inset: &str,
) -> Result<(), DocumentError> {
    if (mode == "FILL") != placement.contains_key(inset) {
        return Err(DocumentError::Invalid("FILL inset presence mismatch"));
    }
    if placement.contains_key(inset) && !placement.get(inset).is_some_and(Value::is_number) {
        return Err(DocumentError::Invalid("FILL inset must be numeric"));
    }
    Ok(())
}

fn validate_point(value: &Value, label: &str) -> Result<(), DocumentError> {
    let point = value
        .as_object()
        .ok_or(DocumentError::Invalid("point must be an object"))?;
    exact_named_members(point, &["xPt", "yPt"], &["xPt", "yPt"], label)?;
    require_numbers(point, &["xPt", "yPt"])
}

fn validate_tracks(value: &Value) -> Result<(), DocumentError> {
    let tracks = value
        .as_array()
        .ok_or(DocumentError::Invalid("tracks must be an array"))?;
    if tracks.is_empty() {
        return Err(DocumentError::Invalid("tracks cannot be empty"));
    }
    for track in tracks {
        let track = track
            .as_object()
            .ok_or(DocumentError::Invalid("track must be an object"))?;
        match track.get("type").and_then(Value::as_str) {
            Some("AUTO") => exact_named_members(track, &["type"], &["type"], "track")?,
            Some("FIXED") => {
                exact_named_members(track, &["type", "valuePt"], &["type", "valuePt"], "track")?;
                require_numbers(track, &["valuePt"])?;
            }
            Some("FRACTION") => {
                exact_named_members(track, &["type", "weight"], &["type", "weight"], "track")?;
                require_numbers(track, &["weight"])?;
            }
            _ => return Err(DocumentError::Invalid("unknown track type")),
        }
    }
    Ok(())
}

fn validate_line_height(value: &Value) -> Result<(), DocumentError> {
    let line_height = value
        .as_object()
        .ok_or(DocumentError::Invalid("lineHeight must be an object"))?;
    match line_height.get("type").and_then(Value::as_str) {
        Some("FACTOR") => {
            exact_named_members(
                line_height,
                &["factor", "type"],
                &["factor", "type"],
                "lineHeight",
            )?;
            require_numbers(line_height, &["factor"])
        }
        Some("FIXED") => {
            exact_named_members(
                line_height,
                &["type", "valuePt"],
                &["type", "valuePt"],
                "lineHeight",
            )?;
            require_numbers(line_height, &["valuePt"])
        }
        _ => Err(DocumentError::Invalid("unknown lineHeight type")),
    }
}

fn validate_runs(value: &Value) -> Result<(), DocumentError> {
    let runs = value
        .as_array()
        .ok_or(DocumentError::Invalid("runs must be an array"))?;
    if runs.is_empty() {
        return Err(DocumentError::Invalid("runs cannot be empty"));
    }
    for run in runs {
        let run = run
            .as_object()
            .ok_or(DocumentError::Invalid("run must be an object"))?;
        exact_named_members(
            run,
            &[
                "color",
                "decoration",
                "fontResourceId",
                "fontSizePt",
                "letterSpacingFactor",
                "letterSpacingPt",
                "text",
            ],
            &[
                "color",
                "decoration",
                "fontResourceId",
                "fontSizePt",
                "text",
            ],
            "run",
        )?;
        if run.contains_key("letterSpacingPt") == run.contains_key("letterSpacingFactor") {
            return Err(DocumentError::Invalid(
                "run requires exactly one letter spacing member",
            ));
        }
        require_numbers(run, &["fontSizePt"])?;
        let spacing = if run.contains_key("letterSpacingPt") {
            "letterSpacingPt"
        } else {
            "letterSpacingFactor"
        };
        require_numbers(run, &[spacing])?;
    }
    Ok(())
}

fn validate_commands(value: &Value) -> Result<(), DocumentError> {
    let commands = value
        .as_array()
        .ok_or(DocumentError::Invalid("commands must be an array"))?;
    if commands.is_empty() {
        return Err(DocumentError::Invalid("commands cannot be empty"));
    }
    for command in commands {
        let command = command
            .as_object()
            .ok_or(DocumentError::Invalid("path command must be an object"))?;
        let coordinates: &[&str] = match command.get("type").and_then(Value::as_str) {
            Some("MOVE_TO" | "LINE_TO") => &["xPt", "yPt"],
            Some("QUAD_TO") => &["cxPt", "cyPt", "xPt", "yPt"],
            Some("CUBIC_TO") => &["c1xPt", "c1yPt", "c2xPt", "c2yPt", "xPt", "yPt"],
            Some("CLOSE") => &[],
            _ => return Err(DocumentError::Invalid("unknown path command")),
        };
        let mut members = vec!["type"];
        members.extend_from_slice(coordinates);
        exact_named_members(command, &members, &members, "path command")?;
        require_numbers(command, coordinates)?;
    }
    Ok(())
}

fn validate_technical_descriptor(
    resource: &Map<String, Value>,
    kind: RenderResourceKind,
) -> Result<AdmittedTechnicalDescriptor, DocumentError> {
    let descriptor = require_object(resource, "technicalDescriptor")?;
    if descriptor.get("kind").and_then(Value::as_str) != Some(kind.as_str()) {
        return Err(DocumentError::Invalid("technical descriptor kind mismatch"));
    }
    match kind {
        RenderResourceKind::Image => {
            let members = [
                "colorEncoding",
                "encodedHeightPx",
                "encodedWidthPx",
                "frameCount",
                "kind",
                "logicalHeightPx",
                "logicalWidthPx",
                "orientation",
            ];
            exact_named_members(descriptor, &members, &members, "image technical descriptor")?;
            let encoded_width = positive_integer(descriptor, "encodedWidthPx")?;
            let encoded_height = positive_integer(descriptor, "encodedHeightPx")?;
            let logical_width = positive_integer(descriptor, "logicalWidthPx")?;
            let logical_height = positive_integer(descriptor, "logicalHeightPx")?;
            if encoded_width > MAX_IMAGE_EDGE_PIXELS_PER_CONTENT
                || encoded_height > MAX_IMAGE_EDGE_PIXELS_PER_CONTENT
                || encoded_width
                    .checked_mul(encoded_height)
                    .is_none_or(|pixels| pixels > MAX_IMAGE_PIXELS_PER_CONTENT)
            {
                return Err(DocumentError::Invalid(
                    "image per-content pixel budget exceeded",
                ));
            }
            if descriptor.get("frameCount").and_then(Value::as_u64) != Some(1) {
                return Err(DocumentError::Invalid("image frameCount must be one"));
            }
            require_text(descriptor, "colorEncoding", "SRGB_8BIT")?;
            let orientation = parse_orientation(descriptor)?;
            let expected_logical = if orientation.swaps_dimensions() {
                (encoded_height, encoded_width)
            } else {
                (encoded_width, encoded_height)
            };
            if (logical_width, logical_height) != expected_logical {
                return Err(DocumentError::Invalid(
                    "image logical dimensions disagree with orientation",
                ));
            }
            Ok(AdmittedTechnicalDescriptor::Image {
                encoded_width_px: encoded_width as u32,
                encoded_height_px: encoded_height as u32,
                orientation,
                logical_width_px: logical_width as u32,
                logical_height_px: logical_height as u32,
            })
        }
        RenderResourceKind::Font => {
            let members = ["faceIndex", "flavor", "kind", "unitsPerEm"];
            exact_named_members(descriptor, &members, &members, "font technical descriptor")?;
            if descriptor.get("faceIndex").and_then(Value::as_u64) != Some(0) {
                return Err(DocumentError::Invalid("font faceIndex must be zero"));
            }
            let flavor = match descriptor.get("flavor").and_then(Value::as_str) {
                Some("TRUETYPE_GLYF") => FontFlavor::TrueTypeGlyf,
                Some("CFF") => FontFlavor::Cff,
                _ => return Err(DocumentError::Invalid("font flavor is not admitted")),
            };
            let units_per_em = positive_integer(descriptor, "unitsPerEm")?;
            if !(16..=16_384).contains(&units_per_em) {
                return Err(DocumentError::Invalid(
                    "font unitsPerEm is outside its range",
                ));
            }
            Ok(AdmittedTechnicalDescriptor::Font {
                flavor,
                units_per_em: units_per_em as u16,
            })
        }
    }
}

fn parse_media_type(
    resource: &Map<String, Value>,
    expected_kind: RenderResourceKind,
) -> Result<RenderResourceMediaType, DocumentError> {
    let media_type = match resource.get("mediaType").and_then(Value::as_str) {
        Some("image/png") => RenderResourceMediaType::ImagePng,
        Some("image/jpeg") => RenderResourceMediaType::ImageJpeg,
        Some("image/webp") => RenderResourceMediaType::ImageWebp,
        Some("font/ttf") => RenderResourceMediaType::FontTtf,
        Some("font/otf") => RenderResourceMediaType::FontOtf,
        _ => return Err(DocumentError::Invalid("resource mediaType is not admitted")),
    };
    if media_type.kind() != expected_kind {
        return Err(DocumentError::Invalid("resource kind/mediaType mismatch"));
    }
    Ok(media_type)
}

fn parse_orientation(descriptor: &Map<String, Value>) -> Result<ImageOrientation, DocumentError> {
    match descriptor.get("orientation").and_then(Value::as_str) {
        Some("IDENTITY") => Ok(ImageOrientation::Identity),
        Some("MIRROR_HORIZONTAL") => Ok(ImageOrientation::MirrorHorizontal),
        Some("ROTATE_180") => Ok(ImageOrientation::Rotate180),
        Some("MIRROR_VERTICAL") => Ok(ImageOrientation::MirrorVertical),
        Some("TRANSPOSE") => Ok(ImageOrientation::Transpose),
        Some("ROTATE_90_CW") => Ok(ImageOrientation::Rotate90Clockwise),
        Some("TRANSVERSE") => Ok(ImageOrientation::Transverse),
        Some("ROTATE_270_CW") => Ok(ImageOrientation::Rotate270Clockwise),
        _ => Err(DocumentError::Invalid("image orientation is not admitted")),
    }
}

fn positive_integer(object: &Map<String, Value>, member: &str) -> Result<u64, DocumentError> {
    object
        .get(member)
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .ok_or(DocumentError::Invalid(
            "positive integer resource member is invalid",
        ))
}

fn checked_budget_add(
    current: u64,
    amount: u64,
    limit: u64,
    message: &'static str,
) -> Result<u64, DocumentError> {
    current
        .checked_add(amount)
        .filter(|total| *total <= limit)
        .ok_or(DocumentError::Invalid(message))
}

fn validate_sha256(value: &str) -> Result<(), DocumentError> {
    if value.len() != 71
        || !value.starts_with("sha256:")
        || !value.as_bytes()[7..]
            .iter()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(DocumentError::Invalid("resource sha256 is not canonical"));
    }
    Ok(())
}

fn validate_resource_id(value: &str) -> Result<(), DocumentError> {
    if value.len() != 70
        || !value.starts_with("rwres_")
        || !value.as_bytes()[6..]
            .iter()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err(DocumentError::Invalid("resourceId is not canonical"));
    }
    Ok(())
}

fn reject_dynamic_residue(
    object: &Map<String, Value>,
    forbidden: &[String],
) -> Result<(), DocumentError> {
    let forbidden = forbidden.iter().map(String::as_str).collect::<HashSet<_>>();
    reject_dynamic_value(&Value::Object(object.clone()), &forbidden)
}

fn reject_dynamic_value(value: &Value, forbidden: &HashSet<&str>) -> Result<(), DocumentError> {
    match value {
        Value::Object(object) => {
            if object.keys().any(|key| forbidden.contains(key.as_str())) {
                return Err(DocumentError::Invalid(
                    "authored or dynamic member survived lowering",
                ));
            }
            for nested in object.values() {
                reject_dynamic_value(nested, forbidden)?;
            }
        }
        Value::Array(items) => {
            for item in items {
                reject_dynamic_value(item, forbidden)?;
            }
        }
        Value::Null => return Err(DocumentError::Invalid("null is forbidden")),
        _ => {}
    }
    Ok(())
}

fn lower_mm_name(name: &str) -> String {
    name.strip_suffix("Mm")
        .map_or_else(|| name.to_owned(), |prefix| format!("{prefix}Pt"))
}

struct StrictValue(Value);

impl<'de> Deserialize<'de> for StrictValue {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_any(StrictValueVisitor)
    }
}

struct StrictValueVisitor;

impl<'de> Visitor<'de> for StrictValueVisitor {
    type Value = StrictValue;

    fn expecting(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("strict JSON without duplicate members or null")
    }

    fn visit_unit<E>(self) -> Result<Self::Value, E>
    where
        E: DeError,
    {
        Err(E::custom("null is forbidden"))
    }

    fn visit_bool<E>(self, value: bool) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::Bool(value)))
    }

    fn visit_i64<E>(self, value: i64) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::Number(Number::from(value))))
    }

    fn visit_u64<E>(self, value: u64) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::Number(Number::from(value))))
    }

    fn visit_f64<E>(self, value: f64) -> Result<Self::Value, E>
    where
        E: DeError,
    {
        Number::from_f64(value)
            .map(Value::Number)
            .map(StrictValue)
            .ok_or_else(|| E::custom("non-finite number is forbidden"))
    }

    fn visit_str<E>(self, value: &str) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::String(value.to_owned())))
    }

    fn visit_string<E>(self, value: String) -> Result<Self::Value, E> {
        Ok(StrictValue(Value::String(value)))
    }

    fn visit_seq<A>(self, mut sequence: A) -> Result<Self::Value, A::Error>
    where
        A: SeqAccess<'de>,
    {
        let mut values = Vec::new();
        while let Some(value) = sequence.next_element::<StrictValue>()? {
            values.push(value.0);
        }
        Ok(StrictValue(Value::Array(values)))
    }

    fn visit_map<A>(self, mut object: A) -> Result<Self::Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut values = Map::new();
        while let Some((key, value)) = object.next_entry::<String, StrictValue>()? {
            if values.insert(key, value.0).is_some() {
                return Err(A::Error::custom("duplicate object member"));
            }
        }
        Ok(StrictValue(Value::Object(values)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    const VECTORS: &str = include_str!("../../../render-document-vectors-v1.json");
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const PROTOCOL_VECTORS: &str = include_str!("../../../protocol-vectors-v1.json");

    const MINIMAL: &str = concat!(
        "{\"canvas\":{\"backgroundColor\":\"#00000000\",",
        "\"bleed\":{\"bottomPt\":0,\"leftPt\":0,\"rightPt\":0,\"topPt\":0},",
        "\"children\":[],\"heightPt\":841.889764,\"kind\":\"canvas\",",
        "\"occurrenceId\":\"rwocc_0000000000000000\",\"widthPt\":595.275591},",
        "\"dslVersion\":\"renderweave-render/1.0\",",
        "\"layoutProfile\":\"renderweave-layout/1.0\",\"resources\":[]}"
    );

    #[test]
    fn admits_exact_minimal_document() {
        let admitted = validate_render_document(MINIMAL).unwrap();
        assert_eq!(admitted.canonical_document(), MINIMAL);
        assert_eq!(admitted.occurrence_count(), 1);
        assert_eq!(admitted.resource_count(), 0);
        assert_eq!(
            admitted.static_kinds(),
            &BTreeSet::from(["canvas".to_owned()])
        );
    }

    #[test]
    fn admits_typed_render_resources_without_business_identity() {
        let document = ALL_KINDS.trim_end_matches(['\r', '\n']);
        let admitted = validate_render_document(document).unwrap();
        let resources = admitted.resources();
        assert_eq!(resources.len(), 2);

        let font = &resources[0];
        assert_eq!(font.kind(), RenderResourceKind::Font);
        assert_eq!(font.media_type(), RenderResourceMediaType::FontTtf);
        assert_eq!(font.byte_length(), 256);
        assert_eq!(font.expires_at_epoch_second(), 2_000);
        assert_eq!(font.acceptance_profile_id(), ASSET_ACCEPTANCE_PROFILE);
        assert_eq!(
            font.technical_descriptor().font_metrics(),
            Some((FontFlavor::TrueTypeGlyf, 1_000))
        );

        let image = &resources[1];
        assert_eq!(image.kind(), RenderResourceKind::Image);
        assert_eq!(image.media_type(), RenderResourceMediaType::ImagePng);
        assert_eq!(image.byte_length(), 128);
        assert_eq!(image.fetch_url(), "https://assets.internal/image");
        assert_eq!(
            image.technical_descriptor().image_dimensions(),
            Some((10, 10, ImageOrientation::Identity, 10, 10))
        );
        assert_eq!(
            image.sha256(),
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );
        let debug = format!("{admitted:?}");
        assert!(!debug.contains("assets.internal"));
        assert!(!debug.contains("sha256:"));
    }

    #[test]
    fn rejects_missing_default_unknown_null_duplicate_and_occurrence_drift() {
        assert!(
            validate_render_document(&MINIMAL.replace("\"backgroundColor\":\"#00000000\",", ""))
                .is_err()
        );
        assert!(
            validate_render_document(
                &MINIMAL.replace("\"children\":[]", "\"children\":[],\"unknown\":true")
            )
            .is_err()
        );
        assert!(
            validate_render_document(&MINIMAL.replace(
                "\"backgroundColor\":\"#00000000\"",
                "\"backgroundColor\":null"
            ))
            .is_err()
        );
        assert!(validate_render_document(&MINIMAL.replace(
            "\"dslVersion\":\"renderweave-render/1.0\"",
            "\"dslVersion\":\"renderweave-render/1.0\",\"dslVersion\":\"renderweave-render/1.0\""
        ))
        .is_err());
        assert!(
            validate_render_document(
                &MINIMAL.replace("rwocc_0000000000000000", "rwocc_0000000000000001")
            )
            .is_err()
        );
    }

    #[test]
    fn rejects_authored_dynamic_residue_even_when_nested() {
        assert!(
            validate_render_document(
                &MINIMAL.replace("\"children\":[]", "\"children\":[],\"bindings\":[]")
            )
            .is_err()
        );
    }

    #[test]
    fn replays_shared_positive_and_negative_vectors() {
        let vectors: Value = serde_json::from_str(VECTORS).unwrap();
        assert_eq!(
            vectors["vectorVersion"],
            "renderweave-render-document-vectors/3"
        );
        assert_eq!(
            vectors["authorityContext"]["resourceAdmission"],
            "TYPED_MANIFEST_AND_COMMAND_LEASE_PREFLIGHT_ONLY"
        );
        assert_eq!(
            vectors["authorityContext"]["leaseSafetyMarginMillis"].as_i64(),
            Some(RESOURCE_LEASE_SAFETY_MARGIN_MILLIS)
        );
        let limits = &vectors["authorityContext"]["resourceLimits"];
        for (name, expected) in [
            ("entries", MAX_RENDER_RESOURCE_ENTRIES as u64),
            ("uniqueExactContents", MAX_UNIQUE_EXACT_CONTENTS as u64),
            ("occurrenceRawBytes", MAX_OCCURRENCE_RAW_BYTES),
            ("occurrenceImagePixels", MAX_OCCURRENCE_IMAGE_PIXELS),
            ("occurrenceFontBytes", MAX_OCCURRENCE_FONT_BYTES),
            ("uniqueRawBytes", MAX_UNIQUE_RAW_BYTES),
            ("uniqueImagePixels", MAX_UNIQUE_IMAGE_PIXELS),
            ("uniqueFontBytes", MAX_UNIQUE_FONT_BYTES),
            ("manifestBytes", MAX_RESOURCE_MANIFEST_BYTES as u64),
            (
                "fetchUrlUtf8BytesPerEntry",
                MAX_FETCH_URL_UTF8_BYTES_PER_ENTRY as u64,
            ),
            ("fetchUrlUtf8BytesTotal", MAX_FETCH_URL_UTF8_BYTES_TOTAL),
            ("imageBytesPerContent", MAX_IMAGE_BYTES_PER_CONTENT),
            (
                "imageEdgePixelsPerContent",
                MAX_IMAGE_EDGE_PIXELS_PER_CONTENT,
            ),
            ("imagePixelsPerContent", MAX_IMAGE_PIXELS_PER_CONTENT),
            ("fontBytesPerContent", MAX_FONT_BYTES_PER_CONTENT),
        ] {
            assert_eq!(limits[name].as_u64(), Some(expected), "{name}");
        }
        let protocol: Value = serde_json::from_str(PROTOCOL_VECTORS).unwrap();
        let minimal = protocol["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == "png-command")
            .unwrap()["documentCanonicalJson"]
            .as_str()
            .unwrap();
        let all_kinds = ALL_KINDS.trim_end_matches(['\r', '\n']);
        let catalog_sha = format!("sha256:{}", hex::encode(Sha256::digest(CATALOG_JSON)));
        assert_eq!(vectors["authorityContext"]["catalogSha256"], catalog_sha);

        for case in vectors["positiveCases"].as_array().unwrap() {
            let document = match case["id"].as_str().unwrap() {
                "minimal-default-explicit" => minimal,
                "all-static-kinds-default-explicit" => all_kinds,
                _ => panic!("unknown positive vector"),
            };
            let admitted = validate_render_document(document).unwrap();
            assert_eq!(
                admitted.occurrence_count(),
                case["occurrenceCount"].as_u64().unwrap() as usize
            );
            assert_eq!(
                admitted.resource_count(),
                case["resourceCount"].as_u64().unwrap() as usize
            );
            let expected_kinds = case["staticKinds"]
                .as_array()
                .unwrap()
                .iter()
                .map(|kind| kind.as_str().unwrap().to_owned())
                .collect::<BTreeSet<_>>();
            assert_eq!(admitted.static_kinds(), &expected_kinds);
            let digest = digest_with_domain(b"renderweave-render-document/1\0", document);
            assert_eq!(case["renderDocumentDigest"], digest);
            if let Some(expected) = case.get("canonicalSha256") {
                assert_eq!(
                    expected,
                    &Value::String(format!(
                        "sha256:{}",
                        hex::encode(Sha256::digest(document.as_bytes()))
                    ))
                );
            }
        }

        for case in vectors["negativeCases"].as_array().unwrap() {
            let base = match case["baseCase"].as_str().unwrap() {
                "minimal-default-explicit" => minimal,
                "all-static-kinds-default-explicit" => all_kinds,
                _ => panic!("unknown negative base vector"),
            };
            let invalid = mutate(base, case);
            assert!(
                validate_render_document(&invalid).is_err(),
                "negative vector unexpectedly admitted: {}",
                case["id"]
            );
        }

        for case in vectors["resourceCases"].as_array().unwrap() {
            let mut value: Value = serde_json::from_str(all_kinds).unwrap();
            for mutation in case["mutations"].as_array().unwrap() {
                mutate_value(&mut value, mutation);
            }
            let document = serde_json::to_string(&value).unwrap();
            let admitted = validate_render_document(&document).is_ok();
            assert_eq!(
                admitted,
                case["expected"] == "ADMITTED",
                "resource vector outcome drifted: {}",
                case["id"].as_str().unwrap()
            );
        }

        for case in vectors["resourceAggregateCases"].as_array().unwrap() {
            let document = aggregate_resource_document(all_kinds, case);
            let admitted = validate_render_document(&document).is_ok();
            assert_eq!(
                admitted,
                case["expected"] == "ADMITTED",
                "resource aggregate vector outcome drifted: {}",
                case["id"].as_str().unwrap()
            );
        }

        for case in vectors["resourceLeaseCases"].as_array().unwrap() {
            let base = match case["baseCase"].as_str().unwrap() {
                "minimal-default-explicit" => minimal,
                "all-static-kinds-default-explicit" => all_kinds,
                _ => panic!("unknown resource lease base vector"),
            };
            let mut value: Value = serde_json::from_str(base).unwrap();
            let resources = value["resources"].as_array_mut().unwrap();
            let expires = case["expiresAtEpochSeconds"].as_array().unwrap();
            assert_eq!(resources.len(), expires.len());
            for (resource, expiry) in resources.iter_mut().zip(expires) {
                resource["expiresAt"] = Value::from(expiry.as_u64().unwrap());
            }
            let document = serde_json::to_string(&value).unwrap();
            let admitted = validate_render_document(&document).unwrap();
            let outcome = validate_resource_lease_coverage(
                &admitted,
                case["deadlineEpochMillis"].as_i64().unwrap(),
            );
            match case["expected"].as_str().unwrap() {
                "ADMITTED" => assert!(
                    outcome.is_ok(),
                    "resource lease vector unexpectedly rejected: {}",
                    case["id"].as_str().unwrap()
                ),
                "REJECTED" => assert_eq!(
                    outcome.unwrap_err().resource_id(),
                    case["expectedResourceId"].as_str().unwrap(),
                    "resource lease first error drifted: {}",
                    case["id"].as_str().unwrap()
                ),
                _ => panic!("unknown resource lease outcome"),
            }
        }
    }

    fn digest_with_domain(domain: &[u8], value: &str) -> String {
        let mut digest = Sha256::new();
        digest.update(domain);
        digest.update(value.as_bytes());
        format!("sha256:{}", hex::encode(digest.finalize()))
    }

    fn mutate(base: &str, case: &Value) -> String {
        if case["operation"] == "rawPrefix" {
            return format!("{}{}", case["value"].as_str().unwrap(), base);
        }
        let mut value: Value = serde_json::from_str(base).unwrap();
        let pointer = case["pointer"].as_str().unwrap();
        let (parent_pointer, token) = pointer.rsplit_once('/').unwrap();
        let token = token.replace("~1", "/").replace("~0", "~");
        let parent = value.pointer_mut(parent_pointer).unwrap();
        match (case["operation"].as_str().unwrap(), parent) {
            ("remove", Value::Object(object)) => {
                object.remove(&token).unwrap();
            }
            ("remove", Value::Array(array)) => {
                array.remove(token.parse().unwrap());
            }
            ("add" | "replace", Value::Object(object)) => {
                object.insert(token, case["value"].clone());
            }
            ("replace", Value::Array(array)) => {
                array[token.parse::<usize>().unwrap()] = case["value"].clone();
            }
            _ => panic!("unsupported vector mutation"),
        }
        serde_json::to_string(&value).unwrap()
    }

    fn mutate_value(value: &mut Value, mutation: &Value) {
        let pointer = mutation["pointer"].as_str().unwrap();
        let (parent_pointer, token) = pointer.rsplit_once('/').unwrap();
        let token = token.replace("~1", "/").replace("~0", "~");
        let parent = value.pointer_mut(parent_pointer).unwrap();
        match (mutation["operation"].as_str().unwrap(), parent) {
            ("replace", Value::Object(object)) => {
                object.insert(token, mutation["value"].clone());
            }
            ("stringUtf8Bytes", Value::Object(object)) => {
                let prefix = mutation["prefix"].as_str().unwrap();
                let length = mutation["utf8Bytes"].as_u64().unwrap() as usize;
                assert!(prefix.len() <= length && prefix.is_ascii());
                object.insert(
                    token,
                    Value::String(format!("{prefix}{}", "x".repeat(length - prefix.len()))),
                );
            }
            _ => panic!("unsupported resource vector mutation"),
        }
    }

    fn aggregate_resource_document(base: &str, case: &Value) -> String {
        let mut document: Value = serde_json::from_str(base).unwrap();
        let kind = case["kind"].as_str().unwrap();
        let count = case["count"].as_u64().unwrap() as usize;
        let unique_contents = case["uniqueContents"].as_u64().unwrap() as usize;
        let byte_length = case["byteLength"].as_u64().unwrap();
        let url_bytes = case
            .get("fetchUrlUtf8Bytes")
            .and_then(Value::as_u64)
            .map_or(11, |value| value as usize);
        let descriptor_drift = case
            .get("descriptorDriftAtIndex")
            .and_then(Value::as_u64)
            .map(|value| value as usize);
        let old_resources = document["resources"].as_array().unwrap().clone();
        let canvas = document["canvas"].as_object_mut().unwrap();
        let old_children = canvas["children"].as_array().unwrap().clone();
        let mut resources = Vec::with_capacity(count);
        let mut children = Vec::new();

        if kind == "font" {
            let mut text = old_children[4].clone();
            text["occurrenceId"] = Value::String("rwocc_0000000000000001".to_owned());
            let base_run = text["runs"][0].clone();
            let mut runs = Vec::with_capacity(count);
            for index in 0..count {
                let resource_id = format!("rwres_{index:064x}");
                let mut run = base_run.clone();
                run["fontResourceId"] = Value::String(resource_id.clone());
                runs.push(run);
                resources.push(generated_resource(
                    &old_resources[0],
                    &resource_id,
                    kind,
                    index,
                    unique_contents,
                    byte_length,
                    url_bytes,
                    1,
                    1,
                    descriptor_drift,
                ));
            }
            text["runs"] = Value::Array(runs);
            children.push(text);
        } else {
            let base_image = old_children[5].clone();
            let width = case
                .get("imageWidthPx")
                .and_then(Value::as_u64)
                .unwrap_or(1);
            let height = case
                .get("imageHeightPx")
                .and_then(Value::as_u64)
                .unwrap_or(1);
            children.reserve(count);
            for index in 0..count {
                let resource_id = format!("rwres_{index:064x}");
                let mut image = base_image.clone();
                image["occurrenceId"] = Value::String(format!("rwocc_{:016x}", index + 1));
                image["imageResourceId"] = Value::String(resource_id.clone());
                children.push(image);
                resources.push(generated_resource(
                    &old_resources[1],
                    &resource_id,
                    kind,
                    index,
                    unique_contents,
                    byte_length,
                    url_bytes,
                    width,
                    height,
                    descriptor_drift,
                ));
            }
        }
        canvas.insert("children".to_owned(), Value::Array(children));
        document["resources"] = Value::Array(resources);
        serde_json::to_string(&document).unwrap()
    }

    #[allow(clippy::too_many_arguments)]
    fn generated_resource(
        base: &Value,
        resource_id: &str,
        kind: &str,
        index: usize,
        unique_contents: usize,
        byte_length: u64,
        url_bytes: usize,
        image_width: u64,
        image_height: u64,
        descriptor_drift: Option<usize>,
    ) -> Value {
        assert!(unique_contents > 0 && url_bytes >= 10);
        let mut resource = base.clone();
        let content = index % unique_contents;
        resource["resourceId"] = Value::String(resource_id.to_owned());
        resource["sha256"] = Value::String(format!("sha256:{content:064x}"));
        resource["byteLength"] = Value::from(byte_length);
        resource["fetchUrl"] = Value::String(format!(
            "https://a/{}",
            "x".repeat(url_bytes - "https://a/".len())
        ));
        if kind == "image" {
            resource["technicalDescriptor"]["encodedWidthPx"] = Value::from(image_width);
            resource["technicalDescriptor"]["logicalWidthPx"] = Value::from(image_width);
            resource["technicalDescriptor"]["encodedHeightPx"] = Value::from(image_height);
            resource["technicalDescriptor"]["logicalHeightPx"] = Value::from(image_height);
        } else if descriptor_drift == Some(index) {
            resource["technicalDescriptor"]["unitsPerEm"] = Value::from(1001);
        }
        resource
    }
}
