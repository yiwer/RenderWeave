package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.run.InferenceStage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static cn.hbads.renderweave.inference.provider.ProviderBudgetReservation.REPRESENTATIONAL_MAXIMUM_ATTEMPT_ORDINAL;

/** Safe attempt metadata: no prompt, input value, model output, or chain-of-thought is retained. */
public record InferenceAttempt(
        UUID runId,
        int attemptOrdinal,
        InferenceStage stage,
        InferenceAttemptStatus status,
        String outcomeCode,
        Optional<String> providerRequestId,
        Optional<String> providerModel,
        long inputTokens,
        long outputTokens,
        long estimatedCostMicrosCny,
        long durationMillis,
        Map<String, Integer> problemCodeCounts,
        Optional<InferenceRejectionEnvelope> rejectionEnvelope,
        Instant completedAt
) {
    public InferenceAttempt {
        Objects.requireNonNull(runId, "runId");
        if (attemptOrdinal < 0 || attemptOrdinal > REPRESENTATIONAL_MAXIMUM_ATTEMPT_ORDINAL) {
            throw new IllegalArgumentException(
                    "attemptOrdinal must be 0.."
                            + REPRESENTATIONAL_MAXIMUM_ATTEMPT_ORDINAL);
        }
        Objects.requireNonNull(stage, "stage");
        if (stage != InferenceStage.OBSERVE && stage != InferenceStage.HIERARCHY
                && stage != InferenceStage.ELEMENT_BINDING
                && stage != InferenceStage.STRUCTURE && stage != InferenceStage.REPAIR) {
            throw new IllegalArgumentException("Attempt stage is not a provider call stage");
        }
        Objects.requireNonNull(status, "status");
        if (outcomeCode == null || !outcomeCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("outcomeCode must be a stable uppercase identifier");
        }
        providerRequestId = Objects.requireNonNull(providerRequestId, "providerRequestId");
        providerModel = Objects.requireNonNull(providerModel, "providerModel");
        if (providerRequestId.isPresent() != providerModel.isPresent()) {
            throw new IllegalArgumentException("Provider request id and model must be paired");
        }
        providerRequestId.ifPresent(value -> {
            if (!value.matches("[A-Za-z0-9._/-]{1,200}")) {
                throw new IllegalArgumentException("providerRequestId contains unsafe characters");
            }
        });
        providerModel.ifPresent(value -> {
            if (!value.matches("[A-Za-z0-9._/-]{1,128}")) {
                throw new IllegalArgumentException("providerModel contains unsafe characters");
            }
        });
        if (inputTokens < 0 || outputTokens < 0 || estimatedCostMicrosCny < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("Attempt telemetry must not be negative");
        }
        problemCodeCounts = InferenceAttemptProblemTaxonomy.normalize(problemCodeCounts);
        rejectionEnvelope = Objects.requireNonNull(rejectionEnvelope, "rejectionEnvelope");
        if (rejectionEnvelope.isPresent()) {
            var envelope = rejectionEnvelope.orElseThrow();
            if (status != InferenceAttemptStatus.REJECTED
                    || !"LIVE_VISUAL_ANALYSIS_REJECTED".equals(outcomeCode)
                    || stage.ordinal() < envelope.earliestStage().ordinal()
                    || !problemCodeCounts.equals(envelope.detailCodeCounts())) {
                throw new IllegalArgumentException(
                        "Attempt rejection envelope is inconsistent with attempt telemetry"
                );
            }
        }
        Objects.requireNonNull(completedAt, "completedAt");
    }

    public InferenceAttempt(
            UUID runId,
            int attemptOrdinal,
            InferenceStage stage,
            InferenceAttemptStatus status,
            String outcomeCode,
            Optional<String> providerRequestId,
            Optional<String> providerModel,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long durationMillis,
            Map<String, Integer> problemCodeCounts,
            Instant completedAt
    ) {
        this(
                runId, attemptOrdinal, stage, status, outcomeCode,
                providerRequestId, providerModel, inputTokens, outputTokens,
                estimatedCostMicrosCny, durationMillis, problemCodeCounts,
                Optional.empty(), completedAt
        );
    }

    public InferenceAttempt(
            UUID runId,
            int attemptOrdinal,
            InferenceStage stage,
            InferenceAttemptStatus status,
            String outcomeCode,
            Optional<String> providerRequestId,
            Optional<String> providerModel,
            long inputTokens,
            long outputTokens,
            long estimatedCostMicrosCny,
            long durationMillis,
            Instant completedAt
    ) {
        this(
                runId, attemptOrdinal, stage, status, outcomeCode,
                providerRequestId, providerModel, inputTokens, outputTokens,
                estimatedCostMicrosCny, durationMillis, Map.of(), Optional.empty(), completedAt
        );
    }

    public InferenceAttempt(
            UUID runId,
            int attemptOrdinal,
            InferenceStage stage,
            InferenceAttemptStatus status,
            String outcomeCode,
            Instant completedAt
    ) {
        this(
                runId, attemptOrdinal, stage, status, outcomeCode,
                Optional.empty(), Optional.empty(), 0, 0, 0, 0, Map.of(),
                Optional.empty(), completedAt
        );
    }
}
