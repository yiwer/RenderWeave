package cn.hbads.renderweave.asset.internal;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AssetModuleArchitectureTest {

    private static final String ROOT = "cn.hbads.renderweave.asset..";
    private static final String API = "cn.hbads.renderweave.asset.api..";
    private static final String INTERNAL = "cn.hbads.renderweave.asset.internal..";
    private static final String SPI = "cn.hbads.renderweave.asset.spi..";
    private static final String ASSEMBLY = "cn.hbads.renderweave.asset.internal.AssetModule";

    private final com.tngtech.archunit.core.domain.JavaClasses production =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("cn.hbads.renderweave.asset");

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
    void assetDomainCannotReachAdaptersFrameworksPersistenceOrNativeProcessSeams() {
        noClasses()
                .that().resideInAPackage(ROOT)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "cn.hbads.renderweave.app..",
                        "cn.hbads.renderweave.template..",
                        "cn.hbads.renderweave.rendering..",
                        "org.springframework..",
                        "jakarta.servlet..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "java.sql..",
                        "javax.sql..",
                        "software.amazon.awssdk..",
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
