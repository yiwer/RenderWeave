//! Static preflight and a resource-independent definite box kernel for
//! `renderweave-layout/1.0`.
//!
//! The crate consumes only a document already admitted by `renderweave-renderer-document`.
//! Preflight returns bounded structural counts or one stable DFS problem. The definite kernel
//! additionally computes local LayoutBox/ContentBox entries for resource-independent ABSOLUTE
//! nodes whose two axes are already definite. It deliberately stops before resource preparation,
//! HUG/Stack/Grid solving, world transforms, shaping, paint, rasterization, and encoding, and it
//! never exposes a partial layout on failure.

use renderweave_renderer_document::AdmittedRenderDocument;
use serde_json::{Map, Number, Value};
use std::collections::BTreeMap;
use std::fmt::{Display, Formatter};

const GRID_TRACKS_PER_AXIS_LIMIT: usize = 64;
const DECIMAL_SCALE: i128 = 1_000_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LayoutProblemCode {
    ConstraintInvalid,
    Cycle,
    NumericError,
    BudgetExceeded,
}

impl LayoutProblemCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::ConstraintInvalid => "LAYOUT_CONSTRAINT_INVALID",
            Self::Cycle => "LAYOUT_CYCLE",
            Self::NumericError => "LAYOUT_NUMERIC_ERROR",
            Self::BudgetExceeded => "LAYOUT_BUDGET_EXCEEDED",
        }
    }
}

#[derive(Debug, Eq, PartialEq)]
pub struct LayoutProblem {
    code: LayoutProblemCode,
    occurrence_id: String,
    property: String,
    parameters: BTreeMap<String, String>,
}

impl LayoutProblem {
    pub const fn code(&self) -> LayoutProblemCode {
        self.code
    }

    pub fn occurrence_id(&self) -> &str {
        &self.occurrence_id
    }

    pub fn property(&self) -> &str {
        &self.property
    }

    pub fn parameters(&self) -> &BTreeMap<String, String> {
        &self.parameters
    }

    fn new(code: LayoutProblemCode, occurrence_id: &str, property: impl Into<String>) -> Self {
        Self {
            code,
            occurrence_id: occurrence_id.to_owned(),
            property: property.into(),
            parameters: BTreeMap::new(),
        }
    }

    fn budget(occurrence_id: &str, property: impl Into<String>, limit_id: &str) -> Self {
        Self {
            code: LayoutProblemCode::BudgetExceeded,
            occurrence_id: occurrence_id.to_owned(),
            property: property.into(),
            parameters: BTreeMap::from([("limitId".to_owned(), limit_id.to_owned())]),
        }
    }
}

impl Display for LayoutProblem {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "{} at {} {}",
            self.code.as_str(),
            self.occurrence_id,
            self.property
        )
    }
}

impl std::error::Error for LayoutProblem {}

#[derive(Debug, Eq, PartialEq)]
pub struct LayoutPreflight {
    occurrence_count: usize,
    tree_edge_count: usize,
    max_depth: usize,
    grid_count: usize,
    grid_track_count: usize,
    grid_cell_count: usize,
}

impl LayoutPreflight {
    pub const fn occurrence_count(&self) -> usize {
        self.occurrence_count
    }

    pub const fn tree_edge_count(&self) -> usize {
        self.tree_edge_count
    }

    pub const fn max_depth(&self) -> usize {
        self.max_depth
    }

    pub const fn grid_count(&self) -> usize {
        self.grid_count
    }

    pub const fn grid_track_count(&self) -> usize {
        self.grid_track_count
    }

