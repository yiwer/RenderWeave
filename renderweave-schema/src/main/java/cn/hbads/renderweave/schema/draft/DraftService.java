package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonWriter;
import cn.hbads.renderweave.schema.definition.DefinitionReferences;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.List;
import java.util.Objects;

/** Validates and normalizes complete definitions before crossing the persistence port. */
public final class DraftService {

    private final DraftStore store;
    private final SchemaDefinitionJsonParser parser;
    private final SchemaDefinitionJsonWriter writer;

    public DraftService(DraftStore store) {
        this(store, new SchemaDefinitionJsonParser(), new SchemaDefinitionJsonWriter());
    }

    DraftService(
            DraftStore store,
            SchemaDefinitionJsonParser parser,
            SchemaDefinitionJsonWriter writer
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public DraftSnapshot create(String rawSchemaKey, String rawDefinitionJson) {
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        var definition = parser.parse(rawDefinitionJson);
        var references = references(definition);
        var stored = store.create(
                schemaKey,
                writer.write(definition),
                CreationSource.USER,
                references.drafts(),
                references.statics()
        );
        return toSnapshot(stored);
    }

    public DraftSnapshot save(String rawSchemaKey, long expectedRevision, String rawDefinitionJson) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        var definition = parser.parse(rawDefinitionJson);
        var references = references(definition);
        var stored = store.save(
                schemaKey,
                expectedRevision,
                writer.write(definition),
                references.drafts(),
                references.statics()
        );
        return toSnapshot(stored);
    }

    public DraftSnapshot get(String rawSchemaKey) {
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        return store.findCurrent(schemaKey)
                .map(this::toSnapshot)
                .orElseThrow(() -> new DraftNotFoundException(schemaKey));
    }

    public DraftPage list(int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        final int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("page is too large", overflow);
        }
        var items = store.findActivePage(offset, size).stream()
                .map(stored -> {
                    var definition = parser.parse(stored.definitionJson());
                    return new DraftSummary(
                            stored.schemaKey(),
                            stored.revision(),
                            stored.creationSource(),
                            definition.displayName(),
                            definition.fields().size(),
                            stored.createdAt(),
                            stored.updatedAt(),
                            stored.savedAt()
                    );
                })
                .toList();
        return new DraftPage(items, page, size, store.countActive());
    }

    public DraftRevisionSnapshot getRevision(String rawSchemaKey, long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        return store.findRevision(schemaKey, revision)
                .map(this::toRevisionSnapshot)
                .orElseThrow(() -> new DraftRevisionNotFoundException(schemaKey, revision));
    }

    public DraftHistoryPage history(String rawSchemaKey, int page, int size) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        var total = store.countHistory(schemaKey);
        if (total == 0) {
            throw new DraftNotFoundException(schemaKey);
        }
        final int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("page is too large", overflow);
        }
        var items = store.findHistory(schemaKey, offset, size).stream()
                .map(this::toRevisionSnapshot)
                .toList();
        return new DraftHistoryPage(items, page, size, total);
    }

    public void delete(String rawSchemaKey, long expectedRevision) {
        requireRevision(expectedRevision, "expectedRevision");
        store.delete(SchemaKey.userProvided(rawSchemaKey), expectedRevision);
    }

    public DraftSnapshot restore(
            String rawSchemaKey,
            long expectedRevision,
            long sourceRevision
    ) {
        requireRevision(expectedRevision, "expectedRevision");
        requireRevision(sourceRevision, "sourceRevision");
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        var source = store.findRevision(schemaKey, sourceRevision)
                .orElseThrow(() -> new DraftRevisionNotFoundException(schemaKey, sourceRevision));
        var definition = parser.parse(source.definitionJson());
        var references = references(definition);
        return toSnapshot(store.restore(
                schemaKey,
                expectedRevision,
                sourceRevision,
                writer.write(definition),
                references.drafts(),
                references.statics()
        ));
    }

    public DraftSnapshot copyCurrent(
            String rawSourceSchemaKey,
            String rawTargetSchemaKey,
            String displayName
    ) {
        var sourceSchemaKey = SchemaKey.userProvided(rawSourceSchemaKey);
        var targetSchemaKey = SchemaKey.userProvided(rawTargetSchemaKey);
        var source = store.findCurrent(sourceSchemaKey)
                .orElseThrow(() -> new DraftNotFoundException(sourceSchemaKey));
        var sourceDefinition = parser.parse(source.draft().definitionJson());
        var renamed = parser.parse(writer.write(new SchemaDefinition(
                sourceDefinition.dslVersion(),
                displayName,
                sourceDefinition.description(),
                sourceDefinition.fields()
        )));
        var references = references(renamed);
        return toSnapshot(store.copyCurrent(
                sourceSchemaKey,
                source.draft().revision(),
                targetSchemaKey,
                writer.write(renamed),
                references.drafts(),
                references.statics()
        ));
    }

    private DraftSnapshot toSnapshot(ResolvedStoredDraft resolved) {
        var stored = resolved.draft();
        return new DraftSnapshot(
                stored.schemaKey(),
                stored.revision(),
                parser.parse(stored.definitionJson()),
                stored.creationSource(),
                stored.createdAt(),
                stored.updatedAt(),
                stored.savedAt(),
                resolved.resolvedRevisions()
        );
    }

    private DraftRevisionSnapshot toRevisionSnapshot(StoredDraftRevision stored) {
        return new DraftRevisionSnapshot(
                stored.schemaKey(),
                stored.revision(),
                parser.parse(stored.definitionJson()),
                stored.savedAt()
        );
    }

    private static ReferenceProjection references(SchemaDefinition definition) {
        var occurrences = DefinitionReferences.find(definition);
        var drafts = occurrences.stream()
                .filter(occurrence -> occurrence.reference() instanceof SchemaRef)
                .map(occurrence -> new DraftReferenceTarget(
                        occurrence.pointer(),
                        occurrence.reference().schemaKey()
                ))
                .toList();
        var statics = occurrences.stream()
                .filter(occurrence -> occurrence.reference() instanceof StaticSchemaRef)
                .map(occurrence -> new StaticReferenceTarget(
                        occurrence.pointer(),
                        (StaticSchemaRef) occurrence.reference()
                ))
                .toList();
        return new ReferenceProjection(drafts, statics);
    }

    private static void requireRevision(long revision, String name) {
        if (revision < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private record ReferenceProjection(
            List<DraftReferenceTarget> drafts,
            List<StaticReferenceTarget> statics
    ) {
    }
}
