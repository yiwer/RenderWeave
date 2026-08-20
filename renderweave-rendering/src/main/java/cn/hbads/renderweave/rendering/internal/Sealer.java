package cn.hbads.renderweave.rendering.internal;

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
 * <p>T21 边界：节点 default 展开随 RenderNodeContract catalog 数据深化逐 kind 物化，本票
 * seal 保留 authored + 已求值 + 量化后的成员；catalog 未含 default 数据的成员不虚构。
 */
final class Sealer {

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

    sealed interface SealOutcome permits Sealed, SealRejected {
    }

    record Sealed(SealedEvaluation evaluation) implements SealOutcome {
    }

    record SealRejected(String limitId) implements SealOutcome {
    }

    private long occurrenceCounter;
    private final List<String> occurrenceIdsInPreorder = new ArrayList<>();

    private Sealer() {
    }

    static SealOutcome seal(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest
    ) {
        var sealer = new Sealer();
        try {
            var canvasJson = sealer.sealCanvas(tree.root());
            var resourcesJson = sealer.sealResources(tree.resources());
            var envelope = new TreeMap<String, String>();
            envelope.put("canvas", canvasJson);
            envelope.put("dslVersion", CanonicalJson.string(RENDER_DSL_VERSION));
            envelope.put("layoutProfile", CanonicalJson.string(LAYOUT_PROFILE));
            envelope.put("resources", CanonicalJson.array(resourcesJson));
            var canonical = CanonicalJson.object(envelope).getBytes(StandardCharsets.UTF_8);
            if (canonical.length > 64L * 1024 * 1024) {
                return new SealRejected("renderDocument.canonicalBytes");
            }
            var documentDigest = RenderingDigests.renderDocumentDigest(canonical);
            var resultDigest = evaluationResultDigest(
                    closure, admittedInput, tree, capabilityResultDigest);
            return new Sealed(new SealedEvaluation(canonical, documentDigest, resultDigest));
        } catch (SealLimitExceeded limit) {
            return new SealRejected(limit.limitId);
        }
    }

    private static final class SealLimitExceeded extends RuntimeException {
        private final String limitId;

        SealLimitExceeded(String limitId) {
            this.limitId = limitId;
        }
    }

    private static String evaluationResultDigest(
            ClosureSnapshot closure,
            AdmittedRenderInput admittedInput,
            Materializer.MaterializedTree tree,
            String capabilityResultDigest
    ) {
        return evaluationResultDigest(
                closure.ownerScope().value(),
                ClosureManifests.digest(closure),
                AdmittedInputCanonicalizer.digest(admittedInput),
                assetSelectionDigest(tree.resources()),
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
        var framed = new java.io.ByteArrayOutputStream();
        for (var resource : resources) {
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
                    technicalDescriptorWire(resource.technicalDescriptor()));
            var bytes = CanonicalJson.object(entry).getBytes(StandardCharsets.UTF_8);
            framed.writeBytes(lengthFrame(bytes.length));
            framed.writeBytes(bytes);
        }
        return RenderingDigests.digestWithDomain(ASSET_SELECTION_DOMAIN, framed.toByteArray());
    }

    private static String technicalDescriptorWire(
            cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.TechnicalDescriptor descriptor) {
        var members = new TreeMap<String, String>();
        if (descriptor
                instanceof cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor image) {
            members.put("colorEncoding", CanonicalJson.string(image.colorEncoding().name()));
            members.put("encodedHeightPx", CanonicalJson.decimal(
                    BigDecimal.valueOf(image.encodedHeightPx())));
            members.put("encodedWidthPx", CanonicalJson.decimal(
                    BigDecimal.valueOf(image.encodedWidthPx())));
            members.put("frameCount", CanonicalJson.decimal(
                    BigDecimal.valueOf(image.frameCount())));
            members.put("kind", CanonicalJson.string("image"));
            members.put("logicalHeightPx", CanonicalJson.decimal(
                    BigDecimal.valueOf(image.logicalHeightPx())));
            members.put("logicalWidthPx", CanonicalJson.decimal(
                    BigDecimal.valueOf(image.logicalWidthPx())));
            members.put("orientation", CanonicalJson.string(image.orientation().name()));
        } else if (descriptor
                instanceof cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontDescriptor font) {
            members.put("faceIndex", CanonicalJson.decimal(BigDecimal.valueOf(font.faceIndex())));
            members.put("flavor", CanonicalJson.string(font.flavor().name()));
            members.put("kind", CanonicalJson.string("font"));
            members.put("unitsPerEm", CanonicalJson.decimal(BigDecimal.valueOf(font.unitsPerEm())));
        } else {
            throw new IllegalStateException("unknown technical descriptor");
        }
        return CanonicalJson.object(members);
    }

    // ------------------------------------------------------------------
    // RenderDocument lowering
    // ------------------------------------------------------------------

    private String sealCanvas(Materializer.MaterializedNode node) {
        if (!"canvas".equals(node.kind())) {
            throw new IllegalStateException("render document root must be the canvas");
        }
        var members = new TreeMap<String, String>();
        members.put("occurrenceId", CanonicalJson.string(nextOccurrenceId()));
        for (var entry : node.members().members().entrySet()) {
            if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            putLowered(members, entry.getKey(), entry.getValue());
        }
        members.put("children", CanonicalJson.array(sealChildren(node.children())));
        return CanonicalJson.object(members);
    }

