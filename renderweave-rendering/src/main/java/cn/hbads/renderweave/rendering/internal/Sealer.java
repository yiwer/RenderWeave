package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * DOCUMENT_SEAL（冻结规格 stage 8）：静态树先序分配请求级 opaque occurrenceId
 * （{@code rwocc_} + 16 位零填充小写十六进制），移除 authored-only 成员，*Mm → *Pt 量化
 * （×360/127，HALF_EVEN ≤6 位小数），compositionViewport 携带 sourceCanvas，canonical
 * RenderDocument（{@code renderweave-render/1.0} + {@code renderweave-layout/1.0}）原子
 * commit bytes 与 digest。RenderDocument 不携带 nodeId/displayName/Binding/逻辑 AssetRef/
 * 动态结构判别；资源 manifest 与树 resourceId 引用双射。
 *
 * <p>T23 边界：每个 static node 在 seal 时按 RenderNodeContract catalog 逐 kind 展开冻结 default；
 * catalog 未声明的成员不虚构，最终 canonical bytes 只含 renderer 合同允许的成员。
 */
final class Sealer {

    private static final RenderingPipelineCapacityGuard CAPACITY_GUARD =
            new RenderingPipelineCapacityGuard();
    static final String RENDER_DSL_VERSION = "renderweave-render/1.0";
    static final String LAYOUT_PROFILE = "renderweave-layout/1.0";
    static final String ASSET_SELECTION_DOMAIN = "renderweave-asset-selection/1\0";
    static final String RESULT_DOMAIN = "renderweave-evaluation-result/1";

    private static final BigDecimal MM_TO_PT = BigDecimal.valueOf(360)
            .divide(BigDecimal.valueOf(127), 20, RoundingMode.HALF_EVEN);
    private static final Set<String> AUTHORING_ONLY_MEMBERS =
            Set.of("nodeId", "displayName", "bindings", "render", "children");

    record SealedEvaluation(
            byte[] renderDocumentCanonicalUtf8,
            String renderDocumentDigest,
            String evaluationResultDigest
    ) {
    }

    sealed interface SealOutcome permits Sealed, SealRejected, SealDeadlineExceeded {
    }

    record Sealed(SealedEvaluation evaluation) implements SealOutcome {
    }

    record SealRejected(RenderingProblem problem) implements SealOutcome {
        SealRejected {
            Objects.requireNonNull(problem, "problem");
        }
    }

    record SealDeadlineExceeded() implements SealOutcome {
    }

    private long occurrenceCounter;
    private final List<String> occurrenceIdsInPreorder = new ArrayList<>();
    private final RenderNodeContractCatalog nodeContracts = RenderNodeContractCatalog.instance();
    private final RenderingPipelineCapacityGuard.RequestTracker requestCapacity;
    private final EvaluationStageControl stageControl;

    private Sealer(
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity,
            EvaluationStageControl stageControl
    ) {
        this.requestCapacity = Objects.requireNonNull(requestCapacity, "requestCapacity");
        this.stageControl = Objects.requireNonNull(stageControl, "stageControl");
    }

    static SealOutcome seal(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest
    ) {
        return seal(
                closure,
                admittedInput,
                tree,
                capabilityResultDigest,
                CAPACITY_GUARD.newRequestTracker());
    }

    static SealOutcome seal(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest,
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity
    ) {
        return seal(
                closure,
                admittedInput,
                tree,
                capabilityResultDigest,
                requestCapacity,
                EvaluationStageControl.unbounded());
    }

    static SealOutcome seal(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest,
            EvaluationStageControl stageControl
    ) {
        return seal(
                closure,
                admittedInput,
                tree,
                capabilityResultDigest,
                CAPACITY_GUARD.newRequestTracker(),
                stageControl);
    }