    pub const fn grid_cell_count(&self) -> usize {
        self.grid_cell_count
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LocalLayoutBox {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

impl LocalLayoutBox {
    pub const fn x(&self) -> f64 {
        self.x
    }

    pub const fn y(&self) -> f64 {
        self.y
    }

    pub const fn width(&self) -> f64 {
        self.width
    }

    pub const fn height(&self) -> f64 {
        self.height
    }
}

#[derive(Debug, PartialEq)]
pub struct DefiniteLayoutEntry {
    occurrence_id: String,
    kind: String,
    layout_box: LocalLayoutBox,
    content_box: Option<LocalLayoutBox>,
}

impl DefiniteLayoutEntry {
    pub fn occurrence_id(&self) -> &str {
        &self.occurrence_id
    }

    pub fn kind(&self) -> &str {
        &self.kind
    }

    pub const fn layout_box(&self) -> &LocalLayoutBox {
        &self.layout_box
    }

    pub const fn content_box(&self) -> Option<&LocalLayoutBox> {
        self.content_box.as_ref()
    }
}

#[derive(Debug, PartialEq)]
pub struct DefiniteLayout {
    entries: Vec<DefiniteLayoutEntry>,
}

impl DefiniteLayout {
    pub fn entries(&self) -> &[DefiniteLayoutEntry] {
        &self.entries
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DefiniteLayoutUnsupported {
    HugContent,
    Group,
    Stack,
    Grid,
    CompositionViewport,
    ResourceDependentKind,
    NonAbsolutePlacement,
    DegenerateContentInset,
}

impl DefiniteLayoutUnsupported {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::HugContent => "HUG_CONTENT",
            Self::Group => "GROUP",
            Self::Stack => "STACK",
            Self::Grid => "GRID",
            Self::CompositionViewport => "COMPOSITION_VIEWPORT",
            Self::ResourceDependentKind => "RESOURCE_DEPENDENT_KIND",
            Self::NonAbsolutePlacement => "NON_ABSOLUTE_PLACEMENT",
            Self::DegenerateContentInset => "DEGENERATE_CONTENT_INSET",
        }
    }
}

#[derive(Debug)]
enum DefiniteLayoutErrorKind {
    Preflight(LayoutProblem),
    Unsupported(DefiniteLayoutUnsupported),
    Invariant(String),
}

#[derive(Debug)]
pub struct DefiniteLayoutError {
    occurrence_id: String,
    kind: DefiniteLayoutErrorKind,
}

impl DefiniteLayoutError {
    pub fn occurrence_id(&self) -> &str {
        &self.occurrence_id
    }

    pub const fn unsupported_feature(&self) -> Option<DefiniteLayoutUnsupported> {
        match self.kind {
            DefiniteLayoutErrorKind::Unsupported(feature) => Some(feature),
            DefiniteLayoutErrorKind::Preflight(_) | DefiniteLayoutErrorKind::Invariant(_) => None,
        }
    }

    pub const fn preflight_problem(&self) -> Option<&LayoutProblem> {
        match &self.kind {
            DefiniteLayoutErrorKind::Preflight(problem) => Some(problem),
            DefiniteLayoutErrorKind::Unsupported(_) | DefiniteLayoutErrorKind::Invariant(_) => None,
        }
    }

    fn unsupported(occurrence_id: &str, feature: DefiniteLayoutUnsupported) -> Self {
        Self {
            occurrence_id: occurrence_id.to_owned(),
            kind: DefiniteLayoutErrorKind::Unsupported(feature),
        }
    }

    fn invariant(occurrence_id: &str, property: impl Into<String>) -> Self {
        Self {
            occurrence_id: occurrence_id.to_owned(),
            kind: DefiniteLayoutErrorKind::Invariant(property.into()),
        }
    }
}

impl From<LayoutProblem> for DefiniteLayoutError {
    fn from(problem: LayoutProblem) -> Self {
        Self {
            occurrence_id: problem.occurrence_id.clone(),
            kind: DefiniteLayoutErrorKind::Preflight(problem),
        }
    }
}

impl Display for DefiniteLayoutError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match &self.kind {
            DefiniteLayoutErrorKind::Preflight(problem) => Display::fmt(problem, formatter),
            DefiniteLayoutErrorKind::Unsupported(feature) => write!(
                formatter,
                "definite layout unsupported {} at {}",
                feature.as_str(),
                self.occurrence_id
            ),
            DefiniteLayoutErrorKind::Invariant(property) => write!(
                formatter,
                "definite layout invariant failed at {} {}",
                self.occurrence_id, property
            ),
        }
    }
}

impl std::error::Error for DefiniteLayoutError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.preflight_problem()
            .map(|problem| problem as &(dyn std::error::Error + 'static))
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SizeMode {
    Fixed,
    Hug,
    Fill,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum TrackKind {
    Fixed,
    Fraction,
    Auto,
}

#[derive(Clone, Debug)]
enum ParentContext {
    Canvas,
    Absolute {
        kind: String,
        width_mode: SizeMode,
        height_mode: SizeMode,
    },
    Stack {
        width_mode: SizeMode,
        height_mode: SizeMode,
    },
    Grid {
        rows: Vec<TrackKind>,
        columns: Vec<TrackKind>,
    },
}

#[derive(Default)]
struct Preflight {
    occurrence_count: usize,
    tree_edge_count: usize,
    max_depth: usize,
    grid_count: usize,
    grid_track_count: usize,
    grid_cell_count: usize,
}

struct DefiniteLayouter {
    entries: Vec<DefiniteLayoutEntry>,
}

pub fn preflight_layout(
    document: &AdmittedRenderDocument,
) -> Result<LayoutPreflight, LayoutProblem> {
    parse_and_preflight(document).map(|(_, preflight)| preflight)
}

fn parse_and_preflight(
    document: &AdmittedRenderDocument,
) -> Result<(Value, LayoutPreflight), LayoutProblem> {
    // Definite layout reuses this tree so each decimal enters binary64 only in this parse.
    let root: Value = serde_json::from_str(document.canonical_document()).map_err(|_| {
        LayoutProblem::new(
            LayoutProblemCode::NumericError,
            "rwocc_0000000000000000",
            "document",
        )
    })?;
    let canvas = object_member(root.as_object(), "canvas", "rwocc_0000000000000000")?;
    let mut state = Preflight::default();
    state.visit_canvas(canvas, 1)?;
    if state.occurrence_count != document.occurrence_count() {
        return Err(LayoutProblem::new(
            LayoutProblemCode::ConstraintInvalid,
            "rwocc_0000000000000000",
            "occurrenceCount",
        ));
    }
    Ok((
        root,
        LayoutPreflight {
            occurrence_count: state.occurrence_count,
            tree_edge_count: state.tree_edge_count,
            max_depth: state.max_depth,
            grid_count: state.grid_count,
            grid_track_count: state.grid_track_count,
            grid_cell_count: state.grid_cell_count,
        },
    ))
}

pub fn layout_definite_absolute(
    document: &AdmittedRenderDocument,
) -> Result<DefiniteLayout, DefiniteLayoutError> {
    let (root, _) = parse_and_preflight(document)?;
    let canvas = object_member(root.as_object(), "canvas", "rwocc_0000000000000000")?;
    let occurrence = occurrence_id(canvas)?;
    let width = binary64_member(canvas, "widthPt", occurrence, "widthPt")?;
    let height = binary64_member(canvas, "heightPt", occurrence, "heightPt")?;
    let canvas_box = LocalLayoutBox {
        x: 0.0,
        y: 0.0,
        width,
        height,
    };
    let mut state = DefiniteLayouter {
        entries: Vec::with_capacity(document.occurrence_count()),
    };
    state.entries.push(DefiniteLayoutEntry {
        occurrence_id: occurrence.to_owned(),
        kind: "canvas".to_owned(),
        layout_box: canvas_box,
        content_box: Some(canvas_box),
    });
    for child in array_member(canvas, "children", occurrence)? {
        state.visit_node(object(child, occurrence, "children")?, &canvas_box)?;
    }
    if state.entries.len() != document.occurrence_count() {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            "occurrenceCount",
        ));
    }
    Ok(DefiniteLayout {
        entries: state.entries,
    })
}

impl DefiniteLayouter {
    fn visit_node(
        &mut self,
        node: &Map<String, Value>,
        parent_content: &LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(node)?;
        let kind = text_member(node, "kind", occurrence, "kind")?;
        let supported_container = match kind {
            "frame" => true,
            "rect" | "ellipse" | "line" | "polygon" | "polyline" | "path" | "qrCode"
            | "barcode" => false,
            "group" => {
                return Err(DefiniteLayoutError::unsupported(
                    occurrence,
                    DefiniteLayoutUnsupported::Group,
                ));
            }
            "stack" => {
                return Err(DefiniteLayoutError::unsupported(
                    occurrence,
                    DefiniteLayoutUnsupported::Stack,
                ));
            }
            "grid" => {
                return Err(DefiniteLayoutError::unsupported(
                    occurrence,
                    DefiniteLayoutUnsupported::Grid,
                ));
            }
            "compositionViewport" => {
                return Err(DefiniteLayoutError::unsupported(
                    occurrence,
                    DefiniteLayoutUnsupported::CompositionViewport,
                ));
            }
            "text" | "image" => {
                return Err(DefiniteLayoutError::unsupported(
                    occurrence,
                    DefiniteLayoutUnsupported::ResourceDependentKind,
                ));
            }
            _ => return Err(DefiniteLayoutError::invariant(occurrence, "kind")),
        };

        let placement = object_member(Some(node), "placement", occurrence)?;
        if text_member(placement, "type", occurrence, "placement.type")? != "ABSOLUTE" {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::NonAbsolutePlacement,
            ));
        }
        let width_mode = size_mode(placement, "widthMode", occurrence)?;
        let height_mode = size_mode(placement, "heightMode", occurrence)?;
        if width_mode == SizeMode::Hug || height_mode == SizeMode::Hug {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::HugContent,
            ));
        }

        let authored_x = binary64_member(placement, "xPt", occurrence, "placement.xPt")?;
        let authored_y = binary64_member(placement, "yPt", occurrence, "placement.yPt")?;
        let width = definite_axis_size(
            placement,
            width_mode,
            parent_content.width,
            authored_x,
            "Width",
            "rightInsetPt",
            occurrence,
        )?;
        let height = definite_axis_size(
            placement,
            height_mode,
            parent_content.height,
            authored_y,
            "Height",
            "bottomInsetPt",
            occurrence,
        )?;
        let layout_box = LocalLayoutBox {
            x: parent_content.x + authored_x,
            y: parent_content.y + authored_y,
            width,
            height,
        };

        if supported_container {
            let content_box = frame_content_box(node, &layout_box, occurrence)?;
            self.entries.push(DefiniteLayoutEntry {
                occurrence_id: occurrence.to_owned(),
                kind: kind.to_owned(),
                layout_box,
                content_box: Some(content_box),
            });
            for child in array_member(node, "children", occurrence)? {
                self.visit_node(object(child, occurrence, "children")?, &content_box)?;
            }
        } else {
            self.entries.push(DefiniteLayoutEntry {
                occurrence_id: occurrence.to_owned(),
                kind: kind.to_owned(),
                layout_box,
                content_box: None,
            });
        }
        Ok(())
    }
}

