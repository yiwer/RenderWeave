package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.schema.draft.DraftReferenceTarget;
import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.List;
import java.util.Objects;

public record MaterializedDraft(
        SchemaKey schemaKey,
        String definitionJson,
        List<DraftReferenceTarget> draftReferences,
        List<StaticReferenceTarget> staticReferences
) {
    public MaterializedDraft {
        Objects.requireNonNull(schemaKey, "schemaKey");
        if (definitionJson == null || definitionJson.isBlank()) {
            throw new IllegalArgumentException("definitionJson is required");
        }
        draftReferences = List.copyOf(Objects.requireNonNull(draftReferences, "draftReferences"));
        staticReferences = List.copyOf(Objects.requireNonNull(staticReferences, "staticReferences"));
    }
}
