package cn.hbads.renderweave.template.internal;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class TemplateModuleArchitectureTest {

    private static final String ROOT = "cn.hbads.renderweave.template..";
    private static final String API = "cn.hbads.renderweave.template.api..";
    private static final String INTERNAL = "cn.hbads.renderweave.template.internal..";
    private static final String SPI = "cn.hbads.renderweave.template.spi..";

    private final com.tngtech.archunit.core.domain.JavaClasses production =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("cn.hbads.renderweave.template");

    @Test
    void publicApiAnchorIsRealAndNonempty() {
        classes()
                .that().resideInAPackage(API)
                .should().bePublic()
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void internalImplementationAnchorIsRealAndNotPublic() {
        noClasses()
                .that().resideInAPackage(INTERNAL)
                .and().areTopLevelClasses()
                .should().bePublic()
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void publicApiCannotReachInternalOrFutureOutboundPackages() {
        noClasses()
                .that().resideInAPackage(API)
                .should().dependOnClassesThat().resideInAnyPackage(INTERNAL, SPI)
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void templateDomainCannotReachAdaptersFrameworksPersistenceOrNativeProcessSeams() {
        noClasses()
                .that().resideInAPackage(ROOT)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "cn.hbads.renderweave.app..",
                        "org.springframework..",
                        "jakarta.servlet..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "java.sql..",
                        "javax.sql..",
                        "java.lang.foreign..",
                        "com.sun.jna..",
                        "jnr.ffi.."
                )
                .allowEmptyShould(false)
                .check(production);
        noClasses()
                .that().resideInAPackage(ROOT)
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void everyPublicProductionTypeRemainsInTheOwnedApiPackage() {
        classes()
                .that().resideInAPackage(ROOT)
                .and().areTopLevelClasses()
                .and().arePublic()
                .should().resideInAPackage(API)
                .allowEmptyShould(false)
                .check(production);
    }
}
