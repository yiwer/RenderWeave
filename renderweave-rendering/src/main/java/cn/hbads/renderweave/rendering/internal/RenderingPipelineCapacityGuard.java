package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.CapabilityDerivation;
import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;

import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Frozen {@code renderweave-rendering-pipeline-capacity-guard/1.0} seam.
 *
 * <p>Authoritative counters derive an observed value at their frozen reservation point and pass
 * it through this single catalog. The isolated conformance fixture uses the same seam without
 * pretending that the full Evaluator or its counter source ran.</p>
 */
final class RenderingPipelineCapacityGuard {

    enum Comparison {
        MAX_INCLUSIVE,
        EXACT
    }

    enum Limit {
        ACTUAL_TEMPLATE_INVOCATIONS(
                "closureAndExpansion.actualTemplateInvocations", 256),
        INVOCATION_DEPTH(
                "closureAndExpansion.invocationDepth", 16),
        COMPOSITION_VIEWPORTS(
                "closureAndExpansion.compositionViewports", 256),
        REPEAT_COLLECTION_ITEMS_PER_OCCURRENCE(
                "closureAndExpansion.repeatCollectionItemsPerOccurrence", 1_000),
        REPEAT_NESTING_DEPTH(
                "closureAndExpansion.repeatNestingDepth", 8),
        LOOP_FRAMES_TOTAL(
                "closureAndExpansion.loopFramesTotal", 10_000),
        RENDER_OCCURRENCES(
                "closureAndExpansion.renderOccurrences", 25_000),
        MATERIALIZED_STATIC_NODES(
                "closureAndExpansion.materializedStaticNodes", 20_000),
        GENERATED_TRACK_AND_CELL_ENTRIES(
                "closureAndExpansion.generatedTrackAndCellEntries", 100_000),
        LOGICAL_OPERATIONS(
                "closureAndExpansion.logicalOperations", 1_000_000),
        RENDER_DOCUMENT_CANONICAL_BYTES(
                "renderDocument.canonicalBytes",
                67_108_864,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_JSON_DEPTH(
                "renderDocument.jsonDepth",
                128,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_STATIC_NODES(
                "renderDocument.staticNodes",
                20_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_CHILD_EDGES(
                "renderDocument.childEdges",
                19_999,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_RUNS(
                "renderDocument.runs",
                10_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_TEXT_SCALARS(
                "renderDocument.textScalars",
                1_000_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        RENDER_DOCUMENT_VECTOR_ENTRIES(
                "renderDocument.vectorEntries",
                100_000,
                ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        DIAGNOSTICS_SIDECAR_ITEMS(
                "diagnostics.sidecarItems",
                25_000,
                ProblemCode.RENDER_DIAGNOSTIC_LIMIT_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        DIAGNOSTICS_SIDECAR_BYTES(
                "diagnostics.sidecarBytes",
                8_388_608,
                ProblemCode.RENDER_DIAGNOSTIC_LIMIT_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        ASSETS_AND_FETCH_AUTHORED_ASSET_OCCURRENCES(
                "assetsAndFetch.authoredAssetOccurrences",
                4_096,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_LOGICAL_ASSETS(
                "assetsAndFetch.uniqueLogicalAssets",
                512,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_ACTUAL_RESOLVE_OCCURRENCES(
                "assetsAndFetch.actualResolveOccurrences",
                2_048,
                ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_RESOLUTION),
        ASSETS_AND_FETCH_RENDER_RESOURCE_ENTRIES(
                "assetsAndFetch.renderResourceEntries",
                2_048,
                ProblemCode.RESOURCE_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_RESOLUTION),
        ASSETS_AND_FETCH_UNIQUE_EXACT_CONTENTS(
                "assetsAndFetch.uniqueExactContents",
                128,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_OCCURRENCE_DECLARED_RAW_BYTES(
                "assetsAndFetch.occurrenceDeclaredRawBytes",
                2_147_483_648L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_RAW_BYTES(
                "assetsAndFetch.uniqueRawBytes",
                268_435_456L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_OCCURRENCE_IMAGE_PIXELS(
                "assetsAndFetch.occurrenceImagePixels",
                1_000_000_000L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_IMAGE_PIXELS(
                "assetsAndFetch.uniqueImagePixels",
                125_000_000L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_OCCURRENCE_FONT_BYTES(
                "assetsAndFetch.occurrenceFontBytes",
                536_870_912L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_UNIQUE_FONT_BYTES(
                "assetsAndFetch.uniqueFontBytes",
                67_108_864L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        ASSETS_AND_FETCH_MANIFEST_BYTES(
                "assetsAndFetch.manifestBytes",
                4_194_304L,
                ProblemCode.ASSET_BUDGET_EXCEEDED,
                EvaluationStage.ASSET_ADMISSION),
        CAPABILITY_RUNTIME_STATIC_CAPABILITY_SOURCES(
                "capabilityRuntime.staticCapabilitySources",
                4_096L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        CAPABILITY_RUNTIME_TOTAL_DEMANDS(
                "capabilityRuntime.totalDemands",
                8_192L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        CAPABILITY_RUNTIME_CLOCK_DEMANDS(
                "capabilityRuntime.clockDemands",
                4_096L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        CAPABILITY_RUNTIME_RANDOM_DEMANDS(
                "capabilityRuntime.randomDemands",
                4_096L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_PER_DEMAND(
                "capabilityRuntime.positionCanonicalBytesPerDemand",
                2_048L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        CAPABILITY_RUNTIME_POSITION_CANONICAL_BYTES_TOTAL(
                "capabilityRuntime.positionCanonicalBytesTotal",
                16_777_216L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        CAPABILITY_RUNTIME_CAPABILITY_STATE_RECORD_BYTES(
                "capabilityRuntime.capabilityStateRecordBytes",
                1_048_576L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.CAPABILITY_STATE),
        CAPABILITY_RUNTIME_RESULT_DIGEST_STREAMING_BYTES(
                "capabilityRuntime.resultDigestStreamingBytes",
                16_777_216L,
                ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                EvaluationStage.MATERIALIZATION),
        CAPABILITY_RUNTIME_INITIALIZATION_ATTEMPTS(
                "capabilityRuntime.initializationAttempts",
                3L,
                Comparison.EXACT,
                ProblemCode.CAPABILITY_STATE_UNAVAILABLE,
                EvaluationStage.CAPABILITY_STATE),
        CAPABILITY_RUNTIME_RANDOM_REJECTION_ATTEMPTS(
                "capabilityRuntime.randomRejectionAttempts",
                CapabilityDerivation.MAX_REJECTION_ATTEMPTS,
                Comparison.EXACT,
                ProblemCode.CAPABILITY_RESULT_INVALID,
                EvaluationStage.MATERIALIZATION),
        DEADLINE_AND_RETENTION_ADMISSION_AND_CLOSURE_MILLIS(
                "deadlineAndRetention.admissionAndClosureMillis",
                5_000L,
                Comparison.EXACT,
                ProblemCode.RENDER_DEADLINE_EXCEEDED,
                EvaluationStage.TEMPLATE_CLOSURE),
        DEADLINE_AND_RETENTION_EVALUATION_AND_DOCUMENT_SEAL_MILLIS(
                "deadlineAndRetention.evaluationAndDocumentSealMillis",
                15_000L,
                Comparison.EXACT,
                ProblemCode.RENDER_DEADLINE_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL),
        DEADLINE_AND_RETENTION_TERMINAL_REGISTRY_AND_OUTPUT_RETENTION_MILLIS(
                "deadlineAndRetention.terminalRegistryAndOutputRetentionMillis",
                300_000L,
                Comparison.EXACT,
                EvaluationStage.ENGINE),
        DEADLINE_AND_RETENTION_PRE_COMMAND_CANCEL_TOMBSTONE_MILLIS(
                "deadlineAndRetention.preCommandCancelTombstoneMillis",
                60_000L,
                Comparison.EXACT,
                EvaluationStage.ENGINE),
        DEADLINE_AND_RETENTION_CAPABILITY_AND_RESOLVER_RECOVERY_RETENTION_AFTER_DEADLINE_MILLIS(
                "deadlineAndRetention.capabilityAndResolverRecoveryRetentionAfterDeadlineMillis",
                300_000L,
                Comparison.EXACT,
                EvaluationStage.ENGINE),
        DEADLINE_AND_RETENTION_TOTAL_DEADLINE_MILLIS(
                "deadlineAndRetention.totalDeadlineMillis",
                60_000L,
                Comparison.EXACT,
                ProblemCode.RENDER_DEADLINE_EXCEEDED,
                EvaluationStage.ENGINE);

        private final String id;
        private final long frozenValue;
        private final Comparison comparison;
        private final ProblemCode problemCode;
        private final EvaluationStage publicStage;

        Limit(String id, long maximumInclusive) {
            this(id, maximumInclusive, Comparison.MAX_INCLUSIVE,
                    ProblemCode.EVALUATION_BUDGET_EXCEEDED,
                    EvaluationStage.MATERIALIZATION);
        }

        Limit(
                String id,
                long maximumInclusive,
                ProblemCode problemCode,
                EvaluationStage publicStage
        ) {
            this(id, maximumInclusive, Comparison.MAX_INCLUSIVE, problemCode, publicStage);
        }

        Limit(
                String id,
                long frozenValue,
                Comparison comparison,
                EvaluationStage publicStage
        ) {
            this(id, frozenValue, comparison, null, publicStage);
        }

        Limit(
                String id,
                long frozenValue,
                Comparison comparison,
                ProblemCode problemCode,
                EvaluationStage publicStage
        ) {
            this.id = id;
            this.frozenValue = frozenValue;
            this.comparison = comparison;
            this.problemCode = problemCode;
            this.publicStage = publicStage;
        }
    }

    Optional<RenderingProblem> admit(Limit limit, long observedValue) {
        Objects.requireNonNull(limit, "limit");
        requireProblemCode(limit);
        requireNonNegative(observedValue, "observedValue");
        var admitted = switch (limit.comparison) {
            case MAX_INCLUSIVE -> observedValue <= limit.frozenValue;
            case EXACT -> observedValue == limit.frozenValue;
        };
        return admitted ? Optional.empty() : problem(limit);
    }

    RenderingProblem rejection(Limit limit) {
        Objects.requireNonNull(limit, "limit");
        requireProblemCode(limit);
        return RenderingProblem.ofLimit(
                limit.problemCode,
                limit.publicStage,
                new LimitId(limit.id));
    }

    Optional<RenderingProblem> admit(
            Limit limit,
            long observedValue,
            long effectiveMaximumInclusive
    ) {
        Objects.requireNonNull(limit, "limit");
        requireComparison(limit, Comparison.MAX_INCLUSIVE);
        requireNonNegative(observedValue, "observedValue");
        if (effectiveMaximumInclusive < 0
                || effectiveMaximumInclusive > limit.frozenValue) {
            throw new IllegalArgumentException(
                    "effective maximum must be within the frozen limit");
        }
        if (observedValue <= effectiveMaximumInclusive) {
            return Optional.empty();
        }
        return problem(limit);
    }

    long maximumInclusive(Limit limit) {
        Objects.requireNonNull(limit, "limit");
        requireComparison(limit, Comparison.MAX_INCLUSIVE);
        return limit.frozenValue;
    }

    long exactValue(Limit limit) {
        Objects.requireNonNull(limit, "limit");
        requireComparison(limit, Comparison.EXACT);
        return limit.frozenValue;
    }

    Optional<RenderingProblem> admitRuntimeMaximum(Limit limit, long observedValue) {
        Objects.requireNonNull(limit, "limit");
        requireProblemCode(limit);
        requireComparison(limit, Comparison.EXACT);
        requireNonNegative(observedValue, "observedValue");
        return observedValue <= limit.frozenValue
                ? Optional.empty()
                : problem(limit);
    }

    Optional<InvariantViolation> admitInvariant(Limit limit, long observedValue) {
        Objects.requireNonNull(limit, "limit");
        requireComparison(limit, Comparison.EXACT);
        if (limit.problemCode != null) {
            throw new IllegalArgumentException("limit must be a code-less invariant");
        }
        requireNonNegative(observedValue, "observedValue");
        return observedValue == limit.frozenValue
                ? Optional.empty()
                : Optional.of(new InvariantViolation(
                        limit.publicStage,
                        new LimitId(limit.id)));
    }

    RequestTracker newRequestTracker() {
        return new RequestTracker(this);
    }

    private static void requireComparison(Limit limit, Comparison expected) {
        if (limit.comparison != expected) {
            throw new IllegalArgumentException(
                    "limit comparison must be " + expected);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requireProblemCode(Limit limit) {
        if (limit.problemCode == null) {
            throw new IllegalArgumentException(
                    "code-less invariant cannot produce a Rendering problem");
        }
    }

    private Optional<RenderingProblem> problem(Limit limit) {
        return Optional.of(rejection(limit));
    }

    record InvariantViolation(EvaluationStage publicStage, LimitId limitId) {
        InvariantViolation {
            Objects.requireNonNull(publicStage, "publicStage");
            Objects.requireNonNull(limitId, "limitId");
        }
    }

    /** Request-local atomic accumulator backed by the same frozen limit catalog. */
    static final class RequestTracker {
        private final RenderingPipelineCapacityGuard guard;
        private final EnumMap<Limit, Long> observed = new EnumMap<>(Limit.class);

        private RequestTracker(RenderingPipelineCapacityGuard guard) {
            this.guard = guard;
        }

        Optional<RenderingProblem> reserve(Limit limit, long delta) {
            Objects.requireNonNull(limit, "limit");
            requireComparison(limit, Comparison.MAX_INCLUSIVE);
            if (delta < 0) {
                throw new IllegalArgumentException("delta must be non-negative");
            }
            var current = observed.getOrDefault(limit, 0L);
            var next = delta > Long.MAX_VALUE - current
                    ? Long.MAX_VALUE
                    : current + delta;
            var problem = guard.admit(limit, next);
            if (problem.isEmpty()) {
                observed.put(limit, next);
            }
            return problem;
        }

        Optional<RenderingProblem> observeMaximum(Limit limit, long observedValue) {
            Objects.requireNonNull(limit, "limit");
            requireComparison(limit, Comparison.MAX_INCLUSIVE);
            if (observedValue < 0) {
                throw new IllegalArgumentException("observedValue must be non-negative");
            }
            var maximum = Math.max(observed.getOrDefault(limit, 0L), observedValue);
            var problem = guard.admit(limit, maximum);
            if (problem.isEmpty()) {
                observed.put(limit, maximum);
            }
            return problem;
        }
    }
}
