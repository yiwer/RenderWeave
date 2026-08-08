package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.draft.CreationSource;
import cn.hbads.renderweave.schema.draft.DraftAlreadyExistsException;
import cn.hbads.renderweave.schema.draft.DraftDeleteBlockedException;
import cn.hbads.renderweave.schema.draft.DraftListSort;
import cn.hbads.renderweave.schema.draft.DraftNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftReferenceGraph;
import cn.hbads.renderweave.schema.draft.DraftReferenceTarget;
import cn.hbads.renderweave.schema.draft.DraftRevisionConflictException;
import cn.hbads.renderweave.schema.draft.DraftRevisionNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftStore;
import cn.hbads.renderweave.schema.draft.IncomingDraftReference;
import cn.hbads.renderweave.schema.draft.ResolvedStoredDraft;
import cn.hbads.renderweave.schema.draft.StoredDraft;
import cn.hbads.renderweave.schema.draft.StoredDraftRevision;
import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Repository
public class PostgresDraftStore implements DraftStore {

    /** ASCII "RenderWe" as one fixed lock domain for every active Draft graph mutation/read snapshot. */
    private static final long GRAPH_LOCK_KEY = 0x52656e6465725765L;
    private static final int INCOMING_REFERENCE_SUMMARY_LIMIT = 20;

    private final JdbcClient jdbcClient;

    public PostgresDraftStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public ResolvedStoredDraft create(
            SchemaKey schemaKey,
            String definitionJson,
            CreationSource creationSource,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    ) {
        acquireGraphLock();
        ensureKeyAvailable(schemaKey);
        validateGraphReplacement(schemaKey, draftReferences, staticReferences);

        try {
            jdbcClient.sql("""
                            insert into schema_draft (
                                schema_key, current_revision, creation_source
                            ) values (
                                :schemaKey, 0, :creationSource
                            )
                            """)
                    .param("schemaKey", schemaKey.value())
                    .param("creationSource", creationSource.name())
                    .update();

            insertRevision(schemaKey, 0, definitionJson);
            insertReferenceEdges(schemaKey, 0, draftReferences, staticReferences);
        } catch (DuplicateKeyException duplicate) {
            throw new DraftAlreadyExistsException(schemaKey, duplicate);
        }
        return requireResolvedCurrent(schemaKey);
    }

    @Override
    @Transactional
    public ResolvedStoredDraft save(
            SchemaKey schemaKey,
            long expectedRevision,
            String definitionJson,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    ) {
        acquireGraphLock();
        var state = requireActiveState(schemaKey);
        requireExpectedRevision(schemaKey, expectedRevision, state.currentRevision());
        validateGraphReplacement(schemaKey, draftReferences, staticReferences);

        var nextRevision = nextRevision(expectedRevision);
        updateCurrent(schemaKey, expectedRevision, nextRevision, false);
        insertRevision(schemaKey, nextRevision, definitionJson);
        deactivateOutgoingEdges(schemaKey);
        insertReferenceEdges(schemaKey, nextRevision, draftReferences, staticReferences);
        return requireResolvedCurrent(schemaKey);
    }

    @Override
    @Transactional
    public Optional<ResolvedStoredDraft> findCurrent(SchemaKey schemaKey) {
        acquireGraphLock();
        return findStoredCurrent(schemaKey).map(stored -> resolve(stored, loadActiveGraph()));
    }

    @Override
    public List<StoredDraft> findActivePage(int offset, int limit, String search, DraftListSort sort) {
        var sql = """
                        select d.schema_key,
                               d.current_revision,
                               d.creation_source,
                               d.created_at,
                               d.updated_at,
                               r.definition_json::text as definition_json,
                               r.saved_at
                        from schema_draft d
                        join schema_draft_revision r
                          on r.schema_key = d.schema_key
                         and r.revision = d.current_revision
                        where d.deleted_at is null
                          and (
                            :search = ''
                            or position(lower(:search) in lower(d.schema_key)) > 0
                            or position(lower(:search) in lower(coalesce(r.definition_json ->> 'displayName', ''))) > 0
                          )
                        """ + draftOrderBy(sort) + """
                        offset :offset rows fetch first :limit rows only
                        """;
        return jdbcClient.sql(sql)
                .param("search", search)
                .param("offset", offset)
                .param("limit", limit)
                .query(PostgresDraftStore::mapStoredDraft)
                .list();
    }

