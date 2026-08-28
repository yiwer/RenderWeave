package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.api.AssetResolver;
import cn.hbads.renderweave.rendering.api.Evaluator;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AssetSliceIntegrationTest {

    private static final StaticSchemaRef SYSTEM_EMPTY = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z");

    @Autowired
    private AssetApplication assets;

    @Autowired
    private AssetResolver resolver;

    @Autowired
    private TemplateApplication templates;

    @Autowired
    private Evaluator evaluator;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc http;


    @BeforeEach
    void clearAssets() {
        jdbc.sql("truncate table asset_render_selection, asset_audit_event, asset_idempotency, asset_delete_confirmation, asset_content_revision, "
                + "asset_aggregate").update();
        jdbc.sql("update asset_capacity set used_bytes = 0").update();
        jdbc.sql("""
                truncate table template_use_reference,
                               template_asset_reference,
                               template_revision,
                               template_aggregate
                cascade
                """).update();
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
        registry.add(
                "renderweave.asset.resolution.key",
                () -> "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
        );
        registry.add(
                "renderweave.asset.fetch-base-url",
                () -> "https://render.internal.example"
        );
        registry.add("renderweave.template.single-owner.enabled", () -> "true");
        registry.add("renderweave.template.single-owner.owner-scope", () -> "it-scope");
        registry.add(
                "renderweave.template.single-owner.capabilities",
                () -> "template.create,template.read"
        );
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

    @Test
    void renderSelectionReplaysExactCurrentAndConflictsBeforeReselecting() throws IOException {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-resolve-1");
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, createCommand(jpegBytes(), "resolve-key-1"))
        );
        long deadline = System.currentTimeMillis() + 60_000;
        var original = resolveRequest(
                "render-resolve-1",
                "0".repeat(64),
                created.detail().assetId(),
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                deadline
        );

        var firstOutcome = resolver.resolve(original);
        assertEquals(
                1,
                jdbc.sql("select count(*) from asset_render_selection")
                        .query(Integer.class)
                        .single(),
                "selection must commit before lease materialization: "
                        + firstOutcome.getClass().getSimpleName()
        );
        var first = assertInstanceOf(
                AssetResolver.Resolved.class,
                firstOutcome
        ).asset();
        assertEquals(0, first.contentVersion());

        var replacement = assertInstanceOf(
                AssetApplication.ReplaceApplied.class,
                assets.replaceContent(
                        invocation,
                        new AssetApplication.ReplaceContentCommand(
                                created.detail().assetId(), 0, ycbcrBytes())
                )
        );
        assertEquals(1, replacement.detail().currentContentVersion());

        var replay = assertInstanceOf(
                AssetResolver.Resolved.class,
                resolver.resolve(original)
        ).asset();
        assertEquals(0, replay.contentVersion());
        assertEquals(first.sha256(), replay.sha256());
        assertEquals(first.lease(), replay.lease());

        var laterOccurrence = assertInstanceOf(
                AssetResolver.Resolved.class,
                resolver.resolve(resolveRequest(
                        "render-resolve-1",
                        "1".repeat(64),
                        created.detail().assetId(),
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        deadline
                ))
        ).asset();
        assertEquals(1, laterOccurrence.contentVersion());

        var conflict = resolver.resolve(resolveRequest(
                "render-resolve-1",
                "0".repeat(64),
                created.detail().assetId(),
                AssetAcceptanceAuthority.AssetKind.FONT,
                deadline
        ));
        assertInstanceOf(AssetResolver.ResolveConflict.class, conflict);

        var stored = jdbc.sql("""
                        select selection_cipher from asset_render_selection
                        where render_request_id = 'render-resolve-1'
                        """)
                .query((rs, rowNum) -> rs.getBytes("selection_cipher"))
                .list();
        assertEquals(2, stored.size());
        for (byte[] cipher : stored) {
            String opaque = new String(cipher, StandardCharsets.ISO_8859_1);
            assertTrue(!opaque.contains(created.detail().assetId().value()));
            assertTrue(!opaque.contains(created.detail().sha256()));
        }
    }

    @Test
    void concurrentSameKeyResolutionLinearizesToOneExactSelectionAndLease() throws Exception {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-resolve-concurrent");
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, createCommand(jpegBytes(), "resolve-key-concurrent"))
        );
        var request = resolveRequest(
                "render-resolve-concurrent",
                "4".repeat(64),
                created.detail().assetId(),
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                System.currentTimeMillis() + 60_000
        );
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<AssetResolver.ResolvedAsset>>();
            for (int index = 0; index < 8; index++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    return assertInstanceOf(
                            AssetResolver.Resolved.class,
                            resolver.resolve(request)).asset();
                }));
            }
            start.countDown();
            var first = futures.get(0).get();
            for (var future : futures) {
                var replay = future.get();
                assertEquals(first.contentVersion(), replay.contentVersion());
                assertEquals(first.sha256(), replay.sha256());
                assertEquals(first.lease(), replay.lease());
            }
        }
        assertEquals(1, jdbc.sql("select count(*) from asset_render_selection")
                .query(Integer.class).single());
    }

    @Test
    void evaluatorConsumesTheProductionBridgeAndExcludesLeaseFromEvaluationIdentity() {
        var assetInvocation = AssetApplication.InvocationRef.serverCreated("inv-eval-asset");
        var createdAsset = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(assetInvocation, createCommand(jpegBytes(), "eval-asset-key"))
        );
        var templateInvocation = TemplateApplication.TemplateInvocationRef.serverCreated(
                "inv-eval-template");
        var createdTemplate = assertInstanceOf(
                TemplateApplication.CreatedReadable.class,
                templates.create(templateInvocation, new TemplateApplication.CreateCommand(
                        SYSTEM_EMPTY, imageDesign(createdAsset.detail().assetId())))
        );

        var first = assertInstanceOf(
                Evaluator.EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(evaluationCommand(
                        "00000000-0000-4000-8000-000000000201", createdTemplate.current().templateId())));
        var second = assertInstanceOf(
                Evaluator.EvaluationOutcome.SealedDocument.class,
                evaluator.evaluate(evaluationCommand(
                        "00000000-0000-4000-8000-000000000202", createdTemplate.current().templateId())));

        String firstDocument = new String(
                first.renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertTrue(firstDocument.contains(
                "https://render.internal.example/internal/render-assets/v1."));
        assertTrue(firstDocument.contains(createdAsset.detail().sha256()));
        assertEquals(first.evaluationResultDigest(), second.evaluationResultDigest());
        assertTrue(!first.renderDocumentDigest().equals(second.renderDocumentDigest()));
        assertEquals(2, jdbc.sql("select count(*) from asset_render_selection")
                .query(Integer.class).single());
    }

    @Test
    void signedInternalFetchServesVerifiedExactBytesAfterLifecycleChanges() throws Exception {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-fetch-1");
        byte[] original = jpegBytes();
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, createCommand(original, "fetch-key-1"))
        );
        var resolved = assertInstanceOf(
                AssetResolver.Resolved.class,
                resolver.resolve(resolveRequest(
                        "render-fetch-1",
                        "2".repeat(64),
                        created.detail().assetId(),
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        System.currentTimeMillis() + 60_000
                ))
        ).asset();
        String path = URI.create(resolved.lease().fetchUrl()).getRawPath();

        assertVerifiedFetch(path, original, "image/jpeg");

        assets.replaceContent(
                invocation,
                new AssetApplication.ReplaceContentCommand(
                        created.detail().assetId(), 0, ycbcrBytes())
        );
        jdbc.sql("""
                        update asset_aggregate
                        set lifecycle = 'DELETED', asset_revision = asset_revision + 1
                        where asset_id = :assetId
                        """)
                .param("assetId", created.detail().assetId().value())
                .update();

        assertVerifiedFetch(path, original, "image/jpeg");
        assertInstanceOf(
                AssetResolver.ResolveRejected.class,
                resolver.resolve(resolveRequest(
                        "render-fetch-1",
                        "3".repeat(64),
                        created.detail().assetId(),
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        System.currentTimeMillis() + 60_000
                ))
        );

        String tampered = path.substring(0, path.length() - 1)
                + (path.endsWith("A") ? "B" : "A");
        http.perform(get(tampered)).andExpect(status().isNotFound());
        http.perform(get(path).header("Range", "bytes=0-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void signedInternalFetchRejectsCorruptExactBlobBeforeWritingAnyBytes() throws Exception {
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-fetch-integrity");
        byte[] original = jpegBytes();
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                assets.create(invocation, createCommand(original, "fetch-integrity-key"))
        );
        var resolved = assertInstanceOf(
                AssetResolver.Resolved.class,
                resolver.resolve(resolveRequest(
                        "render-fetch-integrity",
                        "5".repeat(64),
                        created.detail().assetId(),
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        System.currentTimeMillis() + 60_000
                ))
        ).asset();
        String path = URI.create(resolved.lease().fetchUrl()).getRawPath();
        String key = "it-scope/blobs/" + resolved.sha256();

        try (S3Client s3 = s3Client()) {
            try {
                s3.putObject(PutObjectRequest.builder()
                                .bucket("renderweave-assets").key(key).build(),
                        RequestBody.fromBytes("corrupt".getBytes(StandardCharsets.UTF_8)));
                http.perform(get(path)).andExpect(status().isInternalServerError());
            } finally {
                s3.putObject(PutObjectRequest.builder()
                                .bucket("renderweave-assets").key(key).build(),
                        RequestBody.fromBytes(original));
            }
        }
        assertVerifiedFetch(path, original, "image/jpeg");
    }

    private void assertVerifiedFetch(String path, byte[] expected, String mediaType)
            throws Exception {
        MvcResult pending = http.perform(get(path))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = http.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", mediaType))
                .andExpect(header().longValue("Content-Length", expected.length))
                .andExpect(header().string("Content-Encoding", "identity"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertTrue(java.util.Arrays.equals(expected, completed.getResponse().getContentAsByteArray()));
    }

    private static AssetResolver.ResolveRequest resolveRequest(
            String renderRequestId,
            String resourceHash,
            AssetApplication.AssetId assetId,
            AssetAcceptanceAuthority.AssetKind kind,
            long deadline
    ) {
        return new AssetResolver.ResolveRequest(
                renderRequestId,
                new AssetApplication.OwnerScope("it-scope"),
                "rwres_" + resourceHash,
                assetId,
                kind,
                "renderer:v1",
                deadline
        );
    }

    private static Evaluator.EvaluationCommand evaluationCommand(
            String renderRequestId,
            TemplateApplication.TemplateId templateId
    ) {
        return new Evaluator.EvaluationCommand(
                new Evaluator.RenderRequestId(renderRequestId),
                new Evaluator.OwnerScope("it-scope"),
                "sha256:" + "5".repeat(64),
                Evaluator.ExternalAssetReadAuthorization.GRANTED,
                templateId,
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                Evaluator.OutputSelection.defaultPng(),
                "renderweave-renderer/1.0",
                System.currentTimeMillis() + 60_000L);
    }

    private static byte[] imageDesign(AssetApplication.AssetId assetId) {
        String design = """
                {"dslVersion":"renderweave-design/1.0",
                 "expressionProfile":"renderweave-expression/1.0",
                 "displayName":"Asset bridge","definitions":[],
                 "designRoot":{"nodeId":"00000000-0000-4000-8000-000000000001",
                   "kind":"canvas","widthMm":210,"heightMm":297,"bindings":[],
                   "children":[
                     {"nodeId":"00000000-0000-4000-8000-000000000002","kind":"image",
                      "bindings":[],"placement":{"type":"ABSOLUTE","xMm":1,"yMm":2,
                        "widthMode":"FIXED","widthMm":10,"heightMode":"FIXED","heightMm":5},
                      "imageRef":{"assetId":"%s"}}]}}
                """.formatted(assetId.value());
        return design.getBytes(StandardCharsets.UTF_8);
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())
                ))
                .build();
    }

    private static byte[] ycbcrBytes() throws IOException {
        try (var stream = AssetSliceIntegrationTest.class.getResourceAsStream(
                "/asset-fixtures/ycbcr-progressive.jpg")) {
            assertTrue(stream != null);
            return stream.readAllBytes();
        }
    }
}
