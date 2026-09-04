package cn.hbads.renderweave.inference.admission;

import java.time.Instant;

/** Verified opaque identity projected from a GatewayAssertion; the compact token is never retained. */
public record GatewayRequestIdentity(
        String actorId,
        String requestId,
        String jti,
        String method,
        String path,
        String idempotencyKeyDigest,
        Instant issuedAt,
        Instant expiresAt,
        String keyId
) { }
