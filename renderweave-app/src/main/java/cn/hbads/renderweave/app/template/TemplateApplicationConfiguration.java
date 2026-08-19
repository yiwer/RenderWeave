package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.internal.TemplateModule;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
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
    TemplateApplication templateApplication(
            OwnerScopeAuthority ownerScopes,
            TemplatePersistence persistence,
            StaticSchemaAuthority schemas
    ) {
        return TemplateModule.application(ownerScopes, persistence, schemas);
    }

    private static Set<String> parseCapabilities(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
