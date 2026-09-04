package cn.hbads.renderweave.inference.admission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayAssertionAuthorityTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private static final String ISSUER = "https://gateway.renderweave.internal";
    private static final String AUDIENCE = "renderweave-api";
    private static final String KEY_ID = "gateway-2026-08-a";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private KeyPair keys;
    private MutableClock clock;
    private HashSet<String> consumed;
    private GatewayAssertionAuthority authority;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        clock = new MutableClock(T0);
        consumed = new HashSet<>();
        authority = new GatewayAssertionAuthority(
                keyId -> KEY_ID.equals(keyId) ? Optional.of(keys.getPublic()) : Optional.empty(),
                (identity, acceptedAt) -> consumed.add(identity.jti()),
                clock, ISSUER, AUDIENCE
        );
    }

    @Test
    void verifiesExactMutationAndConsumesItsJtiOnce() throws Exception {
        var request = new GatewayAssertionRequest(
                "POST", "/api/v1/inference-runs/live", "idem-001"
        );
        var token = token(claims(
                "jti-001", "POST", request.path(), request.idempotencyKey(), T0, T0.plusSeconds(60)
        ));

        var identity = authority.authenticate(token, request);

        assertEquals("actor-opaque-001", identity.actorId());
        assertEquals("request-opaque-001", identity.requestId());
        assertEquals(GatewayAssertionAuthority.idempotencyKeyDigest("idem-001"),
                identity.idempotencyKeyDigest());
        assertEquals(Set.of("jti-001"), consumed);
        assertCode("GATEWAY_ASSERTION_REPLAYED", () -> authority.authenticate(token, request));
    }

    @Test
    void requestAndIdempotencyBindingsFailClosedBeforeReplayConsumption() throws Exception {
        var token = token(claims(
                "jti-binding", "POST", "/api/v1/inference-runs/live", "idem-expected",
                T0, T0.plusSeconds(60)
        ));

        assertCode("GATEWAY_ASSERTION_REQUEST_MISMATCH", () -> authority.authenticate(
                token, new GatewayAssertionRequest(
                        "PUT", "/api/v1/inference-runs/live", "idem-expected"
                )
        ));
        assertCode("GATEWAY_ASSERTION_REQUEST_MISMATCH", () -> authority.authenticate(
                token, new GatewayAssertionRequest(
                        "POST", "/api/v1/inference-runs/other", "idem-expected"
                )
        ));
        assertCode("GATEWAY_ASSERTION_IDEMPOTENCY_MISMATCH", () -> authority.authenticate(
                token, new GatewayAssertionRequest(
                        "POST", "/api/v1/inference-runs/live", "idem-drift"
                )
        ));
        assertTrue(consumed.isEmpty());
    }

    @Test
    void temporalEnvelopeIsExactlySixtySecondsWithThirtySecondAssertionSkew() throws Exception {
        assertCode("GATEWAY_ASSERTION_TTL_EXCEEDED", () -> authority.authenticate(
                token(claims("jti-ttl", "GET", "/api/v1/inference-runs", null,
                        T0, T0.plusSeconds(61))),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));
        assertCode("GATEWAY_ASSERTION_NOT_YET_VALID", () -> authority.authenticate(
                token(claims("jti-future", "GET", "/api/v1/inference-runs", null,
                        T0.plusSeconds(31), T0.plusSeconds(60))),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));
        assertCode("GATEWAY_ASSERTION_EXPIRED", () -> authority.authenticate(
                token(claims("jti-expired", "GET", "/api/v1/inference-runs", null,
                        T0.minusSeconds(91), T0.minusSeconds(31))),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));
    }

    @Test
    void algorithmSignatureUnknownClaimsAndUnknownKidAreRejected() throws Exception {
        var values = claims("jti-negative", "GET", "/api/v1/inference-runs", null,
                T0, T0.plusSeconds(60));
        assertCode("GATEWAY_ASSERTION_ALGORITHM_UNSUPPORTED", () -> authority.authenticate(
                token(values, Map.of("alg", "none", "typ", "JWT", "kid", KEY_ID), keys),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));

        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertCode("GATEWAY_ASSERTION_SIGNATURE_INVALID", () -> authority.authenticate(
                token(values, header(), other),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));

        assertCode("GATEWAY_ASSERTION_KEY_UNKNOWN", () -> authority.authenticate(
                token(values, Map.of("alg", "EdDSA", "typ", "JWT", "kid", "unknown"), keys),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));

        var unknownClaim = new LinkedHashMap<String, Object>(values);
        unknownClaim.put("email", "forbidden@example.invalid");
        assertCode("GATEWAY_ASSERTION_MALFORMED", () -> authority.authenticate(
                token(unknownClaim),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));
    }

    @Test
    void utcRollbackLatchesTimeAuthorityUnavailable() throws Exception {
        authority.authenticate(
                token(claims("jti-read-1", "GET", "/api/v1/inference-runs", null,
                        T0, T0.plusSeconds(60))),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        );
        clock.set(T0.minusSeconds(31));
        var rolledBackToken = token(claims(
                "jti-read-2", "GET", "/api/v1/inference-runs", null,
                T0.minusSeconds(31), T0.plusSeconds(29)
        ));

        assertCode("TIME_AUTHORITY_UNAVAILABLE", () -> authority.authenticate(
                rolledBackToken, new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));
        clock.set(T0.plusSeconds(1));
        assertCode("TIME_AUTHORITY_UNAVAILABLE", () -> authority.authenticate(
                token(claims("jti-read-3", "GET", "/api/v1/inference-runs", null,
                        T0, T0.plusSeconds(60))),
                new GatewayAssertionRequest("GET", "/api/v1/inference-runs", null)
        ));
    }

    @Test
    void replayStoreFailureIsUnavailableAndNeverReturnsAnIdentity() throws Exception {
        var failing = new GatewayAssertionAuthority(
                keyId -> Optional.of(keys.getPublic()),
                (identity, acceptedAt) -> { throw new IllegalStateException("database unavailable"); },
                clock, ISSUER, AUDIENCE
        );
        var request = new GatewayAssertionRequest("POST", "/api/v1/test", "idem-store");

        assertCode("GATEWAY_ASSERTION_REPLAY_GUARD_UNAVAILABLE", () -> failing.authenticate(
                token(claims("jti-store", "POST", request.path(), request.idempotencyKey(),
                        T0, T0.plusSeconds(60))), request
        ));
    }

    private LinkedHashMap<String, Object> claims(
            String jti,
            String method,
            String path,
            String idempotencyKey,
            Instant issuedAt,
            Instant expiresAt
    ) {
        var values = new LinkedHashMap<String, Object>();
        values.put("version", GatewayAssertionAuthority.ASSERTION_VERSION);
        values.put("iss", ISSUER);
        values.put("aud", AUDIENCE);
        values.put("sub", "actor-opaque-001");
        values.put("iat", issuedAt.getEpochSecond());
        values.put("exp", expiresAt.getEpochSecond());
        values.put("jti", jti);
        values.put("requestId", "request-opaque-001");
        values.put("method", method);
        values.put("path", path);
        if (idempotencyKey != null) {
            values.put("idempotencyKeyDigest",
                    GatewayAssertionAuthority.idempotencyKeyDigest(idempotencyKey));
        }
        return values;
    }

    private Map<String, String> header() {
        return Map.of("alg", "EdDSA", "typ", "JWT", "kid", KEY_ID);
    }

    private String token(Map<String, Object> claims) throws Exception {
        return token(claims, header(), keys);
    }

    private String token(
            Map<String, Object> claims,
            Map<String, String> header,
            KeyPair signingKeys
    ) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var encodedHeader = encoder.encodeToString(JSON.writeValueAsBytes(header));
        var encodedClaims = encoder.encodeToString(JSON.writeValueAsBytes(claims));
        var input = encodedHeader + "." + encodedClaims;
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(signingKeys.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encoder.encodeToString(signature.sign());
    }

    private static void assertCode(String code, ThrowingCall call) {
        var problem = assertThrows(GatewayAssertionProblem.class, call::run);
        assertEquals(code, problem.code());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void set(Instant value) {
            current = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
