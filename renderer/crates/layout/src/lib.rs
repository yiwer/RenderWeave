//! Static preflight and a resource-independent definite box kernel for
//! `renderweave-layout/1.0`.
//!
//! The crate consumes only a document already admitted by `renderweave-renderer-document`.
//! Preflight returns bounded structural counts or one stable DFS problem. The definite kernel
//! additionally computes local LayoutBox/ContentBox entries for resource-independent ABSOLUTE
//! nodes, Stack children with singleton or inactive-bound multiple main-axis FILL,
//! resource-independent Stack/Grid HUG
//! measurement, exact-quarter-turn affine nonempty Frame/Group HUG measurement (including
//! definite ABSOLUTE/Stack/FIXED opposite-axis Frame offers for odd-quarter-turn cross-axis
//! FILL, same-direction nested Stack main-offer propagation, columns-first direct Grid cell outer
//! offers, and ROW Stack main offers into columns-first Grid cross-HUG), Group normalization, and
//! Grid children whose
//! definite axes contain FIXED tracks, stable finite multi-FRACTION last-remainder allocation,
//! and resource-independent AUTO constraints, including stable multi-AUTO span deficits, that
//! consume supported resource-free HUG
//! contributions. It deliberately stops before resource preparation, non-quarter-turn child
//! rotation, ABSOLUTE/Grid-in-Grid owning offers or row-to-column/general constraint propagation,
//! active-bound multi-FILL Stack water filling, FRACTION tolerance recovery,
//! world transforms, shaping, paint, rasterization, and encoding, and it
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
    StackMainFill,
    GridAutoTrack,
    GridFractionTrack,
    ChildRotation,
    CompositionViewport,
    ResourceDependentKind,
    NonAbsolutePlacement,
}

impl DefiniteLayoutUnsupported {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::HugContent => "HUG_CONTENT",
            Self::Group => "GROUP",
            Self::StackMainFill => "STACK_MAIN_FILL",
            Self::GridAutoTrack => "GRID_AUTO_TRACK",
            Self::GridFractionTrack => "GRID_FRACTION_TRACK",
            Self::ChildRotation => "CHILD_ROTATION",
            Self::CompositionViewport => "COMPOSITION_VIEWPORT",
            Self::ResourceDependentKind => "RESOURCE_DEPENDENT_KIND",
            Self::NonAbsolutePlacement => "NON_ABSOLUTE_PLACEMENT",
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
enum NodeRole {
    Group,
    Frame,
    Stack,
    Grid,
    Leaf,
}

#[derive(Clone, Copy, Debug)]
struct AffineAxisInterval {
    minimum: f64,
    maximum: f64,
}

#[derive(Clone, Copy, Debug)]
enum HugOppositeAxisOffer {
    AbsoluteParentContent(f64),
    ResolvedOuter(f64),
}

#[derive(Clone, Copy, Debug, Default)]
struct AbsoluteChildMeasureOffers {
    axis_fill: Option<f64>,
    opposite_axis_hug: Option<HugOppositeAxisOffer>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ExactQuarterTurn {
    Zero,
    Clockwise90,
    HalfTurn,
    Clockwise270,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum StackDirection {
    Row,
    Column,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum StackAlignment {
    Start,
    Center,
    End,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum StackJustification {
    Start,
    Center,
    End,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
}

#[derive(Clone, Copy, Debug)]
struct StackChildMeasurement {
    width: f64,
    height: f64,
    margin_top: f64,
    margin_right: f64,
    margin_bottom: f64,
    margin_left: f64,
    align_self: StackAlignment,
    main_fill: bool,
    deferred_cross_hug_after_main_fill: bool,
}

impl StackChildMeasurement {
    const fn main_size(self, direction: StackDirection) -> f64 {
        match direction {
            StackDirection::Row => self.width,
            StackDirection::Column => self.height,
        }
    }

    const fn main_leading_margin(self, direction: StackDirection) -> f64 {
        match direction {
            StackDirection::Row => self.margin_left,
            StackDirection::Column => self.margin_top,
        }
    }

    const fn main_trailing_margin(self, direction: StackDirection) -> f64 {
        match direction {
            StackDirection::Row => self.margin_right,
            StackDirection::Column => self.margin_bottom,
        }
    }

    const fn cross_size(self, direction: StackDirection) -> f64 {
        match direction {
            StackDirection::Row => self.height,
            StackDirection::Column => self.width,
        }
    }

    const fn cross_leading_margin(self, direction: StackDirection) -> f64 {
        match direction {
            StackDirection::Row => self.margin_top,
            StackDirection::Column => self.margin_left,
        }
    }

    const fn cross_trailing_margin(self, direction: StackDirection) -> f64 {
        match direction {
            StackDirection::Row => self.margin_bottom,
            StackDirection::Column => self.margin_right,
        }
    }

    const fn with_main_size(mut self, direction: StackDirection, size: f64) -> Self {
        match direction {
            StackDirection::Row => self.width = size,
            StackDirection::Column => self.height = size,
        }
        self
    }

    const fn with_cross_size(mut self, direction: StackDirection, size: f64) -> Self {
        match direction {
            StackDirection::Row => self.height = size,
            StackDirection::Column => self.width = size,
        }
        self
    }
}

#[derive(Clone, Copy, Debug)]
struct StackMeasurementSpace {
    width: Option<f64>,
    height: Option<f64>,
}

impl StackMeasurementSpace {
    const fn definite(content_box: &LocalLayoutBox) -> Self {
        Self {
            width: Some(content_box.width),
            height: Some(content_box.height),
        }
    }

    const fn cross_hug(direction: StackDirection, main_available: f64) -> Self {
        match direction {
            StackDirection::Row => Self {
                width: Some(main_available),
                height: None,
            },
            StackDirection::Column => Self {
                width: None,
                height: Some(main_available),
            },
        }
    }

    const fn main_hug(direction: StackDirection, cross_available: f64) -> Self {
        match direction {
            StackDirection::Row => Self {
                width: None,
                height: Some(cross_available),
            },
            StackDirection::Column => Self {
                width: Some(cross_available),
                height: None,
            },
        }
    }

    const fn main_available(self, direction: StackDirection) -> Option<f64> {
        match direction {
            StackDirection::Row => self.width,
            StackDirection::Column => self.height,
        }
    }
}

struct ResolvedStackChildren {
    direction: StackDirection,
    gap: f64,
    distribution: StackDistribution,
    measurements: Vec<Result<StackChildMeasurement, DefiniteLayoutError>>,
}

#[derive(Debug)]
struct DefiniteGridAxis {
    origins: Vec<f64>,
    sizes: Vec<f64>,
    gap: f64,
}

impl DefiniteGridAxis {
    fn cell(&self, start: usize, span: usize) -> (f64, f64) {
        let mut size = 0.0;
        for index in start..start + span {
            size += self.sizes[index];
            if index + 1 < start + span {
                size += self.gap;
            }
        }
        (self.origins[start], size)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum TrackKind {
    Fixed,
    Fraction,
    Auto,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum GridAxis {
    Column,
    Row,
}

#[derive(Clone, Copy, Debug)]
enum GridAxisMeasurementSpace<'a> {
    Independent,
    RowsAfterColumns(&'a DefiniteGridAxis),
}

impl GridAxis {
    const fn tracks_member(self) -> &'static str {
        match self {
            Self::Column => "columns",
            Self::Row => "rows",
        }
    }

    const fn gap_member(self) -> &'static str {
        match self {
            Self::Column => "columnGapPt",
            Self::Row => "rowGapPt",
        }
    }

    const fn start_member(self) -> &'static str {
        match self {
            Self::Column => "column",
            Self::Row => "row",
        }
    }

    const fn span_member(self) -> &'static str {
        match self {
            Self::Column => "columnSpan",
            Self::Row => "rowSpan",
        }
    }

    const fn mode_member(self) -> &'static str {
        match self {
            Self::Column => "widthMode",
            Self::Row => "heightMode",
        }
    }

    const fn size_member(self) -> &'static str {
        match self {
            Self::Column => "widthPt",
            Self::Row => "heightPt",
        }
    }

    const fn hug_axis(self) -> &'static str {
        match self {
            Self::Column => "Width",
            Self::Row => "Height",
        }
    }

    const fn leading_margin_member(self) -> &'static str {
        match self {
            Self::Column => "marginLeftPt",
            Self::Row => "marginTopPt",
        }
    }

    const fn trailing_margin_member(self) -> &'static str {
        match self {
            Self::Column => "marginRightPt",
            Self::Row => "marginBottomPt",
        }
    }
}

