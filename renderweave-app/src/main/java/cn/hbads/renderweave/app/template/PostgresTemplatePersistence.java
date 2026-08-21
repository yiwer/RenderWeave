package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.TemplateDependencySnapshot;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Repository
public class PostgresTemplatePersistence implements TemplatePersistence {
    private static final int SERIALIZABLE_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PostgresTemplatePersistence(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
        try {
            return jdbc.sql("""
                            select template_id, owner_scope, schema_key, schema_version_tag,
                                   current_revision, lifecycle
                            from template_aggregate
                            where template_id = :templateId
                            """)
                    .param("templateId", templateId.value())
                    .query(PostgresTemplatePersistence::metadata)
                    .optional()
                    .<LocateOutcome>map(Located::new)
                    .orElseGet(LocateNotFound::new);
        } catch (DataAccessException unavailable) {
            return new LocateUnavailable();
        }
    }

    @Override
    public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
        try {
            return jdbc.sql("""
                            select a.template_id, a.owner_scope, a.schema_key,
                                   a.schema_version_tag, a.current_revision, a.lifecycle,
                                   a.readiness, r.design_dsl::text as stored_json,
                                   r.canonical_design_dsl, r.content_hash
                            from template_aggregate a
                            join template_revision r
                              on r.template_id = a.template_id
                             and r.revision = a.current_revision
                            where a.template_id = :templateId
                            """)
                    .param("templateId", templateId.value())
                    .query(PostgresTemplatePersistence::storedCurrent)
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
            return executeSerializable(() -> {
                if (!snapshotMatches(commit.dependencySnapshot())) {
                    return new CreateDependencyDrift();
                }
                jdbc.sql("""
                                insert into template_aggregate (
                                    template_id, owner_scope, schema_key, schema_version_tag,
                                    current_revision, lifecycle, readiness
                                ) values (
                                    :templateId, :ownerScope, :schemaKey, :versionTag,
                                    :revision, 'ACTIVE', :readiness
                                )
                                """)
                        .param("templateId", commit.templateId().value())
                        .param("ownerScope", commit.ownerScope().value())
                        .param("schemaKey", commit.staticSchema().schemaKey().value())
                        .param("versionTag", commit.staticSchema().versionTag().value())
                        .param("revision", commit.revision())
                        .param("readiness", commit.readiness().name())
                        .update();
                insertRevision(commit);
                acquireAssetReadReservations(commit.projection());
                replaceProjection(commit.templateId(), commit.ownerScope(), commit.projection());
                return new Created();
            });
        } catch (DuplicateKeyException collision) {
            return new IdCollision();
        } catch (DataAccessException unavailable) {
            return new CreateUnavailable();
        }
    }

    @Override
    public AppendOutcome append(AppendCommit commit) {
        try {
            return executeSerializable(() -> {
                var locked = jdbc.sql("""
                                select template_id, owner_scope, schema_key, schema_version_tag,
                                       current_revision, lifecycle
                                from template_aggregate
                                where template_id = :templateId
                                for update
                                """)
                        .param("templateId", commit.templateId().value())
                        .query(PostgresTemplatePersistence::metadata)
                        .optional();
                if (locked.isEmpty()) {
                    return new AppendNotFound();
                }
                var metadata = locked.orElseThrow();
                if (metadata.lifecycle() == Lifecycle.DELETED) {
                    return new AppendDeleted();
                }
                if (!metadata.ownerScope().equals(commit.ownerScope())
                        || !metadata.staticSchema().equals(commit.staticSchema())) {
                    return new AppendUnavailable();
                }
                if (metadata.currentRevision() != commit.expectedRevision()) {
                    return new AppendRevisionConflict(metadata.currentRevision());
                }
                if (!snapshotMatches(commit.dependencySnapshot())) {
                    return new AppendDependencyDrift();
                }

                insertRevision(commit);
                acquireAssetReadReservations(commit.projection());
                replaceProjection(commit.templateId(), commit.ownerScope(), commit.projection());
                var updated = jdbc.sql("""
                                update template_aggregate
                                set current_revision = :nextRevision,
                                    readiness = :readiness,
                                    updated_at = clock_timestamp()
                                where template_id = :templateId
                                  and current_revision = :expectedRevision
                                  and lifecycle = 'ACTIVE'
                                """)
                        .param("nextRevision", commit.nextRevision())
                        .param("readiness", commit.readiness().name())
                        .param("templateId", commit.templateId().value())
                        .param("expectedRevision", commit.expectedRevision())
                        .update();
                if (updated != 1) {
                    throw new PersistenceFault("locked Template current changed unexpectedly");
                }
                return new Appended();
            });
        } catch (DataAccessException | PersistenceFault unavailable) {
            return new AppendUnavailable();
        }
    }

    @Override
    public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
        try {
            var targets = jdbc.sql("""
                            select target_template_id
                            from template_use_reference
                            where template_id = :templateId
                            order by canonical_pointer
                            """)
                    .param("templateId", templateId.value())
                    .query((resultSet, rowNumber) -> resultSet.getString("target_template_id"))
                    .list();
            return new UseTargetsLoaded(targets);
        } catch (DataAccessException unavailable) {
            return new UseTargetsUnavailable();
        }
    }

    @Override
    public FindAssetReferencesOutcome findAssetReferences(String assetId) {
        try {
            var templateIds = jdbc.sql("""
                            select distinct r.template_id
                            from template_asset_reference r
                            join template_aggregate a
                              on a.template_id = r.template_id
                             and a.lifecycle = 'ACTIVE'
                            where r.asset_id = :assetId
                            order by r.template_id
                            """)
                    .param("assetId", assetId)
                    .query((resultSet, rowNumber) ->
                            TemplateApplication.TemplateId.of(resultSet.getString("template_id")))
                    .list();
            return new AssetReferencesLoaded(templateIds);
        } catch (DataAccessException unavailable) {
            return new AssetReferencesUnavailable();
        }
    }

    @Override
    public UpdateReadinessOutcome updateReadiness(
            TemplateApplication.TemplateId templateId,
            long currentRevision,
            TemplateApplication.Readiness readiness,
            TemplateDependencySnapshot dependencySnapshot
    ) {
        try {
            return executeSerializable(() -> {
                var locked = jdbc.sql("""
                                select template_id, owner_scope, schema_key, schema_version_tag,
                                       current_revision, lifecycle
                                from template_aggregate
                                where template_id = :templateId
                                for update
                                """)
                        .param("templateId", templateId.value())
                        .query(PostgresTemplatePersistence::metadata)
                        .optional();
                if (locked.isEmpty()) {
                    return new ReadinessNotFound();
                }
                var metadata = locked.orElseThrow();
                if (metadata.currentRevision() != currentRevision
                        || metadata.lifecycle() != Lifecycle.ACTIVE) {
                    return new ReadinessRevisionConflict();
                }
                if (!snapshotMatches(dependencySnapshot)) {
                    return new ReadinessDependencyDrift();
                }
                var updated = jdbc.sql("""
                                update template_aggregate
                                set readiness = :readiness,
                                    updated_at = clock_timestamp()
                                where template_id = :templateId
                                  and current_revision = :currentRevision
                                  and lifecycle = 'ACTIVE'
                                """)
                        .param("readiness", readiness.name())
                        .param("templateId", templateId.value())
                        .param("currentRevision", currentRevision)
                        .update();
                return updated == 1
                        ? new ReadinessUpdated()
                        : new ReadinessRevisionConflict();
            });
        } catch (DataAccessException unavailable) {
            return new ReadinessUnavailable();
        }
    }

    private <T> T executeSerializable(Supplier<T> work) {
        for (int attempt = 1; attempt <= SERIALIZABLE_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(transactions.execute(status -> work.get()));
            } catch (DataAccessException conflict) {
                if (!isSerializationFailure(conflict)
                        || attempt == SERIALIZABLE_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException("unreachable serializable retry state");
    }

    private static boolean isSerializationFailure(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "40001".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private boolean snapshotMatches(TemplateDependencySnapshot expected) {
        var assetFacts = new ArrayList<TemplateDependencySnapshot.AssetFact>();
        for (var fact : expected.assets()) {
            var state = jdbc.sql("""
                            select owner_scope, kind, lifecycle, asset_revision,
                                   current_content_version
                            from asset_aggregate
                            where asset_id = :assetId
                            for share
                            """)
                    .param("assetId", fact.assetId())
                    .query((resultSet, rowNumber) -> new DependencyResolution.AssetState(
                            new OwnerScopeAuthority.OwnerScope(
                                    resultSet.getString("owner_scope")),
                            resultSet.getString("kind"),
                            DependencyResolution.Lifecycle.valueOf(
                                    resultSet.getString("lifecycle")),
                            resultSet.getLong("asset_revision"),
                            resultSet.getLong("current_content_version")
                    ))
                    .optional();
            assetFacts.add(state
                    .<TemplateDependencySnapshot.AssetFact>map(value ->
                            TemplateDependencySnapshot.AssetFact.resolved(
                                    fact.assetId(), value))
                    .orElseGet(() -> TemplateDependencySnapshot.AssetFact.missing(
                            fact.assetId())));
        }

        var templateFacts = new ArrayList<TemplateDependencySnapshot.TemplateFact>();
        for (var fact : expected.templates()) {
            var row = jdbc.sql("""
                            select a.template_id, a.owner_scope, a.current_revision,
                                   a.lifecycle, a.readiness, a.schema_key,
                                   a.schema_version_tag, r.content_hash
                            from template_aggregate a
                            join template_revision r
                              on r.template_id = a.template_id
                             and r.revision = a.current_revision
                            where a.template_id = :templateId
                            for share of a
                            """)
                    .param("templateId", fact.templateId())
                    .query((resultSet, rowNumber) -> new TemplateDependencyRow(
                            resultSet.getString("template_id"),
                            new OwnerScopeAuthority.OwnerScope(
                                    resultSet.getString("owner_scope")),
                            resultSet.getLong("current_revision"),
                            DependencyResolution.Lifecycle.valueOf(
                                    resultSet.getString("lifecycle")),
                            TemplateApplication.Readiness.valueOf(
                                    resultSet.getString("readiness")),
                            new StaticSchemaRef(
                                    schemaKey(resultSet.getString("schema_key")),
                                    VersionTag.of(resultSet.getString("schema_version_tag"))
                            ),
                            resultSet.getString("content_hash")
                    ))
                    .optional();
            if (row.isEmpty()) {
                templateFacts.add(TemplateDependencySnapshot.TemplateFact.missing(
                        fact.templateId()));
                continue;
            }
            var stored = row.orElseThrow();
            var uses = jdbc.sql("""
                            select target_template_id, canonical_pointer
                            from template_use_reference
                            where template_id = :templateId
                            order by canonical_pointer, target_template_id
                            """)
                    .param("templateId", fact.templateId())
                    .query((resultSet, rowNumber) ->
                            new DependencyResolution.TemplateUseEdge(
                                    resultSet.getString("target_template_id"),
                                    resultSet.getString("canonical_pointer")
                            ))
                    .list();
            templateFacts.add(TemplateDependencySnapshot.TemplateFact.resolved(
                    fact.templateId(),
                    new DependencyResolution.TemplateState(
                            stored.templateId(),
                            stored.ownerScope(),
                            stored.currentRevision(),
                            stored.lifecycle(),
                            stored.readiness(),
                            stored.staticSchema(),
                            stored.contentHash(),
                            uses
                    )
            ));
        }
        return expected.fingerprint().equals(
                new TemplateDependencySnapshot(assetFacts, templateFacts).fingerprint()
        );
    }

    /**
     * T12b read reservations: FOR SHARE on every referenced Asset aggregate row, acquired
     * in ascending assetId order, so a confirmed Asset delete's exclusive reservation
     * serializes against Template current changes (CONTEXT AssetReferenceAuthority
     * reservation contract). Missing Assets have no row to lock; they are INVALID anyway.
     */
    private void acquireAssetReadReservations(TemplateDependencyProjection projection) {
        var assetIds = projection.assetAtoms().stream()
                .map(TemplateDependencyProjection.AssetRefAtom::assetId)
                .distinct()
                .sorted()
                .toList();
        if (assetIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        select asset_id
                        from asset_aggregate
                        where asset_id in (:assetIds)
                        order by asset_id
                        for share
                        """)
                .param("assetIds", assetIds)
                .query((resultSet, rowNumber) -> resultSet.getString("asset_id"))
                .list();
    }

    /** Current-only projection replace: delete and re-insert within the same transaction. */
    private void replaceProjection(
            TemplateApplication.TemplateId templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            TemplateDependencyProjection projection
    ) {        jdbc.sql("delete from template_use_reference where template_id = :templateId")
                .param("templateId", templateId.value())
                .update();
        jdbc.sql("delete from template_asset_reference where template_id = :templateId")
                .param("templateId", templateId.value())
                .update();
        for (var use : projection.templateUses()) {
            jdbc.sql("""
                            insert into template_use_reference (
                                template_id, canonical_pointer, target_template_id
                            ) values (:templateId, :canonicalPointer, :targetTemplateId)
                            """)
                    .param("templateId", templateId.value())
                    .param("canonicalPointer", use.canonicalPointer())
                    .param("targetTemplateId", use.targetTemplateId())
                    .update();
        }
        for (var atom : projection.assetAtoms()) {
            jdbc.sql("""
                            insert into template_asset_reference (
                                template_id, owner_scope, canonical_pointer, asset_id, asset_kind
                            ) values (:templateId, :ownerScope, :canonicalPointer, :assetId, :assetKind)
                            """)
                    .param("templateId", templateId.value())
                    .param("ownerScope", ownerScope.value())
                    .param("canonicalPointer", atom.canonicalPointer())
                    .param("assetId", atom.assetId())
                    .param("assetKind", atom.kind())
                    .update();
        }
    }

    private void insertRevision(CreateCommit commit) {
        insertRevision(
                commit.templateId(),
                commit.revision(),
                commit.canonicalDesignDslUtf8(),
                commit.contentHash()
        );
    }

    private void insertRevision(AppendCommit commit) {
        insertRevision(
                commit.templateId(),
                commit.nextRevision(),
                commit.canonicalDesignDslUtf8(),
                commit.contentHash()
        );
    }

    private void insertRevision(
            TemplateApplication.TemplateId templateId,
            long revision,
            byte[] canonicalUtf8,
            String contentHash
    ) {
        jdbc.sql("""
                        insert into template_revision (
                            template_id, revision, design_dsl,
                            canonical_design_dsl, content_hash
                        ) values (
                            :templateId, :revision, cast(:designDsl as jsonb),
                            :canonicalDesignDsl, :contentHash
                        )
                        """)
                .param("templateId", templateId.value())
                .param("revision", revision)
                .param("designDsl", new String(canonicalUtf8, StandardCharsets.UTF_8))
                .param("canonicalDesignDsl", canonicalUtf8)
                .param("contentHash", contentHash)
                .update();
    }

    private static TemplateMetadata metadata(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new TemplateMetadata(
                TemplateApplication.TemplateId.of(resultSet.getString("template_id")),
                new OwnerScopeAuthority.OwnerScope(resultSet.getString("owner_scope")),
                new StaticSchemaRef(
                        schemaKey(resultSet.getString("schema_key")),
                        VersionTag.of(resultSet.getString("schema_version_tag"))
                ),
                resultSet.getLong("current_revision"),
                Lifecycle.valueOf(resultSet.getString("lifecycle"))
        );
    }

    private static StoredCurrent storedCurrent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        var metadata = metadata(resultSet, rowNumber);
        return new StoredCurrent(
                metadata,
                resultSet.getString("stored_json").getBytes(StandardCharsets.UTF_8),
                resultSet.getBytes("canonical_design_dsl"),
                resultSet.getString("content_hash"),
                TemplateApplication.Readiness.valueOf(resultSet.getString("readiness"))
        );
    }

    private static SchemaKey schemaKey(String raw) {
        return raw.startsWith("system-")
                ? SchemaKey.systemProvided(raw)
                : SchemaKey.userProvided(raw);
    }

    private static final class PersistenceFault extends RuntimeException {
        private PersistenceFault(String message) {
            super(message);
        }
    }

    private record TemplateDependencyRow(
            String templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            long currentRevision,
            DependencyResolution.Lifecycle lifecycle,
            TemplateApplication.Readiness readiness,
            StaticSchemaRef staticSchema,
            String contentHash
    ) {
    }
}
