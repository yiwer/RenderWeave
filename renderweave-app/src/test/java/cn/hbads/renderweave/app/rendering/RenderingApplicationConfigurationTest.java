package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderCommand;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityContract;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityRequirements;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RenderingApplicationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    RenderingApplicationConfiguration.class,
                    CandidatePreviewApplicationConfiguration.class)
            .withBean(TemplateClosureAuthority.class, () -> mock(TemplateClosureAuthority.class))
            .withBean(DesignInputExpressionCapacityAuthority.class,
                    TemplateModule::designInputExpressionCapacityAuthority)
            .withBean(DesignSemanticAuthority.class, () -> mock(DesignSemanticAuthority.class))
            .withBean(DesignDslAuthority.class, () -> mock(DesignDslAuthority.class))
            .withBean(TemplatePersistence.class, RenderingApplicationConfigurationTest::templates)
            .withBean(AssetOwnerScopeAuthority.class, () -> mock(AssetOwnerScopeAuthority.class))
            .withBean(ValidationTargetResolver.class, () -> mock(ValidationTargetResolver.class));

    @Test
    void defaultAssemblyExposesTheRealApplicationButFailsHostAndProfileClosed() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RenderingApplication.class);
            assertThat(context).doesNotHaveBean(CandidatePreviewApplication.class);
            assertThat(context).hasSingleBean(RenderingAuthority.class);
            assertThat(context.getBean(RenderingAuthority.class))
                    .isInstanceOf(FailClosedRenderingAuthority.class);
            assertThat(context).hasSingleBean(RendererProfileAuthority.class);
            assertThat(context.getBean(RendererProfileAuthority.class))
                    .isInstanceOf(FailClosedRendererProfileAuthority.class);
        });
    }

    @Test
    void explicitCandidatePreviewUsesASeparateApplicationWithoutPublishingAProfileAuthority() {
        contextRunner
                .withPropertyValues(
                        "renderweave.template.candidate-preview.enabled=true",
                        "renderweave.template.single-owner.enabled=true",
                        "renderweave.template.single-owner.owner-scope=render-owner",
                        "renderweave.template.single-owner.capabilities="
                                + "template.read,template.render")
                .withBean(RendererProcessAdapter.class,
                        () -> mock(RendererProcessAdapter.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(CandidatePreviewApplication.class);
                    assertThat(context).hasSingleBean(RenderingApplication.class);
                    assertThat(context).hasSingleBean(RendererProfileAuthority.class);
                    assertThat(context.getBean(RendererProfileAuthority.class))
                            .isInstanceOf(FailClosedRendererProfileAuthority.class);

                    var formal = context.getBean(RenderingApplication.class);
                    var outcome = formal.render(
                            RenderInvocationRef.serverCreated("formal-stays-closed"),
                            new RenderCommand(
                                    TemplateId.of(
                                            "00000000-0000-4000-8000-0000000000a1"),
                                    "{\"rootDocument\":{}}"
                                            .getBytes(StandardCharsets.UTF_8),
                                    cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection
                                            .defaultPng(),
                                    RenderPurpose.AUTHORITATIVE_PREVIEW));
                    assertThat(outcome)
                            .isInstanceOf(RenderOutcome.RendererUnavailable.class);
                });
    }

    @Test
    void explicitCandidateRunsTheRealEvaluatorAndExactProcessAdapterToASealedOutput() {
        var closureCalls = new AtomicInteger();
        var engineCommand = new AtomicReference<RenderEngine.RendererCommand>();
        var engine = mock(RendererProcessAdapter.class);
        when(engine.execute(any())).thenAnswer(invocation -> {
            var command = (RenderEngine.RendererCommand) invocation.getArgument(0);
            engineCommand.set(command);
            return new RenderEngine.EngineOutcome.SealedOutput(pngOutput(command));
        });

        candidateExecutionContext(closureCalls, engine).run(context -> {
            var candidate = context.getBean(CandidatePreviewApplication.class);
            var outcome = candidate.render(
                    RenderInvocationRef.serverCreated("candidate-complete-chain"),
                    new RenderCommand(
                            templateId(),
                            "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                            Evaluator.OutputSelection.defaultPng(),
                            RenderPurpose.AUTHORITATIVE_PREVIEW));

            assertThat(outcome).isInstanceOf(RenderOutcome.Rendered.class);
            assertThat(closureCalls).hasValue(1);
            assertThat(engineCommand.get()).isNotNull();
            assertThat(engineCommand.get().rendererProfile())
                    .isEqualTo("renderweave-renderer/1.0");
            assertThat(new String(
                    engineCommand.get().renderDocumentCanonicalUtf8(),
                    StandardCharsets.UTF_8))
                    .contains("\"dslVersion\":\"renderweave-render/1.0\"");
            var rendered = (RenderOutcome.Rendered) outcome;
            assertThat(rendered.output().sealedImageBytes()).containsExactly(1, 2, 3, 4);
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

    @Test
    void clockOnlyCapabilityStateDoesNotReadEntropy() {
        var entropy = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                throw new AssertionError("CLOCK-only state must not read entropy");
            }
        };
        var runtime = new RenderingApplicationConfiguration.InMemoryRenderingCapabilityRuntime(
                Clock.fixed(Instant.parse("2026-08-28T03:04:05Z"), ZoneOffset.UTC),
                entropy);

        var established = runtime.establish(new CapabilityRequirements(
                Set.of(CapabilityContract.CLOCK_1_0)));

        assertThat(established.sealedState()).hasSize(10);
        var supplied = established.runtime().supply("CLOCK", "UTC_DATE", new byte[]{1});
        assertThat(supplied).isInstanceOf(RenderingCapabilityRuntime.Supplied.class);
        assertThat(((RenderingCapabilityRuntime.DateResult)
                ((RenderingCapabilityRuntime.Supplied) supplied).value()).value())
                .isEqualTo("2026-08-28");
    }

    @Test
    void randomOnlyCapabilityStateDoesNotReadClock() {
        var unreadableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new AssertionError("RANDOM-only state must not read the clock");
            }
        };
        var entropy = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 0x5a);
            }
        };
        var runtime = new RenderingApplicationConfiguration.InMemoryRenderingCapabilityRuntime(
                unreadableClock,
                entropy);

        var established = runtime.establish(new CapabilityRequirements(
                Set.of(CapabilityContract.RANDOM_1_0)));

        assertThat(established.sealedState()).hasSize(34);
        assertThat(established.runtime().supply(
                "RANDOM", "UNIFORM_DECIMAL_0_1", new byte[]{1}))
                .isInstanceOf(RenderingCapabilityRuntime.Supplied.class);
        assertThat(established.runtime().supply("CLOCK", "UTC_DATE", new byte[]{1}))
                .isInstanceOf(RenderingCapabilityRuntime.ProviderUnavailable.class);
    }

    @Test
    void selectiveStateRestoresOnlyForItsExactRequirements() {
        var runtime = new RenderingApplicationConfiguration.InMemoryRenderingCapabilityRuntime(
                Clock.fixed(Instant.parse("2026-08-28T03:04:05Z"), ZoneOffset.UTC),
                new SecureRandom());
        var clock = new CapabilityRequirements(Set.of(CapabilityContract.CLOCK_1_0));
        var random = new CapabilityRequirements(Set.of(CapabilityContract.RANDOM_1_0));
        var state = runtime.establish(clock).sealedState();

        assertThat(runtime.restore(clock, state).supply("CLOCK", "UTC_TIME", new byte[]{1}))
                .isInstanceOf(RenderingCapabilityRuntime.Supplied.class);
        assertThatThrownBy(() -> runtime.restore(random, state))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.restore(clock, new byte[]{2, 4}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyStateRestoresOnlyForBothRequirements() {
        var runtime = new RenderingApplicationConfiguration.InMemoryRenderingCapabilityRuntime(
                Clock.fixed(Instant.parse("2026-08-28T03:04:05Z"), ZoneOffset.UTC),
                new SecureRandom());
        var both = new CapabilityRequirements(Set.of(
                CapabilityContract.CLOCK_1_0,
                CapabilityContract.RANDOM_1_0));
        var legacy = ByteBuffer.allocate(41)
                .put((byte) 1)
                .putLong(Instant.parse("2026-08-28T03:04:05Z").getEpochSecond())
                .put(new byte[32])
                .array();

        var restored = runtime.restore(both, legacy);

        assertThat(restored.supply("CLOCK", "UTC_DATE", new byte[]{1}))
                .isInstanceOf(RenderingCapabilityRuntime.Supplied.class);
        assertThat(restored.supply("RANDOM", "UNIFORM_DECIMAL_0_1", new byte[]{1}))
                .isInstanceOf(RenderingCapabilityRuntime.Supplied.class);
        assertThat(runtime.establish(both).sealedState()).hasSize(42);
        assertThatThrownBy(() -> runtime.restore(
                new CapabilityRequirements(Set.of(CapabilityContract.CLOCK_1_0)),
                legacy))
                .isInstanceOf(IllegalArgumentException.class);
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

    private static ApplicationContextRunner candidateExecutionContext(
            AtomicInteger closureCalls,
            RendererProcessAdapter engine
    ) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        RenderingApplicationConfiguration.class,
                        CandidatePreviewApplicationConfiguration.class)
                .withPropertyValues(
                        "renderweave.template.candidate-preview.enabled=true",
                        "renderweave.template.single-owner.enabled=true",
                        "renderweave.template.single-owner.owner-scope=render-owner",
                        "renderweave.template.single-owner.capabilities="
                                + "template.read,template.render")
                .withBean(TemplateClosureAuthority.class,
                        () -> successfulClosure(closureCalls))
                .withBean(DesignInputExpressionCapacityAuthority.class,
                        TemplateModule::designInputExpressionCapacityAuthority)
                .withBean(DesignSemanticAuthority.class,
                        TemplateModule::designSemanticAuthority)
                .withBean(DesignDslAuthority.class, TemplateModule::designDslAuthority)
                .withBean(TemplatePersistence.class,
                        RenderingApplicationConfigurationTest::templates)
                .withBean(AssetOwnerScopeAuthority.class,
                        () -> mock(AssetOwnerScopeAuthority.class))
                .withBean(ValidationTargetResolver.class,
                        () -> ignored -> emptyValidationTarget())
                .withBean(RendererProcessAdapter.class, () -> engine);
    }

    private static TemplateClosureAuthority successfulClosure(AtomicInteger calls) {
        var admission = TemplateModule.designDslAuthority().admit(("""
                {"definitions":[],"designRoot":{"bindings":[],"children":[],
                "heightMm":297,"kind":"canvas",
                "nodeId":"123e4567-e89b-42d3-a456-426614174000","widthMm":210},
                "displayName":"Candidate chain","dslVersion":"renderweave-design/1.0",
                "expressionProfile":"renderweave-expression/1.0"}
                """).replace("\n", "").getBytes(StandardCharsets.UTF_8));
        var admitted = (DesignDslAuthority.Admitted) admission;
        var closureOwner = new TemplateClosureAuthority.OwnerScope("render-owner");
        var snapshot = new TemplateSnapshot(
                templateId(),
                0,
                closureOwner,
                emptySchema(),
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admitted.canonicalUtf8(),
                admitted.contentHash());
        var closure = new ClosureSnapshot(
                closureOwner,
                templateId(),
                0,
                List.of(snapshot),
                List.of());
        return (requestId, rootTemplateId, control) -> {
            calls.incrementAndGet();
            return new TemplateClosureAuthority.ClosureFrozen(closure);
        };
    }

    private static ResolvedValidationTarget emptyValidationTarget() {
        var schema = new ResolvedSchema(
                new ResolvedSchemaIdentity.StaticIdentity(emptySchema()),
                new SchemaDefinition(
                        SchemaDefinition.DSL_VERSION,
                        "Empty",
                        Optional.empty(),
                        List.of()));
        return new ResolvedValidationTarget(
                schema.identity(),
                Map.of(),
                Map.of(emptySchema(), schema));
    }

    private static RenderOutput pngOutput(RenderEngine.RendererCommand command) {
        var bytes = new byte[] { 1, 2, 3, 4 };
        var dpi = ((Evaluator.OutputSelection.Png) command.outputSelection()).dpi();
        return new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                command.rendererProfile(),
                "renderweave-render/1.0",
                "renderweave-layout/1.0",
                "renderweave-output-png/1.0",
                "PNG",
                "image/png",
                794,
                1123,
                dpi,
                OptionalInt.empty(),
                bytes.length,
                sha256(bytes));
    }

    private static TemplateId templateId() {
        return TemplateId.of("00000000-0000-4000-8000-0000000000a1");
    }

    private static StaticSchemaRef emptySchema() {
        return new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
