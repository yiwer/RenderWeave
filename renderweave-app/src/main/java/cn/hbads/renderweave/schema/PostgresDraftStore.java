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
import cn.hbads.renderweave.schema.draft.StoredDraftRevisionSummary;
import cn.hbads.renderweave.schema.draft.StoredDraftSummary;
import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaProblem;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class PostgresDraftStore implements DraftStore {

    /** ASCII "RenderWe" as one fixed lock domain for active Draft graph writes/shared snapshots. */
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
        acquireGraphReadLock();
        return findStoredCurrent(schemaKey).map(this::resolveCurrent);
    }

    @Override
    public List<StoredDraftSummary> findActivePage(
            int offset,
            int limit,
            String search,
            DraftListSort sort
    ) {
        var sql = """
                        select d.schema_key,
                               d.current_revision,
                               d.creation_source,
                               d.created_at,
                               d.updated_at,
                               r.definition_json ->> 'displayName' as display_name,
                               jsonb_array_length(r.definition_json -> 'fields') as field_count,
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
                .query(PostgresDraftStore::mapStoredDraftSummary)
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
    public List<StoredDraftRevisionSummary> findHistory(
            SchemaKey schemaKey,
            int offset,
            int limit
    ) {
        return jdbcClient.sql("""
                        select revision,
                               definition_json ->> 'displayName' as display_name,
                               jsonb_array_length(definition_json -> 'fields') as field_count,
                               saved_at
                        from schema_draft_revision
                        where schema_key = :schemaKey
                        order by revision desc
                        offset :offset rows fetch first :limit rows only
                        """)
                .param("schemaKey", schemaKey.value())
                .param("offset", offset)
                .param("limit", limit)
                .query(PostgresDraftStore::mapStoredRevisionSummary)
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

    private void acquireGraphReadLock() {
        jdbcClient.sql("select pg_advisory_xact_lock_shared(:lockKey)")
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
        var draftTargets = draftReferences.stream()
                .map(DraftReferenceTarget::schemaKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var staticTargets = staticReferences.stream()
                .map(StaticReferenceTarget::reference)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        var missingDrafts = missingActiveDrafts(draftTargets);
        // A create may propose a self-reference before its row exists; preserve the domain's
        // more precise cycle diagnostic instead of misclassifying it as a missing target.
        missingDrafts.remove(sourceSchemaKey);
        var staticDepths = loadStaticDepths(staticTargets);
        var missingStatics = new LinkedHashSet<>(staticTargets);
        missingStatics.removeAll(staticDepths.keySet());
        var missing = new java.util.ArrayList<SchemaProblem>();
        draftReferences.stream()
                .filter(reference -> missingDrafts.contains(reference.schemaKey()))
                .map(reference -> new SchemaProblem(
                        "SCHEMA_REFERENCE_NOT_FOUND",
                        reference.pointer(),
                        "Referenced active Draft does not exist: "
                                + reference.schemaKey().value()
                ))
                .forEach(missing::add);
        staticReferences.stream()
                .filter(reference -> missingStatics.contains(reference.reference()))
                .map(reference -> new SchemaProblem(
                        "STATIC_SCHEMA_REFERENCE_NOT_FOUND",
                        reference.pointer(),
                        "Referenced StaticSchema does not exist: "
                                + reference.reference().schemaKey().value()
                                + "@" + reference.reference().versionTag().value()
                ))
                .forEach(missing::add);
        if (!missing.isEmpty()) throw new InvalidSchemaDefinitionException(missing);

        var ancestorDistances = ancestorDistances(sourceSchemaKey);
        var cycles = draftReferences.stream()
                .filter(reference -> ancestorDistances.containsKey(reference.schemaKey()))
                .map(reference -> new SchemaProblem(
                        "SCHEMA_REFERENCE_CYCLE",
                        reference.pointer(),
                        "Reference would create a cycle through "
                                + reference.schemaKey().value()
                ))
                .toList();
        if (!cycles.isEmpty()) throw new InvalidSchemaDefinitionException(cycles);

        var maximumChildDepth = maximumDraftTargetDepth(draftTargets);
        var maximumStaticDepth = staticDepths.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        var sourceDepth = Math.max(1, 1 + Math.max(maximumChildDepth, maximumStaticDepth));
        var maximumAncestorDistance = ancestorDistances.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        var prospectiveDepth = maximumAncestorDistance + sourceDepth;
        if (prospectiveDepth > DraftReferenceGraph.MAX_DEPTH) {
            var pointer = !draftReferences.isEmpty()
                    ? draftReferences.getFirst().pointer()
                    : staticReferences.isEmpty() ? "" : staticReferences.getFirst().pointer();
            throw new InvalidSchemaDefinitionException(List.of(new SchemaProblem(
                    "SCHEMA_REFERENCE_DEPTH_EXCEEDED",
                    pointer,
                    "Reference graph depth " + prospectiveDepth + " exceeds maximum "
                            + DraftReferenceGraph.MAX_DEPTH
            )));
        }
    }

    private Set<SchemaKey> missingActiveDrafts(Set<SchemaKey> targets) {
        if (targets.isEmpty()) return new LinkedHashSet<>();
        var encoded = targets.stream().map(SchemaKey::value).collect(java.util.stream.Collectors.joining(","));
        return new LinkedHashSet<>(jdbcClient.sql("""
                        with proposed(schema_key) as (
                            select unnest(string_to_array(:targets, ','))
                        )
                        select proposed.schema_key
                        from proposed
                        left join schema_draft draft
                          on draft.schema_key = proposed.schema_key
                         and draft.deleted_at is null
                        where draft.schema_key is null
                        order by proposed.schema_key
                        """)
                .param("targets", encoded)
                .query((resultSet, rowNumber) ->
                        SchemaKey.userProvided(resultSet.getString("schema_key")))
                .list());
    }

    private Map<StaticSchemaRef, Integer> loadStaticDepths(Set<StaticSchemaRef> targets) {
        if (targets.isEmpty()) return Map.of();
        var encoded = targets.stream()
                .map(reference -> reference.schemaKey().value() + "@" + reference.versionTag().value())
                .collect(java.util.stream.Collectors.joining(","));
        var result = new LinkedHashMap<StaticSchemaRef, Integer>();
        jdbcClient.sql("""
                        with proposed(token) as (
                            select unnest(string_to_array(:targets, ','))
                        )
                        select static.schema_key, static.version_tag, static.reference_depth
                        from proposed
                        join static_schema static
                          on static.schema_key = split_part(proposed.token, '@', 1)
                         and static.version_tag = split_part(proposed.token, '@', 2)
                        order by static.schema_key, static.version_tag
                        """)
                .param("targets", encoded)
                .query((resultSet, rowNumber) -> new GraphStaticDepth(
                        staticReference(
                                resultSet.getString("schema_key"),
                                resultSet.getString("version_tag")
                        ),
                        resultSet.getInt("reference_depth")
                ))
                .list()
                .forEach(entry -> result.put(entry.reference(), entry.depth()));
        return result;
    }

    /**
     * Only ancestors of the replaced source can have their depth changed. The reverse active-edge
     * index bounds this snapshot to that impact set instead of materializing every active edge.
     */
    private Map<SchemaKey, Integer> ancestorDistances(SchemaKey sourceSchemaKey) {
        var result = new LinkedHashMap<SchemaKey, Integer>();
        jdbcClient.sql("""
                        with recursive ancestors(schema_key, distance) as (
                            select cast(:schemaKey as varchar(63)), 0
                            union
                            select edge.source_schema_key, ancestors.distance + 1
                            from ancestors
                            join schema_reference_edge edge
                              on edge.target_schema_key = ancestors.schema_key
                             and edge.active
                             and edge.target_kind = 'DRAFT'
                            join schema_draft source
                              on source.schema_key = edge.source_schema_key
                             and source.deleted_at is null
                            where ancestors.distance < :maximumDepth
                        )
                        select schema_key, max(distance) as distance
                        from ancestors
                        group by schema_key
                        order by schema_key
                        """)
                .param("schemaKey", sourceSchemaKey.value())
                .param("maximumDepth", DraftReferenceGraph.MAX_DEPTH)
                .query((resultSet, rowNumber) -> new GraphDistance(
                        SchemaKey.userProvided(resultSet.getString("schema_key")),
                        resultSet.getInt("distance")
                ))
                .list()
                .forEach(entry -> result.put(entry.schemaKey(), entry.distance()));
        return result;
    }

    /** Maximum existing depth below any proposed Draft target, including nested Static depth. */
    private int maximumDraftTargetDepth(Set<SchemaKey> targets) {
        if (targets.isEmpty()) return 0;
        var encoded = targets.stream().map(SchemaKey::value).collect(java.util.stream.Collectors.joining(","));
        return jdbcClient.sql("""
                        with recursive descendants(schema_key, draft_depth) as (
                            select unnest(string_to_array(:targets, ',')), 1
                            union
                            select edge.target_schema_key, descendants.draft_depth + 1
                            from descendants
                            join schema_reference_edge edge
                              on edge.source_schema_key = descendants.schema_key
                             and edge.active
                             and edge.target_kind = 'DRAFT'
                            join schema_draft target
                              on target.schema_key = edge.target_schema_key
                             and target.deleted_at is null
                            where descendants.draft_depth <= :maximumDepth
                        )
                        select greatest(
                            coalesce((select max(draft_depth) from descendants), 0),
                            coalesce((
                                select max(descendants.draft_depth + static.reference_depth)
                                from descendants
                                join schema_reference_edge edge
                                  on edge.source_schema_key = descendants.schema_key
                                 and edge.active
                                 and edge.target_kind = 'STATIC'
                                join static_schema static
                                  on static.schema_key = edge.target_schema_key
                                 and static.version_tag = edge.target_version_tag
                            ), 0)
                        )
                        """)
                .param("targets", encoded)
                .param("maximumDepth", DraftReferenceGraph.MAX_DEPTH)
                .query(Integer.class)
                .single();
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
        return resolveCurrent(stored);
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

    /**
     * Freeze only the root's reachable live-Draft closure while the caller holds the graph lock.
     * Reads take the shared form of that lock, so ten detail/validation requests do not serialize;
     * graph mutations retain the exclusive form and cannot interleave this multi-row snapshot.
     */
    private ResolvedStoredDraft resolveCurrent(StoredDraft root) {
        var revisions = new LinkedHashMap<SchemaKey, Long>();
        jdbcClient.sql("""
                        with recursive reachable(schema_key, current_revision, distance) as (
                            select schema_key, current_revision, 0
                            from schema_draft
                            where schema_key = :schemaKey and deleted_at is null
                            union
                            select target.schema_key,
                                   target.current_revision,
                                   current_node.distance + 1
                            from reachable current_node
                            join schema_reference_edge edge
                              on edge.source_schema_key = current_node.schema_key
                             and edge.active
                             and edge.target_kind = 'DRAFT'
                            join schema_draft target
                              on target.schema_key = edge.target_schema_key
                             and target.deleted_at is null
                            where current_node.distance < :maximumDepth
                        )
                        select schema_key, current_revision, min(distance) as distance
                        from reachable
                        group by schema_key, current_revision
                        order by distance, schema_key
                        """)
                .param("schemaKey", root.schemaKey().value())
                .param("maximumDepth", DraftReferenceGraph.MAX_DEPTH)
                .query((resultSet, rowNumber) -> new GraphRevision(
                        SchemaKey.userProvided(resultSet.getString("schema_key")),
                        resultSet.getLong("current_revision")
                ))
                .list()
                .forEach(entry -> revisions.put(entry.schemaKey(), entry.revision()));
        if (!revisions.containsKey(root.schemaKey())) {
            throw new IllegalStateException("Active Draft disappeared inside graph snapshot");
        }
        return new ResolvedStoredDraft(root, revisions);
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

    private static StoredDraftSummary mapStoredDraftSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StoredDraftSummary(
                SchemaKey.userProvided(resultSet.getString("schema_key")),
                resultSet.getLong("current_revision"),
                CreationSource.valueOf(resultSet.getString("creation_source")),
                resultSet.getString("display_name"),
                resultSet.getInt("field_count"),
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

    private static StoredDraftRevisionSummary mapStoredRevisionSummary(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new StoredDraftRevisionSummary(
                resultSet.getLong("revision"),
                resultSet.getString("display_name"),
                resultSet.getInt("field_count"),
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

    private record GraphStaticDepth(StaticSchemaRef reference, int depth) {
    }

    private record GraphDistance(SchemaKey schemaKey, int distance) {
    }
}