    @Override
    public long countActive(String search) {
        return jdbcClient.sql("""
                        select count(*)
                        from schema_draft d
                        join schema_draft_revision r
                          on r.schema_key = d.schema_key
                         and r.revision = d.current_revision
                        where d.deleted_at is null
                          and (
                            :search = ''
                            or position(lower(:search) in lower(d.schema_key)) > 0
                            or position(lower(:search) in lower(coalesce(r.definition_json ->> 'displayName', ''))) > 0
                          )
                        """)
                .param("search", search)
                .query(Long.class)
                .single();
    }

    private static String draftOrderBy(DraftListSort sort) {
        return switch (sort) {
            case UPDATED_DESC -> " order by d.updated_at desc, d.schema_key asc ";
            case UPDATED_ASC -> " order by d.updated_at asc, d.schema_key asc ";
            case NAME_ASC -> " order by lower(r.definition_json ->> 'displayName') asc, d.schema_key asc ";
            case NAME_DESC -> " order by lower(r.definition_json ->> 'displayName') desc, d.schema_key asc ";
        };
    }

    @Override
    public Optional<StoredDraftRevision> findRevision(SchemaKey schemaKey, long revision) {
        return jdbcClient.sql("""
                        select schema_key, revision, definition_json::text as definition_json, saved_at
                        from schema_draft_revision
                        where schema_key = :schemaKey and revision = :revision
                        """)
                .param("schemaKey", schemaKey.value())
                .param("revision", revision)
                .query(PostgresDraftStore::mapStoredRevision)
                .optional();
    }

    @Override
    public List<StoredDraftRevision> findHistory(SchemaKey schemaKey, int offset, int limit) {
        return jdbcClient.sql("""
                        select schema_key, revision, definition_json::text as definition_json, saved_at
                        from schema_draft_revision
                        where schema_key = :schemaKey
                        order by revision desc
                        offset :offset rows fetch first :limit rows only
                        """)
                .param("schemaKey", schemaKey.value())
                .param("offset", offset)
                .param("limit", limit)
                .query(PostgresDraftStore::mapStoredRevision)
                .list();
    }

    @Override
    public long countHistory(SchemaKey schemaKey) {
        return jdbcClient.sql("""
                        select count(*) from schema_draft_revision where schema_key = :schemaKey
                        """)
                .param("schemaKey", schemaKey.value())
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional
    public void delete(SchemaKey schemaKey, long expectedRevision) {
        acquireGraphLock();
        var state = requireActiveState(schemaKey);
        requireExpectedRevision(schemaKey, expectedRevision, state.currentRevision());

        var incomingTotal = countIncomingReferences(schemaKey);
        if (incomingTotal > 0) {
            throw new DraftDeleteBlockedException(
                    schemaKey,
                    incomingReferenceSummary(schemaKey),
                    incomingTotal
            );
        }

        var updated = jdbcClient.sql("""
                        update schema_draft
                        set deleted_at = clock_timestamp(),
                            updated_at = clock_timestamp()
                        where schema_key = :schemaKey
                          and current_revision = :expectedRevision
                          and deleted_at is null
                        """)
                .param("schemaKey", schemaKey.value())
                .param("expectedRevision", expectedRevision)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Graph lock did not protect Draft deletion");
        }
        deactivateOutgoingEdges(schemaKey);
    }

