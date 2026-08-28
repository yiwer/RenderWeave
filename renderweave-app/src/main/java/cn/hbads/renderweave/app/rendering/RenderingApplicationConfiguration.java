package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.rendering.api.CapabilityDerivation;
import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.internal.RenderingModule;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rendering app 装配（ADR-0044）：CapabilityStateStore 加密落盘 Adapter、capability 运行时
 * 与 Evaluator assembly。部署未配置 AES-256 key 时 store Adapter 不装配（失败封闭）；
 * AssetResolutionPort 由 T13 bridge 提供；Renderer process 只有在全部 exact deployment identity
 * 显式配置时才装配，默认没有 Engine bean 并保持失败封闭。
 */
@Configuration(proxyBeanMethods = false)
class RenderingApplicationConfiguration {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    @Bean("renderingClock")
    Clock renderingClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(
            name = "renderweave.template.single-owner.enabled",
            havingValue = "true")
    RenderingAuthority configuredRenderingAuthority(
            @Value("${renderweave.template.single-owner.owner-scope:}") String ownerScope,
            @Value("${renderweave.template.single-owner.capabilities:}") String capabilities,
            TemplatePersistence templates
    ) {
        return new ConfiguredSingleOwnerRenderingAuthority(
                ownerScope,
                parseCapabilities(capabilities),
                templates);
    }

    @Bean
    @ConditionalOnMissingBean(RenderingAuthority.class)
    RenderingAuthority failClosedRenderingAuthority() {
        return new FailClosedRenderingAuthority();
    }

    @Bean
    @ConditionalOnMissingBean(RendererProfileAuthority.class)
    RendererProfileAuthority failClosedRendererProfileAuthority() {
        return new FailClosedRendererProfileAuthority();
    }

    @Bean
    @ConditionalOnExpression("'${renderweave.rendering.capability-state.key:}' != ''")
    SecretKey renderingCapabilityStateKey(
            @Value("${renderweave.rendering.capability-state.key:}") String base64Key
    ) {
        var decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "renderweave.rendering.capability-state.key must be a 32-byte AES-256 key");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    @Bean
    @ConditionalOnBean(SecretKey.class)
    PostgresCapabilityStateStore postgresCapabilityStateStore(
            JdbcClient jdbc,
            SecretKey key,
            PlatformTransactionManager transactionManager
    ) {
        return new PostgresCapabilityStateStore(jdbc, key, transactionManager);
    }

    @Bean
    @ConditionalOnBean(PostgresCapabilityStateStore.class)
    RenderingCapabilityStateSweeper renderingCapabilityStateSweeper(
            PostgresCapabilityStateStore store
    ) {
        return new RenderingCapabilityStateSweeper(store);
    }

    @Bean
    RenderingCapabilityRuntime renderingCapabilityRuntime(
            @Qualifier("renderingClock") Clock renderingClock
    ) {
        return new InMemoryRenderingCapabilityRuntime(renderingClock, new SecureRandom());
    }

    @Bean
    @ConditionalOnBean(AssetResolver.class)
    AssetResolutionPort renderingAssetResolutionPort(AssetResolver resolver) {
        return new AssetResolverToRenderingAdapter(resolver);
    }

    @Bean
    Evaluator renderingEvaluator(
            TemplateClosureAuthority closureAuthority,
            DesignSemanticAuthority semantics,
            DesignDslAuthority dslAuthority,
            ObjectProvider<AssetResolutionPort> assets,
            RenderingCapabilityRuntime capabilities,
            ObjectProvider<CapabilityStateStore> capabilityStates,
            ValidationTargetResolver validationResolver,
            @Qualifier("renderingClock") Clock renderingClock
    ) {
        return RenderingModule.evaluator(
                closureAuthority,
                semantics,
                dslAuthority,
                assets.getIfAvailable(),
                capabilities,
                capabilityStates.getIfAvailable(RenderingApplicationConfiguration::unavailableCapabilityStateStore),
                EffectiveBudgetVector.load(),
                validationResolver,
                renderingClock);
    }

    private static CapabilityStateStore unavailableCapabilityStateStore() {
        return new CapabilityStateStore() {
            @Override
            public SaveOutcome save(SaveRequest request) {
                return new SaveOutcome.SaveUnavailable();
            }

            @Override
            public LoadOutcome load(Evaluator.RenderRequestId requestId, String fingerprint) {
                return new LoadOutcome.LoadUnavailable();
            }
        };
    }