    private List<String> sealChildren(List<Materializer.MaterializedNode> children) {
        var items = new ArrayList<String>(children.size());
        for (var child : children) {
            items.add(sealNode(child));
        }
        return items;
    }

    private String sealNode(Materializer.MaterializedNode node) {
        if ("compositionViewport".equals(node.kind())) {
            return sealViewport(node);
        }
        var members = new TreeMap<String, String>();
        members.put("kind", CanonicalJson.string(node.kind()));
        members.put("occurrenceId", CanonicalJson.string(nextOccurrenceId()));
        for (var entry : node.members().members().entrySet()) {
            if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            putLowered(members, entry.getKey(), entry.getValue());
        }
        if (!node.children().isEmpty()) {
            members.put("children", CanonicalJson.array(sealChildren(node.children())));
        }
        return CanonicalJson.object(members);
    }

    /** viewport：occurrenceId 先分配，sourceCanvas 在 children 之前分配。 */
    private String sealViewport(Materializer.MaterializedNode node) {
        var members = new TreeMap<String, String>();
        members.put("kind", CanonicalJson.string("compositionViewport"));
        members.put("occurrenceId", CanonicalJson.string(nextOccurrenceId()));
        for (var entry : node.members().members().entrySet()) {
            if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                    || "kind".equals(entry.getKey())) {
                continue;
            }
            putLowered(members, entry.getKey(), entry.getValue());
        }
        if (!node.children().isEmpty() && "canvas".equals(node.children().get(0).kind())) {
            var source = node.children().get(0);
            var sourceMembers = new TreeMap<String, String>();
            sourceMembers.put("occurrenceId", CanonicalJson.string(nextOccurrenceId()));
            for (var entry : source.members().members().entrySet()) {
                if (AUTHORING_ONLY_MEMBERS.contains(entry.getKey())
                        || "kind".equals(entry.getKey())) {
                    continue;
                }
                putLowered(sourceMembers, entry.getKey(), entry.getValue());
            }
            sourceMembers.put("children", CanonicalJson.array(sealChildren(source.children())));
            members.put("sourceCanvas", CanonicalJson.object(sourceMembers));
        }
        return CanonicalJson.object(members);
    }

    private void putLowered(TreeMap<String, String> members, String key, DesignNodeValue value) {
        if (value instanceof NumberToken number && key.endsWith("Mm")) {
            var ptKey = key.substring(0, key.length() - 2) + "Pt";
            members.put(ptKey, CanonicalJson.decimal(convertMmToPt(number.rawToken())));
            return;
        }
        members.put(key, lowerValue(value));
    }

    private String lowerValue(DesignNodeValue value) {
        if (value instanceof Text text) {
            return CanonicalJson.string(text.value());
        }
        if (value instanceof NumberToken number) {
            return CanonicalJson.decimal(new BigDecimal(number.rawToken()));
        }
        if (value instanceof Bool bool) {
            return CanonicalJson.bool(bool.value());
        }
        if (value instanceof ObjectNode object) {
            var members = new TreeMap<String, String>();
            for (var entry : object.members().entrySet()) {
                if (entry.getValue() instanceof NumberToken number
                        && entry.getKey().endsWith("Mm")) {
                    var ptKey = entry.getKey().substring(0, entry.getKey().length() - 2) + "Pt";
                    members.put(ptKey, CanonicalJson.decimal(convertMmToPt(number.rawToken())));
                } else {
                    members.put(entry.getKey(), lowerValue(entry.getValue()));
                }
            }
            return CanonicalJson.object(members);
        }
        if (value instanceof ArrayNode array) {
            var items = new ArrayList<String>(array.items().size());
            for (var item : array.items()) {
                items.add(lowerValue(item));
            }
            return CanonicalJson.array(items);
        }
        throw new IllegalStateException("unknown DesignNodeValue variant");
    }

    /** mm → pt exact：×360/127，HALF_EVEN 量化至 ≤6 位小数。 */
    private static BigDecimal convertMmToPt(String mmToken) {
        var mm = new BigDecimal(mmToken);
        return mm.multiply(MM_TO_PT).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    private List<String> sealResources(List<Materializer.ResourceEntry> resources) {
        var items = new ArrayList<String>(resources.size());
        for (var resource : resources) {
            var entry = new TreeMap<String, String>();
            entry.put("acceptanceProfileId", CanonicalJson.string(resource.acceptanceProfileId()));
            entry.put("byteLength", CanonicalJson.decimal(
                    BigDecimal.valueOf(resource.byteLength())));
            entry.put("expiresAt", CanonicalJson.decimal(
                    BigDecimal.valueOf(resource.leaseExpiresAtEpochSecond())));
            entry.put("fetchUrl", CanonicalJson.string(resource.fetchUrl()));
            entry.put("kind", CanonicalJson.string(resource.kind().toLowerCase()));
            entry.put("mediaType", CanonicalJson.string(resource.mediaType()));
            entry.put("resourceId", CanonicalJson.string(resource.resourceId()));
            entry.put("sha256", CanonicalJson.string(resource.sha256()));
            entry.put("technicalDescriptor",
                    technicalDescriptorWire(resource.technicalDescriptor()));
            items.add(CanonicalJson.object(entry));
        }
        return items;
    }

    private String nextOccurrenceId() {
        if (occurrenceCounter < 0) {
            throw new SealLimitExceeded("closureAndExpansion.renderOccurrences");
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