    @Override
    @Transactional
    public ResolvedStoredDraft restore(
            SchemaKey schemaKey,
            long expectedRevision,
            long sourceRevision,
            String definitionJson,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    ) {
        acquireGraphLock();
        var state = requireState(schemaKey);
        requireExpectedRevision(schemaKey, expectedRevision, state.currentRevision());
        requireMatchingRevision(schemaKey, sourceRevision, definitionJson);
        validateGraphReplacement(schemaKey, draftReferences, staticReferences);

        var nextRevision = nextRevision(expectedRevision);
        updateCurrent(schemaKey, expectedRevision, nextRevision, true);
        insertRevision(schemaKey, nextRevision, definitionJson);
        deactivateOutgoingEdges(schemaKey);
        insertReferenceEdges(schemaKey, nextRevision, draftReferences, staticReferences);
        return requireResolvedCurrent(schemaKey);
    }

    @Override
    @Transactional
    public ResolvedStoredDraft copyCurrent(
            SchemaKey sourceSchemaKey,
            long expectedSourceRevision,
            SchemaKey targetSchemaKey,
            String definitionJson,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    ) {
        acquireGraphLock();
        var source = requireActiveState(sourceSchemaKey);
        requireExpectedRevision(sourceSchemaKey, expectedSourceRevision, source.currentRevision());
        ensureKeyAvailable(targetSchemaKey);
        validateGraphReplacement(targetSchemaKey, draftReferences, staticReferences);

        try {
            jdbcClient.sql("""
                            insert into schema_draft (
                                schema_key, current_revision, creation_source
                            ) values (
                                :schemaKey, 0, 'USER'
                            )
                            """)
                    .param("schemaKey", targetSchemaKey.value())
                    .update();
            insertRevision(targetSchemaKey, 0, definitionJson);
            insertReferenceEdges(targetSchemaKey, 0, draftReferences, staticReferences);
        } catch (DuplicateKeyException duplicate) {
            throw new DraftAlreadyExistsException(targetSchemaKey, duplicate);
        }
        return requireResolvedCurrent(targetSchemaKey);
    }

    private void acquireGraphLock() {
        jdbcClient.sql("select pg_advisory_xact_lock(:lockKey)")
                .param("lockKey", GRAPH_LOCK_KEY)
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private void ensureKeyAvailable(SchemaKey schemaKey) {
        var exists = jdbcClient.sql("select exists(select 1 from schema_draft where schema_key = :schemaKey)")
                .param("schemaKey", schemaKey.value())
                .query(Boolean.class)
                .single();
        if (exists) {
            throw new DraftAlreadyExistsException(schemaKey, null);
        }
    }

    private DraftState requireActiveState(SchemaKey schemaKey) {
        var state = requireState(schemaKey);
        if (state.deleted()) {
            throw new DraftNotFoundException(schemaKey);
        }
        return state;
    }

    private DraftState requireState(SchemaKey schemaKey) {
        return jdbcClient.sql("""
                        select current_revision, deleted_at is not null as deleted
                        from schema_draft
                        where schema_key = :schemaKey
                        """)
                .param("schemaKey", schemaKey.value())
                .query((resultSet, rowNumber) -> new DraftState(
                        resultSet.getLong("current_revision"),
                        resultSet.getBoolean("deleted")
                ))
                .optional()
                .orElseThrow(() -> new DraftNotFoundException(schemaKey));
    }

    private static void requireExpectedRevision(
            SchemaKey schemaKey,
            long expectedRevision,
            long currentRevision
    ) {
        if (expectedRevision != currentRevision) {
            throw new DraftRevisionConflictException(schemaKey, expectedRevision, currentRevision);
        }
    }

    private void requireMatchingRevision(
            SchemaKey schemaKey,
            long revision,
            String definitionJson
    ) {
        var exists = jdbcClient.sql("""
                        select exists(
                            select 1
                            from schema_draft_revision
                            where schema_key = :schemaKey and revision = :revision
                        )
                        """)
                .param("schemaKey", schemaKey.value())
                .param("revision", revision)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new DraftRevisionNotFoundException(schemaKey, revision);
        }

        var matches = jdbcClient.sql("""
                        select definition_json = cast(:definitionJson as jsonb)
                        from schema_draft_revision
                        where schema_key = :schemaKey and revision = :revision
                        """)
                .param("schemaKey", schemaKey.value())
                .param("revision", revision)
                .param("definitionJson", definitionJson)
                .query(Boolean.class)
                .single();
        if (!matches) {
            throw new IllegalStateException("Restore definition differs from its immutable source revision");
        }
    }

