package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidence;

import java.util.EnumSet;
import java.util.List;

/** Deterministic, payload-free provenance classifier for successor region fallbacks. */
final class VisualRegionFallbackClassifier {
    static final String MIXED_PRIMARY_CODE = "VISUAL_GROUNDING_REGION_FIELDS_INVALID";
    static final String UNCLASSIFIED_PRIMARY_CODE = "VISUAL_GROUNDING_REGION_UNCLASSIFIED";
    private static final List<String> CLOSED_DETAIL_CODES = List.of(
            FailureFamily.ENTRY.code,
            FailureFamily.ID.code,
            FailureFamily.PARENT_ID.code,
            FailureFamily.MULTIPLICITY.code,
            FailureFamily.READING_ORDER.code,
            FailureFamily.REPEAT_GROUP_ID.code,
            FailureFamily.EVIDENCE.code
    );

    private VisualRegionFallbackClassifier() { }

    static List<String> closedDetailCodes() {
        return CLOSED_DETAIL_CODES;
    }

    static Classification classify(
            List<RegionInput> regions,
            EvidenceValidator evidenceValidator
    ) {
        if (regions == null || evidenceValidator == null) return Classification.unclassified();
        var failures = EnumSet.noneOf(FailureFamily.class);
        try {
            for (var region : regions) {
                if (region == null) {
                    failures.add(FailureFamily.ENTRY);
                    continue;
                }
                if (!validLocalId(region.regionId(), "regionId")) {
                    failures.add(FailureFamily.ID);
                }
                if (region.parentRegionId() != null
                        && !validLocalId(region.parentRegionId(), "parentRegionId")) {
                    failures.add(FailureFamily.PARENT_ID);
                }
                if (region.multiplicity() == null) {
                    failures.add(FailureFamily.MULTIPLICITY);
                }
                if (region.readingOrder() < 0 || region.readingOrder() > 127) {
                    failures.add(FailureFamily.READING_ORDER);
                }
                if (region.repeatGroupId() != null
                        && !validLocalId(region.repeatGroupId(), "repeatGroupId")) {
                    failures.add(FailureFamily.REPEAT_GROUP_ID);
                }
                if (!validEvidence(region.evidence(), evidenceValidator)) {
                    failures.add(FailureFamily.EVIDENCE);
                }
            }
            return Classification.known(failures);
        } catch (RuntimeException unexpected) {
            return Classification.unclassified();
        }
    }

    private static boolean validLocalId(String value, String name) {
        try {
            VisualAnalysisValidation.localId(value, name);
            return true;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    private static boolean validEvidence(
            List<VisualViewEvidence> evidence,
            EvidenceValidator evidenceValidator
    ) {
        if (evidence == null || evidence.isEmpty()
                || evidence.size() > VisualAnalysisValidation.MAX_EVIDENCE_PER_ITEM
                || evidence.stream().anyMatch(value ->
                        value == null || value.viewId() == null || value.boundingBox() == null)) {
            return false;
        }
        try {
            var validated = evidenceValidator.validate(List.copyOf(evidence));
            if (validated == null || validated.stream().anyMatch(java.util.Objects::isNull)) {
                return false;
            }
            validated = VisualAnalysisValidation.imageEvidence(validated, "region evidence");
            return validated.size() == 1;
        } catch (IllegalArgumentException expected) {
            return false;
        }
    }

    enum Disposition {
        VALID,
        KNOWN_SINGLE,
        KNOWN_MIXED,
        UNCLASSIFIED
    }

    enum FailureFamily {
        ENTRY("VISUAL_GROUNDING_REGION_ENTRY_INVALID"),
        ID("VISUAL_GROUNDING_REGION_ID_INVALID"),
        PARENT_ID("VISUAL_GROUNDING_REGION_PARENT_ID_INVALID"),
        MULTIPLICITY("VISUAL_GROUNDING_REGION_MULTIPLICITY_INVALID"),
        READING_ORDER("VISUAL_GROUNDING_REGION_READING_ORDER_INVALID"),
        REPEAT_GROUP_ID("VISUAL_GROUNDING_REGION_REPEAT_GROUP_ID_INVALID"),
        EVIDENCE("VISUAL_GROUNDING_REGION_EVIDENCE_INVALID");

        private final String code;

        FailureFamily(String code) {
            this.code = code;
        }
    }

    record RegionInput(
            String regionId,
            String parentRegionId,
            VisualMultiplicity multiplicity,
            int readingOrder,
            String repeatGroupId,
            List<VisualViewEvidence> evidence
    ) { }

    record Classification(
            Disposition disposition,
            String primaryCode,
            List<String> detailCodes,
            int knownFieldFamilyCount
    ) {
        Classification {
            detailCodes = List.copyOf(detailCodes);
            switch (disposition) {
                case VALID -> {
                    if (primaryCode != null || !detailCodes.isEmpty()
                            || knownFieldFamilyCount != 0) {
                        throw new IllegalArgumentException("Valid provenance is not empty");
                    }
                }
                case KNOWN_SINGLE -> {
                    if (!CLOSED_DETAIL_CODES.contains(primaryCode) || !detailCodes.isEmpty()
                            || knownFieldFamilyCount != 1) {
                        throw new IllegalArgumentException("Single provenance is invalid");
                    }
                }
                case KNOWN_MIXED -> {
                    if (!MIXED_PRIMARY_CODE.equals(primaryCode)
                            || detailCodes.size() < 2
                            || detailCodes.size() > CLOSED_DETAIL_CODES.size()
                            || knownFieldFamilyCount != detailCodes.size()
                            || !detailCodes.equals(CLOSED_DETAIL_CODES.stream()
                            .filter(detailCodes::contains).toList())) {
                        throw new IllegalArgumentException("Mixed provenance is invalid");
                    }
                }
                case UNCLASSIFIED -> {
                    if (!UNCLASSIFIED_PRIMARY_CODE.equals(primaryCode)
                            || !detailCodes.isEmpty() || knownFieldFamilyCount != 0) {
                        throw new IllegalArgumentException("Unclassified provenance is invalid");
                    }
                }
            }
        }

        private static Classification known(EnumSet<FailureFamily> failures) {
            if (failures.isEmpty()) {
                return new Classification(Disposition.VALID, null, List.of(), 0);
            }
            if (failures.size() == 1) {
                return new Classification(
                        Disposition.KNOWN_SINGLE, failures.iterator().next().code, List.of(), 1
                );
            }
            var details = failures.stream().map(value -> value.code).toList();
            return new Classification(
                    Disposition.KNOWN_MIXED, MIXED_PRIMARY_CODE, details, details.size()
            );
        }

        private static Classification unclassified() {
            return new Classification(
                    Disposition.UNCLASSIFIED, UNCLASSIFIED_PRIMARY_CODE, List.of(), 0
            );
        }

        boolean valid() {
            return disposition == Disposition.VALID;
        }

    }

    @FunctionalInterface
    interface EvidenceValidator {
        List<CandidateEvidence> validate(List<VisualViewEvidence> evidence);
    }
}
