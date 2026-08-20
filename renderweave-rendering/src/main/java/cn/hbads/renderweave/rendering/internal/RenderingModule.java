package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.validation.ValidationTargetResolver;

import java.util.Objects;

/**
 * app 可 import 的唯一 Rendering {@code .internal} assembly seam（ADR-0041 窄例外，
 * 与 {@code TemplateModule}/{@code AssetModule} 同构）：final、static factory、无 public 构造器、
 * 无 Spring/反射旁路。factory 随各实现切片登记。
 */
public final class RenderingModule {

    private RenderingModule() {
    }

    /**
     * Evaluator assembly：closure/语义/admission authority + Asset resolve port（T13 前可为
     * null，含 Asset 的 Evaluation fail-closed）+ capability 运行时 + 验证目标解析 + 部署
     * deadline。Engine 执行面随 Renderer 实现票另行装配。
     */
    public static Evaluator evaluator(
            TemplateClosureAuthority closureAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            RenderingCapabilityRuntime capabilities,
            ValidationTargetResolver validationResolver,
            long deadlineEpochMilli
    ) {
        return new CanonicalEvaluator(
                Objects.requireNonNull(closureAuthority, "closureAuthority"),
                Objects.requireNonNull(semantics, "semantics"),
                Objects.requireNonNull(dslAuthority, "dslAuthority"),
                assets,
                Objects.requireNonNull(capabilities, "capabilities"),
                Objects.requireNonNull(validationResolver, "validationResolver"),
                deadlineEpochMilli);
    }
}
