package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateDependencyEvaluatorTest {
    private static final OwnerScopeAuthority.OwnerScope OWNER =
            new OwnerScopeAuthority.OwnerScope("owner-a");
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String SELF = "00000000-0000-4000-8000-000000000001";

    @Test
    void dependencyProblemsAreStableOrderedAndSnapshotFactsAreDeduplicated() {
        var resolution = new ResolutionScript();
        resolution.assets.put("missing", new DependencyResolution.AssetMissing());
        resolution.assets.put("wrong-kind", new DependencyResolution.AssetResolved(
                asset("FONT", OWNER)));
        var projection = new TemplateDependencyProjection(
                List.of(
                        atom("missing", "imageRef", "/designRoot/children/2/imageRef"),
                        atom("wrong-kind", "imageRef", "/designRoot/children/1/imageRef"),
                        atom("missing", "imageRef", "/designRoot/children/0/imageRef")
                ),
                List.of()
        );

        var evaluated = evaluate(resolution, projection);

        assertEquals(TemplateDependencyEvaluator.Classification.DEPENDENCY_ERROR,
                evaluated.classification());
        assertFalse(evaluated.report().truncated());
        assertEquals(
                List.of(
                        "/designRoot/children/0/imageRef",
                        "/designRoot/children/1/imageRef",
                        "/designRoot/children/2/imageRef"
                ),
                evaluated.report().problems().stream()
                        .map(TemplateApplication.ValidationProblem::canonicalPointer)
                        .toList()
        );
        assertEquals(
                List.of(
                        "TEMPLATE_ASSET_NOT_FOUND",
                        "TEMPLATE_ASSET_KIND_MISMATCH",
                        "TEMPLATE_ASSET_NOT_FOUND"
                ),
                evaluated.report().problems().stream()
                        .map(TemplateApplication.ValidationProblem::code)
                        .toList()
        );
        assertEquals(2, evaluated.snapshot().assets().size());
        assertTrue(evaluated.snapshot().templates().isEmpty());
        assertTrue(evaluated.snapshot().fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void childNotReadyIsConfirmableButCrossScopeAndCycleAreHard() {
        var notReady = template("child-invalid", OWNER, TemplateApplication.Readiness.INVALID,
                List.of());
        var dependencyResolution = new ResolutionScript();
        dependencyResolution.templates.put(
                "child-invalid", new DependencyResolution.TemplateResolved(notReady));
        var dependency = evaluate(dependencyResolution,
                uses("child-invalid", "/designRoot/children/0/templateRef"));
        assertEquals(TemplateDependencyEvaluator.Classification.DEPENDENCY_ERROR,
                dependency.classification());
        assertEquals("TEMPLATE_CHILD_NOT_READY", dependency.report().problems().getFirst().code());

        var foreignResolution = new ResolutionScript();
        foreignResolution.templates.put(
                "foreign", new DependencyResolution.TemplateResolved(
                        new DependencyResolution.TemplateState(
                                "foreign",
                                new OwnerScopeAuthority.OwnerScope("owner-b"),
                                4,
                                DependencyResolution.Lifecycle.ACTIVE,
                                TemplateApplication.Readiness.READY,
                                SCHEMA,
                                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                List.of(),
                                "{}"
                        )));
        var foreign = evaluate(foreignResolution,
                uses("foreign", "/designRoot/children/1/templateRef"));
        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                foreign.classification());
        assertEquals("TEMPLATE_DEPENDENCY_SCOPE_MISMATCH",
                foreign.report().problems().getFirst().code());
        assertEquals(1, foreign.report().problems().size(),
                "foreign canonical content must never cross the owner-scope boundary");

        var cycleResolution = new ResolutionScript();
        cycleResolution.templates.put(
                "child", new DependencyResolution.TemplateResolved(template(
                        "child", OWNER, TemplateApplication.Readiness.READY,
                        List.of(new DependencyResolution.TemplateUseEdge(
                                SELF, "/designRoot/children/4/templateRef")))));
        var cycle = evaluate(cycleResolution,
                uses("child", "/designRoot/children/0/templateRef"));
        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                cycle.classification());
        assertEquals("TEMPLATE_REF_CYCLE",
                cycle.report().problems().getFirst().code());
    }

    @Test
    void problemLimitUsesOneFinalMarkerAndMakesTheResultUnconfirmable() {
        var resolution = new ResolutionScript();
        var atoms = new ArrayList<TemplateDependencyProjection.AssetRefAtom>();
        for (int index = 0; index < 500; index++) {
            var assetId = "missing-" + index;
            resolution.assets.put(assetId, new DependencyResolution.AssetMissing());
            atoms.add(atom(assetId, "imageRef", "/designRoot/children/" + index + "/imageRef"));
        }

        var evaluated = evaluate(resolution,
                new TemplateDependencyProjection(
                        atoms,
                        List.of(new TemplateDependencyProjection.TemplateUseOccurrence(
                                "should-not-resolve",
                                "00000000-0000-4000-8000-000000000099",
                                "/designRoot/children/500/templateRef"
                        ))
                ));

        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                evaluated.classification());
        assertTrue(evaluated.report().truncated());
        assertEquals(200, evaluated.report().problems().size());
        assertEquals("PROBLEM_LIMIT_REACHED", evaluated.report().problems().getLast().code());
        assertEquals("ITEMS", evaluated.report().problems().getLast().messageArgs().getFirst());
        assertFalse(evaluated.report().confirmable());
        assertEquals(201, resolution.assetResolutions);
        assertEquals(0, resolution.templateResolutions);
    }

    @Test
    void byteBudgetStopsFurtherDependencyResolutionBeforeTheItemLimit() {
        var resolution = new ResolutionScript();
        var atoms = new ArrayList<TemplateDependencyProjection.AssetRefAtom>();
        for (int index = 0; index < 500; index++) {
            var assetId = "large-pointer-missing-" + index;
            var prefix = "/designRoot/children/%03d/".formatted(index);
            resolution.assets.put(assetId, new DependencyResolution.AssetMissing());
            atoms.add(atom(
                    assetId,
                    "imageRef",
                    prefix + "p".repeat(2048 - prefix.length())
            ));
        }

        var evaluated = evaluate(resolution,
                new TemplateDependencyProjection(
                        atoms,
                        List.of(new TemplateDependencyProjection.TemplateUseOccurrence(
                                "should-not-resolve",
                                "00000000-0000-4000-8000-000000000099",
                                "/designRoot/children/500/templateRef"
                        ))
                ));

        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                evaluated.classification());
        assertTrue(evaluated.report().truncated());
        assertEquals("BYTES", evaluated.report().problems().getLast()
                .messageArgs().getFirst());
        assertTrue(resolution.assetResolutions < 201);
        assertEquals(0, resolution.templateResolutions);
    }

    @Test
    void snapshotFingerprintChangesWhenAnyDependencyCurrentFactChanges() {
        var firstResolution = new ResolutionScript();
        firstResolution.assets.put("asset", new DependencyResolution.AssetResolved(
                new DependencyResolution.AssetState(OWNER, "IMAGE",
                        DependencyResolution.Lifecycle.ACTIVE, 7, 3)));
        var secondResolution = new ResolutionScript();
        secondResolution.assets.put("asset", new DependencyResolution.AssetResolved(
                new DependencyResolution.AssetState(OWNER, "IMAGE",
                        DependencyResolution.Lifecycle.ACTIVE, 8, 3)));
        var projection = new TemplateDependencyProjection(
                List.of(atom("asset", "imageRef", "/designRoot/children/0/imageRef")),
                List.of());

        var first = evaluate(firstResolution, projection);
        var second = evaluate(secondResolution, projection);

        assertEquals(TemplateDependencyEvaluator.Classification.READY, first.classification());
        assertEquals(TemplateDependencyEvaluator.Classification.READY, second.classification());
        assertNotEquals(first.snapshot().fingerprint(), second.snapshot().fingerprint());
    }

    @Test
    void templateSnapshotCapacityAllowsExactlySixtyFourAndStopsBeforeSixtyFive() {
        var withinResolution = new ResolutionScript();
        var withinUses = templateUses(64, withinResolution);

        var within = evaluate(withinResolution,
                new TemplateDependencyProjection(List.of(), withinUses));

        assertEquals(TemplateDependencyEvaluator.Classification.READY, within.classification());
        assertEquals(64, within.snapshot().templates().size());
        assertEquals(64, withinResolution.templateResolutions);

        var overResolution = new ResolutionScript();
        var overUses = templateUses(65, overResolution);
        var over = evaluate(overResolution,
                new TemplateDependencyProjection(List.of(), overUses));

        assertEquals(TemplateDependencyEvaluator.Classification.HARD_ERROR,
                over.classification());
        assertEquals("TEMPLATE_DEPENDENCY_CLOSURE_LIMIT_REACHED",
                over.report().problems().getFirst().code());
        assertEquals(64, over.snapshot().templates().size());
        assertEquals(64, overResolution.templateResolutions);
    }

    private static List<TemplateDependencyProjection.TemplateUseOccurrence> templateUses(
            int count,
            ResolutionScript resolution
    ) {
        var uses = new ArrayList<TemplateDependencyProjection.TemplateUseOccurrence>();
        for (int index = 0; index < count; index++) {
            var target = "child-" + index;
            resolution.templates.put(target, new DependencyResolution.TemplateResolved(
                    template(target, OWNER, TemplateApplication.Readiness.READY, List.of())
            ));
            uses.add(new TemplateDependencyProjection.TemplateUseOccurrence(
                    target,
                    "use-" + index,
                    "/designRoot/children/" + index + "/templateRef"
            ));
        }
        return uses;
    }

    private static TemplateDependencyEvaluator.Evaluation evaluate(
            DependencyResolution resolution,
            TemplateDependencyProjection projection
    ) {
        var canonical = TemplateTestData.emptyDesignCanonical()
                .getBytes(StandardCharsets.UTF_8);
        return new TemplateDependencyEvaluator(
                resolution,
                TemplateTestData::resolvedEmpty,
                new CanonicalDesignDslAuthority()
        ).evaluate(projection, canonical, SCHEMA, SELF, OWNER);
    }

    private static TemplateDependencyProjection.AssetRefAtom atom(
            String assetId,
            String kind,
            String pointer
    ) {
        return new TemplateDependencyProjection.AssetRefAtom(assetId, kind, pointer);
    }

    private static TemplateDependencyProjection uses(String target, String pointer) {
        return new TemplateDependencyProjection(
                List.of(),
                List.of(new TemplateDependencyProjection.TemplateUseOccurrence(
                        target, "00000000-0000-4000-8000-000000000099", pointer))
        );
    }

    private static DependencyResolution.AssetState asset(
            String kind,
            OwnerScopeAuthority.OwnerScope ownerScope
    ) {
        return new DependencyResolution.AssetState(
                ownerScope, kind, DependencyResolution.Lifecycle.ACTIVE, 0, 0);
    }

    private static DependencyResolution.TemplateState template(
            String templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            TemplateApplication.Readiness readiness,
            List<DependencyResolution.TemplateUseEdge> uses
    ) {
        return new DependencyResolution.TemplateState(
                templateId,
                ownerScope,
                4,
                DependencyResolution.Lifecycle.ACTIVE,
                readiness,
                SCHEMA,
                TemplateTestData.emptyDesignContentHash(),
                uses,
                TemplateTestData.emptyDesignCanonical()
        );
    }

    private static final class ResolutionScript implements DependencyResolution {
        private final Map<String, AssetResolution> assets = new HashMap<>();
        private final Map<String, TemplateResolution> templates = new HashMap<>();
        private int assetResolutions;
        private int templateResolutions;

        @Override
        public AssetResolution resolveAsset(String assetId) {
            assetResolutions++;
            return assets.getOrDefault(assetId, new AssetMissing());
        }

        @Override
        public TemplateResolution resolveTemplate(String targetTemplateId) {
            templateResolutions++;
            return templates.getOrDefault(targetTemplateId, new TemplateMissing());
        }
    }
}
