package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaProblem;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.draft.DraftNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftRevisionConflictException;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.schema.staticvalue.PublishStaticSchema;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaAlreadyExistsException;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaListSort;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaOrigin;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaOriginFilter;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import cn.hbads.renderweave.schema.staticvalue.StoredStaticSchema;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class PostgresStaticSchemaStore implements StaticSchemaStore {

    private static final long GRAPH_LOCK_KEY = 0x52656e6465725765L;

    private final JdbcClient jdbcClient;

    public PostgresStaticSchemaStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public StoredStaticSchema publish(PublishStaticSchema command) {
        acquireGraphLock();
        var state = requireActiveDraft(command.schemaKey());
        if (state.currentRevision() != command.expectedRevision()) {
            throw new DraftRevisionConflictException(
                    command.schemaKey(),
                    command.expectedRevision(),
                    state.currentRevision()
            );
        }
        requireCurrentDefinition(command);
        var calculatedDepth = calculateReferenceDepth(command);
        if (calculatedDepth != command.referenceDepth()) {
            throw new IllegalStateException("StaticSchema reference depth changed during publication");
        }

        var identity = new StaticSchemaRef(command.schemaKey(), command.versionTag());
        try {
            jdbcClient.sql("""
                            insert into static_schema (
                                schema_key,
                                version_tag,
                                origin,
                                source_draft_revision,
                                definition_json,
                                compiled_json_schema,
                                compiler_version,
                                release_note,
                                reference_depth
                            ) values (
                                :schemaKey,
                                :versionTag,
                                'DRAFT',
                                :sourceRevision,
                                cast(:definitionJson as jsonb),
                                cast(:compiledJsonSchema as json),
                                :compilerVersion,
                                :releaseNote,
                                :referenceDepth
                            )
                            """)
                    .param("schemaKey", command.schemaKey().value())
                    .param("versionTag", command.versionTag().value())
                    .param("sourceRevision", command.expectedRevision())
                    .param("definitionJson", command.definitionJson())
                    .param("compiledJsonSchema", command.compiledJsonSchema())
                    .param("compilerVersion", command.compilerVersion())
                    .param("releaseNote", command.releaseNote().orElse(null))
                    .param("referenceDepth", command.referenceDepth())
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new StaticSchemaAlreadyExistsException(identity, duplicate);
        }
        for (var reference : command.references()) {
            jdbcClient.sql("""
                            insert into static_schema_reference_edge (
                                source_schema_key,
                                source_version_tag,
                                source_pointer,
                                target_schema_key,
                                target_version_tag
                            ) values (
                                :sourceSchemaKey,
                                :sourceVersionTag,
                                :sourcePointer,
                                :targetSchemaKey,
                                :targetVersionTag
                            )
                            """)
                    .param("sourceSchemaKey", command.schemaKey().value())
                    .param("sourceVersionTag", command.versionTag().value())
                    .param("sourcePointer", reference.pointer())
                    .param("targetSchemaKey", reference.reference().schemaKey().value())
                    .param("targetVersionTag", reference.reference().versionTag().value())
                    .update();
        }
        return findRequired(identity);
    }

    @Override
    public Optional<StoredStaticSchema> find(StaticSchemaRef reference) {
        return jdbcClient.sql("""
                        select schema_key,
                               version_tag,
                               origin,
                               source_draft_revision,
                               definition_json::text as definition_json,
                               compiled_json_schema::text as compiled_json_schema,
                               compiler_version,
                               release_note,
                               reference_depth,
                               published_at
                        from static_schema
                        where schema_key = :schemaKey and version_tag = :versionTag
                        """)
                .param("schemaKey", reference.schemaKey().value())
                .param("versionTag", reference.versionTag().value())
                .query(PostgresStaticSchemaStore::mapStoredStaticSchema)
                .optional();
    }

    @Override
    public List<StoredStaticSchema> findPage(int offset, int limit) {
        return jdbcClient.sql("""
                        select schema_key,
                               version_tag,
                               origin,
                               source_draft_revision,
                               definition_json::text as definition_json,
                               compiled_json_schema::text as compiled_json_schema,
                               compiler_version,
                               release_note,
                               reference_depth,
                               published_at
                        from static_schema
                        order by case when origin = 'SYSTEM' then 0 else 1 end,
                                 schema_key,
                                 version_tag,
                                 published_at desc
                        offset :offset rows fetch first :limit rows only
                        """)
                .param("offset", offset)
                .param("limit", limit)
                .query(PostgresStaticSchemaStore::mapStoredStaticSchema)
                .list();
    }

    @Override
    public long count() {
        return jdbcClient.sql("select count(*) from static_schema")
                .query(Long.class)
                .single();
    }

    @Override
    public List<StoredStaticSchema> findPage(
            int offset,
            int limit,
            String search,
            StaticSchemaListSort sort,
            StaticSchemaOriginFilter origin
    ) {
        var sql = """
                        select schema_key,
                               version_tag,
                               origin,
                               source_draft_revision,
                               definition_json::text as definition_json,
                               compiled_json_schema::text as compiled_json_schema,
                               compiler_version,
                               release_note,
                               reference_depth,
                               published_at
                        from static_schema
                        where (:origin = 'ALL' or origin = :origin)
                          and (
                            :search = ''
                            or position(lower(:search) in lower(schema_key)) > 0
                            or position(lower(:search) in lower(version_tag)) > 0
                            or position(lower(:search) in lower(coalesce(definition_json ->> 'displayName', ''))) > 0
                          )
                        """ + staticOrderBy(sort) + """
                        offset :offset rows fetch first :limit rows only
                        """;
        return jdbcClient.sql(sql)
                .param("origin", origin.name())
                .param("search", search)
                .param("offset", offset)
                .param("limit", limit)
                .query(PostgresStaticSchemaStore::mapStoredStaticSchema)
                .list();
    }

    @Override
    public long count(String search, StaticSchemaOriginFilter origin) {
        return jdbcClient.sql("""
                        select count(*)
                        from static_schema
                        where (:origin = 'ALL' or origin = :origin)
                          and (
                            :search = ''
                            or position(lower(:search) in lower(schema_key)) > 0
                            or position(lower(:search) in lower(version_tag)) > 0
                            or position(lower(:search) in lower(coalesce(definition_json ->> 'displayName', ''))) > 0
                          )
                        """)
                .param("origin", origin.name())
                .param("search", search)
                .query(Long.class)
                .single();
    }

    private static String staticOrderBy(StaticSchemaListSort sort) {
        return switch (sort) {
            case PUBLISHED_DESC -> " order by published_at desc, schema_key asc, version_tag asc ";
            case PUBLISHED_ASC -> " order by published_at asc, schema_key asc, version_tag asc ";
            case NAME_ASC -> " order by lower(definition_json ->> 'displayName') asc, schema_key asc, version_tag asc ";
            case NAME_DESC -> " order by lower(definition_json ->> 'displayName') desc, schema_key asc, version_tag asc ";
        };
    }

    private void acquireGraphLock() {
        jdbcClient.sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", GRAPH_LOCK_KEY)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private DraftState requireActiveDraft(SchemaKey schemaKey) {
        return jdbcClient.sql("""
                        select current_revision
                        from schema_draft
                        where schema_key = :schemaKey and deleted_at is null
                        """)
                .param("schemaKey", schemaKey.value())
                .query((resultSet, rowNumber) -> new DraftState(resultSet.getLong("current_revision")))
                .optional()
                .orElseThrow(() -> new DraftNotFoundException(schemaKey));
    }

    private void requireCurrentDefinition(PublishStaticSchema command) {
        var matches = jdbcClient.sql("""
                        select definition_json = cast(:definitionJson as jsonb)
                        from schema_draft_revision
                        where schema_key = :schemaKey and revision = :revision
                        """)
                .param("schemaKey", command.schemaKey().value())
                .param("revision", command.expectedRevision())
                .param("definitionJson", command.definitionJson())
                .query(Boolean.class)
                .single();
        if (!matches) {
            throw new IllegalStateException("Publication definition differs from current immutable revision");
        }
    }

    private int calculateReferenceDepth(PublishStaticSchema command) {
        var maximumChildDepth = 0;
        for (var reference : command.references()) {
            var depth = jdbcClient.sql("""
                            select reference_depth
                            from static_schema
                            where schema_key = :schemaKey and version_tag = :versionTag
                            """)
                    .param("schemaKey", reference.reference().schemaKey().value())
                    .param("versionTag", reference.reference().versionTag().value())
                    .query(Integer.class)
                    .optional();
            if (depth.isEmpty()) {
                throw new InvalidSchemaDefinitionException(List.of(new SchemaProblem(
                        "STATIC_SCHEMA_REFERENCE_NOT_FOUND",
                        reference.pointer(),
                        "Referenced StaticSchema does not exist: "
                                + reference.reference().schemaKey().value()
                                + "@" + reference.reference().versionTag().value()
                )));
            }
            maximumChildDepth = Math.max(maximumChildDepth, depth.orElseThrow());
        }
        return 1 + maximumChildDepth;
    }

    private StoredStaticSchema findRequired(StaticSchemaRef reference) {
        return find(reference).orElseThrow(() -> new IllegalStateException(
                "StaticSchema disappeared inside publication transaction"
        ));
    }

    private static StoredStaticSchema mapStoredStaticSchema(ResultSet resultSet, int rowNumber)
            throws SQLException {
        var rawSchemaKey = resultSet.getString("schema_key");
        var schemaKey = rawSchemaKey.startsWith("system-")
                ? SchemaKey.systemProvided(rawSchemaKey)
                : SchemaKey.userProvided(rawSchemaKey);
        var sourceRevision = resultSet.getObject("source_draft_revision", Long.class);
        var releaseNote = resultSet.getString("release_note");
        return new StoredStaticSchema(
                new StaticSchemaRef(schemaKey, VersionTag.of(resultSet.getString("version_tag"))),
                StaticSchemaOrigin.valueOf(resultSet.getString("origin")),
                Optional.ofNullable(sourceRevision),
                resultSet.getString("definition_json"),
                resultSet.getString("compiled_json_schema"),
                resultSet.getString("compiler_version"),
                Optional.ofNullable(releaseNote),
                resultSet.getInt("reference_depth"),
                resultSet.getObject("published_at", OffsetDateTime.class).toInstant()
        );
    }

    private record DraftState(long currentRevision) {
    }
}
