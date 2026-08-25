package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderCommand;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RenderingApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RenderingApplicationConfiguration.class)
            .withBean(TemplateClosureAuthority.class, () -> mock(TemplateClosureAuthority.class))
            .withBean(DesignSemanticAuthority.class, () -> mock(DesignSemanticAuthority.class))
            .withBean(DesignDslAuthority.class, () -> mock(DesignDslAuthority.class))
            .withBean(TemplatePersistence.class, RenderingApplicationConfigurationTest::templates)
            .withBean(ValidationTargetResolver.class, () -> mock(ValidationTargetResolver.class));

    @Test
    void defaultAssemblyExposesTheRealApplicationButFailsHostAndProfileClosed() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RenderingApplication.class);
            assertThat(context).hasSingleBean(RenderingAuthority.class);
            assertThat(context.getBean(RenderingAuthority.class))
                    .isInstanceOf(FailClosedRenderingAuthority.class);
            assertThat(context).hasSingleBean(RendererProfileAuthority.class);
            assertThat(context.getBean(RendererProfileAuthority.class))
                    .isInstanceOf(FailClosedRendererProfileAuthority.class);
        });
    }

    @Test
    void explicitSingleOwnerConfigurationSelectsTheRenderingHostFacet() {
        contextRunner
                .withPropertyValues(
                        "renderweave.template.single-owner.enabled=true",
                        "renderweave.template.single-owner.owner-scope=render-owner",
                        "renderweave.template.single-owner.capabilities="
                                + "template.read,template.render")
                .run(context -> {
                    assertThat(context).hasSingleBean(RenderingAuthority.class);
                    assertThat(context.getBean(RenderingAuthority.class))
                            .isInstanceOf(ConfiguredSingleOwnerRenderingAuthority.class);
                });
    }

    @Test
    void availableProfileWithoutAnEngineStillStopsBeforeEvaluation() {
        contextRunner
                .withPropertyValues(
                        "renderweave.template.single-owner.enabled=true",
                        "renderweave.template.single-owner.owner-scope=render-owner",
                        "renderweave.template.single-owner.capabilities="
                                + "template.read,template.render")
                .withBean(RendererProfileAuthority.class, () -> output ->
                        new RendererProfileAuthority.Available(
                                "renderweave-renderer/1.0",
                                "renderweave-layout/1.0"))
                .run(context -> {
                    var application = context.getBean(RenderingApplication.class);
                    var outcome = application.render(
                            RenderInvocationRef.serverCreated("no-engine"),
                            new RenderCommand(
                                    TemplateId.of("00000000-0000-4000-8000-0000000000a1"),
                                    "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                                    cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection
                                            .defaultPng(),
                                    RenderPurpose.AUTHORITATIVE_PREVIEW));

                    assertThat(outcome).isInstanceOf(RenderOutcome.RendererUnavailable.class);
                });
    }

    private static TemplatePersistence templates() {
        var templates = mock(TemplatePersistence.class);
        when(templates.locate(any())).thenAnswer(invocation ->
                new TemplatePersistence.Located(new TemplatePersistence.TemplateMetadata(
                        invocation.getArgument(0),
                        new OwnerScopeAuthority.OwnerScope("render-owner"),
                        new StaticSchemaRef(
                                SchemaKey.systemProvided("system-empty"),
                                VersionTag.of("v1")),
                        0,
                        TemplatePersistence.Lifecycle.ACTIVE)));
        return templates;
    }
}
