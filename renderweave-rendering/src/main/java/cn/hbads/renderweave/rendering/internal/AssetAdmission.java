package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetApplication.AssetId;
import cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope;
import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.ExternalAssetReadAuthorization;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluator stage 5 deep module. It admits every authored and effective external AssetRef before
 * CapabilityState initialization; success is represented by an opaque token required by the
 * Materializer, so stage 7 cannot accidentally repeat or bypass external prechecks.
 */
final class AssetAdmission {

    private static final int MAX_AUTHORED_ASSET_OCCURRENCES = 4_096;

    sealed interface Outcome permits Admitted, Rejected {
    }

    /** Opaque proof that stage 5 completed for this Evaluation. */
    static final class Admitted implements Outcome {
        private Admitted() {
        }
    }

    record Rejected(EvaluationStage stage, RenderingProblem problem) implements Outcome {
    }

    private final ClosureSnapshot closure;
    private final DesignSemanticAuthority semantics;
    private final AssetResolutionPort assets;
    private final String ownerScope;
    private final Map<String, ObjectNode> documentsByTemplate = new HashMap<>();
    private int authoredAtomCount;

    private AssetAdmission(
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            AssetResolutionPort assets
    ) {
        this.closure = closure;
        this.semantics = semantics;
        this.assets = assets;
        this.ownerScope = closure.ownerScope().value();
    }

    static Outcome admit(
            ClosureSnapshot closure,
            DesignSemanticAuthority semantics,
            AssetResolutionPort assets,
            AdmittedRenderInput admittedInput,
            ExternalAssetReadAuthorization externalAssetReadAuthorization
    ) {
        var admission = new AssetAdmission(closure, semantics, assets);
        return admission.admit(admittedInput, externalAssetReadAuthorization);
    }

    private Outcome admit(
            AdmittedRenderInput admittedInput,
            ExternalAssetReadAuthorization externalAssetReadAuthorization
    ) {
        var externalAtoms = new ArrayList<AssetAtom>();
        for (var custom : admittedInput.externalCustomOverrides().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            collectExternalAtoms(custom.getValue(), externalAtoms);
        }
        var authored = new ArrayList<AssetAtom>();
        for (var snapshot : closure.snapshots()) {
            var document = documentOf(snapshot.templateId().value(), snapshot.canonicalDesignDslUtf8());
            if (document == null) {
                return rejected(EvaluationStage.TEMPLATE_CLOSURE,
                        ProblemCode.RENDER_INTERNAL_ERROR, null);
            }
            collectAuthoredAtoms(document, authored);
        }
        if (authored.isEmpty() && externalAtoms.isEmpty()) {
            return new Admitted();
        }
        for (var atom : authored) {
            var capacityFailure = reserveAuthoredAtom();
            if (capacityFailure != null) {
                return capacityFailure;
            }
            if (assets == null) {
                return rejected(
                        EvaluationStage.ASSET_ADMISSION,
                        ProblemCode.EVALUATION_FAILED,
                        null);
            }
            var failure = precheck(atom);
            if (failure != null) {
                return failure;
            }
        }
        if (externalAtoms.isEmpty()) {
            return new Admitted();
        }
        if (externalAssetReadAuthorization == ExternalAssetReadAuthorization.DENIED) {
            return rejected(EvaluationStage.ASSET_ADMISSION, ProblemCode.ASSET_NOT_FOUND, null);
        }
        if (externalAssetReadAuthorization == ExternalAssetReadAuthorization.UNAVAILABLE
                || assets == null) {
            return rejected(EvaluationStage.ASSET_ADMISSION, ProblemCode.EVALUATION_FAILED, null);
        }
        for (var atom : externalAtoms) {
            var failure = precheck(atom);
            if (failure != null) {
                return failure;
            }
        }
        return new Admitted();
    }

    private ObjectNode documentOf(String templateId, byte[] canonicalDesignDslUtf8) {
        return documentsByTemplate.computeIfAbsent(templateId, ignored -> {
            var outcome = semantics.interpret(canonicalDesignDslUtf8);
            return outcome instanceof DesignSemanticAuthority.Interpreted interpreted
                    ? interpreted.document() : null;
        });
    }

