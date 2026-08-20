package cn.hbads.renderweave.app.rendering;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Rendering app 装配（ADR-0044 §5）：CapabilityStateStore 加密落盘 Adapter。
 * 部署未配置 AES-256 key 时 Adapter 不装配，Evaluation 失败封闭——与 Asset 无 S3
 * endpoint 时不装配同一模式。
 */
@Configuration(proxyBeanMethods = false)
class RenderingApplicationConfiguration {

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
