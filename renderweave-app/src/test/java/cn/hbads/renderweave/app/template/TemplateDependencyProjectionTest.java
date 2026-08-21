package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.AssetReferenceAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateReadinessAuthority;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T20 vertical: current-only dependency projection persistence, readiness at
 * create/save, the AssetReferenceAuthority reverse proof, and the replayable STALE
 * consumer over asset_audit_event (Testcontainers PostgreSQL; no H2).
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.template.single-owner.enabled=true",
        "renderweave.template.single-owner.owner-scope=test-owner",
        "renderweave.template.single-owner.capabilities=template.create,template.read,template.update"
})
class TemplateDependencyProjectionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final StaticSchemaRef SYSTEM_EMPTY = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1")
    );
    private static final StaticSchemaRef SYSTEM_BASIC_TEXT = new StaticSchemaRef(
            SchemaKey.systemProvided("system-basic-text"),
            VersionTag.of("v1")
    );
    private static final String CHILD_PUBLIC_TEXT =
            "00000000-0000-4000-8000-0000000000c1";
    private static final String PARENT_PRIVATE_TEXT =
            "00000000-0000-4000-8000-0000000000d1";

    private static final String ASSET_IMAGE = "00000000-0000-4000-8000-0000000000aa";
    private static final String ASSET_FONT = "00000000-0000-4000-8000-0000000000ab";
    private static final String ASSET_MISSING = "00000000-0000-4000-8000-0000000000ac";

    @Autowired
    private TemplateApplication templates;

    @Autowired
    private AssetReferenceAuthority assetReferences;

    @Autowired
    private TemplateReadinessAuthority readinessAuthority;

    @Autowired
    private TemplateAssetStaleConsumer staleConsumer;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetState() {
        jdbc.sql("""
                truncate table template_use_reference,
                                 template_asset_reference,
                                 template_invalid_commit_confirmation,
                                 template_revision,
                                 template_aggregate,
                                 asset_audit_event,
                                 asset_content_revision,
                                 asset_aggregate
                cascade
                """).update();
        jdbc.sql("""
                update template_asset_stale_cursor
                set last_event_id = 0
                where singleton
                """).update();
    }

    @Test
    void createPersistsProjectionAndReadinessAndReverseProof() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var created = create(design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\","
                        + "\"kind\":\"image\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                        + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                        + "\"imageRef\":{\"assetId\":\"" + ASSET_IMAGE + "\"}}]"));
        assertThat(created.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);

        var referencesOutcome = assetReferences.references(ASSET_IMAGE);
        assertThat(referencesOutcome).isInstanceOf(AssetReferenceAuthority.ReferencesReadable.class);
        var references = ((AssetReferenceAuthority.ReferencesReadable) referencesOutcome)
                .references();
        assertThat(references.templateIds())
                .extracting(TemplateApplication.TemplateId::value)
                .containsExactly(created.current().templateId().value());
        var missingOutcome = assetReferences.references(ASSET_MISSING);
        assertThat(missingOutcome).isInstanceOf(AssetReferenceAuthority.ReferencesReadable.class);
        assertThat(((AssetReferenceAuthority.ReferencesReadable) missingOutcome)
                .references().templateIds()).isEmpty();
    }

    @Test
    void missingAssetMakesStrictCreateRejectWithoutRows() {
        var outcome = templates.create(invocation(), new TemplateApplication.CreateCommand(
                SYSTEM_EMPTY,
                design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000012\","
                        + "\"kind\":\"image\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                        + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                        + "\"imageRef\":{\"assetId\":\"" + ASSET_MISSING + "\"}}]")));
        assertThat(outcome).isInstanceOf(TemplateApplication.CreateDependencyRejected.class);
        assertThat(((TemplateApplication.CreateDependencyRejected) outcome).report().confirmable())
                .isTrue();
        assertThat(jdbc.sql("select count(*) from template_aggregate")
                .query(Long.class).single()).isZero();
    }

    @Test
    void assetKindMismatchIsInvalidAndFontKindMatches() {
        insertAsset(ASSET_FONT, "FONT");
        var mismatch = templates.create(invocation(), new TemplateApplication.CreateCommand(
                SYSTEM_EMPTY,
                design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000013\","
                        + "\"kind\":\"image\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                        + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                        + "\"imageRef\":{\"assetId\":\"" + ASSET_FONT + "\"}}]")));
        assertThat(mismatch).isInstanceOf(TemplateApplication.CreateDependencyRejected.class);

        var match = create(design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000014\","
                        + "\"kind\":\"text\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                        + "\"heightMode\":\"HUG_CONTENT\"},"
                        + "\"runs\":[{\"text\":\"Hi\","
                        + "\"fontRef\":{\"assetId\":\"" + ASSET_FONT + "\"},"
                        + "\"fontSizePt\":12,\"color\":\"#FF000000\","
                        + "\"decoration\":\"NONE\",\"letterSpacingPt\":0}]}]"));
        assertThat(match.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);
    }

    @Test
    void contentEventsMarkStaleAndRecheckLandsReadiness() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var created = create(design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000015\","
                        + "\"kind\":\"image\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                        + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                        + "\"imageRef\":{\"assetId\":\"" + ASSET_IMAGE + "\"}}]"));
        assertThat(created.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);

        insertAuditEvent(ASSET_IMAGE, "CONTENT_REPLACE");
        assertThat(staleConsumer.consumePending()).isEqualTo(1);
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.STALE);

        // Recheck: asset still exists with matching kind -> READY.
        staleConsumer.recheckStale();
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.READY);

        // Metadata-only events never mark STALE.
        insertAuditEvent(ASSET_IMAGE, "METADATA_UPDATE");
        assertThat(staleConsumer.consumePending()).isZero();
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.READY);

        // Content change plus a now-mismatched asset -> STALE then INVALID.
        jdbc.sql("update asset_aggregate set kind = 'FONT' where asset_id = :assetId")
                .param("assetId", ASSET_IMAGE)
                .update();
        insertAuditEvent(ASSET_IMAGE, "CONTENT_REPLACE");
        staleConsumer.consumePending();
        staleConsumer.recheckStale();
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.INVALID);
    }

    @Test
    void templateUseEdgesPersistAndCycleIsHardZeroWrite() {
        var child = create(design(""));
        var parent = create(design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000016\","
                        + "\"kind\":\"templateUse\",\"bindings\":[],"
                        + "\"useId\":\"00000000-0000-4000-8000-0000000000b1\","
                        + "\"templateRef\":{\"templateId\":\"" + child.current().templateId().value() + "\"},"
                        + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"}}]"));
        assertThat(parent.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);

        // Child now references parent -> the closure child->parent->child is a cycle.
        var cycleSave = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        child.current().templateId(), 0,
                        design(
                                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000017\","
                                        + "\"kind\":\"templateUse\",\"bindings\":[],"
                                        + "\"useId\":\"00000000-0000-4000-8000-0000000000b2\","
                                        + "\"templateRef\":{\"templateId\":\""
                                        + parent.current().templateId().value() + "\"},"
                                        + "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],"
                                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                                        + "\"widthMode\":\"HUG_CONTENT\","
                                        + "\"heightMode\":\"HUG_CONTENT\"}}]")
                )
        );
        assertThat(cycleSave).isInstanceOf(TemplateApplication.SaveDependencyRejected.class);
        var report = ((TemplateApplication.SaveDependencyRejected) cycleSave).report();
        assertThat(report.confirmable()).isFalse();
        assertThat(report.problems()).extracting(TemplateApplication.ValidationProblem::code)
                .contains("TEMPLATE_REF_CYCLE");
        assertThat(jdbc.sql("select current_revision from template_aggregate "
                        + "where template_id = :templateId")
                .param("templateId", child.current().templateId().value())
                .query(Long.class).single()).isZero();
    }

    @Test
    void dependencyInvalidSaveRequiresPersistedOfferThenAppendsInvalidWhenExact() {
        var created = create(design(""));
        var proposed = missingImageDesign("00000000-0000-4000-8000-000000000019");

        var first = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(created.current().templateId(), 0, proposed)
        );
        var required = (TemplateApplication.SaveConfirmationRequired) first;
        assertThat(required.offer().confirmationToken()).matches("[0-9a-f]{64}");
        assertThat(required.offer().report().confirmable()).isTrue();
        assertThat(jdbc.sql("select count(*) from template_invalid_commit_confirmation")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from template_revision")
                .query(Long.class).single()).isEqualTo(1);

        var confirmed = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        created.current().templateId(),
                        0,
                        proposed,
                        required.offer().confirmationToken()
                )
        );
        assertThat(confirmed).isInstanceOf(TemplateApplication.SavedReadable.class);
        assertThat(((TemplateApplication.SavedReadable) confirmed).current().readiness())
                .isEqualTo(TemplateApplication.Readiness.INVALID);
        assertThat(jdbc.sql("select count(*) from template_revision")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void invalidExpiredAndDependencyDriftConfirmationsAreZeroWrite() {
        var created = create(design(""));
        var proposed = missingImageDesign("00000000-0000-4000-8000-00000000001a");
        var invalid = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        created.current().templateId(), 0, proposed, "f".repeat(64))
        );
        assertThat(invalid).isInstanceOf(TemplateApplication.SaveConfirmationInvalid.class);

        var first = (TemplateApplication.SaveConfirmationRequired) templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(created.current().templateId(), 0, proposed)
        );
        jdbc.sql("""
                update template_invalid_commit_confirmation
                set issued_at = now() - interval '10 minutes',
                    expires_at = now() - interval '1 second'
                """).update();
        var expired = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        created.current().templateId(), 0, proposed,
                        first.offer().confirmationToken())
        );
        assertThat(expired).isInstanceOf(TemplateApplication.SaveConfirmationExpired.class);

        jdbc.sql("truncate table template_invalid_commit_confirmation").update();
        var driftOffer = (TemplateApplication.SaveConfirmationRequired) templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(created.current().templateId(), 0, proposed)
        );
        insertAsset(ASSET_MISSING, "IMAGE");
        var drifted = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        created.current().templateId(), 0, proposed,
                        driftOffer.offer().confirmationToken())
        );
        assertThat(drifted).isInstanceOf(TemplateApplication.SaveConfirmationStale.class);
        assertThat(((TemplateApplication.SaveConfirmationStale) drifted).replacement()).isEmpty();
        assertThat(jdbc.sql("select count(*) from template_revision")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void readinessAuthorityRecheckUpdatesPersistedReadiness() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var created = create(design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000018\","
                        + "\"kind\":\"image\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                        + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                        + "\"imageRef\":{\"assetId\":\"" + ASSET_IMAGE + "\"}}]"));
        assertThat(created.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);

        // Assets are soft-deleted via lifecycle (asset_content_revision rows are kept
        // immutable; the aggregate -> current-revision FK is ON DELETE RESTRICT, so
        // physical deletion is not a supported state). A DELETED lifecycle makes the
        // dependency probe report NOT_FOUND, which is the honest "asset gone" fact.
        jdbc.sql("update asset_aggregate set lifecycle = 'DELETED' where asset_id = :assetId")
                .param("assetId", ASSET_IMAGE)
                .update();
        var rechecked = readinessAuthority.recheck(created.current().templateId());
        assertThat(rechecked).isInstanceOf(TemplateReadinessAuthority.Rechecked.class);
        assertThat(((TemplateReadinessAuthority.Rechecked) rechecked).readiness())
                .isEqualTo(TemplateApplication.Readiness.INVALID);
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.INVALID);
    }

    @Test
    void exactStaticSchemaTypeProblemRequiresConfirmationAndPersistsInvalid() {
        var created = create(SYSTEM_BASIC_TEXT, design(""));
        var proposed = design(
                "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000071\","
                        + "\"kind\":\"conditional\",\"bindings\":[],"
                        + "\"condition\":{\"kind\":\"context\","
                        + "\"domain\":\"invocation\",\"pointer\":\"/value\"},"
                        + "\"absentPolicy\":\"FALSE\","
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"},"
                        + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000072\","
                        + "\"kind\":\"frame\",\"bindings\":[],"
                        + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                        + "\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"},"
                        + "\"children\":[]}]}]"
        );

        var first = templates.save(invocation(), new TemplateApplication.SaveCommand(
                created.current().templateId(), 0, proposed));
        assertThat(first).isInstanceOf(TemplateApplication.SaveConfirmationRequired.class);
        var required = (TemplateApplication.SaveConfirmationRequired) first;
        assertThat(required.offer().report().problems())
                .extracting(TemplateApplication.ValidationProblem::code)
                .containsExactly("TEMPLATE_CONDITION_TYPE_MISMATCH");
        assertThat(jdbc.sql("select count(*) from template_revision")
                .query(Long.class).single()).isEqualTo(1);

        var confirmed = templates.save(invocation(), new TemplateApplication.SaveCommand(
                created.current().templateId(),
                0,
                proposed,
                required.offer().confirmationToken()
        ));
        assertThat(confirmed).isInstanceOf(TemplateApplication.SavedReadable.class);
        assertThat(((TemplateApplication.SavedReadable) confirmed).current().readiness())
                .isEqualTo(TemplateApplication.Readiness.INVALID);
    }

    @Test
    void childCurrentDefinitionDriftMakesParentInvalidOnExactRecheck() {
        var childDefinitions = "[{\"definitionId\":\"" + CHILD_PUBLIC_TEXT + "\","
                + "\"kind\":\"custom\",\"displayName\":\"Public text\","
                + "\"exposure\":\"PUBLIC\",\"valueType\":\"text\","
                + "\"defaultValue\":\"child default\"}]";
        var child = create(design(childDefinitions, ""));
        var parentDefinitions = "[{\"definitionId\":\"" + PARENT_PRIVATE_TEXT + "\","
                + "\"kind\":\"custom\",\"displayName\":\"Parent text\","
                + "\"exposure\":\"PRIVATE\",\"valueType\":\"text\","
                + "\"defaultValue\":\"parent default\"}]";
        var use = "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000081\","
                + "\"kind\":\"templateUse\",\"bindings\":[],"
                + "\"useId\":\"00000000-0000-4000-8000-000000000082\","
                + "\"templateRef\":{\"templateId\":\""
                + child.current().templateId().value() + "\"},"
                + "\"contextSelector\":{\"kind\":\"empty\"},"
                + "\"fills\":[{\"targetDefinitionId\":\"" + CHILD_PUBLIC_TEXT + "\","
                + "\"source\":{\"kind\":\"definition\",\"definitionId\":\""
                + PARENT_PRIVATE_TEXT + "\"}}],"
                + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"}}]";
        var parent = create(design(parentDefinitions, use));
        assertThat(parent.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);

        var childSave = templates.save(invocation(), new TemplateApplication.SaveCommand(
                child.current().templateId(), 0, design("")));
        assertThat(childSave).isInstanceOf(TemplateApplication.SavedReadable.class);

        var rechecked = readinessAuthority.recheck(parent.current().templateId());
        assertThat(rechecked).isInstanceOf(TemplateReadinessAuthority.Rechecked.class);
        assertThat(((TemplateReadinessAuthority.Rechecked) rechecked).readiness())
                .isEqualTo(TemplateApplication.Readiness.INVALID);
        assertThat(readinessOf(parent.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.INVALID);
    }

    private TemplateApplication.CreatedReadable create(byte[] design) {
        return create(SYSTEM_EMPTY, design);
    }

    private TemplateApplication.CreatedReadable create(
            StaticSchemaRef schema,
            byte[] design
    ) {
        var outcome = templates.create(
                invocation(),
                new TemplateApplication.CreateCommand(schema, design)
        );
        assertThat(outcome).isInstanceOf(TemplateApplication.CreatedReadable.class);
        return (TemplateApplication.CreatedReadable) outcome;
    }

    private static TemplateApplication.TemplateInvocationRef invocation() {
        return TemplateApplication.TemplateInvocationRef.serverCreated("t20-slice-request");
    }

    private TemplateApplication.Readiness readinessOf(String templateId) {
        var outcome = templates.getCurrent(
                invocation(), TemplateApplication.TemplateId.of(templateId));
        assertThat(outcome).isInstanceOf(TemplateApplication.CurrentReadable.class);
        return ((TemplateApplication.CurrentReadable) outcome).current().readiness();
    }

    private void insertAsset(String assetId, String kind) {
        // The aggregate's (asset_id, current_content_version) FK is DEFERRABLE INITIALLY
        // DEFERRED, so both rows must land in one transaction.
        var transactions = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into asset_aggregate (
                        asset_id, owner_scope, kind, lifecycle, asset_revision,
                        current_content_version, display_name, tags
                    ) values (
                        :assetId, 'test-owner', :kind, 'ACTIVE', 0, 0, :displayName, '[]'
                    )
                    """)
                    .param("assetId", assetId)
                    .param("kind", kind)
                    .param("displayName", "fixture asset")
                    .update();
            jdbc.sql("""
                    insert into asset_content_revision (
                        asset_id, content_version, sha256, media_type, byte_length,
                        descriptor_kind, descriptor_json
                    ) values (
                        :assetId, 0,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'image/png', 4, :kind, '{}'
                    )
                    """)
                    .param("assetId", assetId)
                    .param("kind", kind)
                    .update();
        });
    }

    private void insertAuditEvent(String assetId, String operationType) {
        jdbc.sql("""
                insert into asset_audit_event (
                    asset_id, before_asset_revision, after_asset_revision,
                    actor_id, operation_type, content_version
                ) values (:assetId, 0, 1, 't20-test', :operationType, 1)
                """)
                .param("assetId", assetId)
                .param("operationType", operationType)
                .update();
    }

    private static byte[] design(String children) {
        return design("[]", children);
    }

    private static byte[] design(String definitions, String children) {
        return ("{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"T20 fixture\",\"definitions\":" + definitions + ","
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[]"
                + (children.isEmpty() ? ",\"children\":[]" : "," + children) + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] missingImageDesign(String nodeId) {
        return design(
                "\"children\":[{\"nodeId\":\"" + nodeId + "\"," +
                        "\"kind\":\"image\",\"bindings\":[]," +
                        "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0," +
                        "\"widthMode\":\"FIXED\",\"widthMm\":10," +
                        "\"heightMode\":\"FIXED\",\"heightMm\":10}," +
                        "\"imageRef\":{\"assetId\":\"" + ASSET_MISSING + "\"}}]"
        );
    }
}