    @Bean
    RenderingApplication renderingApplication(
            Evaluator evaluator,
            ObjectProvider<RenderEngine> engines,
            RenderingAuthority authority,
            RendererProfileAuthority profiles,
            @Qualifier("renderingClock") Clock renderingClock
    ) {
        var availableEngine = engines.getIfAvailable();
        if (availableEngine == null) {
            RenderEngine unavailableEngine = command ->
                    new RenderEngine.EngineOutcome.TerminalProblem(RenderingProblem.of(
                            RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                            EvaluationStage.ENGINE));
            RendererProfileAuthority unavailableProfiles = output ->
                    new RendererProfileAuthority.Unavailable();
            return RenderingModule.application(
                    evaluator,
                    unavailableEngine,
                    authority,
                    unavailableProfiles,
                    renderingClock);
        }
        return RenderingModule.application(
                evaluator,
                availableEngine,
                authority,
                profiles,
                renderingClock);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "renderweave.rendering.engine.process",
            name = "enabled",
            havingValue = "true")
    RendererProcessAdapter rendererProcessEngine(
            @Value("${renderweave.rendering.engine.process.executable}") String executable,
            @Value("${renderweave.rendering.engine.process.socket}") String socket,
            @Value("${renderweave.rendering.engine.process.manifest}") String manifest,
            @Value("${renderweave.rendering.engine.process.manifest-sha256}") String manifestSha256,
            @Value("${renderweave.asset.fetch-base-url}") String assetFetchOrigin,
            @Value("${renderweave.rendering.engine.process.asset-fetch-allowed-ips}")
            List<String> assetFetchAllowedIps,
            @Value("${renderweave.rendering.engine.process.max-frame-bytes}") int maximumFramedBytes,
            @Value("${renderweave.rendering.engine.process.startup-timeout-ms}") long startupTimeoutMillis,
            @Value("${renderweave.rendering.engine.process.restart-backoff-ms}") long restartBackoffMillis,
            @Value("${renderweave.rendering.engine.process.handshake-timeout-ms}") long handshakeTimeoutMillis,
            @Qualifier("renderingClock") Clock renderingClock
    ) {
        var supervisor = new RendererProcessSupervisor(
                Path.of(executable),
                Path.of(socket),
                Path.of(manifest),
                manifestSha256,
                assetFetchOrigin,
                assetFetchAllowedIps,
                maximumFramedBytes,
                Duration.ofMillis(startupTimeoutMillis),
                Duration.ofMillis(restartBackoffMillis),
                renderingClock);
        return new RendererProcessAdapter(
                supervisor,
                manifestSha256,
                maximumFramedBytes,
                Duration.ofMillis(handshakeTimeoutMillis),
                renderingClock);
    }

