package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalAbsent;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalError;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalOutcome;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalValue;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * MATERIALIZATION/ASSET_RESOLUTION（冻结规格 stage 7）：在 {@link AssetAdmission} 成功后执行
 * 结构展开（render:false 剪枝、Conditional absentPolicy、Repeat loop frame、TemplateUse
 * 隔离 child invocation + fills）、Binding overlay 后以重构文档按 nodeId 换入重新 admission
 * 完成 exact 重验、消费点 Asset 串行 resolve（首个 demand 失败即停，逻辑 AssetRef 替换为
 * 请求级 resourceId）。侧 sidecar 容量受限、请求级。
 */
final class Materializer {

    private static final RenderingPipelineCapacityGuard CAPACITY_GUARD =
            new RenderingPipelineCapacityGuard();

    record MaterializedTree(
            MaterializedNode root,
            List<ResourceEntry> resources,
            List<SidecarEntry> sidecar
    ) {
    }

    record MaterializedNode(
            String kind,
            ObjectNode members,
            List<MaterializedNode> children,
            String occurrencePath
    ) {
    }

    private record PackingShape(
            String kind,
            int rows,
            int columns,
            long generatedEntries
    ) {
    }

    private record ExactContentIdentity(
            AssetKind kind,
            String sha256,
            long byteLength,
            String mediaType
    ) {
    }

    record ResourceEntry(
            String resourceId,
            String kind,
            String fetchUrl,
            long leaseExpiresAtEpochSecond,
            String sha256,
            String mediaType,
            long byteLength,
            String acceptanceProfileId,
            String assetId,
            String contentVersion,
            String occurrencePath,
            String consumerPropertyRef,
            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.TechnicalDescriptor technicalDescriptor
    ) {
    }

    record SidecarEntry(String occurrencePath, String sourceNodeId) {
    }

    sealed interface MaterializationOutcome permits Materialized, MaterializationFailed {
    }

    record Materialized(MaterializedTree tree) implements MaterializationOutcome {
    }

    record MaterializationFailed(EvaluationStage stage, RenderingProblem problem)
            implements MaterializationOutcome, AssetResolutionStep, ValueStep {
    }

    private final ClosureSnapshot closure;
    private final DesignSemanticAuthority semantics;
    private final DesignDslAuthority dslAuthority;
    private final AssetResolutionPort assets;
    private final DefinitionEngine.CapabilityProvider capabilities;
    private final RenderRequestId renderRequestId;
    private final AssetResolutionPort.RendererAudience audience;
    private final long deadlineEpochMilli;
    private final String ownerScope;

    private final Map<String, ObjectNode> documentsByTemplate = new HashMap<>();
    private final Map<String, DefinitionEngine> enginesByTemplate = new HashMap<>();
    private final List<ResourceEntry> resources = new ArrayList<>();
    private final List<SidecarEntry> sidecar = new ArrayList<>();
    private final Set<ExactContentIdentity> exactContents = new HashSet<>();
    private final RenderingPipelineCapacityGuard.RequestTracker requestCapacity;
    private int occurrences;
    private int nodes;
    private int invocations;
    private int compositionViewports;
    private int loopFrames;

    private Materializer(
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            DefinitionEngine.CapabilityProvider capabilities,
            RenderRequestId renderRequestId,
            AssetResolutionPort.RendererAudience audience,
            long deadlineEpochMilli,
            String ownerScope,
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity
    ) {
        this.closure = closure;
        this.semantics = semantics;
        this.dslAuthority = dslAuthority;
        this.assets = assets;
        this.capabilities = capabilities;
        this.renderRequestId = renderRequestId;
        this.audience = audience;
        this.deadlineEpochMilli = deadlineEpochMilli;
        this.ownerScope = ownerScope;
        this.requestCapacity = Objects.requireNonNull(requestCapacity, "requestCapacity");
    }

    static MaterializationOutcome materialize(
            AssetAdmission.Admitted assetAdmission,
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            DefinitionEngine.CapabilityProvider capabilities,
            AdmittedRenderInput admittedInput,
            RenderRequestId renderRequestId,
            AssetResolutionPort.RendererAudience audience,
            long deadlineEpochMilli
    ) {
        return materialize(
                assetAdmission,
                closure,
                semantics,
                dslAuthority,
                assets,
                capabilities,
                admittedInput,
                renderRequestId,
                audience,
                deadlineEpochMilli,
                CAPACITY_GUARD.newRequestTracker());
    }

