package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TemplateApplicationConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TemplateApplicationConfiguration.class)
            .withBean(TemplatePersistence.class, () -> mock(TemplatePersistence.class))
            .withBean(StaticSchemaAuthority.class, () -> mock(StaticSchemaAuthority.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void missingHostConfigurationSelectsTheFailClosedAuthority() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OwnerScopeAuthority.class);
            assertThat(context).hasSingleBean(TemplateApplication.class);
            assertThat(context.getBean(OwnerScopeAuthority.class))
                    .isInstanceOf(FailClosedOwnerScopeAuthority.class);
        });
    }

    @Test
    void explicitSingleOwnerConfigurationSelectsOnlyTheDevelopmentAdapter() {
        contextRunner
                .withPropertyValues(
                        "renderweave.template.single-owner.enabled=true",
                        "renderweave.template.single-owner.owner-scope=assembly-owner",
                        "renderweave.template.single-owner.capabilities="
                                + "template.create,template.read,template.update"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OwnerScopeAuthority.class);
                    assertThat(context.getBean(OwnerScopeAuthority.class))
                            .isInstanceOf(ConfiguredSingleOwnerScopeAuthority.class);
                });
    }
}
