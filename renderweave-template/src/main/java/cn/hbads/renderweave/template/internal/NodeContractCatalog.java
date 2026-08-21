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
        GRID,
        REPEAT,
        TEXT,
        IMAGE,
        RECT,
        ELLIPSE,
        LINE,
        POLYGON,
        POLYLINE,
        PATH,
        QRCODE,
        BARCODE,
        TEMPLATE_USE,
        CONDITIONAL
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
    static final Map<String, NodeKind> KIND_BY_NAME = Map.ofEntries(
            Map.entry("canvas", NodeKind.CANVAS),
            Map.entry("group", NodeKind.GROUP),
            Map.entry("frame", NodeKind.FRAME),
            Map.entry("stack", NodeKind.STACK),
            Map.entry("grid", NodeKind.GRID),
            Map.entry("repeat", NodeKind.REPEAT),
            Map.entry("text", NodeKind.TEXT),
            Map.entry("image", NodeKind.IMAGE),
            Map.entry("rect", NodeKind.RECT),
            Map.entry("ellipse", NodeKind.ELLIPSE),
            Map.entry("line", NodeKind.LINE),
            Map.entry("polygon", NodeKind.POLYGON),
            Map.entry("polyline", NodeKind.POLYLINE),
            Map.entry("path", NodeKind.PATH),
            Map.entry("qrCode", NodeKind.QRCODE),
            Map.entry("barcode", NodeKind.BARCODE),
            Map.entry("templateUse", NodeKind.TEMPLATE_USE),
            Map.entry("conditional", NodeKind.CONDITIONAL)
    );

    /**
     * Known-but-not-yet-admitted kinds. Empty for {@code renderweave-design/1.0}: every
     * v1 kind is now admitted; future wire kinds must come with a new dslVersion.
     */
    static final Set<String> FUTURE_KINDS = Set.of();

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

    /** Repeat structural members (ticket 11 §1); no appearance/box members. */
    static final Set<String> REPEAT_MEMBERS = Set.of(
            "loopId", "items", "absentPolicy", "itemLayout", "instanceLayout"
    );

    // --- Visual leaf members (ticket 09 §6-§7) ---------------------------------

    static final Set<String> TEXT_MEMBERS = Set.of(
            "runs", "writingMode", "horizontalAlign", "verticalAlign", "lineBreak",
            "overflow", "lineHeight", "maxLines", "padding", "stroke", "fitMode", "minScale"
    );
    static final Set<String> RUN_MEMBERS = Set.of(
            "text", "fontRef", "fontSizePt", "color", "decoration",
            "letterSpacingPt", "letterSpacingFactor"
    );
    static final Set<String> LINE_HEIGHT_MEMBERS = Set.of("type", "factor", "valuePt");
    static final Set<String> IMAGE_MEMBERS = Set.of("imageRef", "fit", "sampling");
    static final Set<String> RECT_MEMBERS = Set.of("fill", "stroke", "cornerRadii");
    static final Set<String> ELLIPSE_MEMBERS = Set.of("fill", "stroke");
    static final Set<String> LINE_MEMBERS = Set.of("start", "end", "stroke");
    static final Set<String> POLYGON_MEMBERS = Set.of("points", "fill", "stroke");
    static final Set<String> POLYLINE_MEMBERS = Set.of("points", "stroke");
    static final Set<String> PATH_MEMBERS = Set.of("commands", "fill", "stroke", "fillRule");
    static final Set<String> QRCODE_MEMBERS = Set.of(
            "content", "errorCorrectionLevel", "foregroundColor", "backgroundColor"
    );
    static final Set<String> BARCODE_MEMBERS = Set.of(
            "format", "value", "foregroundColor", "backgroundColor"
    );
    static final Set<String> POINT_MM_MEMBERS = Set.of("xMm", "yMm");

    static final Set<String> MOVE_TO_COMMAND_MEMBERS = Set.of("type", "xMm", "yMm");
    static final Set<String> LINE_TO_COMMAND_MEMBERS = Set.of("type", "xMm", "yMm");
    static final Set<String> QUAD_TO_COMMAND_MEMBERS = Set.of(
            "type", "cxMm", "cyMm", "xMm", "yMm"
    );
    static final Set<String> CUBIC_TO_COMMAND_MEMBERS = Set.of(
            "type", "c1xMm", "c1yMm", "c2xMm", "c2yMm", "xMm", "yMm"
    );
    static final Set<String> CLOSE_COMMAND_MEMBERS = Set.of("type");

    static final Set<String> WRITING_MODE_TOKENS = Set.of("HORIZONTAL_TB", "VERTICAL_RL");
    static final Set<String> HORIZONTAL_ALIGN_TOKENS = Set.of(
            "LEFT", "CENTER", "RIGHT", "JUSTIFY", "SPACE_EVENLY"
    );
    static final Set<String> VERTICAL_ALIGN_TOKENS = Set.of(
            "TOP", "CENTER", "BOTTOM", "JUSTIFY", "SPACE_EVENLY"
    );
    static final Set<String> LINE_BREAK_TOKENS = Set.of("NONE", "WORD", "CHAR");
    static final Set<String> TEXT_OVERFLOW_TOKENS = Set.of("VISIBLE", "CLIP", "ELLIPSIS", "FAIL");
    static final Set<String> DECORATION_TOKENS = Set.of("NONE", "UNDERLINE", "LINE_THROUGH");
    static final Set<String> LINE_HEIGHT_TYPE_TOKENS = Set.of("FACTOR", "FIXED");
    static final Set<String> FIT_MODE_TOKENS = Set.of("NONE", "SHRINK_TO_FIT");
    static final Set<String> IMAGE_FIT_TOKENS = Set.of("CONTAIN", "COVER", "FILL");
    static final Set<String> IMAGE_SAMPLING_TOKENS = Set.of("LINEAR", "NEAREST");
    static final Set<String> FILL_RULE_TOKENS = Set.of("NONZERO", "EVEN_ODD");
    static final Set<String> QR_ERROR_CORRECTION_TOKENS = Set.of("L", "M", "Q", "H");
    static final Set<String> BARCODE_FORMAT_TOKENS = Set.of("EAN_8", "EAN_13", "UPC_A", "CODE_128");
    static final Set<String> PATH_COMMAND_TYPES = Set.of(
            "MOVE_TO", "LINE_TO", "QUAD_TO", "CUBIC_TO", "CLOSE"
    );

    static final Set<String> ABSENT_POLICY_TOKENS = Set.of("ERROR", "EMPTY");

    /**
     * Repeat items list element types: only the five StaticSchema scalars (ticket 11 §2);
     * color/imageRef/fontRef lists are not iterable.
     */
    static final Set<String> REPEAT_ITEM_TYPES = Set.of("text", "decimal", "date", "time", "boolean");

    static final Set<String> STACK_PACKING_SPEC_MEMBERS = Set.of("kind", "direction", "gapMm");
    static final Set<String> GRID_PACKING_SPEC_MEMBERS = Set.of(
            "kind", "columns", "columnGapMm", "rowGapMm"
    );

    static final Set<String> FILL_MEMBERS = Set.of("color");
    static final Set<String> STROKE_MM_MEMBERS = Set.of("color", "widthMm", "cap", "join");

    /** StrokePt: Text-only glyph stroke with pt unit (ticket 09 §3, §6). */
    static final Set<String> STROKE_PT_MEMBERS = Set.of("color", "widthPt", "cap", "join");

    // --- Binding contract (ticket 07 §6-§9, ticket 09 §8) -----------------------

    static final Set<String> BINDING_MEMBERS = Set.of(
            "bindingId", "targetPropertyRef", "source"
    );
    static final Set<String> TARGET_PROPERTY_REF_MEMBERS = Set.of(
            "rootPropertyId", "selectors"
    );
    static final Set<String> MEMBER_SELECTOR_MEMBERS = Set.of("kind", "name");
    static final Set<String> INDEX_SELECTOR_MEMBERS = Set.of("kind", "index");
    static final Set<String> SELECTOR_KINDS = Set.of("member", "index");

    // --- TemplateUse contract (ticket 12 §1, §3, §4) ----------------------------

    static final Set<String> TEMPLATE_USE_MEMBERS = Set.of(
            "useId", "templateRef", "contextSelector", "fills"
    );
    static final Set<String> TEMPLATE_REF_MEMBERS = Set.of("templateId");
    static final Set<String> CONTEXT_SELECTOR_MEMBERS = Set.of(
            "kind", "domain", "pointer", "contextAbsentPolicy"
    );
    static final Set<String> EMPTY_SELECTOR_MEMBERS = Set.of("kind");
    static final Set<String> SELECTOR_DOMAIN_MEMBERS = Set.of("kind", "loopId");
    static final Set<String> CONTEXT_ABSENT_POLICY_TOKENS = Set.of("ERROR", "SKIP");
    static final Set<String> USE_FILL_MEMBERS = Set.of("targetDefinitionId", "source");

    // --- Conditional contract (ticket 11 §1, §5, §7) ----------------------------

    static final Set<String> CONDITIONAL_MEMBERS = Set.of("condition", "absentPolicy");
    static final Set<String> CONDITIONAL_ABSENT_POLICY_TOKENS = Set.of("FALSE", "ERROR");
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

    /**
     * PACK placement (ticket 11 §7): Repeat-direct-child only; width/height modes restricted
     * to FIXED|HUG_CONTENT at validation time; no FILL/margins/insets/hints.
     */
    static final Set<String> PACK_PLACEMENT_MEMBERS = Set.of(
            "type", "widthMode", "heightMode", "widthMm", "heightMm",
            "minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm"
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
     * no placement; Repeat children use PACK (ticket 11 §7).
     */
    static PlacementVariant expectedVariant(NodeKind parentKind) {
        return switch (parentKind) {
            // Visual leaves and TemplateUse never host children; ABSOLUTE keeps the switch total.
            case CANVAS, FRAME, GROUP, TEXT, IMAGE, RECT, ELLIPSE, LINE, POLYGON, POLYLINE,
                    PATH, QRCODE, BARCODE, TEMPLATE_USE, CONDITIONAL -> PlacementVariant.ABSOLUTE;
            case STACK -> PlacementVariant.STACK;
            case GRID -> PlacementVariant.GRID;
            case REPEAT -> PlacementVariant.PACK;
        };
    }

    /** Per-kind width/height mode capability (ticket 09 §4 table). */
    static Set<SizeMode> sizeModes(NodeKind kind) {
        return switch (kind) {
            case GROUP -> Set.of(SizeMode.HUG_CONTENT);
            case RECT, ELLIPSE, QRCODE, BARCODE -> Set.of(SizeMode.FIXED, SizeMode.FILL);
            case CANVAS, FRAME, STACK, GRID, REPEAT, TEXT, LINE, POLYGON, POLYLINE, PATH,
                    TEMPLATE_USE, CONDITIONAL -> Set.of(SizeMode.FIXED, SizeMode.HUG_CONTENT, SizeMode.FILL);
            case IMAGE -> Set.of(SizeMode.FIXED, SizeMode.HUG_CONTENT, SizeMode.FILL);
        };
    }

    /**
     * Containers may nest (except Canvas, which is root-only); visual leaves, TemplateUse
     * and Repeat children have their own ContentModels, so the generic children member is
     * forbidden where not allowed.
     */
    static boolean allowsChildren(NodeKind kind) {
        return switch (kind) {
            case CANVAS, TEXT, IMAGE, RECT, ELLIPSE, LINE, POLYGON, POLYLINE, PATH,
                    QRCODE, BARCODE, TEMPLATE_USE -> false;
            case GROUP, FRAME, STACK, GRID, REPEAT, CONDITIONAL -> true;
        };
    }

    /**
     * Exact ValueType of a bindable leaf path. The binding policy separately decides
     * which node-kind/path identities are authorized; this catalog remains the owner
     * of permanent Node Property Identity types.
     */
    static String propertyValueType(String propertyPathPattern) {
        if (Set.of("render", "visible", "clipContent").contains(propertyPathPattern)) {
            return "boolean";
        }
        if (propertyPathPattern.endsWith(".color")
                || propertyPathPattern.endsWith("Color")) {
            return "color";
        }
        if ("imageRef".equals(propertyPathPattern)) {
            return "imageRef";
        }
        if (propertyPathPattern.endsWith(".fontRef")) {
            return "fontRef";
        }
        var leaf = propertyPathPattern.substring(
                propertyPathPattern.lastIndexOf('.') + 1);
        if (leaf.endsWith("Mm")
                || leaf.endsWith("Pt")
                || Set.of(
                "opacity", "rotationDeg", "scaleX", "scaleY", "originX", "originY",
                "row", "column", "rowSpan", "columnSpan", "fillWeight",
                "weight", "columns", "letterSpacingFactor", "factor",
                "maxLines", "minScale"
        ).contains(leaf)) {
            return "decimal";
        }
        return "text";
    }

    /** Authored wire name of a kind (lowerCamelCase; qrCode/barcode/templateUse are not plain lower). */
    static String wireName(NodeKind kind) {
        return switch (kind) {
            case QRCODE -> "qrCode";
            case BARCODE -> "barcode";
            case TEMPLATE_USE -> "templateUse";
            default -> kind.name().toLowerCase();
        };
    }

    private NodeContractCatalog() {
    }
}
