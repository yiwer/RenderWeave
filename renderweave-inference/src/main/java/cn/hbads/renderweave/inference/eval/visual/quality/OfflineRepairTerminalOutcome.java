package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Content-addressable proof that a conditional offline ticket performed no forbidden work. */
public record OfflineRepairTerminalOutcome(
        String contractVersion,
        Ticket ticket,
        String rootDecisionIdentity,
        R2R5TriggerDecision.OverallDisposition rootDisposition,
        List<String> supportingIdentities,
        Disposition disposition,
        String reasonCode,
        OfflineWorkUsage offlineWorkUsage,
        FrozenQualityEvidencePack.ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "OfflineRepairTerminalOutcome/1.0";
    public static final String IDENTITY_PREFIX =
            "renderweave-offline-repair-terminal-outcome/1.0:";
    private static final String DECISION_IDENTITY_PATTERN =
            "renderweave-r2r5-trigger-decision/1\\.0:[0-9a-f]{64}";
    private static final String CATALOG_IDENTITY_PATTERN =
            "renderweave-challenger-capability-catalog/1\\.0:[0-9a-f]{64}";
    private static final String CAPABILITY_IDENTITY_PATTERN =
            "renderweave-challenger-capability/1\\.0:[0-9a-f]{64}";
    private static final String OUTCOME_IDENTITY_PATTERN =
            "renderweave-offline-repair-terminal-outcome/1\\.0:[0-9a-f]{64}";

    public OfflineRepairTerminalOutcome {
        if (!VERSION.equals(contractVersion)) {
            throw invalid("OFFLINE_TERMINAL_OUTCOME_VERSION_INVALID");
        }
        Objects.requireNonNull(ticket, "ticket");
        if (rootDecisionIdentity == null || !rootDecisionIdentity.matches(DECISION_IDENTITY_PATTERN)) {
            throw invalid("OFFLINE_TERMINAL_ROOT_DECISION_IDENTITY_INVALID");
        }
        if (rootDisposition != R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5) {
            throw invalid("OFFLINE_TERMINAL_ROOT_DISPOSITION_INVALID");
        }
        var identities = new ArrayList<>(List.copyOf(
                Objects.requireNonNull(supportingIdentities, "supportingIdentities")));
        identities.sort(String::compareTo);
        if (new HashSet<>(identities).size() != identities.size()
                || !validSupportingIdentities(ticket, identities)) {
            throw invalid("OFFLINE_TERMINAL_SUPPORTING_IDENTITIES_INVALID");
        }
        supportingIdentities = List.copyOf(identities);
        if (disposition != expectedDisposition(ticket)
                || !expectedReasonCode(ticket).equals(reasonCode)) {
            throw invalid("OFFLINE_TERMINAL_RESULT_INCONSISTENT");
        }
        offlineWorkUsage = Objects.requireNonNull(offlineWorkUsage, "offlineWorkUsage");
        if (!offlineWorkUsage.zeroWork()) {
            throw invalid("OFFLINE_TERMINAL_WORK_USAGE_NONZERO");
        }
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) {
            throw invalid("OFFLINE_TERMINAL_PROVIDER_USAGE_NONZERO");
        }
    }

    static Disposition expectedDisposition(Ticket ticket) {
        return switch (ticket) {
            case VRQ_08_PP_STRUCTUREV3_DEV_SHADOW, VRQ_09_TESSERACT_DEV_BASELINE ->
                    Disposition.STOPPED_FOR_R5_SUCCESSOR_SPEC;
            case VRQ_10_SOLE_DEV_WINNER_SELECTION, VRQ_11_WINNER_HOLDOUT,
                    VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY, VRQ_13_INDEPENDENT_A2_ADMISSION ->
                    Disposition.BLOCKED_BY_PREDECESSOR;
            case VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY ->
                    Disposition.LIVE_J1_REQUEST_NOT_ELIGIBLE;
        };
    }

    static String expectedReasonCode(Ticket ticket) {
        return switch (ticket) {
            case VRQ_08_PP_STRUCTUREV3_DEV_SHADOW, VRQ_09_TESSERACT_DEV_BASELINE ->
                    "R5_TRIGGERED_REQUIRES_SUCCESSOR_SPEC";
            case VRQ_10_SOLE_DEV_WINNER_SELECTION -> "R2_DEV_REPORTS_UNAVAILABLE";
            case VRQ_11_WINNER_HOLDOUT -> "R2_SOLE_WINNER_UNAVAILABLE";
            case VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY -> "R2_QUALIFIED_REPAIR_UNAVAILABLE";
            case VRQ_13_INDEPENDENT_A2_ADMISSION -> "IMAGE_ONLY_REPLAY_UNAVAILABLE";
            case VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY ->
                    "INDEPENDENT_OFFLINE_ADMISSION_UNAVAILABLE";
        };
    }

    private static boolean validSupportingIdentities(Ticket ticket, List<String> identities) {
        return switch (ticket) {
            case VRQ_08_PP_STRUCTUREV3_DEV_SHADOW, VRQ_09_TESSERACT_DEV_BASELINE ->
                    identities.size() == 2
                            && identities.stream().filter(value ->
                            value.matches(CATALOG_IDENTITY_PATTERN)).count() == 1
                            && identities.stream().filter(value ->
                            value.matches(CAPABILITY_IDENTITY_PATTERN)).count() == 1;
            case VRQ_10_SOLE_DEV_WINNER_SELECTION ->
                    identities.size() == 2
                            && identities.stream().allMatch(value ->
                            value.matches(OUTCOME_IDENTITY_PATTERN));
            case VRQ_11_WINNER_HOLDOUT, VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY,
                    VRQ_13_INDEPENDENT_A2_ADMISSION,
                    VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY ->
                    identities.size() == 1 && identities.getFirst().matches(OUTCOME_IDENTITY_PATTERN);
        };
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum Ticket {
        VRQ_08_PP_STRUCTUREV3_DEV_SHADOW,
        VRQ_09_TESSERACT_DEV_BASELINE,
        VRQ_10_SOLE_DEV_WINNER_SELECTION,
        VRQ_11_WINNER_HOLDOUT,
        VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY,
        VRQ_13_INDEPENDENT_A2_ADMISSION,
        VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY
    }

    public enum Disposition {
        STOPPED_FOR_R5_SUCCESSOR_SPEC,
        BLOCKED_BY_PREDECESSOR,
        LIVE_J1_REQUEST_NOT_ELIGIBLE
    }

    public record OfflineWorkUsage(
            long artifactAcquisitions,
            long devCasesExecuted,
            long holdoutCasesAccessed,
            long scriptedWorkflowReplays,
            long independentAdmissionReplays,
            long productWrites,
            long apiKeyReads
    ) {
        public OfflineWorkUsage {
            if (artifactAcquisitions < 0 || devCasesExecuted < 0 || holdoutCasesAccessed < 0
                    || scriptedWorkflowReplays < 0 || independentAdmissionReplays < 0
                    || productWrites < 0 || apiKeyReads < 0) {
                throw invalid("OFFLINE_TERMINAL_WORK_USAGE_INVALID");
            }
        }

        public static OfflineWorkUsage zero() {
            return new OfflineWorkUsage(0, 0, 0, 0, 0, 0, 0);
        }

        public boolean zeroWork() {
            return artifactAcquisitions == 0 && devCasesExecuted == 0 && holdoutCasesAccessed == 0
                    && scriptedWorkflowReplays == 0 && independentAdmissionReplays == 0
                    && productWrites == 0 && apiKeyReads == 0;
        }
    }
}