    private static long nextRevision(long currentRevision) {
        try {
            return Math.addExact(currentRevision, 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("revision is too large", overflow);
        }
    }

    private void updateCurrent(
            SchemaKey schemaKey,
            long expectedRevision,
            long nextRevision,
            boolean restoreDeleted
    ) {
        var sql = restoreDeleted
                ? """
                  update schema_draft
                  set current_revision = :nextRevision,
                      deleted_at = null,
                      updated_at = clock_timestamp()
                  where schema_key = :schemaKey and current_revision = :expectedRevision
                  """
                : """
                  update schema_draft
                  set current_revision = :nextRevision,
                      updated_at = clock_timestamp()
                  where schema_key = :schemaKey
                    and current_revision = :expectedRevision
                    and deleted_at is null
                  """;
        var updated = jdbcClient.sql(sql)
                .param("nextRevision", nextRevision)
                .param("schemaKey", schemaKey.value())
                .param("expectedRevision", expectedRevision)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Graph lock did not protect Draft revision update");
        }
    }

    private void insertRevision(SchemaKey schemaKey, long revision, String definitionJson) {
        jdbcClient.sql("""
                        insert into schema_draft_revision (
                            schema_key, revision, definition_json
                        ) values (
                            :schemaKey, :revision, cast(:definitionJson as jsonb)
                        )
                        """)
                .param("schemaKey", schemaKey.value())
                .param("revision", revision)
                .param("definitionJson", definitionJson)
                .update();
    }

    private void deactivateOutgoingEdges(SchemaKey schemaKey) {
        jdbcClient.sql("""
                        update schema_reference_edge
                        set active = false
                        where source_schema_key = :schemaKey and active
                        """)
                .param("schemaKey", schemaKey.value())
                .update();
    }

    private void insertReferenceEdges(
            SchemaKey sourceSchemaKey,
            long sourceRevision,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    ) {
        for (var reference : draftReferences) {
            jdbcClient.sql("""
                            insert into schema_reference_edge (
                                source_schema_key,
                                source_revision,
                                source_pointer,
                                target_kind,
                                target_schema_key,
                                target_version_tag,
                                active
                            ) values (
                                :sourceSchemaKey,
                                :sourceRevision,
                                :sourcePointer,
                                'DRAFT',
                                :targetSchemaKey,
                                null,
                                true
                            )
                            """)
                    .param("sourceSchemaKey", sourceSchemaKey.value())
                    .param("sourceRevision", sourceRevision)
                    .param("sourcePointer", reference.pointer())
                    .param("targetSchemaKey", reference.schemaKey().value())
                    .update();
        }
        for (var reference : staticReferences) {
            jdbcClient.sql("""
                            insert into schema_reference_edge (
                                source_schema_key,
                                source_revision,
                                source_pointer,
                                target_kind,
                                target_schema_key,
                                target_version_tag,
                                active
                            ) values (
                                :sourceSchemaKey,
                                :sourceRevision,
                                :sourcePointer,
                                'STATIC',
                                :targetSchemaKey,
                                :targetVersionTag,
                                true
                            )
                            """)
                    .param("sourceSchemaKey", sourceSchemaKey.value())
                    .param("sourceRevision", sourceRevision)
                    .param("sourcePointer", reference.pointer())
                    .param("targetSchemaKey", reference.reference().schemaKey().value())
                    .param("targetVersionTag", reference.reference().versionTag().value())
                    .update();
        }
    }

