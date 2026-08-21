package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CanonicalTemplateReadinessAuthorityTest {
    private static final byte[] DESIGN = """
            {
              "dslVersion":"renderweave-design/1.0",
              "expressionProfile":"renderweave-expression/1.0",
              "displayName":"Readiness test",
              "definitions":[],
              "designRoot":{
                "nodeId":"123e4567-e89b-42d3-a456-426614174000",
                "kind":"canvas",
                "widthMm":210,
                "heightMm":297,
                "bindings":[],
                "children":[]
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void repeatedRevisionDriftStopsAfterThreeCompleteAttempts() {
        var templateId = TemplateApplication.TemplateId.of(
                "3f3aa31e-32e8-4c6e-a249-b622e41743a1"
        );
        var admitted = assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(DESIGN)
        );
        var persistence = new DriftingPersistence(templateId, admitted);
        var authority = new CanonicalTemplateReadinessAuthority(
                persistence,
                new DependencyResolution() {
                    @Override
                    public AssetResolution resolveAsset(String assetId) {
                        throw new AssertionError("dependency-free design must not check assets");
                    }

                    @Override
                    public TemplateResolution resolveTemplate(String targetTemplateId) {
                        throw new AssertionError("dependency-free design must not check templates");
                    }
                },
                TemplateTestData::resolvedEmpty,
                new CanonicalDesignDslAuthority()
        );

        assertInstanceOf(
                TemplateReadinessAuthority.RecheckUnavailable.class,
                authority.recheck(templateId)
        );
        assertEquals(3, persistence.locates);
        assertEquals(3, persistence.loads);
        assertEquals(3, persistence.updates);
    }

    private static final class DriftingPersistence implements TemplatePersistence {
        private final TemplateApplication.TemplateId templateId;
        private final StoredCurrent current;
        private int locates;
        private int loads;
        private int updates;

        private DriftingPersistence(
                TemplateApplication.TemplateId templateId,
                DesignDslAuthority.Admitted admitted
        ) {
            this.templateId = templateId;
            var metadata = new TemplateMetadata(
                    templateId,
                    new OwnerScopeAuthority.OwnerScope("readiness-test-owner"),
                    new StaticSchemaRef(
                            SchemaKey.systemProvided("system-empty"),
                            VersionTag.of("v1")
                    ),
                    7,
                    Lifecycle.ACTIVE
            );
            this.current = new StoredCurrent(
                    metadata,
                    DESIGN,
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    TemplateApplication.Readiness.STALE
            );
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId actualTemplateId) {
            assertEquals(templateId, actualTemplateId);
            locates++;
            return new Located(current.metadata());
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId actualTemplateId) {
            assertEquals(templateId, actualTemplateId);
            loads++;
            return new CurrentLoaded(current);
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId actualTemplateId,
                long currentRevision,
                TemplateApplication.Readiness readiness,
                cn.hbads.renderweave.template.spi.TemplateDependencySnapshot dependencySnapshot
        ) {
            assertEquals(templateId, actualTemplateId);
            assertEquals(7, currentRevision);
            assertEquals(TemplateApplication.Readiness.READY, readiness);
            updates++;
            return updates <= 3
                    ? new ReadinessRevisionConflict()
                    : new ReadinessUnavailable();
        }

        @Override
        public LoadUseTargetsOutcome loadUseTargets(
                TemplateApplication.TemplateId actualTemplateId
        ) {
            assertEquals(templateId, actualTemplateId);
            return new UseTargetsLoaded(List.of());
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            throw new AssertionError("unexpected create");
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("unexpected append");
        }
    }
}
