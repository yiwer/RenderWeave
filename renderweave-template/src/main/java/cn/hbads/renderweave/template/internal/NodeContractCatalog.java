package cn.hbads.renderweave.template.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single machine authority for the admitted DesignDSL Node contract (T14 increment 1: containers).
 * Every property tree, ValueType, unit, ContentModel and placement capability below is a permanent
 * Node Property Identity fact for {@code renderweave-design/1.0}; future extensions must create a
 * new dslVersion. Visual leaf kinds and the repeat/conditional/templateUse structural kinds are
 * intentionally NOT in this catalog yet (they fail closed with DESIGN_KERNEL_SCOPE_UNSUPPORTED).
 */
final class NodeContractCatalog {

    enum NodeKind {
        CANVAS,
        GROUP,
        FRAME,
        STACK,
        GRID
    }

    enum PlacementVariant {
        ABSOLUTE,
        STACK,
        GRID,
        PACK
    }

    enum SizeMode {
        FIXED,
        HUG_CONTENT,
        FILL
    }

    /** Kind names as authored on the wire (lowerCamelCase). */
    static final Map<String, NodeKind> KIND_BY_NAME = Map.of(
            "canvas", NodeKind.CANVAS,
            "group", NodeKind.GROUP,
            "frame", NodeKind.FRAME,
            "stack", NodeKind.STACK,
            "grid", NodeKind.GRID
    );

    /**
     * Known-but-not-yet-admitted kinds (visual leaves and structural kinds). They fail closed with
     * DESIGN_KERNEL_SCOPE_UNSUPPORTED until their atoms tickets land; anything else is an unknown
     * kind value.
     */
    static final Set<String> FUTURE_KINDS = Set.of(
            "text", "image", "rect", "ellipse", "line", "polygon", "polyline", "path",
            "qrCode", "barcode", "repeat", "conditional", "templateUse"
    );

    static final Set<String> COMMON_NODE_MEMBERS = Set.of(
            "nodeId", "kind", "displayName", "bindings", "placement",
            "render", "visible", "opacity", "transform"
    );

    static final Set<String> CONTAINER_MEMBERS = Set.of("children");

    /** Frame, Stack and Grid all share the optional appearance members. */
    static final Set<String> APPEARANCE_MEMBERS = Set.of(
            "fill", "stroke", "cornerRadii", "padding", "clipContent"
    );

    static final Set<String> STACK_MEMBERS = Set.of(
            "direction", "gapMm", "justifyContent", "alignItems"
    );

    static final Set<String> GRID_MEMBERS = Set.of(
            "rows", "columns", "rowGapMm", "columnGapMm"
    );

    static final Set<String> FILL_MEMBERS = Set.of("color");
    static final Set<String> STROKE_MM_MEMBERS = Set.of("color", "widthMm", "cap", "join");
    static final List<String> PADDING_MEMBER_ORDER = List.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    static final Set<String> PADDING_MEMBERS = Set.copyOf(PADDING_MEMBER_ORDER);
    static final List<String> CORNER_RADII_MEMBER_ORDER = List.of(
            "topLeftMm", "topRightMm", "bottomRightMm", "bottomLeftMm"
    );
    static final Set<String> CORNER_RADII_MEMBERS = Set.copyOf(CORNER_RADII_MEMBER_ORDER);
    static final Set<String> TRANSFORM_MEMBERS = Set.of(
            "rotationDeg", "scaleX", "scaleY", "originX", "originY"
    );

    static final Set<String> ABSOLUTE_PLACEMENT_MEMBERS = Set.of(
            "type", "xMm", "yMm", "widthMode", "heightMode", "widthMm", "heightMm",
            "minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm",
            "rightInsetMm", "bottomInsetMm"
    );
    static final Set<String> STACK_PLACEMENT_MEMBERS = Set.of(
            "type", "widthMode", "heightMode", "widthMm", "heightMm",
            "minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm",
            "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm",
            "alignSelf", "fillWeight"
    );
    static final Set<String> GRID_PLACEMENT_MEMBERS = Set.of(
            "type", "widthMode", "heightMode", "widthMm", "heightMm",
            "minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm",
            "row", "column", "rowSpan", "columnSpan",
            "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm",
            "horizontalAlignSelf", "verticalAlignSelf"
    );

    static final Set<String> SIZE_MODE_TOKENS = Set.of(
            "FIXED", "HUG_CONTENT", "FILL"
    );

    static final Set<String> STROKE_CAP_TOKENS = Set.of("BUTT", "ROUND", "SQUARE");
    static final Set<String> STROKE_JOIN_TOKENS = Set.of("MITER", "ROUND", "BEVEL");
    static final Set<String> STACK_DIRECTION_TOKENS = Set.of("ROW", "COLUMN");
    static final Set<String> JUSTIFY_CONTENT_TOKENS = Set.of(
            "START", "CENTER", "END", "SPACE_BETWEEN", "SPACE_AROUND", "SPACE_EVENLY"
    );
    static final Set<String> ALIGN_ITEMS_TOKENS = Set.of("START", "CENTER", "END");
    static final Set<String> TRACK_TYPE_TOKENS = Set.of("FIXED", "FRACTION", "AUTO");

    /**
     * Placement variant required for a direct child of the given parent kind. The root Canvas has
     * no placement; Repeat children use PACK (registered with the Repeat atoms ticket).
     */
    static PlacementVariant expectedVariant(NodeKind parentKind) {
        return switch (parentKind) {
            case CANVAS, FRAME, GROUP -> PlacementVariant.ABSOLUTE;
            case STACK -> PlacementVariant.STACK;
            case GRID -> PlacementVariant.GRID;
        };
    }

    /** Per-kind width/height mode capability. */
    static Set<SizeMode> sizeModes(NodeKind kind) {
        return switch (kind) {
            case GROUP -> Set.of(SizeMode.HUG_CONTENT);
            case CANVAS, FRAME, STACK, GRID -> Set.of(SizeMode.FIXED, SizeMode.HUG_CONTENT, SizeMode.FILL);
        };
    }

    /** Containers may nest other containers (except Canvas, which is root-only). */
    static boolean allowsChildren(NodeKind kind) {
        return kind != NodeKind.CANVAS;
    }

    private NodeContractCatalog() {
    }
}
