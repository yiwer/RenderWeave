package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12b vertical: delete precheck impact + single-use confirmation token, soft delete with
 * exclusive reservation and re-derived proof, lifecycle restore at the pre-delete current,
 * and the Template STALE -> recheck INVALID/READY consumption over asset_audit_event
 * (Testcontainers PostgreSQL + MinIO; no H2).
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.template.single-owner.enabled=true",
        "renderweave.template.single-owner.owner-scope=slice-owner",
        "renderweave.template.single-owner.capabilities=template.create,template.read,template.update"
})
class AssetDeleteRestoreSliceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z");

    private static final StaticSchemaRef SYSTEM_EMPTY = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1")
    );

    private static final String ASSET_IMAGE = "00000000-0000-4000-8000-0000000000aa";
    private static final String ASSET_FONT = "00000000-0000-4000-8000-0000000000ab";

    @Autowired
    private AssetApplication assets;

    @Autowired
    private TemplateApplication templates;

    @Autowired
    private TemplateAssetStaleConsumer staleConsumer;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("renderweave.asset.single-owner.enabled", () -> "true");
        registry.add("renderweave.asset.single-owner.owner-scope", () -> "slice-owner");
        registry.add(
                "renderweave.asset.single-owner.capabilities",
                () -> "asset.read,asset.create,asset.update,asset.delete,asset.restore"
        );
        registry.add("renderweave.asset.s3.endpoint", () -> MINIO.getS3URL());
        registry.add("renderweave.asset.s3.access-key", MINIO::getUserName);
        registry.add("renderweave.asset.s3.secret-key", MINIO::getPassword);
        registry.add("renderweave.asset.s3.bucket", () -> "renderweave-assets");
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())
                ))
                .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket("renderweave-assets").build());
        }
    }

    @BeforeEach
    void resetState() {
        jdbc.sql("""
                truncate table template_use_reference,
                                 template_asset_reference,
                                 template_revision,
                                 template_aggregate,
                                 asset_audit_event,
                                 asset_delete_confirmation,
                                 asset_idempotency,
                                 asset_content_revision,
                                 asset_aggregate
                cascade
                """).update();
        jdbc.sql("update asset_capacity set used_bytes = 0").update();
        jdbc.sql("""
                update template_asset_stale_cursor
                set last_event_id = 0
                where singleton
                """).update();
    }

    @Test
    void precheckThenDeleteMarksReferencingTemplateStaleAndRecheckInvalidThenRestoreReadsReady() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var created = createTemplate(imageDesign(ASSET_IMAGE));
        assertThat(created.current().readiness()).isEqualTo(TemplateApplication.Readiness.READY);

        // Precheck: one current-only reference, no redaction in this single-owner test.
        var precheck = assets.deletePrecheck(
                AssetApplication.InvocationRef.serverCreated("slice-precheck-1"),
                AssetApplication.AssetId.of(ASSET_IMAGE)
        );
        assertThat(precheck).isInstanceOf(AssetApplication.DeletePrecheckReadable.class);
        var readable = (AssetApplication.DeletePrecheckReadable) precheck;
        assertThat(readable.impact().totalCount()).isEqualTo(1);
        assertThat(readable.impact().readableTemplateIds())
                .containsExactly(created.current().templateId().value());
        assertThat(readable.impact().redactedCount()).isZero();

        // Missing confirmation is rejected with zero writes.
        var missing = assets.delete(
                AssetApplication.InvocationRef.serverCreated("slice-delete-1"),
                new AssetApplication.DeleteCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        new AssetApplication.ConfirmationToken("0".repeat(64))
                )
        );
        assertThat(missing).isInstanceOf(AssetApplication.DeleteConfirmationRequired.class);
        assertThat(lifecycleOf(ASSET_IMAGE)).isEqualTo("ACTIVE");

        // Confirmed delete applies, then the DELETE audit event drives STALE -> INVALID.
        var deleted = assets.delete(
                AssetApplication.InvocationRef.serverCreated("slice-delete-1"),
                new AssetApplication.DeleteCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        readable.confirmationToken()
                )
        );
        assertThat(deleted).isInstanceOf(AssetApplication.DeleteApplied.class);
        assertThat(((AssetApplication.DeleteApplied) deleted).detail().lifecycle())
                .isEqualTo(AssetApplication.Lifecycle.DELETED);
        assertThat(((AssetApplication.DeleteApplied) deleted).detail().assetRevision())
                .isEqualTo(1L);
        assertThat(lifecycleOf(ASSET_IMAGE)).isEqualTo("DELETED");

        assertThat(staleConsumer.consumePending()).isEqualTo(1);
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.STALE);
        staleConsumer.recheckStale();
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.INVALID);

        // Single-use: replaying the same token cannot delete again.
        var replay = assets.delete(
                AssetApplication.InvocationRef.serverCreated("slice-delete-1"),
                new AssetApplication.DeleteCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        readable.confirmationToken()
                )
        );
        assertThat(replay).isInstanceOf(AssetApplication.DeleteDeleted.class);

        // Restore reactivates the same Asset id at the pre-delete current (no new content
        // version) and the RESTORE event drives STALE -> READY again.
        var restored = assets.restore(
                AssetApplication.InvocationRef.serverCreated("slice-restore-1"),
                new AssetApplication.RestoreLifecycleCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        1L
                )
        );
        assertThat(restored).isInstanceOf(AssetApplication.RestoreLifecycleApplied.class);
        var restoredDetail = ((AssetApplication.RestoreLifecycleApplied) restored).detail();
        assertThat(restoredDetail.lifecycle()).isEqualTo(AssetApplication.Lifecycle.ACTIVE);
        assertThat(restoredDetail.currentContentVersion()).isZero();
        assertThat(restoredDetail.assetRevision()).isEqualTo(2L);

        assertThat(staleConsumer.consumePending()).isEqualTo(1);
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.STALE);
        staleConsumer.recheckStale();
        assertThat(readinessOf(created.current().templateId().value()))
                .isEqualTo(TemplateApplication.Readiness.READY);
    }

    @Test
    void expiredConfirmationIsRejectedWithoutWrites() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var precheck = assets.deletePrecheck(
                AssetApplication.InvocationRef.serverCreated("slice-precheck-2"),
                AssetApplication.AssetId.of(ASSET_IMAGE)
        );
        assertThat(precheck).isInstanceOf(AssetApplication.DeletePrecheckReadable.class);
        var readable = (AssetApplication.DeletePrecheckReadable) precheck;

        jdbc.sql("""
                        update asset_delete_confirmation
                        set expires_at = clock_timestamp() - interval '1 second'
                        where confirmation_token = :token
                        """)
                .param("token", readable.confirmationToken().value())
                .update();

        var expired = assets.delete(
                AssetApplication.InvocationRef.serverCreated("slice-delete-2"),
                new AssetApplication.DeleteCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        readable.confirmationToken()
                )
        );
        assertThat(expired).isInstanceOf(AssetApplication.DeleteConfirmationExpired.class);
        assertThat(lifecycleOf(ASSET_IMAGE)).isEqualTo("ACTIVE");
    }

    @Test
    void assetRevisionDriftBetweenPrecheckAndDeleteIsStaleWithZeroWrites() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var precheck = assets.deletePrecheck(
                AssetApplication.InvocationRef.serverCreated("slice-precheck-3"),
                AssetApplication.AssetId.of(ASSET_IMAGE)
        );
        assertThat(precheck).isInstanceOf(AssetApplication.DeletePrecheckReadable.class);
        var readable = (AssetApplication.DeletePrecheckReadable) precheck;

        // A metadata update after the precheck advances the Asset revision: the token no
        // longer binds the current revision, so the delete must be zero-write.
        jdbc.sql("""
                        update asset_aggregate
                        set display_name = 'drifted',
                            asset_revision = asset_revision + 1,
                            updated_at = clock_timestamp()
                        where asset_id = :assetId
                        """)
                .param("assetId", ASSET_IMAGE)
                .update();

        var stale = assets.delete(
                AssetApplication.InvocationRef.serverCreated("slice-delete-3"),
                new AssetApplication.DeleteCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        readable.confirmationToken()
                )
        );
        assertThat(stale).isInstanceOf(AssetApplication.DeleteConfirmationStale.class);
        assertThat(lifecycleOf(ASSET_IMAGE)).isEqualTo("ACTIVE");
    }

    @Test
    void referenceDriftBetweenPrecheckAndDeleteIsStale() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        insertAsset(ASSET_FONT, "FONT");
        var precheck = assets.deletePrecheck(
                AssetApplication.InvocationRef.serverCreated("slice-precheck-4"),
                AssetApplication.AssetId.of(ASSET_IMAGE)
        );
        assertThat(precheck).isInstanceOf(AssetApplication.DeletePrecheckReadable.class);
        var readable = (AssetApplication.DeletePrecheckReadable) precheck;
        assertThat(readable.impact().totalCount()).isZero();

        // A Template now references the Asset: the reference set (and its fingerprint)
        // changed since the precheck, so the delete must be zero-write.
        createTemplate(imageDesign(ASSET_IMAGE));

        var stale = assets.delete(
                AssetApplication.InvocationRef.serverCreated("slice-delete-4"),
                new AssetApplication.DeleteCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        readable.confirmationToken()
                )
        );
        assertThat(stale).isInstanceOf(AssetApplication.DeleteConfirmationStale.class);
        assertThat(lifecycleOf(ASSET_IMAGE)).isEqualTo("ACTIVE");
    }

    @Test
    void restoreOfActiveAssetConflicts() {
        insertAsset(ASSET_IMAGE, "IMAGE");
        var outcome = assets.restore(
                AssetApplication.InvocationRef.serverCreated("slice-restore-2"),
                new AssetApplication.RestoreLifecycleCommand(
                        AssetApplication.AssetId.of(ASSET_IMAGE),
                        0L
                )
        );
        assertThat(outcome).isInstanceOf(AssetApplication.RestoreLifecycleActive.class);
    }

    private TemplateApplication.CreatedReadable createTemplate(byte[] design) {
        var outcome = templates.create(
                TemplateApplication.TemplateInvocationRef.serverCreated("slice-template-request"),
                new TemplateApplication.CreateCommand(SYSTEM_EMPTY, design)
        );
        assertThat(outcome).isInstanceOf(TemplateApplication.CreatedReadable.class);
        return (TemplateApplication.CreatedReadable) outcome;
    }

    private TemplateApplication.Readiness readinessOf(String templateId) {
        var outcome = templates.getCurrent(
                TemplateApplication.TemplateInvocationRef.serverCreated("slice-read-request"),
                TemplateApplication.TemplateId.of(templateId));
        assertThat(outcome).isInstanceOf(TemplateApplication.CurrentReadable.class);
        return ((TemplateApplication.CurrentReadable) outcome).current().readiness();
    }

    private String lifecycleOf(String assetId) {
        return jdbc.sql("""
                        select lifecycle
                        from asset_aggregate
                        where asset_id = :assetId
                        """)
                .param("assetId", assetId)
                .query(String.class)
                .single();
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
                        :assetId, 'slice-owner', :kind, 'ACTIVE', 0, 0, 'fixture asset', '[]'
                    )
                    """)
                    .param("assetId", assetId)
                    .param("kind", kind)
                    .update();
            jdbc.sql("""
                    insert into asset_content_revision (
                        asset_id, content_version, sha256, media_type, byte_length,
                        descriptor_kind, descriptor_json
                    ) values (
                        :assetId, 0,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'image/png', 4, :kind, cast(:descriptorJson as jsonb)
                    )
                    """)
                    .param("assetId", assetId)
                    .param("kind", kind)
                    .param("descriptorJson", """
                            {"encodedWidthPx":1,"encodedHeightPx":1,"orientation":"IDENTITY",
                             "logicalWidthPx":1,"logicalHeightPx":1,"frameCount":1,
                             "colorEncoding":"SRGB_8BIT"}
                            """)
                    .update();
        });
    }

    private static byte[] imageDesign(String assetId) {
        return ("{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"T12b fixture\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],"
                + "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\","
                + "\"kind\":\"image\",\"bindings\":[],"
                + "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,"
                + "\"widthMode\":\"FIXED\",\"widthMm\":10,"
                + "\"heightMode\":\"FIXED\",\"heightMm\":10},"
                + "\"imageRef\":{\"assetId\":\"" + assetId + "\"}}]}}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
