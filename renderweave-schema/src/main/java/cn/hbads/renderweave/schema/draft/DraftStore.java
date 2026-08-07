package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Optional;
import java.util.List;

/** Transactional persistence boundary owned by the schema module and implemented by the app adapter. */
public interface DraftStore {

    ResolvedStoredDraft create(
            SchemaKey schemaKey,
            String definitionJson,
            CreationSource creationSource,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    );

    ResolvedStoredDraft save(
            SchemaKey schemaKey,
            long expectedRevision,
            String definitionJson,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    );

    Optional<ResolvedStoredDraft> findCurrent(SchemaKey schemaKey);

    List<StoredDraft> findActivePage(int offset, int limit);

    long countActive();

    Optional<StoredDraftRevision> findRevision(SchemaKey schemaKey, long revision);

    List<StoredDraftRevision> findHistory(SchemaKey schemaKey, int offset, int limit);

    long countHistory(SchemaKey schemaKey);

    void delete(SchemaKey schemaKey, long expectedRevision);

    ResolvedStoredDraft restore(
            SchemaKey schemaKey,
            long expectedRevision,
            long sourceRevision,
            String definitionJson,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    );

    ResolvedStoredDraft copyCurrent(
            SchemaKey sourceSchemaKey,
            long expectedSourceRevision,
            SchemaKey targetSchemaKey,
            String definitionJson,
            List<DraftReferenceTarget> draftReferences,
            List<StaticReferenceTarget> staticReferences
    );
}
