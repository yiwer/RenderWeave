package cn.hbads.renderweave.inference.eval.visual;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ephemeral scorer input. It intentionally has no JSON codec and its string representation
 * exposes only counts, never OCR text or a Candidate payload.
 */
public record LayeredVisualPrediction(
        String caseId,
        List<OcrLine> ocrLines,
        List<Region> regions,
        List<Evidence> evidence,
        List<LayeredVisualAnnotation.PrecedenceEdge> precedenceEdges,
        List<LayeredVisualAnnotation.RepeatGroup> repeatGroups,
        List<LayeredVisualAnnotation.Entity> entities,
        List<LayeredVisualAnnotation.Relationship> relationships,
        List<LayeredVisualAnnotation.Binding> bindings,
        Candidate candidate,
        List<Confidence> confidence,
        Runtime runtime
) {
    public LayeredVisualPrediction {
        caseId = LayeredVisualAnnotation.requireId(caseId, "PREDICTION_CASE_ID_INVALID");
        ocrLines = LayeredVisualAnnotation.immutable(ocrLines, 512, "PREDICTION_OCR_COUNT_INVALID");
        regions = LayeredVisualAnnotation.immutable(regions, 2_048, "PREDICTION_REGION_COUNT_INVALID");
        evidence = LayeredVisualAnnotation.immutable(evidence, 4_096, "PREDICTION_EVIDENCE_COUNT_INVALID");
        precedenceEdges = LayeredVisualAnnotation.immutable(precedenceEdges, 4_096,
                "PREDICTION_PRECEDENCE_COUNT_INVALID");
        repeatGroups = LayeredVisualAnnotation.immutable(repeatGroups, 256,
                "PREDICTION_REPEAT_COUNT_INVALID");
        entities = LayeredVisualAnnotation.immutable(entities, 256, "PREDICTION_ENTITY_COUNT_INVALID");
        relationships = LayeredVisualAnnotation.immutable(relationships, 512,
                "PREDICTION_RELATIONSHIP_COUNT_INVALID");
        bindings = LayeredVisualAnnotation.immutable(bindings, 2_048, "PREDICTION_BINDING_COUNT_INVALID");
        confidence = LayeredVisualAnnotation.immutable(confidence, 4_096,
                "PREDICTION_CONFIDENCE_COUNT_INVALID");
        Objects.requireNonNull(runtime, "runtime");
        requireUnique(ocrLines.stream().map(OcrLine::lineId).toList(), "DUPLICATE_PREDICTED_OCR_LINE");
        requireUnique(regions.stream().map(Region::regionId).toList(), "DUPLICATE_PREDICTED_REGION");
    }

    @Override
    public String toString() {
        return "LayeredVisualPrediction[caseId=" + caseId + ", ocrLines=" + ocrLines.size()
                + ", regions=" + regions.size() + ", evidence=" + evidence.size()
                + ", precedenceEdges=" + precedenceEdges.size() + ", repeatGroups=" + repeatGroups.size()
                + ", entities=" + entities.size() + ", relationships=" + relationships.size()
                + ", bindings=" + bindings.size() + ", candidatePresent=" + (candidate != null)
                + ", confidence=" + confidence.size() + ", runtime=" + runtime + "]";
    }

    public record OcrLine(String lineId, String text) {
        public OcrLine {
            lineId = LayeredVisualAnnotation.requireId(lineId, "PREDICTED_OCR_LINE_ID_INVALID");
            text = LayeredVisualAnnotation.requireText(text, 2_048, "PREDICTED_OCR_TEXT_INVALID");
        }

        @Override
        public String toString() {
            return "OcrLine[lineId=" + lineId + ", text=<ephemeral>]";
        }
    }

    public record Region(
            String regionId,
            LayeredVisualAnnotation.RegionKind kind,
            LayeredVisualAnnotation.Geometry geometry,
            int confidenceBps
    ) {
        public Region {
            regionId = LayeredVisualAnnotation.requireId(regionId, "PREDICTED_REGION_ID_INVALID");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(geometry, "geometry");
            requireBasisPoints(confidenceBps, "PREDICTED_REGION_CONFIDENCE_INVALID");
        }
    }

    public record Evidence(
            LayeredVisualAnnotation.OwnerKind ownerKind,
            String ownerId,
            LayeredVisualAnnotation.Geometry geometry
    ) {
        public Evidence {
            Objects.requireNonNull(ownerKind, "ownerKind");
            ownerId = LayeredVisualAnnotation.requireId(ownerId, "PREDICTED_EVIDENCE_OWNER_INVALID");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    public record Candidate(
            String rootEntityId,
            List<LayeredVisualAnnotation.CandidateField> fields,
            List<String> relationshipIds,
            boolean contractValid,
            boolean dagValid,
            boolean topologyPreserved,
            int criticalHallucinations,
            int blockers,
            String outcomeCode
    ) {
        public Candidate {
            rootEntityId = LayeredVisualAnnotation.requireId(rootEntityId, "PREDICTED_CANDIDATE_ROOT_INVALID");
            fields = LayeredVisualAnnotation.immutable(fields, 2_048, "PREDICTED_CANDIDATE_FIELDS_INVALID");
            relationshipIds = LayeredVisualAnnotation.requireIds(relationshipIds, 512,
                    "PREDICTED_CANDIDATE_RELATIONSHIPS_INVALID");
            if (criticalHallucinations < 0 || blockers < 0) {
                throw LayeredVisualAnnotation.invalid("PREDICTED_CANDIDATE_COUNTS_INVALID");
            }
            if (outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw LayeredVisualAnnotation.invalid("PREDICTED_CANDIDATE_OUTCOME_INVALID");
            }
        }

        @Override
        public String toString() {
            return "Candidate[rootEntityId=<ephemeral>, fields=" + fields.size()
                    + ", relationships=" + relationshipIds.size() + ", outcomeCode=" + outcomeCode + "]";
        }
    }

    public record Confidence(String ownerId, int confidenceBps) {
        public Confidence {
            ownerId = LayeredVisualAnnotation.requireId(ownerId, "PREDICTED_CONFIDENCE_OWNER_INVALID");
            requireBasisPoints(confidenceBps, "PREDICTED_CONFIDENCE_INVALID");
        }
    }

    public enum Stage { ACQUISITION, HIERARCHY, ELEMENT_BINDING, CANDIDATE }

    public enum RecoveryCode { NONE, FIXED_RETRY, LEASE_RECOVERY }

    public record Runtime(
            int scriptedCalls,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long settledCostMicrosCny,
            Map<Stage, Long> latencyMicros,
            RecoveryCode recoveryCode,
            int recoveryCount,
            int acceptedStageReplayCount,
            int providerAttempts,
            int providerReservations,
            long externalProviderCostMicrosCny
    ) {
        public Runtime {
            if (scriptedCalls < 0 || scriptedCalls > 16 || inputTokens < 0 || outputTokens < 0
                    || estimatedCostMicrosCny < 0 || settledCostMicrosCny < 0
                    || recoveryCount < 0 || acceptedStageReplayCount < 0 || providerAttempts < 0
                    || providerReservations < 0 || externalProviderCostMicrosCny < 0) {
                throw LayeredVisualAnnotation.invalid("PREDICTED_RUNTIME_INVALID");
            }
            latencyMicros = Map.copyOf(Objects.requireNonNull(latencyMicros, "latencyMicros"));
            if (latencyMicros.size() > Stage.values().length
                    || latencyMicros.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getValue() == null || entry.getValue() < 0)) {
                throw LayeredVisualAnnotation.invalid("PREDICTED_LATENCY_INVALID");
            }
            Objects.requireNonNull(recoveryCode, "recoveryCode");
        }

        public static Runtime empty() {
            return new Runtime(0, 0, 0, 0, 0, Map.of(), RecoveryCode.NONE, 0, 0, 0, 0, 0);
        }
    }

    private static void requireBasisPoints(int value, String code) {
        if (value < 0 || value > 10_000) throw LayeredVisualAnnotation.invalid(code);
    }

    private static void requireUnique(List<String> values, String code) {
        if (new HashSet<>(values).size() != values.size()) throw LayeredVisualAnnotation.invalid(code);
    }
}
