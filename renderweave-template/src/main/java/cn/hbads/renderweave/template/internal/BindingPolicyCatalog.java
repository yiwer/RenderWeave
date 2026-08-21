package cn.hbads.renderweave.template.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Global append-only BindingPolicyCatalog base registration (ticket 09 §8, T14b).
 *
 * <p>Every entry authorizes one existing {@code (nodeKind, propertyPathPattern)} Node Property
 * Identity as a possible Binding target; target type/default/validation always derive from the
 * NodeContractCatalog, never copied here. The registry is monotonically append-only: no mutation
 * API, no overlap detection needed yet (T16 consumes bindability). Entries are expanded
 * per-kind — there is no node-kind wildcard at runtime. Array patterns use {@code [*]}; concrete
 * Binding targets use fixed nonnegative indexes (T16).
 *
 * <p>Structural kinds not yet admitted (conditional/templateUse) have no Property Identities in
 * the kernel, so they are intentionally absent from this base registration.
 */
final class BindingPolicyCatalog {

    private static final List<String> NON_CANVAS_KINDS = List.of(
            "group", "frame", "stack", "grid", "repeat",
            "text", "image", "rect", "ellipse", "line",
            "polygon", "polyline", "path", "qrCode", "barcode",
            "templateUse", "conditional"
    );

    private static final List<String> NON_CANVAS_NON_GROUP_KINDS = NON_CANVAS_KINDS.stream()
            .filter(kind -> !"group".equals(kind))
            .toList();

    /** Every (nodeKind, propertyPathPattern) entry; concrete, immutable, append-only. */
    static final Set<Entry> ENTRIES = buildEntries();

    record Entry(String nodeKind, String propertyPathPattern) {
    }

    static boolean allows(String nodeKind, String propertyPathPattern) {
        return ENTRIES.contains(new Entry(nodeKind, propertyPathPattern));
    }

    /**
     * Exact closed DesignDSL ValueType for an authorized property identity.
     * Bindability remains owned here while the property type itself comes from
     * the Node contract authority.
     */
    static String valueType(String nodeKind, String propertyPathPattern) {
        if (!allows(nodeKind, propertyPathPattern)) {
            return null;
        }
        return NodeContractCatalog.propertyValueType(propertyPathPattern);
    }

    private static Set<Entry> buildEntries() {
        var entries = new HashSet<Entry>();
        add(entries, "canvas", List.of("backgroundColor"));
        for (var kind : NON_CANVAS_KINDS) {
            add(entries, kind, List.of(
                    "render", "visible", "opacity",
                    "transform.rotationDeg", "transform.scaleX", "transform.scaleY",
                    "transform.originX", "transform.originY"
            ));
            add(entries, kind, List.of(
                    "placement.xMm", "placement.yMm"
            ));
            add(entries, kind, List.of(
                    "placement.marginTopMm", "placement.marginRightMm",
                    "placement.marginBottomMm", "placement.marginLeftMm"
            ));
            add(entries, kind, List.of("placement.alignSelf"));
            add(entries, kind, List.of(
                    "placement.row", "placement.column", "placement.rowSpan",
                    "placement.columnSpan", "placement.horizontalAlignSelf",
                    "placement.verticalAlignSelf"
            ));
        }
        for (var kind : NON_CANVAS_NON_GROUP_KINDS) {
            add(entries, kind, List.of("placement.fillWeight"));
            add(entries, kind, List.of(
                    "placement.widthMm", "placement.heightMm",
                    "placement.minWidthMm", "placement.minHeightMm",
                    "placement.maxWidthMm", "placement.maxHeightMm"
            ));
            add(entries, kind, List.of(
                    "placement.rightInsetMm", "placement.bottomInsetMm"
            ));
        }
        for (var kind : List.of("frame", "stack", "grid")) {
            add(entries, kind, List.of(
                    "fill.color",
                    "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join",
                    "cornerRadii.topLeftMm", "cornerRadii.topRightMm",
                    "cornerRadii.bottomRightMm", "cornerRadii.bottomLeftMm",
                    "padding.topMm", "padding.rightMm", "padding.bottomMm", "padding.leftMm",
                    "clipContent"
            ));
        }
        add(entries, "stack", List.of("direction", "gapMm", "justifyContent", "alignItems"));
        add(entries, "grid", List.of(
                "rowGapMm", "columnGapMm",
                "rows[*].valueMm", "rows[*].weight",
                "columns[*].valueMm", "columns[*].weight"
        ));
        add(entries, "repeat", List.of(
                "itemLayout.direction", "itemLayout.gapMm", "itemLayout.columns",
                "itemLayout.columnGapMm", "itemLayout.rowGapMm",
                "instanceLayout.direction", "instanceLayout.gapMm", "instanceLayout.columns",
                "instanceLayout.columnGapMm", "instanceLayout.rowGapMm"
        ));
        add(entries, "text", List.of(
                "runs[*].text", "runs[*].fontRef", "runs[*].fontSizePt", "runs[*].color",
                "runs[*].letterSpacingPt", "runs[*].letterSpacingFactor", "runs[*].decoration",
                "writingMode", "horizontalAlign", "verticalAlign", "lineBreak", "overflow",
                "lineHeight.factor", "lineHeight.valuePt", "maxLines",
                "padding.topMm", "padding.rightMm", "padding.bottomMm", "padding.leftMm",
                "stroke.color", "stroke.widthPt", "stroke.cap", "stroke.join",
                "minScale"
        ));
        add(entries, "image", List.of("imageRef", "fit", "sampling"));
        add(entries, "rect", List.of(
                "fill.color",
                "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join",
                "cornerRadii.topLeftMm", "cornerRadii.topRightMm",
                "cornerRadii.bottomRightMm", "cornerRadii.bottomLeftMm"
        ));
        add(entries, "ellipse", List.of(
                "fill.color", "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join"
        ));
        add(entries, "line", List.of(
                "start.xMm", "start.yMm", "end.xMm", "end.yMm",
                "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join"
        ));
        add(entries, "polygon", List.of(
                "points[*].xMm", "points[*].yMm",
                "fill.color", "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join"
        ));
        add(entries, "polyline", List.of(
                "points[*].xMm", "points[*].yMm",
                "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join"
        ));
        add(entries, "path", List.of(
                "commands[*].xMm", "commands[*].yMm", "commands[*].cxMm", "commands[*].cyMm",
                "commands[*].c1xMm", "commands[*].c1yMm", "commands[*].c2xMm", "commands[*].c2yMm",
                "fill.color", "stroke.color", "stroke.widthMm", "stroke.cap", "stroke.join",
                "fillRule"
        ));
        add(entries, "qrCode", List.of(
                "content", "errorCorrectionLevel", "foregroundColor", "backgroundColor"
        ));
        add(entries, "barcode", List.of(
                "format", "value", "foregroundColor", "backgroundColor"
        ));
        return Set.copyOf(entries);
    }

    private static void add(Set<Entry> entries, String nodeKind, List<String> patterns) {
        for (var pattern : patterns) {
            entries.add(new Entry(nodeKind, pattern));
        }
    }

    private BindingPolicyCatalog() {
    }
}
