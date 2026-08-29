package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.validation.ValidationTargetResolver;

import java.time.Clock;
import java.time.Duration;
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
            DesignInputExpressionCapacityAuthority capacityAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            AssetResolutionPort assets,
            RenderingCapabilityRuntime capabilities,
            CapabilityStateStore capabilityStates,
            String effectiveBudgetVector,
            ValidationTargetResolver validationResolver,
            Clock clock
    ) {
        return new CanonicalEvaluator(
                Objects.requireNonNull(closureAuthority, "closureAuthority"),
                Objects.requireNonNull(capacityAuthority, "capacityAuthority"),
                Objects.requireNonNull(semantics, "semantics"),
                Objects.requireNonNull(dslAuthority, "dslAuthority"),
                assets,
                Objects.requireNonNull(capabilities, "capabilities"),
                Objects.requireNonNull(capabilityStates, "capabilityStates"),
                Objects.requireNonNull(effectiveBudgetVector, "effectiveBudgetVector"),
                Objects.requireNonNull(validationResolver, "validationResolver"),
                Objects.requireNonNull(clock, "clock"));
    }

    /**
     * 正式 Rendering 产品操作 assembly：授权、Profile availability、一次 Evaluation、同 Command
     * 恢复与结果释放前 recheck。没有 available Profile 时在 payload work 前失败封闭。
     */
    public static RenderingApplication application(
            Evaluator evaluator,
            RenderEngine engine,
            RenderingAuthority authority,
            RendererProfileAuthority profiles,
            Clock clock
    ) {
        return new CanonicalRenderingApplication(
                Objects.requireNonNull(evaluator, "evaluator"),
                Objects.requireNonNull(engine, "engine"),
                Objects.requireNonNull(authority, "authority"),
                Objects.requireNonNull(profiles, "profiles"),
                Objects.requireNonNull(clock, "clock"),
                Duration.ofMillis(10));
    }
}