fn definite_axis_size(
    placement: &Map<String, Value>,
    mode: SizeMode,
    parent_size: f64,
    start: f64,
    axis: &str,
    end_inset_member: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let size_member = format!("{}Pt", axis.to_ascii_lowercase());
    if mode == SizeMode::Fixed {
        return binary64_member(
            placement,
            &size_member,
            occurrence,
            format!("placement.{size_member}"),
        );
    }
    if mode != SizeMode::Fill {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("placement.{axis}Mode"),
        ));
    }

    let end_inset = binary64_member(
        placement,
        end_inset_member,
        occurrence,
        format!("placement.{end_inset_member}"),
    )?;
    let remaining = (parent_size - start) - end_inset;
    let mut size = if remaining > 0.0 { remaining } else { 0.0 };
    let minimum_member = format!("min{axis}Pt");
    if let Some(minimum) = optional_binary64_member(
        placement,
        &minimum_member,
        occurrence,
        format!("placement.{minimum_member}"),
    )? && size < minimum
    {
        size = minimum;
    }
    let maximum_member = format!("max{axis}Pt");
    if let Some(maximum) = optional_binary64_member(
        placement,
        &maximum_member,
        occurrence,
        format!("placement.{maximum_member}"),
    )? && size > maximum
    {
        size = maximum;
    }
    Ok(size)
}

