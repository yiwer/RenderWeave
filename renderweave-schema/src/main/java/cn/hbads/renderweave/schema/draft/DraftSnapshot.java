package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DraftSnapshot(
        SchemaKey schemaKey,
        long revision,
        SchemaDefinition definition,
        CreationSource creationSource,
        Instant createdAt,
        Instant updatedAt,
        Instant savedAt,
        Map<SchemaKey, Long> resolvedRevisions
) {

    public DraftSnapshot {
        Objects.requireNonNull(schemaKey, "schemaKey");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(creationSource, "creationSource");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(savedAt, "savedAt");
        Objects.requireNonNull(resolvedRevisions, "resolvedRevisions");
        resolvedRevisions = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedRevisions));
    }
}
