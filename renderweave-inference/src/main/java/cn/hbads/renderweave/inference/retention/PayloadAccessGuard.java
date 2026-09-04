package cn.hbads.renderweave.inference.retention;

import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface PayloadAccessGuard {
    void require(UUID runId, PayloadAccess access);

    static PayloadAccessGuard allowAll() {
        return (runId, access) -> {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(access, "access");
        };
    }
}
