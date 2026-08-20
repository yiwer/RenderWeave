package cn.hbads.renderweave.rendering.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rendering 上下文的唯一动态语义权威（ADR-0044 §2）。
 *
 * <p>单一窄入口：一次根 Evaluation 把 Template closure snapshot、AdmittedRenderInput 与获准
 * capability 按固定准入与 consumer 顺序降低并原子 seal 为请求级 RenderDocument。input admission、
 * first-fail 串行、lazy materialization、Binding overlay、结构展开与 Asset occurrence 串行 resolve
 * 全部收在 Rendering 内部；失败无 partial output。
 *
 * <p>SealedDocument 携带的 RenderDocument canonical bytes 与 digest 只用于向 RenderEngine 交接，
 * 一律不返回产品调用方、不持久化、不跨请求复用。
 */
public interface Evaluator {

    EvaluationOutcome evaluate(EvaluationCommand command);

    /** Rendering 创建的请求级不透明身份；不跨请求复用。 */
    record RenderRequestId(String value) {
        private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

        public RenderRequestId {
            Objects.requireNonNull(value, "value");
            if (!CANONICAL_UUID_V4.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "renderRequestId must be a canonical lowercase UUID v4");
            }
        }
    }

    /** Rendering-owned ownerScope 窄 facet 值；请求不得自报，由 Host capability 授权后经 app 传入。 */
    record OwnerScope(String value) {
        public OwnerScope {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("ownerScope must be non-blank and at most 256 chars");
            }
        }
    }

    /**
     * 一次根 Evaluation 的封闭命令。
     *
     * @param renderRequestId     Rendering 创建的请求级身份
     * @param ownerScope          授权上下文携带的 ownerScope（Host capability 解析，非请求自报）
     * @param rootTemplateId      根 Template 身份；Schema 目标只来自其 TemplateSnapshot
     * @param rawRenderInputUtf8  原始 RenderInput strict-JSON envelope bytes
     * @param outputSelection     bounded output 选择；缺省已在构造前展开为 96/90
     */
    record EvaluationCommand(
            RenderRequestId renderRequestId,
            OwnerScope ownerScope,
            cn.hbads.renderweave.template.api.TemplateApplication.TemplateId rootTemplateId,
            byte[] rawRenderInputUtf8,
            OutputSelection outputSelection
    ) {
        public EvaluationCommand {
            Objects.requireNonNull(renderRequestId, "renderRequestId");
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(rootTemplateId, "rootTemplateId");
            Objects.requireNonNull(rawRenderInputUtf8, "rawRenderInputUtf8");
            Objects.requireNonNull(outputSelection, "outputSelection");
            rawRenderInputUtf8 = rawRenderInputUtf8.clone();
        }

        public byte[] rawRenderInputUtf8() {
            return rawRenderInputUtf8.clone();
        }
    }

    /**
     * caller 只选 bounded output；Layout Profile 由 compatibility table 唯一确定、
     * Renderer Profile 服务端冻结，调用方不能协商、不能选 latest 或 fallback。
     */
    sealed interface OutputSelection permits OutputSelection.Png, OutputSelection.Jpeg {

        int DEFAULT_DPI = 96;
        int DEFAULT_JPEG_QUALITY = 90;

        /** 缺省展开为 96 dpi 的 PNG。 */
        record Png(int dpi) implements OutputSelection {
            public Png {
                if (dpi < 1 || dpi > 100_000) {
                    throw new IllegalArgumentException("dpi must be within 1..100000");
                }
            }
        }

        /** 缺省展开为 96 dpi / quality 90 的 JPEG。 */
        record Jpeg(int dpi, int quality) implements OutputSelection {
            public Jpeg {
                if (dpi < 1 || dpi > 100_000) {
                    throw new IllegalArgumentException("dpi must be within 1..100000");
                }
                if (quality < 1 || quality > 100) {
                    throw new IllegalArgumentException("quality must be within 1..100");
                }
            }
        }

        static OutputSelection defaultPng() {
            return new Png(DEFAULT_DPI);
        }

        static OutputSelection defaultJpeg() {
            return new Jpeg(DEFAULT_DPI, DEFAULT_JPEG_QUALITY);
        }
    }

    /** closed 求值结果：原子 seal 成功交接值，或 first-fail 阶段拒绝。 */
    sealed interface EvaluationOutcome
            permits EvaluationOutcome.SealedDocument, EvaluationOutcome.Rejected {

        /**
         * 不可变 RenderDocument 交接值 + 成功身份成分。只用于向 RenderEngine 交接；
         * 不返回产品调用方、不持久化、不跨请求复用。
         *
         * @param renderDocumentCanonicalUtf8 canonical RenderDocument strict-JSON bytes（含 fetch lease）
         * @param renderDocumentDigest        {@code sha256:} 前缀的 RenderDocument digest
         * @param evaluationResultDigest      成功语义身份（domain-separated SHA-256）
         */
        record SealedDocument(
                RenderRequestId renderRequestId,
                byte[] renderDocumentCanonicalUtf8,
                String renderDocumentDigest,
                String evaluationResultDigest,
                OutputSelection outputSelection
        ) implements EvaluationOutcome {
            public SealedDocument {
                Objects.requireNonNull(renderRequestId, "renderRequestId");
                Objects.requireNonNull(renderDocumentCanonicalUtf8, "renderDocumentCanonicalUtf8");
                Objects.requireNonNull(renderDocumentDigest, "renderDocumentDigest");
                Objects.requireNonNull(evaluationResultDigest, "evaluationResultDigest");
                Objects.requireNonNull(outputSelection, "outputSelection");
                if (renderDocumentCanonicalUtf8.length == 0) {
                    throw new IllegalArgumentException("renderDocumentCanonicalUtf8 must not be empty");
                }
                renderDocumentCanonicalUtf8 = renderDocumentCanonicalUtf8.clone();
            }

            public byte[] renderDocumentCanonicalUtf8() {
                return renderDocumentCanonicalUtf8.clone();
            }
        }

        /** first-fail 串行语义下的阶段拒绝；有界问题，无 partial output。 */
        record Rejected(EvaluationStage stage, RenderingProblem problem) implements EvaluationOutcome {
            public Rejected {
                Objects.requireNonNull(stage, "stage");
                Objects.requireNonNull(problem, "problem");
                if (problem.stage() != stage) {
                    throw new IllegalArgumentException("problem stage must match rejection stage");
                }
            }
        }
    }
}
