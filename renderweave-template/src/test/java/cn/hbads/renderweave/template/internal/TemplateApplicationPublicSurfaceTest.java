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
    void authoringInterfaceContainsOnlyTheFourRegisteredBehaviors() {
        assertEquals(
                Set.of("create", "getCurrent", "recheckCurrent", "save"),
                methodNames(TemplateApplication.class)
        );
        assertTrue(TemplateApplication.CreateOutcome.class.isSealed());
        assertTrue(TemplateApplication.CurrentOutcome.class.isSealed());
        assertTrue(TemplateApplication.RecheckCurrentOutcome.class.isSealed());
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
                Set.of(
                        "locate",
                        "loadCurrent",
                        "create",
                        "append",
                        "loadUseTargets",
                        "findAssetReferences",
                        "updateReadiness"
                ),
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
        // No generic repository verbs: updateReadiness is a narrow recheck projection op,
        // never a generic update/delete/purge/rebind/executeSql escape hatch.
        assertFalse(allPersistenceNames.contains("update"));
        assertFalse(allPersistenceNames.contains("delete"));
        assertFalse(allPersistenceNames.contains("purge"));
        assertFalse(allPersistenceNames.contains("rebind"));
        assertFalse(allPersistenceNames.contains("executeSql"));
        assertEquals(
                Set.of("checkAsset", "checkTemplateUse"),
                methodNames(cn.hbads.renderweave.template.spi.DependencyResolution.class)
        );
    }

    @Test
    void exactAssemblyExceptionExposesOnlyTheStaticFactories() throws Exception {
        assertTrue(Modifier.isFinal(TemplateModule.class.getModifiers()));
        assertEquals(
                Set.of("application", "assetReferenceAuthority", "closureAuthority",
                        "designDslAuthority", "designSemanticAuthority", "readinessAuthority"),
                methodNames(TemplateModule.class)
        );
        var application = TemplateModule.class.getDeclaredMethod(
                "application",
                OwnerScopeAuthority.class,
                TemplatePersistence.class,
                StaticSchemaAuthority.class,
                cn.hbads.renderweave.template.spi.DependencyResolution.class
        );
        assertTrue(Modifier.isPublic(application.getModifiers()));
        assertTrue(Modifier.isStatic(application.getModifiers()));
        assertEquals(TemplateApplication.class, application.getReturnType());
        assertEquals(
                List.of(
                        OwnerScopeAuthority.class,
                        TemplatePersistence.class,
                        StaticSchemaAuthority.class,
                        cn.hbads.renderweave.template.spi.DependencyResolution.class
                ),
                Arrays.asList(application.getParameterTypes())
        );
        var closureAuthority = TemplateModule.class.getDeclaredMethod(
                "closureAuthority",
                TemplatePersistence.class
        );
        assertTrue(Modifier.isPublic(closureAuthority.getModifiers()));
        assertTrue(Modifier.isStatic(closureAuthority.getModifiers()));
        assertEquals(
                cn.hbads.renderweave.template.api.TemplateClosureAuthority.class,
                closureAuthority.getReturnType()
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