#[derive(Clone, Debug)]
struct GridAutoConstraint {
    start: usize,
    span: usize,
    materialized_order: usize,
    auto_indices: Vec<usize>,
    contribution: f64,
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

pub fn layout_definite_resource_free(
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
        state.visit_absolute_node(object(child, occurrence, "children")?, &canvas_box)?;
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
    fn visit_absolute_node(
        &mut self,
        node: &Map<String, Value>,
        parent_content: &LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(node)?;
        let kind = text_member(node, "kind", occurrence, "kind")?;
        let role = definite_node_role(kind, occurrence)?;

        let placement = object_member(Some(node), "placement", occurrence)?;
        if text_member(placement, "type", occurrence, "placement.type")? != "ABSOLUTE" {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::NonAbsolutePlacement,
            ));
        }
        let width_mode = size_mode(placement, "widthMode", occurrence)?;
        let height_mode = size_mode(placement, "heightMode", occurrence)?;
        let authored_x = binary64_member(placement, "xPt", occurrence, "placement.xPt")?;
        let authored_y = binary64_member(placement, "yPt", occurrence, "placement.yPt")?;
        let width = if width_mode == SizeMode::Hug {
            resource_free_hug_axis(
                node,
                role,
                placement,
                "Width",
                occurrence,
                Some(HugOppositeAxisOffer::AbsoluteParentContent(
                    parent_content.height,
                )),
            )?
        } else {
            definite_axis_size(
                placement,
                width_mode,
                parent_content.width,
                authored_x,
                "Width",
                "rightInsetPt",
                occurrence,
            )?
        };
        let height = if height_mode == SizeMode::Hug {
            resource_free_hug_axis(
                node,
                role,
                placement,
                "Height",
                occurrence,
                Some(HugOppositeAxisOffer::AbsoluteParentContent(
                    parent_content.width,
                )),
            )?
        } else {
            definite_axis_size(
                placement,
                height_mode,
                parent_content.height,
                authored_y,
                "Height",
                "bottomInsetPt",
                occurrence,
            )?
        };
        let layout_box = LocalLayoutBox {
            x: parent_content.x + authored_x,
            y: parent_content.y + authored_y,
            width,
            height,
        };
        self.emit_positioned_node(node, kind, role, layout_box)
    }

    fn visit_stack_child(
        &mut self,
        node: &Map<String, Value>,
        measurement: Result<StackChildMeasurement, DefiniteLayoutError>,
        layout_box: LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(node)?;
        let kind = text_member(node, "kind", occurrence, "kind")?;
        let role = definite_node_role(kind, occurrence)?;
        measurement?;
        self.emit_positioned_node(node, kind, role, layout_box)
    }

    fn emit_positioned_node(
        &mut self,
        node: &Map<String, Value>,
        kind: &str,
        role: NodeRole,
        layout_box: LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(node)?;
        match role {
            NodeRole::Frame | NodeRole::Stack | NodeRole::Grid => {
                let content_box = container_content_box(node, &layout_box, occurrence)?;
                self.entries.push(DefiniteLayoutEntry {
                    occurrence_id: occurrence.to_owned(),
                    kind: kind.to_owned(),
                    layout_box,
                    content_box: Some(content_box),
                });
                match role {
                    NodeRole::Frame => {
                        for child in array_member(node, "children", occurrence)? {
                            self.visit_absolute_node(
                                object(child, occurrence, "children")?,
                                &content_box,
                            )?;
                        }
                    }
                    NodeRole::Stack => self.visit_stack_children(node, &content_box)?,
                    NodeRole::Grid => self.visit_grid_children(node, &content_box)?,
                    NodeRole::Group | NodeRole::Leaf => unreachable!(),
                }
            }
            NodeRole::Group => {
                self.entries.push(DefiniteLayoutEntry {
                    occurrence_id: occurrence.to_owned(),
                    kind: kind.to_owned(),
                    layout_box,
                    content_box: None,
                });
                self.visit_group_children(node, &layout_box)?;
            }
            NodeRole::Leaf => {
                self.entries.push(DefiniteLayoutEntry {
                    occurrence_id: occurrence.to_owned(),
                    kind: kind.to_owned(),
                    layout_box,
                    content_box: None,
                });
            }
        }
        Ok(())
    }

    fn visit_group_children(
        &mut self,
        group: &Map<String, Value>,
        layout_box: &LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(group)?;
        let children = array_member(group, "children", occurrence)?;
        if children.is_empty() {
            return Ok(());
        }
        let horizontal = resource_free_group_hug_axis_union(group, "Width", occurrence)?;
        let vertical = resource_free_group_hug_axis_union(group, "Height", occurrence)?;
        let normalized_parent = LocalLayoutBox {
            x: finite_group_normalization_value(layout_box.x - horizontal.minimum, occurrence)?,
            y: finite_group_normalization_value(layout_box.y - vertical.minimum, occurrence)?,
            width: layout_box.width,
            height: layout_box.height,
        };
        for child in children {
            self.visit_absolute_node(object(child, occurrence, "children")?, &normalized_parent)?;
        }
        Ok(())
    }

    fn visit_stack_children(
        &mut self,
        stack: &Map<String, Value>,
        content_box: &LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(stack)?;
        let children = array_member(stack, "children", occurrence)?;
        let resolved = measure_and_allocate_stack_children(
            stack,
            StackMeasurementSpace::definite(content_box),
        )?;
        let mut cursor = resolved.distribution.leading;

        for (index, (child, measured)) in children.iter().zip(resolved.measurements).enumerate() {
            let child = object(child, occurrence, "children")?;
            let layout_box = if let Ok(measurement) = &measured {
                cursor += measurement.main_leading_margin(resolved.direction);
                let layout_box =
                    stack_child_box(content_box, *measurement, resolved.direction, cursor);
                cursor += measurement.main_size(resolved.direction);
                cursor += measurement.main_trailing_margin(resolved.direction);
                if index + 1 < children.len() {
                    cursor += resolved.gap;
                    cursor += resolved.distribution.between[index];
                }
                layout_box
            } else {
                LocalLayoutBox {
                    x: content_box.x,
                    y: content_box.y,
                    width: 0.0,
                    height: 0.0,
                }
            };
            self.visit_stack_child(child, measured, layout_box)?;
        }
        Ok(())
    }

    fn visit_grid_children(
        &mut self,
        grid: &Map<String, Value>,
        content_box: &LocalLayoutBox,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(grid)?;
        let children = array_member(grid, "children", occurrence)?;
        // The frozen profile always solves columns first, then rows.
        let columns = definite_grid_axis(
            grid,
            children,
            GridAxis::Column,
            content_box.x,
            content_box.width,
            GridAxisMeasurementSpace::Independent,
            occurrence,
        )?;
        let rows = definite_grid_axis(
            grid,
            children,
            GridAxis::Row,
            content_box.y,
            content_box.height,
            GridAxisMeasurementSpace::RowsAfterColumns(&columns),
            occurrence,
        )?;
        for child in children {
            self.visit_grid_child(object(child, occurrence, "children")?, &columns, &rows)?;
        }
        Ok(())
    }

    fn visit_grid_child(
        &mut self,
        node: &Map<String, Value>,
        columns: &DefiniteGridAxis,
        rows: &DefiniteGridAxis,
    ) -> Result<(), DefiniteLayoutError> {
        let occurrence = occurrence_id(node)?;
        let kind = text_member(node, "kind", occurrence, "kind")?;
        let role = definite_node_role(kind, occurrence)?;
        let placement = object_member(Some(node), "placement", occurrence)?;
        if text_member(placement, "type", occurrence, "placement.type")? != "GRID" {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::NonAbsolutePlacement,
            ));
        }
        let width_mode = size_mode(placement, "widthMode", occurrence)?;
        let height_mode = size_mode(placement, "heightMode", occurrence)?;
        let column = integer_member(placement, "column", occurrence, "placement.column")?;
        let column_span =
            integer_member(placement, "columnSpan", occurrence, "placement.columnSpan")?;
        let row = integer_member(placement, "row", occurrence, "placement.row")?;
        let row_span = integer_member(placement, "rowSpan", occurrence, "placement.rowSpan")?;
        let (cell_x, cell_width) = columns.cell(column, column_span);
        let (cell_y, cell_height) = rows.cell(row, row_span);

        let margin_top = binary64_member(
            placement,
            "marginTopPt",
            occurrence,
            "placement.marginTopPt",
        )?;
        let margin_right = binary64_member(
            placement,
            "marginRightPt",
            occurrence,
            "placement.marginRightPt",
        )?;
        let margin_bottom = binary64_member(
            placement,
            "marginBottomPt",
            occurrence,
            "placement.marginBottomPt",
        )?;
        let margin_left = binary64_member(
            placement,
            "marginLeftPt",
            occurrence,
            "placement.marginLeftPt",
        )?;
        let resolved_width_fill = resolved_grid_fill_outer_size(
            placement,
            width_mode,
            cell_width,
            margin_left,
            margin_right,
            "Width",
            occurrence,
        )?;
        let resolved_height_fill = resolved_grid_fill_outer_size(
            placement,
            height_mode,
            cell_height,
            margin_top,
            margin_bottom,
            "Height",
            occurrence,
        )?;
        let consumes_resolved_width_offer =
            grid_row_hug_consumes_resolved_width_offer(node, role, placement, occurrence)?;
        let (x, width) = grid_axis_arrangement(
            node,
            role,
            placement,
            width_mode,
            cell_x,
            cell_width,
            margin_left,
            margin_right,
            "Width",
            "horizontalAlignSelf",
            resolved_width_fill,
            if role == NodeRole::Frame && width_mode == SizeMode::Hug {
                resolved_height_fill
            } else {
                None
            },
            occurrence,
        )?;
        let (y, height) = grid_axis_arrangement(
            node,
            role,
            placement,
            height_mode,
            cell_y,
            cell_height,
            margin_top,
            margin_bottom,
            "Height",
            "verticalAlignSelf",
            resolved_height_fill,
            if consumes_resolved_width_offer {
                resolved_width_fill
            } else {
                None
            },
            occurrence,
        )?;
        self.emit_positioned_node(
            node,
            kind,
            role,
            LocalLayoutBox {
                x,
                y,
                width,
                height,
            },
        )
    }
}

fn definite_grid_axis(
    grid: &Map<String, Value>,
    children: &[Value],
    axis: GridAxis,
    origin: f64,
    available: f64,
    measurement_space: GridAxisMeasurementSpace<'_>,
    occurrence: &str,
) -> Result<DefiniteGridAxis, DefiniteLayoutError> {
    let tracks_member = axis.tracks_member();
    let gap_member = axis.gap_member();
    let gap = nonnegative_binary64_member(grid, gap_member, occurrence, gap_member)?;
    let tracks = array_member(grid, tracks_member, occurrence)?;
    let mut sizes = Vec::with_capacity(tracks.len());
    let mut auto_indices = Vec::new();
    let mut fraction_tracks = Vec::new();

    for (index, track) in tracks.iter().enumerate() {
        let track = object(track, occurrence, tracks_member)?;
        let track_type = text_member(
            track,
            "type",
            occurrence,
            format!("{tracks_member}[{index}].type"),
        )?;
        match track_type {
            "FIXED" => {
                let size = binary64_member(
                    track,
                    "valuePt",
                    occurrence,
                    format!("{tracks_member}[{index}].valuePt"),
                )?;
                sizes.push(size);
            }
            "AUTO" => {
                auto_indices.push(index);
                sizes.push(0.0);
            }
            "FRACTION" => {
                let weight = binary64_member(
                    track,
                    "weight",
                    occurrence,
                    format!("{tracks_member}[{index}].weight"),
                )?;
                fraction_tracks.push((index, weight));
                sizes.push(0.0);
            }
            _ => {
                return Err(DefiniteLayoutError::invariant(
                    occurrence,
                    format!("{tracks_member}[{index}].type"),
                ));
            }
        }
    }

    // Track solving is staged by the frozen Profile: FIXED, then AUTO, then FRACTION.
    // The complete authored scan above makes that stage order independent of track order.
    if !auto_indices.is_empty() {
        apply_grid_auto_constraints(
            children,
            axis,
            &auto_indices,
            &mut sizes,
            gap,
            measurement_space,
            occurrence,
        )?;
    }
    if let Some((first_index, _)) = fraction_tracks.first().copied() {
        let used_without_fraction = grid_span_extent(&sizes, gap, 0, sizes.len());
        let remaining = available - used_without_fraction;
        let remaining = if remaining > 0.0 { remaining } else { 0.0 };
        if fraction_tracks.len() == 1 {
            sizes[first_index] = remaining;
        } else {
            let mut total_weight = 0.0;
            for (_, weight) in &fraction_tracks {
                total_weight += weight;
                if !total_weight.is_finite() {
                    return Err(DefiniteLayoutError::unsupported(
                        occurrence,
                        DefiniteLayoutUnsupported::GridFractionTrack,
                    ));
                }
            }

            let mut allocated_before_last = 0.0;
            let last_position = fraction_tracks.len() - 1;
            for (position, (index, weight)) in fraction_tracks.iter().copied().enumerate() {
                let share = if position == last_position {
                    remaining - allocated_before_last
                } else {
                    remaining * weight / total_weight
                };
                if !share.is_finite() || share < 0.0 {
                    return Err(DefiniteLayoutError::unsupported(
                        occurrence,
                        DefiniteLayoutUnsupported::GridFractionTrack,
                    ));
                }
                sizes[index] = if share > 0.0 { share } else { 0.0 };
                if position != last_position {
                    allocated_before_last += share;
                    if !allocated_before_last.is_finite() {
                        return Err(DefiniteLayoutError::unsupported(
                            occurrence,
                            DefiniteLayoutUnsupported::GridFractionTrack,
                        ));
                    }
                }
            }
        }
    }

    let mut origins = Vec::with_capacity(tracks.len());
    let mut cursor = origin;
    for (index, size) in sizes.iter().enumerate() {
        origins.push(cursor);
        cursor += size;
        if index + 1 < sizes.len() {
            cursor += gap;
        }
    }

    Ok(DefiniteGridAxis {
        origins,
        sizes,
        gap,
    })
}

