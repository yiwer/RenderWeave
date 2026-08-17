package cn.hbads.renderweave.app.validation;

import cn.hbads.renderweave.schema.definition.DefinitionReferences;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.draft.DraftNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftStore;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaNotFoundException;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import cn.hbads.renderweave.validation.ValidationTarget;
import cn.hbads.renderweave.validation.ValidationTargetResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds an exact immutable graph after DraftStore has frozen all live Draft revisions. */
@Component
final class PersistedValidationTargetResolver implements ValidationTargetResolver {

    private final DraftStore drafts;
    private final StaticSchemaStore statics;
    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();

    PersistedValidationTargetResolver(DraftStore drafts, StaticSchemaStore statics) {
        this.drafts = drafts;
        this.statics = statics;
    }

    @Override
    public ResolvedValidationTarget resolve(ValidationTarget target) {
        if (target instanceof ValidationTarget.DraftTarget draft) {
            return resolveDraft(draft);
        }
        return resolveStatic((ValidationTarget.StaticTarget) target);
    }

    private ResolvedValidationTarget resolveDraft(ValidationTarget.DraftTarget target) {
        var frozen = drafts.findCurrent(target.schemaKey())
                .orElseThrow(() -> new DraftNotFoundException(target.schemaKey()));
        var resolvedDrafts = new LinkedHashMap<SchemaKey, ResolvedSchema>();
        for (var revision : frozen.resolvedRevisions().entrySet()) {
            var stored = drafts.findRevision(revision.getKey(), revision.getValue())
                    .orElseThrow(() -> new IllegalStateException(
                            "Frozen Draft revision disappeared: "
                                    + revision.getKey().value() + "@" + revision.getValue()
                    ));
            var identity = new ResolvedSchemaIdentity.DraftIdentity(
                    revision.getKey(), revision.getValue()
            );
            resolvedDrafts.put(
                    revision.getKey(),
                    new ResolvedSchema(identity, parser.parse(stored.definitionJson()))
            );
        }

        var resolvedStatics = new LinkedHashMap<StaticSchemaRef, ResolvedSchema>();
        for (var schema : resolvedDrafts.values()) {
            verifyDraftReferences(schema.definition(), resolvedDrafts);
            collectStaticReferences(schema.definition(), resolvedStatics, false);
        }
        var rootIdentity = new ResolvedSchemaIdentity.DraftIdentity(
                frozen.draft().schemaKey(), frozen.draft().revision()
        );
        return new ResolvedValidationTarget(rootIdentity, resolvedDrafts, resolvedStatics);
    }

    private ResolvedValidationTarget resolveStatic(ValidationTarget.StaticTarget target) {
        var root = statics.find(target.reference())
                .orElseThrow(() -> new StaticSchemaNotFoundException(target.reference()));
        var resolvedStatics = new LinkedHashMap<StaticSchemaRef, ResolvedSchema>();
        var definition = parser.parse(root.definitionJson());
        var rootIdentity = new ResolvedSchemaIdentity.StaticIdentity(root.reference());
        resolvedStatics.put(root.reference(), new ResolvedSchema(rootIdentity, definition));
        collectStaticReferences(definition, resolvedStatics, true);
        return new ResolvedValidationTarget(rootIdentity, Map.of(), resolvedStatics);
    }

    private void collectStaticReferences(
            SchemaDefinition definition,
            LinkedHashMap<StaticSchemaRef, ResolvedSchema> resolved,
            boolean staticSource
    ) {
        for (var occurrence : DefinitionReferences.find(definition)) {
            if (occurrence.reference() instanceof SchemaRef) {
                if (staticSource) {
                    throw new IllegalStateException("Stored StaticSchema contains a live Draft reference");
                }
                continue;
            }
            var reference = (StaticSchemaRef) occurrence.reference();
            if (resolved.containsKey(reference)) {
                continue;
            }
            var stored = statics.find(reference)
                    .orElseThrow(() -> new StaticSchemaNotFoundException(reference));
            var childDefinition = parser.parse(stored.definitionJson());
            resolved.put(
                    reference,
                    new ResolvedSchema(new ResolvedSchemaIdentity.StaticIdentity(reference), childDefinition)
            );
            collectStaticReferences(childDefinition, resolved, true);
        }
    }

    private static void verifyDraftReferences(
            SchemaDefinition definition,
            Map<SchemaKey, ResolvedSchema> resolvedDrafts
    ) {
        for (var occurrence : DefinitionReferences.find(definition)) {
            if (occurrence.reference() instanceof SchemaRef draft
                    && !resolvedDrafts.containsKey(draft.schemaKey())) {
                throw new IllegalStateException(
                        "Frozen Draft graph is missing " + draft.schemaKey().value()
                );
            }
        }
    }
}