    private static Set<String> parseCapabilities(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 每 Evaluation 仅建立完整 closure 声明的 exact capability components；Clock 投影与
     * Random 派生使用 api {@link CapabilityDerivation}（与 Rendering 内部同一 exact 合同）。
     * opaque state 由 CapabilityStateStore 按 fingerprint 重放或拒绝冲突。
     */
    static final class InMemoryRenderingCapabilityRuntime implements RenderingCapabilityRuntime {

        private static final byte LEGACY_STATE_VERSION = 1;
        private static final byte STATE_VERSION = 2;
        private static final byte CLOCK_FLAG = 0x01;
        private static final byte RANDOM_FLAG = 0x02;
        private static final byte KNOWN_FLAGS = CLOCK_FLAG | RANDOM_FLAG;
        private static final int NONCE_BYTES = 32;

        private final Clock clock;
        private final SecureRandom entropy;

        InMemoryRenderingCapabilityRuntime(Clock clock, SecureRandom entropy) {
            this.clock = Objects.requireNonNull(clock, "clock");
            this.entropy = Objects.requireNonNull(entropy, "entropy");
        }

        @Override
        public Established establish(CapabilityRequirements requirements) {
            Objects.requireNonNull(requirements, "requirements");
            var flags = flags(requirements);
            var state = ByteBuffer.allocate(stateLength(flags))
                    .put(STATE_VERSION)
                    .put(flags);
            Long clockSecond = null;
            byte[] nonce = null;
            if ((flags & CLOCK_FLAG) != 0) {
                clockSecond = clock.instant().getEpochSecond();
                state.putLong(clockSecond);
            }
            if ((flags & RANDOM_FLAG) != 0) {
                nonce = new byte[NONCE_BYTES];
                entropy.nextBytes(nonce);
                state.put(nonce);
            }
            return new Established(runtime(clockSecond, nonce), state.array());
        }

        @Override
        public Runtime restore(CapabilityRequirements requirements, byte[] sealedState) {
            Objects.requireNonNull(requirements, "requirements");
            if (sealedState == null || sealedState.length == 0) {
                throw new IllegalArgumentException("invalid capability state");
            }
            if (sealedState[0] == LEGACY_STATE_VERSION) {
                return restoreLegacy(requirements, sealedState);
            }
            if (sealedState.length < 2 || sealedState[0] != STATE_VERSION) {
                throw new IllegalArgumentException("invalid capability state");
            }
            var flags = sealedState[1];
            if ((flags & ~KNOWN_FLAGS) != 0
                    || flags != flags(requirements)
                    || sealedState.length != stateLength(flags)) {
                throw new IllegalArgumentException("capability state requirements mismatch");
            }
            var state = ByteBuffer.wrap(sealedState);
            state.get();
            state.get();
            Long clockSecond = null;
            byte[] nonce = null;
            if ((flags & CLOCK_FLAG) != 0) {
                clockSecond = state.getLong();
            }
            if ((flags & RANDOM_FLAG) != 0) {
                nonce = new byte[NONCE_BYTES];
                state.get(nonce);
            }
            return runtime(clockSecond, nonce);
        }

        private static Runtime restoreLegacy(
                CapabilityRequirements requirements,
                byte[] sealedState
        ) {
            if (!requirements.requires(CapabilityContract.CLOCK_1_0)
                    || !requirements.requires(CapabilityContract.RANDOM_1_0)
                    || sealedState.length != 1 + Long.BYTES + NONCE_BYTES) {
                throw new IllegalArgumentException("legacy capability state requirements mismatch");
            }
            var state = ByteBuffer.wrap(sealedState);
            state.get();
            var clockSecond = state.getLong();
            var nonce = new byte[NONCE_BYTES];
            state.get(nonce);
            return runtime(clockSecond, nonce);
        }

        private static byte flags(CapabilityRequirements requirements) {
            byte flags = 0;
            if (requirements.requires(CapabilityContract.CLOCK_1_0)) {
                flags |= CLOCK_FLAG;
            }
            if (requirements.requires(CapabilityContract.RANDOM_1_0)) {
                flags |= RANDOM_FLAG;
            }
            return flags;
        }

        private static int stateLength(byte flags) {
            return 2
                    + ((flags & CLOCK_FLAG) == 0 ? 0 : Long.BYTES)
                    + ((flags & RANDOM_FLAG) == 0 ? 0 : NONCE_BYTES);
        }

        private static Runtime runtime(Long clockSecond, byte[] nonce) {
            return (capability, operation, callPosition) -> {
                switch (capability + "/" + operation) {
                    case "CLOCK/UTC_DATE": {
                        if (clockSecond == null) {
                            return new ProviderUnavailable();
                        }
                        return new Supplied(new DateResult(DATE_FORMAT.format(
                                Instant.ofEpochSecond(clockSecond))));
                    }
                    case "CLOCK/UTC_TIME": {
                        if (clockSecond == null) {
                            return new ProviderUnavailable();
                        }
                        return new Supplied(new TimeResult(TIME_FORMAT.format(
                                Instant.ofEpochSecond(clockSecond))));
                    }
                    case "RANDOM/UNIFORM_DECIMAL_0_1": {
                        if (nonce == null) {
                            return new ProviderUnavailable();
                        }
                        BigDecimal derived = CapabilityDerivation.uniformDecimal(
                                nonce, callPosition);
                        if (derived == null) {
                            return new ProviderUnavailable();
                        }
                        return new Supplied(new DecimalResult(derived));
                    }
                    default:
                        return new ProviderUnavailable();
                }
            };
        }

        @Override
        public Set<CapabilityContract> supportedContracts() {
            return Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0);
        }
    }

    /** 固定 TTL 过期驱动：周期删除过期记录；不续期、不解密。 */
    static final class RenderingCapabilityStateSweeper {

        private final PostgresCapabilityStateStore store;

        RenderingCapabilityStateSweeper(PostgresCapabilityStateStore store) {
            this.store = store;
        }

        @Scheduled(fixedDelayString = "${renderweave.rendering.capability-state.sweep-delay-ms:60000}")
        void sweep() {
            store.sweepExpired();
        }
    }
}
