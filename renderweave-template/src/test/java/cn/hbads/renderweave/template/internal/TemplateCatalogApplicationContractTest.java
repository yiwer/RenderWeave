package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplateDependencySnapshot;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TemplateCatalogApplicationContractTest {
    private static final TemplateApplication.TemplateInvocationRef INVOCATION =
            TemplateApplication.TemplateInvocationRef.serverCreated("catalog-contract");
    private static final OwnerScopeAuthority.OwnerScope OWNER =
            new OwnerScopeAuthority.OwnerScope("owner-a");
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1")
    );

    @Test
    void catalogMapsOnlySafeFieldsAfterExactOwnerAuthorization() {
        var stored = new TemplatePersistence.CatalogEntry(
                TemplateApplication.TemplateId.of("11111111-1111-4111-8111-111111111111"),
                OWNER,
                SCHEMA,
                7,
                TemplatePersistence.Lifecycle.ACTIVE,
                "门店价签",
                TemplateApplication.Readiness.READY,
                Instant.parse("2026-08-25T07:00:00Z")
        );
        var persistence = new CatalogPersistence(
                new TemplatePersistence.CatalogPage(List.of(stored), Optional.of("next"))
        );
        var application = application(
                new CatalogAuthority(new OwnerScopeAuthority.CatalogGranted(OWNER)),
                persistence
        );

        var outcome = application.catalog(
                INVOCATION,
                new TemplateApplication.CatalogCommand("  价签  ", null, 20)
        );

        var page = assertInstanceOf(TemplateApplication.CatalogPage.class, outcome);
        assertEquals(Optional.of("next"), page.nextCursor());
        assertEquals(1, page.entries().size());
        var entry = page.entries().getFirst();
        assertEquals(stored.templateId(), entry.templateId());
        assertEquals(stored.displayName(), entry.displayName());
        assertEquals(stored.staticSchema(), entry.staticSchema());
        assertEquals(stored.currentRevision(), entry.revision());
        assertEquals(stored.readiness(), entry.readiness());
        assertEquals(stored.updatedAt(), entry.updatedAt());
        assertEquals(new TemplatePersistence.CatalogQuery(OWNER, "价签", null, 20),
                persistence.query);
    }

    @Test
    void catalogFailsClosedWhenPersistenceLeaksCrossOwnerOrDeletedRows() {
        for (var leaked : List.of(
                entry(new OwnerScopeAuthority.OwnerScope("owner-b"),
                        TemplatePersistence.Lifecycle.ACTIVE),
                entry(OWNER, TemplatePersistence.Lifecycle.DELETED)
        )) {
            var application = application(
                    new CatalogAuthority(new OwnerScopeAuthority.CatalogGranted(OWNER)),
                    new CatalogPersistence(new TemplatePersistence.CatalogPage(
                            List.of(leaked),
                            Optional.empty()
                    ))
            );

            assertInstanceOf(
                    TemplateApplication.CatalogPersistenceUnavailable.class,
                    application.catalog(INVOCATION, new TemplateApplication.CatalogCommand(
                            null,
                            null,
                            20
                    ))
            );
        }
    }

    @Test
    void catalogDoesNotConsultPersistenceWhenReadCapabilityIsDeniedOrUnavailable() {
        for (var decision : List.<OwnerScopeAuthority.CatalogDecision>of(
                new OwnerScopeAuthority.CatalogDenied(),
                new OwnerScopeAuthority.CatalogUnavailable()
        )) {
            var persistence = new CatalogPersistence(new AssertionError("must not catalog"));
            var outcome = application(new CatalogAuthority(decision), persistence).catalog(
                    INVOCATION,
                    new TemplateApplication.CatalogCommand(null, null, 20)
            );
            if (decision instanceof OwnerScopeAuthority.CatalogDenied) {
                assertInstanceOf(TemplateApplication.CatalogForbidden.class, outcome);
            } else {
                assertInstanceOf(TemplateApplication.CatalogAuthorityUnavailable.class, outcome);
            }
        }
    }

    private static TemplatePersistence.CatalogEntry entry(
            OwnerScopeAuthority.OwnerScope owner,
            TemplatePersistence.Lifecycle lifecycle
    ) {
        return new TemplatePersistence.CatalogEntry(
                TemplateApplication.TemplateId.of("22222222-2222-4222-8222-222222222222"),
                owner,
                SCHEMA,
                0,
                lifecycle,
                "Leaked",
                TemplateApplication.Readiness.READY,
                Instant.parse("2026-08-25T07:00:00Z")
        );
    }

    private static TemplateApplication application(
            OwnerScopeAuthority authority,
            TemplatePersistence persistence
    ) {
        StaticSchemaAuthority schemas = reference -> {
            throw new AssertionError("catalog must not resolve StaticSchema content");
        };
        DependencyResolution dependencies = new DependencyResolution() {
            @Override
            public AssetResolution resolveAsset(String assetId) {
                throw new AssertionError("catalog must not resolve Assets");
            }

            @Override
            public TemplateResolution resolveTemplate(String targetTemplateId) {
                throw new AssertionError("catalog must not resolve Template dependencies");
            }
        };
        return TemplateModule.application(authority, persistence, schemas, dependencies);
    }

    private static final class CatalogAuthority implements OwnerScopeAuthority {
        private final CatalogDecision decision;

        private CatalogAuthority(CatalogDecision decision) {
            this.decision = decision;
        }

        @Override
        public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
            throw new AssertionError("unexpected create authorization");
        }

        @Override
        public CatalogDecision authorizeCatalog(
                TemplateApplication.TemplateInvocationRef invocation
        ) {
            assertEquals(INVOCATION, invocation);
            return decision;
        }

        @Override
        public ExistingDecision authorizeExisting(
                TemplateApplication.TemplateInvocationRef invocation,
                OwnerScope storedOwnerScope,
                ExistingOperation operation
        ) {
            throw new AssertionError("unexpected existing authorization");
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            throw new AssertionError("unexpected recheck");
        }
    }

    private static final class CatalogPersistence implements TemplatePersistence {
        private final CatalogOutcome outcome;
        private final AssertionError failure;
        private CatalogQuery query;

        private CatalogPersistence(CatalogOutcome outcome) {
            this.outcome = outcome;
            this.failure = null;
        }

        private CatalogPersistence(AssertionError failure) {
            this.outcome = null;
            this.failure = failure;
        }

        @Override
        public CatalogOutcome catalog(CatalogQuery query) {
            if (failure != null) {
                throw failure;
            }
            this.query = query;
            return outcome;
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected locate");
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected current load");
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            throw new AssertionError("unexpected create");
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("unexpected append");
        }

        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected TemplateUse lookup");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected Asset lookup");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness,
                TemplateDependencySnapshot dependencySnapshot
        ) {
            throw new AssertionError("unexpected readiness update");
        }
    }
}
