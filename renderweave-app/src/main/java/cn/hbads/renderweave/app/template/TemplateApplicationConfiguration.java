package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.app.coordination.AssetDependencyFacts;
import cn.hbads.renderweave.asset.spi.AssetReferencePort;
import cn.hbads.renderweave.asset.spi.AssetAuditEventSource;
import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.AssetReferenceAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.InvalidCommitConfirmationAuthority;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class TemplateApplicationConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "renderweave.template.single-owner.enabled",
            havingValue = "true"
    )
    OwnerScopeAuthority configuredTemplateOwnerScopeAuthority(
            @Value("${renderweave.template.single-owner.owner-scope:}") String ownerScope,
            @Value("${renderweave.template.single-owner.capabilities:}") String capabilities
    ) {
        return new ConfiguredSingleOwnerScopeAuthority(ownerScope, parseCapabilities(capabilities));
    }

    @Bean
    @ConditionalOnMissingBean(OwnerScopeAuthority.class)
    OwnerScopeAuthority failClosedTemplateOwnerScopeAuthority() {
        return new FailClosedOwnerScopeAuthority();
    }

    @Bean
    DependencyResolution templateDependencyResolution(
            JdbcClient jdbc,
            AssetDependencyFacts assetFacts
    ) {
        return new TemplateDependencyResolutionAdapter(jdbc, assetFacts);
    }

    @Bean
    TemplateApplication templateApplication(
            OwnerScopeAuthority ownerScopes,
            TemplatePersistence persistence,
            StaticSchemaAuthority schemas,
            DependencyResolution dependencyResolution,
            ObjectProvider<InvalidCommitConfirmationAuthority> confirmationProvider
    ) {
        var confirmations = confirmationProvider.getIfAvailable();
        if (confirmations == null) {
            return TemplateModule.application(
                    ownerScopes,
                    persistence,
                    schemas,
                    dependencyResolution
            );
        }
        return TemplateModule.application(
                ownerScopes,
                persistence,
                schemas,
                dependencyResolution,
                confirmations
        );
    }

    @Bean
    AssetReferenceAuthority templateAssetReferenceAuthority(TemplatePersistence persistence) {
        return TemplateModule.assetReferenceAuthority(persistence);
    }

    @Bean
    cn.hbads.renderweave.template.api.DesignDslAuthority templateDesignDslAuthority() {
        return TemplateModule.designDslAuthority();
    }

    @Bean
    cn.hbads.renderweave.template.api.DesignSemanticAuthority templateDesignSemanticAuthority() {
        return TemplateModule.designSemanticAuthority();
    }

    @Bean
    DesignInputExpressionCapacityAuthority templateDesignInputExpressionCapacityAuthority() {
        return TemplateModule.designInputExpressionCapacityAuthority();
    }

    @Bean
    cn.hbads.renderweave.template.api.TemplateClosureAuthority templateClosureAuthority(
            TemplatePersistence persistence
    ) {
        return TemplateModule.closureAuthority(persistence);
    }

    @Bean
    TemplateReadinessAuthority templateReadinessAuthority(
            TemplatePersistence persistence,
            DependencyResolution dependencyResolution,
            StaticSchemaAuthority schemas
    ) {
        return TemplateModule.readinessAuthority(persistence, dependencyResolution, schemas);
    }

    @Bean
    AssetReferencePort templateAssetReferencePort(
            AssetReferenceAuthority authority,
            TemplatePersistence persistence,
            OwnerScopeAuthority ownerScopes
    ) {
        return new TemplateAssetReferencePortAdapter(authority, persistence, ownerScopes);
    }

    @Bean
    TemplateAssetStaleConsumer templateAssetStaleConsumer(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            AssetAuditEventSource assetAuditEvents,
            TemplateReadinessAuthority readinessAuthority
    ) {
        return new TemplateAssetStaleConsumer(
                jdbc, transactionManager, assetAuditEvents, readinessAuthority);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "renderweave.template.stale-consumer",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    TemplateAssetStalePoller templateAssetStalePoller(TemplateAssetStaleConsumer consumer) {
        return new TemplateAssetStalePoller(consumer);
    }

    private static Set<String> parseCapabilities(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Production scheduling adapter; the replayable consumer remains directly testable. */
    static final class TemplateAssetStalePoller {
        private final TemplateAssetStaleConsumer consumer;

        TemplateAssetStalePoller(TemplateAssetStaleConsumer consumer) {
            this.consumer = Objects.requireNonNull(consumer, "consumer");
        }

        @Scheduled(fixedDelayString = "${renderweave.template.stale-consumer.delay-ms:5000}")
        void poll() {
            consumer.consumePending();
            consumer.recheckStale();
        }
    }
}