    private void validateGraphReplacement(
            SchemaKey sourceSchemaKey,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    ) {
        var graph = loadActiveGraph();
        DraftReferenceGraph.validateReplacement(
                sourceSchemaKey,
                draftReferences,
                staticReferences,
                graph.revisions().keySet(),
                graph.draftEdges(),
                graph.staticEdges(),
                graph.staticDepths()
        );
    }

    private long countIncomingReferences(SchemaKey schemaKey) {
        return jdbcClient.sql("""
                        select count(*)
                        from schema_reference_edge
                        where target_kind = 'DRAFT'
                          and target_schema_key = :schemaKey
                          and active
                        """)
                .param("schemaKey", schemaKey.value())
                .query(Long.class)
                .single();
    }

    private List<IncomingDraftReference> incomingReferenceSummary(SchemaKey schemaKey) {
        return jdbcClient.sql("""
                        select source_schema_key, source_revision, source_pointer
                        from schema_reference_edge
                        where target_kind = 'DRAFT'
                          and target_schema_key = :schemaKey
                          and active
                        order by source_schema_key, source_pointer
                        fetch first :limit rows only
                        """)
                .param("schemaKey", schemaKey.value())
                .param("limit", INCOMING_REFERENCE_SUMMARY_LIMIT)
                .query((resultSet, rowNumber) -> new IncomingDraftReference(
                        SchemaKey.userProvided(resultSet.getString("source_schema_key")),
                        resultSet.getLong("source_revision"),
                        resultSet.getString("source_pointer")
                ))
                .list();
    }

    private ResolvedStoredDraft requireResolvedCurrent(SchemaKey schemaKey) {
        var stored = findStoredCurrent(schemaKey)
                .orElseThrow(() -> new DraftNotFoundException(schemaKey));
        return resolve(stored, loadActiveGraph());
    }

    private Optional<StoredDraft> findStoredCurrent(SchemaKey schemaKey) {
        return jdbcClient.sql("""
                        select d.schema_key,
                               d.current_revision,
                               d.creation_source,
                               d.created_at,
                               d.updated_at,
                               r.definition_json::text as definition_json,
                               r.saved_at
                        from schema_draft d
                        join schema_draft_revision r
                          on r.schema_key = d.schema_key
                         and r.revision = d.current_revision
                        where d.schema_key = :schemaKey
                          and d.deleted_at is null
                        """)
                .param("schemaKey", schemaKey.value())
                .query(PostgresDraftStore::mapStoredDraft)
                .optional();
    }

    private ActiveGraph loadActiveGraph() {
        var revisions = new LinkedHashMap<SchemaKey, Long>();
        jdbcClient.sql("""
                        select schema_key, current_revision
                        from schema_draft
                        where deleted_at is null
                        order by schema_key
                        """)
                .query((resultSet, rowNumber) -> new GraphRevision(
                        SchemaKey.userProvided(resultSet.getString("schema_key")),
                        resultSet.getLong("current_revision")
                ))
                .list()
                .forEach(entry -> revisions.put(entry.schemaKey(), entry.revision()));

        var draftEdges = new LinkedHashMap<SchemaKey, Set<SchemaKey>>();
        jdbcClient.sql("""
                        select source_schema_key, target_schema_key
                        from schema_reference_edge
                        where active and target_kind = 'DRAFT'
                        order by source_schema_key, target_schema_key, source_pointer
                        """)
                .query((resultSet, rowNumber) -> new GraphEdge(
                        SchemaKey.userProvided(resultSet.getString("source_schema_key")),
                        SchemaKey.userProvided(resultSet.getString("target_schema_key"))
                ))
                .list()
                .forEach(edge -> draftEdges
                        .computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>())
                        .add(edge.target()));

