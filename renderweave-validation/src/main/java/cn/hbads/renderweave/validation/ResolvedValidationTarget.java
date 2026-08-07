package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.DefinitionReferences;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.SchemaReference;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable request-scoped graph of exact Draft revisions and StaticSchema versions. */
public final class ResolvedValidationTarget {

    private final ResolvedSchemaIdentity rootIdentity;
    private final Map<SchemaKey, ResolvedSchema> drafts;
    private final Map<StaticSchemaRef, ResolvedSchema> statics;
    private final List<ResolvedSchema> orderedSchemas;

    public ResolvedValidationTarget(
            ResolvedSchemaIdentity rootIdentity,
            Map<SchemaKey, ResolvedSchema> drafts,
            Map<StaticSchemaRef, ResolvedSchema> statics
    ) {
        this.rootIdentity = Objects.requireNonNull(rootIdentity, "rootIdentity");
        this.drafts = Collections.unmodifiableMap(new LinkedHashMap<>(drafts));
        this.statics = Collections.unmodifiableMap(new LinkedHashMap<>(statics));
        var root = requireIdentity(rootIdentity);
        var ordered = new java.util.ArrayList<ResolvedSchema>();
        visit(root, new LinkedHashSet<>(), ordered);
        this.orderedSchemas = List.copyOf(ordered);
    }

    public ResolvedSchemaIdentity rootIdentity() {
        return rootIdentity;
    }

    public ResolvedSchema rootSchema() {
        return requireIdentity(rootIdentity);
    }

    public List<ResolvedSchema> orderedSchemas() {
        return orderedSchemas;
    }

    public ResolvedSchema resolve(SchemaReference reference) {
        Objects.requireNonNull(reference, "reference");
        var resolved = reference instanceof SchemaRef draft
                ? drafts.get(draft.schemaKey())
                : statics.get((StaticSchemaRef) reference);
        if (resolved == null) {
            throw new IllegalStateException("Frozen validation graph is missing reference " + reference);
        }
        return resolved;
    }

    private ResolvedSchema requireIdentity(ResolvedSchemaIdentity identity) {
        var resolved = identity instanceof ResolvedSchemaIdentity.DraftIdentity draft
                ? drafts.get(draft.schemaKey())
                : statics.get(((ResolvedSchemaIdentity.StaticIdentity) identity).reference());
        if (resolved == null || !resolved.identity().equals(identity)) {
            throw new IllegalArgumentException("Root identity is absent from the resolved validation graph");
        }
        return resolved;
    }

    private void visit(
            ResolvedSchema schema,
            LinkedHashSet<ResolvedSchemaIdentity> seen,
            java.util.ArrayList<ResolvedSchema> ordered
    ) {
        if (!seen.add(schema.identity())) {
            return;
        }
        ordered.add(schema);
        for (var occurrence : DefinitionReferences.find(schema.definition())) {
            visit(resolve(occurrence.reference()), seen, ordered);
        }
    }
}