fn frame_content_box(
    node: &Map<String, Value>,
    layout_box: &LocalLayoutBox,
    occurrence: &str,
) -> Result<LocalLayoutBox, DefiniteLayoutError> {
    let stroke_width = if let Some(stroke) = node.get("stroke") {
        let stroke = stroke
            .as_object()
            .ok_or_else(|| DefiniteLayoutError::invariant(occurrence, "stroke"))?;
        nonnegative_binary64_member(stroke, "widthPt", occurrence, "stroke.widthPt")?
    } else {
        0.0
    };
    let inner_width = subtract_content_inset(layout_box.width, stroke_width, occurrence)?;
    let inner_width = subtract_content_inset(inner_width, stroke_width, occurrence)?;
    let inner_height = subtract_content_inset(layout_box.height, stroke_width, occurrence)?;
    let inner_height = subtract_content_inset(inner_height, stroke_width, occurrence)?;
    let inner_x = layout_box.x + stroke_width;
    let inner_y = layout_box.y + stroke_width;

    let padding = object_member(Some(node), "padding", occurrence)?;
    let top = nonnegative_binary64_member(padding, "topPt", occurrence, "padding.topPt")?;
    let right = nonnegative_binary64_member(padding, "rightPt", occurrence, "padding.rightPt")?;
    let bottom = nonnegative_binary64_member(padding, "bottomPt", occurrence, "padding.bottomPt")?;
    let left = nonnegative_binary64_member(padding, "leftPt", occurrence, "padding.leftPt")?;
    let content_width = subtract_content_inset(inner_width, left, occurrence)?;
    let content_width = subtract_content_inset(content_width, right, occurrence)?;
    let content_height = subtract_content_inset(inner_height, top, occurrence)?;
    let content_height = subtract_content_inset(content_height, bottom, occurrence)?;
    Ok(LocalLayoutBox {
        x: inner_x + left,
        y: inner_y + top,
        width: content_width,
        height: content_height,
    })
}

fn subtract_content_inset(
    size: f64,
    inset: f64,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let remaining = size - inset;
    if remaining < 0.0 {
        return Err(DefiniteLayoutError::unsupported(
            occurrence,
            DefiniteLayoutUnsupported::DegenerateContentInset,
        ));
    }
    Ok(remaining)
}

fn nonnegative_binary64_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl Into<String>,
) -> Result<f64, DefiniteLayoutError> {
    let property = property.into();
    let value = binary64_member(object, member, occurrence, &property)?;
    if value < 0.0 {
        return Err(
            LayoutProblem::new(LayoutProblemCode::ConstraintInvalid, occurrence, property).into(),
        );
    }
    Ok(value)
}

fn optional_binary64_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl Into<String>,
) -> Result<Option<f64>, DefiniteLayoutError> {
    let property = property.into();
    object
        .contains_key(member)
        .then(|| binary64_member(object, member, occurrence, property))
        .transpose()
}

fn binary64_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl AsRef<str>,
) -> Result<f64, DefiniteLayoutError> {
    let property = property.as_ref();
    let number = object.get(member).and_then(Value::as_number);
    decimal6(number, occurrence, property)?;
    number
        .and_then(Number::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| {
            LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property).into()
        })
}

