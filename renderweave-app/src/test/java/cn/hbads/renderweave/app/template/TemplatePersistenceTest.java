package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplateDependencySnapshot;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.template.single-owner.enabled=true",
        "renderweave.template.single-owner.owner-scope=test-owner",
        "renderweave.template.single-owner.capabilities=template.create,template.read,template.update"
})
class TemplatePersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final StaticSchemaRef SYSTEM_EMPTY = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1")
    );
    private static final byte[] DESIGN = """
            {"dslVersion":"renderweave-design/1.0",
             "expressionProfile":"renderweave-expression/1.0",
             "displayName":"Persistent template",
             "definitions":[],
             "designRoot":{"nodeId":"123e4567-e89b-42d3-a456-426614174000",
               "kind":"canvas","widthMm":210,"heightMm":297,
               "bindings":[],"children":[]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final String FENCED_ASSET = "00000000-0000-4000-8000-0000000000f1";
    private static final byte[] ASSET_DESIGN = ("""
            {"dslVersion":"renderweave-design/1.0",
             "expressionProfile":"renderweave-expression/1.0",
             "displayName":"Snapshot fence",
             "definitions":[],
             "designRoot":{"nodeId":"123e4567-e89b-42d3-a456-426614174000",
               "kind":"canvas","widthMm":210,"heightMm":297,"bindings":[],
               "children":[{"nodeId":"00000000-0000-4000-8000-0000000000f2",
                 "kind":"image","bindings":[],
                 "placement":{"type":"ABSOLUTE","xMm":0,"yMm":0,
                   "widthMode":"FIXED","widthMm":10,
                   "heightMode":"FIXED","heightMm":10},
                 "imageRef":{"assetId":"%s"}}]}}
            """).formatted(FENCED_ASSET).getBytes(StandardCharsets.UTF_8);

    @Autowired
    private TemplateApplication templates;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TemplatePersistence persistence;

    @Autowired
    private DesignDslAuthority designs;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTemplates() {
        jdbc.sql("""
                truncate table template_use_reference,
                                 template_asset_reference,
                                 template_invalid_commit_confirmation,
                                 template_revision,
                                 template_aggregate,
                                 asset_content_revision,
                                 asset_aggregate
                cascade
                """).update();
    }

    @Test
    void createReadAndSameHashSaveUseImmutablePostgresRevisions() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("pg-request-1");
        var created = (TemplateApplication.CreatedReadable) templates.create(
                invocation,
                new TemplateApplication.CreateCommand(SYSTEM_EMPTY, DESIGN)
        );

        assertThat(created.current().revision()).isZero();
        var saved = (TemplateApplication.SavedReadable) templates.save(
                invocation,
                new TemplateApplication.SaveCommand(created.current().templateId(), 0, DESIGN)
        );
        var read = (TemplateApplication.CurrentReadable) templates.getCurrent(
                invocation,
                created.current().templateId()
        );

        assertThat(saved.current().revision()).isEqualTo(1);
        assertThat(saved.current().contentHash()).isEqualTo(created.current().contentHash());
        assertThat(read.current().revision()).isEqualTo(1);
        assertThat(read.current().canonicalDesignDslUtf8())
                .isEqualTo(saved.current().canonicalDesignDslUtf8());
        assertThat(jdbc.sql("select count(*) from template_aggregate")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from template_revision")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select current_revision from template_aggregate")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select owner_scope || ':' || schema_key || '@' || schema_version_tag
                        from template_aggregate
                        """).query(String.class).single())
                .isEqualTo("test-owner:system-empty@v1");
    }

    @Test
    void concurrentSavesWithOneExpectedRevisionHaveExactlyOneWinner() throws Exception {
        var created = create("pg-concurrent-create");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        List<TemplateApplication.SaveOutcome> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> saveAfterBarrier(
                    created.current().templateId(), "pg-concurrent-a", ready, start
            ));
            var second = executor.submit(() -> saveAfterBarrier(
                    created.current().templateId(), "pg-concurrent-b", ready, start
            ));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }

        assertThat(outcomes.stream().filter(TemplateApplication.SavedReadable.class::isInstance))
                .as("concurrent save outcomes: %s", outcomes)
                .hasSize(1);
        assertThat(outcomes.stream().filter(TemplateApplication.SaveRevisionConflict.class::isInstance))
                .as("concurrent save outcomes: %s", outcomes)
                .singleElement()
                .satisfies(outcome -> assertThat(
                        ((TemplateApplication.SaveRevisionConflict) outcome)
                                .currentRevision()
                                .orElseThrow()
                ).isEqualTo(1));
        assertThat(jdbc.sql("select current_revision from template_aggregate")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from template_revision")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void missingStaticSchemaAndRevisionInsertFaultLeaveNoTemplateRows() {
        var missing = new StaticSchemaRef(
                SchemaKey.userProvided("missing-template-schema"),
                VersionTag.of("v1")
        );
        var missingOutcome = templates.create(
                TemplateApplication.TemplateInvocationRef.serverCreated("pg-missing-schema"),
                new TemplateApplication.CreateCommand(missing, DESIGN)
        );

        assertThat(missingOutcome)
                .isInstanceOf(TemplateApplication.CreateStaticSchemaNotFound.class);
        assertThat(templateCount()).isZero();
        assertThat(revisionCount()).isZero();

        installRevisionInsertFailure();
        try {
            var faulted = templates.create(
                    TemplateApplication.TemplateInvocationRef.serverCreated("pg-create-fault"),
                    new TemplateApplication.CreateCommand(SYSTEM_EMPTY, DESIGN)
            );
            assertThat(faulted)
                    .isInstanceOf(TemplateApplication.CreatePersistenceUnavailable.class);
            assertThat(templateCount()).isZero();
            assertThat(revisionCount()).isZero();
        } finally {
            removeRevisionInsertFailure();
        }
    }

    @Test
    void aggregateUpdateFaultRollsBackNewRevisionAndCorruptionFailsTrustedRead() {
        var created = create("pg-update-fault-create");
        installAggregateUpdateFailure();
        try {
            var faulted = templates.save(
                    TemplateApplication.TemplateInvocationRef.serverCreated("pg-update-fault"),
                    new TemplateApplication.SaveCommand(created.current().templateId(), 0, DESIGN)
            );
            assertThat(faulted)
                    .isInstanceOf(TemplateApplication.SavePersistenceUnavailable.class);
            assertThat(jdbc.sql("select current_revision from template_aggregate")
                    .query(Long.class).single()).isZero();
            assertThat(revisionCount()).isEqualTo(1);
        } finally {
            removeAggregateUpdateFailure();
        }

        jdbc.sql("""
                        update template_revision
                        set content_hash = 'sha256:' || repeat('0', 64)
                        where template_id = :templateId and revision = 0
                        """)
                .param("templateId", created.current().templateId().value())
                .update();
        var corrupted = templates.getCurrent(
                TemplateApplication.TemplateInvocationRef.serverCreated("pg-corruption-read"),
                created.current().templateId()
        );
        assertThat(corrupted)
                .isInstanceOf(TemplateApplication.CurrentIntegrityMismatch.class);
    }

    @Test
    void terminalDeletedStateRejectsCurrentAndSaveWhilePreservingHistory() {
        var created = create("pg-deleted-state-create");
        jdbc.sql("""
                        update template_aggregate
                        set lifecycle = 'DELETED'
                        where template_id = :templateId
                        """)
                .param("templateId", created.current().templateId().value())
                .update();

        var read = templates.getCurrent(
                TemplateApplication.TemplateInvocationRef.serverCreated("pg-deleted-state-read"),
                created.current().templateId()
        );
        var save = templates.save(
                TemplateApplication.TemplateInvocationRef.serverCreated("pg-deleted-state-save"),
                new TemplateApplication.SaveCommand(created.current().templateId(), 0, DESIGN)
        );

        assertThat(read).isInstanceOf(TemplateApplication.CurrentDeleted.class);
        assertThat(save).isInstanceOf(TemplateApplication.SaveDeleted.class);
        assertThat(jdbc.sql("select current_revision from template_aggregate")
                .query(Long.class).single()).isZero();
        assertThat(revisionCount()).isEqualTo(1);
    }

    @Test
    void catalogExcludesDeletedAndCrossOwnerRowsAndCanSearchByOpaqueIdentity() {
        var visible = create("pg-catalog-visible");
        var deleted = create("pg-catalog-deleted");
        var crossOwner = create("pg-catalog-cross-owner");
        jdbc.sql("""
                        update template_aggregate
                        set lifecycle = 'DELETED'
                        where template_id = :templateId
                        """)
                .param("templateId", deleted.current().templateId().value())
                .update();
        jdbc.sql("""
                        update template_aggregate
                        set owner_scope = 'another-owner'
                        where template_id = :templateId
                        """)
                .param("templateId", crossOwner.current().templateId().value())
                .update();

        var outcome = persistence.catalog(new TemplatePersistence.CatalogQuery(
                new OwnerScopeAuthority.OwnerScope("test-owner"),
                visible.current().templateId().value(),
                null,
                50
        ));

        var page = (TemplatePersistence.CatalogPage) outcome;
        assertThat(page.entries())
                .extracting(entry -> entry.templateId().value())
                .containsExactly(visible.current().templateId().value());
        assertThat(page.entries()).allSatisfy(entry -> {
            assertThat(entry.ownerScope().value()).isEqualTo("test-owner");
            assertThat(entry.lifecycle()).isEqualTo(TemplatePersistence.Lifecycle.ACTIVE);
        });
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void catalogTreatsCorruptStoredSummaryAsPersistenceFailureNotClientCursorError() {
        var created = create("pg-catalog-corrupt");
        jdbc.sql("""
                        update template_revision
                        set design_dsl = jsonb_set(design_dsl, '{displayName}', '\"\"'::jsonb)
                        where template_id = :templateId
                        """)
                .param("templateId", created.current().templateId().value())
                .update();

        var outcome = persistence.catalog(new TemplatePersistence.CatalogQuery(
                new OwnerScopeAuthority.OwnerScope("test-owner"),
                null,
                null,
                20
        ));

        assertThat(outcome).isInstanceOf(TemplatePersistence.CatalogUnavailable.class);
    }

    @Test
    void staleMissingRowSnapshotRejectsAppendAndRollsBackTheRevision() {
        var created = create("pg-snapshot-create");
        var admitted = (DesignDslAuthority.Admitted) designs.admit(ASSET_DESIGN);
        var projection = new TemplateDependencyProjection(
                List.of(new TemplateDependencyProjection.AssetRefAtom(
                        FENCED_ASSET,
                        "imageRef",
                        "/designRoot/children/0/imageRef"
                )),
                List.of()
        );
        var staleSnapshot = new TemplateDependencySnapshot(
                List.of(TemplateDependencySnapshot.AssetFact.missing(FENCED_ASSET)),
                List.of()
        );
        insertImageAsset();

        var outcome = persistence.append(new TemplatePersistence.AppendCommit() {
            @Override
            public TemplateApplication.TemplateId templateId() {
                return created.current().templateId();
            }

            @Override
            public OwnerScopeAuthority.OwnerScope ownerScope() {
                return new OwnerScopeAuthority.OwnerScope("test-owner");
            }

            @Override
            public StaticSchemaRef staticSchema() {
                return SYSTEM_EMPTY;
            }

            @Override
            public long expectedRevision() {
                return 0;
            }

            @Override
            public long nextRevision() {
                return 1;
            }

            @Override
            public byte[] canonicalDesignDslUtf8() {
                return admitted.canonicalUtf8();
            }

            @Override
            public String contentHash() {
                return admitted.contentHash();
            }

            @Override
            public TemplateApplication.Readiness readiness() {
                return TemplateApplication.Readiness.INVALID;
            }

            @Override
            public TemplateDependencyProjection projection() {
                return projection;
            }

            @Override
            public TemplateDependencySnapshot dependencySnapshot() {
                return staleSnapshot;
            }
        });

        assertThat(outcome).isInstanceOf(TemplatePersistence.AppendDependencyDrift.class);
        assertThat(jdbc.sql("select current_revision from template_aggregate")
                .query(Long.class).single()).isZero();
        assertThat(revisionCount()).isEqualTo(1);
    }

    private TemplateApplication.CreatedReadable create(String invocationId) {
        return (TemplateApplication.CreatedReadable) templates.create(
                TemplateApplication.TemplateInvocationRef.serverCreated(invocationId),
                new TemplateApplication.CreateCommand(SYSTEM_EMPTY, DESIGN)
        );
    }

    private TemplateApplication.SaveOutcome saveAfterBarrier(
            TemplateApplication.TemplateId templateId,
            String invocationId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent save start barrier timed out");
        }
        return templates.save(
                TemplateApplication.TemplateInvocationRef.serverCreated(invocationId),
                new TemplateApplication.SaveCommand(templateId, 0, DESIGN)
        );
    }

    private long templateCount() {
        return jdbc.sql("select count(*) from template_aggregate").query(Long.class).single();
    }

    private long revisionCount() {
        return jdbc.sql("select count(*) from template_revision").query(Long.class).single();
    }

    private void insertImageAsset() {
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into asset_aggregate (
                        asset_id, owner_scope, kind, lifecycle, asset_revision,
                        current_content_version, display_name, tags
                    ) values (
                        :assetId, 'test-owner', 'IMAGE', 'ACTIVE', 0,
                        0, 'snapshot fixture', '[]'
                    )
                    """)
                    .param("assetId", FENCED_ASSET)
                    .update();
            jdbc.sql("""
                    insert into asset_content_revision (
                        asset_id, content_version, sha256, media_type, byte_length,
                        descriptor_kind, descriptor_json
                    ) values (
                        :assetId, 0,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'image/png', 4, 'IMAGE', '{}'
                    )
                    """)
                    .param("assetId", FENCED_ASSET)
                    .update();
        });
    }

    private void installRevisionInsertFailure() {
        jdbc.sql("""
                create function test_fail_template_revision_insert() returns trigger
                language plpgsql as $$
                begin
                    raise exception 'injected template revision insert failure';
                end
                $$
                """).update();
        jdbc.sql("""
                create trigger test_fail_template_revision_insert
                before insert on template_revision
                for each row execute function test_fail_template_revision_insert()
                """).update();
    }

    private void removeRevisionInsertFailure() {
        jdbc.sql("drop trigger if exists test_fail_template_revision_insert on template_revision")
                .update();
        jdbc.sql("drop function if exists test_fail_template_revision_insert()")
                .update();
    }

    private void installAggregateUpdateFailure() {
        jdbc.sql("""
                create function test_fail_template_aggregate_update() returns trigger
                language plpgsql as $$
                begin
                    raise exception 'injected template aggregate update failure';
                end
                $$
                """).update();
        jdbc.sql("""
                create trigger test_fail_template_aggregate_update
                before update on template_aggregate
                for each row execute function test_fail_template_aggregate_update()
                """).update();
    }

    private void removeAggregateUpdateFailure() {
        jdbc.sql("drop trigger if exists test_fail_template_aggregate_update on template_aggregate")
                .update();
        jdbc.sql("drop function if exists test_fail_template_aggregate_update()")
                .update();
    }
}
