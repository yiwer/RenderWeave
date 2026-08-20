package cn.hbads.renderweave.rendering.internal;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MATERIALIZATION/ASSET_RESOLUTION（冻结规格 stage 5/7）：authored AssetRef 预准入、
 * 结构展开（render:false 剪枝、Conditional absentPolicy、Repeat loop frame、TemplateUse
 * 隔离 child invocation + fills）、Binding overlay 后以重构文档按 nodeId 换入重新 admission
 * 完成 exact 重验、消费点 Asset 串行 resolve（首个 demand 失败即停，逻辑 AssetRef 替换为
 * 请求级 resourceId）。侧 sidecar 容量受限、请求级。
 */
final class Materializer {

    static final int MAX_RENDER_OCCURRENCES = 25_000;
    static final int MAX_MATERIALIZED_NODES = 20_000;
    static final int MAX_SIDECAR_ITEMS = 25_000;
    static final int MAX_AUTHORED_ASSET_OCCURRENCES = 4_096;
    static final int MAX_ACTUAL_RESOLVE_OCCURRENCES = 2_048;
    static final int MAX_RESOURCE_ENTRIES = 2_048;
    static final int MAX_TEMPLATE_INVOCATIONS = 256;

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

    record ResourceEntry(
            String resourceId,
            String kind,
            String fetchUrl,
            long leaseExpiresAtEpochSecond,
            String sha256,
            String mediaType,
            long byteLength,
            String acceptanceProfileId
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
    private int occurrences;
    private int nodes;
    private int invocations;
    private int authoredAtoms;
    private int resolves;

    private Materializer(
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            DefinitionEngine.CapabilityProvider capabilities,
            RenderRequestId renderRequestId,
            AssetResolutionPort.RendererAudience audience,
            long deadlineEpochMilli,
            String ownerScope
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
    }

    static MaterializationOutcome materialize(
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
        Objects.requireNonNull(closure, "closure");
        var materializer = new Materializer(
                closure, semantics, dslAuthority, assets, capabilities,
                renderRequestId, audience, deadlineEpochMilli,
                closure.ownerScope().value());

        var precheckFailure = materializer.preadmitAssetAtoms();
        if (precheckFailure != null) {
            return precheckFailure;
        }

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

        var rootScope = new InvocationScope(
                admittedInput.rootDocument(),
                admittedInput.customs(),
                materializer.engineOf(rootSnapshot),
                DefinitionEngine.LoopFrames.EMPTY,
                capabilities);
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
    // stage 5: authored AssetRef pre-admission
    // ------------------------------------------------------------------

    private MaterializationOutcome preadmitAssetAtoms() {
        for (var snapshot : closure.snapshots()) {
            var document = documentOf(snapshot);
            if (document == null) {
                return failed(EvaluationStage.TEMPLATE_CLOSURE,
                        ProblemCode.RENDER_INTERNAL_ERROR, null);
            }
            if (!containsAssetAtom(document)) {
                continue;
            }
            if (assets == null) {
                return failed(EvaluationStage.ASSET_ADMISSION, ProblemCode.ASSET_NOT_FOUND, null);
            }
            var failure = preadmitAtomsIn(document);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private boolean containsAssetAtom(DesignNodeValue value) {
        if (value instanceof ObjectNode object) {
            if (isAssetRefShape(object)) {
                return true;
            }
            for (var member : object.members().values()) {
                if (containsAssetAtom(member)) {
                    return true;
                }
            }
        } else if (value instanceof ArrayNode array) {
            for (var item : array.items()) {
                if (containsAssetAtom(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private MaterializationOutcome preadmitAtomsIn(ObjectNode node) {
        if (isAssetRefShape(node)) {
            authoredAtoms++;
            if (authoredAtoms > MAX_AUTHORED_ASSET_OCCURRENCES) {
                return failed(EvaluationStage.ASSET_ADMISSION,
                        ProblemCode.ASSET_BUDGET_EXCEEDED,
                        "assetsAndFetch.authoredAssetOccurrences");
            }
            var assetId = ((Text) node.members().get("assetId")).value();
            var outcome = assets.precheckAdmission(
                    new cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope(ownerScope),
                    new cn.hbads.renderweave.asset.api.AssetApplication.AssetId(assetId),
                    cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind.IMAGE);
            return switch (outcome) {
                case AssetResolutionPort.PrecheckOutcome.PrecheckPassed ignored -> null;
                case AssetResolutionPort.PrecheckOutcome.PrecheckRejected ignored ->
                        failed(EvaluationStage.ASSET_ADMISSION, ProblemCode.ASSET_NOT_FOUND, null);
                case AssetResolutionPort.PrecheckOutcome.PrecheckUnavailable ignored ->
                        failed(EvaluationStage.ASSET_ADMISSION, ProblemCode.ASSET_NOT_FOUND, null);
            };
        }
        for (var member : node.members().values()) {
            var failure = member instanceof ObjectNode object
                    ? preadmitAtomsIn(object)
                    : (member instanceof ArrayNode array ? preadmitArray(array) : null);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private MaterializationOutcome preadmitArray(ArrayNode array) {
        for (var item : array.items()) {
            var failure = item instanceof ObjectNode object
                    ? preadmitAtomsIn(object)
                    : (item instanceof ArrayNode nested ? preadmitArray(nested) : null);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static boolean isAssetRefShape(ObjectNode object) {
        return object.members().size() == 1
                && object.members().get("assetId") instanceof Text;
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
        return switch (kind) {
            case "conditional" -> expandConditional(node, snapshot, scope, path, output);
            case "repeat" -> expandRepeat(node, snapshot, scope, path, output);
            case "templateUse" -> expandTemplateUse(node, snapshot, scope, path, output);
            default -> materializeNode(node, snapshot, scope, path, output, kind);
        };
    }

    private MaterializationOutcome expandConditional(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        var conditionWire = node.members().get("condition");
        if (conditionWire == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var condition = scope.definitions().resolveSource(conditionWire, scope, frameKey(scope));
        if (condition instanceof EvalError) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
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
        var items = scope.definitions().resolveSource(itemsWire, scope, frameKey(scope));
        if (items instanceof EvalError) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
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
        var index = 0;
        for (var item : itemList) {
            var frames = new HashMap<>(scope.loopFrames().frames());
            frames.put(loopId, new DefinitionEngine.LoopFrame(toTypedValue(item), index));
            var itemScope = scope.withLoopFrames(new DefinitionEngine.LoopFrames(frames));
            var itemPath = path + "/repeat(" + loopId + ")[" + index + "]";
            var failure = expandFrame(node, snapshot, itemScope, itemPath, output);
            if (failure != null) {
                return failure;
            }
            index++;
        }
        return null;
    }

    /** conditional/repeat 生成的普通 frame 容器：展开 children，无 authored 外观成员。 */
    private MaterializationOutcome expandFrame(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        var children = expandChildList(node, snapshot, scope, path);
        if (children == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        output.add(new MaterializedNode(
                "frame", new ObjectNode(Map.of("kind", new Text("frame"))), children, path));
        recordSidecar(path, node);
        return null;
    }

    private List<MaterializedNode> expandChildList(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope, String path) {
        var children = new ArrayList<MaterializedNode>();
        if (node.members().get("children") instanceof ArrayNode childArray) {
            for (var child : childArray.items()) {
                if (!(child instanceof ObjectNode childObject)) {
                    return null;
                }
                var failure = expandNode(childObject, snapshot, scope, path, children);
                if (failure != null) {
                    return null;
                }
            }
        }
        return children;
    }

    private MaterializationOutcome expandTemplateUse(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output) {
        invocations++;
        if (invocations > MAX_TEMPLATE_INVOCATIONS) {
            return failed(EvaluationStage.MATERIALIZATION,
                    ProblemCode.TEMPLATE_CLOSURE_LIMIT_EXCEEDED,
                    "closureAndExpansion.actualTemplateInvocations");
        }
        var templateRef = node.members().get("templateRef");
        if (!(templateRef instanceof ObjectNode refObject)
                || !(refObject.members().get("templateId") instanceof Text targetId)) {
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
                        fillObject.members().get("source"), scope, frameKey(scope));
                if (value instanceof EvalError) {
                    return failed(EvaluationStage.MATERIALIZATION,
                            ProblemCode.EVALUATION_FAILED, null);
                }
                if (value instanceof EvalAbsent) {
                    continue;
                }
                childCustoms.put(target.value(), ((EvalValue) value).value());
            }
        }
        var childScope = new InvocationScope(
                childContext, Map.copyOf(childCustoms), childEngine,
                DefinitionEngine.LoopFrames.EMPTY, capabilities);
        var childRoot = childObject(childDocument, "designRoot");
        if (childRoot == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        var usePath = path + "/templateUse(" + targetId.value() + ")";
        var viewportChildren = new ArrayList<MaterializedNode>();
        var failure = expandNode(childRoot, childSnapshot, childScope, usePath, viewportChildren);
        if (failure != null) {
            return failure;
        }
        var viewportMembers = new LinkedHashMap<String, DesignNodeValue>();
        viewportMembers.put("kind", new Text("compositionViewport"));
        var placement = node.members().get("placement");
        if (placement != null) {
            viewportMembers.put("placement", placement);
        }
        output.add(new MaterializedNode(
                "compositionViewport", new ObjectNode(viewportMembers), viewportChildren, usePath));
        recordSidecar(usePath, node);
        return null;
    }

    private MaterializationOutcome contextFailure(ObjectNode node, String path) {
        if ("SKIP".equals(textMember(node, "contextAbsentPolicy"))) {
            return null;
        }
        return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
    }

    private MaterializationOutcome materializeNode(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope,
            String path, List<MaterializedNode> output, String kind) {
        nodes++;
        if (nodes > MAX_MATERIALIZED_NODES) {
            return failed(EvaluationStage.MATERIALIZATION,
                    ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                    "closureAndExpansion.materializedStaticNodes");
        }
        occurrences++;
        if (occurrences > MAX_RENDER_OCCURRENCES) {
            return failed(EvaluationStage.MATERIALIZATION,
                    ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                    "closureAndExpansion.renderOccurrences");
        }
        var overlaid = overlayBindings(node, snapshot, scope);
        if (overlaid == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.EVALUATION_FAILED, null);
        }
        var resolvedMembers = resolveAssetsIn(overlaid, path);
        if (resolvedMembers instanceof MaterializationFailed resolveFailure) {
            return resolveFailure;
        }
        var finalMembers = ((ResolvedMembers) resolvedMembers).members();
        var children = expandChildList(node, snapshot, scope, path);
        if (children == null) {
            return failed(EvaluationStage.MATERIALIZATION, ProblemCode.RENDER_INTERNAL_ERROR, null);
        }
        output.add(new MaterializedNode(kind, finalMembers, children, path));
        recordSidecar(path, node);
        return null;
    }

    // ------------------------------------------------------------------
    // Binding overlay + re-admission
    // ------------------------------------------------------------------

    private ObjectNode overlayBindings(
            ObjectNode node, TemplateSnapshot snapshot, InvocationScope scope) {
        var bindings = node.members().get("bindings");
        if (!(bindings instanceof ArrayNode bindingList) || bindingList.items().isEmpty()) {
            return node;
        }
        var members = new LinkedHashMap<>(node.members());
        for (var binding : bindingList.items()) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                return null;
            }
            var targetRef = bindingObject.members().get("targetPropertyRef");
            var sourceWire = bindingObject.members().get("source");
            if (!(targetRef instanceof ObjectNode ref) || sourceWire == null) {
                return null;
            }
            var value = scope.definitions().resolveSource(sourceWire, scope, frameKey(scope));
            if (!(value instanceof EvalValue evalValue)) {
                return null;
            }
            if (!(ref.members().get("rootPropertyId") instanceof Text rootProperty)) {
                return null;
            }
            var applied = applyOverlay(
                    members, rootProperty.value(), ref, toWireValue(evalValue.value()));
            if (applied == null) {
                return null;
            }
            members = applied;
        }
        var overlaid = new ObjectNode(members);
        if (!readmitWith(snapshot, node, overlaid)) {
            return null;
        }
        return overlaid;
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
        resolves++;
        if (resolves > MAX_ACTUAL_RESOLVE_OCCURRENCES) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                    "assetsAndFetch.actualResolveOccurrences");
        }
        if (assets == null) {
            return failed(EvaluationStage.ASSET_RESOLUTION, ProblemCode.ASSET_NOT_FOUND, null);
        }
        var assetId = ((Text) atom.members().get("assetId")).value();
        var kind = "imageRef".equals(memberName)
                ? cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind.IMAGE
                : cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind.FONT;
        var canonicalIdentity = (path + "\0" + memberName + "\0" + kind.name())
                .getBytes(StandardCharsets.UTF_8);
        var resourceId = new AssetResolutionPort.ResourceId(
                "rwres_" + RenderingDigests.sha256Hex(canonicalIdentity,
                        assetId.getBytes(StandardCharsets.UTF_8)));
        var outcome = assets.resolve(new AssetResolutionPort.ResolveRequest(
                renderRequestId,
                new cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope(ownerScope),
                resourceId,
                new cn.hbads.renderweave.asset.api.AssetApplication.AssetId(assetId),
                kind,
                audience,
                deadlineEpochMilli));
        if (!(outcome instanceof AssetResolutionPort.ResolveOutcome.Resolved resolved)) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.ASSET_RESOLVE_NOT_FOUND, null);
        }
        if (resources.size() == MAX_RESOURCE_ENTRIES) {
            return failed(EvaluationStage.ASSET_RESOLUTION,
                    ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                    "assetsAndFetch.renderResourceEntries");
        }
        var fact = resolved.fact();
        resources.add(new ResourceEntry(
                resourceId.value(),
                kind.name(),
                fact.fetchUrl(),
                fact.leaseExpiresAtEpochSecond(),
                fact.sha256(),
                fact.mediaType(),
                fact.byteLength(),
                fact.acceptanceProfileId()));
        return new ResolvedValue(new ObjectNode(Map.of("resourceId", new Text(resourceId.value()))));
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

    private static String frameKey(InvocationScope scope) {
        if (scope.loopFrames().frames().isEmpty()) {
            return "invocation";
        }
        var builder = new StringBuilder();
        for (var frame : scope.loopFrames().frames().entrySet()) {
            builder.append(frame.getKey()).append(':').append(frame.getValue().index()).append(';');
        }
        return builder.toString();
    }

    private void recordSidecar(String path, ObjectNode node) {
        if (sidecar.size() < MAX_SIDECAR_ITEMS) {
            var sourceNodeId = node.members().get("nodeId") instanceof Text nodeId
                    ? nodeId.value() : null;
            sidecar.add(new SidecarEntry(path, sourceNodeId));
        }
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

    /** invocation frame：typed context、有效 Custom map、definition 引擎与活跃 loop frames。 */
    static final class InvocationScope implements DefinitionEngine.ResolutionScope {
        private final TypedObject context;
        private final Map<String, DesignValue> customs;
        private final DefinitionEngine engine;
        private final DefinitionEngine.LoopFrames loopFrames;
        private final DefinitionEngine.CapabilityProvider capabilities;

        InvocationScope(
                TypedObject context,
                Map<String, DesignValue> customs,
                DefinitionEngine engine,
                DefinitionEngine.LoopFrames loopFrames,
                DefinitionEngine.CapabilityProvider capabilities
        ) {
            this.context = context;
            this.customs = customs;
            this.engine = engine;
            this.loopFrames = loopFrames;
            this.capabilities = capabilities;
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

        InvocationScope withLoopFrames(DefinitionEngine.LoopFrames frames) {
            return new InvocationScope(context, customs, engine, frames, capabilities);
        }
    }
}
