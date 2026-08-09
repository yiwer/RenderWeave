package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;

import java.util.List;
import java.util.Set;

/** Blockers the model may deterministically rebuild; semantic uncertainty remains for the user. */
final class LiveRepairPolicy {
    private static final Set<String> REPAIRABLE_BLOCKERS = Set.of(
            "CANDIDATE_VERSION_UNSUPPORTED",
            "CANDIDATE_SCHEMA_MISSING",
            "CANDIDATE_SCHEMA_ID_DUPLICATE",
            "CANDIDATE_ITEM_ID_DUPLICATE",
            "CANDIDATE_ROOT_REMOVED",
            "CANDIDATE_ROOT_NOT_FOUND",
            "CANDIDATE_SCHEMA_KEY_UNRESOLVED",
            "CANDIDATE_SCHEMA_KEY_INVALID",
            "CANDIDATE_SCHEMA_KEY_DUPLICATE",
            "CANDIDATE_DISPLAY_NAME_MISSING",
            "CANDIDATE_FIELD_KEY_DUPLICATE",
            "INFERENCE_SOURCE_INVALID",
            "INFERENCE_PROVENANCE_INVALID",
            "INFERENCE_RESOLUTION_INVALID",
            "AI_CONFIDENCE_INVALID",
            "AI_EVIDENCE_MISSING",
            "IMAGE_EVIDENCE_SHAPE_INVALID",
            "IMAGE_EVIDENCE_ARTIFACT_UNKNOWN",
            "IMAGE_EVIDENCE_BOUNDS_INVALID",
            "JSON_EVIDENCE_SHAPE_INVALID",
            "JSON_EVIDENCE_SAMPLE_UNKNOWN",
            "JSON_EVIDENCE_POINTER_INVALID",
            "JSON_EVIDENCE_LOCATION_UNKNOWN",
            "JSON_EVIDENCE_ITEM_MISMATCH",
            "JSON_EVIDENCE_ITEM_MISSING",
            "EVIDENCE_KIND_INVALID",
            "CANDIDATE_ARRAY_SHAPE_INVALID",
            "NESTED_ARRAY_UNSUPPORTED",
            "CANDIDATE_REFERENCE_SHAPE_INVALID",
            "CANDIDATE_REFERENCE_KIND_INVALID",
            "CANDIDATE_REFERENCE_TARGET_MISSING",
            "CANDIDATE_SCHEMA_ORPHAN",
            "CANDIDATE_REFERENCE_CYCLE",
            "UNRESOLVED_TYPE_EVIDENCE_MISSING",
            "CONFLICT_TYPE_EVIDENCE_INCOMPLETE",
            "CANDIDATE_SCALAR_SHAPE_INVALID",
            "AI_REQUIRED_UNCONFIRMED",
            "AI_CONSTRAINT_UNCONFIRMED"
    );

    private static final Set<String> HUMAN_REVIEW_BLOCKERS = Set.of(
            "CANDIDATE_FIELD_KEY_UNRESOLVED",
            "CANDIDATE_FIELD_KEY_INVALID",
            "CANDIDATE_ITEM_UNRESOLVED",
            "LOW_CONFIDENCE_STATE_INVALID",
            "LOW_CONFIDENCE_UNRESOLVED",
            "CANDIDATE_TYPE_UNRESOLVED",
            "CANDIDATE_TYPE_CONFLICT"
    );

    private LiveRepairPolicy() { }

    static Decision decide(List<CandidateProblem> problems) {
        var blockers = problems.stream()
                .filter(problem -> problem.severity() == CandidateProblemSeverity.BLOCKER)
                .toList();
        if (blockers.isEmpty()) return Decision.REVIEW;
        if (blockers.stream().allMatch(problem -> REPAIRABLE_BLOCKERS.contains(problem.code()))) {
            return Decision.REPAIR;
        }
        if (blockers.stream().allMatch(problem -> HUMAN_REVIEW_BLOCKERS.contains(problem.code()))) {
            return Decision.REVIEW;
        }
        return Decision.REJECT;
    }

    enum Decision {
        REPAIR,
        REVIEW,
        REJECT
    }
}
