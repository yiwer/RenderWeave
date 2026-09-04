package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Closed, payload-free rejection provenance persisted with one provider attempt. */
public record InferenceRejectionEnvelope(
        String primaryCode,
        InferenceStage earliestStage,
        List<String> detailCodes
) {
    public static final String MIXED_REGION_FIELDS_PRIMARY_CODE =
            "VISUAL_GROUNDING_REGION_FIELDS_INVALID";
    public static final String UNCLASSIFIED_REGION_PRIMARY_CODE =
            "VISUAL_GROUNDING_REGION_UNCLASSIFIED";
    public static final String PARENT_CONTAINMENT_PRIMARY_CODE =
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED";
    public static final List<String> REGION_DETAIL_CODES = List.of(
            "VISUAL_GROUNDING_REGION_ENTRY_INVALID",
            "VISUAL_GROUNDING_REGION_ID_INVALID",
            "VISUAL_GROUNDING_REGION_PARENT_ID_INVALID",
            "VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID",
            "VISUAL_GROUNDING_REGION_READING_ORDER_INVALID",
            "VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID",
            "VISUAL_GROUNDING_REGION_EVIDENCE_INVALID"
    );
    public static final List<String> PARENT_CONTAINMENT_DETAIL_CODES = List.of(
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE",
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_AMBIGUOUS_COMPATIBLE",
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_ZERO_COMPATIBLE",
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_NON_ITEM_AMBIGUOUS_COMPATIBLE",
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_ATOMIC_ROLLBACK",
            "VISUAL_GROUNDING_PARENT_CONTAINMENT_UNCLASSIFIED"
    );

    public InferenceRejectionEnvelope {
        Objects.requireNonNull(primaryCode, "primaryCode");
        Objects.requireNonNull(earliestStage, "earliestStage");
        detailCodes = List.copyOf(Objects.requireNonNull(detailCodes, "detailCodes"));
        if (earliestStage != InferenceStage.OBSERVE) {
            throw new IllegalArgumentException("Region rejection earliestStage must be OBSERVE");
        }
        if (MIXED_REGION_FIELDS_PRIMARY_CODE.equals(primaryCode)) {
            if (detailCodes.size() < 2 || detailCodes.size() > REGION_DETAIL_CODES.size()
                    || !detailCodes.equals(REGION_DETAIL_CODES.stream()
                    .filter(detailCodes::contains).toList())) {
                throw new IllegalArgumentException(
                        "Mixed region rejection details must be canonical closed codes"
                );
            }
        } else if (UNCLASSIFIED_REGION_PRIMARY_CODE.equals(primaryCode)) {
            if (!detailCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unclassified region rejection must not carry details"
                );
            }
        } else if (PARENT_CONTAINMENT_PRIMARY_CODE.equals(primaryCode)) {
            var atomicRollback = PARENT_CONTAINMENT_DETAIL_CODES.get(4);
            var unclassified = PARENT_CONTAINMENT_DETAIL_CODES.get(5);
            if (detailCodes.isEmpty()
                    || !detailCodes.equals(PARENT_CONTAINMENT_DETAIL_CODES.stream()
                    .filter(detailCodes::contains).toList())
                    || (detailCodes.contains(atomicRollback) && detailCodes.size() != 1)
                    || (detailCodes.contains(unclassified) && detailCodes.size() != 1)) {
                throw new IllegalArgumentException(
                        "Parent-containment rejection details must be canonical closed codes"
                );
            }
        } else {
            throw new IllegalArgumentException("Rejection primaryCode is not approved");
        }
    }

    public int detailCodeCount() {
        return detailCodes.size();
    }

    public Map<String, Integer> detailCodeCounts() {
        return InferenceAttemptProblemTaxonomy.count(detailCodes);
    }
}