    private static SealOutcome seal(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest,
            RenderingPipelineCapacityGuard.RequestTracker requestCapacity,
            EvaluationStageControl stageControl
    ) {
        var sealer = new Sealer(requestCapacity, stageControl);
        try {
            sealer.stageControl.checkpoint();
            var canvasJson = sealer.sealCanvas(tree.root());
            sealer.stageControl.checkpoint();
            var resourcesJson = sealer.sealResources(tree.resources());
            sealer.stageControl.checkpoint();
            var envelope = new TreeMap<String, CanonicalJson.CanonicalValue>();
            envelope.put("canvas", canvasJson);
            envelope.put("dslVersion", CanonicalJson.stringValue(RENDER_DSL_VERSION));
            envelope.put("layoutProfile", CanonicalJson.stringValue(LAYOUT_PROFILE));
            envelope.put("resources", CanonicalJson.arrayValue(resourcesJson));
            var canonical = RenderDocumentCanonicalWriter.write(
                    CanonicalJson.objectValue(envelope), sealer.requestCapacity);
            sealer.stageControl.checkpoint();
            var documentDigest = RenderingDigests.renderDocumentDigest(canonical);
            sealer.stageControl.checkpoint();
            var resultDigest = sealer.evaluationResultDigest(
                    closure, admittedInput, tree, capabilityResultDigest);
            sealer.stageControl.checkpoint();
            return new Sealed(new SealedEvaluation(canonical, documentDigest, resultDigest));
        } catch (RenderDocumentCanonicalWriter.CapacityExceeded capacity) {
            return new SealRejected(capacity.problem());
        } catch (SealCapacityExceeded capacity) {
            return new SealRejected(capacity.problem());
        } catch (EvaluationStageControl.DeadlineExceeded ignored) {
            return new SealDeadlineExceeded();
        }
    }

    private static final class SealCapacityExceeded extends RuntimeException {
        private final RenderingProblem problem;

        SealCapacityExceeded(RenderingProblem problem) {
            this.problem = Objects.requireNonNull(problem, "problem");
        }

        RenderingProblem problem() {
            return problem;
        }
    }

    private String evaluationResultDigest(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest
    ) {
        return evaluationResultDigest(
                closure.ownerScope().value(),
                ClosureManifests.digest(closure),
                AdmittedInputCanonicalizer.digest(admittedInput),
                assetSelectionDigest(tree.resources(), stageControl),
                capabilityResultDigest);
    }

    /** closed 输入组合 → evaluationResultDigest（向量重放使用同一组合路径）。 */
    static String evaluationResultDigest(
            String ownerScope,
            String closureDigest,
            String admittedInputDigest,
            String assetSelectionDigest,
            String capabilityResultDigest
    ) {
        var members = new TreeMap<String, String>();
        members.put("admittedInputDigest", CanonicalJson.string(admittedInputDigest));
        members.put("assetAcceptanceProfile",
                CanonicalJson.string("renderweave-asset-acceptance/1.0"));
        members.put("assetSelectionDigest", CanonicalJson.string(assetSelectionDigest));
        members.put("capabilityResultDigest", CanonicalJson.string(capabilityResultDigest));
        members.put("closureDigest", CanonicalJson.string(closureDigest));
        members.put("layoutProfile", CanonicalJson.string(LAYOUT_PROFILE));
        members.put("ownerScope", CanonicalJson.string(ownerScope));
        members.put("renderDslVersion", CanonicalJson.string(RENDER_DSL_VERSION));
        return RenderingDigests.digestWithDomain(
                RESULT_DOMAIN, CanonicalJson.object(members).getBytes(StandardCharsets.UTF_8));
    }

    /** 按 resource encounter order 的 domain-separated selection digest（票据 13 §117）。 */
    static String assetSelectionDigest(List<Materializer.ResourceEntry> resources) {
        return assetSelectionDigest(resources, EvaluationStageControl.unbounded());
    }

