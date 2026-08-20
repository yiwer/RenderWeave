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

#[derive(Debug)]
pub struct AdmittedRenderDocument {
    canonical_document: Box<str>,
    occurrence_count: usize,
    resource_count: usize,
    static_kinds: BTreeSet<String>,
}

impl AdmittedRenderDocument {
    pub fn canonical_document(&self) -> &str {
        &self.canonical_document
    }

    pub fn occurrence_count(&self) -> usize {
        self.occurrence_count
    }

    pub fn resource_count(&self) -> usize {
        self.resource_count
    }

    pub fn static_kinds(&self) -> &BTreeSet<String> {
        &self.static_kinds
    }
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
    kind: &'static str,
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
    admission.validate_resources(resources)?;
    Ok(AdmittedRenderDocument {
        canonical_document: canonical.into_boxed_str(),
        occurrence_count: admission.next_occurrence,
        resource_count: resources.len(),
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
                kind: "image",
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
                    kind: "font",
                });
            }
        }
        Ok(())
    }

    fn validate_resources(&self, resources: &[Value]) -> Result<(), DocumentError> {
        if resources.len() != self.demands.len() {
            return Err(DocumentError::Invalid(
                "resource manifest/reference cardinality mismatch",
            ));
        }
        let mut seen = HashSet::new();
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
                || resource.get("kind").and_then(Value::as_str) != Some(demand.kind)
                || !seen.insert(resource_id)
            {
                return Err(DocumentError::Invalid(
                    "resource manifest order, kind, or uniqueness mismatch",
                ));
            }
            if !resource.get("expiresAt").is_some_and(Value::is_number)
                || !resource.get("byteLength").is_some_and(Value::is_number)
            {
                return Err(DocumentError::Invalid(
                    "resource numeric metadata must be concrete",
                ));
            }
            validate_technical_descriptor(resource, demand.kind)?;
        }
        Ok(())
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
    kind: &str,
) -> Result<(), DocumentError> {
    let descriptor = require_object(resource, "technicalDescriptor")?;
    if descriptor.get("kind").and_then(Value::as_str) != Some(kind) {
        return Err(DocumentError::Invalid("technical descriptor kind mismatch"));
    }
    if kind == "image" {
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
        require_nonnegative_integers(
            descriptor,
            &[
                "encodedHeightPx",
                "encodedWidthPx",
                "frameCount",
                "logicalHeightPx",
                "logicalWidthPx",
            ],
        )?;
    } else {
        let members = ["faceIndex", "flavor", "kind", "unitsPerEm"];
        exact_named_members(descriptor, &members, &members, "font technical descriptor")?;
        require_nonnegative_integers(descriptor, &["faceIndex", "unitsPerEm"])?;
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
}
