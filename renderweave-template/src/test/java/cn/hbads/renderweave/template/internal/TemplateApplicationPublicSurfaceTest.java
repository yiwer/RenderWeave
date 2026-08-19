package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateApplicationPublicSurfaceTest {

    @Test
    void authoringInterfaceContainsOnlyTheThreeTicketSixBehaviors() {
        assertEquals(
                Set.of("create", "getCurrent", "save"),
                methodNames(TemplateApplication.class)
        );
        assertTrue(TemplateApplication.CreateOutcome.class.isSealed());
        assertTrue(TemplateApplication.CurrentOutcome.class.isSealed());
        assertTrue(TemplateApplication.SaveOutcome.class.isSealed());

        var commandSurface = Set.of(
                TemplateApplication.CreateCommand.class,
                TemplateApplication.SaveCommand.class
        ).stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
        assertFalse(commandSurface.contains("ownerScope"));
        assertFalse(commandSurface.contains("capabilities"));
        assertFalse(commandSurface.contains("role"));
        assertFalse(commandSurface.contains("authorized"));
        assertFalse(commandSurface.contains("staticSchemaRef"));
    }

    @Test
    void outboundInterfacesAreTransactionSizedAndContainNoMutationBypass() {
        assertEquals(
                Set.of("locate", "loadCurrent", "create", "append"),
                methodNames(TemplatePersistence.class)
        );
        assertEquals(
                Set.of("authorizeCreate", "authorizeExisting", "recheck"),
                methodNames(OwnerScopeAuthority.class)
        );
        var allPersistenceNames = Arrays.stream(TemplatePersistence.class.getDeclaredClasses())
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
        assertFalse(allPersistenceNames.contains("update"));
        assertFalse(allPersistenceNames.contains("delete"));
        assertFalse(allPersistenceNames.contains("purge"));
        assertFalse(allPersistenceNames.contains("rebind"));
        assertFalse(allPersistenceNames.contains("executeSql"));
    }

    @Test
    void exactAssemblyExceptionExposesOnlyTheStaticApplicationFactory() throws Exception {
        assertTrue(Modifier.isFinal(TemplateModule.class.getModifiers()));
        assertEquals(Set.of("application"), methodNames(TemplateModule.class));
        var application = TemplateModule.class.getDeclaredMethod(
                "application",
                OwnerScopeAuthority.class,
                TemplatePersistence.class,
                StaticSchemaAuthority.class
        );
        assertTrue(Modifier.isPublic(application.getModifiers()));
        assertTrue(Modifier.isStatic(application.getModifiers()));
        assertEquals(TemplateApplication.class, application.getReturnType());
        assertEquals(
                List.of(
                        OwnerScopeAuthority.class,
                        TemplatePersistence.class,
                        StaticSchemaAuthority.class
                ),
                Arrays.asList(application.getParameterTypes())
        );
        assertEquals(1, TemplateModule.class.getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(
                TemplateModule.class.getDeclaredConstructors()[0].getModifiers()
        ));
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
