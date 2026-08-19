package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class CanonicalDesignDslAuthority implements DesignDslAuthority {

    private static final byte[] HASH_DOMAIN =
            "renderweave-design-content/1\0".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> ROOT_MEMBERS = Set.of(
            "dslVersion", "expressionProfile", "displayName", "description",
            "definitions", "designRoot"
    );
    private static final Set<String> CANVAS_MEMBERS = Set.of(
            "nodeId", "kind", "displayName", "widthMm", "heightMm", "backgroundColor",
            "bleed", "bindings", "children"
    );
    private static final Set<String> BLEED_MEMBERS = Set.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    private static final List<String> BLEED_MEMBER_ORDER = List.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Pattern RGBA = Pattern.compile("^#[0-9A-F]{8}$");

    private final StrictJsonParser parser = new StrictJsonParser();
    private final CanonicalJsonWriter writer = new CanonicalJsonWriter();

    @Override
    public Admission admit(byte[] rawUtf8) {
        try {
            var parsed = parser.parse(rawUtf8);
            var normalized = validateAndNormalize(parsed);
            var canonical = writer.write(normalized);
            return new Admitted(canonical, contentHash(canonical));
        } catch (CanonicalJsonWriter.CanonicalLimitException limit) {
            return new Rejected(
                    FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                    FailureStage.DESIGN_CANONICAL_COUNT,
                    "",
                    Optional.of(Limit.CANONICAL_BYTES)
            );
        } catch (DesignDslFailureException failure) {
            return failure.rejection();
        }
    }

    private JsonValue validateAndNormalize(JsonValue parsed) throws DesignDslFailureException {
        rejectNull(parsed, "");
        var root = object(parsed, "");
        rejectUnknown(root, ROOT_MEMBERS, "");
        exactVersion(root, "dslVersion", "renderweave-design/1.0", "/dslVersion");
        exactVersion(
                root,
                "expressionProfile",
                "renderweave-expression/1.0",
                "/expressionProfile"
        );
        var displayName = metadata(root, "displayName", 128, false, "/displayName");
        var definitions = array(required(root, "definitions", "/definitions"), "/definitions");
        if (!definitions.items().isEmpty()) {
            throw failure(
                    FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED,
                    "/definitions"
            );
        }

        var canvas = object(required(root, "designRoot", "/designRoot"), "/designRoot");
        rejectUnknown(canvas, CANVAS_MEMBERS, "/designRoot");
        var kind = string(required(canvas, "kind", "/designRoot/kind"), "/designRoot/kind");
        if (!"canvas".equals(kind)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, "/designRoot/kind");
        }
        var nodeId = string(required(canvas, "nodeId", "/designRoot/nodeId"),
                "/designRoot/nodeId");
        if (!UUID_V4.matcher(nodeId).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, "/designRoot/nodeId");
        }
        positiveDecimal(canvas, "widthMm", "/designRoot/widthMm");
        positiveDecimal(canvas, "heightMm", "/designRoot/heightMm");
        if (canvas.members().containsKey("backgroundColor")) {
            var color = string(
                    canvas.members().get("backgroundColor"),
                    "/designRoot/backgroundColor"
            );
            if (!RGBA.matcher(color).matches()) {
                throw failure(
                        FailureCode.DESIGN_VALUE_INVALID,
                        "/designRoot/backgroundColor"
                );
            }
        }
        if (canvas.members().containsKey("bleed")) {
            var bleed = object(canvas.members().get("bleed"), "/designRoot/bleed");
            rejectUnknown(bleed, BLEED_MEMBERS, "/designRoot/bleed");
            for (var member : BLEED_MEMBER_ORDER) {
                nonNegativeDecimal(
                        bleed,
                        member,
                        "/designRoot/bleed/" + member
                );
            }
        }
        var bindings = array(required(canvas, "bindings", "/designRoot/bindings"),
                "/designRoot/bindings");
        if (!bindings.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, "/designRoot/bindings");
        }
        var children = array(required(canvas, "children", "/designRoot/children"),
                "/designRoot/children");
        var normalizedChildren = validateChildren(
                children,
                "/designRoot/children",
                NodeContractCatalog.NodeKind.CANVAS,
                null,
                new java.util.HashSet<>()
        );

        var normalizedCanvas = new LinkedHashMap<>(canvas.members());
        normalizedCanvas.put("children", normalizedChildren);
        if (canvas.members().containsKey("displayName")) {
            normalizedCanvas.put(
                    "displayName",
                    new JsonValue.StringValue(metadata(
                            canvas, "displayName", 128, false, "/designRoot/displayName"
                    ))
            );
        }
        var normalizedRoot = new LinkedHashMap<>(root.members());
        normalizedRoot.put("displayName", new JsonValue.StringValue(displayName));
        if (root.members().containsKey("description")) {
            var description = metadata(root, "description", 2048, true, "/description");
            if (description.isEmpty()) {
                normalizedRoot.remove("description");
            } else {
                normalizedRoot.put("description", new JsonValue.StringValue(description));
            }
        }
        normalizedRoot.put("designRoot", new JsonValue.ObjectValue(normalizedCanvas));
        return new JsonValue.ObjectValue(normalizedRoot);
    }

    /** Recursively validate container children; the tree keeps authored order (paint z-order). */
    private JsonValue.ArrayValue validateChildren(
            JsonValue.ArrayValue children,
            String pointer,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection,
            Set<String> seenNodeIds
    ) throws DesignDslFailureException {
        var normalized = new ArrayList<JsonValue>();
        for (int index = 0; index < children.items().size(); index++) {
            var childPointer = pointer + "/" + index;
            var child = object(children.items().get(index), childPointer);
            normalized.add(validateNonCanvasNode(
                    child, childPointer, parentKind, parentDirection, seenNodeIds));
        }
        return new JsonValue.ArrayValue(normalized);
    }

    private JsonValue.ObjectValue validateNonCanvasNode(
            JsonValue.ObjectValue node,
            String pointer,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection,
            Set<String> seenNodeIds
    ) throws DesignDslFailureException {
        var kindToken = string(required(node, "kind", pointer + "/kind"), pointer + "/kind");
        var kind = NodeContractCatalog.KIND_BY_NAME.get(kindToken);
        if (kind == null) {
            if (NodeContractCatalog.FUTURE_KINDS.contains(kindToken)) {
                throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, pointer + "/kind");
            }
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
        if (kind == NodeContractCatalog.NodeKind.CANVAS) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/kind");
        }
        rejectUnknown(node, allowedMembers(kind), pointer);
        var nodeId = string(required(node, "nodeId", pointer + "/nodeId"), pointer + "/nodeId");
        if (!UUID_V4.matcher(nodeId).matches() || !seenNodeIds.add(nodeId)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/nodeId");
        }
        var bindings = array(required(node, "bindings", pointer + "/bindings"), pointer + "/bindings");
        if (!bindings.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, pointer + "/bindings");
        }
        var normalized = new LinkedHashMap<>(node.members());
        if (node.members().containsKey("displayName")) {
            normalized.put(
                    "displayName",
                    new JsonValue.StringValue(metadata(
                            node, "displayName", 128, false, pointer + "/displayName"
                    ))
            );
        }
        if (node.members().containsKey("render")) {
            booleanValue(node, "render", pointer + "/render");
        }
        if (node.members().containsKey("visible")) {
            booleanValue(node, "visible", pointer + "/visible");
        }
        if (node.members().containsKey("opacity")) {
            rangedDecimal(node, "opacity", pointer + "/opacity", 0, 1);
        }
        if (node.members().containsKey("transform")) {
            validateTransform(node.members().get("transform"), pointer + "/transform");
        }
        var placement = object(required(node, "placement", pointer + "/placement"),
                pointer + "/placement");
        validatePlacement(placement, pointer + "/placement", kind, parentKind, parentDirection);
        String ownDirection = null;
        switch (kind) {
            case FRAME, STACK, GRID -> validateAppearanceMembers(node, pointer);
            case CANVAS, GROUP -> {
            }
        }
        switch (kind) {
            case STACK -> ownDirection = validateStackMembers(node, pointer);
            case GRID -> validateGridMembers(node, pointer);
            case CANVAS, FRAME, GROUP -> {
            }
        }
        var children = array(required(node, "children", pointer + "/children"), pointer + "/children");
        normalized.put(
                "children",
                validateChildren(children, pointer + "/children", kind, ownDirection, seenNodeIds)
        );
        return new JsonValue.ObjectValue(normalized);
    }

    private void validateAppearanceMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("fill")) {
            validateFill(node.members().get("fill"), pointer + "/fill");
        }
        if (node.members().containsKey("stroke")) {
            validateStrokeMm(node.members().get("stroke"), pointer + "/stroke");
        }
        if (node.members().containsKey("cornerRadii")) {
            validateCornerRadii(node.members().get("cornerRadii"), pointer + "/cornerRadii");
        }
        if (node.members().containsKey("padding")) {
            validatePadding(node.members().get("padding"), pointer + "/padding");
        }
        if (node.members().containsKey("clipContent")) {
            booleanValue(node, "clipContent", pointer + "/clipContent");
        }
    }

    private String validateStackMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        String direction = "COLUMN";
        if (node.members().containsKey("direction")) {
            enumMember(node, "direction", NodeContractCatalog.STACK_DIRECTION_TOKENS,
                    pointer + "/direction");
            direction = string(node.members().get("direction"), pointer + "/direction");
        }
        if (node.members().containsKey("gapMm")) {
            nonNegativeDecimal(node, "gapMm", pointer + "/gapMm");
        }
        if (node.members().containsKey("justifyContent")) {
            enumMember(node, "justifyContent", NodeContractCatalog.JUSTIFY_CONTENT_TOKENS,
                    pointer + "/justifyContent");
        }
        if (node.members().containsKey("alignItems")) {
            enumMember(node, "alignItems", NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                    pointer + "/alignItems");
        }
        return direction;
    }

    private void validateGridMembers(
            JsonValue.ObjectValue node,
            String pointer
    ) throws DesignDslFailureException {
        if (node.members().containsKey("rowGapMm")) {
            nonNegativeDecimal(node, "rowGapMm", pointer + "/rowGapMm");
        }
        if (node.members().containsKey("columnGapMm")) {
            nonNegativeDecimal(node, "columnGapMm", pointer + "/columnGapMm");
        }
        validateTracks(node, "rows", pointer + "/rows");
        validateTracks(node, "columns", pointer + "/columns");
    }

    private void validateTracks(
            JsonValue.ObjectValue node,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var tracks = array(required(node, name, pointer), pointer);
        if (tracks.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        for (int index = 0; index < tracks.items().size(); index++) {
            var trackPointer = pointer + "/" + index;
            var track = object(tracks.items().get(index), trackPointer);
            var type = string(required(track, "type", trackPointer + "/type"),
                    trackPointer + "/type");
            switch (type) {
                case "FIXED" -> {
                    rejectUnknown(track, Set.of("type", "valueMm"), trackPointer);
                    positiveDecimal(track, "valueMm", trackPointer + "/valueMm");
                }
                case "FRACTION" -> {
                    rejectUnknown(track, Set.of("type", "weight"), trackPointer);
                    positiveDecimal(track, "weight", trackPointer + "/weight");
                }
                case "AUTO" -> rejectUnknown(track, Set.of("type"), trackPointer);
                default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, trackPointer + "/type");
            }
        }
    }

    private void validateFill(JsonValue value, String pointer) throws DesignDslFailureException {
        var fill = object(value, pointer);
        rejectUnknown(fill, NodeContractCatalog.FILL_MEMBERS, pointer);
        colorMember(fill, "color", pointer + "/color");
    }

    private void validateStrokeMm(JsonValue value, String pointer) throws DesignDslFailureException {
        var stroke = object(value, pointer);
        rejectUnknown(stroke, NodeContractCatalog.STROKE_MM_MEMBERS, pointer);
        colorMember(stroke, "color", pointer + "/color");
        positiveDecimal(stroke, "widthMm", pointer + "/widthMm");
        enumMember(stroke, "cap", NodeContractCatalog.STROKE_CAP_TOKENS, pointer + "/cap");
        enumMember(stroke, "join", NodeContractCatalog.STROKE_JOIN_TOKENS, pointer + "/join");
    }

    private void validatePadding(JsonValue value, String pointer) throws DesignDslFailureException {
        var padding = object(value, pointer);
        rejectUnknown(padding, NodeContractCatalog.PADDING_MEMBERS, pointer);
        for (var member : NodeContractCatalog.PADDING_MEMBER_ORDER) {
            nonNegativeDecimal(padding, member, pointer + "/" + member);
        }
    }

    private void validateCornerRadii(JsonValue value, String pointer) throws DesignDslFailureException {
        var radii = object(value, pointer);
        rejectUnknown(radii, NodeContractCatalog.CORNER_RADII_MEMBERS, pointer);
        for (var member : NodeContractCatalog.CORNER_RADII_MEMBER_ORDER) {
            nonNegativeDecimal(radii, member, pointer + "/" + member);
        }
    }

    private void colorMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var color = string(required(object, name, pointer), pointer);
        if (!RGBA.matcher(color).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private Set<String> allowedMembers(NodeContractCatalog.NodeKind kind) {
        var members = new java.util.HashSet<>(NodeContractCatalog.COMMON_NODE_MEMBERS);
        members.addAll(NodeContractCatalog.CONTAINER_MEMBERS);
        switch (kind) {
            case FRAME, STACK, GRID -> members.addAll(NodeContractCatalog.APPEARANCE_MEMBERS);
            case CANVAS, GROUP -> {
            }
        }
        switch (kind) {
            case STACK -> members.addAll(NodeContractCatalog.STACK_MEMBERS);
            case GRID -> members.addAll(NodeContractCatalog.GRID_MEMBERS);
            case CANVAS, FRAME, GROUP -> {
            }
        }
        return Set.copyOf(members);
    }

    private void validatePlacement(
            JsonValue.ObjectValue placement,
            String pointer,
            NodeContractCatalog.NodeKind kind,
            NodeContractCatalog.NodeKind parentKind,
            String parentDirection
    ) throws DesignDslFailureException {
        var variantToken = string(required(placement, "type", pointer + "/type"), pointer + "/type");
        var expected = NodeContractCatalog.expectedVariant(parentKind);
        if ("PACK".equals(variantToken)) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, pointer + "/type");
        }
        var variant = switch (variantToken) {
            case "ABSOLUTE" -> NodeContractCatalog.PlacementVariant.ABSOLUTE;
            case "STACK" -> NodeContractCatalog.PlacementVariant.STACK;
            case "GRID" -> NodeContractCatalog.PlacementVariant.GRID;
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        };
        if (variant != expected) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/type");
        }
        switch (variant) {
            case ABSOLUTE -> {
                rejectUnknown(placement, NodeContractCatalog.ABSOLUTE_PLACEMENT_MEMBERS, pointer);
                decimalMember(placement, "xMm", pointer + "/xMm");
                decimalMember(placement, "yMm", pointer + "/yMm");
            }
            case STACK -> rejectUnknown(placement, NodeContractCatalog.STACK_PLACEMENT_MEMBERS, pointer);
            case GRID -> {
                rejectUnknown(placement, NodeContractCatalog.GRID_PLACEMENT_MEMBERS, pointer);
                nonNegativeIntegerMember(placement, "row", pointer + "/row");
                nonNegativeIntegerMember(placement, "column", pointer + "/column");
                if (placement.members().containsKey("rowSpan")) {
                    positiveIntegerMember(placement, "rowSpan", pointer + "/rowSpan");
                }
                if (placement.members().containsKey("columnSpan")) {
                    positiveIntegerMember(placement, "columnSpan", pointer + "/columnSpan");
                }
                if (placement.members().containsKey("horizontalAlignSelf")) {
                    enumMember(
                            placement, "horizontalAlignSelf",
                            NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                            pointer + "/horizontalAlignSelf"
                    );
                }
                if (placement.members().containsKey("verticalAlignSelf")) {
                    enumMember(
                            placement, "verticalAlignSelf",
                            NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                            pointer + "/verticalAlignSelf"
                    );
                }
            }
            case PACK -> {
                // unreachable: PACK rejected above
            }
        }

        var widthMode = sizeModeMember(placement, "widthMode", pointer + "/widthMode");
        var heightMode = sizeModeMember(placement, "heightMode", pointer + "/heightMode");
        var modes = NodeContractCatalog.sizeModes(kind);
        if (!modes.contains(widthMode)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMode");
        }
        if (!modes.contains(heightMode)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMode");
        }
        if (widthMode == NodeContractCatalog.SizeMode.FIXED) {
            positiveDecimal(placement, "widthMm", pointer + "/widthMm");
        } else if (placement.members().containsKey("widthMm")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
        }
        if (heightMode == NodeContractCatalog.SizeMode.FIXED) {
            positiveDecimal(placement, "heightMm", pointer + "/heightMm");
        } else if (placement.members().containsKey("heightMm")) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMm");
        }

        if (kind == NodeContractCatalog.NodeKind.GROUP) {
            for (var member : List.of(
                    "minWidthMm", "minHeightMm", "maxWidthMm", "maxHeightMm")) {
                if (placement.members().containsKey(member)) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/" + member);
                }
            }
        } else {
            validateMinMax(placement, pointer);
        }

        if (variant == NodeContractCatalog.PlacementVariant.ABSOLUTE) {
            if (placement.members().containsKey("rightInsetMm")) {
                if (widthMode != NodeContractCatalog.SizeMode.FILL) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/rightInsetMm");
                }
                decimalMember(placement, "rightInsetMm", pointer + "/rightInsetMm");
            }
            if (placement.members().containsKey("bottomInsetMm")) {
                if (heightMode != NodeContractCatalog.SizeMode.FILL) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/bottomInsetMm");
                }
                decimalMember(placement, "bottomInsetMm", pointer + "/bottomInsetMm");
            }
        }
        if (variant == NodeContractCatalog.PlacementVariant.STACK) {
            for (var member : List.of(
                    "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm")) {
                if (placement.members().containsKey(member)) {
                    decimalMember(placement, member, pointer + "/" + member);
                }
            }
            if (placement.members().containsKey("alignSelf")) {
                enumMember(placement, "alignSelf", NodeContractCatalog.ALIGN_ITEMS_TOKENS,
                        pointer + "/alignSelf");
                var crossAxisFill = "ROW".equals(parentDirection)
                        ? heightMode == NodeContractCatalog.SizeMode.FILL
                        : widthMode == NodeContractCatalog.SizeMode.FILL;
                if (crossAxisFill) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/alignSelf");
                }
            }
            if (placement.members().containsKey("fillWeight")) {
                var mainAxisFill = "ROW".equals(parentDirection)
                        ? widthMode == NodeContractCatalog.SizeMode.FILL
                        : heightMode == NodeContractCatalog.SizeMode.FILL;
                if (!mainAxisFill) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/fillWeight");
                }
                positiveDecimal(placement, "fillWeight", pointer + "/fillWeight");
            }
        }
        if (variant == NodeContractCatalog.PlacementVariant.GRID) {
            for (var member : List.of(
                    "marginTopMm", "marginRightMm", "marginBottomMm", "marginLeftMm")) {
                if (placement.members().containsKey(member)) {
                    decimalMember(placement, member, pointer + "/" + member);
                }
            }
            if (widthMode == NodeContractCatalog.SizeMode.FILL
                    && placement.members().containsKey("horizontalAlignSelf")) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/horizontalAlignSelf");
            }
            if (heightMode == NodeContractCatalog.SizeMode.FILL
                    && placement.members().containsKey("verticalAlignSelf")) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/verticalAlignSelf");
            }
        }
    }

    private void validateMinMax(
            JsonValue.ObjectValue placement,
            String pointer
    ) throws DesignDslFailureException {
        for (var axis : List.of("Width", "Height")) {
            var minName = "min" + axis + "Mm";
            var maxName = "max" + axis + "Mm";
            if (placement.members().containsKey(minName)) {
                nonNegativeDecimal(placement, minName, pointer + "/" + minName);
            }
            if (placement.members().containsKey(maxName)) {
                positiveDecimal(placement, maxName, pointer + "/" + maxName);
            }
            if (placement.members().containsKey(minName) && placement.members().containsKey(maxName)) {
                var min = decimalValue(placement.members().get(minName), pointer + "/" + minName);
                var max = decimalValue(placement.members().get(maxName), pointer + "/" + maxName);
                if (min.compareTo(max) > 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/" + minName);
                }
            }
            if (placement.members().containsKey("widthMm") && "Width".equals(axis)) {
                var fixed = decimalValue(placement.members().get("widthMm"), pointer + "/widthMm");
                var min = placement.members().containsKey(minName)
                        ? decimalValue(placement.members().get(minName), pointer + "/" + minName)
                        : null;
                var max = placement.members().containsKey(maxName)
                        ? decimalValue(placement.members().get(maxName), pointer + "/" + maxName)
                        : null;
                if (min != null && fixed.compareTo(min) < 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
                }
                if (max != null && fixed.compareTo(max) > 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/widthMm");
                }
            }
            if (placement.members().containsKey("heightMm") && "Height".equals(axis)) {
                var fixed = decimalValue(placement.members().get("heightMm"), pointer + "/heightMm");
                var min = placement.members().containsKey(minName)
                        ? decimalValue(placement.members().get(minName), pointer + "/" + minName)
                        : null;
                var max = placement.members().containsKey(maxName)
                        ? decimalValue(placement.members().get(maxName), pointer + "/" + maxName)
                        : null;
                if (min != null && fixed.compareTo(min) < 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMm");
                }
                if (max != null && fixed.compareTo(max) > 0) {
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer + "/heightMm");
                }
            }
        }
    }

    private void validateTransform(JsonValue value, String pointer) throws DesignDslFailureException {
        var transform = object(value, pointer);
        rejectUnknown(transform, NodeContractCatalog.TRANSFORM_MEMBERS, pointer);
        decimalMember(transform, "rotationDeg", pointer + "/rotationDeg");
        nonZeroDecimal(transform, "scaleX", pointer + "/scaleX");
        nonZeroDecimal(transform, "scaleY", pointer + "/scaleY");
        rangedDecimal(transform, "originX", pointer + "/originX", 0, 1);
        rangedDecimal(transform, "originY", pointer + "/originY", 0, 1);
    }

    private NodeContractCatalog.SizeMode sizeModeMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var token = string(required(object, name, pointer), pointer);
        return switch (token) {
            case "FIXED" -> NodeContractCatalog.SizeMode.FIXED;
            case "HUG_CONTENT" -> NodeContractCatalog.SizeMode.HUG_CONTENT;
            case "FILL" -> NodeContractCatalog.SizeMode.FILL;
            default -> throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        };
    }

    private void booleanValue(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        if (!(required(object, name, pointer) instanceof JsonValue.BooleanValue)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
    }

    private void enumMember(
            JsonValue.ObjectValue object,
            String name,
            Set<String> allowed,
            String pointer
    ) throws DesignDslFailureException {
        var token = string(required(object, name, pointer), pointer);
        if (!allowed.contains(token)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void decimalMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            new BigDecimal(((JsonValue.NumberValue) value).token());
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private BigDecimal decimalValue(JsonValue value, String pointer) throws DesignDslFailureException {
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            return new BigDecimal(number.token());
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonZeroDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() == 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void rangedDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer,
            int minimum,
            int maximum
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() < minimum || value.compareTo(new BigDecimal(maximum)) > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonNegativeIntegerMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void positiveIntegerMember(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = decimalValue(required(object, name, pointer), pointer);
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void rejectNull(JsonValue value, String pointer) throws DesignDslFailureException {
        switch (value) {
            case JsonValue.NullValue ignored ->
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            case JsonValue.ObjectValue object -> {
                for (var entry : object.members().entrySet()) {
                    rejectNull(entry.getValue(), pointer + "/" + escape(entry.getKey()));
                }
            }
            case JsonValue.ArrayValue array -> {
                for (int index = 0; index < array.items().size(); index++) {
                    rejectNull(array.items().get(index), pointer + "/" + index);
                }
            }
            default -> {
            }
        }
    }

    private void rejectUnknown(
            JsonValue.ObjectValue object,
            Set<String> allowed,
            String pointer
    ) throws DesignDslFailureException {
        for (var name : object.members().keySet()) {
            if (!allowed.contains(name)) {
                throw failure(FailureCode.DESIGN_MEMBER_UNKNOWN, pointer + "/" + escape(name));
            }
        }
    }

    private void exactVersion(
            JsonValue.ObjectValue object,
            String name,
            String expected,
            String pointer
    ) throws DesignDslFailureException {
        var actual = string(required(object, name, pointer), pointer);
        if (!expected.equals(actual)) {
            throw failure(FailureCode.DESIGN_VERSION_UNSUPPORTED, pointer);
        }
    }

    private String metadata(
            JsonValue.ObjectValue object,
            String name,
            int maximumCodePoints,
            boolean blankMayDisappear,
            String pointer
    ) throws DesignDslFailureException {
        var value = string(required(object, name, pointer), pointer).trim();
        var length = value.codePointCount(0, value.length());
        if ((!blankMayDisappear && length == 0) || length > maximumCodePoints) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        return value;
    }

    private void positiveDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            if (new BigDecimal(number.token()).signum() <= 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonNegativeDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            if (new BigDecimal(number.token()).signum() < 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private JsonValue required(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = object.members().get(name);
        if (value == null) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        return value;
    }

    private JsonValue.ObjectValue object(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ObjectValue object) {
            return object;
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private JsonValue.ArrayValue array(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ArrayValue array) {
            return array;
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private String string(JsonValue value, String pointer) throws DesignDslFailureException {
        if (value instanceof JsonValue.StringValue string) {
            return string.value();
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private DesignDslFailureException failure(FailureCode code, String pointer) {
        return new DesignDslFailureException(new Rejected(
                code,
                FailureStage.DESIGN_SEMANTIC_VALIDATION,
                pointer,
                Optional.empty()
        ));
    }

    private String contentHash(byte[] canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(HASH_DOMAIN);
            digest.update(canonical);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
