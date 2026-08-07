package cn.hbads.renderweave.inference.candidate;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Deterministic opaque identities scoped to one inference run; never materialized as formal field IDs. */
public final class CandidateIds {
    private CandidateIds() { }

    public static UUID schema(UUID runId, String localPath) {
        return opaque(runId, "schema", localPath);
    }

    public static UUID field(UUID runId, String schemaPath, String fieldKey) {
        return opaque(runId, "field", schemaPath + "\u0000" + fieldKey);
    }

    private static UUID opaque(UUID runId, String kind, String localPath) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(localPath, "localPath");
        return UUID.nameUUIDFromBytes(
                ("renderweave-candidate/1\u0000" + runId + "\u0000" + kind + "\u0000" + localPath)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }
}
