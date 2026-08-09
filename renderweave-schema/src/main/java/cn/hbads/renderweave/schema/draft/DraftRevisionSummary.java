package cn.hbads.renderweave.schema.draft;

import java.time.Instant;
import java.util.Objects;

public record DraftRevisionSummary(
        long revision,
        String displayName,
        int fieldCount,
        Instant savedAt
) {
    public DraftRevisionSummary {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(savedAt, "savedAt");
        if (revision < 0 || fieldCount < 0 || fieldCount > 256) {
            throw new IllegalArgumentException("Draft revision summary counts are outside the supported range");
        }
    }
}
