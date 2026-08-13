package cn.hbads.renderweave.inference.eval.visual;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Scores actual N7 durable checkpoints against the semantic stage gold while corpus v2 remains
 * the exact render authority. The richer R1 layered scorer stays a separate zero-Provider shadow seam.
 */
public final class N7LiveSemanticEvaluation {
    public static final String VERSION = "renderweave-n7-live-semantic-evaluation/1.0";
    private static final String EVALUATOR_VERSION = "renderweave-n7-live-semantic-evaluator/1.0";
    private static final VisualStageCorpus SEMANTIC_GOLD = new VisualStageCorpus();

    private final VisualStageEvaluator evaluator = new VisualStageEvaluator();

    public VisualStageEvaluationResult evaluate(
            LayeredVisualCorpus.Case evaluationCase,
            VisualStageSnapshot actual
    ) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        return evaluator.evaluate(evaluationCase.renderCase(), Objects.requireNonNull(actual, "actual"));
    }

    public VisualStageEvaluationResult evaluateFailure(
            LayeredVisualCorpus.Case evaluationCase,
            VisualStageSnapshot actual,
            String outcomeCode
    ) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        return evaluator.evaluateFailure(evaluationCase.renderCase(),
                Objects.requireNonNull(actual, "actual"), outcomeCode);
    }

    public N7LiveSemanticEvaluationReport report(
            LayeredVisualCorpus corpus,
            Binding binding,
            List<VisualStageEvaluationResult> source
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(binding, "binding");
        source = List.copyOf(Objects.requireNonNull(source, "source"));
        var expected = new HashSet<>(binding.caseIds());
        var indexed = new LinkedHashMap<String, VisualStageEvaluationResult>();
        for (var result : source) {
            if (!expected.contains(result.caseId()) || indexed.putIfAbsent(result.caseId(), result) != null) {
                throw new IllegalArgumentException("N7_LIVE_RESULT_ASSIGNMENT_INVALID");
            }
            var layeredCase = corpus.require(result.caseId());
            var gold = layeredCase.renderCase();
            if (result.partition() != gold.partition() || result.style() != gold.style()
                    || result.domainPack() != gold.scene().domainPack()) {
                throw new IllegalArgumentException("N7_LIVE_RESULT_SLICE_INVALID");
            }
        }
        for (var caseId : binding.caseIds()) corpus.require(caseId);
        var observedCaseIds = binding.caseIds().stream().filter(indexed::containsKey).toList();
        var ordered = observedCaseIds.stream().map(indexed::get).toList();
        var semantic = new VisualStageReporter().report(SEMANTIC_GOLD, ordered);
        return new N7LiveSemanticEvaluationReport(
                N7LiveSemanticEvaluationReport.VERSION, evaluatorIdentity(), binding.authorizationId(),
                binding.phase(), binding.repositoryEvaluationIdentity(), binding.profileId(),
                binding.profileSnapshotSha256(), binding.qualificationProtocolIdentity(),
                binding.assignmentIdentity(), LayeredVisualCorpus.VERSION, corpus.corpusIdentity(),
                corpus.sourceScenesSha256(), binding.caseIds(), observedCaseIds,
                observedCaseIds.equals(binding.caseIds()), semantic.global(), semantic.partitions(),
                semantic.styles(), semantic.domainPacks()
        );
    }

    public static String evaluatorIdentity() {
        return EVALUATOR_VERSION + ":" + sha256(List.of(
                VERSION,
                EVALUATOR_VERSION,
                "actual-input=durable-visual-stage-checkpoint/1.0",
                "render-authority=" + LayeredVisualCorpus.VERSION,
                "semantic-gold=" + VisualStageCorpus.VERSION,
                "semantic-matching=geometry-plus-canonical-entity-path/1.0",
                "candidate-contract=renderweave-live-candidate-evaluator/1.0",
                "aggregation=" + VisualStageReport.VERSION,
                "report=" + N7LiveSemanticEvaluationReport.VERSION,
                "integer-basis-points-floor/1.0"
        ));
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var encoded = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    public record Binding(
            String authorizationId,
            String phase,
            String repositoryEvaluationIdentity,
            String profileId,
            String profileSnapshotSha256,
            String qualificationProtocolIdentity,
            String assignmentIdentity,
            List<String> caseIds
    ) {
        public Binding {
            if (authorizationId == null || !authorizationId.matches("[a-z][a-z0-9-]{0,127}")
                    || phase == null || !phase.matches("[A-Z][A-Z0-9_]{0,31}")
                    || repositoryEvaluationIdentity == null
                    || !repositoryEvaluationIdentity.matches(
                    "renderweave-visual-evaluation-tree-sha256/[12]:[0-9a-f]{64}")
                    || profileId == null || !profileId.matches("[a-z][a-z0-9-]{0,127}")
                    || profileSnapshotSha256 == null || !profileSnapshotSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("N7_LIVE_BINDING_INVALID");
            }
            LayeredVisualAnnotation.requireIdentity(qualificationProtocolIdentity,
                    "N7_LIVE_BINDING_PROTOCOL_IDENTITY_INVALID");
            LayeredVisualAnnotation.requireIdentity(assignmentIdentity,
                    "N7_LIVE_BINDING_ASSIGNMENT_IDENTITY_INVALID");
            caseIds = List.copyOf(Objects.requireNonNull(caseIds, "caseIds"));
            if (caseIds.isEmpty() || caseIds.size() > 60
                    || new HashSet<>(caseIds).size() != caseIds.size()
                    || caseIds.stream().anyMatch(item -> item == null
                    || !item.matches("[a-z][a-z0-9-]{0,127}"))) {
                throw new IllegalArgumentException("N7_LIVE_BINDING_CASES_INVALID");
            }
        }
    }
}
