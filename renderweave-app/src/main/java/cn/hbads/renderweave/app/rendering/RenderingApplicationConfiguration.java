package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.rendering.api.CapabilityDerivation;
import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.internal.RenderingModule;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

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
    PostgresCapabilityStateStore postgresCapabilityStateStore(JdbcClient jdbc, SecretKey key) {
        return new PostgresCapabilityStateStore(jdbc, key);
    }

    @Bean
    @ConditionalOnBean(PostgresCapabilityStateStore.class)
    RenderingCapabilityStateSweeper renderingCapabilityStateSweeper(
            PostgresCapabilityStateStore store
    ) {
        return new RenderingCapabilityStateSweeper(store);
    }

    @Bean
    RenderingCapabilityRuntime renderingCapabilityRuntime() {
        return new InMemoryRenderingCapabilityRuntime();
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
            ValidationTargetResolver validationResolver
    ) {
        return RenderingModule.evaluator(
                closureAuthority,
                semantics,
                dslAuthority,
                assets.getIfAvailable(),
                capabilities,
                validationResolver,
                Clock.systemUTC());
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
            @Value("${renderweave.rendering.engine.process.handshake-timeout-ms}") long handshakeTimeoutMillis
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
                Clock.systemUTC());
        return new RendererProcessAdapter(
                supervisor,
                manifestSha256,
                maximumFramedBytes,
                Duration.ofMillis(handshakeTimeoutMillis),
                Clock.systemUTC());
    }

    /**
     * 每 Evaluation 建立单一 Clock snapshot + 单一 server-only nonce；Clock 投影与 Random
     * 派生使用 api {@link CapabilityDerivation}（与 Rendering 内部同一 exact 合同）。
     * CapabilityState 的持久化重放流（fingerprint replay/conflict）随 Engine 时代的
     * resend 编排接入 CapabilityStateStore。
     */
    static final class InMemoryRenderingCapabilityRuntime implements RenderingCapabilityRuntime {

        private final SecureRandom entropy = new SecureRandom();

        @Override
        public Runtime establish() {
            var clockSecond = Instant.now(Clock.systemUTC()).getEpochSecond();
            var nonce = new byte[32];
            entropy.nextBytes(nonce);
            return (capability, operation, callPosition) -> {
                switch (capability + "/" + operation) {
                    case "CLOCK/UTC_DATE":
                        return new Supplied(new DateResult(DATE_FORMAT.format(
                                Instant.ofEpochSecond(clockSecond))));
                    case "CLOCK/UTC_TIME":
                        return new Supplied(new TimeResult(TIME_FORMAT.format(
                                Instant.ofEpochSecond(clockSecond))));
                    case "RANDOM/UNIFORM_DECIMAL_0_1": {
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
        public String capabilityContracts() {
            return "renderweave-capability-clock/1.0,renderweave-capability-random/1.0";
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