fn apply_grid_auto_constraints(
    children: &[Value],
    axis: GridAxis,
    auto_indices: &[usize],
    sizes: &mut [f64],
    gap: f64,
    measurement_space: GridAxisMeasurementSpace<'_>,
    grid_occurrence: &str,
) -> Result<(), DefiniteLayoutError> {
    let mut constraints = Vec::new();
    for (materialized_order, child) in children.iter().enumerate() {
        let child = object(child, grid_occurrence, "children")?;
        let child_occurrence = occurrence_id(child)?;
        let placement = object_member(Some(child), "placement", child_occurrence)?;
        if text_member(placement, "type", child_occurrence, "placement.type")? != "GRID" {
            return Err(DefiniteLayoutError::unsupported(
                child_occurrence,
                DefiniteLayoutUnsupported::NonAbsolutePlacement,
            ));
        }

        let start = integer_member(
            placement,
            axis.start_member(),
            child_occurrence,
            &format!("placement.{}", axis.start_member()),
        )?;
        let span = integer_member(
            placement,
            axis.span_member(),
            child_occurrence,
            &format!("placement.{}", axis.span_member()),
        )?;
        let covered_auto_indices = auto_indices
            .iter()
            .copied()
            .filter(|index| *index >= start && *index < start + span)
            .collect::<Vec<_>>();
        if covered_auto_indices.is_empty() {
            continue;
        }

        let mode = size_mode(placement, axis.mode_member(), child_occurrence)?;
        let size = match mode {
            SizeMode::Fixed => binary64_member(
                placement,
                axis.size_member(),
                child_occurrence,
                format!("placement.{}", axis.size_member()),
            )?,
            SizeMode::Hug => {
                let kind = text_member(child, "kind", child_occurrence, "kind")?;
                let role = definite_node_role(kind, child_occurrence)?;
                let opposite_axis_offer = grid_auto_hug_opposite_axis_offer(
                    child,
                    placement,
                    role,
                    axis,
                    measurement_space,
                    child_occurrence,
                )?;
                resource_free_hug_axis(
                    child,
                    role,
                    placement,
                    axis.hug_axis(),
                    child_occurrence,
                    opposite_axis_offer,
                )?
            }
            SizeMode::Fill => {
                return Err(DefiniteLayoutError::invariant(
                    child_occurrence,
                    format!("placement.{}", axis.mode_member()),
                ));
            }
        };
        let leading_margin = binary64_member(
            placement,
            axis.leading_margin_member(),
            child_occurrence,
            format!("placement.{}", axis.leading_margin_member()),
        )?;
        let trailing_margin = binary64_member(
            placement,
            axis.trailing_margin_member(),
            child_occurrence,
            format!("placement.{}", axis.trailing_margin_member()),
        )?;
        let contribution = (size + leading_margin) + trailing_margin;
        constraints.push(GridAutoConstraint {
            start,
            span,
            materialized_order,
            auto_indices: covered_auto_indices,
            contribution: if contribution > 0.0 {
                contribution
            } else {
                0.0
            },
        });
    }

    constraints.sort_by_key(|constraint| {
        (
            constraint.span,
            constraint.start,
            constraint.materialized_order,
        )
    });
    for constraint in constraints {
        let occupied = grid_span_extent(sizes, gap, constraint.start, constraint.span);
        let deficit = constraint.contribution - occupied;
        if deficit > 0.0 {
            apply_grid_auto_deficit(sizes, &constraint.auto_indices, deficit, grid_occurrence)?;
        }
    }
    Ok(())
}

fn apply_grid_auto_deficit(
    sizes: &mut [f64],
    auto_indices: &[usize],
    deficit: f64,
    grid_occurrence: &str,
) -> Result<(), DefiniteLayoutError> {
    if !deficit.is_finite() {
        return Err(DefiniteLayoutError::unsupported(
            grid_occurrence,
            DefiniteLayoutUnsupported::GridAutoTrack,
        ));
    }
    if auto_indices.len() == 1 {
        let index = auto_indices[0];
        let updated = sizes[index] + deficit;
        if !updated.is_finite() {
            return Err(DefiniteLayoutError::unsupported(
                grid_occurrence,
                DefiniteLayoutUnsupported::GridAutoTrack,
            ));
        }
        sizes[index] = if updated > 0.0 { updated } else { 0.0 };
        return Ok(());
    }

    let equal_share = deficit / auto_indices.len() as f64;
    let mut allocated_before_last = 0.0;
    let mut updates = Vec::with_capacity(auto_indices.len());
    let last_position = auto_indices.len() - 1;
    for (position, index) in auto_indices.iter().copied().enumerate() {
        let share = if position == last_position {
            deficit - allocated_before_last
        } else {
            equal_share
        };
        let updated = sizes[index] + share;
        if !share.is_finite() || share < 0.0 || !updated.is_finite() {
            return Err(DefiniteLayoutError::unsupported(
                grid_occurrence,
                DefiniteLayoutUnsupported::GridAutoTrack,
            ));
        }
        updates.push((index, if updated > 0.0 { updated } else { 0.0 }));
        if position != last_position {
            allocated_before_last += share;
            if !allocated_before_last.is_finite() {
                return Err(DefiniteLayoutError::unsupported(
                    grid_occurrence,
                    DefiniteLayoutUnsupported::GridAutoTrack,
                ));
            }
        }
    }
    for (index, updated) in updates {
        sizes[index] = updated;
    }
    Ok(())
}

fn grid_auto_hug_opposite_axis_offer(
    node: &Map<String, Value>,
    placement: &Map<String, Value>,
    role: NodeRole,
    axis: GridAxis,
    measurement_space: GridAxisMeasurementSpace<'_>,
    occurrence: &str,
) -> Result<Option<HugOppositeAxisOffer>, DefiniteLayoutError> {
    let GridAxisMeasurementSpace::RowsAfterColumns(columns) = measurement_space else {
        return Ok(None);
    };
    if axis != GridAxis::Row
        || !grid_row_hug_consumes_resolved_width_offer(node, role, placement, occurrence)?
    {
        return Ok(None);
    }
    let column = integer_member(placement, "column", occurrence, "placement.column")?;
    let column_span = integer_member(placement, "columnSpan", occurrence, "placement.columnSpan")?;
    let (_, cell_width) = columns.cell(column, column_span);
    let margin_left = binary64_member(
        placement,
        "marginLeftPt",
        occurrence,
        "placement.marginLeftPt",
    )?;
    let margin_right = binary64_member(
        placement,
        "marginRightPt",
        occurrence,
        "placement.marginRightPt",
    )?;
    let outer_width = stack_axis_size(
        placement,
        SizeMode::Fill,
        cell_width,
        margin_left,
        margin_right,
        "Width",
        occurrence,
    )?;
    Ok(Some(HugOppositeAxisOffer::ResolvedOuter(outer_width)))
}

fn grid_row_hug_consumes_resolved_width_offer(
    node: &Map<String, Value>,
    role: NodeRole,
    placement: &Map<String, Value>,
    occurrence: &str,
) -> Result<bool, DefiniteLayoutError> {
    if size_mode(placement, "widthMode", occurrence)? != SizeMode::Fill
        || size_mode(placement, "heightMode", occurrence)? != SizeMode::Hug
    {
        return Ok(false);
    }
    match role {
        NodeRole::Frame | NodeRole::Grid => Ok(true),
        NodeRole::Stack => Ok(stack_direction(node, occurrence)? == StackDirection::Row),
        NodeRole::Group | NodeRole::Leaf => Ok(false),
    }
}

fn grid_span_extent(sizes: &[f64], gap: f64, start: usize, span: usize) -> f64 {
    let mut extent = 0.0;
    for (offset, size) in sizes.iter().skip(start).take(span).enumerate() {
        extent += size;
        if offset + 1 < span {
            extent += gap;
        }
    }
    extent
}

#[allow(clippy::too_many_arguments)]
fn grid_axis_arrangement(
    node: &Map<String, Value>,
    role: NodeRole,
    placement: &Map<String, Value>,
    mode: SizeMode,
    cell_origin: f64,
    cell_size: f64,
    leading_margin: f64,
    trailing_margin: f64,
    axis: &str,
    alignment_member: &str,
    resolved_fill_outer: Option<f64>,
    opposite_fill_outer: Option<f64>,
    occurrence: &str,
) -> Result<(f64, f64), DefiniteLayoutError> {
    let size = match mode {
        SizeMode::Hug => resource_free_hug_axis(
            node,
            role,
            placement,
            axis,
            occurrence,
            opposite_fill_outer.map(HugOppositeAxisOffer::ResolvedOuter),
        )?,
        SizeMode::Fill => resolved_fill_outer.ok_or_else(|| {
            DefiniteLayoutError::invariant(occurrence, format!("resolvedGrid{axis}Fill"))
        })?,
        SizeMode::Fixed => stack_axis_size(
            placement,
            mode,
            cell_size,
            leading_margin,
            trailing_margin,
            axis,
            occurrence,
        )?,
    };
    let position = if matches!(mode, SizeMode::Fixed | SizeMode::Hug) {
        aligned_cross_position(
            cell_origin,
            cell_size,
            leading_margin,
            trailing_margin,
            size,
            stack_alignment(placement, alignment_member, occurrence)?,
        )
    } else if mode == SizeMode::Fill {
        cell_origin + leading_margin
    } else {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("placement.{axis}Mode"),
        ));
    };
    Ok((position, size))
}

#[allow(clippy::too_many_arguments)]
fn resolved_grid_fill_outer_size(
    placement: &Map<String, Value>,
    mode: SizeMode,
    cell_size: f64,
    leading_margin: f64,
    trailing_margin: f64,
    axis: &str,
    occurrence: &str,
) -> Result<Option<f64>, DefiniteLayoutError> {
    if mode != SizeMode::Fill {
        return Ok(None);
    }
    stack_axis_size(
        placement,
        mode,
        cell_size,
        leading_margin,
        trailing_margin,
        axis,
        occurrence,
    )
    .map(Some)
}

struct StackDistribution {
    leading: f64,
    between: Vec<f64>,
}

fn stack_distribution(
    justification: StackJustification,
    free: f64,
    child_count: usize,
) -> StackDistribution {
    let mut result = StackDistribution {
        leading: 0.0,
        between: vec![0.0; child_count.saturating_sub(1)],
    };
    match justification {
        StackJustification::Start => {}
        StackJustification::End => result.leading = free,
        StackJustification::Center => result.leading = free / 2.0,
        StackJustification::SpaceBetween if child_count > 1 => {
            result.between = equal_binary64_slots(free, child_count - 1);
        }
        StackJustification::SpaceBetween => {}
        StackJustification::SpaceAround if child_count > 0 => {
            let slots = equal_binary64_slots(free, child_count);
            result.leading = slots[0] / 2.0;
            for index in 0..result.between.len() {
                result.between[index] = (slots[index] / 2.0) + (slots[index + 1] / 2.0);
            }
        }
        StackJustification::SpaceAround => {}
        StackJustification::SpaceEvenly if child_count > 0 => {
            let slots = equal_binary64_slots(free, child_count + 1);
            result.leading = slots[0];
            let between_len = result.between.len();
            result.between.copy_from_slice(&slots[1..1 + between_len]);
        }
        StackJustification::SpaceEvenly => {}
    }
    result
}

fn equal_binary64_slots(total: f64, count: usize) -> Vec<f64> {
    if count == 0 {
        return Vec::new();
    }
    let unit = total / count as f64;
    let mut remaining = total;
    let mut result = Vec::with_capacity(count);
    for index in 0..count {
        let slot = if index + 1 == count { remaining } else { unit };
        result.push(slot);
        remaining -= slot;
    }
    result
}

fn stack_child_box(
    parent: &LocalLayoutBox,
    child: StackChildMeasurement,
    direction: StackDirection,
    main_position: f64,
) -> LocalLayoutBox {
    match direction {
        StackDirection::Row => LocalLayoutBox {
            x: parent.x + main_position,
            y: aligned_cross_position(
                parent.y,
                parent.height,
                child.margin_top,
                child.margin_bottom,
                child.height,
                child.align_self,
            ),
            width: child.width,
            height: child.height,
        },
        StackDirection::Column => LocalLayoutBox {
            x: aligned_cross_position(
                parent.x,
                parent.width,
                child.margin_left,
                child.margin_right,
                child.width,
                child.align_self,
            ),
            y: parent.y + main_position,
            width: child.width,
            height: child.height,
        },
    }
}

fn aligned_cross_position(
    parent_origin: f64,
    parent_size: f64,
    leading_margin: f64,
    trailing_margin: f64,
    child_size: f64,
    alignment: StackAlignment,
) -> f64 {
    let interval = (parent_size - leading_margin) - trailing_margin;
    let extra = match alignment {
        StackAlignment::Start => 0.0,
        StackAlignment::Center => (interval - child_size) / 2.0,
        StackAlignment::End => interval - child_size,
    };
    (parent_origin + leading_margin) + extra
}

