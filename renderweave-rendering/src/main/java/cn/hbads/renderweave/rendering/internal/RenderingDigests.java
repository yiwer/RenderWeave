package cn.hbads.renderweave.rendering.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Rendering domain-separated SHA-256 工具（冻结规格 issue 15 §digest 小节）。
 *
 * <p>全部 SHA-256 wire 统一为 {@code sha256:} + 64 位 lowercase hex。concat 形 domain
 * （以 NUL 结尾）按 {@code UTF8(domain) || payload} 精确拼帧；multi-field framed digest
 * （fingerprint/result）的载荷是 closed 对象的 canonical bytes。
 */
final class RenderingDigests {

    static final String SHA256_PREFIX = "sha256:";

    static final String RENDER_DOCUMENT_DOMAIN = "renderweave-render-document/1\0";
    static final String TEMPLATE_CLOSURE_DOMAIN = "renderweave-template-closure/1\0";
    static final String ADMITTED_INPUT_DOMAIN = "renderweave-admitted-input/1\0";
    static final String CAPABILITY_RESULTS_DOMAIN = "renderweave-capability-results/1\0";
    static final String RANDOM_UNIFORM_DECIMAL_DOMAIN = "renderweave-capability-random-uniform-decimal/1\0";
    static final String EVALUATION_FINGERPRINT_DOMAIN = "renderweave-evaluation-fingerprint/1";
    static final String EVALUATION_RESULT_DOMAIN = "renderweave-evaluation-result/1";

    private RenderingDigests() {
    }

    /** {@code renderDocumentDigest}：覆盖包括 fetchUrl/expiresAt 在内的完整交接字节。 */
    static String renderDocumentDigest(byte[] canonicalRenderDocumentBytes) {
        return digestWithDomain(RENDER_DOCUMENT_DOMAIN, canonicalRenderDocumentBytes);
    }

    /** {@code closureDigest}：载荷为 canonical closure manifest。 */
    static String closureDigest(byte[] canonicalClosureManifest) {
        return digestWithDomain(TEMPLATE_CLOSURE_DOMAIN, canonicalClosureManifest);
    }

    /** {@code admittedInputDigest}：载荷为 canonical typed input。 */
    static String admittedInputDigest(byte[] canonicalTypedInput) {
        return digestWithDomain(ADMITTED_INPUT_DOMAIN, canonicalTypedInput);
    }

    static String digestWithDomain(String domain, byte[] payload) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(payload, "payload");
        var domainBytes = domain.getBytes(StandardCharsets.UTF_8);
        var digest = newMessageDigest();
        digest.update(domainBytes);
        digest.update(payload);
        return SHA256_PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    static String sha256Of(byte[]... parts) {
        var digest = newMessageDigest();
        for (var part : parts) {
            digest.update(Objects.requireNonNull(part, "part"));
        }
        return SHA256_PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    static byte[] hmacSha256(byte[] key, byte[]... parts) {
        Objects.requireNonNull(key, "key");
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            for (var part : parts) {
                mac.update(Objects.requireNonNull(part, "part"));
            }
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 must be available", e);
        }
    }

    private static MessageDigest newMessageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
