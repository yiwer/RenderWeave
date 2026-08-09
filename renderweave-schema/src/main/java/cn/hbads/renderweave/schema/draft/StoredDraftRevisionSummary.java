package cn.hbads.renderweave.schema.draft;

import java.time.Instant;
import java.util.Objects;

/** Persistence projection for revision history rows; full DSL is fetched by exact revision. */
public record StoredDraftRevisionSummary(
        long revision,
        String displayName,
        int fieldCount,
        Instant savedAt
) {
    public StoredDraftRevisionSummary {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(savedAt, "savedAt");
        if (revision < 0 || fieldCount < 0 || fieldCount > 256) {
            throw new IllegalArgumentException("Draft revision summary counts are outside the supported range");
        }
    }
}
