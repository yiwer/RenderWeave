package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
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

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
class AssetSliceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z");

    @Autowired
    private AssetApplication assets;

    @Autowired
    private JdbcClient jdbc;


    @BeforeEach
    void clearAssets() {
        jdbc.sql("truncate table asset_audit_event, asset_idempotency, asset_content_revision, "
                + "asset_aggregate").update();
        jdbc.sql("update asset_capacity set used_bytes = 0").update();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("renderweave.asset.single-owner.enabled", () -> "true");
        registry.add("renderweave.asset.single-owner.owner-scope", () -> "it-scope");
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

    private static byte[] jpegBytes() {
        try (var stream = AssetSliceIntegrationTest.class.getResourceAsStream(
                "/asset-fixtures/grayscale-baseline.jpg")) {
            assertTrue(stream != null);
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static AssetApplication.CreateCommand createCommand(byte[] content, String key) {
        return new AssetApplication.CreateCommand(
                key,
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                "Integration Asset",
                List.of("it", "slice"),
                "fixture.jpg",
                content
        );
    }

    @Test
    void createCurrentCatalogUpdateVersionsAndDownloadThroughPostgresAndMinio() {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-it-1");
        var raw = jpegBytes();

        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, createCommand(raw, "it-key-1"))
        );
        var detail = created.detail();
        assertEquals("it-scope", detail.ownerScope().value());
        assertEquals("Integration Asset", detail.displayName());
        assertEquals("image/jpeg", detail.mediaType());

        var current = assertInstanceOf(
                AssetApplication.CurrentReadable.class,
                assets.getCurrent(invocation, detail.assetId())
        );
        assertEquals(detail.sha256(), current.detail().sha256());

        var page = assertInstanceOf(
                AssetApplication.CatalogPage.class,
                assets.catalog(
                        invocation,
                        new AssetApplication.CatalogCommand(
                                AssetAcceptanceAuthority.AssetKind.IMAGE,
                                List.of("it"),
                                List.of(),
                                null,
                                null,
                                false,
                                null,
                                20
                        )
                )
        );
        assertEquals(1, page.entries().size());
        assertEquals(detail.assetId(), page.entries().get(0).assetId());

        var updated = assertInstanceOf(
                AssetApplication.UpdatedReadable.class,
                assets.updateMetadata(
                        invocation,
                        new AssetApplication.UpdateMetadataCommand(
                                detail.assetId(),
                                0,
                                "Renamed",
                                List.of("slice")
                        )
                )
        );
        assertEquals(1, updated.detail().assetRevision());
        assertEquals("Renamed", updated.detail().displayName());

        var versions = assertInstanceOf(
                AssetApplication.VersionsReadable.class,
                assets.listContentVersions(invocation, detail.assetId())
        );
        assertEquals(1, versions.entries().size());

        var download = assertInstanceOf(
                AssetApplication.DownloadReadable.class,
                assets.downloadExact(invocation, detail.assetId(), 0)
        );
        assertEquals(detail.sha256(), download.content().sha256());
        assertEquals(raw.length, download.content().bytes().length);
    }

    @Test
    void idempotentReplayReturnsSameAssetAndConflictRejectsDifferentInput() {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-it-2");
        var command = createCommand(jpegBytes(), "it-key-2");

        var first = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, command)
        );
        var replayed = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, command)
        );
        assertEquals(first.detail().assetId(), replayed.detail().assetId());

        var conflict = assets.create(
                invocation,
                new AssetApplication.CreateCommand(
                        "it-key-2",
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        "Different",
                        List.of(),
                        "fixture.jpg",
                        jpegBytes()
                )
        );
        assertInstanceOf(AssetApplication.CreateIdempotencyConflict.class, conflict);
    }

    @Test
    void replaceAndRestoreAppendContentVersionsAndRecordBoundedAuditEvents() throws IOException {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-it-3");
        var raw = jpegBytes();
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, createCommand(raw, "it-key-3"))
        );
        var assetId = created.detail().assetId();
        var replacement = ycbcrBytes();

        var replaced = assertInstanceOf(
                AssetApplication.ReplaceApplied.class,
                assets.replaceContent(
                        invocation,
                        new AssetApplication.ReplaceContentCommand(assetId, 0, replacement)
                )
        );
        assertEquals(1, replaced.detail().assetRevision());
        assertEquals(1, replaced.detail().currentContentVersion());

        var noOp = assertInstanceOf(
                AssetApplication.ReplaceNoOp.class,
                assets.replaceContent(
                        invocation,
                        new AssetApplication.ReplaceContentCommand(assetId, 1, replacement)
                )
        );
        assertEquals(1, noOp.detail().assetRevision());

        var restored = assertInstanceOf(
                AssetApplication.RestoreApplied.class,
                assets.restoreContent(
                        invocation,
                        new AssetApplication.RestoreContentCommand(assetId, 1, 0)
                )
        );
        assertEquals(2, restored.detail().assetRevision());
        assertEquals(2, restored.detail().currentContentVersion());
        assertEquals(created.detail().sha256(), restored.detail().sha256());

        var versions = assertInstanceOf(
                AssetApplication.VersionsReadable.class,
                assets.listContentVersions(invocation, assetId)
        );
        assertEquals(3, versions.entries().size());
        assertEquals(List.of(0L, 1L, 2L), versions.entries().stream()
                .map(AssetApplication.ContentVersionEntry::contentVersion)
                .toList());
        assertEquals(
                versions.entries().get(2).sha256(),
                versions.entries().get(0).sha256()
        );

        var restoredDownload = assertInstanceOf(
                AssetApplication.DownloadReadable.class,
                assets.downloadExact(invocation, assetId, 2)
        );
        assertEquals(raw.length, restoredDownload.content().bytes().length);
        assertEquals(raw[0], restoredDownload.content().bytes()[0]);

        var audit = jdbc.sql("""
                        select operation_type, before_asset_revision, after_asset_revision,
                               actor_id, content_version
                        from asset_audit_event
                        where asset_id = :assetId
                        order by event_id
                        """)
                .param("assetId", assetId.value())
                .query((rs, rowNum) -> new String[]{
                        rs.getString("operation_type"),
                        rs.getString("before_asset_revision"),
                        rs.getString("after_asset_revision"),
                        rs.getString("actor_id"),
                        rs.getString("content_version")
                })
                .list();
        assertEquals(3, audit.size());
        assertEquals("CREATE", audit.get(0)[0]);
        assertEquals("inv-it-3", audit.get(0)[3]);
        assertEquals("CONTENT_REPLACE", audit.get(1)[0]);
        assertEquals("0", audit.get(1)[1]);
        assertEquals("1", audit.get(1)[2]);
        assertEquals("1", audit.get(1)[4]);
        assertEquals("CONTENT_RESTORE", audit.get(2)[0]);
        assertEquals("1", audit.get(2)[1]);
        assertEquals("2", audit.get(2)[2]);
        assertEquals("2", audit.get(2)[4]);
    }

    private static byte[] ycbcrBytes() throws IOException {
        try (var stream = AssetSliceIntegrationTest.class.getResourceAsStream(
                "/asset-fixtures/ycbcr-progressive.jpg")) {
            assertTrue(stream != null);
            return stream.readAllBytes();
        }
    }
}
