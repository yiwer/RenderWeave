package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

@Repository
public class PostgresTemplatePersistence implements TemplatePersistence {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PostgresTemplatePersistence(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
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
            return Objects.requireNonNull(transactions.execute(status -> {
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
                return new Created();
            }));
        } catch (DuplicateKeyException collision) {
            return new IdCollision();
        } catch (DataAccessException unavailable) {
            return new CreateUnavailable();
        }
    }

    @Override
    public AppendOutcome append(AppendCommit commit) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
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

                insertRevision(commit);
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
            }));
        } catch (DataAccessException | PersistenceFault unavailable) {
            return new AppendUnavailable();
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
}
