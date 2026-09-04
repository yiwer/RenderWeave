package cn.hbads.renderweave.inference;

import java.time.Instant;
import java.util.Objects;

/** PostgreSQL authority for one encrypted inference artifact; no key material or plaintext is retained. */
record ArtifactEnvelope(
        String artifactId,
        String envelopeVersion,
        String payloadAlgorithm,
        String wrappingAlgorithm,
        String ciphertextLocator,
        String ciphertextSha256,
        byte[] payloadNonce,
        byte[] payloadTag,
        String kekId,
        byte[] wrappedDek,
        byte[] wrappingNonce,
        byte[] wrappingTag,
        Instant createdAt,
        Instant rewrappedAt
) {
    static final String VERSION = "renderweave-artifact-envelope/1.0";
    static final String ALGORITHM = "AES-256-GCM";

    ArtifactEnvelope {
        artifactId = requireSha(artifactId, "artifactId");
        if (!VERSION.equals(envelopeVersion)) throw new IllegalArgumentException("Unsupported envelope version");
        if (!ALGORITHM.equals(payloadAlgorithm) || !ALGORITHM.equals(wrappingAlgorithm)) {
            throw new IllegalArgumentException("Unsupported envelope algorithm");
        }
        if (!artifactId.equals(ciphertextLocator)) {
            throw new IllegalArgumentException("Ciphertext locator must remain the opaque artifact identity");
        }
        ciphertextSha256 = requireSha(ciphertextSha256, "ciphertextSha256");
        payloadNonce = requireBytes(payloadNonce, 12, "payloadNonce");
        payloadTag = requireBytes(payloadTag, 16, "payloadTag");
        kekId = requireKeyId(kekId);
        wrappedDek = requireBytes(wrappedDek, 32, "wrappedDek");
        wrappingNonce = requireBytes(wrappingNonce, 12, "wrappingNonce");
        wrappingTag = requireBytes(wrappingTag, 16, "wrappingTag");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(rewrappedAt, "rewrappedAt");
        if (rewrappedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("rewrappedAt must not precede createdAt");
        }
    }

    ArtifactEnvelope rewrapped(
            String targetKekId,
            byte[] targetWrappedDek,
            byte[] targetWrappingNonce,
            byte[] targetWrappingTag,
            Instant at
    ) {
        return new ArtifactEnvelope(
                artifactId, envelopeVersion, payloadAlgorithm, wrappingAlgorithm,
                ciphertextLocator, ciphertextSha256, payloadNonce, payloadTag,
                targetKekId, targetWrappedDek, targetWrappingNonce, targetWrappingTag,
                createdAt, at
        );
    }

    @Override
    public byte[] payloadNonce() { return payloadNonce.clone(); }

    @Override
    public byte[] payloadTag() { return payloadTag.clone(); }

    @Override
    public byte[] wrappedDek() { return wrappedDek.clone(); }

    @Override
    public byte[] wrappingNonce() { return wrappingNonce.clone(); }

    @Override
    public byte[] wrappingTag() { return wrappingTag.clone(); }

    static String requireKeyId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("KEK id is invalid");
        }
        return value;
    }

    private static String requireSha(String value, String name) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return value;
    }

    private static byte[] requireBytes(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must contain exactly " + length + " bytes");
        }
        return value.clone();
    }
}
