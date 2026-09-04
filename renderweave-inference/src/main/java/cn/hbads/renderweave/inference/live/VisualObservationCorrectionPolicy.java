package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.replay.InferenceRejectionEnvelope;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Successor-only allowlist for bounded OBSERVE correction calls. */
final class VisualObservationCorrectionPolicy {
    static final String PIPELINE_VERSION = "renderweave-inference-pipeline/4.30";
    static final String MIXED_ENVELOPE_PIPELINE_VERSION =
            "renderweave-inference-pipeline/4.31";
    static final String LOCAL_ID_CANONICALIZATION_PIPELINE_VERSION =
            "renderweave-inference-pipeline/4.32";
    static final String PARENT_CONTAINMENT_PROVENANCE_PIPELINE_VERSION =
            "renderweave-inference-pipeline/4.33";
    static final String ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION =
            "renderweave-inference-pipeline/4.34";

    private static final Set<String> RETRYABLE_OBSERVE_CODES = Set.of(
            "VISUAL_GROUNDING_OUTPUT_TRUNCATED",
            "VISUAL_GROUNDING_JSON_SHAPE_INVALID_REGION_READING_ORDER",
            "VISUAL_GROUNDING_READING_ORDER_GAP",
            "VISUAL_GROUNDING_READING_ORDER_DUPLICATE",
            "VISUAL_GROUNDING_READING_ORDER_POSITION_INVALID",
            "VISUAL_GROUNDING_SIBLING_OVERLAP",
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID",
            "VISUAL_GROUNDING_REGION_FOREST_INVALID",
            "VISUAL_GROUNDING_PARENT_KIND_INVALID",
            "VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_KIND",
            "VISUAL_GROUNDING_JSON_ENUM_INVALID_REGION_MULTIPLICITY",
            "VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_MULTIPLICITY",
            "VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_KIND",
            "VISUAL_GROUNDING_JSON_ENUM_INVALID_ELEMENT_VALUE_HINT",
            "VISUAL_GROUNDING_ELEMENT_INVALID",
            "VISUAL_GROUNDING_ELEMENT_REGION_UNKNOWN",
            "VISUAL_GROUNDING_ELEMENT_EVIDENCE_OUTSIDE_REGION",
            "VISUAL_GROUNDING_REPEAT_CHILD_INVALID",
            "VISUAL_GROUNDING_REPEAT_ITEM_INVALID",
            "VISUAL_GROUNDING_NON_REPEATED_CARDINALITY_INVALID",
            "VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING",
            "VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID",
            "VISUAL_SEMANTIC_REPEATED_ITEM_FIELD_MISSING",
            "VISUAL_SEMANTIC_GROUP_REGION_INVALID",
            "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_GROUP_MISSING",
            "VISUAL_SEMANTIC_OBSERVE_RELATIONSHIP_REGION_GROUP_MISSING",
            "VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING",
            "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
            "VISUAL_GROUNDING_REGION_ID_INVALID",
            "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
            "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
            "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
            "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
            "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID"
    );
    private static final Set<String> MIXED_RETRYABLE_DETAIL_CODES = Set.of(
            "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
            "VISUAL_GROUNDING_REGION_ID_INVALID",
            "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
            "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
            "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
            "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
            "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID"
    );

    private VisualObservationCorrectionPolicy() { }

    static boolean permitsRetry(
            String pipelineVersion,
            InferenceStage earliestStage,
            String diagnosticCode
    ) {
        return permitsRetry(
                pipelineVersion, earliestStage, diagnosticCode, Optional.empty()
        );
    }

    static boolean permitsRetry(
            String pipelineVersion,
            InferenceStage earliestStage,
            String diagnosticCode,
            Optional<InferenceRejectionEnvelope> rejectionEnvelope
    ) {
        if (parentContainmentDiagnostics(pipelineVersion)
                && InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE.equals(
                diagnosticCode)) {
            return false;
        }
        if (mixedRegionDiagnostics(pipelineVersion)
                && InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE.equals(
                diagnosticCode)) {
            return earliestStage == InferenceStage.OBSERVE
                    && rejectionEnvelope.filter(envelope ->
                    diagnosticCode.equals(envelope.primaryCode())
                            && mixedDetailsPromptCovered(envelope.detailCodes())
            ).isPresent();
        }
        if (mixedRegionDiagnostics(pipelineVersion)
                && InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE.equals(
                diagnosticCode)) {
            return false;
        }
        if (!(PIPELINE_VERSION.equals(pipelineVersion)
                || mixedRegionDiagnostics(pipelineVersion))
                || earliestStage != InferenceStage.OBSERVE) {
            return true;
        }
        return RETRYABLE_OBSERVE_CODES.contains(diagnosticCode);
    }

    static boolean mixedDetailsPromptCovered(List<String> detailCodes) {
        if (detailCodes == null || detailCodes.size() < 2
                || detailCodes.size() > InferenceRejectionEnvelope.REGION_DETAIL_CODES.size()
                || !MIXED_RETRYABLE_DETAIL_CODES.containsAll(detailCodes)) {
            return false;
        }
        return detailCodes.equals(InferenceRejectionEnvelope.REGION_DETAIL_CODES.stream()
                .filter(detailCodes::contains)
                .toList());
    }

    static boolean fieldSpecificRegionDiagnostics(String pipelineVersion) {
        return PIPELINE_VERSION.equals(pipelineVersion);
    }

    static boolean mixedRegionDiagnostics(String pipelineVersion) {
        return MIXED_ENVELOPE_PIPELINE_VERSION.equals(pipelineVersion)
                || LOCAL_ID_CANONICALIZATION_PIPELINE_VERSION.equals(pipelineVersion)
                || PARENT_CONTAINMENT_PROVENANCE_PIPELINE_VERSION.equals(pipelineVersion)
                || ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION.equals(pipelineVersion);
    }

    static boolean losslessLocalIdCanonicalization(String pipelineVersion) {
        return LOCAL_ID_CANONICALIZATION_PIPELINE_VERSION.equals(pipelineVersion)
                || PARENT_CONTAINMENT_PROVENANCE_PIPELINE_VERSION.equals(pipelineVersion)
                || ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION.equals(pipelineVersion);
    }

    static boolean parentContainmentDiagnostics(String pipelineVersion) {
        return PARENT_CONTAINMENT_PROVENANCE_PIPELINE_VERSION.equals(pipelineVersion)
                || ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION.equals(pipelineVersion);
    }

    static boolean itemParentEnvelopeNormalization(String pipelineVersion) {
        return ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION.equals(pipelineVersion);
    }
}
