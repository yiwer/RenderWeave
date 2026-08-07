package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A stored Draft plus the request-scoped, root-inclusive live-reference snapshot. */
public record ResolvedStoredDraft(
        StoredDraft draft,
        Map<SchemaKey, Long> resolvedRevisions
) {

    public ResolvedStoredDraft {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(resolvedRevisions, "resolvedRevisions");
        resolvedRevisions = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedRevisions));
    }
}
