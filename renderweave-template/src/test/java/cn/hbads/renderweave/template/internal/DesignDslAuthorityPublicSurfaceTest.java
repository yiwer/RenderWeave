package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignDslAuthorityPublicSurfaceTest {

    @Test
    void exposesOnlyTheClosedTemplateTopLevelContracts() {
        var production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("cn.hbads.renderweave.template");
        var publicTopLevel = production.stream()
                .filter(javaClass -> javaClass.getEnclosingClass().isEmpty())
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .map(javaClass -> javaClass.getName())
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of(
                DesignDslAuthority.class.getName(),
                DesignInputExpressionCapacityAuthority.class.getName(),
                "cn.hbads.renderweave.template.api.TemplateApplication",
                "cn.hbads.renderweave.template.api.TemplateDependencyProjection",
                "cn.hbads.renderweave.template.api.AssetReferenceAuthority",
                "cn.hbads.renderweave.template.api.TemplateReadinessAuthority",
                "cn.hbads.renderweave.template.api.TemplateClosureAuthority",
                "cn.hbads.renderweave.template.api.DesignSemanticAuthority",
                "cn.hbads.renderweave.template.spi.OwnerScopeAuthority",
                "cn.hbads.renderweave.template.spi.TemplatePersistence",
                "cn.hbads.renderweave.template.spi.DependencyResolution",
                "cn.hbads.renderweave.template.spi.TemplateDependencySnapshot",
                "cn.hbads.renderweave.template.spi.InvalidCommitConfirmationAuthority",
                "cn.hbads.renderweave.template.internal.TemplateModule"
        ), publicTopLevel);
    }

    @Test
    void freezesTheSingleBehavioralMethodAndClosedOutcomeTypes() {
        var methods = DesignDslAuthority.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        var admit = methods[0];
        assertEquals("admit", admit.getName());
        assertEquals(DesignDslAuthority.Admission.class, admit.getReturnType());
        assertArrayEquals(new Class<?>[]{byte[].class}, admit.getParameterTypes());
        assertTrue(Modifier.isPublic(admit.getModifiers()));
        assertTrue(Modifier.isAbstract(admit.getModifiers()));

        assertTrue(DesignDslAuthority.Admission.class.isSealed());
        assertEquals(
                Set.of(
                        DesignDslAuthority.Admitted.class,
                        DesignDslAuthority.Rejected.class
                ),
                Set.of(DesignDslAuthority.Admission.class.getPermittedSubclasses())
        );
        assertEquals(
                Set.of(
                        "Admission",
                        "Admitted",
                        "Rejected",
                        "FailureCode",
                        "FailureStage",
                        "Limit"
                ),
                Arrays.stream(DesignDslAuthority.class.getDeclaredClasses())
                        .map(Class::getSimpleName)
                        .collect(Collectors.toUnmodifiableSet())
        );
    }

    @Test
    void freezesFailureCodesStagesLimitsAndRejectedRecordShape() {
        assertArrayEquals(
                new DesignDslAuthority.FailureCode[]{
                        DesignDslAuthority.FailureCode.DESIGN_UTF8_INVALID,
                        DesignDslAuthority.FailureCode.DESIGN_JSON_INVALID,
                        DesignDslAuthority.FailureCode.DESIGN_DUPLICATE_MEMBER,
                        DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                        DesignDslAuthority.FailureCode.DESIGN_VERSION_UNSUPPORTED,
                        DesignDslAuthority.FailureCode.DESIGN_MEMBER_UNKNOWN,
                        DesignDslAuthority.FailureCode.DESIGN_STRUCTURE_INVALID,
                        DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID,
                        DesignDslAuthority.FailureCode.DESIGN_PROPERTY_CONSTRAINT_INVALID,
                        DesignDslAuthority.FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED
                },
                DesignDslAuthority.FailureCode.values()
        );
        assertArrayEquals(
                new DesignDslAuthority.FailureStage[]{
                        DesignDslAuthority.FailureStage.DESIGN_PARSE,
                        DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                        DesignDslAuthority.FailureStage.DESIGN_PROPERTY_AND_AGGREGATE_VALIDATION,
                        DesignDslAuthority.FailureStage.DESIGN_CANONICAL_COUNT
                },
                DesignDslAuthority.FailureStage.values()
        );
        assertArrayEquals(
                new String[]{
                        "designDslParser.rawUtf8Bytes",
                        "designDslParser.canonicalBytes",
                        "designDslParser.jsonDepth",
                        "designDslParser.objectMembers",
                        "designDslParser.arrayItems",
                        "designDslParser.totalValuesAndContainers",
                        "designDslParser.stringUtf8Bytes",
                        "designDslParser.memberNameUtf8Bytes",
                        "designDslParser.numberTokenBytes",
                        "designDslSemantics.authoredNodes",
                        "designDslSemantics.authoredTreeDepth",
                        "designDslSemantics.childrenPerContainer",
                        "designDslSemantics.definitions",
                        "designDslSemantics.bindingsTotal",
                        "designDslSemantics.bindingsPerNode",
                        "designDslSemantics.runsPerTextNode",
                        "designDslSemantics.runsTotal",
                        "designDslSemantics.gridTracksPerAxis",
                        "designDslSemantics.vectorEntriesPerNode",
                        "designDslSemantics.vectorEntriesTotal",
                        "designDslSemantics.fillsPerTemplateUse",
                        "designDslSemantics.literalListItemsPerList",
                        "designDslSemantics.literalListItemsTotal",
                        "designDslSemantics.authoredRunTextScalars",
                        "expression.sourceUtf8BytesPerExpression",
                        "expression.sourceUtf8BytesTotal",
                        "expression.inputsPerExpression",
                        "expression.inputsTotal",
                        "expression.mappingCasesPerDefinition",
                        "expression.mappingCasesTotal",
                        "expression.astNodesPerExpression",
                        "expression.astNodesTotal",
                        "expression.definitionGraphEdges",
                        "expression.definitionChainDepth",
                        "expression.admittedDecimalPrecisionDigits",
                        "expression.admittedDecimalScaleMin",
                        "expression.admittedDecimalScaleMax",
                        "expression.intermediateDecimalPrecisionDigits",
                        "expression.intermediateDecimalScaleMin",
                        "expression.intermediateDecimalScaleMax",
                        "expression.explicitRoundingScaleMax",
                        "geometry.canvasTrimMmPerAxisExclusiveMin",
                        "geometry.canvasTrimMmPerAxisMax"
                },
                Arrays.stream(DesignDslAuthority.Limit.values())
                        .map(DesignDslAuthority.Limit::id)
                        .toArray(String[]::new)
        );

        assertTrue(DesignDslAuthority.Rejected.class.isRecord());
        var components = DesignDslAuthority.Rejected.class.getRecordComponents();
        assertArrayEquals(
                new String[]{"code", "stage", "pointer", "limit"},
                Arrays.stream(components).map(component -> component.getName()).toArray(String[]::new)
        );
        assertArrayEquals(
                new Class<?>[]{
                        DesignDslAuthority.FailureCode.class,
                        DesignDslAuthority.FailureStage.class,
                        String.class,
                        Optional.class
                },
                Arrays.stream(components).map(component -> component.getType()).toArray(Class<?>[]::new)
        );
        assertFalse(DesignDslAuthority.Admitted.class.isRecord());
        assertTrue(Modifier.isFinal(DesignDslAuthority.Admitted.class.getModifiers()));
    }
}