impl Preflight {
    fn visit_canvas(
        &mut self,
        canvas: &Map<String, Value>,
        depth: usize,
    ) -> Result<(), LayoutProblem> {
        let occurrence = occurrence_id(canvas)?;
        self.reserve_occurrence(depth, occurrence)?;
        positive_member(canvas, "widthPt", occurrence, "widthPt")?;
        positive_member(canvas, "heightPt", occurrence, "heightPt")?;
        if let Some(bleed) = canvas.get("bleed") {
            let bleed = bleed.as_object().ok_or_else(|| {
                LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, "bleed")
            })?;
            for member in ["topPt", "rightPt", "bottomPt", "leftPt"] {
                nonnegative_member(bleed, member, occurrence, format!("bleed.{member}"))?;
            }
        }
        let children = array_member(canvas, "children", occurrence)?;
        for child in children {
            self.reserve_edge(occurrence)?;
            self.visit_node(
                object(child, occurrence, "children")?,
                depth + 1,
                &ParentContext::Canvas,
            )?;
        }
        Ok(())
    }

    fn visit_node(
        &mut self,
        node: &Map<String, Value>,
        depth: usize,
        parent: &ParentContext,
    ) -> Result<(), LayoutProblem> {
        let occurrence = occurrence_id(node)?;
        self.reserve_occurrence(depth, occurrence)?;
        let kind = text_member(node, "kind", occurrence, "kind")?;
        let placement = object_member(Some(node), "placement", occurrence)?;
        let width_mode = size_mode(placement, "widthMode", occurrence)?;
        let height_mode = size_mode(placement, "heightMode", occurrence)?;

        self.validate_mode_capability(kind, width_mode, height_mode, occurrence)?;
        self.validate_placement_numbers(placement, occurrence)?;
        if kind == "group" {
            for member in ["minWidthPt", "minHeightPt", "maxWidthPt", "maxHeightPt"] {
                if placement.contains_key(member) {
                    return Err(LayoutProblem::new(
                        LayoutProblemCode::ConstraintInvalid,
                        occurrence,
                        format!("placement.{member}"),
                    ));
                }
            }
        }
        self.validate_axis(placement, width_mode, "Width", occurrence)?;
        self.validate_axis(placement, height_mode, "Height", occurrence)?;
        self.validate_parent_dependency(parent, placement, width_mode, height_mode, occurrence)?;

        if kind == "text"
            && node.get("maxLines").is_some()
            && node.get("overflow").and_then(Value::as_str) == Some("VISIBLE")
        {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                "maxLines",
            ));
        }
        if let Some(max_lines) = node.get("maxLines")
            && max_lines.as_u64().is_none_or(|value| value == 0)
        {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                "maxLines",
            ));
        }
        if kind == "qrCode" && width_mode == SizeMode::Fixed && height_mode == SizeMode::Fixed {
            let width = decimal_member(placement, "widthPt", occurrence, "placement.widthPt")?;
            let height = decimal_member(placement, "heightPt", occurrence, "placement.heightPt")?;
            if width != height {
                return Err(LayoutProblem::new(
                    LayoutProblemCode::ConstraintInvalid,
                    occurrence,
                    "placement.heightPt",
                ));
            }
        }

        match kind {
            "compositionViewport" => {
                let source = object_member(Some(node), "sourceCanvas", occurrence)?;
                self.reserve_edge(occurrence)?;
                self.visit_canvas(source, depth + 1)?;
            }
            "group" | "frame" => {
                let context = ParentContext::Absolute {
                    kind: kind.to_owned(),
                    width_mode,
                    height_mode,
                };
                self.visit_children(node, depth, occurrence, &context)?;
            }
            "stack" => {
                let direction = text_member(node, "direction", occurrence, "direction")?;
                if !matches!(direction, "ROW" | "COLUMN") {
                    return Err(LayoutProblem::new(
                        LayoutProblemCode::ConstraintInvalid,
                        occurrence,
                        "direction",
                    ));
                }
                nonnegative_member(node, "gapPt", occurrence, "gapPt")?;
                let context = ParentContext::Stack {
                    width_mode,
                    height_mode,
                };
                self.visit_children(node, depth, occurrence, &context)?;
            }
            "grid" => {
                nonnegative_member(node, "rowGapPt", occurrence, "rowGapPt")?;
                nonnegative_member(node, "columnGapPt", occurrence, "columnGapPt")?;
                let rows = self.validate_tracks(node, "rows", height_mode, occurrence)?;
                let columns = self.validate_tracks(node, "columns", width_mode, occurrence)?;
                self.grid_count = checked_add(self.grid_count, 1, occurrence, "gridCount")?;
                self.grid_track_count = checked_add(
                    self.grid_track_count,
                    rows.len() + columns.len(),
                    occurrence,
                    "gridTrackCount",
                )?;
                let children = array_member(node, "children", occurrence)?;
                self.grid_cell_count = checked_add(
                    self.grid_cell_count,
                    children.len(),
                    occurrence,
                    "gridCellCount",
                )?;
                let context = ParentContext::Grid { rows, columns };
                self.visit_children(node, depth, occurrence, &context)?;
            }
            _ => {}
        }
        Ok(())
    }

    fn visit_children(
        &mut self,
        node: &Map<String, Value>,
        depth: usize,
        occurrence: &str,
        parent: &ParentContext,
    ) -> Result<(), LayoutProblem> {
        for child in array_member(node, "children", occurrence)? {
            self.reserve_edge(occurrence)?;
            self.visit_node(object(child, occurrence, "children")?, depth + 1, parent)?;
        }
        Ok(())
    }

    fn reserve_occurrence(&mut self, depth: usize, occurrence: &str) -> Result<(), LayoutProblem> {
        self.occurrence_count =
            checked_add(self.occurrence_count, 1, occurrence, "occurrenceCount")?;
        self.max_depth = self.max_depth.max(depth);
        Ok(())
    }

    fn reserve_edge(&mut self, occurrence: &str) -> Result<(), LayoutProblem> {
        self.tree_edge_count = checked_add(self.tree_edge_count, 1, occurrence, "treeEdgeCount")?;
        Ok(())
    }

    fn validate_mode_capability(
        &self,
        kind: &str,
        width_mode: SizeMode,
        height_mode: SizeMode,
        occurrence: &str,
    ) -> Result<(), LayoutProblem> {
        let allowed = |mode| match kind {
            "group" => mode == SizeMode::Hug,
            "rect" | "ellipse" | "qrCode" | "barcode" => mode != SizeMode::Hug,
            _ => true,
        };
        if !allowed(width_mode) {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                "placement.widthMode",
            ));
        }
        if !allowed(height_mode) {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                "placement.heightMode",
            ));
        }
        if kind == "image" && width_mode == SizeMode::Hug && height_mode == SizeMode::Hug {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                "placement.heightMode",
            ));
        }
        Ok(())
    }

    fn validate_placement_numbers(
        &self,
        placement: &Map<String, Value>,
        occurrence: &str,
    ) -> Result<(), LayoutProblem> {
        for member in [
            "xPt",
            "yPt",
            "widthPt",
            "heightPt",
            "minWidthPt",
            "minHeightPt",
            "maxWidthPt",
            "maxHeightPt",
            "rightInsetPt",
            "bottomInsetPt",
            "marginTopPt",
            "marginRightPt",
            "marginBottomPt",
            "marginLeftPt",
            "fillWeight",
        ] {
            if placement.contains_key(member) {
                decimal_member(placement, member, occurrence, format!("placement.{member}"))?;
            }
        }
        if placement.contains_key("fillWeight") {
            positive_member(placement, "fillWeight", occurrence, "placement.fillWeight")?;
        }
        Ok(())
    }

    fn validate_axis(
        &self,
        placement: &Map<String, Value>,
        mode: SizeMode,
        axis: &str,
        occurrence: &str,
    ) -> Result<(), LayoutProblem> {
        let size_name = format!("{}Pt", axis.to_ascii_lowercase());
        let min_name = format!("min{axis}Pt");
        let max_name = format!("max{axis}Pt");
        let size_property = format!("placement.{size_name}");
        let min_property = format!("placement.{min_name}");
        let max_property = format!("placement.{max_name}");
        let minimum = optional_decimal(placement, &min_name, occurrence, &min_property)?;
        let maximum = optional_decimal(placement, &max_name, occurrence, &max_property)?;
        if minimum.is_some_and(|value| value < 0) {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                min_property,
            ));
        }
        if maximum.is_some_and(|value| value <= 0) {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                max_property,
            ));
        }
        if minimum.zip(maximum).is_some_and(|(min, max)| min > max) {
            return Err(LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                occurrence,
                min_property,
            ));
        }
        if mode == SizeMode::Fixed {
            let size = decimal_member(placement, &size_name, occurrence, &size_property)?;
            if size <= 0
                || minimum.is_some_and(|min| size < min)
                || maximum.is_some_and(|max| size > max)
            {
                return Err(LayoutProblem::new(
                    LayoutProblemCode::ConstraintInvalid,
                    occurrence,
                    size_property,
                ));
            }
        }
        Ok(())
    }

    fn validate_parent_dependency(
        &self,
        parent: &ParentContext,
        placement: &Map<String, Value>,
        width_mode: SizeMode,
        height_mode: SizeMode,
        occurrence: &str,
    ) -> Result<(), LayoutProblem> {
        match parent {
            ParentContext::Canvas => Ok(()),
            ParentContext::Absolute {
                kind,
                width_mode: parent_width,
                height_mode: parent_height,
            } => {
                if matches!(kind.as_str(), "group" | "frame") {
                    reject_hug_fill(*parent_width, width_mode, occurrence, "placement.widthMode")?;
                    reject_hug_fill(
                        *parent_height,
                        height_mode,
                        occurrence,
                        "placement.heightMode",
                    )?;
                }
                Ok(())
            }
            ParentContext::Stack {
                width_mode: parent_width,
                height_mode: parent_height,
            } => {
                reject_hug_fill(*parent_width, width_mode, occurrence, "placement.widthMode")?;
                reject_hug_fill(
                    *parent_height,
                    height_mode,
                    occurrence,
                    "placement.heightMode",
                )
            }
            ParentContext::Grid { rows, columns } => {
                let row = integer_member(placement, "row", occurrence, "placement.row")?;
                let column = integer_member(placement, "column", occurrence, "placement.column")?;
                let row_span =
                    integer_member(placement, "rowSpan", occurrence, "placement.rowSpan")?;
                let column_span =
                    integer_member(placement, "columnSpan", occurrence, "placement.columnSpan")?;
                validate_grid_range(row, row_span, rows.len(), occurrence, "row", "rowSpan")?;
                validate_grid_range(
                    column,
                    column_span,
                    columns.len(),
                    occurrence,
                    "column",
                    "columnSpan",
                )?;
                if width_mode == SizeMode::Fill
                    && columns[column..column + column_span].contains(&TrackKind::Auto)
                {
                    return Err(LayoutProblem::new(
                        LayoutProblemCode::Cycle,
                        occurrence,
                        "placement.widthMode",
                    ));
                }
                if height_mode == SizeMode::Fill
                    && rows[row..row + row_span].contains(&TrackKind::Auto)
                {
                    return Err(LayoutProblem::new(
                        LayoutProblemCode::Cycle,
                        occurrence,
                        "placement.heightMode",
                    ));
                }
                Ok(())
            }
        }
    }

    fn validate_tracks(
        &self,
        node: &Map<String, Value>,
        member: &str,
        axis_mode: SizeMode,
        occurrence: &str,
    ) -> Result<Vec<TrackKind>, LayoutProblem> {
        let tracks = array_member(node, member, occurrence)?;
        if tracks.len() > GRID_TRACKS_PER_AXIS_LIMIT {
            return Err(LayoutProblem::budget(
                occurrence,
                member,
                "designDsl.gridTracksPerAxis",
            ));
        }
        let mut result = Vec::with_capacity(tracks.len());
        for (index, track) in tracks.iter().enumerate() {
            let track = object(track, occurrence, member)?;
            let track_type =
                text_member(track, "type", occurrence, format!("{member}[{index}].type"))?;
            let kind = match track_type {
                "FIXED" => {
                    positive_member(
                        track,
                        "valuePt",
                        occurrence,
                        format!("{member}[{index}].valuePt"),
                    )?;
                    TrackKind::Fixed
                }
                "FRACTION" => {
                    positive_member(
                        track,
                        "weight",
                        occurrence,
                        format!("{member}[{index}].weight"),
                    )?;
                    if axis_mode == SizeMode::Hug {
                        return Err(LayoutProblem::new(
                            LayoutProblemCode::ConstraintInvalid,
                            occurrence,
                            format!("{member}[{index}].type"),
                        ));
                    }
                    TrackKind::Fraction
                }
                "AUTO" => TrackKind::Auto,
                _ => {
                    return Err(LayoutProblem::new(
                        LayoutProblemCode::ConstraintInvalid,
                        occurrence,
                        format!("{member}[{index}].type"),
                    ));
                }
            };
            result.push(kind);
        }
        Ok(result)
    }
}

