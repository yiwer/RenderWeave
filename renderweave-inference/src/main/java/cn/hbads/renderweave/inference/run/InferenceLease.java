package cn.hbads.renderweave.inference.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InferenceLease(UUID token, String owner, Instant expiresAt) {
    public InferenceLease {
        Objects.requireNonNull(token, "token");
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("lease owner is required");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
