package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.replay.InferenceRejectionEnvelope;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualObservationCorrectionPolicyTest {
    @Test
    void v48RetriesOnlyPromptCoveredObserveCodes() {
        assertTrue(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.PIPELINE_VERSION, InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_OUTPUT_TRUNCATED"
        ));
        assertTrue(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.PIPELINE_VERSION, InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID"
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.PIPELINE_VERSION, InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_REGION_INVALID"
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.PIPELINE_VERSION, InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_VERSION_INVALID"
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.PIPELINE_VERSION, InferenceStage.OBSERVE,
                "UNKNOWN_PROVIDER_CODE"
        ));
    }

    @Test
    void historicalAndNonObservePathsKeepTheirExistingRetrySemantics() {
        assertTrue(VisualObservationCorrectionPolicy.permitsRetry(
                "renderweave-inference-pipeline/4.29", InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_REGION_INVALID"
        ));
        assertTrue(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.PIPELINE_VERSION, InferenceStage.HIERARCHY,
                "VISUAL_HIERARCHY_V2_TOPOLOGY_INVALID"
        ));
    }

    @Test
    void v49RetriesOnlyAnExactPromptCoveredMixedEnvelope() {
        var mixed = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(
                        "VISUAL_GROUNDING_REGION_ID_INVALID",
                        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                )
        );
        var unclassified = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.UNCLASSIFIED_REGION_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of()
        );

        assertTrue(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.MIXED_ENVELOPE_PIPELINE_VERSION,
                InferenceStage.OBSERVE, mixed.primaryCode(), Optional.of(mixed)
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.MIXED_ENVELOPE_PIPELINE_VERSION,
                InferenceStage.OBSERVE, mixed.primaryCode(), Optional.empty()
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                VisualObservationCorrectionPolicy.MIXED_ENVELOPE_PIPELINE_VERSION,
                InferenceStage.OBSERVE, unclassified.primaryCode(), Optional.of(unclassified)
        ));
        assertTrue(VisualObservationCorrectionPolicy.mixedDetailsPromptCovered(
                mixed.detailCodes()
        ));
        assertFalse(VisualObservationCorrectionPolicy.mixedDetailsPromptCovered(List.of(
                "VISUAL_GROUNDING_REGION_ID_INVALID", "UNKNOWN_PROVIDER_CODE"
        )));
    }

    @Test
    void v50KeepsTheV49MixedEnvelopeBoundaryButDoesNotRetryCanonicalizationFailure() {
        var mixed = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.MIXED_REGION_FIELDS_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(
                        "VISUAL_GROUNDING_REGION_ID_INVALID",
                        "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"
                )
        );
        var pipeline = VisualObservationCorrectionPolicy
                .LOCAL_ID_CANONICALIZATION_PIPELINE_VERSION;

        assertTrue(VisualObservationCorrectionPolicy.losslessLocalIdCanonicalization(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.mixedRegionDiagnostics(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.permitsRetry(
                pipeline, InferenceStage.OBSERVE, mixed.primaryCode(), Optional.of(mixed)
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                pipeline, InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_LOCAL_ID_CANONICALIZATION_INVALID"
        ));
        assertFalse(VisualObservationCorrectionPolicy.losslessLocalIdCanonicalization(
                VisualObservationCorrectionPolicy.MIXED_ENVELOPE_PIPELINE_VERSION
        ));
    }

    @Test
    void v51KeepsV50BoundariesAndMakesClassifiedContainmentTerminal() {
        var pipeline = VisualObservationCorrectionPolicy
                .PARENT_CONTAINMENT_PROVENANCE_PIPELINE_VERSION;
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(InferenceRejectionEnvelope.PARENT_CONTAINMENT_DETAIL_CODES.getFirst())
        );

        assertTrue(VisualObservationCorrectionPolicy.parentContainmentDiagnostics(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.mixedRegionDiagnostics(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.losslessLocalIdCanonicalization(pipeline));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                pipeline, InferenceStage.OBSERVE, envelope.primaryCode(), Optional.of(envelope)
        ));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                pipeline, InferenceStage.OBSERVE,
                "VISUAL_GROUNDING_LOCAL_ID_CANONICALIZATION_INVALID"
        ));
        assertFalse(VisualObservationCorrectionPolicy.parentContainmentDiagnostics(
                VisualObservationCorrectionPolicy.LOCAL_ID_CANONICALIZATION_PIPELINE_VERSION
        ));
    }

    @Test
    void v52AddsOnlyTheEnvelopeNormalizationAndKeepsClassifiedFailuresTerminal() {
        var pipeline = VisualObservationCorrectionPolicy
                .ITEM_PARENT_ENVELOPE_NORMALIZATION_PIPELINE_VERSION;
        var envelope = new InferenceRejectionEnvelope(
                InferenceRejectionEnvelope.PARENT_CONTAINMENT_PRIMARY_CODE,
                InferenceStage.OBSERVE,
                List.of(InferenceRejectionEnvelope.PARENT_CONTAINMENT_DETAIL_CODES.getFirst())
        );

        assertTrue(VisualObservationCorrectionPolicy.itemParentEnvelopeNormalization(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.parentContainmentDiagnostics(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.mixedRegionDiagnostics(pipeline));
        assertTrue(VisualObservationCorrectionPolicy.losslessLocalIdCanonicalization(pipeline));
        assertFalse(VisualObservationCorrectionPolicy.permitsRetry(
                pipeline, InferenceStage.OBSERVE, envelope.primaryCode(), Optional.of(envelope)
        ));
        assertFalse(VisualObservationCorrectionPolicy.itemParentEnvelopeNormalization(
                VisualObservationCorrectionPolicy.PARENT_CONTAINMENT_PROVENANCE_PIPELINE_VERSION
        ));
    }
}