fn reject_hug_fill(
    parent: SizeMode,
    child: SizeMode,
    occurrence: &str,
    property: &str,
) -> Result<(), LayoutProblem> {
    if parent == SizeMode::Hug && child == SizeMode::Fill {
        return Err(LayoutProblem::new(
            LayoutProblemCode::Cycle,
            occurrence,
            property,
        ));
    }
    Ok(())
}

fn validate_grid_range(
    start: usize,
    span: usize,
    track_count: usize,
    occurrence: &str,
    start_property: &str,
    span_property: &str,
) -> Result<(), LayoutProblem> {
    if start >= track_count {
        return Err(LayoutProblem::new(
            LayoutProblemCode::ConstraintInvalid,
            occurrence,
            format!("placement.{start_property}"),
        ));
    }
    if start.checked_add(span).is_none_or(|end| end > track_count) {
        return Err(LayoutProblem::new(
            LayoutProblemCode::ConstraintInvalid,
            occurrence,
            format!("placement.{span_property}"),
        ));
    }
    Ok(())
}

fn checked_add(
    current: usize,
    increment: usize,
    occurrence: &str,
    property: &str,
) -> Result<usize, LayoutProblem> {
    current
        .checked_add(increment)
        .ok_or_else(|| LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property))
}

fn size_mode(
    placement: &Map<String, Value>,
    member: &str,
    occurrence: &str,
) -> Result<SizeMode, LayoutProblem> {
    match placement.get(member).and_then(Value::as_str) {
        Some("FIXED") => Ok(SizeMode::Fixed),
        Some("HUG_CONTENT") => Ok(SizeMode::Hug),
        Some("FILL") => Ok(SizeMode::Fill),
        _ => Err(LayoutProblem::new(
            LayoutProblemCode::ConstraintInvalid,
            occurrence,
            format!("placement.{member}"),
        )),
    }
}