    private static String assetSelectionDigest(
            List<Materializer.ResourceEntry> resources,
            EvaluationStageControl stageControl
    ) {
        var framed = new java.io.ByteArrayOutputStream();
        for (var resource : resources) {
            stageControl.checkpoint();
            var entry = new TreeMap<String, String>();
            entry.put("acceptanceProfileId", CanonicalJson.string(resource.acceptanceProfileId()));
            entry.put("assetId", CanonicalJson.string(resource.assetId()));
            entry.put("byteLength", CanonicalJson.decimal(
                    BigDecimal.valueOf(resource.byteLength())));
            entry.put("consumerPropertyRef", CanonicalJson.string(resource.consumerPropertyRef()));
            entry.put("contentVersion", CanonicalJson.string(resource.contentVersion()));
            entry.put("kind", CanonicalJson.string(resource.kind().toLowerCase()));
            entry.put("mediaType", CanonicalJson.string(resource.mediaType()));
            entry.put("occurrencePath", CanonicalJson.string(resource.occurrencePath()));
            entry.put("resourceId", CanonicalJson.string(resource.resourceId()));
            entry.put("sha256", CanonicalJson.string(resource.sha256()));
            entry.put("technicalDescriptor",
                    RenderResourceCanonicalizer.technicalDescriptorWire(
                            resource.technicalDescriptor()));
            var bytes = CanonicalJson.object(entry).getBytes(StandardCharsets.UTF_8);
            framed.writeBytes(lengthFrame(bytes.length));
            framed.writeBytes(bytes);
            stageControl.checkpoint();
        }
        return RenderingDigests.digestWithDomain(ASSET_SELECTION_DOMAIN, framed.toByteArray());
    }

    // ------------------------------------------------------------------
    // RenderDocument lowering
    // ------------------------------------------------------------------

    private CanonicalJson.CanonicalValue sealCanvas(Materializer.MaterializedNode node) {
        stageControl.checkpoint();
        if (!"canvas".equals(node.kind())) {
            throw new IllegalStateException("render document root must be the canvas");
        }
        var expanded = nodeContracts.expandNodeDefaults("canvas", node.members());
        var members = new TreeMap<String, CanonicalJson.CanonicalValue>();
        members.put("occurrenceId", CanonicalJson.stringValue(nextOccurrenceId()));
        members.put("kind", CanonicalJson.stringValue("canvas"));
        for (var entry : expanded.members().entrySet()) {
            stageControl.checkpoint();
            if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            putLowered(members, entry.getKey(), entry.getValue());
        }
        members.put("children", CanonicalJson.arrayValue(
                sealChildren(node.children(), expanded)));
        return CanonicalJson.objectValue(members);
    }

    private List<CanonicalJson.CanonicalValue> sealChildren(
            List<Materializer.MaterializedNode> children,
            ObjectNode expandedParent) {
        var items = new ArrayList<CanonicalJson.CanonicalValue>();
        for (var child : children) {
            stageControl.checkpoint();
            reserveChildEdge();
            items.add(sealNode(child, expandedParent));
        }
        return items;
    }

    private CanonicalJson.CanonicalValue sealNode(
            Materializer.MaterializedNode node,
            ObjectNode expandedParent) {
        stageControl.checkpoint();
        if ("compositionViewport".equals(node.kind())) {
            return sealViewport(node, expandedParent);
        }
        var expanded = expandNode(node, expandedParent);
        var members = new TreeMap<String, CanonicalJson.CanonicalValue>();
        members.put("kind", CanonicalJson.stringValue(node.kind()));
        members.put("occurrenceId", CanonicalJson.stringValue(nextOccurrenceId()));
        for (var entry : expanded.members().entrySet()) {
            stageControl.checkpoint();
            if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            if ("text".equals(node.kind()) && "runs".equals(entry.getKey())) {
                members.put("runs", lowerRuns(entry.getValue()));
                continue;
            }
            if (isVectorEntryArray(node.kind(), entry.getKey())) {
                members.put(entry.getKey(), lowerVectorEntries(entry.getValue()));
                continue;
            }
            putLowered(members, entry.getKey(), entry.getValue());
        }
        if (nodeContracts.isContainer(node.kind())) {
            members.put("children", CanonicalJson.arrayValue(
                    sealChildren(node.children(), expanded)));
        }
        return CanonicalJson.objectValue(members);
    }

