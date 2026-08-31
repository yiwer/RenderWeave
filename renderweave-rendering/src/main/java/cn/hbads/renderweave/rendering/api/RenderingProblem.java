package cn.hbads.renderweave.rendering.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Rendering problem 基础形态（ADR-0044 §8）：{@code {code, stage, safeLocation, parameters}}。
 *
 * <p>stage 是 closed 九值全序；容量 problem 的 parameters 只允许 closed {@link LimitId}。
 * 内部违约（contentHash/Profile 兼容回归、malformed sealed document、manifest 不变量破坏）
 * 对外折叠为 {@link ProblemCode#RENDER_INTERNAL_ERROR}；容量数值与 limitId→code/stage/
 * reservation point/零写边界的机器 oracle 归 Ticket 19，不在此预建。
 */
public record RenderingProblem(
        ProblemCode code,
        EvaluationStage stage,
        Optional<String> safeLocation,
        Optional<LimitId> limitId
) {

    public RenderingProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(safeLocation, "safeLocation");
        Objects.requireNonNull(limitId, "limitId");
        safeLocation.ifPresent(location -> {
            if (location.isBlank() || location.length() > 1024) {
                throw new IllegalArgumentException("safeLocation must be non-blank and at most 1024 chars");
            }
        });
    }

    public static RenderingProblem of(ProblemCode code, EvaluationStage stage) {
        return new RenderingProblem(code, stage, Optional.empty(), Optional.empty());
    }

    public static RenderingProblem ofLimit(ProblemCode code, EvaluationStage stage, LimitId limitId) {
        return new RenderingProblem(code, stage, Optional.empty(), Optional.of(limitId));
    }

    public static RenderingProblem ofLocation(ProblemCode code, EvaluationStage stage, String safeLocation) {
        return new RenderingProblem(code, stage, Optional.of(safeLocation), Optional.empty());
    }

    /** closed problem code 集合，取冻结规格 issue 06/13/14/15/16 的机器可读名字。 */
    public enum ProblemCode {
        // REQUEST_ADMISSION / input envelope
        RENDER_INPUT_LIMIT_EXCEEDED,
        RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED,

        // TEMPLATE_CLOSURE
        TEMPLATE_NOT_FOUND,
        TEMPLATE_DELETED,
        TEMPLATE_DEPENDENCY_ERROR,
        TEMPLATE_AUTHORITY_UNAVAILABLE,
        TEMPLATE_CLOSURE_LIMIT_EXCEEDED,
        TEMPLATE_CLOSURE_UNSTABLE,
        DESIGN_DSL_LIMIT_EXCEEDED,

        // ASSET admission / resolution
        ASSET_BUDGET_EXCEEDED,
        ASSET_NOT_FOUND,
        ASSET_RESOLVE_NOT_FOUND,
        ASSET_RESOLVE_DELETED,
        ASSET_RESOLVE_KIND_MISMATCH,
        ASSET_RESOLVE_UNAVAILABLE,
        ASSET_RESOLVE_TIMEOUT,

        // CAPABILITY_STATE
        CAPABILITY_BUDGET_EXCEEDED,
        CAPABILITY_STATE_CONFLICT,
        CAPABILITY_STATE_UNAVAILABLE,
        CAPABILITY_RESULT_INVALID,
        CAPABILITY_CLOCK_UNAVAILABLE,
        CAPABILITY_ENTROPY_UNAVAILABLE,
        CAPABILITY_PROFILE_UNAVAILABLE,
        CAPABILITY_DEADLINE_EXCEEDED,
        CAPABILITY_CANCELLED,

        // MATERIALIZATION / budgets
        EXPRESSION_LIMIT_EXCEEDED,
        EVALUATION_BUDGET_EXCEEDED,
        EVALUATION_FAILED,

        // DOCUMENT_SEAL / diagnostics
        RENDER_DOCUMENT_LIMIT_EXCEEDED,
        RENDER_DIAGNOSTIC_LIMIT_EXCEEDED,

        // ENGINE（合同冻结，执行面随 Engine 实现票物化）
        RESOURCE_BUDGET_EXCEEDED,
        RESOURCE_LEASE_EXPIRED,
        FETCH_FAILED,
        LENGTH_MISMATCH,
        HASH_MISMATCH,
        MEDIA_MISMATCH,
        DECODE_FAILED,
        FONT_GLYPH_MISSING,
        RASTER_BUDGET_EXCEEDED,
        OUTPUT_BUDGET_EXCEEDED,
        RENDER_REQUEST_STATE_LOST,
        RENDER_REQUEST_CONFLICT,
        RENDER_ENGINE_BUSY,
        RENDER_CANCELLED,
        RENDER_DEADLINE_EXCEEDED,
        RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED,

        // 内部违约折叠
        RENDER_INTERNAL_ERROR
    }

    /** 容量 problem 的唯一合法 parameter：closed limitId（数值 oracle 归 Ticket 19）。 */
    public record LimitId(String value) {
        public LimitId {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("limitId must be non-blank and at most 256 chars");
            }
        }
    }
}