fn measure_and_allocate_stack_children(
    stack: &Map<String, Value>,
    space: StackMeasurementSpace,
) -> Result<ResolvedStackChildren, DefiniteLayoutError> {
    let occurrence = occurrence_id(stack)?;
    let direction = stack_direction(stack, occurrence)?;
    let justification = stack_justification(stack, occurrence)?;
    let gap = nonnegative_binary64_member(stack, "gapPt", occurrence, "gapPt")?;
    let children = array_member(stack, "children", occurrence)?;
    let mut measurements = Vec::with_capacity(children.len());
    let mut used_without_fill = 0.0;
    let mut fill_indices = Vec::new();

    for (index, child) in children.iter().enumerate() {
        let child = object(child, occurrence, "children")?;
        if stack_child_has_main_fill(child, direction) {
            fill_indices.push(index);
        }
        let measured = measure_stack_child(child, space, direction);
        if let Ok(measurement) = &measured {
            used_without_fill += measurement.main_leading_margin(direction);
            if !measurement.main_fill {
                used_without_fill += measurement.main_size(direction);
            }
            used_without_fill += measurement.main_trailing_margin(direction);
        }
        if index + 1 < children.len() {
            used_without_fill += gap;
        }
        measurements.push(measured);
    }

    let available = space
        .main_available(direction)
        .ok_or_else(|| DefiniteLayoutError::invariant(occurrence, "stackMainOffer"))?;
    if fill_indices.len() > 1 {
        let first_fill = fill_indices[0];
        match stack_main_fill_allocations(
            children,
            &fill_indices,
            direction,
            available,
            used_without_fill,
            occurrence,
        ) {
            Ok(allocations) => {
                for (fill_index, size) in allocations {
                    if let Ok(measurement) = &measurements[fill_index] {
                        let child = object(&children[fill_index], occurrence, "children")?;
                        let measurement = measurement.with_main_size(direction, size);
                        measurements[fill_index] = remeasure_stack_child_cross_hug_after_main_fill(
                            child,
                            measurement,
                            direction,
                        );
                    }
                }
            }
            Err(error) if measurements[first_fill].is_ok() => {
                measurements[first_fill] = Err(error);
            }
            Err(_) => {}
        }
    } else if let Some(&fill_index) = fill_indices.first()
        && measurements[fill_index].is_ok()
    {
        let child = object(&children[fill_index], occurrence, "children")?;
        let child_occurrence = occurrence_id(child)?;
        let placement = object_member(Some(child), "placement", child_occurrence)?;
        let remaining = available - used_without_fill;
        let offered = if remaining > 0.0 { remaining } else { 0.0 };
        let size = clamp_flexible_axis(
            placement,
            offered,
            match direction {
                StackDirection::Row => "Width",
                StackDirection::Column => "Height",
            },
            child_occurrence,
        )?;
        if let Ok(measurement) = measurements[fill_index] {
            let measurement = measurement.with_main_size(direction, size);
            measurements[fill_index] =
                remeasure_stack_child_cross_hug_after_main_fill(child, measurement, direction);
        }
    }

    let mut occupied = used_without_fill;
    for fill_index in fill_indices {
        if let Ok(measurement) = &measurements[fill_index] {
            occupied += measurement.main_size(direction);
        }
    }
    let occupied = if occupied > 0.0 { occupied } else { 0.0 };
    let remaining = available - occupied;
    let free = if remaining > 0.0 { remaining } else { 0.0 };
    Ok(ResolvedStackChildren {
        direction,
        gap,
        distribution: stack_distribution(justification, free, children.len()),
        measurements,
    })
}

fn measure_stack_child(
    node: &Map<String, Value>,
    space: StackMeasurementSpace,
    direction: StackDirection,
) -> Result<StackChildMeasurement, DefiniteLayoutError> {
    let occurrence = occurrence_id(node)?;
    let kind = text_member(node, "kind", occurrence, "kind")?;
    let role = definite_node_role(kind, occurrence)?;
    let placement = object_member(Some(node), "placement", occurrence)?;
    if text_member(placement, "type", occurrence, "placement.type")? != "STACK" {
        return Err(DefiniteLayoutError::unsupported(
            occurrence,
            DefiniteLayoutUnsupported::NonAbsolutePlacement,
        ));
    }
    let width_mode = size_mode(placement, "widthMode", occurrence)?;
    let height_mode = size_mode(placement, "heightMode", occurrence)?;
    let main_mode = match direction {
        StackDirection::Row => width_mode,
        StackDirection::Column => height_mode,
    };
    let main_fill = main_mode == SizeMode::Fill;
    let deferred_role = match role {
        NodeRole::Frame => true,
        NodeRole::Stack => true,
        NodeRole::Grid => direction == StackDirection::Row,
        NodeRole::Group | NodeRole::Leaf => false,
    };
    let deferred_cross_hug_after_main_fill = deferred_role
        && matches!(
            (direction, width_mode, height_mode),
            (StackDirection::Row, SizeMode::Fill, SizeMode::Hug)
                | (StackDirection::Column, SizeMode::Hug, SizeMode::Fill)
        );

    let margin_top = binary64_member(
        placement,
        "marginTopPt",
        occurrence,
        "placement.marginTopPt",
    )?;
    let margin_right = binary64_member(
        placement,
        "marginRightPt",
        occurrence,
        "placement.marginRightPt",
    )?;
    let margin_bottom = binary64_member(
        placement,
        "marginBottomPt",
        occurrence,
        "placement.marginBottomPt",
    )?;
    let margin_left = binary64_member(
        placement,
        "marginLeftPt",
        occurrence,
        "placement.marginLeftPt",
    )?;
    let resolved_cross_outer_offer = match (role, direction, width_mode, height_mode) {
        (NodeRole::Frame | NodeRole::Stack, StackDirection::Row, SizeMode::Hug, SizeMode::Fill) => {
            Some(stack_axis_size_from_offer(
                placement,
                SizeMode::Fill,
                space.height,
                margin_top,
                margin_bottom,
                "Height",
                occurrence,
            )?)
        }
        (
            NodeRole::Frame | NodeRole::Stack | NodeRole::Grid,
            StackDirection::Column,
            SizeMode::Fill,
            SizeMode::Hug,
        ) => Some(stack_axis_size_from_offer(
            placement,
            SizeMode::Fill,
            space.width,
            margin_left,
            margin_right,
            "Width",
            occurrence,
        )?),
        _ => None,
    };
    let width = if width_mode == SizeMode::Hug
        && deferred_cross_hug_after_main_fill
        && direction == StackDirection::Column
    {
        0.0
    } else if width_mode == SizeMode::Hug {
        let offer = if direction == StackDirection::Row {
            resolved_cross_outer_offer.map(HugOppositeAxisOffer::ResolvedOuter)
        } else {
            None
        };
        resource_free_hug_axis(node, role, placement, "Width", occurrence, offer)?
    } else if direction == StackDirection::Row && main_fill {
        0.0
    } else if direction == StackDirection::Column
        && width_mode == SizeMode::Fill
        && let Some(size) = resolved_cross_outer_offer
    {
        size
    } else {
        stack_axis_size_from_offer(
            placement,
            width_mode,
            space.width,
            margin_left,
            margin_right,
            "Width",
            occurrence,
        )?
    };
    let height = if height_mode == SizeMode::Hug
        && deferred_cross_hug_after_main_fill
        && direction == StackDirection::Row
    {
        0.0
    } else if height_mode == SizeMode::Hug {
        let offer = if direction == StackDirection::Column {
            resolved_cross_outer_offer.map(HugOppositeAxisOffer::ResolvedOuter)
        } else {
            None
        };
        resource_free_hug_axis(node, role, placement, "Height", occurrence, offer)?
    } else if direction == StackDirection::Column && main_fill {
        0.0
    } else if direction == StackDirection::Row
        && height_mode == SizeMode::Fill
        && let Some(size) = resolved_cross_outer_offer
    {
        size
    } else {
        stack_axis_size_from_offer(
            placement,
            height_mode,
            space.height,
            margin_top,
            margin_bottom,
            "Height",
            occurrence,
        )?
    };
    Ok(StackChildMeasurement {
        width,
        height,
        margin_top,
        margin_right,
        margin_bottom,
        margin_left,
        align_self: stack_alignment(placement, "alignSelf", occurrence)?,
        main_fill,
        deferred_cross_hug_after_main_fill,
    })
}

fn remeasure_stack_child_cross_hug_after_main_fill(
    node: &Map<String, Value>,
    measurement: StackChildMeasurement,
    direction: StackDirection,
) -> Result<StackChildMeasurement, DefiniteLayoutError> {
    if !measurement.deferred_cross_hug_after_main_fill {
        return Ok(measurement);
    }
    let occurrence = occurrence_id(node)?;
    let kind = text_member(node, "kind", occurrence, "kind")?;
    let role = definite_node_role(kind, occurrence)?;
    let placement = object_member(Some(node), "placement", occurrence)?;
    let cross_axis = match direction {
        StackDirection::Row => "Height",
        StackDirection::Column => "Width",
    };
    let cross_size = resource_free_hug_axis(
        node,
        role,
        placement,
        cross_axis,
        occurrence,
        Some(HugOppositeAxisOffer::ResolvedOuter(
            measurement.main_size(direction),
        )),
    )?;
    Ok(measurement.with_cross_size(direction, cross_size))
}

fn stack_child_has_main_fill(node: &Map<String, Value>, direction: StackDirection) -> bool {
    let Some(placement) = node.get("placement").and_then(Value::as_object) else {
        return false;
    };
    if placement.get("type").and_then(Value::as_str) != Some("STACK") {
        return false;
    }
    let member = match direction {
        StackDirection::Row => "widthMode",
        StackDirection::Column => "heightMode",
    };
    placement.get(member).and_then(Value::as_str) == Some("FILL")
}

