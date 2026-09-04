package cn.hbads.renderweave.inference.admission;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single application-side gateway trust interface. It validates an exact compact JWS and
 * consumes mutation replay identity before returning any caller identity to the application.
 */
public final class GatewayAssertionAuthority {
    public static final String ASSERTION_VERSION = "renderweave-gateway-assertion/1.0";
    public static final String IDEMPOTENCY_DIGEST_VERSION =
            "renderweave-gateway-idempotency-key/1.0";
    public static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final int MAXIMUM_COMPACT_BYTES = 8 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder()
                            .streamReadConstraints(StreamReadConstraints.builder()
                                    .maxNestingDepth(4)
                                    .maxStringLength(2048)
                                    .maxNameLength(64)
                                    .maxNumberLength(24)
                                    .build())
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final GatewayAssertionKeyResolver keys;
    private final GatewayAssertionReplayStore replayStore;
    private final Clock clock;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final AtomicReference<Instant> latestObservedTime = new AtomicReference<>();
    private final AtomicBoolean timeAuthorityUnavailable = new AtomicBoolean();

    public GatewayAssertionAuthority(
            GatewayAssertionKeyResolver keys,
            GatewayAssertionReplayStore replayStore,
            Clock clock,
            String expectedIssuer,
            String expectedAudience
    ) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.replayStore = Objects.requireNonNull(replayStore, "replayStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expectedIssuer = requireConfiguredIdentity(expectedIssuer, "issuer");
        this.expectedAudience = requireConfiguredIdentity(expectedAudience, "audience");
    }

    public GatewayRequestIdentity authenticate(
            String compactJws,
            GatewayAssertionRequest request
    ) {
        Objects.requireNonNull(request, "request");
        if (compactJws == null || compactJws.isBlank()) {
            throw problem("GATEWAY_ASSERTION_MISSING");
        }
        if (compactJws.length() > MAXIMUM_COMPACT_BYTES
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(compactJws)) {
            throw problem("GATEWAY_ASSERTION_MALFORMED");
        }

        var parts = compactJws.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()
                || parts[0].contains("=") || parts[1].contains("=") || parts[2].contains("=")) {
            throw problem("GATEWAY_ASSERTION_MALFORMED");
        }
        var header = decode(parts[0], GatewayAssertionHeader.class);
        if (!"EdDSA".equals(header.alg()) || !"JWT".equals(header.typ())) {
            throw problem("GATEWAY_ASSERTION_ALGORITHM_UNSUPPORTED");
        }
        requireOpaque(header.kid(), "GATEWAY_ASSERTION_KEY_INVALID");
        var key = keys.resolve(header.kid())
                .orElseThrow(() -> problem("GATEWAY_ASSERTION_KEY_UNKNOWN"));
        requireValidSignature(parts, key);

        var claims = decode(parts[1], GatewayAssertionClaims.class);
        if (!ASSERTION_VERSION.equals(claims.version())
                || !expectedIssuer.equals(claims.iss())
                || !expectedAudience.equals(claims.aud())) {
            throw problem("GATEWAY_ASSERTION_CLAIM_INVALID");
        }
        requireOpaque(claims.sub(), "GATEWAY_ASSERTION_ACTOR_INVALID");
        requireOpaque(claims.requestId(), "GATEWAY_ASSERTION_REQUEST_ID_INVALID");
        requireOpaque(claims.jti(), "GATEWAY_ASSERTION_JTI_INVALID");

        var issuedAt = instant(claims.iat());
        var expiresAt = instant(claims.exp());
        if (!expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            throw problem("GATEWAY_ASSERTION_TTL_EXCEEDED");
        }
        var now = trustedNow();
        if (now.isBefore(issuedAt.minus(CLOCK_SKEW))) {
            throw problem("GATEWAY_ASSERTION_NOT_YET_VALID");
        }
        if (now.isAfter(expiresAt.plus(CLOCK_SKEW))) {
            throw problem("GATEWAY_ASSERTION_EXPIRED");
        }
        if (!request.method().equals(claims.method()) || !request.path().equals(claims.path())) {
            throw problem("GATEWAY_ASSERTION_REQUEST_MISMATCH");
        }