fn positive_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl Into<String>,
) -> Result<i128, LayoutProblem> {
    let property = property.into();
    let value = decimal_member(object, member, occurrence, &property)?;
    if value <= 0 {
        return Err(LayoutProblem::new(
            LayoutProblemCode::ConstraintInvalid,
            occurrence,
            property,
        ));
    }
    Ok(value)
}

fn nonnegative_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl Into<String>,
) -> Result<i128, LayoutProblem> {
    let property = property.into();
    let value = decimal_member(object, member, occurrence, &property)?;
    if value < 0 {
        return Err(LayoutProblem::new(
            LayoutProblemCode::ConstraintInvalid,
            occurrence,
            property,
        ));
    }
    Ok(value)
}

fn optional_decimal(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: &str,
) -> Result<Option<i128>, LayoutProblem> {
    object
        .get(member)
        .map(|value| decimal6(value.as_number(), occurrence, property))
        .transpose()
}

fn decimal_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl AsRef<str>,
) -> Result<i128, LayoutProblem> {
    decimal6(
        object.get(member).and_then(Value::as_number),
        occurrence,
        property.as_ref(),
    )
}

fn decimal6(
    number: Option<&Number>,
    occurrence: &str,
    property: &str,
) -> Result<i128, LayoutProblem> {
    let token = number
        .map(Number::to_string)
        .ok_or_else(|| LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property))?;
    let (negative, unsigned) = token
        .strip_prefix('-')
        .map_or((false, token.as_str()), |value| (true, value));
    if unsigned.is_empty() || unsigned.contains(['e', 'E']) {
        return Err(LayoutProblem::new(
            LayoutProblemCode::NumericError,
            occurrence,
            property,
        ));
    }
    let mut parts = unsigned.split('.');
    let integer = parts.next().unwrap_or_default();
    let fraction = parts.next();
    if parts.next().is_some()
        || integer.is_empty()
        || !integer.bytes().all(|byte| byte.is_ascii_digit())
        || fraction.is_some_and(|value| {
            value.is_empty() || value.len() > 6 || !value.bytes().all(|byte| byte.is_ascii_digit())
        })
    {
        return Err(LayoutProblem::new(
            LayoutProblemCode::NumericError,
            occurrence,
            property,
        ));
    }
    let whole = integer
        .parse::<i128>()
        .map_err(|_| LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property))?;
    let mut scaled = whole
        .checked_mul(DECIMAL_SCALE)
        .ok_or_else(|| LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property))?;
    if let Some(fraction) = fraction {
        let fractional = fraction.parse::<i128>().map_err(|_| {
            LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property)
        })?;
        let multiplier = 10_i128.pow((6 - fraction.len()) as u32);
        scaled = scaled
            .checked_add(fractional.checked_mul(multiplier).ok_or_else(|| {
                LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property)
            })?)
            .ok_or_else(|| {
                LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property)
            })?;
    }
    if negative {
        if scaled == 0 {
            return Err(LayoutProblem::new(
                LayoutProblemCode::NumericError,
                occurrence,
                property,
            ));
        }
        scaled = scaled.checked_neg().ok_or_else(|| {
            LayoutProblem::new(LayoutProblemCode::NumericError, occurrence, property)
        })?;
    }
    Ok(scaled)
}