fn stack_main_fill_allocations(
    children: &[Value],
    fill_indices: &[usize],
    direction: StackDirection,
    available: f64,
    used_without_fill: f64,
    stack_occurrence: &str,
) -> Result<Vec<(usize, f64)>, DefiniteLayoutError> {
    let first_child = object(&children[fill_indices[0]], stack_occurrence, "children")?;
    let first_occurrence = occurrence_id(first_child)?;
    let axis = match direction {
        StackDirection::Row => "Width",
        StackDirection::Column => "Height",
    };
    let minimum_member = format!("min{axis}Pt");
    let maximum_member = format!("max{axis}Pt");
    let mut weighted_indices = Vec::with_capacity(fill_indices.len());
    let mut total_weight = 0.0;

    for &fill_index in fill_indices {
        let child = object(&children[fill_index], stack_occurrence, "children")?;
        let child_occurrence = occurrence_id(child)?;
        let placement = object_member(Some(child), "placement", child_occurrence)?;
        let weight = binary64_member(
            placement,
            "fillWeight",
            child_occurrence,
            "placement.fillWeight",
        )?;
        total_weight += weight;
        if !weight.is_finite() || weight <= 0.0 || !total_weight.is_finite() || total_weight <= 0.0
        {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        weighted_indices.push((fill_index, weight));
    }

    let residual = available - used_without_fill;
    if !residual.is_finite() {
        return Err(DefiniteLayoutError::unsupported(
            first_occurrence,
            DefiniteLayoutUnsupported::StackMainFill,
        ));
    }
    let remaining = if residual > 0.0 { residual } else { 0.0 };
    let mut allocated_before_last = 0.0;
    let mut allocations = Vec::with_capacity(weighted_indices.len());
    let last_position = weighted_indices.len() - 1;
    for (position, &(fill_index, weight)) in weighted_indices.iter().enumerate() {
        let share = if position == last_position {
            remaining - allocated_before_last
        } else {
            remaining * weight / total_weight
        };
        if !share.is_finite() || share < 0.0 {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        allocations.push((fill_index, if share > 0.0 { share } else { 0.0 }));
        if position != last_position {
            allocated_before_last += share;
            if !allocated_before_last.is_finite() {
                return Err(DefiniteLayoutError::unsupported(
                    first_occurrence,
                    DefiniteLayoutUnsupported::StackMainFill,
                ));
            }
        }
    }
    let mut bounds = Vec::with_capacity(allocations.len());
    let mut active_bound = None;
    for (position, &(fill_index, share)) in allocations.iter().enumerate() {
        let child = object(&children[fill_index], stack_occurrence, "children")?;
        let child_occurrence = occurrence_id(child)?;
        let placement = object_member(Some(child), "placement", child_occurrence)?;
        let minimum = optional_binary64_member(
            placement,
            &minimum_member,
            child_occurrence,
            format!("placement.{minimum_member}"),
        )?;
        let maximum = optional_binary64_member(
            placement,
            &maximum_member,
            child_occurrence,
            format!("placement.{maximum_member}"),
        )?;
        let hit = if let Some(bound) = minimum.filter(|bound| share < *bound) {
            Some((bound, true))
        } else {
            maximum
                .filter(|bound| share > *bound)
                .map(|bound| (bound, false))
        };
        if let Some((bound, is_minimum)) = hit {
            if active_bound.is_some() {
                return Err(DefiniteLayoutError::unsupported(
                    first_occurrence,
                    DefiniteLayoutUnsupported::StackMainFill,
                ));
            }
            active_bound = Some((position, bound, is_minimum));
        }
        bounds.push((minimum, maximum));
    }

    let Some((active_position, frozen_bound, active_is_minimum)) = active_bound else {
        return Ok(allocations);
    };
    if !frozen_bound.is_finite() || frozen_bound < 0.0 {
        return Err(DefiniteLayoutError::unsupported(
            first_occurrence,
            DefiniteLayoutUnsupported::StackMainFill,
        ));
    }
    let (minimum, maximum) = bounds[active_position];
    if minimum.is_some_and(|bound| frozen_bound < bound)
        || maximum.is_some_and(|bound| frozen_bound > bound)
    {
        return Err(DefiniteLayoutError::unsupported(
            first_occurrence,
            DefiniteLayoutUnsupported::StackMainFill,
        ));
    }

    if allocations.len() == 3 {
        if frozen_bound > remaining {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        let mut unfrozen_positions = Vec::with_capacity(2);
        for position in 0..bounds.len() {
            if position == active_position {
                continue;
            }
            unfrozen_positions.push(position);
        }
        let redistributed_remaining = remaining - frozen_bound;
        let redistributed_weight =
            weighted_indices[unfrozen_positions[0]].1 + weighted_indices[unfrozen_positions[1]].1;
        if !redistributed_remaining.is_finite()
            || redistributed_remaining < 0.0
            || !redistributed_weight.is_finite()
            || redistributed_weight <= 0.0
        {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        let first_share = redistributed_remaining * weighted_indices[unfrozen_positions[0]].1
            / redistributed_weight;
        let second_share = redistributed_remaining - first_share;
        if !first_share.is_finite()
            || first_share < 0.0
            || !second_share.is_finite()
            || second_share < 0.0
        {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        let redistributed_shares = [
            (unfrozen_positions[0], first_share),
            (unfrozen_positions[1], second_share),
        ];
        let mut redistributed_active = None;
        for (position, share) in redistributed_shares {
            let (minimum, maximum) = bounds[position];
            if minimum.is_some_and(|bound| !bound.is_finite() || bound < 0.0)
                || maximum.is_some_and(|bound| !bound.is_finite() || bound < 0.0)
            {
                return Err(DefiniteLayoutError::unsupported(
                    first_occurrence,
                    DefiniteLayoutUnsupported::StackMainFill,
                ));
            }
            let hit = if let Some(bound) = minimum.filter(|bound| share < *bound) {
                Some((bound, true))
            } else {
                maximum
                    .filter(|bound| share > *bound)
                    .map(|bound| (bound, false))
            };
            if let Some((bound, is_minimum)) = hit {
                if redistributed_active.is_some() {
                    return Err(DefiniteLayoutError::unsupported(
                        first_occurrence,
                        DefiniteLayoutUnsupported::StackMainFill,
                    ));
                }
                redistributed_active = Some((position, bound, is_minimum));
            }
        }
        allocations[active_position].1 = if frozen_bound > 0.0 {
            frozen_bound
        } else {
            0.0
        };
        if let Some((second_active_position, second_frozen_bound, second_active_is_minimum)) =
            redistributed_active
        {
            let (active_minimum, active_maximum) = bounds[active_position];
            let (second_minimum, second_maximum) = bounds[second_active_position];
            let last_position = if unfrozen_positions[0] == second_active_position {
                unfrozen_positions[1]
            } else {
                unfrozen_positions[0]
            };
            if !active_is_minimum
                || active_minimum.is_none()
                || active_maximum.is_some()
                || !second_active_is_minimum
                || second_minimum.is_none()
                || second_maximum.is_some()
                || allocations[second_active_position].1 < second_frozen_bound
                || bounds[last_position].0.is_some()
                || bounds[last_position].1.is_some()
                || second_frozen_bound > redistributed_remaining
            {
                return Err(DefiniteLayoutError::unsupported(
                    first_occurrence,
                    DefiniteLayoutUnsupported::StackMainFill,
                ));
            }
            let last_share = redistributed_remaining - second_frozen_bound;
            if !last_share.is_finite() || last_share < 0.0 {
                return Err(DefiniteLayoutError::unsupported(
                    first_occurrence,
                    DefiniteLayoutUnsupported::StackMainFill,
                ));
            }
            allocations[second_active_position].1 = if second_frozen_bound > 0.0 {
                second_frozen_bound
            } else {
                0.0
            };
            allocations[last_position].1 = if last_share > 0.0 { last_share } else { 0.0 };
            return Ok(allocations);
        }
        allocations[unfrozen_positions[0]].1 = if first_share > 0.0 { first_share } else { 0.0 };
        allocations[unfrozen_positions[1]].1 = if second_share > 0.0 {
            second_share
        } else {
            0.0
        };
        return Ok(allocations);
    }

    if allocations.len() != 2 {
        return Err(DefiniteLayoutError::unsupported(
            first_occurrence,
            DefiniteLayoutUnsupported::StackMainFill,
        ));
    }
    let unfrozen_position = 1 - active_position;
    let (active_minimum, active_maximum) = bounds[active_position];
    let (unfrozen_minimum, unfrozen_maximum) = bounds[unfrozen_position];
    if !active_is_minimum
        && active_minimum.is_none()
        && active_maximum.is_some()
        && unfrozen_minimum.is_none()
        && frozen_bound <= remaining
        && let Some(unfrozen_maximum) = unfrozen_maximum
        && unfrozen_maximum.is_finite()
        && unfrozen_maximum >= 0.0
        && allocations[unfrozen_position].1 <= unfrozen_maximum
    {
        let unfrozen_offer = remaining - frozen_bound;
        if !unfrozen_offer.is_finite() || unfrozen_offer < 0.0 {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        if unfrozen_offer > unfrozen_maximum {
            allocations[active_position].1 = if frozen_bound > 0.0 {
                frozen_bound
            } else {
                0.0
            };
            allocations[unfrozen_position].1 = if unfrozen_maximum > 0.0 {
                unfrozen_maximum
            } else {
                0.0
            };
            return Ok(allocations);
        }
    }
    if active_is_minimum
        && active_minimum.is_some()
        && active_maximum.is_none()
        && unfrozen_maximum.is_none()
        && frozen_bound <= remaining
        && let Some(unfrozen_minimum) = unfrozen_minimum
        && unfrozen_minimum.is_finite()
        && unfrozen_minimum >= 0.0
        && allocations[unfrozen_position].1 >= unfrozen_minimum
    {
        let unfrozen_offer = remaining - frozen_bound;
        if !unfrozen_offer.is_finite() || unfrozen_offer < 0.0 {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        if unfrozen_offer < unfrozen_minimum {
            allocations[active_position].1 = if frozen_bound > 0.0 {
                frozen_bound
            } else {
                0.0
            };
            allocations[unfrozen_position].1 = if unfrozen_minimum > 0.0 {
                unfrozen_minimum
            } else {
                0.0
            };
            return Ok(allocations);
        }
    }
    if bounds[unfrozen_position].0.is_some() || bounds[unfrozen_position].1.is_some() {
        return Err(DefiniteLayoutError::unsupported(
            first_occurrence,
            DefiniteLayoutUnsupported::StackMainFill,
        ));
    }
    let unfrozen_share = if frozen_bound > remaining {
        if !active_is_minimum {
            return Err(DefiniteLayoutError::unsupported(
                first_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        0.0
    } else {
        remaining - frozen_bound
    };
    if !unfrozen_share.is_finite() || unfrozen_share < 0.0 {
        return Err(DefiniteLayoutError::unsupported(
            first_occurrence,
            DefiniteLayoutUnsupported::StackMainFill,
        ));
    }
    allocations[active_position].1 = if frozen_bound > 0.0 {
        frozen_bound
    } else {
        0.0
    };
    allocations[unfrozen_position].1 = if unfrozen_share > 0.0 {
        unfrozen_share
    } else {
        0.0
    };
    Ok(allocations)
}

fn stack_axis_size(
    placement: &Map<String, Value>,
    mode: SizeMode,
    parent_size: f64,
    leading_margin: f64,
    trailing_margin: f64,
    axis: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    stack_axis_size_from_offer(
        placement,
        mode,
        Some(parent_size),
        leading_margin,
        trailing_margin,
        axis,
        occurrence,
    )
}

fn stack_axis_size_from_offer(
    placement: &Map<String, Value>,
    mode: SizeMode,
    parent_size: Option<f64>,
    leading_margin: f64,
    trailing_margin: f64,
    axis: &str,
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
    let parent_size = parent_size.ok_or_else(|| {
        DefiniteLayoutError::invariant(occurrence, format!("definite{axis}Offer"))
    })?;
    let remaining = (parent_size - leading_margin) - trailing_margin;
    let size = if remaining > 0.0 { remaining } else { 0.0 };
    clamp_flexible_axis(placement, size, axis, occurrence)
}

fn clamp_flexible_axis(
    placement: &Map<String, Value>,
    mut size: f64,
    axis: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
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

fn definite_node_role(kind: &str, occurrence: &str) -> Result<NodeRole, DefiniteLayoutError> {
    match kind {
        "group" => Ok(NodeRole::Group),
        "frame" => Ok(NodeRole::Frame),
        "stack" => Ok(NodeRole::Stack),
        "grid" => Ok(NodeRole::Grid),
        "rect" | "ellipse" | "line" | "polygon" | "polyline" | "path" | "qrCode" | "barcode" => {
            Ok(NodeRole::Leaf)
        }
        "compositionViewport" => Err(DefiniteLayoutError::unsupported(
            occurrence,
            DefiniteLayoutUnsupported::CompositionViewport,
        )),
        "text" | "image" => Err(DefiniteLayoutError::unsupported(
            occurrence,
            DefiniteLayoutUnsupported::ResourceDependentKind,
        )),
        _ => Err(DefiniteLayoutError::invariant(occurrence, "kind")),
    }
}

fn resource_free_hug_axis(
    node: &Map<String, Value>,
    role: NodeRole,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<f64, DefiniteLayoutError> {
    let children = match role {
        NodeRole::Group | NodeRole::Frame | NodeRole::Stack | NodeRole::Grid => {
            array_member(node, "children", occurrence)?
        }
        NodeRole::Leaf => {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::HugContent,
            ));
        }
    };
    if children.is_empty() {
        return empty_container_hug_axis(node, role, placement, axis, occurrence);
    }
    if role == NodeRole::Group {
        let union = resource_free_group_hug_axis_union(node, axis, occurrence)?;
        let size = finite_group_union_value(union.maximum - union.minimum, occurrence)?;
        if size < 0.0 {
            return Err(DefiniteLayoutError::invariant(occurrence, "groupUnion"));
        }
        return Ok(size);
    }
    let content_extent = match role {
        NodeRole::Frame => resource_free_frame_hug_content_extent(
            node,
            placement,
            axis,
            occurrence,
            opposite_axis_offer,
        )?,
        NodeRole::Stack => resource_free_stack_hug_content_extent(
            node,
            placement,
            axis,
            occurrence,
            opposite_axis_offer,
        )?,
        NodeRole::Grid => resource_free_grid_hug_content_extent(
            node,
            placement,
            axis,
            occurrence,
            opposite_axis_offer,
        )?,
        NodeRole::Group => unreachable!(),
        NodeRole::Leaf => {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::HugContent,
            ));
        }
    };
    let natural = container_outer_extent(node, axis, content_extent, occurrence)?;
    clamp_flexible_axis(placement, natural, axis, occurrence)
}

fn resource_free_frame_hug_content_extent(
    frame: &Map<String, Value>,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<f64, DefiniteLayoutError> {
    let cross_axis_fill_offer = definite_frame_opposite_content_offer(
        frame,
        placement,
        axis,
        occurrence,
        opposite_axis_offer,
    )?;
    let mut extent = 0.0;
    for child in array_member(frame, "children", occurrence)? {
        let child = object(child, occurrence, "children")?;
        let interval =
            resource_free_absolute_child_axis_interval(child, axis, cross_axis_fill_offer)?;
        if interval.maximum > extent {
            extent = interval.maximum;
        }
    }
    Ok(extent)
}

fn definite_frame_opposite_content_offer(
    frame: &Map<String, Value>,
    placement: &Map<String, Value>,
    hug_axis: &str,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<Option<f64>, DefiniteLayoutError> {
    let (opposite_axis, mode_member, size_member, start_member, end_inset_member) = match hug_axis {
        "Width" => ("Height", "heightMode", "heightPt", "yPt", "bottomInsetPt"),
        "Height" => ("Width", "widthMode", "widthPt", "xPt", "rightInsetPt"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    let mode = size_mode(placement, mode_member, occurrence)?;
    let outer_size = match mode {
        SizeMode::Fixed => binary64_member(
            placement,
            size_member,
            occurrence,
            format!("placement.{size_member}"),
        )?,
        SizeMode::Fill => {
            let Some(offer) = opposite_axis_offer else {
                return Ok(None);
            };
            match offer {
                HugOppositeAxisOffer::AbsoluteParentContent(parent_size) => {
                    let start = binary64_member(
                        placement,
                        start_member,
                        occurrence,
                        format!("placement.{start_member}"),
                    )?;
                    definite_axis_size(
                        placement,
                        SizeMode::Fill,
                        parent_size,
                        start,
                        opposite_axis,
                        end_inset_member,
                        occurrence,
                    )?
                }
                HugOppositeAxisOffer::ResolvedOuter(outer_size) => outer_size,
            }
        }
        SizeMode::Hug => return Ok(None),
    };
    let content_size = container_axis_content_size(frame, outer_size, opposite_axis, occurrence)?;
    if !content_size.is_finite() {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("definiteOpposite{opposite_axis}ContentOffer"),
        ));
    }
    Ok(Some(content_size))
}

fn container_axis_content_size(
    node: &Map<String, Value>,
    outer_size: f64,
    axis: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let (leading_padding, trailing_padding) = match axis {
        "Width" => ("leftPt", "rightPt"),
        "Height" => ("topPt", "bottomPt"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "contentAxis")),
    };
    let stroke_width = if let Some(stroke) = node.get("stroke") {
        let stroke = stroke
            .as_object()
            .ok_or_else(|| DefiniteLayoutError::invariant(occurrence, "stroke"))?;
        nonnegative_binary64_member(stroke, "widthPt", occurrence, "stroke.widthPt")?
    } else {
        0.0
    };
    let mut content_size = subtract_content_inset(outer_size, stroke_width);
    content_size = subtract_content_inset(content_size, stroke_width);
    let padding = object_member(Some(node), "padding", occurrence)?;
    content_size = subtract_content_inset(
        content_size,
        nonnegative_binary64_member(
            padding,
            leading_padding,
            occurrence,
            format!("padding.{leading_padding}"),
        )?,
    );
    content_size = subtract_content_inset(
        content_size,
        nonnegative_binary64_member(
            padding,
            trailing_padding,
            occurrence,
            format!("padding.{trailing_padding}"),
        )?,
    );
    Ok(content_size)
}

fn resource_free_group_hug_axis_union(
    group: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
) -> Result<AffineAxisInterval, DefiniteLayoutError> {
    let mut union: Option<AffineAxisInterval> = None;
    for child in array_member(group, "children", occurrence)? {
        let child = object(child, occurrence, "children")?;
        let interval = resource_free_absolute_child_axis_interval(child, axis, None)?;
        union = Some(match union {
            Some(current) => AffineAxisInterval {
                minimum: current.minimum.min(interval.minimum),
                maximum: current.maximum.max(interval.maximum),
            },
            None => interval,
        });
    }
    union.ok_or_else(|| DefiniteLayoutError::invariant(occurrence, "groupUnion"))
}

fn resource_free_absolute_child_axis_interval(
    child: &Map<String, Value>,
    axis: &str,
    cross_axis_fill_offer: Option<f64>,
) -> Result<AffineAxisInterval, DefiniteLayoutError> {
    let child_occurrence = occurrence_id(child)?;
    let kind = text_member(child, "kind", child_occurrence, "kind")?;
    let role = definite_node_role(kind, child_occurrence)?;
    let placement = object_member(Some(child), "placement", child_occurrence)?;
    if text_member(placement, "type", child_occurrence, "placement.type")? != "ABSOLUTE" {
        return Err(DefiniteLayoutError::unsupported(
            child_occurrence,
            DefiniteLayoutUnsupported::NonAbsolutePlacement,
        ));
    }
    let (position, size) = resource_free_absolute_child_axis_geometry(
        child,
        role,
        placement,
        axis,
        child_occurrence,
        false,
        AbsoluteChildMeasureOffers {
            axis_fill: None,
            opposite_axis_hug: cross_axis_fill_offer
                .map(HugOppositeAxisOffer::AbsoluteParentContent),
        },
    )?;
    let transform = object_member(Some(child), "transform", child_occurrence)?;
    let rotation = binary64_member(
        transform,
        "rotationDeg",
        child_occurrence,
        "transform.rotationDeg",
    )?;
    let quarter_turn = exact_quarter_turn(rotation).ok_or_else(|| {
        DefiniteLayoutError::unsupported(child_occurrence, DefiniteLayoutUnsupported::ChildRotation)
    })?;

    match quarter_turn {
        ExactQuarterTurn::Zero if rotation == 0.0 => {
            zero_rotation_affine_axis_interval(child, position, size, axis, child_occurrence)
        }
        ExactQuarterTurn::Zero => axis_preserving_affine_axis_interval(
            transform,
            position,
            size,
            axis,
            false,
            child_occurrence,
        ),
        ExactQuarterTurn::HalfTurn => axis_preserving_affine_axis_interval(
            transform,
            position,
            size,
            axis,
            true,
            child_occurrence,
        ),
        ExactQuarterTurn::Clockwise90 | ExactQuarterTurn::Clockwise270 => {
            let cross_axis = match axis {
                "Width" => "Height",
                "Height" => "Width",
                _ => {
                    return Err(DefiniteLayoutError::invariant(child_occurrence, "HUG axis"));
                }
            };
            let (cross_position, cross_size) = resource_free_absolute_child_axis_geometry(
                child,
                role,
                placement,
                cross_axis,
                child_occurrence,
                true,
                AbsoluteChildMeasureOffers {
                    axis_fill: cross_axis_fill_offer,
                    opposite_axis_hug: None,
                },
            )?;
            quarter_turn_affine_axis_interval(
                transform,
                position,
                size,
                cross_position,
                cross_size,
                axis,
                quarter_turn,
                child_occurrence,
            )
        }
    }
}

fn resource_free_absolute_child_axis_geometry(
    child: &Map<String, Value>,
    role: NodeRole,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
    cross_axis_for_quarter_turn: bool,
    offers: AbsoluteChildMeasureOffers,
) -> Result<(f64, f64), DefiniteLayoutError> {
    let (position_member, mode_member, size_member, end_inset_member) = match axis {
        "Width" => ("xPt", "widthMode", "widthPt", "rightInsetPt"),
        "Height" => ("yPt", "heightMode", "heightPt", "bottomInsetPt"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    let position = binary64_member(
        placement,
        position_member,
        occurrence,
        format!("placement.{position_member}"),
    )?;
    let size = match size_mode(placement, mode_member, occurrence)? {
        SizeMode::Fixed => binary64_member(
            placement,
            size_member,
            occurrence,
            format!("placement.{size_member}"),
        )?,
        SizeMode::Hug => resource_free_hug_axis(
            child,
            role,
            placement,
            axis,
            occurrence,
            offers.opposite_axis_hug,
        )?,
        SizeMode::Fill if cross_axis_for_quarter_turn => match offers.axis_fill {
            Some(parent_size) => definite_axis_size(
                placement,
                SizeMode::Fill,
                parent_size,
                position,
                axis,
                end_inset_member,
                occurrence,
            )?,
            None => {
                return Err(DefiniteLayoutError::unsupported(
                    occurrence,
                    DefiniteLayoutUnsupported::ChildRotation,
                ));
            }
        },
        SizeMode::Fill => {
            return Err(DefiniteLayoutError::invariant(
                occurrence,
                format!("placement.{mode_member}"),
            ));
        }
    };
    Ok((position, size))
}

fn exact_quarter_turn(rotation: f64) -> Option<ExactQuarterTurn> {
    if rotation == -360.0 || rotation == 0.0 || rotation == 360.0 {
        Some(ExactQuarterTurn::Zero)
    } else if rotation == -270.0 || rotation == 90.0 {
        Some(ExactQuarterTurn::Clockwise90)
    } else if rotation == -180.0 || rotation == 180.0 {
        Some(ExactQuarterTurn::HalfTurn)
    } else if rotation == -90.0 || rotation == 270.0 {
        Some(ExactQuarterTurn::Clockwise270)
    } else {
        None
    }
}

fn zero_rotation_affine_axis_interval(
    node: &Map<String, Value>,
    position: f64,
    size: f64,
    axis: &str,
    occurrence: &str,
) -> Result<AffineAxisInterval, DefiniteLayoutError> {
    let transform = object_member(Some(node), "transform", occurrence)?;
    let rotation = binary64_member(
        transform,
        "rotationDeg",
        occurrence,
        "transform.rotationDeg",
    )?;
    if rotation != 0.0 {
        return Err(DefiniteLayoutError::unsupported(
            occurrence,
            DefiniteLayoutUnsupported::ChildRotation,
        ));
    }
    let (origin_member, scale_member) = match axis {
        "Width" => ("originX", "scaleX"),
        "Height" => ("originY", "scaleY"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    let origin_ratio = binary64_member(
        transform,
        origin_member,
        occurrence,
        format!("transform.{origin_member}"),
    )?;
    let scale = binary64_member(
        transform,
        scale_member,
        occurrence,
        format!("transform.{scale_member}"),
    )?;
    if scale == 0.0 {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("transform.{scale_member}"),
        ));
    }

    let origin_offset = finite_transform_value(origin_ratio * size, occurrence)?;
    let transform_origin = finite_transform_value(position + origin_offset, occurrence)?;
    let near_delta = finite_transform_value(position - transform_origin, occurrence)?;
    let near_scaled = finite_transform_value(scale * near_delta, occurrence)?;
    let near = finite_transform_value(transform_origin + near_scaled, occurrence)?;
    let far_position = finite_transform_value(position + size, occurrence)?;
    let far_delta = finite_transform_value(far_position - transform_origin, occurrence)?;
    let far_scaled = finite_transform_value(scale * far_delta, occurrence)?;
    let far = finite_transform_value(transform_origin + far_scaled, occurrence)?;
    Ok(AffineAxisInterval {
        minimum: near.min(far),
        maximum: near.max(far),
    })
}

fn axis_preserving_affine_axis_interval(
    transform: &Map<String, Value>,
    position: f64,
    size: f64,
    axis: &str,
    reverse: bool,
    occurrence: &str,
) -> Result<AffineAxisInterval, DefiniteLayoutError> {
    let (origin_member, scale_member) = match axis {
        "Width" => ("originX", "scaleX"),
        "Height" => ("originY", "scaleY"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    let origin_ratio = binary64_member(
        transform,
        origin_member,
        occurrence,
        format!("transform.{origin_member}"),
    )?;
    let scale = binary64_member(
        transform,
        scale_member,
        occurrence,
        format!("transform.{scale_member}"),
    )?;
    if scale == 0.0 {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("transform.{scale_member}"),
        ));
    }

    let origin_offset = finite_transform_value(origin_ratio * size, occurrence)?;
    let transform_origin = finite_transform_value(position + origin_offset, occurrence)?;
    let near_delta = finite_transform_value(position - transform_origin, occurrence)?;
    let near_scaled = finite_transform_value(scale * near_delta, occurrence)?;
    let near = signed_transform_endpoint(transform_origin, near_scaled, reverse, occurrence)?;
    let far_position = finite_transform_value(position + size, occurrence)?;
    let far_delta = finite_transform_value(far_position - transform_origin, occurrence)?;
    let far_scaled = finite_transform_value(scale * far_delta, occurrence)?;
    let far = signed_transform_endpoint(transform_origin, far_scaled, reverse, occurrence)?;
    Ok(AffineAxisInterval {
        minimum: near.min(far),
        maximum: near.max(far),
    })
}

#[allow(clippy::too_many_arguments)]
fn quarter_turn_affine_axis_interval(
    transform: &Map<String, Value>,
    target_position: f64,
    target_size: f64,
    source_position: f64,
    source_size: f64,
    axis: &str,
    quarter_turn: ExactQuarterTurn,
    occurrence: &str,
) -> Result<AffineAxisInterval, DefiniteLayoutError> {
    let (target_origin_member, source_origin_member, source_scale_member, reverse) =
        match (axis, quarter_turn) {
            ("Width", ExactQuarterTurn::Clockwise90) => ("originX", "originY", "scaleY", true),
            ("Height", ExactQuarterTurn::Clockwise90) => ("originY", "originX", "scaleX", false),
            ("Width", ExactQuarterTurn::Clockwise270) => ("originX", "originY", "scaleY", false),
            ("Height", ExactQuarterTurn::Clockwise270) => ("originY", "originX", "scaleX", true),
            _ => return Err(DefiniteLayoutError::invariant(occurrence, "quarterTurn")),
        };
    let target_origin_ratio = binary64_member(
        transform,
        target_origin_member,
        occurrence,
        format!("transform.{target_origin_member}"),
    )?;
    let source_origin_ratio = binary64_member(
        transform,
        source_origin_member,
        occurrence,
        format!("transform.{source_origin_member}"),
    )?;
    let source_scale = binary64_member(
        transform,
        source_scale_member,
        occurrence,
        format!("transform.{source_scale_member}"),
    )?;
    if source_scale == 0.0 {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("transform.{source_scale_member}"),
        ));
    }

    let target_origin_offset =
        finite_transform_value(target_origin_ratio * target_size, occurrence)?;
    let target_origin = finite_transform_value(target_position + target_origin_offset, occurrence)?;
    let source_origin_offset =
        finite_transform_value(source_origin_ratio * source_size, occurrence)?;
    let source_origin = finite_transform_value(source_position + source_origin_offset, occurrence)?;
    let near_delta = finite_transform_value(source_position - source_origin, occurrence)?;
    let near_scaled = finite_transform_value(source_scale * near_delta, occurrence)?;
    let near = signed_transform_endpoint(target_origin, near_scaled, reverse, occurrence)?;
    let far_position = finite_transform_value(source_position + source_size, occurrence)?;
    let far_delta = finite_transform_value(far_position - source_origin, occurrence)?;
    let far_scaled = finite_transform_value(source_scale * far_delta, occurrence)?;
    let far = signed_transform_endpoint(target_origin, far_scaled, reverse, occurrence)?;
    Ok(AffineAxisInterval {
        minimum: near.min(far),
        maximum: near.max(far),
    })
}

fn signed_transform_endpoint(
    origin: f64,
    scaled_delta: f64,
    reverse: bool,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let endpoint = if reverse {
        origin - scaled_delta
    } else {
        origin + scaled_delta
    };
    finite_transform_value(endpoint, occurrence)
}

fn finite_transform_value(value: f64, occurrence: &str) -> Result<f64, DefiniteLayoutError> {
    if value.is_finite() {
        Ok(value)
    } else {
        Err(DefiniteLayoutError::invariant(occurrence, "transform"))
    }
}

fn finite_group_union_value(value: f64, occurrence: &str) -> Result<f64, DefiniteLayoutError> {
    if value.is_finite() {
        Ok(value)
    } else {
        Err(DefiniteLayoutError::invariant(occurrence, "groupUnion"))
    }
}

fn finite_group_normalization_value(
    value: f64,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    if value.is_finite() {
        Ok(value)
    } else {
        Err(DefiniteLayoutError::invariant(
            occurrence,
            "groupNormalization",
        ))
    }
}

fn resource_free_grid_hug_content_extent(
    grid: &Map<String, Value>,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<f64, DefiniteLayoutError> {
    let children = array_member(grid, "children", occurrence)?;
    let resolved = match axis {
        "Width" => definite_grid_axis(
            grid,
            children,
            GridAxis::Column,
            0.0,
            0.0,
            GridAxisMeasurementSpace::Independent,
            occurrence,
        )?,
        "Height" => {
            let Some(column_content_offer) = definite_grid_column_content_offer(
                grid,
                placement,
                occurrence,
                opposite_axis_offer,
            )?
            else {
                return resource_free_independent_grid_hug_content_extent(
                    grid,
                    children,
                    GridAxis::Row,
                    occurrence,
                );
            };
            let columns = definite_grid_axis(
                grid,
                children,
                GridAxis::Column,
                0.0,
                column_content_offer,
                GridAxisMeasurementSpace::Independent,
                occurrence,
            )?;
            definite_grid_axis(
                grid,
                children,
                GridAxis::Row,
                0.0,
                0.0,
                GridAxisMeasurementSpace::RowsAfterColumns(&columns),
                occurrence,
            )?
        }
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    Ok(grid_span_extent(
        &resolved.sizes,
        resolved.gap,
        0,
        resolved.sizes.len(),
    ))
}

fn resource_free_independent_grid_hug_content_extent(
    grid: &Map<String, Value>,
    children: &[Value],
    axis: GridAxis,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let resolved = definite_grid_axis(
        grid,
        children,
        axis,
        0.0,
        0.0,
        GridAxisMeasurementSpace::Independent,
        occurrence,
    )?;
    Ok(grid_span_extent(
        &resolved.sizes,
        resolved.gap,
        0,
        resolved.sizes.len(),
    ))
}

fn definite_grid_column_content_offer(
    grid: &Map<String, Value>,
    placement: &Map<String, Value>,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<Option<f64>, DefiniteLayoutError> {
    if size_mode(placement, "widthMode", occurrence)? != SizeMode::Fill {
        return Ok(None);
    }
    let Some(opposite_axis_offer) = opposite_axis_offer else {
        return Ok(None);
    };
    let width = match opposite_axis_offer {
        HugOppositeAxisOffer::ResolvedOuter(width) => width,
        HugOppositeAxisOffer::AbsoluteParentContent(parent_width) => {
            let authored_x = binary64_member(placement, "xPt", occurrence, "placement.xPt")?;
            definite_axis_size(
                placement,
                SizeMode::Fill,
                parent_width,
                authored_x,
                "Width",
                "rightInsetPt",
                occurrence,
            )?
        }
    };
    let content_width = container_axis_content_size(grid, width, "Width", occurrence)?;
    if !content_width.is_finite() {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            "definiteColumnContentOffer",
        ));
    }
    Ok(Some(content_width))
}

fn resource_free_stack_hug_content_extent(
    stack: &Map<String, Value>,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<f64, DefiniteLayoutError> {
    let direction = stack_direction(stack, occurrence)?;
    let axis_is_main = matches!(
        (direction, axis),
        (StackDirection::Row, "Width") | (StackDirection::Column, "Height")
    );
    if !matches!(axis, "Width" | "Height") {
        return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis"));
    }
    if axis_is_main
        && let Some(cross_content_offer) = definite_stack_cross_content_offer(
            stack,
            placement,
            direction,
            occurrence,
            opposite_axis_offer,
        )?
    {
        return resource_free_stack_main_hug_content_extent(stack, direction, cross_content_offer);
    }
    if !axis_is_main
        && let Some(main_content_offer) = definite_stack_main_content_offer(
            stack,
            placement,
            direction,
            occurrence,
            opposite_axis_offer,
        )?
    {
        return resource_free_stack_cross_hug_content_extent(stack, direction, main_content_offer);
    }
    let children = array_member(stack, "children", occurrence)?;
    let gap = nonnegative_binary64_member(stack, "gapPt", occurrence, "gapPt")?;
    let mut extent = 0.0;

    if axis_is_main {
        let mut cursor = 0.0;
        for (index, child) in children.iter().enumerate() {
            let child = object(child, occurrence, "children")?;
            let child_occurrence = occurrence_id(child)?;
            let placement = stack_child_placement(child, child_occurrence)?;
            let (leading_margin, trailing_margin) =
                stack_hug_axis_margins(placement, axis, child_occurrence)?;
            let child_size =
                resource_free_stack_child_axis_size(child, placement, axis, child_occurrence)?;
            for addition in [leading_margin, child_size, trailing_margin] {
                cursor += addition;
                if cursor > extent {
                    extent = cursor;
                }
            }
            if index + 1 < children.len() {
                cursor += gap;
                if cursor > extent {
                    extent = cursor;
                }
            }
        }
        return Ok(extent);
    }

    for child in children {
        let child = object(child, occurrence, "children")?;
        let child_occurrence = occurrence_id(child)?;
        let placement = stack_child_placement(child, child_occurrence)?;
        let (leading_margin, trailing_margin) =
            stack_hug_axis_margins(placement, axis, child_occurrence)?;
        let child_size =
            resource_free_stack_child_axis_size(child, placement, axis, child_occurrence)?;
        let mut margin_extent_end = leading_margin;
        margin_extent_end += child_size;
        margin_extent_end += trailing_margin;
        if margin_extent_end > extent {
            extent = margin_extent_end;
        }
    }
    Ok(extent)
}

fn definite_stack_cross_content_offer(
    stack: &Map<String, Value>,
    placement: &Map<String, Value>,
    direction: StackDirection,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<Option<f64>, DefiniteLayoutError> {
    let (cross_axis, mode_member) = match direction {
        StackDirection::Row => ("Height", "heightMode"),
        StackDirection::Column => ("Width", "widthMode"),
    };
    if size_mode(placement, mode_member, occurrence)? != SizeMode::Fill {
        return Ok(None);
    }
    let Some(HugOppositeAxisOffer::ResolvedOuter(cross_outer_size)) = opposite_axis_offer else {
        return Ok(None);
    };
    let cross_content_size =
        container_axis_content_size(stack, cross_outer_size, cross_axis, occurrence)?;
    if !cross_content_size.is_finite() {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("definite{cross_axis}ContentOffer"),
        ));
    }
    Ok(Some(cross_content_size))
}

fn resource_free_stack_main_hug_content_extent(
    stack: &Map<String, Value>,
    direction: StackDirection,
    cross_content_offer: f64,
) -> Result<f64, DefiniteLayoutError> {
    let occurrence = occurrence_id(stack)?;
    let children = array_member(stack, "children", occurrence)?;
    let gap = nonnegative_binary64_member(stack, "gapPt", occurrence, "gapPt")?;
    let space = StackMeasurementSpace::main_hug(direction, cross_content_offer);
    let mut extent = 0.0;

    for (index, child) in children.iter().enumerate() {
        let child = object(child, occurrence, "children")?;
        let child_occurrence = occurrence_id(child)?;
        let measurement = measure_stack_child(child, space, direction)?;
        if measurement.main_fill {
            return Err(DefiniteLayoutError::unsupported(
                child_occurrence,
                DefiniteLayoutUnsupported::StackMainFill,
            ));
        }
        for addition in [
            measurement.main_leading_margin(direction),
            measurement.main_size(direction),
            measurement.main_trailing_margin(direction),
        ] {
            extent += addition;
        }
        if index + 1 < children.len() {
            extent += gap;
        }
    }
    Ok(extent)
}

fn definite_stack_main_content_offer(
    stack: &Map<String, Value>,
    placement: &Map<String, Value>,
    direction: StackDirection,
    occurrence: &str,
    opposite_axis_offer: Option<HugOppositeAxisOffer>,
) -> Result<Option<f64>, DefiniteLayoutError> {
    let (main_axis, mode_member) = match direction {
        StackDirection::Row => ("Width", "widthMode"),
        StackDirection::Column => ("Height", "heightMode"),
    };
    if size_mode(placement, mode_member, occurrence)? != SizeMode::Fill {
        return Ok(None);
    }
    let Some(HugOppositeAxisOffer::ResolvedOuter(main_outer_size)) = opposite_axis_offer else {
        return Ok(None);
    };
    let main_content_size =
        container_axis_content_size(stack, main_outer_size, main_axis, occurrence)?;
    if !main_content_size.is_finite() {
        return Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("definite{main_axis}ContentOffer"),
        ));
    }
    Ok(Some(main_content_size))
}

fn resource_free_stack_cross_hug_content_extent(
    stack: &Map<String, Value>,
    direction: StackDirection,
    main_content_offer: f64,
) -> Result<f64, DefiniteLayoutError> {
    let resolved = measure_and_allocate_stack_children(
        stack,
        StackMeasurementSpace::cross_hug(direction, main_content_offer),
    )?;
    let mut extent = 0.0;
    for measured in resolved.measurements {
        let measurement = measured?;
        let margin_extent_end = (measurement.cross_leading_margin(direction)
            + measurement.cross_size(direction))
            + measurement.cross_trailing_margin(direction);
        if margin_extent_end > extent {
            extent = margin_extent_end;
        }
    }
    Ok(extent)
}

fn stack_child_placement<'a>(
    node: &'a Map<String, Value>,
    occurrence: &str,
) -> Result<&'a Map<String, Value>, DefiniteLayoutError> {
    let placement = object_member(Some(node), "placement", occurrence)?;
    if text_member(placement, "type", occurrence, "placement.type")? != "STACK" {
        return Err(DefiniteLayoutError::unsupported(
            occurrence,
            DefiniteLayoutUnsupported::NonAbsolutePlacement,
        ));
    }
    Ok(placement)
}

fn stack_hug_axis_margins(
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
) -> Result<(f64, f64), DefiniteLayoutError> {
    let (leading_member, trailing_member) = match axis {
        "Width" => ("marginLeftPt", "marginRightPt"),
        "Height" => ("marginTopPt", "marginBottomPt"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    Ok((
        binary64_member(
            placement,
            leading_member,
            occurrence,
            format!("placement.{leading_member}"),
        )?,
        binary64_member(
            placement,
            trailing_member,
            occurrence,
            format!("placement.{trailing_member}"),
        )?,
    ))
}

fn resource_free_stack_child_axis_size(
    node: &Map<String, Value>,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let kind = text_member(node, "kind", occurrence, "kind")?;
    let role = definite_node_role(kind, occurrence)?;
    let mode_member = format!("{}Mode", axis.to_ascii_lowercase());
    match size_mode(placement, &mode_member, occurrence)? {
        SizeMode::Fixed => {
            let size_member = format!("{}Pt", axis.to_ascii_lowercase());
            binary64_member(
                placement,
                &size_member,
                occurrence,
                format!("placement.{size_member}"),
            )
        }
        SizeMode::Hug => resource_free_hug_axis(node, role, placement, axis, occurrence, None),
        SizeMode::Fill => Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("placement.{mode_member}"),
        )),
    }
}

fn empty_container_hug_axis(
    node: &Map<String, Value>,
    role: NodeRole,
    placement: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let children = match role {
        NodeRole::Group | NodeRole::Frame | NodeRole::Stack | NodeRole::Grid => {
            array_member(node, "children", occurrence)?
        }
        NodeRole::Leaf => {
            return Err(DefiniteLayoutError::unsupported(
                occurrence,
                DefiniteLayoutUnsupported::HugContent,
            ));
        }
    };
    if !children.is_empty() {
        return Err(DefiniteLayoutError::unsupported(
            occurrence,
            if role == NodeRole::Group {
                DefiniteLayoutUnsupported::Group
            } else {
                DefiniteLayoutUnsupported::HugContent
            },
        ));
    }
    if role == NodeRole::Group {
        return Ok(0.0);
    }

    let content_extent = match role {
        NodeRole::Frame | NodeRole::Stack => 0.0,
        NodeRole::Grid => empty_grid_track_extent(node, axis, occurrence)?,
        NodeRole::Group | NodeRole::Leaf => unreachable!(),
    };
    let natural = container_outer_extent(node, axis, content_extent, occurrence)?;
    clamp_flexible_axis(placement, natural, axis, occurrence)
}

fn empty_grid_track_extent(
    grid: &Map<String, Value>,
    axis: &str,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let (tracks_member, gap_member) = match axis {
        "Width" => ("columns", "columnGapPt"),
        "Height" => ("rows", "rowGapPt"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    let tracks = array_member(grid, tracks_member, occurrence)?;
    let gap = nonnegative_binary64_member(grid, gap_member, occurrence, gap_member)?;
    let mut extent = 0.0;
    for (index, track) in tracks.iter().enumerate() {
        let track = object(track, occurrence, tracks_member)?;
        match text_member(
            track,
            "type",
            occurrence,
            format!("{tracks_member}[{index}].type"),
        )? {
            "FIXED" => {
                extent += binary64_member(
                    track,
                    "valuePt",
                    occurrence,
                    format!("{tracks_member}[{index}].valuePt"),
                )?;
            }
            "AUTO" => {}
            "FRACTION" => {
                return Err(DefiniteLayoutError::invariant(
                    occurrence,
                    format!("{tracks_member}[{index}].type"),
                ));
            }
            _ => {
                return Err(DefiniteLayoutError::invariant(
                    occurrence,
                    format!("{tracks_member}[{index}].type"),
                ));
            }
        }
        if index + 1 < tracks.len() {
            extent += gap;
        }
    }
    Ok(extent)
}

fn container_outer_extent(
    node: &Map<String, Value>,
    axis: &str,
    mut extent: f64,
    occurrence: &str,
) -> Result<f64, DefiniteLayoutError> {
    let stroke_width = if let Some(stroke) = node.get("stroke") {
        let stroke = stroke
            .as_object()
            .ok_or_else(|| DefiniteLayoutError::invariant(occurrence, "stroke"))?;
        nonnegative_binary64_member(stroke, "widthPt", occurrence, "stroke.widthPt")?
    } else {
        0.0
    };
    let padding = object_member(Some(node), "padding", occurrence)?;
    let (leading_member, trailing_member) = match axis {
        "Width" => ("leftPt", "rightPt"),
        "Height" => ("topPt", "bottomPt"),
        _ => return Err(DefiniteLayoutError::invariant(occurrence, "HUG axis")),
    };
    extent += nonnegative_binary64_member(
        padding,
        leading_member,
        occurrence,
        format!("padding.{leading_member}"),
    )?;
    extent += nonnegative_binary64_member(
        padding,
        trailing_member,
        occurrence,
        format!("padding.{trailing_member}"),
    )?;
    extent += stroke_width;
    extent += stroke_width;
    Ok(extent)
}

fn stack_direction(
    stack: &Map<String, Value>,
    occurrence: &str,
) -> Result<StackDirection, DefiniteLayoutError> {
    match text_member(stack, "direction", occurrence, "direction")? {
        "ROW" => Ok(StackDirection::Row),
        "COLUMN" => Ok(StackDirection::Column),
        _ => Err(DefiniteLayoutError::invariant(occurrence, "direction")),
    }
}

fn stack_alignment(
    object: &Map<String, Value>,
    member: &str,
    occurrence: &str,
) -> Result<StackAlignment, DefiniteLayoutError> {
    match text_member(object, member, occurrence, format!("placement.{member}"))? {
        "START" => Ok(StackAlignment::Start),
        "CENTER" => Ok(StackAlignment::Center),
        "END" => Ok(StackAlignment::End),
        _ => Err(DefiniteLayoutError::invariant(
            occurrence,
            format!("placement.{member}"),
        )),
    }
}

fn stack_justification(
    stack: &Map<String, Value>,
    occurrence: &str,
) -> Result<StackJustification, DefiniteLayoutError> {
    match text_member(stack, "justifyContent", occurrence, "justifyContent")? {
        "START" => Ok(StackJustification::Start),
        "CENTER" => Ok(StackJustification::Center),
        "END" => Ok(StackJustification::End),
        "SPACE_BETWEEN" => Ok(StackJustification::SpaceBetween),
        "SPACE_AROUND" => Ok(StackJustification::SpaceAround),
        "SPACE_EVENLY" => Ok(StackJustification::SpaceEvenly),
        _ => Err(DefiniteLayoutError::invariant(occurrence, "justifyContent")),
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

fn container_content_box(
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
    let inner_width = subtract_content_inset(layout_box.width, stroke_width);
    let inner_width = subtract_content_inset(inner_width, stroke_width);
    let inner_height = subtract_content_inset(layout_box.height, stroke_width);
    let inner_height = subtract_content_inset(inner_height, stroke_width);
    let inner_x = layout_box.x + stroke_width;
    let inner_y = layout_box.y + stroke_width;

    let padding = object_member(Some(node), "padding", occurrence)?;
    let top = nonnegative_binary64_member(padding, "topPt", occurrence, "padding.topPt")?;
    let right = nonnegative_binary64_member(padding, "rightPt", occurrence, "padding.rightPt")?;
    let bottom = nonnegative_binary64_member(padding, "bottomPt", occurrence, "padding.bottomPt")?;
    let left = nonnegative_binary64_member(padding, "leftPt", occurrence, "padding.leftPt")?;
    let content_width = subtract_content_inset(inner_width, left);
    let content_width = subtract_content_inset(content_width, right);
    let content_height = subtract_content_inset(inner_height, top);
    let content_height = subtract_content_inset(content_height, bottom);
    Ok(LocalLayoutBox {
        x: inner_x + left,
        y: inner_y + top,
        width: content_width,
        height: content_height,
    })
}

fn subtract_content_inset(size: f64, inset: f64) -> f64 {
    let remaining = size - inset;
    if remaining > 0.0 { remaining } else { 0.0 }
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
