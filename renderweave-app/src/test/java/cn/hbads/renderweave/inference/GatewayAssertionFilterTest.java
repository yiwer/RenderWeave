package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.GatewayAssertionAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAssertionFilterTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private static final String PATH = "/api/v1/inference-runs";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private KeyPair keys;
    private GatewayAssertionAuthority authority;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        authority = new GatewayAssertionAuthority(
                keyId -> "gateway-key-a".equals(keyId)
                        ? Optional.of(keys.getPublic()) : Optional.empty(),
                (identity, acceptedAt) -> true,
                Clock.fixed(T0, ZoneOffset.UTC), "renderweave-gateway", "renderweave-api"
        );
    }

    @Test
    void missingAssertionIsAStaticNoStoreProblem() throws Exception {
        var filter = new GatewayAssertionFilter(
                authority, new ClientCertificateGate(java.util.Set.of()), false, JSON
        );
        var request = new MockHttpServletRequest("GET", PATH);
        var response = new MockHttpServletResponse();
        var delegated = new AtomicBoolean();

        filter.doFilter(request, response, (source, target) -> delegated.set(true));

        assertThat(delegated).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("GATEWAY_ASSERTION_MISSING")
                .doesNotContain("actor-opaque", "gateway-key-a");
    }

    @Test
    void verifiedIdentityIsRequestScopedAndCompactTokenIsNotProjected() throws Exception {
        var filter = new GatewayAssertionFilter(
                authority, new ClientCertificateGate(java.util.Set.of()), false, JSON
        );
        var request = new MockHttpServletRequest("GET", PATH);
        request.addHeader(GatewayAssertionFilter.ASSERTION_HEADER, token("jti-read"));
        var response = new MockHttpServletResponse();
        var delegated = new AtomicBoolean();

        filter.doFilter(request, response, (source, target) -> {
            delegated.set(true);
            assertThat(((HttpServletRequest) source).getAttribute(
                    GatewayAssertionFilter.IDENTITY_ATTRIBUTE)).isNotNull();
        });

        assertThat(delegated).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(request.getAttributeNames().asIterator())
                .toIterable()
                .noneMatch(name -> name.toLowerCase().contains("token"));
    }

    @Test
    void exactLeafCertificateFingerprintCanBeRequiredIndependently() throws Exception {
        var encodedCertificate = new byte[] {1, 3, 3, 7};
        var fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(encodedCertificate)
        );
        var filter = new GatewayAssertionFilter(
                authority, new ClientCertificateGate(java.util.Set.of(fingerprint)), true, JSON
        );
        var missing = new MockHttpServletRequest("GET", PATH);
        missing.addHeader(GatewayAssertionFilter.ASSERTION_HEADER, token("jti-missing-cert"));
        var denied = new MockHttpServletResponse();
        filter.doFilter(missing, denied, (source, target) -> { });
        assertThat(denied.getStatus()).isEqualTo(403);

        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(encodedCertificate);
        var exact = new MockHttpServletRequest("GET", PATH);
        exact.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {certificate});
        exact.addHeader(GatewayAssertionFilter.ASSERTION_HEADER, token("jti-exact-cert"));
        var accepted = new MockHttpServletResponse();
        var delegated = new AtomicBoolean();

        filter.doFilter(exact, accepted, (source, target) -> delegated.set(true));

        assertThat(delegated).isTrue();
    }

    @Test
    void actuatorFilterNeverDelegatesWithoutExactOperationsCertificate() throws Exception {
        var filter = new InternalActuatorMtlsFilter(
                new ClientCertificateGate(java.util.Set.of()), JSON
        );
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        var response = new MockHttpServletResponse();
        var delegated = new AtomicBoolean();

        filter.doFilter(request, response, (source, target) -> delegated.set(true));

        assertThat(delegated).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACTUATOR_MTLS_IDENTITY_INVALID");
    }

    private String token(String jti) throws Exception {
        var header = Map.of("alg", "EdDSA", "typ", "JWT", "kid", "gateway-key-a");
        var claims = new LinkedHashMap<String, Object>();
        claims.put("version", GatewayAssertionAuthority.ASSERTION_VERSION);
        claims.put("iss", "renderweave-gateway");
        claims.put("aud", "renderweave-api");
        claims.put("sub", "actor-opaque-001");
        claims.put("iat", T0.getEpochSecond());
        claims.put("exp", T0.plusSeconds(60).getEpochSecond());
        claims.put("jti", jti);
        claims.put("requestId", "request-opaque-001");
        claims.put("method", "GET");
        claims.put("path", PATH);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var encodedHeader = encoder.encodeToString(JSON.writeValueAsBytes(header));
        var encodedClaims = encoder.encodeToString(JSON.writeValueAsBytes(claims));
        var input = encodedHeader + "." + encodedClaims;
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encoder.encodeToString(signature.sign());
    }
}
