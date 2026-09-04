package cn.hbads.renderweave.inference.admission;

import java.time.Instant;

/** Atomically records a mutation jti; false means it was already consumed. */
@FunctionalInterface
public interface GatewayAssertionReplayStore {
    boolean consume(GatewayRequestIdentity identity, Instant acceptedAt);
}
