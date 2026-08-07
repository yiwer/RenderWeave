package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.List;
import java.util.Objects;

public record MaterializedDraftBundle(
        SchemaKey rootSchemaKey,
        List<MaterializedDraft> draftsInCreationOrder
) {
    public MaterializedDraftBundle {
        Objects.requireNonNull(rootSchemaKey, "rootSchemaKey");
        draftsInCreationOrder = List.copyOf(Objects.requireNonNull(
                draftsInCreationOrder, "draftsInCreationOrder"
        ));
        if (draftsInCreationOrder.isEmpty()) {
            throw new IllegalArgumentException("At least one materialized Draft is required");
        }
    }
}
