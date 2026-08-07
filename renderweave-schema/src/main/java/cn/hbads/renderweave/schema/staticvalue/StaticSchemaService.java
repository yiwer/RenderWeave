package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.compile.CompiledStaticArtifact;
import cn.hbads.renderweave.schema.compile.JsonSchemaCompiler;
import cn.hbads.renderweave.schema.definition.DefinitionReferences;
import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonWriter;
import cn.hbads.renderweave.schema.definition.SchemaProblem;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.draft.CreationSource;
import cn.hbads.renderweave.schema.draft.DraftNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftReferenceTarget;
import cn.hbads.renderweave.schema.draft.DraftRevisionConflictException;
import cn.hbads.renderweave.schema.draft.DraftSnapshot;
import cn.hbads.renderweave.schema.draft.DraftStore;
import cn.hbads.renderweave.schema.draft.ResolvedStoredDraft;
import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class StaticSchemaService {

    private final DraftStore drafts;
    private final StaticSchemaStore statics;
    private final SchemaDefinitionJsonParser parser;
    private final SchemaDefinitionJsonWriter writer;
    private final JsonSchemaCompiler compiler;

    public StaticSchemaService(DraftStore drafts, StaticSchemaStore statics) {
        this(
                drafts,
                statics,
                new SchemaDefinitionJsonParser(),
                new SchemaDefinitionJsonWriter(),
                new JsonSchemaCompiler()
        );
    }

    StaticSchemaService(
            DraftStore drafts,
            StaticSchemaStore statics,
            SchemaDefinitionJsonParser parser,
            SchemaDefinitionJsonWriter writer,
            JsonSchemaCompiler compiler
    ) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.statics = Objects.requireNonNull(statics, "statics");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    public StaticSchemaSnapshot publish(
            String rawSchemaKey,
            long expectedRevision,
            String rawVersionTag,
            String rawReleaseNote
    ) {
        requireRevision(expectedRevision);
        var schemaKey = SchemaKey.userProvided(rawSchemaKey);
        var versionTag = VersionTag.of(rawVersionTag);
        var identity = new StaticSchemaRef(schemaKey, versionTag);
        var current = drafts.findCurrent(schemaKey)
                .orElseThrow(() -> new DraftNotFoundException(schemaKey));
        if (current.draft().revision() != expectedRevision) {
            throw new DraftRevisionConflictException(
                    schemaKey,
                    expectedRevision,
                    current.draft().revision()
            );
        }

        var definition = parser.parse(current.draft().definitionJson());
        var references = staticReferencesForPublication(definition);
        var resolved = resolveStaticReferences(references);
        var referenceDepth = 1 + resolved.values().stream()
                .mapToInt(StoredStaticSchema::referenceDepth)
                .max()
                .orElse(0);
        if (referenceDepth > 16) {
            var pointer = references.isEmpty() ? "" : references.getFirst().pointer();
            throw new InvalidSchemaDefinitionException(List.of(new SchemaProblem(
                    "STATIC_REFERENCE_DEPTH_EXCEEDED",
                    pointer,
                    "StaticSchema reference depth " + referenceDepth + " exceeds maximum 16"
            )));
        }

        var compiled = compiler.compile(
                identity,
                definition,
                reference -> {
                    var child = resolved.get(reference);
                    if (child == null) {
                        throw new IllegalStateException("Pre-resolved StaticSchema disappeared: " + reference);
                    }
                    return new CompiledStaticArtifact(reference, child.compiledJsonSchema());
                }
        );
        var stored = statics.publish(new PublishStaticSchema(
                schemaKey,
                expectedRevision,
                versionTag,
                writer.write(definition),
                compiled.json(),
                compiled.compilerVersion(),
                normalizeReleaseNote(rawReleaseNote),
                references,
                referenceDepth
        ));
        return toSnapshot(stored);
    }

    public StaticSchemaSnapshot get(String rawSchemaKey, String rawVersionTag) {
        var reference = reference(rawSchemaKey, rawVersionTag);
        return statics.find(reference)
                .map(this::toSnapshot)
                .orElseThrow(() -> new StaticSchemaNotFoundException(reference));
    }

    public StaticSchemaPage list(int page, int size) {
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
        var items = statics.findPage(offset, size).stream()
                .map(stored -> {
                    var definition = parser.parse(stored.definitionJson());
                    return new StaticSchemaSummary(
                            stored.reference(),
                            stored.origin(),
                            definition.displayName(),
                            definition.fields().size(),
                            stored.referenceDepth(),
                            stored.publishedAt()
                    );
                })
                .toList();
        return new StaticSchemaPage(items, page, size, statics.count());
    }

    public DraftSnapshot copyToDraft(
            String rawSourceSchemaKey,
            String rawVersionTag,
            String rawTargetSchemaKey,
            String displayName
    ) {
        var source = get(rawSourceSchemaKey, rawVersionTag);
        var targetSchemaKey = SchemaKey.userProvided(rawTargetSchemaKey);
        var renamed = parser.parse(writer.write(new SchemaDefinition(
                source.definition().dslVersion(),
                displayName,
                source.definition().description(),
                source.definition().fields()
        )));
        var references = references(renamed);
        var created = drafts.create(
                targetSchemaKey,
                writer.write(renamed),
                CreationSource.USER,
                references.drafts(),
                references.statics()
        );
        return toDraftSnapshot(created);
    }

    private List<StaticReferenceTarget> staticReferencesForPublication(SchemaDefinition definition) {
        var occurrences = DefinitionReferences.find(definition);
        var liveProblems = occurrences.stream()
                .filter(occurrence -> occurrence.reference() instanceof SchemaRef)
                .map(occurrence -> new SchemaProblem(
                        "DRAFT_REFERENCE_NOT_PUBLISHABLE",
                        occurrence.pointer(),
                        "Replace the live Draft reference with an exact StaticSchemaRef before publishing"
                ))
                .toList();
        if (!liveProblems.isEmpty()) {
            throw new InvalidSchemaDefinitionException(liveProblems);
        }
        return occurrences.stream()
                .map(occurrence -> new StaticReferenceTarget(
                        occurrence.pointer(),
                        (StaticSchemaRef) occurrence.reference()
                ))
                .toList();
    }

    private LinkedHashMap<StaticSchemaRef, StoredStaticSchema> resolveStaticReferences(
            List<StaticReferenceTarget> references
    ) {
        var resolved = new LinkedHashMap<StaticSchemaRef, StoredStaticSchema>();
        var problems = new java.util.ArrayList<SchemaProblem>();
        for (var occurrence : references) {
            if (resolved.containsKey(occurrence.reference())) {
                continue;
            }
            var stored = statics.find(occurrence.reference());
            if (stored.isEmpty()) {
                problems.add(new SchemaProblem(
                        "STATIC_SCHEMA_REFERENCE_NOT_FOUND",
                        occurrence.pointer(),
                        "Referenced StaticSchema does not exist: "
                                + occurrence.reference().schemaKey().value()
                                + "@" + occurrence.reference().versionTag().value()
                ));
            } else {
                resolved.put(occurrence.reference(), stored.orElseThrow());
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidSchemaDefinitionException(problems);
        }
        return resolved;
    }

    private StaticSchemaSnapshot toSnapshot(StoredStaticSchema stored) {
        return new StaticSchemaSnapshot(
                stored.reference(),
                stored.origin(),
                stored.sourceDraftRevision(),
                parser.parse(stored.definitionJson()),
                stored.compiledJsonSchema(),
                stored.compilerVersion(),
                stored.releaseNote(),
                stored.referenceDepth(),
                stored.publishedAt()
        );
    }

    private DraftSnapshot toDraftSnapshot(ResolvedStoredDraft resolved) {
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

    private static ReferenceProjection references(SchemaDefinition definition) {
        var occurrences = DefinitionReferences.find(definition);
        var draftReferences = occurrences.stream()
                .filter(occurrence -> occurrence.reference() instanceof SchemaRef)
                .map(occurrence -> new DraftReferenceTarget(
                        occurrence.pointer(),
                        occurrence.reference().schemaKey()
                ))
                .toList();
        var staticReferences = occurrences.stream()
                .filter(occurrence -> occurrence.reference() instanceof StaticSchemaRef)
                .map(occurrence -> new StaticReferenceTarget(
                        occurrence.pointer(),
                        (StaticSchemaRef) occurrence.reference()
                ))
                .toList();
        return new ReferenceProjection(draftReferences, staticReferences);
    }

    private static StaticSchemaRef reference(String rawSchemaKey, String rawVersionTag) {
        var schemaKey = rawSchemaKey != null && rawSchemaKey.startsWith("system-")
                ? SchemaKey.systemProvided(rawSchemaKey)
                : SchemaKey.userProvided(rawSchemaKey);
        return new StaticSchemaRef(schemaKey, VersionTag.of(rawVersionTag));
    }

    private static Optional<String> normalizeReleaseNote(String rawReleaseNote) {
        if (rawReleaseNote == null) {
            return Optional.empty();
        }
        var normalized = rawReleaseNote.strip();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    private static void requireRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    private record ReferenceProjection(
            List<DraftReferenceTarget> drafts,
            List<StaticReferenceTarget> statics
    ) {
    }
}
