package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
class DomainServicesTransactionalConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXECUTION_CLASS = "EXEC::DOMAIN_SERVICES::1.0";
    private static final String TARGET_PATH =
            ".scratch/renderweave-template-v1/domain-services/execution-class-target-v1.json";
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String POSTGRES_DIGEST =
            "sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50";
    private static final AssetApplication.OwnerScope OWNER_SCOPE =
            new AssetApplication.OwnerScope("domain-conformance");
    private static final AssetApplication.AssetId COMMITTED_ASSET = AssetApplication.AssetId.of(
            "00000000-0000-4000-8000-000000000128");
    private static final AssetApplication.AssetId ROLLED_BACK_ASSET = AssetApplication.AssetId.of(
            "00000000-0000-4000-8000-000000000129");
    private static final String IDEMPOTENCY_KEY = "domain-services-transactional-replay";
    private static final String FINGERPRINT = "1".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    @Autowired
    private PostgresAssetPersistence persistence;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetAssetState() {
        jdbc.sql("""
                truncate table asset_render_selection,
                               asset_audit_event,
                               asset_idempotency,
                               asset_delete_confirmation,
                               asset_content_revision,
                               asset_aggregate
                cascade
                """).update();
        jdbc.sql("update asset_capacity set used_bytes = 0").update();
    }

    @Test
    void independentlyReplaysCommitIdempotencyAndRollbackAgainstPostgresql() throws Exception {
        assertEquals(POSTGRES_DIGEST, POSTGRES.getContainerInfo().getImageId(),
                "mutable image tag must resolve to the frozen PostgreSQL digest");
        DatabaseSnapshot empty = snapshot();
        assertEquals(DatabaseSnapshot.empty(), empty);

        var committed = persistence.create(commit(COMMITTED_ASSET, FINGERPRINT));
        assertInstanceOf(AssetPersistence.Created.class, committed);
        DatabaseSnapshot afterCommit = snapshot();
        assertEquals(new DatabaseSnapshot(1, 1, 1, 1, 332), afterCommit);
        assertEquals("ACTIVE", jdbc.sql("""
                        select lifecycle from asset_aggregate where asset_id = :assetId
                        """)
                .param("assetId", COMMITTED_ASSET.value())
                .query(String.class)
                .single());
        assertEquals(0L, jdbc.sql("""
                        select asset_revision from asset_aggregate where asset_id = :assetId
                        """)
                .param("assetId", COMMITTED_ASSET.value())
                .query(Long.class)
                .single());

        var replay = persistence.resolveIdempotency(
                new IdempotencyQuery(OWNER_SCOPE, IDEMPOTENCY_KEY, FINGERPRINT));
        assertEquals(new AssetPersistence.IdempotencyReplay(COMMITTED_ASSET), replay);
        var conflict = persistence.resolveIdempotency(
                new IdempotencyQuery(OWNER_SCOPE, IDEMPOTENCY_KEY, "2".repeat(64)));
        assertInstanceOf(AssetPersistence.IdempotencyConflict.class, conflict);
        DatabaseSnapshot afterIdempotency = snapshot();
        assertEquals(afterCommit, afterIdempotency);

        var rolledBack = persistence.create(commit(ROLLED_BACK_ASSET, "3".repeat(64)));
        assertInstanceOf(AssetPersistence.AssetIdCollision.class, rolledBack);
        DatabaseSnapshot afterRollback = snapshot();
        assertEquals(afterCommit, afterRollback);
        assertEquals(0, rowsFor(ROLLED_BACK_ASSET));

        writeReportIfRequested(empty, afterCommit, afterIdempotency, afterRollback);
    }

    private void writeReportIfRequested(
            DatabaseSnapshot empty,
            DatabaseSnapshot afterCommit,
            DatabaseSnapshot afterIdempotency,
            DatabaseSnapshot afterRollback
    ) throws Exception {
        String reportPath = System.getProperty("renderweave.domainServices.transactionalReport");
        if (reportPath == null) {
            return;
        }
        String targetPath = System.getProperty("renderweave.domainServices.executionClassTarget");
        assertTrue(targetPath != null, "exact execution-class target is required for report issuance");
        byte[] targetBytes = Files.readAllBytes(Path.of(targetPath));
        JsonNode target = JSON.readTree(targetBytes);
        assertEquals("renderweave-domain-services-execution-class-target/1.0",
                target.get("artifactVersion").asText());
        assertEquals("DOMAIN_SERVICES_TARGET::ASSET_AND_POSTGRESQL::1.0",
                target.get("targetId").asText());
        assertEquals(EXECUTION_CLASS, target.get("executionClass").asText());

        var targetBinding = orderedMap(
                "path", TARGET_PATH,
                "sha256", "sha256:" + sha256(targetBytes),
                "byteLength", targetBytes.length
        );
        var postgresql = orderedMap(
                "imageReference", POSTGRES_IMAGE,
                "expectedImageDigest", POSTGRES_DIGEST,
                "runtimeImageDigest", POSTGRES.getContainerInfo().getImageId(),
                "serverVersion", jdbc.sql("show server_version").query(String.class).single(),
                "requiredMigrations", List.of(
                        "V028__asset_aggregate_and_content.sql",
                        "V029__asset_audit_events.sql"
                )
        );
        var scenarios = List.of(
                orderedMap(
                        "scenarioId", "DOMAIN_TX::COMMIT_CREATE",
                        "outcome", "CREATED",
                        "transactionCommitted", true,
                        "before", empty.asMap(),
                        "after", afterCommit.asMap()
                ),
                orderedMap(
                        "scenarioId", "DOMAIN_TX::IDEMPOTENCY_READS",
                        "outcome", List.of("REPLAY", "CONFLICT"),
                        "transactionCommitted", false,
                        "snapshotUnchanged", afterCommit.equals(afterIdempotency),
                        "after", afterIdempotency.asMap()
                ),
                orderedMap(
                        "scenarioId", "DOMAIN_TX::DUPLICATE_KEY_ROLLBACK",
                        "outcome", "ASSET_ID_COLLISION",
                        "transactionCommitted", false,
                        "attemptedAssetRowCount", rowsFor(ROLLED_BACK_ASSET),
                        "snapshotUnchanged", afterCommit.equals(afterRollback),
                        "after", afterRollback.asMap()
                )
        );
        var boundary = orderedMap(
                "rawAssetBytesRead", false,
                "externalNetworkAllowed", false,
                "rendererInvoked", false,
                "candidateCaseCount", 12,
                "formalRecordsIssued", 0,
                "recordIssuanceAllowed", false
        );
        var report = orderedMap(
                "reportVersion", "renderweave-domain-services-transactional-replay/1",
                "engine", "java-postgresql-integration",
                "role", "transactional-integration-replayer",
                "assurance", "A2_INDEPENDENT_PRODUCT_REPLAY",
                "executionClass", EXECUTION_CLASS,
                "targetManifest", targetBinding,
                "implementationRevision", target.get("implementationRevision").asText(),
                "postgresql", postgresql,
                "scenarioCount", scenarios.size(),
                "passed", scenarios.size(),
                "failed", 0,
                "scenarios", scenarios,
                "boundary", boundary
        );
        Path output = Path.of(reportPath);
        byte[] reportBytes = (JSON.writeValueAsString(report) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(output, reportBytes, StandardOpenOption.CREATE_NEW);
    }

    private DatabaseSnapshot snapshot() {
        return new DatabaseSnapshot(
                count("asset_aggregate"),
                count("asset_content_revision"),
                count("asset_audit_event"),
                count("asset_idempotency"),
                jdbc.sql("""
                                select coalesce(
                                    (select used_bytes from asset_capacity where deployment_id = 'default'),
                                    0
                                )
                                """)
                        .query(Long.class)
                        .single()
        );
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private long rowsFor(AssetApplication.AssetId assetId) {
        return jdbc.sql("""
                        select count(*) from asset_aggregate where asset_id = :assetId
                        """)
                .param("assetId", assetId.value())
                .query(Long.class)
                .single();
    }

    private static AssetPersistence.CreateCommit commit(
            AssetApplication.AssetId assetId,
            String fingerprint
    ) {
        return new CreateCommit(
                assetId,
                OWNER_SCOPE,
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                "Domain conformance",
                List.of("conformance"),
                "domain.jpg",
                0,
                0,
                "a".repeat(64),
                "image/jpeg",
                332,
                new AssetAcceptanceAuthority.ImageDescriptor(
                        1,
                        1,
                        AssetAcceptanceAuthority.Orientation.IDENTITY,
                        1,
                        1,
                        1,
                        AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT
                ),
                IDEMPOTENCY_KEY,
                fingerprint,
                true,
                "domain-conformance"
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> orderedMap(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private record DatabaseSnapshot(
            long aggregateCount,
            long contentRevisionCount,
            long auditEventCount,
            long idempotencyCount,
            long usedBytes
    ) {
        static DatabaseSnapshot empty() {
            return new DatabaseSnapshot(0, 0, 0, 0, 0);
        }

        Map<String, Object> asMap() {
            return orderedMap(
                    "aggregateCount", aggregateCount,
                    "contentRevisionCount", contentRevisionCount,
                    "auditEventCount", auditEventCount,
                    "idempotencyCount", idempotencyCount,
                    "usedBytes", usedBytes
            );
        }
    }

    private record CreateCommit(
            AssetApplication.AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            AssetAcceptanceAuthority.AssetKind kind,
            String displayName,
            List<String> tags,
            String sourceFileName,
            long assetRevision,
            long contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor,
            String idempotencyKey,
            String idempotencyFingerprint,
            boolean blobCreated,
            String actorId
    ) implements AssetPersistence.CreateCommit {
    }

    private record IdempotencyQuery(
            AssetApplication.OwnerScope ownerScope,
            String idempotencyKey,
            String fingerprint
    ) implements AssetPersistence.IdempotencyQuery {
    }
}