        var staticEdges = new LinkedHashMap<SchemaKey, Set<StaticSchemaRef>>();
        jdbcClient.sql("""
                        select source_schema_key, target_schema_key, target_version_tag
                        from schema_reference_edge
                        where active and target_kind = 'STATIC'
                        order by source_schema_key, target_schema_key, target_version_tag, source_pointer
                        """)
                .query((resultSet, rowNumber) -> new GraphStaticEdge(
                        SchemaKey.userProvided(resultSet.getString("source_schema_key")),
                        staticReference(
                                resultSet.getString("target_schema_key"),
                                resultSet.getString("target_version_tag")
                        )
                ))
                .list()
                .forEach(edge -> staticEdges
                        .computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>())
                        .add(edge.target()));

        var staticDepths = new LinkedHashMap<StaticSchemaRef, Integer>();
        jdbcClient.sql("""
                        select schema_key, version_tag, reference_depth
                        from static_schema
                        order by schema_key, version_tag
                        """)
                .query((resultSet, rowNumber) -> new GraphStaticDepth(
                        staticReference(
                                resultSet.getString("schema_key"),
                                resultSet.getString("version_tag")
                        ),
                        resultSet.getInt("reference_depth")
                ))
                .list()
                .forEach(entry -> staticDepths.put(entry.reference(), entry.depth()));
        return new ActiveGraph(revisions, draftEdges, staticEdges, staticDepths);
    }

    private static ResolvedStoredDraft resolve(StoredDraft root, ActiveGraph graph) {
        var resolved = new LinkedHashMap<SchemaKey, Long>();
        var queued = new HashSet<SchemaKey>();
        var queue = new ArrayDeque<SchemaKey>();
        queue.add(root.schemaKey());
        queued.add(root.schemaKey());

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            var revision = graph.revisions().get(current);
            if (revision == null) {
                throw new IllegalStateException("Active reference target disappeared: " + current.value());
            }
            resolved.put(current, revision);

            var targets = new TreeSet<>(Comparator.comparing(SchemaKey::value));
            targets.addAll(graph.draftEdges().getOrDefault(current, Set.of()));
            for (var target : targets) {
                if (queued.add(target)) {
                    queue.addLast(target);
                }
            }
        }
        return new ResolvedStoredDraft(root, resolved);
    }

    private static StoredDraft mapStoredDraft(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredDraft(
                SchemaKey.userProvided(resultSet.getString("schema_key")),
                resultSet.getLong("current_revision"),
                resultSet.getString("definition_json"),
                CreationSource.valueOf(resultSet.getString("creation_source")),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("saved_at", OffsetDateTime.class).toInstant()
        );
    }

    private static StoredDraftRevision mapStoredRevision(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoredDraftRevision(
                SchemaKey.userProvided(resultSet.getString("schema_key")),
                resultSet.getLong("revision"),
                resultSet.getString("definition_json"),
                resultSet.getObject("saved_at", OffsetDateTime.class).toInstant()
        );
    }

    private static StaticSchemaRef staticReference(String schemaKey, String versionTag) {
        var key = schemaKey.startsWith("system-")
                ? SchemaKey.systemProvided(schemaKey)
                : SchemaKey.userProvided(schemaKey);
        return new StaticSchemaRef(key, VersionTag.of(versionTag));
    }

    private record DraftState(long currentRevision, boolean deleted) {
    }

    private record GraphRevision(SchemaKey schemaKey, long revision) {
    }

    private record GraphEdge(SchemaKey source, SchemaKey target) {
    }

    private record GraphStaticEdge(SchemaKey source, StaticSchemaRef target) {
    }

    private record GraphStaticDepth(StaticSchemaRef reference, int depth) {
    }

    private record ActiveGraph(
            Map<SchemaKey, Long> revisions,
            Map<SchemaKey, Set<SchemaKey>> draftEdges,
            Map<SchemaKey, Set<StaticSchemaRef>> staticEdges,
            Map<StaticSchemaRef, Integer> staticDepths
    ) {
    }
}