    private static void collectExternalAtoms(DesignValue value, List<AssetAtom> atoms) {
        if (value instanceof DesignValue.ImageRef ref) {
            atoms.add(new AssetAtom(
                    ref.assetId(), AssetKind.IMAGE));
        } else if (value instanceof DesignValue.FontRef ref) {
            atoms.add(new AssetAtom(
                    ref.assetId(), AssetKind.FONT));
        } else if (value instanceof DesignValue.ListValue list) {
            for (var item : list.items()) {
                collectExternalAtoms(item, atoms);
            }
        }
    }

    private static void collectAuthoredAtoms(DesignNodeValue value, List<AssetAtom> atoms) {
        if (value instanceof ObjectNode object) {
            for (var entry : canonicalMembers(object)) {
                var kind = assetKind(entry.getKey());
                if (kind != null && entry.getValue() instanceof ObjectNode ref
                        && isAssetRefShape(ref)) {
                    atoms.add(new AssetAtom(assetId(ref), kind));
                }
            }
            var typedKind = typedAssetKind(object);
            if (typedKind != null) {
                for (var memberName : List.of("defaultValue", "value")) {
                    var member = object.members().get(memberName);
                    if (member instanceof ObjectNode ref && isAssetRefShape(ref)) {
                        atoms.add(new AssetAtom(assetId(ref), typedKind));
                    } else if (member instanceof ArrayNode array) {
                        for (var item : array.items()) {
                            if (item instanceof ObjectNode ref && isAssetRefShape(ref)) {
                                atoms.add(new AssetAtom(assetId(ref), typedKind));
                            }
                        }
                    }
                }
            }
            for (var entry : canonicalMembers(object)) {
                collectAuthoredAtoms(entry.getValue(), atoms);
            }
        } else if (value instanceof ArrayNode array) {
            for (var item : array.items()) {
                collectAuthoredAtoms(item, atoms);
            }
        }
    }

    private static List<Map.Entry<String, DesignNodeValue>> canonicalMembers(ObjectNode object) {
        return object.members().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }

    private static AssetKind typedAssetKind(ObjectNode object) {
        var valueType = object.members().get("valueType");
        if (valueType instanceof Text token) {
            return assetKind(token.value());
        }
        if (valueType instanceof ObjectNode derived
                && derived.members().get("type") instanceof Text type
                && "list".equals(type.value())
                && derived.members().get("items") instanceof Text items) {
            return assetKind(items.value());
        }
        return null;
    }

    private static AssetKind assetKind(String token) {
        return switch (token) {
            case "imageRef" -> AssetKind.IMAGE;
            case "fontRef" -> AssetKind.FONT;
            default -> null;
        };
    }

    private static String assetId(ObjectNode ref) {
        return ((Text) ref.members().get("assetId")).value();
    }

    private Rejected reserveAuthoredAtom() {
        authoredAtomCount++;
        return authoredAtomCount > MAX_AUTHORED_ASSET_OCCURRENCES
                ? rejected(EvaluationStage.ASSET_ADMISSION,
                        ProblemCode.ASSET_BUDGET_EXCEEDED,
                        "assetsAndFetch.authoredAssetOccurrences")
                : null;
    }

    private static Rejected precheck(AssetResolutionPort.PrecheckOutcome outcome) {
        return switch (outcome) {
            case AssetResolutionPort.PrecheckOutcome.PrecheckPassed ignored -> null;
            case AssetResolutionPort.PrecheckOutcome.PrecheckRejected ignored ->
                    rejected(EvaluationStage.ASSET_ADMISSION, ProblemCode.ASSET_NOT_FOUND, null);
            case AssetResolutionPort.PrecheckOutcome.PrecheckUnavailable ignored ->
                    rejected(EvaluationStage.ASSET_ADMISSION, ProblemCode.EVALUATION_FAILED, null);
        };
    }

    private Rejected precheck(AssetAtom atom) {
        return precheck(assets.precheckAdmission(
                new OwnerScope(ownerScope),
                new AssetId(atom.assetId()),
                atom.kind()));
    }

    private static boolean isAssetRefShape(ObjectNode object) {
        return object.members().size() == 1
                && object.members().get("assetId") instanceof Text;
    }

    private static Rejected rejected(EvaluationStage stage, ProblemCode code, String limitId) {
        return new Rejected(stage, new RenderingProblem(
                code,
                stage,
                Optional.empty(),
                limitId == null ? Optional.empty() : Optional.of(new LimitId(limitId))));
    }

    private record AssetAtom(
            String assetId,
            AssetKind kind
    ) {
    }
}
