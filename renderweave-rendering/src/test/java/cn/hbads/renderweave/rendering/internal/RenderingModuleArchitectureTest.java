package cn.hbads.renderweave.rendering.internal;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RenderingModuleArchitectureTest {

    private static final String ROOT = "cn.hbads.renderweave.rendering..";
    private static final String API = "cn.hbads.renderweave.rendering.api..";
    private static final String INTERNAL = "cn.hbads.renderweave.rendering.internal..";
    private static final String SPI = "cn.hbads.renderweave.rendering.spi..";
    private static final String ASSEMBLY = "cn.hbads.renderweave.rendering.internal.RenderingModule";

    private final com.tngtech.archunit.core.domain.JavaClasses production =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("cn.hbads.renderweave.rendering");

    @Test
    void publicApiAnchorIsRealAndNonempty() {
        classes()
                .that().resideInAPackage(API)
                .should().bePublic()
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void outboundSpiAnchorIsRealAndCannotReachInternalImplementation() {
        classes()
                .that().resideInAPackage(SPI)
                .should().bePublic()
                .allowEmptyShould(false)
                .check(production);
        noClasses()
                .that().resideInAPackage(SPI)
                .should().dependOnClassesThat().resideInAPackage(INTERNAL)
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void internalImplementationAnchorIsRealAndNotPublic() {
        noClasses()
                .that().resideInAPackage(INTERNAL)
                .and().areTopLevelClasses()
                .and().doNotHaveFullyQualifiedName(ASSEMBLY)
                .should().bePublic()
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void publicApiCannotReachInternalOrOutboundPackages() {
        noClasses()
                .that().resideInAPackage(API)
                .should().dependOnClassesThat().resideInAnyPackage(INTERNAL, SPI)
                .allowEmptyShould(false)
                .check(production);
    }

    @Test
    void renderingDomainCannotReachAdaptersFrameworksPersistenceOrNativeProcessSeams() {
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
                        "jnr.ffi..",
                        "software.amazon.awssdk.."
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
    void everyPublicProductionTypeRemainsInAnOwnedContractPackage() {
        classes()
                .that().resideInAPackage(ROOT)
                .and().areTopLevelClasses()
                .and().arePublic()
                .and().doNotHaveFullyQualifiedName(ASSEMBLY)
                .should().resideInAnyPackage(API, SPI)
                .allowEmptyShould(false)
                .check(production);
    }
}