fn integer_member(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: &str,
) -> Result<usize, LayoutProblem> {
    object
        .get(member)
        .and_then(Value::as_u64)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| {
            LayoutProblem::new(LayoutProblemCode::ConstraintInvalid, occurrence, property)
        })
}

fn occurrence_id(object: &Map<String, Value>) -> Result<&str, LayoutProblem> {
    object
        .get("occurrenceId")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            LayoutProblem::new(
                LayoutProblemCode::ConstraintInvalid,
                "rwocc_0000000000000000",
                "occurrenceId",
            )
        })
}

fn text_member<'a>(
    object: &'a Map<String, Value>,
    member: &str,
    occurrence: &str,
    property: impl Into<String>,
) -> Result<&'a str, LayoutProblem> {
    object.get(member).and_then(Value::as_str).ok_or_else(|| {
        LayoutProblem::new(LayoutProblemCode::ConstraintInvalid, occurrence, property)
    })
}

fn object_member<'a>(
    object: Option<&'a Map<String, Value>>,
    member: &str,
    occurrence: &str,
) -> Result<&'a Map<String, Value>, LayoutProblem> {
    object
        .and_then(|value| value.get(member))
        .and_then(Value::as_object)
        .ok_or_else(|| LayoutProblem::new(LayoutProblemCode::ConstraintInvalid, occurrence, member))
}

fn array_member<'a>(
    object: &'a Map<String, Value>,
    member: &str,
    occurrence: &str,
) -> Result<&'a [Value], LayoutProblem> {
    object
        .get(member)
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .ok_or_else(|| LayoutProblem::new(LayoutProblemCode::ConstraintInvalid, occurrence, member))
}

fn object<'a>(
    value: &'a Value,
    occurrence: &str,
    property: &str,
) -> Result<&'a Map<String, Value>, LayoutProblem> {
    value.as_object().ok_or_else(|| {
        LayoutProblem::new(LayoutProblemCode::ConstraintInvalid, occurrence, property)
    })
}
