package cn.hbads.renderweave.rendering.api;

import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 正式输出与 Authoritative Preview 共用的 Rendering 产品应用接口。
 *
 * <p>调用方只提交根 Template、RenderInput 与 bounded output 选择；ownerScope、exact Profile、
 * Engine request identity、RenderDocument、deadline、lease 与内部 digest 均由服务端持有。
 */
public interface RenderingApplication {

    RenderOutcome render(RenderInvocationRef invocation, RenderCommand command);

    /** app 为一次宿主调用创建的不透明引用；请求 body 不得自报授权事实。 */
    record RenderInvocationRef(String value) {
        public RenderInvocationRef {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "invocation must be non-blank and at most 256 characters");
            }
        }

        public static RenderInvocationRef serverCreated(String value) {
            return new RenderInvocationRef(value);
        }
    }

    /** 产品调用方可见、但不能用作 Engine replay/cancel capability 的 UUID v4。 */
    record RenderOperationId(String value) {
        private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

        public RenderOperationId {
            Objects.requireNonNull(value, "value");
            if (!CANONICAL_UUID_V4.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "renderOperationId must be a canonical lowercase UUID v4");
            }
        }
    }

    /** purpose 由受信任 route/controller 选择，不从不透明请求字段动态扩展 capability。 */
    enum RenderPurpose {
        FORMAL_OUTPUT,
        AUTHORITATIVE_PREVIEW
    }

    /** 一次同步、单根 Canvas、单张完整图片的产品命令。 */
    record RenderCommand(
            TemplateId rootTemplateId,
            byte[] rawRenderInputUtf8,
            Evaluator.OutputSelection outputSelection,
            RenderPurpose purpose
    ) {
        public RenderCommand {
            Objects.requireNonNull(rootTemplateId, "rootTemplateId");
            Objects.requireNonNull(rawRenderInputUtf8, "rawRenderInputUtf8");
            Objects.requireNonNull(outputSelection, "outputSelection");
            Objects.requireNonNull(purpose, "purpose");
            rawRenderInputUtf8 = rawRenderInputUtf8.clone();
        }

        public byte[] rawRenderInputUtf8() {
            return rawRenderInputUtf8.clone();
        }
    }

    /**
     * closed 产品结果。每个结果携带 public operation identity；只有 {@link Rendered} 含图片，
     * 所有其他结果均保证没有 partial/旧 output 回退。
     */
    sealed interface RenderOutcome permits
            RenderOutcome.Rendered,
            RenderOutcome.Rejected,
            RenderOutcome.NotFound,
            RenderOutcome.Forbidden,
            RenderOutcome.AuthorityUnavailable,
            RenderOutcome.RendererUnavailable {

        RenderOperationId operationId();

        record Rendered(RenderOperationId operationId, RenderOutput output)
                implements RenderOutcome {
            public Rendered {
                Objects.requireNonNull(operationId, "operationId");
                Objects.requireNonNull(output, "output");
            }
        }

        record Rejected(RenderOperationId operationId, RenderingProblem problem)
                implements RenderOutcome {
            public Rejected {
                Objects.requireNonNull(operationId, "operationId");
                Objects.requireNonNull(problem, "problem");
            }
        }

        record NotFound(RenderOperationId operationId) implements RenderOutcome {
            public NotFound {
                Objects.requireNonNull(operationId, "operationId");
            }
        }

        record Forbidden(RenderOperationId operationId) implements RenderOutcome {
            public Forbidden {
                Objects.requireNonNull(operationId, "operationId");
            }
        }

        record AuthorityUnavailable(RenderOperationId operationId) implements RenderOutcome {
            public AuthorityUnavailable {
                Objects.requireNonNull(operationId, "operationId");
            }
        }

        record RendererUnavailable(RenderOperationId operationId) implements RenderOutcome {
            public RendererUnavailable {
                Objects.requireNonNull(operationId, "operationId");
            }
        }
    }
}
