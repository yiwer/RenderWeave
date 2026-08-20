package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import cn.hbads.renderweave.asset.spi.AssetReferencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class PostgresAssetPersistence implements AssetPersistence {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();
    private static final String DEPLOYMENT_ID = "default";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final long hardLimitBytes;
    private final Duration idempotencyTtl;
    private final AssetReferencePort referencePort;
    private final ObjectProvider<AssetResolutionSecrets> resolutionSecrets;

    PostgresAssetPersistence(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            @Value("${renderweave.asset.capacity.hard-limit-bytes:107374182400}") long hardLimitBytes,
            @Value("${renderweave.asset.idempotency.ttl:PT24H}") Duration idempotencyTtl,
            AssetReferencePort referencePort,
            ObjectProvider<AssetResolutionSecrets> resolutionSecrets
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.hardLimitBytes = hardLimitBytes;
        this.idempotencyTtl = idempotencyTtl;
        this.referencePort = Objects.requireNonNull(referencePort, "referencePort");
        this.resolutionSecrets = Objects.requireNonNull(resolutionSecrets, "resolutionSecrets");
    }

    @Override
    public LocateOutcome locate(AssetApplication.AssetId assetId) {
        try {
            return jdbc.sql("""
                            select asset_id, owner_scope, kind, lifecycle, asset_revision,
                                   current_content_version, display_name, tags,
                                   source_file_name, created_at, updated_at
                            from asset_aggregate
                            where asset_id = :assetId
                            """)
                    .param("assetId", assetId.value())
                    .query(PostgresAssetPersistence::metadata)
                    .optional()
                    .<LocateOutcome>map(Located::new)
                    .orElseGet(LocateNotFound::new);
        } catch (DataAccessException unavailable) {
            return new LocateUnavailable();
        }
    }

    @Override
    public LoadCurrentOutcome loadCurrent(AssetApplication.AssetId assetId) {
        try {
            return jdbc.sql("""
                            select a.asset_id, a.owner_scope, a.kind, a.lifecycle, a.asset_revision,
                                   a.current_content_version, a.display_name, a.tags,
                                   a.source_file_name, a.created_at, a.updated_at,
                                   r.content_version, r.sha256, r.media_type, r.byte_length,
                                   r.source_file_name as revision_source_file_name,
                                   r.descriptor_kind, r.descriptor_json, r.created_at as revision_created_at
                            from asset_aggregate a
                            join asset_content_revision r
                              on r.asset_id = a.asset_id
                             and r.content_version = a.current_content_version
                            where a.asset_id = :assetId
                            """)
                    .param("assetId", assetId.value())
                    .query(PostgresAssetPersistence::storedCurrent)
                    .optional()
                    .<LoadCurrentOutcome>map(CurrentLoaded::new)
                    .orElseGet(CurrentNotFound::new);
        } catch (DataAccessException unavailable) {
            return new CurrentLoadUnavailable();
        }
    }

    @Override
    public CreateOutcome create(CreateCommit commit) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                long usedBytes = readUsedBytes();
                if (commit.blobCreated() && usedBytes + commit.byteLength() > hardLimitBytes) {
                    return new StorageCapacityExceeded();
                }
                jdbc.sql("""
                                insert into asset_aggregate (
                                    asset_id, owner_scope, kind, lifecycle, asset_revision,
                                    current_content_version, display_name, tags, source_file_name
                                ) values (
                                    :assetId, :ownerScope, :kind, 'ACTIVE', 0,
                                    0, :displayName, :tags::jsonb, :sourceFileName
                                )
                                """)
                        .param("assetId", commit.assetId().value())
                        .param("ownerScope", commit.ownerScope().value())
                        .param("kind", commit.kind().name())
                        .param("displayName", commit.displayName())
                        .param("tags", writeTags(commit.tags()))
                        .param("sourceFileName", commit.sourceFileName())
                        .update();
                insertContentRevision(commit);
                insertAuditEvent(
                        commit.assetId(),
                        null,
                        0,
                        commit.actorId(),
                        AuditOperation.CREATE,
                        0
                );
                jdbc.sql("""
                                insert into asset_idempotency (
                                    owner_scope, idempotency_key, asset_id, fingerprint, expires_at
                                ) values (
                                    :ownerScope, :idempotencyKey, :assetId, :fingerprint, :expiresAt
                                )
                                """)
                        .param("ownerScope", commit.ownerScope().value())
                        .param("idempotencyKey", commit.idempotencyKey())
                        .param("assetId", commit.assetId().value())
                        .param("fingerprint", commit.idempotencyFingerprint())
                        .param("expiresAt", java.sql.Timestamp.from(Instant.now().plus(idempotencyTtl)))
                        .update();
                if (commit.blobCreated()) {
                    addUsedBytes(commit.byteLength());
                }
                return new Created();
            }));
        } catch (DuplicateKeyException collision) {
            return new AssetIdCollision();
        } catch (DataAccessException unavailable) {
            return new CreateUnavailable();
        }
    }

    @Override
    public UpdateMetadataOutcome updateMetadata(UpdateMetadataCommit commit) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                var rows = jdbc.sql("""
                                select lifecycle, asset_revision, current_content_version
                                from asset_aggregate
                                where asset_id = :assetId
                                for update
                                """)
                        .param("assetId", commit.assetId().value())
                        .query((rs, rowNum) -> new long[]{
                                "ACTIVE".equals(rs.getString("lifecycle")) ? 0 : 1,
                                rs.getLong("asset_revision"),
                                rs.getLong("current_content_version")
                        })
                        .optional();
                if (rows.isEmpty()) {
                    return new UpdateNotFound();
                }
                long[] values = rows.get();
                if (values[0] == 1) {
                    return new UpdateDeleted();
                }
                if (values[1] != commit.expectedAssetRevision()) {
                    return new UpdateRevisionConflict(values[1]);
                }
                jdbc.sql("""
                                update asset_aggregate
                                set display_name = :displayName,
                                    tags = :tags::jsonb,
                                    asset_revision = asset_revision + 1,
                                    updated_at = clock_timestamp()
                                where asset_id = :assetId
                                """)
                        .param("assetId", commit.assetId().value())
                        .param("displayName", commit.displayName())
                        .param("tags", writeTags(commit.tags()))
                        .update();
                insertAuditEvent(
                        commit.assetId(),
                        commit.expectedAssetRevision(),
                        commit.expectedAssetRevision() + 1,
                        commit.actorId(),
                        AuditOperation.METADATA_UPDATE,
                        values[2]
                );
                return new MetadataUpdated(true);
            }));
        } catch (DataAccessException unavailable) {
            return new UpdateUnavailable();
        }
    }

    @Override
    public AppendContentOutcome appendContent(AppendContentCommit commit) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                long usedBytes = readUsedBytes();
                if (commit.blobCreated() && usedBytes + commit.byteLength() > hardLimitBytes) {
                    return new AppendStorageCapacityExceeded();
                }
                var rows = jdbc.sql("""
                                select lifecycle, asset_revision
                                from asset_aggregate
                                where asset_id = :assetId
                                for update
                                """)
                        .param("assetId", commit.assetId().value())
                        .query((rs, rowNum) -> new long[]{
                                "ACTIVE".equals(rs.getString("lifecycle")) ? 0 : 1,
                                rs.getLong("asset_revision")
                        })
                        .optional();
                if (rows.isEmpty()) {
                    return new AppendNotFound();
                }
                long[] values = rows.get();
                if (values[0] == 1) {
                    return new AppendDeleted();
                }
                if (values[1] != commit.expectedAssetRevision()) {
                    return new AppendRevisionConflict(values[1]);
                }
                jdbc.sql("""
                                insert into asset_content_revision (
                                    asset_id, content_version, sha256, media_type, byte_length,
                                    source_file_name, descriptor_kind, descriptor_json
                                ) values (
                                    :assetId, :contentVersion, :sha256, :mediaType, :byteLength,
                                    :sourceFileName, :descriptorKind, :descriptorJson::jsonb
                                )
                                """)
                        .param("assetId", commit.assetId().value())
                        .param("contentVersion", commit.contentVersion())
                        .param("sha256", commit.sha256())
                        .param("mediaType", commit.mediaType())
                        .param("byteLength", commit.byteLength())
                        .param("sourceFileName", commit.sourceFileName())
                        .param("descriptorKind", descriptorKind(commit.descriptor()))
                        .param("descriptorJson", writeDescriptor(commit.descriptor()))
                        .update();
                jdbc.sql("""
                                update asset_aggregate
                                set current_content_version = :contentVersion,
                                    asset_revision = asset_revision + 1,
                                    updated_at = clock_timestamp()
                                where asset_id = :assetId
                                """)
                        .param("contentVersion", commit.contentVersion())
                        .param("assetId", commit.assetId().value())
                        .update();
                insertAuditEvent(
                        commit.assetId(),
                        commit.expectedAssetRevision(),
                        commit.expectedAssetRevision() + 1,
                        commit.actorId(),
                        commit.operation(),
                        commit.contentVersion()
                );
                if (commit.blobCreated()) {
                    addUsedBytes(commit.byteLength());
                }
                return new ContentAppended();
            }));
        } catch (DataAccessException unavailable) {
            return new AppendUnavailable();
        }
    }

    @Override
    public ContentVersionOutcome loadContentVersion(
            AssetApplication.AssetId assetId,
            long contentVersion
    ) {
        try {
            return jdbc.sql("""
                            select content_version, sha256, media_type, byte_length,
                                   source_file_name, descriptor_kind, descriptor_json, created_at
                            from asset_content_revision
                            where asset_id = :assetId
                              and content_version = :contentVersion
                            """)
                    .param("assetId", assetId.value())
                    .param("contentVersion", contentVersion)
                    .query(PostgresAssetPersistence::storedContentRow)
                    .optional()
                    .<ContentVersionOutcome>map(ContentVersionLoaded::new)
                    .orElseGet(ContentVersionNotFound::new);
        } catch (DataAccessException unavailable) {
            return new ContentVersionUnavailable();
        }
    }

    @Override
    public CatalogOutcome catalog(CatalogQuery query) {
        try {
            StringBuilder sql = new StringBuilder("""
                    select asset_id, kind, lifecycle, display_name, tags, source_file_name, updated_at
                    from asset_aggregate
                    where owner_scope = :ownerScope
                    """);
            if (!query.includeDeleted()) {
                sql.append(" and lifecycle = 'ACTIVE'");
            }
            if (query.kind() != null) {
                sql.append(" and kind = :kind");
            }
            if (!query.tagsAll().isEmpty()) {
                sql.append(" and tags::jsonb @> :tagsAll::jsonb");
            }
            if (!query.tagsAny().isEmpty()) {
                sql.append(" and jsonb_exists_any(tags, :tagsAny)");
            }
            if (query.displayNameContains() != null) {
                sql.append(" and display_name ilike :displayNameContains");
            }
            if (query.sourceFileNameContains() != null) {
                sql.append(" and source_file_name ilike :sourceFileNameContains");
            }
            if (query.cursor() != null) {
                var cursor = decodeCursor(query.cursor());
                sql.append(" and (updated_at < :cursorUpdatedAt::timestamptz"
                        + " or (updated_at = :cursorUpdatedAt::timestamptz and asset_id > :cursorAssetId))");
            }
            sql.append(" order by updated_at desc, asset_id asc limit :limit");

            var spec = jdbc.sql(sql.toString())
                    .param("ownerScope", query.ownerScope().value())
                    .param("limit", query.limit() + 1);
            if (query.kind() != null) {
                spec.param("kind", query.kind().name());
            }
            if (!query.tagsAll().isEmpty()) {
                spec.param("tagsAll", writeTags(query.tagsAll()));
            }
            if (!query.tagsAny().isEmpty()) {
                spec.param("tagsAny", query.tagsAny().toArray(new String[0]));
            }
            if (query.displayNameContains() != null) {
                spec.param("displayNameContains", "%" + query.displayNameContains() + "%");
            }
            if (query.sourceFileNameContains() != null) {
                spec.param("sourceFileNameContains", "%" + query.sourceFileNameContains() + "%");
            }
            if (query.cursor() != null) {
                var cursor = decodeCursor(query.cursor());
                spec.param("cursorUpdatedAt", cursor.updatedAt());
                spec.param("cursorAssetId", cursor.assetId());
            }
            List<CatalogEntry> entries = spec.query(PostgresAssetPersistence::catalogEntry).list();
            Optional<String> nextCursor = Optional.empty();
            if (entries.size() > query.limit()) {
                entries = new ArrayList<>(entries.subList(0, query.limit()));
                var last = entries.get(entries.size() - 1);
                nextCursor = Optional.of(encodeCursor(last.updatedAt(), last.assetId().value()));
            }
            return new CatalogPage(entries, nextCursor);
        } catch (DataAccessException unavailable) {
            return new CatalogUnavailable();
        }
    }

    @Override
    public VersionsOutcome listContentVersions(AssetApplication.AssetId assetId) {
        try {
            var exists = jdbc.sql("""
                            select count(*) from asset_aggregate where asset_id = :assetId
                            """)
                    .param("assetId", assetId.value())
                    .query(Integer.class)
                    .single();
            if (exists == 0) {
                return new VersionsNotFound();
            }
            List<ContentVersionEntry> entries = jdbc.sql("""
                            select content_version, sha256, media_type, byte_length,
                                   source_file_name, created_at
                            from asset_content_revision
                            where asset_id = :assetId
                            order by content_version
                            """)
                    .param("assetId", assetId.value())
                    .query(PostgresAssetPersistence::contentVersionEntry)
                    .list();
            return new VersionsListed(entries);
        } catch (DataAccessException unavailable) {
            return new VersionsUnavailable();
        }
    }

    @Override
    public IdempotencyOutcome resolveIdempotency(IdempotencyQuery query) {        try {
            return jdbc.sql("""
                            select asset_id, fingerprint
                            from asset_idempotency
                            where owner_scope = :ownerScope
                              and idempotency_key = :idempotencyKey
                              and expires_at > clock_timestamp()
                            """)
                    .param("ownerScope", query.ownerScope().value())
                    .param("idempotencyKey", query.idempotencyKey())
                    .query((rs, rowNum) -> new String[]{rs.getString("asset_id"), rs.getString("fingerprint")})
                    .optional()
                    .<IdempotencyOutcome>map(row -> {
                        if (!query.fingerprint().equals(row[1])) {
                            return new IdempotencyConflict();
                        }
                        return new IdempotencyReplay(AssetApplication.AssetId.of(row[0]));
                    })
                    .orElseGet(IdempotencyMiss::new);
        } catch (DataAccessException unavailable) {
            return new IdempotencyUnavailable();
        }
    }

    @Override
    public CapacityOutcome capacity() {
        try {
            return new Capacity(hardLimitBytes, readUsedBytes());
        } catch (DataAccessException unavailable) {
            return new CapacityUnavailable();
        }
    }

    @Override
    public IssueDeleteConfirmationOutcome issueDeleteConfirmation(
            IssueDeleteConfirmationCommit commit
    ) {
        try {
            jdbc.sql("""
                            insert into asset_delete_confirmation (
                                confirmation_token, owner_scope, asset_id, actor_id,
                                asset_revision, reference_fingerprint, expires_at
                            ) values (
                                :confirmationToken, :ownerScope, :assetId, :actorId,
                                :assetRevision, :referenceFingerprint, :expiresAt
                            )
                            """)
                    .param("confirmationToken", commit.confirmationToken())
                    .param("ownerScope", commit.ownerScope().value())
                    .param("assetId", commit.assetId().value())
                    .param("actorId", commit.actorId())
                    .param("assetRevision", commit.assetRevision())
                    .param("referenceFingerprint", commit.referenceFingerprint())
                    .param("expiresAt", java.sql.Timestamp.from(commit.expiresAt()))
                    .update();
            return new ConfirmationIssued();
        } catch (DuplicateKeyException collision) {
            return new ConfirmationUnavailable();
        } catch (DataAccessException unavailable) {
            return new ConfirmationUnavailable();
        }
    }

    @Override
    public DeleteOutcome delete(DeleteCommit commit) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                // Exclusive reservation: the confirmed delete linearizes against every
                // Template current change that references this Asset (they hold FOR SHARE
                // on the same row in ascending assetId order).
                var assetRow = jdbc.sql("""
                                select lifecycle, asset_revision, current_content_version
                                from asset_aggregate
                                where asset_id = :assetId
                                for update
                                """)
                        .param("assetId", commit.assetId().value())
                        .query((rs, rowNum) -> new long[]{
                                "ACTIVE".equals(rs.getString("lifecycle")) ? 0 : 1,
                                rs.getLong("asset_revision"),
                                rs.getLong("current_content_version")
                        })
                        .optional();
                if (assetRow.isEmpty()) {
                    return new DeleteNotFound();
                }
                long[] assetFacts = assetRow.get();
                if (assetFacts[0] == 1) {
                    return new DeleteDeleted();
                }

                var tokenRow = jdbc.sql("""
                                select owner_scope, asset_id, actor_id, asset_revision,
                                       reference_fingerprint, expires_at, used_at
                                from asset_delete_confirmation
                                where confirmation_token = :confirmationToken
                                for update
                                """)
                        .param("confirmationToken", commit.confirmationToken())
                        .query((rs, rowNum) -> new Object[]{
                                rs.getString("owner_scope"),
                                rs.getString("asset_id"),
                                rs.getString("actor_id"),
                                rs.getLong("asset_revision"),
                                rs.getString("reference_fingerprint"),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                rs.getObject("used_at", OffsetDateTime.class)
                        })
                        .optional();
                if (tokenRow.isEmpty()) {
                    return new DeleteConfirmationRequired();
                }
                Object[] tokenFacts = tokenRow.get();
                if (tokenFacts[6] != null) {
                    return new DeleteConfirmationStale();
                }
                if (!((Instant) tokenFacts[5]).isAfter(Instant.now())) {
                    return new DeleteConfirmationExpired();
                }
                if (!commit.ownerScope().value().equals(tokenFacts[0])
                        || !commit.assetId().value().equals(tokenFacts[1])
                        || !commit.actorId().equals(tokenFacts[2])
                        || assetFacts[1] != (Long) tokenFacts[3]) {
                    return new DeleteConfirmationStale();
                }

                // Recompute the reference proof under the exclusive reservation; any
                // reference drift invalidates the confirmation with zero writes.
                var recomputed = referencePort.references(
                        AssetApplication.InvocationRef.serverCreated(commit.actorId()),
                        commit.assetId()
                );
                if (recomputed instanceof AssetReferencePort.ReferencesUnavailable) {
                    return new DeleteDependencyUnavailable();
                }
                var fingerprint = ((AssetReferencePort.ReferencesReadable) recomputed)
                        .proof()
                        .referenceFingerprint();
                if (!fingerprint.equals(tokenFacts[4])) {
                    return new DeleteConfirmationStale();
                }

                jdbc.sql("""
                                update asset_delete_confirmation
                                set used_at = clock_timestamp()
                                where confirmation_token = :confirmationToken
                                """)
                        .param("confirmationToken", commit.confirmationToken())
                        .update();
                jdbc.sql("""
                                update asset_aggregate
                                set lifecycle = 'DELETED',
                                    asset_revision = asset_revision + 1,
                                    updated_at = clock_timestamp()
                                where asset_id = :assetId
                                """)
                        .param("assetId", commit.assetId().value())
                        .update();
                insertAuditEvent(
                        commit.assetId(),
                        assetFacts[1],
                        assetFacts[1] + 1,
                        commit.actorId(),
                        AuditOperation.DELETE,
                        assetFacts[2]
                );
                return new Deleted();
            }));
        } catch (DataAccessException unavailable) {
            return new DeleteUnavailable();
        }
    }

    @Override
    public RestoreLifecycleOutcome restore(RestoreLifecycleCommit commit) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                var assetRow = jdbc.sql("""
                                select lifecycle, asset_revision, current_content_version
                                from asset_aggregate
                                where asset_id = :assetId
                                for update
                                """)
                        .param("assetId", commit.assetId().value())
                        .query((rs, rowNum) -> new long[]{
                                "ACTIVE".equals(rs.getString("lifecycle")) ? 0 : 1,
                                rs.getLong("asset_revision"),
                                rs.getLong("current_content_version")
                        })
                        .optional();
                if (assetRow.isEmpty()) {
                    return new RestoreNotFound();
                }
                long[] assetFacts = assetRow.get();
                if (assetFacts[0] == 0) {
                    return new RestoreActive();
                }
                if (assetFacts[1] != commit.expectedAssetRevision()) {
                    return new RestoreRevisionConflict(assetFacts[1]);
                }
                jdbc.sql("""
                                update asset_aggregate
                                set lifecycle = 'ACTIVE',
                                    asset_revision = asset_revision + 1,
                                    updated_at = clock_timestamp()
                                where asset_id = :assetId
                                """)
                        .param("assetId", commit.assetId().value())
                        .update();
                insertAuditEvent(
                        commit.assetId(),
                        assetFacts[1],
                        assetFacts[1] + 1,
                        commit.actorId(),
                        AuditOperation.RESTORE,
                        assetFacts[2]
                );
                return new Restored();
            }));
        } catch (DataAccessException unavailable) {
            return new RestoreUnavailable();
        }
    }

    @Override
    public RenderPrecheckOutcome precheckForRender(RenderPrecheckQuery query) {
        try {
            var found = jdbc.sql("""
                            select owner_scope, kind, lifecycle
                            from asset_aggregate
                            where asset_id = :assetId
                            """)
                    .param("assetId", query.assetId().value())
                    .query((rs, rowNum) -> new String[]{
                            rs.getString("owner_scope"),
                            rs.getString("kind"),
                            rs.getString("lifecycle")
                    })
                    .optional();
            if (found.isEmpty()) {
                return new RenderPrecheckRejected(RenderRejection.NOT_FOUND);
            }
            String[] facts = found.get();
            if (!query.ownerScope().value().equals(facts[0])) {
                return new RenderPrecheckRejected(RenderRejection.SCOPE_MISMATCH);
            }
            if (!"ACTIVE".equals(facts[2])) {
                return new RenderPrecheckRejected(RenderRejection.DELETED);
            }
            if (!query.expectedKind().name().equals(facts[1])) {
                return new RenderPrecheckRejected(RenderRejection.KIND_MISMATCH);
            }
            return new RenderPrecheckPassed();
        } catch (DataAccessException unavailable) {
            return new RenderPrecheckUnavailable();
        }
    }

    @Override
    public RenderSelectionOutcome resolveForRender(RenderSelectionQuery query) {
        AssetResolutionSecrets secrets = resolutionSecrets.getIfAvailable();
        if (secrets == null) {
            return new RenderSelectionUnavailable();
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                // The advisory lock closes the absent-row race; the table PK remains the
                // durable uniqueness authority. A hash collision only serializes unrelated keys.
                jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:selectionKey, 0))")
                        .param(
                                "selectionKey",
                                query.renderRequestId().length() + ":" + query.renderRequestId()
                                        + query.resourceId()
                        )
                        .query((rs, rowNum) -> 1)
                        .single();

                var existing = findSelection(query.renderRequestId(), query.resourceId());
                if (existing.isPresent()) {
                    var row = existing.get();
                    if (row.header().recordExpiresAtEpochMilli() <= query.issuedAtEpochMilli()) {
                        jdbc.sql("""
                                        delete from asset_render_selection
                                        where render_request_id = :renderRequestId
                                          and resource_id = :resourceId
                                        """)
                                .param("renderRequestId", query.renderRequestId())
                                .param("resourceId", query.resourceId())
                                .update();
                    } else if (!row.header().requestFingerprint()
                            .equals(query.requestFingerprint())) {
                        return new RenderSelectionConflict();
                    } else {
                        try {
                            return new RenderSelectionResolved(secrets.open(
                                    row.header(), row.nonce(), row.ciphertext()));
                        } catch (AssetResolutionSecrets.SecretFailure corrupt) {
                            return new RenderSelectionUnavailable();
                        }
                    }
                }

                var candidate = jdbc.sql("""
                                select a.owner_scope, a.kind, a.lifecycle,
                                       r.content_version, r.sha256, r.media_type, r.byte_length,
                                       r.descriptor_kind, r.descriptor_json
                                from asset_aggregate a
                                join asset_content_revision r
                                  on r.asset_id = a.asset_id
                                 and r.content_version = a.current_content_version
                                where a.asset_id = :assetId
                                for share of a
                                """)
                        .param("assetId", query.assetId().value())
                        .query((rs, rowNum) -> new ResolutionCandidate(
                                rs.getString("owner_scope"),
                                AssetAcceptanceAuthority.AssetKind.valueOf(rs.getString("kind")),
                                AssetApplication.Lifecycle.valueOf(rs.getString("lifecycle")),
                                new ResolutionContent(
                                        rs.getLong("content_version"),
                                        rs.getString("sha256"),
                                        rs.getString("media_type"),
                                        rs.getLong("byte_length"),
                                        readDescriptor(
                                                rs.getString("descriptor_kind"),
                                                rs.getString("descriptor_json"))
                                )
                        ))
                        .optional();
                if (candidate.isEmpty()) {
                    return new RenderSelectionRejected(RenderRejection.NOT_FOUND);
                }
                ResolutionCandidate facts = candidate.get();
                if (!query.ownerScope().value().equals(facts.ownerScope())) {
                    return new RenderSelectionRejected(RenderRejection.SCOPE_MISMATCH);
                }
                if (facts.lifecycle() != AssetApplication.Lifecycle.ACTIVE) {
                    return new RenderSelectionRejected(RenderRejection.DELETED);
                }
                if (facts.kind() != query.expectedKind()) {
                    return new RenderSelectionRejected(RenderRejection.KIND_MISMATCH);
                }

                var selection = new RenderSelection(
                        query.renderRequestId(),
                        query.ownerScope(),
                        query.resourceId(),
                        query.assetId(),
                        query.expectedKind(),
                        query.rendererAudience(),
                        query.requestFingerprint(),
                        secrets.newLeaseHandle(),
                        facts.content(),
                        query.issuedAtEpochMilli(),
                        query.leaseExpiresAtEpochSecond(),
                        query.recordExpiresAtEpochMilli()
                );
                AssetResolutionSecrets.SealedSelection sealed;
                try {
                    sealed = secrets.seal(selection);
                } catch (AssetResolutionSecrets.SecretFailure unavailable) {
                    return new RenderSelectionUnavailable();
                }
                jdbc.sql("""
                                insert into asset_render_selection (
                                    render_request_id, resource_id, request_fingerprint,
                                    lease_handle, selection_nonce, selection_cipher,
                                    issued_at, lease_expires_at, record_expires_at
                                ) values (
                                    :renderRequestId, :resourceId, :requestFingerprint,
                                    :leaseHandle, :selectionNonce, :selectionCipher,
                                    :issuedAt, :leaseExpiresAt, :recordExpiresAt
                                )
                                """)
                        .param("renderRequestId", selection.renderRequestId())
                        .param("resourceId", selection.resourceId())
                        .param("requestFingerprint", selection.requestFingerprint())
                        .param("leaseHandle", selection.leaseHandle())
                        .param("selectionNonce", sealed.nonce())
                        .param("selectionCipher", sealed.ciphertext())
                        .param("issuedAt", selection.issuedAtEpochMilli())
                        .param("leaseExpiresAt", selection.leaseExpiresAtEpochSecond())
                        .param("recordExpiresAt", selection.recordExpiresAtEpochMilli())
                        .update();
                return new RenderSelectionResolved(selection);
            }));
        } catch (RuntimeException unavailable) {
            return new RenderSelectionUnavailable();
        }
    }

    @Override
    public RenderLeaseLoadOutcome loadRenderSelection(RenderLeaseLookup lookup) {
        AssetResolutionSecrets secrets = resolutionSecrets.getIfAvailable();
        if (secrets == null) {
            return new RenderLeaseUnavailable();
        }
        try {
            var found = jdbc.sql("""
                            select render_request_id, resource_id, request_fingerprint,
                                   lease_handle, selection_nonce, selection_cipher,
                                   issued_at, lease_expires_at, record_expires_at
                            from asset_render_selection
                            where lease_handle = :leaseHandle
                            """)
                    .param("leaseHandle", lookup.leaseHandle())
                    .query(PostgresAssetPersistence::encryptedSelectionRow)
                    .optional();
            if (found.isEmpty()
                    || found.get().header().recordExpiresAtEpochMilli()
                    <= System.currentTimeMillis()) {
                return new RenderLeaseNotFound();
            }
            try {
                return new RenderLeaseLoaded(secrets.open(
                        found.get().header(), found.get().nonce(), found.get().ciphertext()));
            } catch (AssetResolutionSecrets.SecretFailure corrupt) {
                return new RenderLeaseNotFound();
            }
        } catch (DataAccessException unavailable) {
            return new RenderLeaseUnavailable();
        }
    }

    int sweepExpiredRenderSelections() {
        return jdbc.sql("""
                        delete from asset_render_selection
                        where record_expires_at <= :now
                        """)
                .param("now", System.currentTimeMillis())
                .update();
    }

    private Optional<EncryptedSelectionRow> findSelection(
            String renderRequestId,
            String resourceId
    ) {
        return jdbc.sql("""
                        select render_request_id, resource_id, request_fingerprint,
                               lease_handle, selection_nonce, selection_cipher,
                               issued_at, lease_expires_at, record_expires_at
                        from asset_render_selection
                        where render_request_id = :renderRequestId
                          and resource_id = :resourceId
                        for update
                        """)
                .param("renderRequestId", renderRequestId)
                .param("resourceId", resourceId)
                .query(PostgresAssetPersistence::encryptedSelectionRow)
                .optional();
    }

    private void insertContentRevision(CreateCommit commit) {
        jdbc.sql("""
                        insert into asset_content_revision (
                            asset_id, content_version, sha256, media_type, byte_length,
                            source_file_name, descriptor_kind, descriptor_json
                        ) values (
                            :assetId, :contentVersion, :sha256, :mediaType, :byteLength,
                            :sourceFileName, :descriptorKind, :descriptorJson::jsonb
                        )
                        """)
                .param("assetId", commit.assetId().value())
                .param("contentVersion", commit.contentVersion())
                .param("sha256", commit.sha256())
                .param("mediaType", commit.mediaType())
                .param("byteLength", commit.byteLength())
                .param("sourceFileName", commit.sourceFileName())
                .param("descriptorKind", descriptorKind(commit.descriptor()))
                .param("descriptorJson", writeDescriptor(commit.descriptor()))
                .update();
    }

    private long readUsedBytes() {
        jdbc.sql("""
                        insert into asset_capacity (deployment_id, used_bytes)
                        values (:deploymentId, 0)
                        on conflict (deployment_id) do nothing
                        """)
                .param("deploymentId", DEPLOYMENT_ID)
                .update();
        return jdbc.sql("""
                        select used_bytes from asset_capacity where deployment_id = :deploymentId
                        """)
                .param("deploymentId", DEPLOYMENT_ID)
                .query(Long.class)
                .single();
    }

    private void addUsedBytes(long bytes) {
        jdbc.sql("""
                        update asset_capacity
                        set used_bytes = used_bytes + :bytes
                        where deployment_id = :deploymentId
                        """)
                .param("bytes", bytes)
                .param("deploymentId", DEPLOYMENT_ID)
                .update();
    }

    private void insertAuditEvent(
            AssetApplication.AssetId assetId,
            Long beforeAssetRevision,
            long afterAssetRevision,
            String actorId,
            AuditOperation operation,
            long contentVersion
    ) {
        jdbc.sql("""
                        insert into asset_audit_event (
                            asset_id, before_asset_revision, after_asset_revision,
                            actor_id, operation_type, content_version
                        ) values (
                            :assetId, :beforeAssetRevision, :afterAssetRevision,
                            :actorId, :operationType, :contentVersion
                        )
                        """)
                .param("assetId", assetId.value())
                .param("beforeAssetRevision", beforeAssetRevision)
                .param("afterAssetRevision", afterAssetRevision)
                .param("actorId", actorId)
                .param("operationType", operation.name())
                .param("contentVersion", contentVersion)
                .update();
    }

    private String writeTags(List<String> tags) {
        try {
            return json.writeValueAsString(tags);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String writeDescriptor(AssetAcceptanceAuthority.TechnicalDescriptor descriptor) {
        try {
            return json.writeValueAsString(descriptor);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String descriptorKind(AssetAcceptanceAuthority.TechnicalDescriptor descriptor) {
        return descriptor instanceof AssetAcceptanceAuthority.ImageDescriptor ? "IMAGE" : "FONT";
    }

    private static AssetMetadata metadata(ResultSet rs, int rowNum) throws SQLException {
        return new AssetMetadata(
                AssetApplication.AssetId.of(rs.getString("asset_id")),
                new AssetApplication.OwnerScope(rs.getString("owner_scope")),
                AssetAcceptanceAuthority.AssetKind.valueOf(rs.getString("kind")),
                AssetApplication.Lifecycle.valueOf(rs.getString("lifecycle")),
                rs.getLong("asset_revision"),
                rs.getLong("current_content_version"),
                rs.getString("display_name"),
                readTags(rs.getString("tags")),
                rs.getString("source_file_name"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static StoredCurrent storedCurrent(ResultSet rs, int rowNum) throws SQLException {
        AssetMetadata metadata = new AssetMetadata(
                AssetApplication.AssetId.of(rs.getString("asset_id")),
                new AssetApplication.OwnerScope(rs.getString("owner_scope")),
                AssetAcceptanceAuthority.AssetKind.valueOf(rs.getString("kind")),
                AssetApplication.Lifecycle.valueOf(rs.getString("lifecycle")),
                rs.getLong("asset_revision"),
                rs.getLong("current_content_version"),
                rs.getString("display_name"),
                readTags(rs.getString("tags")),
                rs.getString("source_file_name"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
        StoredContent content = new StoredContent(
                rs.getLong("content_version"),
                rs.getString("sha256"),
                rs.getString("media_type"),
                rs.getLong("byte_length"),
                rs.getString("revision_source_file_name"),
                readDescriptor(rs.getString("descriptor_kind"), rs.getString("descriptor_json")),
                rs.getObject("revision_created_at", OffsetDateTime.class).toInstant()
        );
        return new StoredCurrent(metadata, content);
    }

    private static CatalogEntry catalogEntry(ResultSet rs, int rowNum) throws SQLException {
        return new CatalogEntry(
                AssetApplication.AssetId.of(rs.getString("asset_id")),
                AssetAcceptanceAuthority.AssetKind.valueOf(rs.getString("kind")),
                AssetApplication.Lifecycle.valueOf(rs.getString("lifecycle")),
                rs.getString("display_name"),
                readTags(rs.getString("tags")),
                rs.getString("source_file_name"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static ContentVersionEntry contentVersionEntry(ResultSet rs, int rowNum) throws SQLException {
        return new ContentVersionEntry(
                rs.getLong("content_version"),
                rs.getString("sha256"),
                rs.getString("media_type"),
                rs.getLong("byte_length"),
                rs.getString("source_file_name"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private static StoredContent storedContentRow(ResultSet rs, int rowNum) throws SQLException {
        return new StoredContent(
                rs.getLong("content_version"),
                rs.getString("sha256"),
                rs.getString("media_type"),
                rs.getLong("byte_length"),
                rs.getString("source_file_name"),
                readDescriptor(rs.getString("descriptor_kind"), rs.getString("descriptor_json")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private static EncryptedSelectionRow encryptedSelectionRow(ResultSet rs, int rowNum)
            throws SQLException {
        return new EncryptedSelectionRow(
                new AssetResolutionSecrets.RecordHeader(
                        rs.getString("render_request_id"),
                        rs.getString("resource_id"),
                        rs.getString("request_fingerprint"),
                        rs.getString("lease_handle"),
                        rs.getLong("issued_at"),
                        rs.getLong("lease_expires_at"),
                        rs.getLong("record_expires_at")
                ),
                rs.getBytes("selection_nonce"),
                rs.getBytes("selection_cipher")
        );
    }

    private static List<String> readTags(String raw) {
        try {
            return STATIC_JSON.readValue(raw, STRING_LIST);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AssetAcceptanceAuthority.TechnicalDescriptor readDescriptor(
            String kind,
            String raw
    ) {
        try {
            JsonNode node = STATIC_JSON.readTree(raw);
            if ("IMAGE".equals(kind)) {
                return new AssetAcceptanceAuthority.ImageDescriptor(
                        node.get("encodedWidthPx").asInt(),
                        node.get("encodedHeightPx").asInt(),
                        AssetAcceptanceAuthority.Orientation.valueOf(node.get("orientation").asText()),
                        node.get("logicalWidthPx").asInt(),
                        node.get("logicalHeightPx").asInt(),
                        node.get("frameCount").asInt(),
                        AssetAcceptanceAuthority.ColorEncoding.valueOf(node.get("colorEncoding").asText())
                );
            }
            return new AssetAcceptanceAuthority.FontDescriptor(
                    node.get("faceIndex").asInt(),
                    AssetAcceptanceAuthority.FontFlavor.valueOf(node.get("flavor").asText()),
                    node.get("unitsPerEm").asInt()
            );
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encodeCursor(Instant updatedAt, String assetId) {
        String payload = updatedAt.toEpochMilli() + "|" + assetId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant updatedAt, String assetId) {
    }

    private record ResolutionCandidate(
            String ownerScope,
            AssetAcceptanceAuthority.AssetKind kind,
            AssetApplication.Lifecycle lifecycle,
            ResolutionContent content
    ) {
    }

    private record EncryptedSelectionRow(
            AssetResolutionSecrets.RecordHeader header,
            byte[] nonce,
            byte[] ciphertext
    ) {
    }

    private static Cursor decodeCursor(String cursor) {
        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
            String[] parts = payload.split("\\|", 2);
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), parts[1]);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("malformed catalog cursor");
        }
    }
}