    static MaterializationOutcome materialize(
            AssetAdmission.Admitted assetAdmission,
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            DefinitionEngine.CapabilityProvider capabilities,
            AdmittedRenderInput admittedInput,
            RenderRequestId renderRequestId,
            AssetResolutionPort.RendererAudience audience,
            long deadlineEpochMilli,
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity
    ) {
        Objects.requireNonNull(assetAdmission, "assetAdmission");
        Objects.requireNonNull(closure, "closure");
        var materializer = new Materializer(
                closure, semantics, dslAuthority, assets, capabilities,
                renderRequestId, audience, deadlineEpochMilli,
                closure.ownerScope().value(), requestCapacity);

        var rootSnapshot = closure.snapshots().stream()
                .filter(snapshot -> snapshot.templateId().equals(closure.rootTemplateId()))
                .findFirst()
                .orElse(null);
        if (rootSnapshot == null) {
            return failed(EvaluationStage.TEMPLATE_CLOSURE, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var document = materializer.documentOf(rootSnapshot);
        if (document == null) {
            return failed(EvaluationStage.TEMPLATE_CLOSURE, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var designRoot = childObject(document, "designRoot");
        if (designRoot == null) {
            return failed(EvaluationStage.TEMPLATE_CLOSURE, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }

        var invocationFailure = materializer.reserveTemplateInvocation(1);
        if (invocationFailure != null) {
            return invocationFailure;
        }
        var rootScope = new InvocationScope(
                admittedInput.rootDocument(),
                admittedInput.customs(),
                materializer.engineOf(rootSnapshot),
                DefinitionEngine.LoopFrames.EMPTY,
                capabilities,
                CapabilityCallPosition.root(
                        rootSnapshot.templateId().value(), rootSnapshot.revision()),
                1,
                0);
        var children = new ArrayList<MaterializedNode>();
        var expandFailure = materializer.expandNode(
                designRoot, rootSnapshot, rootScope, "", children);
        if (expandFailure != null) {
            return expandFailure;
        }
        if (children.size() != 1) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        return new Materialized(new MaterializedTree(
                children.get(0), List.copyOf(materializer.resources),
                List.copyOf(materializer.sidecar)));
    }

    // ------------------------------------------------------------------
    // expansion
    // ------------------------------------------------------------------

    private MaterializationOutcome expandNode(
            ObjectNode node,
            TemplateSnapshot snapshot,
            InvocationScope scope,
            String path,
            List<MaterializedNode> output
    ) {
        if (!(node.members().get("kind") instanceof Text kindValue)) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var kind = kindValue.value();
        if (node.members().get("render") instanceof Bool render && !render.value()) {
            return null;
        }
        var overlay = overlayBindings(node, snapshot, scope);
        if (overlay instanceof OverlayFailed overlayFailed) {
            return overlayFailed.failure();
        }
        var evaluatedNode = ((Overlaid) overlay).node();
        if (evaluatedNode.members().get("render") instanceof Bool render && !render.value()) {
            return null;
        }
        return switch (kind) {
            case "conditional" -> expandConditional(
                    evaluatedNode, snapshot, scope, path, output);
            case "repeat" -> expandRepeat(evaluatedNode, snapshot, scope, path, output);
            case "templateUse" -> expandTemplateUse(
                    evaluatedNode, snapshot, scope, path, output);
            default -> materializeNode(
                    evaluatedNode, snapshot, scope, path, output, kind);
        };
    }

    private MaterializationOutcome expandConditional(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        var conditionWire = node.members().get("condition");
        if (conditionWire == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var condition = scope.definitions().resolveSource(conditionWire, scope);
        if (condition instanceof EvalError error) {
            return valueFailure(error);
        }
        boolean flag;
        if (condition instanceof EvalAbsent) {
            if ("FALSE".equals(textMember(node, "absentPolicy"))) {
                flag = false;
            } else {
                return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
            }
        } else if (((EvalValue) condition).value() instanceof DesignValue.Bool bool) {
            flag = bool.value();
        } else {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
        }
        if (!flag) {
            return null;
        }
        // true Conditional lowers 为普通 frame：无 fill/stroke/padding/clip。
        return expandFrame(node, snapshot, scope, path, output);
    }

    private MaterializationOutcome expandRepeat(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        var itemsWire = node.members().get("items");
        var loopId = textMember(node, "loopId");
        if (itemsWire == null || loopId == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        MaterializationOutcome capacityFailure = capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_NESTING_DEPTH,
                scope.repeatNestingDepth() + 1));
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var items = scope.definitions().resolveSource(itemsWire, scope);
        if (items instanceof EvalError error) {
            return valueFailure(error);
        }
        List<DesignValue> itemList;
        if (items instanceof EvalAbsent) {
            if ("EMPTY".equals(textMember(node, "absentPolicy"))) {
                itemList = List.of();
            } else {
                return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
            }
        } else if (((EvalValue) items).value() instanceof DesignValue.ListValue list) {
            itemList = list.items();
        } else {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
        }
        capacityFailure = capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE,
                itemList.size()));
        if (capacityFailure != null) {
            return capacityFailure;
        }
        if (!(node.members().get("itemLayout") instanceof ObjectNode itemLayout)
                || !(node.members().get("instanceLayout") instanceof ObjectNode instanceLayout)) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        if (itemList.isEmpty()) {
            return null;
        }
        var itemNodes = new ArrayList<MaterializedNode>(itemList.size());
        var index = 0;
        for (var item : itemList) {
            capacityFailure = reserveLogicalOperation();
            if (capacityFailure != null) {
                return capacityFailure;
            }
            capacityFailure = reserveLoopFrame();
            if (capacityFailure != null) {
                return capacityFailure;
            }
            var itemScope = scope.withLoopFrame(
                    loopId, new DefinitionEngine.LoopFrame(toTypedValue(item), index));
            var itemPath = path + "/repeat(" + loopId + ")[" + index + "]";
            var expandedChildren = new ArrayList<MaterializedNode>();
            var failure = expandChildList(
                    node, snapshot, itemScope, itemPath, expandedChildren);
            if (failure != null) {
                return failure;
            }
            if (expandedChildren.isEmpty()) {
                index++;
                continue;
            }
            capacityFailure = reserveMaterializedNode();
            if (capacityFailure != null) {
                return capacityFailure;
            }
            var itemShape = packingShape(itemLayout, expandedChildren.size());
            capacityFailure = reserveGeneratedEntries(itemShape);
            if (capacityFailure != null) {
                return capacityFailure;
            }
            var packedChildren = lowerPackedChildren(
                    expandedChildren, itemShape);
            var itemMembers = packingNodeMembers(
                    itemLayout,
                    itemShape,
                    null,
                    null);
            itemNodes.add(new MaterializedNode(
                    itemShape.kind(), itemMembers, packedChildren, itemPath));
            index++;
        }
        if (itemNodes.isEmpty()) {
            return null;
        }
        capacityFailure = reserveMaterializedNode();
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var instanceShape = packingShape(instanceLayout, itemNodes.size());
        capacityFailure = reserveGeneratedEntries(instanceShape);
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var placedItems = placeGeneratedChildren(itemNodes, instanceShape);
        var instanceMembers = packingNodeMembers(
                instanceLayout,
                instanceShape,
                null,
                node);
        output.add(new MaterializedNode(
                instanceShape.kind(), instanceMembers, placedItems, path));
        return recordSidecar(path, node);
    }

    private static List<MaterializedNode> lowerPackedChildren(
            List<MaterializedNode> children,
            PackingShape shape) {
        var lowered = new ArrayList<MaterializedNode>(children.size());
        for (var index = 0; index < children.size(); index++) {
            var child = children.get(index);
            if (!(child.members().members().get("placement") instanceof ObjectNode placement)
                    || !"PACK".equals(textMember(placement, "type"))) {
                throw new IllegalStateException(
                        "Repeat expansion requires PACK on every surviving direct child");
            }
            var members = new LinkedHashMap<>(child.members().members());
            members.put("placement", convertedPackingPlacement(
                    placement, shape, index));
            lowered.add(new MaterializedNode(
                    child.kind(), new ObjectNode(members), child.children(), child.occurrencePath()));
        }
        return List.copyOf(lowered);
    }

    private static List<MaterializedNode> placeGeneratedChildren(
            List<MaterializedNode> children,
            PackingShape shape) {
        var placed = new ArrayList<MaterializedNode>(children.size());
        for (var index = 0; index < children.size(); index++) {
            var child = children.get(index);
            var members = new LinkedHashMap<>(child.members().members());
            members.put("placement", generatedPackingPlacement(shape, index));
            placed.add(new MaterializedNode(
                    child.kind(), new ObjectNode(members), child.children(), child.occurrencePath()));
        }
        return List.copyOf(placed);
    }

    private static ObjectNode convertedPackingPlacement(
            ObjectNode authored,
            PackingShape shape,
            int index) {
        var members = new LinkedHashMap<String, DesignNodeValue>();
        members.put("type", new Text(shape.kind().toUpperCase(java.util.Locale.ROOT)));
        for (var entry : authored.members().entrySet()) {
            if (!"type".equals(entry.getKey())) {
                members.put(entry.getKey(), entry.getValue());
            }
        }
        addGridCell(members, shape, index);
        return new ObjectNode(members);
    }

    private static ObjectNode generatedPackingPlacement(PackingShape shape, int index) {
        var members = new LinkedHashMap<String, DesignNodeValue>();
        members.put("type", new Text(shape.kind().toUpperCase(java.util.Locale.ROOT)));
        members.put("widthMode", new Text("HUG_CONTENT"));
        members.put("heightMode", new Text("HUG_CONTENT"));
        addGridCell(members, shape, index);
        return new ObjectNode(members);
    }

    private static void addGridCell(
            LinkedHashMap<String, DesignNodeValue> members,
            PackingShape shape,
            int index) {
        if (!"grid".equals(shape.kind())) {
            return;
        }
        members.put("row", new NumberToken(Integer.toString(index / shape.columns())));
        members.put("column", new NumberToken(Integer.toString(index % shape.columns())));
    }

    private static ObjectNode packingNodeMembers(
            ObjectNode packing,
            PackingShape shape,
            ObjectNode placement,
            ObjectNode host) {
        var members = host == null
                ? new LinkedHashMap<String, DesignNodeValue>()
                : new LinkedHashMap<>(loweredStructuralMembers(shape.kind(), host).members());
        members.put("kind", new Text(shape.kind()));
        if (placement != null) {
            members.put("placement", placement);
        }
        if ("stack".equals(shape.kind())) {
            members.put("direction", requiredMember(packing, "direction"));
            copyIfPresent(packing, members, "gapMm");
        } else {
            members.put("rows", autoTracks(shape.rows()));
            members.put("columns", autoTracks(shape.columns()));
            copyIfPresent(packing, members, "rowGapMm");
            copyIfPresent(packing, members, "columnGapMm");
        }
        return new ObjectNode(members);
    }

    private static ArrayNode autoTracks(int count) {
        var tracks = new ArrayList<DesignNodeValue>(count);
        for (var index = 0; index < count; index++) {
            tracks.add(new ObjectNode(Map.of("type", new Text("AUTO"))));
        }
        return new ArrayNode(tracks);
    }

    private static String renderPackingKind(ObjectNode packing) {
        var kind = textMember(packing, "kind");
        return switch (kind) {
            case "STACK" -> "stack";
            case "GRID" -> "grid";
            default -> throw new IllegalStateException("unknown Repeat packing kind");
        };
    }

    private static PackingShape packingShape(ObjectNode packing, int childCount) {
        if (childCount <= 0) {
            throw new IllegalArgumentException("packing requires surviving children");
        }
        var kind = renderPackingKind(packing);
        if ("stack".equals(kind)) {
            return new PackingShape(kind, 0, 0, 0);
        }
        if (!(packing.members().get("columns") instanceof NumberToken number)) {
            throw new IllegalStateException("Repeat grid packing requires columns");
        }
        var authoredColumns = new java.math.BigDecimal(number.rawToken());
        if (authoredColumns.signum() <= 0) {
            throw new IllegalStateException("Repeat grid packing requires positive columns");
        }
        var columns = authoredColumns.compareTo(java.math.BigDecimal.valueOf(childCount)) >= 0
                ? childCount
                : authoredColumns.intValueExact();
        var rows = (childCount + columns - 1) / columns;
        return new PackingShape(
                kind,
                rows,
                columns,
                (long) rows + columns + childCount);
    }

    private static DesignNodeValue requiredMember(ObjectNode object, String member) {
        var value = object.members().get(member);
        if (value == null) {
            throw new IllegalStateException("Repeat packing member is absent");
        }
        return value;
    }

    private static void copyIfPresent(
            ObjectNode source,
            LinkedHashMap<String, DesignNodeValue> destination,
            String member) {
        var value = source.members().get(member);
        if (value != null) {
            destination.put(member, value);
        }
    }

    /** conditional/repeat 生成的普通 frame 容器：展开 children，无 authored 外观成员。 */
    private MaterializationOutcome expandFrame(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        var capacityFailure = reserveMaterializedNode();
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var children = new ArrayList<MaterializedNode>();
        var failure = expandChildList(node, snapshot, scope, path, children);
        if (failure != null) {
            return failure;
        }
        output.add(new MaterializedNode(
                "frame", loweredStructuralMembers("frame", node), children, path));
        return recordSidecar(path, node);
    }

    private static ObjectNode loweredStructuralMembers(String kind, ObjectNode source) {
        var members = new LinkedHashMap<String, DesignNodeValue>();
        members.put("kind", new Text(kind));
        for (var memberName : List.of("placement", "visible", "opacity", "transform")) {
            var member = source.members().get(memberName);
            if (member != null) {
                members.put(memberName, member);
            }
        }
        return new ObjectNode(members);
    }

    private MaterializationOutcome expandChildList(
            ObjectNode node,
            TemplateSnapshot snapshot,
            InvocationScope scope,
            String path,
            List<MaterializedNode> children
    ) {
        if (node.members().get("children") instanceof ArrayNode childArray) {
            for (var child : childArray.items()) {
                if (!(child instanceof ObjectNode childObject)) {
                    return failed(EvaluationStage.MATERIALIZATION,
                            ProblemCode.RENDER_INTERNAL_ERROR, null);
                }
                var failure = expandNode(childObject, snapshot, scope, path, children);
                if (failure != null) {
                    return failure;
                }
            }
        }
        return null;
    }

    private MaterializationOutcome expandTemplateUse(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        var templateRef = node.members().get("templateRef");
        if (!(templateRef instanceof ObjectNode refObject)
                || !(refObject.members().get("templateId") instanceof Text targetId)
                || !(node.members().get("useId") instanceof Text useId)) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var childSnapshot = closure.snapshots().stream()
                .filter(candidate -> candidate.templateId().value().equals(targetId.value()))
                .findFirst()
                .orElse(null);
        if (childSnapshot == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
        }
        var childDocument = documentOf(childSnapshot);
        if (childDocument == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var selector = node.members().get("contextSelector");
        TypedObject childContext;
        if (selector instanceof ObjectNode selectorObject
                && selectorObject.members().get("kind") instanceof Text selectorKind) {
            if ("empty".equals(selectorKind.value())) {
                childContext = new TypedObject(childSnapshot.staticSchema(), Map.of());
            } else if ("context".equals(selectorKind.value())) {
                var pointer = selectorObject.members().get("pointer") instanceof Text pointerText
                        ? pointerText.value() : "";
                TypedValue root = scope.context();
                var domain = selectorObject.members().get("domain");
                if (domain instanceof ObjectNode domainObject
                        && domainObject.members().get("kind") instanceof Text domainKind
                        && "loop".equals(domainKind.value())
                        && domainObject.members().get("loopId") instanceof Text loopId) {
                    var frame = scope.loopFrames().frames().get(loopId.value());
                    if (frame == null) {
                        return contextFailure(node, path);
                    }
                    root = frame.item();
                }
                var selected = pointer.isEmpty()
                        ? DefinitionEngine.selectSubview(root, "")
                        : DefinitionEngine.selectReferenceSubview(root, pointer);
                if (selected == null) {
                    var failure = contextFailure(node, path);
                    if (failure != null) {
                        return failure;
                    }
                    return null;
                }
                childContext = selected;
            } else {
                return failed(EvaluationStage.MATERIALIZATION,
                        ProblemCode.RENDER_INTERNAL_ERROR, null);
            }
        } else {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }

        var childEngine = engineOf(childSnapshot);
        var childCustoms = new HashMap<>(childEngine.customDefaults());
        if (node.members().get("fills") instanceof ArrayNode fills) {
            for (var fill : fills.items()) {
                if (!(fill instanceof ObjectNode fillObject)
                        || !(fillObject.members().get("targetDefinitionId") instanceof Text target)
                        || fillObject.members().get("source") == null) {
                    return failed(EvaluationStage.MATERIALIZATION,
                            ProblemCode.RENDER_INTERNAL_ERROR, null);
                }
                var value = scope.definitions().resolveSource(
                        fillObject.members().get("source"), scope);
                if (value instanceof EvalError error) {
                    return valueFailure(error);
                }
                if (value instanceof EvalAbsent) {
                    continue;
                }
                childCustoms.put(target.value(), ((EvalValue) value).value());
            }
        }
        var childInvocationDepth = scope.invocationDepth() + 1;
        var invocationFailure = reserveTemplateInvocation(childInvocationDepth);
        if (invocationFailure != null) {
            return invocationFailure;
        }
        var childScope = new InvocationScope(
                childContext, Map.copyOf(childCustoms), childEngine,
                DefinitionEngine.LoopFrames.EMPTY, capabilities,
                scope.capabilityPath().enterTemplateUse(
                        useId.value(), childSnapshot.templateId().value(), childSnapshot.revision()),
                childInvocationDepth,
                scope.repeatNestingDepth());
        var childRoot = childObject(childDocument, "designRoot");
        if (childRoot == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var usePath = path + "/templateUse(" + targetId.value() + ")";
        var capacityFailure = reserveCompositionViewport();
        if (capacityFailure != null) {
            return capacityFailure;
        }
        capacityFailure = reserveMaterializedNode();
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var viewportChildren = new ArrayList<MaterializedNode>();
        var failure = expandNode(childRoot, childSnapshot, childScope, usePath, viewportChildren);
        if (failure != null) {
            return failure;
        }
        var viewportMembers = new LinkedHashMap<String, DesignNodeValue>();
        viewportMembers.put("kind", new Text("compositionViewport"));
        for (var memberName : List.of("placement", "visible", "opacity", "transform")) {
            var member = node.members().get(memberName);
            if (member != null) {
                viewportMembers.put(memberName, member);
            }
        }
        output.add(new MaterializedNode(
                "compositionViewport", new ObjectNode(viewportMembers), viewportChildren, usePath));
        return recordSidecar(usePath, node);
    }

    private MaterializationOutcome contextFailure(ObjectNode node, String path) {
        var selector = node.members().get("contextSelector");
        if (selector instanceof ObjectNode selectorObject
                && "SKIP".equals(textMember(selectorObject, "contextAbsentPolicy"))) {
            return null;
        }
        return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
    }

    private MaterializationOutcome materializeNode(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output, String kind) {
        var capacityFailure = reserveMaterializedNode();
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var resolvedMembers = resolveAssetsIn(node, path);
        if (resolvedMembers instanceof MaterializationFailed resolveFailure) {
            return resolveFailure;
        }
        var finalMembers = ((ResolvedMembers) resolvedMembers).members();
        var children = new ArrayList<MaterializedNode>();
        var childFailure = expandChildList(node, snapshot, scope, path, children);
        if (childFailure != null) {
            return childFailure;
        }
        output.add(new MaterializedNode(kind, finalMembers, children, path));
        return recordSidecar(path, node);
    }

    private MaterializationOutcome reserveMaterializedNode() {
        nodes++;
        var capacityFailure = capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.MATERIALIZED_STATIC_NODES,
                nodes));
        if (capacityFailure != null) {
            return capacityFailure;
        }
        occurrences++;
        return capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.RENDER_OCCURRENCES,
                occurrences));
    }

    private MaterializationOutcome reserveTemplateInvocation(int invocationDepth) {
        var capacityFailure = capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.INVOCATION_DEPTH,
                invocationDepth));
        if (capacityFailure != null) {
            return capacityFailure;
        }
        invocations++;
        return capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.ACTUAL_TEMPLATE_INVOCATIONS,
                invocations));
    }

    private MaterializationOutcome reserveCompositionViewport() {
        compositionViewports++;
        return capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.COMPOSITION_VIEWPORTS,
                compositionViewports));
    }

    private MaterializationOutcome reserveLoopFrame() {
        loopFrames++;
        return capacityFailure(CAPACITY_GUARD.admit(
                RenderingPipelineCapacityGuard.Limit.LOOP_FRAMES_TOTAL,
                loopFrames));
    }

    private MaterializationOutcome reserveGeneratedEntries(PackingShape shape) {
        return capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.GENERATED_TRACK_AND_CELL_ENTRIES,
                shape.generatedEntries()));
    }

    private MaterializationOutcome reserveLogicalOperation() {
        return capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.LOGICAL_OPERATIONS,
                1));
    }

    private static MaterializationFailed capacityFailure(
            Optional<RenderingProblem> problem) {
        return problem.map(value -> new MaterializationFailed(value.stage(), value)).orElse(null);
    }

    // ------------------------------------------------------------------
    // Binding overlay + re-admission
    // ------------------------------------------------------------------

    private sealed interface OverlayOutcome permits Overlaid, OverlayFailed {
    }

    private record Overlaid(ObjectNode node) implements OverlayOutcome {
    }

    private record OverlayFailed(MaterializationFailed failure) implements OverlayOutcome {
    }

    private OverlayOutcome overlayBindings(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope) {
        var bindings = node.members().get("bindings");
        if (!(bindings instanceof ArrayNode bindingList) || bindingList.items().isEmpty()) {
            return new Overlaid(node);
        }
        var members = new LinkedHashMap<>(node.members());
        for (var binding : bindingList.items()) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                return new OverlayFailed(failed(
                        EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null));
            }
            var targetRef = bindingObject.members().get("targetPropertyRef");
            var sourceWire = bindingObject.members().get("source");
            if (!(targetRef instanceof ObjectNode ref) || sourceWire == null) {
                return new OverlayFailed(failed(
                        EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null));
            }
            var value = scope.definitions().resolveSource(sourceWire, scope);
            if (value instanceof EvalError error) {
                return new OverlayFailed(valueFailure(error));
            }
            if (!(value instanceof EvalValue evalValue)) {
                return new OverlayFailed(failed(
                        EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null));
            }
            if (!(ref.members().get("rootPropertyId") instanceof Text rootProperty)) {
                return new OverlayFailed(failed(
                        EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null));
            }
            var applied = applyOverlay(
                    members, rootProperty.value(), ref, toWireValue(evalValue.value()));
            if (applied == null) {
                return new OverlayFailed(failed(
                        EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null));
            }
            members = applied;
        }
        var overlaid = new ObjectNode(members);
        if (!readmitWith(snapshot, node, overlaid)) {
            return new OverlayFailed(failed(
                    EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null));
        }
        return new Overlaid(overlaid);
    }

    private LinkedHashMap<String, DesignNodeValue> applyOverlay(
            LinkedHashMap<String, DesignNodeValue> members,
            String rootPropertyId,
            ObjectNode targetRef,
            DesignNodeValue value
    ) {
        var selectors = targetRef.members().get("selectors");
        var next = new LinkedHashMap<>(members);
        if (!(selectors instanceof ArrayNode selectorList) || selectorList.items().isEmpty()) {
            next.put(rootPropertyId, value);
            return next;
        }
        var updated = applySelectors(next.get(rootPropertyId), selectorList.items(), 0, value);
        if (updated == null) {
            return null;
        }
        next.put(rootPropertyId, updated);
        return next;
    }

    private DesignNodeValue applySelectors(
            DesignNodeValue current, List<DesignNodeValue> selectors, int index,
            DesignNodeValue leafValue) {
        if (index == selectors.size()) {
            return leafValue;
        }
        if (!(selectors.get(index) instanceof ObjectNode selector)
                || !(selector.members().get("kind") instanceof Text selectorKind)) {
            return null;
        }
        if ("member".equals(selectorKind.value())) {
            if (!(current instanceof ObjectNode object)
                    || !(selector.members().get("name") instanceof Text name)) {
                return null;
            }
            var updatedMembers = new LinkedHashMap<>(object.members());
            var updated = applySelectors(
                    updatedMembers.get(name.value()), selectors, index + 1, leafValue);
            if (updated == null) {
                return null;
            }
            updatedMembers.put(name.value(), updated);
            return new ObjectNode(updatedMembers);
        }
        if ("index".equals(selectorKind.value())) {
            if (!(current instanceof ArrayNode array)
                    || !(selector.members().get("value") instanceof NumberToken indexToken)) {
                return null;
            }
            var position = new java.math.BigDecimal(indexToken.rawToken()).intValueExact();
            if (position < 0 || position >= array.items().size()) {
                return null;
            }
            var updatedItems = new ArrayList<>(array.items());
            var updated = applySelectors(
                    updatedItems.get(position), selectors, index + 1, leafValue);
            if (updated == null) {
                return null;
            }
            updatedItems.set(position, updated);
            return new ArrayNode(updatedItems);
        }
        return null;
    }

    /** overlay 后按 nodeId 换入重构文档重新 admission：同一 Catalog 的 exact 重验。 */
    private boolean readmitWith(
            TemplateSnapshot snapshot, ObjectNode originalNode, ObjectNode overlaidNode) {
        var document = documentOf(snapshot);
        if (document == null) {
            return false;
        }
        var nodeId = originalNode.members().get("nodeId") instanceof Text id ? id.value() : null;
        if (nodeId == null) {
            return false;
        }
        var rebuilt = replaceByIdentity(document, nodeId, overlaidNode);
        if (rebuilt == null) {
            return false;
        }
        var bytes = DesignJsonWriter.write(rebuilt);
        return dslAuthority.admit(bytes) instanceof DesignDslAuthority.Admitted;
    }

    private DesignNodeValue replaceByIdentity(
            DesignNodeValue value, String nodeId, ObjectNode replacement) {
        if (value instanceof ObjectNode object) {
            if (object.members().get("nodeId") instanceof Text id
                    && id.value().equals(nodeId)) {
                return replacement;
            }
            var changed = false;
            var members = new LinkedHashMap<String, DesignNodeValue>();
            for (var entry : object.members().entrySet()) {
                var updated = replaceByIdentity(entry.getValue(), nodeId, replacement);
                if (updated != entry.getValue()) {
                    changed = true;
                }
                members.put(entry.getKey(), updated);
            }
            return changed ? new ObjectNode(members) : object;
        }
        if (value instanceof ArrayNode array) {
            var changed = false;
            var items = new ArrayList<DesignNodeValue>(array.items().size());
            for (var item : array.items()) {
                var updated = replaceByIdentity(item, nodeId, replacement);
                if (updated != item) {
                    changed = true;
                }
                items.add(updated);
            }
            return changed ? new ArrayNode(items) : array;
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Asset resolution
    // ------------------------------------------------------------------

    private sealed interface AssetResolutionStep {
    }

    private record ResolvedMembers(ObjectNode members) implements AssetResolutionStep {
    }

    private AssetResolutionStep resolveAssetsIn(ObjectNode members, String path) {
        var updated = new LinkedHashMap<String, DesignNodeValue>();
        for (var entry : members.members().entrySet()) {
            if ("children".equals(entry.getKey())) {
                // children 是结构成员：由展开单独处理，避免 occurrence 双重 resolve。
                updated.put(entry.getKey(), entry.getValue());
                continue;
            }
            var step = resolveValueAssets(entry.getKey(), entry.getValue(), path);
            if (step instanceof MaterializationFailed failure) {
                return failure;
            }
            updated.put(entry.getKey(), ((ResolvedValue) step).value());
        }
        return new ResolvedMembers(new ObjectNode(updated));
    }

    private sealed interface ValueStep {
    }

    private record ResolvedValue(DesignNodeValue value) implements ValueStep {
    }

    private ValueStep resolveValueAssets(String memberName, DesignNodeValue value, String path) {
        if (value instanceof ObjectNode object) {
            if (isAssetRefShape(object)
                    && ("imageRef".equals(memberName) || "fontRef".equals(memberName))) {
                return resolveAtom(memberName, object, path);
            }
            var updated = new LinkedHashMap<String, DesignNodeValue>();
            for (var entry : object.members().entrySet()) {
                var step = resolveValueAssets(entry.getKey(), entry.getValue(), path);
                if (step instanceof MaterializationFailed failure) {
                    return failure;
                }
                updated.put(entry.getKey(), ((ResolvedValue) step).value());
            }
            return new ResolvedValue(new ObjectNode(updated));
        }
        if (value instanceof ArrayNode array) {
            var updatedItems = new ArrayList<DesignNodeValue>(array.items().size());
            for (var item : array.items()) {
                var step = resolveValueAssets(memberName, item, path);
                if (step instanceof MaterializationFailed failure) {
                    return failure;
                }
                updatedItems.add(((ResolvedValue) step).value());
            }
            return new ResolvedValue(new ArrayNode(updatedItems));
        }
        return new ResolvedValue(value);
    }

    private ValueStep resolveAtom(String memberName, ObjectNode atom, String path) {
        var capacityFailure = capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES,
                1));
        if (capacityFailure != null) {
            return capacityFailure;
        }
        if (assets == null) {
            // 依赖不可用（T13 前无生产 bridge）：冻结码集无 asset-unavailable 专用码。
            return failed(EvaluationStage.ASSET_RESOLUTION, ProblemCode.EVALUATION_FAILED, null);
        }
        var assetId = ((Text) atom.members().get("assetId")).value();
        var kind = "imageRef".equals(memberName) ? AssetKind.IMAGE : AssetKind.FONT;
        // 冻结公式：SHA-256(canonical OccurrencePath + ConsumerPropertyRef + expectedKind)。
        // T21 边界：OccurrencePath 以物化路径字符串近似，完整语义随求值硬化票。
        var canonicalIdentity = (path + "\0" + memberName + "\0" + kind.name())
                .getBytes(StandardCharsets.UTF_8);
        var resourceId = new AssetResolutionPort.ResourceId(
                "rwres_" + RenderingDigests.sha256Hex(canonicalIdentity));
        var outcome = assets.resolve(new AssetResolutionPort.ResolveRequest(
                renderRequestId,
                new cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope(ownerScope),
                resourceId,
                new cn.hbads.renderweave.asset.api.AssetApplication.AssetId(assetId),
                kind,
                audience,
                deadlineEpochMilli));
        if (outcome instanceof AssetResolutionPort.ResolveOutcome.ResolveRejected rejected) {
            var problem = switch (rejected.reason()) {
                case SCOPE_MISMATCH, NOT_FOUND -> ProblemCode.ASSET_RESOLVE_NOT_FOUND;
                case NOT_ACTIVE -> ProblemCode.ASSET_RESOLVE_DELETED;
                case KIND_MISMATCH -> ProblemCode.ASSET_RESOLVE_KIND_MISMATCH;
            };
            return failed(EvaluationStage.ASSET_RESOLUTION, problem, null);
        }
        if (outcome instanceof AssetResolutionPort.ResolveOutcome.ResolveConflict) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.RENDER_REQUEST_CONFLICT, null);
        }
        if (outcome instanceof AssetResolutionPort.ResolveOutcome.ResolveTimedOut) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.ASSET_RESOLVE_TIMEOUT, null);
        }
        if (outcome instanceof AssetResolutionPort.ResolveOutcome.ResolveUnavailable) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.ASSET_RESOLVE_UNAVAILABLE, null);
        }
        if (!(outcome instanceof AssetResolutionPort.ResolveOutcome.Resolved resolved)) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var fact = resolved.fact();
        var occurrenceRawByteFailure = capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES,
                fact.byteLength()));
        if (occurrenceRawByteFailure != null) {
            return occurrenceRawByteFailure;
        }
        var exactContentFailure = reserveExactContent(kind, fact);
        if (exactContentFailure != null) {
            return exactContentFailure;
        }
        var resourceCapacityFailure = capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES,
                1));
        if (resourceCapacityFailure != null) {
            return resourceCapacityFailure;
        }
        resources.add(new ResourceEntry(
                resourceId.value(),
                kind.name(),
                fact.fetchUrl(),
                fact.leaseExpiresAtEpochSecond(),
                fact.sha256(),
                fact.mediaType(),
                fact.byteLength(),
                fact.acceptanceProfileId(),
                assetId,
                fact.contentVersion(),
                path,
                memberName,
                fact.technicalDescriptor()));
        return new ResolvedValue(new ObjectNode(Map.of("resourceId", new Text(resourceId.value()))));
    }

    private MaterializationFailed reserveExactContent(
            AssetKind kind,
            AssetResolutionPort.ResolvedAssetFact fact
    ) {
        var identity = new ExactContentIdentity(
                kind, fact.sha256(), fact.byteLength(), fact.mediaType());
        if (exactContents.contains(identity)) {
            return null;
        }
        var capacityFailure = capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit
                        .ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS,
                1));
        if (capacityFailure == null) {
            exactContents.add(identity);
        }
        return capacityFailure;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ObjectNode documentOf(TemplateSnapshot snapshot) {
        return documentsByTemplate.computeIfAbsent(
                snapshot.templateId().value(),
                key -> {
                    var outcome = semantics.interpret(snapshot.canonicalDesignDslUtf8());
                    return outcome instanceof DesignSemanticAuthority.Interpreted interpreted
                            ? interpreted.document() : null;
                });
    }

    private static boolean isAssetRefShape(ObjectNode object) {
        return object.members().size() == 1
                && object.members().get("assetId") instanceof Text;
    }

    private DefinitionEngine engineOf(TemplateSnapshot snapshot) {
        return enginesByTemplate.computeIfAbsent(snapshot.templateId().value(), key -> {
            var document = documentOf(snapshot);
            if (document == null
                    || !(document.members().get("definitions") instanceof ArrayNode definitions)) {
                return new DefinitionEngine(List.of());
            }
            return new DefinitionEngine(definitions.items());
        });
    }

    private static ObjectNode childObject(ObjectNode document, String member) {
        return document.members().get(member) instanceof ObjectNode object ? object : null;
    }

    private static String textMember(ObjectNode node, String member) {
        return node.members().get(member) instanceof Text text ? text.value() : null;
    }

    private MaterializationFailed recordSidecar(String path, ObjectNode node) {
        var capacityFailure = capacityFailure(requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_ITEMS, 1));
        if (capacityFailure != null) {
            return capacityFailure;
        }
        var sourceNodeId = node.members().get("nodeId") instanceof Text nodeId
                ? nodeId.value() : null;
        sidecar.add(new SidecarEntry(path, sourceNodeId));
        return null;
    }

    private static DesignNodeValue toWireValue(DesignValue value) {
        if (value instanceof DesignValue.Text text) {
            return new Text(text.value());
        }
        if (value instanceof DesignValue.Decimal decimal) {
            return new NumberToken(decimal.value().toString());
        }
        if (value instanceof DesignValue.Bool bool) {
            return new Bool(bool.value());
        }
        if (value instanceof DesignValue.Date date) {
            return new Text(date.value());
        }
        if (value instanceof DesignValue.Time time) {
            return new Text(time.value());
        }
        if (value instanceof DesignValue.Color color) {
            return new Text(color.value());
        }
        if (value instanceof DesignValue.ImageRef ref) {
            return new ObjectNode(Map.of("assetId", new Text(ref.assetId())));
        }
        if (value instanceof DesignValue.FontRef ref) {
            return new ObjectNode(Map.of("assetId", new Text(ref.assetId())));
        }
        if (value instanceof DesignValue.ListValue list) {
            var items = new ArrayList<DesignNodeValue>(list.items().size());
            for (var item : list.items()) {
                items.add(toWireValue(item));
            }
            return new ArrayNode(items);
        }
        throw new IllegalStateException("unknown DesignValue variant");
    }

    private static TypedValue toTypedValue(DesignValue value) {
        if (value instanceof DesignValue.Text text) {
            return new TypedValue.Text(text.value());
        }
        if (value instanceof DesignValue.Decimal decimal) {
            return new TypedValue.Decimal(decimal.value());
        }
        if (value instanceof DesignValue.Bool bool) {
            return new TypedValue.Bool(bool.value());
        }
        if (value instanceof DesignValue.Date date) {
            return new TypedValue.Date(date.value());
        }
        if (value instanceof DesignValue.Time time) {
            return new TypedValue.Time(time.value());
        }
        if (value instanceof DesignValue.ListValue list) {
            var items = new ArrayList<TypedValue>(list.items().size());
            for (var item : list.items()) {
                items.add(toTypedValue(item));
            }
            return new TypedValue.Array(items);
        }
        throw new IllegalStateException("loop item must be a scalar or list");
    }

    private static MaterializationFailed failed(
            EvaluationStage stage, ProblemCode code, String limitId) {
        return new MaterializationFailed(stage, new RenderingProblem(
                code, stage, Optional.empty(),
                limitId == null ? Optional.empty() : Optional.of(new LimitId(limitId))));
    }

    private static MaterializationFailed valueFailure(EvalError error) {
        return switch (error.failure().kind()) {
            case CAPABILITY_BUDGET_EXCEEDED -> failed(
                    EvaluationStage.MATERIALIZATION,
                    ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                    error.failure().limitId());
            case CAPABILITY_RESULT_INVALID -> failed(
                    EvaluationStage.MATERIALIZATION,
                    ProblemCode.CAPABILITY_RESULT_INVALID,
                    error.failure().limitId());
            default -> failed(
                    EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
        };
    }

    /** invocation frame：typed context、有效 Custom map、definition 引擎与活跃 loop frames。 */
    static final class InvocationScope implements DefinitionEngine.ResolutionScope {
        private final TypedObject context;
        private final Map<String, DesignValue> customs;
        private final DefinitionEngine engine;
        private final DefinitionEngine.LoopFrames loopFrames;
        private final DefinitionEngine.CapabilityProvider capabilities;
        private final CapabilityCallPosition.RuntimePath capabilityPath;
        private final int invocationDepth;
        private final int repeatNestingDepth;

        InvocationScope(
                TypedObject context,
                Map<String, DesignValue> customs,
                DefinitionEngine engine,
                DefinitionEngine.LoopFrames loopFrames,
                DefinitionEngine.CapabilityProvider capabilities,
                CapabilityCallPosition.RuntimePath capabilityPath,
                int invocationDepth,
                int repeatNestingDepth
        ) {
            this.context = context;
            this.customs = customs;
            this.engine = engine;
            this.loopFrames = loopFrames;
            this.capabilities = capabilities;
            this.capabilityPath = capabilityPath;
            if (invocationDepth < 1) {
                throw new IllegalArgumentException("invocationDepth must be positive");
            }
            if (repeatNestingDepth < 0) {
                throw new IllegalArgumentException("repeatNestingDepth must be non-negative");
            }
            this.invocationDepth = invocationDepth;
            this.repeatNestingDepth = repeatNestingDepth;
        }

        @Override
        public TypedObject context() {
            return context;
        }

        @Override
        public Map<String, DesignValue> customs() {
            return customs;
        }

        @Override
        public DefinitionEngine.LoopFrames loopFrames() {
            return loopFrames;
        }

        @Override
        public DefinitionEngine definitions() {
            return engine;
        }

        @Override
        public DefinitionEngine.CapabilityProvider capabilities() {
            return capabilities;
        }

        @Override
        public CapabilityCallPosition.RuntimePath capabilityPath() {
            return capabilityPath;
        }

        int invocationDepth() {
            return invocationDepth;
        }

        int repeatNestingDepth() {
            return repeatNestingDepth;
        }

        InvocationScope withLoopFrame(String loopId, DefinitionEngine.LoopFrame frame) {
            var frames = new java.util.LinkedHashMap<>(loopFrames.frames());
            frames.put(loopId, frame);
            return new InvocationScope(
                    context,
                    customs,
                    engine,
                    new DefinitionEngine.LoopFrames(frames),
                    capabilities,
                    capabilityPath.enterRepeat(loopId, frame.index()),
                    invocationDepth,
                    repeatNestingDepth + 1);
        }
    }
}