    /** viewport：occurrenceId 先分配，sourceCanvas 在 children 之前分配。 */
    private CanonicalJson.CanonicalValue sealViewport(
            Materializer.MaterializedNode node,
            ObjectNode expandedParent) {
        stageControl.checkpoint();
        var expanded = expandNode(node, expandedParent);
        var members = new TreeMap<String, CanonicalJson.CanonicalValue>();
        members.put("kind", CanonicalJson.stringValue("compositionViewport"));
        members.put("occurrenceId", CanonicalJson.stringValue(nextOccurrenceId()));
        for (var entry : expanded.members().entrySet()) {
            stageControl.checkpoint();
            if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            putLowered(members, entry.getKey(), entry.getValue());
        }
        if (!node.children().isEmpty() && "canvas".equals(node.children().get(0).kind())) {
            reserveChildEdge();
            var source = node.children().get(0);
            var expandedSource = nodeContracts.expandNodeDefaults("canvas", source.members());
            var sourceMembers = new TreeMap<String, CanonicalJson.CanonicalValue>();
            sourceMembers.put("occurrenceId", CanonicalJson.stringValue(nextOccurrenceId()));
            for (var entry : expandedSource.members().entrySet()) {
                stageControl.checkpoint();
                if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                        || "kind".equals(entry.getKey())
                        || "bleed".equals(entry.getKey())) {
                    continue;
                }
                putLowered(sourceMembers, entry.getKey(), entry.getValue());
            }
            sourceMembers.put("children", CanonicalJson.arrayValue(
                    sealChildren(source.children(), expandedSource)));
            members.put("sourceCanvas", CanonicalJson.objectValue(sourceMembers));
        } else {
            throw new IllegalStateException("compositionViewport requires one source Canvas");
        }
        return CanonicalJson.objectValue(members);
    }

    private ObjectNode expandNode(
            Materializer.MaterializedNode node,
            ObjectNode expandedParent) {
        var expanded = nodeContracts.expandNodeDefaults(node.kind(), node.members());
        if (!(expanded.members().get("placement") instanceof ObjectNode placement)) {
            throw new IllegalStateException("non-Canvas RenderDSL node requires placement");
        }
        var parentKind = expandedParent.members().get("kind") instanceof Text text
                ? text.value()
                : null;
        var members = new java.util.LinkedHashMap<String, DesignNodeValue>(expanded.members());
        members.put("placement", nodeContracts.expandPlacementDefaults(
                placement, parentKind, expandedParent));
        return new ObjectNode(members);
    }

    private void putLowered(
            TreeMap<String, CanonicalJson.CanonicalValue> members,
            String key,
            DesignNodeValue value
    ) {
        var resourceMember = nodeContracts.loweredResourceMember(key);
        if (resourceMember != null) {
            if (!(value instanceof ObjectNode resource)
                    || resource.members().size() != 1
                    || !(resource.members().get("resourceId") instanceof Text resourceId)) {
                throw new IllegalStateException("logical AssetRef survived RenderDocument lowering");
            }
            members.put(resourceMember, CanonicalJson.stringValue(resourceId.value()));
            return;
        }
        if (value instanceof NumberToken number && key.endsWith("Mm")) {
            var ptKey = key.substring(0, key.length() - 2) + "Pt";
            members.put(ptKey, CanonicalJson.decimalValue(convertMmToPt(number.rawToken())));
            return;
        }
        members.put(key, lowerValue(value));
    }

    private CanonicalJson.CanonicalValue lowerValue(DesignNodeValue value) {
        stageControl.checkpoint();
        if (value instanceof Text text) {
            return CanonicalJson.stringValue(text.value());
        }
        if (value instanceof NumberToken number) {
            return CanonicalJson.decimalValue(new BigDecimal(number.rawToken()));
        }
        if (value instanceof Bool bool) {
            return CanonicalJson.boolValue(bool.value());
        }
        if (value instanceof ObjectNode object) {
            var members = new TreeMap<String, CanonicalJson.CanonicalValue>();
            for (var entry : object.members().entrySet()) {
                stageControl.checkpoint();
                putLowered(members, entry.getKey(), entry.getValue());
            }
            return CanonicalJson.objectValue(members);
        }
        if (value instanceof ArrayNode array) {
            var items = new ArrayList<CanonicalJson.CanonicalValue>(array.items().size());
            for (var item : array.items()) {
                stageControl.checkpoint();
                items.add(lowerValue(item));
            }
            return CanonicalJson.arrayValue(items);
        }
        throw new IllegalStateException("unknown DesignNodeValue variant");
    }

    /** mm → pt exact：×360/127，HALF_EVEN 量化至 ≤6 位小数。 */
    private static BigDecimal convertMmToPt(String mmToken) {
        var mm = new BigDecimal(mmToken);
        return mm.multiply(MM_TO_PT).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    private List<CanonicalJson.CanonicalValue> sealResources(
            List<Materializer.ResourceEntry> resources) {
        var items = new ArrayList<CanonicalJson.CanonicalValue>(resources.size());
        for (var resource : resources) {
            stageControl.checkpoint();
            items.add(RenderResourceCanonicalizer.canonicalValue(resource));
            stageControl.checkpoint();
        }
        return items;
    }

    private void reserveChildEdge() {
        stageControl.checkpoint();
        var capacityProblem = requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CHILD_EDGES,
                1);
        if (capacityProblem.isPresent()) {
            throw new SealCapacityExceeded(capacityProblem.orElseThrow());
        }
    }

    private CanonicalJson.CanonicalValue lowerRuns(DesignNodeValue value) {
        if (!(value instanceof ArrayNode runs)) {
            throw new IllegalStateException("Text runs must be an array at document seal");
        }
        var items = new ArrayList<CanonicalJson.CanonicalValue>();
        for (var run : runs.items()) {
            stageControl.checkpoint();
            reserveRun();
            items.add(lowerRun(run));
        }
        return CanonicalJson.arrayValue(items);
    }

    private CanonicalJson.CanonicalValue lowerRun(DesignNodeValue value) {
        if (!(value instanceof ObjectNode run)
                || !(run.members().get("text") instanceof Text text)) {
            throw new IllegalStateException("Text Run requires text at document seal");
        }
        reserveTextScalars(text.value());
        return lowerValue(run);
    }

    private void reserveRun() {
        stageControl.checkpoint();
        var capacityProblem = requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_RUNS,
                1);
        if (capacityProblem.isPresent()) {
            throw new SealCapacityExceeded(capacityProblem.orElseThrow());
        }
    }

    private void reserveTextScalars(String text) {
        stageControl.checkpoint();
        var scalarCount = text.codePointCount(0, text.length());
        var capacityProblem = requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_TEXT_SCALARS,
                scalarCount);
        if (capacityProblem.isPresent()) {
            throw new SealCapacityExceeded(capacityProblem.orElseThrow());
        }
    }

    private static boolean isVectorEntryArray(String nodeKind, String member) {
        return switch (nodeKind) {
            case "polygon", "polyline" -> "points".equals(member);
            case "path" -> "commands".equals(member);
            default -> false;
        };
    }

    private CanonicalJson.CanonicalValue lowerVectorEntries(DesignNodeValue value) {
        if (!(value instanceof ArrayNode entries)) {
            throw new IllegalStateException("Vector entries must be an array at document seal");
        }
        var items = new ArrayList<CanonicalJson.CanonicalValue>();
        for (var entry : entries.items()) {
            stageControl.checkpoint();
            reserveVectorEntry();
            items.add(lowerValue(entry));
        }
        return CanonicalJson.arrayValue(items);
    }

    private void reserveVectorEntry() {
        stageControl.checkpoint();
        var capacityProblem = requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_VECTOR_ENTRIES,
                1);
        if (capacityProblem.isPresent()) {
            throw new SealCapacityExceeded(capacityProblem.orElseThrow());
        }
    }

    private String nextOccurrenceId() {
        stageControl.checkpoint();
        if (occurrenceCounter < 0) {
            throw new IllegalStateException("RenderDocument occurrence ordinal overflow");
        }
        var capacityProblem = requestCapacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_STATIC_NODES,
                1);
        if (capacityProblem.isPresent()) {
            throw new SealCapacityExceeded(capacityProblem.orElseThrow());
        }
        var id = "rwocc_" + String.format("%016x", occurrenceCounter);
        occurrenceCounter++;
        occurrenceIdsInPreorder.add(id);
        return id;
    }

    private static byte[] lengthFrame(int length) {
        var frame = new byte[8];
        var value = (long) length;
        for (int index = 7; index >= 0; index--) {
            frame[index] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return frame;
    }
}