        String idempotencyDigest = null;
        if (request.mutation()) {
            if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                    || request.idempotencyKey().length() > 256) {
                throw problem("GATEWAY_ASSERTION_IDEMPOTENCY_REQUIRED");
            }
            idempotencyDigest = idempotencyKeyDigest(request.idempotencyKey());
            if (!constantEquals(idempotencyDigest, claims.idempotencyKeyDigest())) {
                throw problem("GATEWAY_ASSERTION_IDEMPOTENCY_MISMATCH");
            }
        } else if (claims.idempotencyKeyDigest() != null) {
            throw problem("GATEWAY_ASSERTION_CLAIM_INVALID");
        }

        var identity = new GatewayRequestIdentity(
                claims.sub(), claims.requestId(), claims.jti(), claims.method(), claims.path(),
                idempotencyDigest, issuedAt, expiresAt, header.kid()
        );
        if (request.mutation()) {
            final boolean consumed;
            try {
                consumed = replayStore.consume(identity, now);
            } catch (RuntimeException failure) {
                throw new GatewayAssertionProblem(
                        "GATEWAY_ASSERTION_REPLAY_GUARD_UNAVAILABLE",
                        message("GATEWAY_ASSERTION_REPLAY_GUARD_UNAVAILABLE"), failure
                );
            }
            if (!consumed) {
                throw problem("GATEWAY_ASSERTION_REPLAYED");
            }
        }
        return identity;
    }

    public static String idempotencyKeyDigest(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(IDEMPOTENCY_DIGEST_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Instant trustedNow() {
        if (timeAuthorityUnavailable.get()) {
            throw problem("TIME_AUTHORITY_UNAVAILABLE");
        }
        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException failure) {
            timeAuthorityUnavailable.set(true);
            throw new GatewayAssertionProblem(
                    "TIME_AUTHORITY_UNAVAILABLE", message("TIME_AUTHORITY_UNAVAILABLE"), failure
            );
        }
        while (true) {
            var latest = latestObservedTime.get();
            if (latest != null && now.isBefore(latest.minus(CLOCK_SKEW))) {
                timeAuthorityUnavailable.set(true);
                throw problem("TIME_AUTHORITY_UNAVAILABLE");
            }
            if (latest != null && !now.isAfter(latest)) {
                return now;
            }
            if (latestObservedTime.compareAndSet(latest, now)) {
                return now;
            }
        }
    }

    private void requireValidSignature(String[] parts, java.security.PublicKey key) {
        try {
            if (!"EdDSA".equals(key.getAlgorithm()) && !"Ed25519".equals(key.getAlgorithm())) {
                throw problem("GATEWAY_ASSERTION_KEY_INVALID");
            }
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(decodeBytes(parts[2]))) {
                throw problem("GATEWAY_ASSERTION_SIGNATURE_INVALID");
            }
        } catch (GatewayAssertionProblem problem) {
            throw problem;
        } catch (Exception failure) {
            throw new GatewayAssertionProblem(
                    "GATEWAY_ASSERTION_VERIFICATION_UNAVAILABLE",
                    message("GATEWAY_ASSERTION_VERIFICATION_UNAVAILABLE"), failure
            );
        }
    }

    private static <T> T decode(String value, Class<T> type) {
        try {
            return JSON.readValue(decodeBytes(value), type);
        } catch (Exception failure) {
            throw new GatewayAssertionProblem(
                    "GATEWAY_ASSERTION_MALFORMED", message("GATEWAY_ASSERTION_MALFORMED"), failure
            );
        }
    }

    private static byte[] decodeBytes(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new GatewayAssertionProblem(
                    "GATEWAY_ASSERTION_MALFORMED", message("GATEWAY_ASSERTION_MALFORMED"), failure
            );
        }
    }

    private static Instant instant(long epochSecond) {
        try {
            return Instant.ofEpochSecond(epochSecond);
        } catch (DateTimeException failure) {
            throw new GatewayAssertionProblem(
                    "GATEWAY_ASSERTION_CLAIM_INVALID", message("GATEWAY_ASSERTION_CLAIM_INVALID"), failure
            );
        }
    }

    private static void requireOpaque(String value, String code) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw problem(code);
        }
    }

    private static boolean constantEquals(String left, String right) {
        if (right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static String requireConfiguredIdentity(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Gateway assertion " + label + " is invalid");
        }
        return value;
    }

    private static GatewayAssertionProblem problem(String code) {
        return new GatewayAssertionProblem(code, message(code));
    }

    private static String message(String code) {
        return switch (code) {
            case "GATEWAY_ASSERTION_MISSING" -> "A gateway assertion is required.";
            case "GATEWAY_ASSERTION_REPLAYED" -> "The gateway assertion was already consumed.";
            case "GATEWAY_ASSERTION_EXPIRED" -> "The gateway assertion has expired.";
            case "GATEWAY_ASSERTION_NOT_YET_VALID" -> "The gateway assertion is not yet valid.";
            case "GATEWAY_ASSERTION_TTL_EXCEEDED" -> "The gateway assertion lifetime is invalid.";
            case "GATEWAY_ASSERTION_REQUEST_MISMATCH" -> "The gateway assertion does not bind this request.";
            case "GATEWAY_ASSERTION_IDEMPOTENCY_REQUIRED" -> "A bound idempotency key is required.";
            case "GATEWAY_ASSERTION_IDEMPOTENCY_MISMATCH" -> "The gateway assertion does not bind this mutation.";
            case "GATEWAY_ASSERTION_REPLAY_GUARD_UNAVAILABLE" -> "The gateway replay guard is unavailable.";
            case "GATEWAY_ASSERTION_VERIFICATION_UNAVAILABLE" -> "Gateway assertion verification is unavailable.";
            case "TIME_AUTHORITY_UNAVAILABLE" -> "The trusted UTC time authority is unavailable.";
            default -> "The gateway assertion is invalid.";
        };
    }

    private record GatewayAssertionHeader(String alg, String typ, String kid) { }

    private record GatewayAssertionClaims(
            String version,
            String iss,
            String aud,
            String sub,
            long iat,
            long exp,
            String jti,
            String requestId,
            String method,
            String path,
            String idempotencyKeyDigest
    ) { }
}
